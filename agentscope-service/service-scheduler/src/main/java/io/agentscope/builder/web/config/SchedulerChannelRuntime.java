/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.web.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.config.ChannelConfigEntry;
import io.agentscope.builder.runtime.config.ChannelTypeRegistry;
import io.agentscope.extensions.channel.dingtalk.DingTalkChannel;
import io.agentscope.extensions.channel.feishu.FeishuChannel;
import io.agentscope.extensions.channel.github.GitHubChannel;
import io.agentscope.extensions.channel.gitlab.GitLabChannel;
import io.agentscope.extensions.channel.wecom.WeComChannel;
import io.agentscope.extensions.channel.weixin.WeixinChannel;
import io.agentscope.extensions.channel.weixin.WeixinLease;
import io.agentscope.extensions.channel.weixin.WeixinRuntimeListener;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scheduler channel runtime: owns the live set of IM channel adapters, refreshes config from the
 * control plane on an interval, and reports per-channel started/error status.
 */
@Component
public class SchedulerChannelRuntime implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SchedulerChannelRuntime.class);

    private static final TypeReference<Map<String, ChannelConfigEntry>> CHANNEL_CONFIG_MAP =
            new TypeReference<>() {};

    private static final AtomicBoolean FACTORIES_REGISTERED = new AtomicBoolean(false);

    private final ChannelManager channelManager;
    private final Gateway gateway;
    private final WebClient controlPlane;
    private final ObjectMapper objectMapper;
    private final ChannelRuntimeCatalog catalog;
    private final io.agentscope.extensions.channel.weixin.WeixinStateStore weixinStateStore;
    private final int configFetchRetries;
    private final long configFetchBackoffMs;
    private final long refreshIntervalMs;

    private final ScheduledExecutorService refreshExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "channel-config-refresh");
                        t.setDaemon(true);
                        return t;
                    });

    private final Map<String, String> lastConfigFingerprints = new HashMap<>();
    private final Map<String, String> lastErrors = new HashMap<>();
    private final Map<String, RuntimeObservation> runtimeObservations = new HashMap<>();
    private final Map<String, Object> runtimeListenerTokens = new HashMap<>();

    private volatile boolean running;
    private ScheduledFuture<?> refreshTask;

    public SchedulerChannelRuntime(
            ChannelManager channelManager,
            Gateway gateway,
            @Qualifier("controlPlaneWebClient") WebClient controlPlane,
            ObjectMapper objectMapper,
            ChannelRuntimeCatalog catalog,
            io.agentscope.extensions.channel.weixin.WeixinStateStore weixinStateStore,
            @Value("${builder.scheduler.channel-config-retries:12}") int configFetchRetries,
            @Value("${builder.scheduler.channel-config-backoff-ms:5000}") long backoffMs,
            @Value("${builder.scheduler.channel-refresh-ms:15000}") long refreshIntervalMs) {
        this.channelManager = channelManager;
        this.gateway = gateway;
        this.controlPlane = controlPlane;
        this.objectMapper = objectMapper;
        this.catalog = catalog;
        this.weixinStateStore = weixinStateStore;
        this.configFetchRetries = configFetchRetries;
        this.configFetchBackoffMs = backoffMs;
        this.refreshIntervalMs = refreshIntervalMs;
    }

    /** Registers the bundled channel factories exactly once per JVM. */
    static void registerChannelFactories() {
        if (!FACTORIES_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        ChannelTypeRegistry.register(DingTalkChannel.TYPE, DingTalkChannel::fromProperties);
        ChannelTypeRegistry.register(FeishuChannel.TYPE, FeishuChannel::fromProperties);
        ChannelTypeRegistry.register(WeComChannel.TYPE, WeComChannel::fromProperties);
        ChannelTypeRegistry.register(GitHubChannel.TYPE, GitHubChannel::fromProperties);
        ChannelTypeRegistry.register(GitLabChannel.TYPE, GitLabChannel::fromProperties);
        ChannelTypeRegistry.register(WeixinChannel.TYPE, WeixinChannel::fromProperties);
        log.info("Registered channel factories: {}", ChannelTypeRegistry.registeredTypes());
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        registerChannelFactories();
        reconcile(fetchChannelConfigWithRetry());
        running = true;
        if (refreshIntervalMs > 0) {
            refreshTask =
                    refreshExecutor.scheduleWithFixedDelay(
                            this::refreshSafely,
                            refreshIntervalMs,
                            refreshIntervalMs,
                            TimeUnit.MILLISECONDS);
        }
        log.info(
                "Scheduler channel runtime started: {} channel(s) active",
                channelManager.channelIds().size());
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        runtimeListenerTokens.clear();
        runtimeObservations.replaceAll(
                (id, prior) ->
                        new RuntimeObservation(
                                false, prior.errorCode(), prior.lease(), prior.sequence() + 1));
        if (refreshTask != null) {
            refreshTask.cancel(false);
            refreshTask = null;
        }
        try {
            channelManager.stopAll();
        } catch (Exception ex) {
            log.warn("Channel stopAll failed: {}", ex.getMessage());
        }
        channelManager.channelIds().forEach(channelManager::unregister);
        lastConfigFingerprints.clear();
        lastErrors.clear();
        reportRuntimeStatus();
        runtimeObservations.clear();
        catalog.replaceAll(Map.of());
        running = false;
        log.info("Scheduler channel runtime stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void refreshSafely() {
        try {
            Map<String, ChannelConfigEntry> configs = fetchChannelConfigOnce();
            if (configs != null) {
                reconcile(configs);
            }
        } catch (Exception ex) {
            log.warn("Channel config refresh failed: {}", ex.getMessage());
        }
    }

    /**
     * Diffs desired config against live adapters: starts new/changed channels, stops removed ones,
     * then reports runtime status to the control plane.
     */
    private synchronized void reconcile(Map<String, ChannelConfigEntry> desired) {
        if (desired == null) {
            desired = Map.of();
        }
        catalog.replaceAll(desired);

        Set<String> desiredIds = new HashSet<>(desired.keySet());
        for (String liveId : new ArrayList<>(channelManager.channelIds())) {
            if (!desiredIds.contains(liveId)) {
                channelManager.unregister(liveId);
                lastConfigFingerprints.remove(liveId);
                lastErrors.remove(liveId);
                runtimeObservations.remove(liveId);
                runtimeListenerTokens.remove(liveId);
                log.info("Channel '{}' removed (no longer in control-plane config)", liveId);
            }
        }

        for (Map.Entry<String, ChannelConfigEntry> e : desired.entrySet()) {
            String channelId = e.getKey();
            ChannelConfigEntry entry = e.getValue();
            if (entry != null && Boolean.TRUE.equals(entry.getDisabled())) {
                channelManager.unregister(channelId);
                lastConfigFingerprints.remove(channelId);
                lastErrors.remove(channelId);
                runtimeObservations.remove(channelId);
                runtimeListenerTokens.remove(channelId);
                continue;
            }
            String fingerprint = fingerprint(entry);
            if (Objects.equals(fingerprint, lastConfigFingerprints.get(channelId))
                    && channelManager.getChannel(channelId).isPresent()) {
                continue;
            }
            channelManager.unregister(channelId);
            runtimeListenerTokens.remove(channelId);
            lastErrors.remove(channelId);
            Channel channel = buildChannel(channelId, entry);
            if (channel == null) {
                lastConfigFingerprints.remove(channelId);
                runtimeListenerTokens.remove(channelId);
                // Keep the specific reason buildChannel recorded; the generic text is only for
                // the paths that return null without one (unknown type).
                lastErrors.putIfAbsent(channelId, "failed to build channel");
                continue;
            }
            try {
                runtimeObservations.remove(channelId);
                channelManager.register(channel);
                channel.init(gateway);
                channel.start();
                lastConfigFingerprints.put(channelId, fingerprint);
                lastErrors.remove(channelId);
                log.info("Channel '{}' started (type={})", channelId, entry.getType());
            } catch (Exception ex) {
                channelManager.unregister(channelId);
                lastConfigFingerprints.remove(channelId);
                runtimeObservations.remove(channelId);
                runtimeListenerTokens.remove(channelId);
                lastErrors.put(channelId, ex.getMessage());
                log.warn("Failed to start channel '{}': {}", channelId, ex.getMessage());
            }
        }
        reportRuntimeStatus();
    }

    private void reportRuntimeStatus() {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, RuntimeObservation> observations;
        Map<String, String> errors;
        Map<String, ChannelConfigEntry> configs;
        synchronized (this) {
            observations = new HashMap<>(runtimeObservations);
            errors = new HashMap<>(lastErrors);
            configs = catalog.snapshot();
        }
        for (var entry : configs.entrySet()) {
            String id = entry.getKey();
            RuntimeObservation observation = observations.get(id);
            boolean weixin = WeixinChannel.TYPE.equals(entry.getValue().getType());
            // A standby has no observation authority and must not overwrite the active replica.
            if (weixin && (observation == null || observation.lease() == null)) {
                // A channel that failed to build or start never acquires a lease either, so it has
                // no observation and would be dropped from the report entirely. Surface it instead:
                // an operator must be able to tell a failing channel from one that was never
                // configured. A healthy standby has no recorded error and stays silent.
                if (errors.containsKey(id)) {
                    items.add(
                            weixinStartFailure(
                                    id,
                                    entry.getValue(),
                                    errors.get(id) == null
                                            ? "channel failed to start"
                                            : errors.get(id)));
                }
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("channelId", id);
            if (weixin) {
                Long credentialRevision = credentialRevision(entry.getValue().getProperties());
                if (credentialRevision == null) {
                    // One unreadable entry must not stop every other channel's state from
                    // reaching the control plane.
                    items.add(
                            weixinStartFailure(
                                    id,
                                    entry.getValue(),
                                    "credentialRevision is missing or not a number"));
                    continue;
                }
                item.put("accountId", entry.getValue().getProperties().get("accountId"));
                item.put("credentialRevision", credentialRevision);
                item.put("leaseHolder", observation.lease().holderId());
                item.put("leaseGeneration", observation.lease().generation());
                item.put("sequence", observation.sequence());
            }
            boolean started =
                    observation != null
                            ? observation.started()
                            : channelManager.getChannel(id).isPresent() && !errors.containsKey(id);
            item.put("started", started);
            String err = observation != null ? observation.errorCode() : errors.get(id);
            if (err != null) {
                item.put("error", err);
            }
            items.add(item);
        }
        // Also clear status for channels that disappeared.
        Map<String, Object> body = Map.of("channels", items);
        try {
            controlPlane
                    .post()
                    .uri("/api/internal/channels/runtime")
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(15));
        } catch (Exception ex) {
            log.debug("Channel runtime report failed: {}", ex.getMessage());
        }
    }

    private Channel buildChannel(String channelId, ChannelConfigEntry entry) {
        String type = entry != null ? entry.getType() : null;
        if (type == null || type.isBlank()) {
            log.warn("Channel '{}' has no 'type'; skipping.", channelId);
            return null;
        }
        if ("chatui".equals(type)) {
            log.debug("Channel '{}' is chatui; not hosted by the scheduler.", channelId);
            return null;
        }
        ChannelFactory factory = ChannelTypeRegistry.get(type).orElse(null);
        if (factory == null) {
            log.warn(
                    "Channel '{}' declares unknown type '{}'; skipping. Registered types: {}",
                    channelId,
                    type,
                    ChannelTypeRegistry.registeredTypes());
            return null;
        }
        try {
            Map<String, Object> properties = new LinkedHashMap<>(entry.getProperties());
            if (WeixinChannel.TYPE.equals(type)) {
                Object listenerToken = new Object();
                runtimeListenerTokens.put(channelId, listenerToken);
                return WeixinChannel.create(
                        channelId,
                        entry.toChannelConfig(channelId),
                        io.agentscope.extensions.channel.weixin.WeixinChannelProperties.from(
                                channelId, properties),
                        new WeixinControlPlaneCredentialProvider(
                                controlPlane,
                                objectMapper,
                                channelId,
                                requireCredentialRevision(properties)),
                        weixinStateStore,
                        managedWeixinRuntimeListener(channelId, listenerToken));
            }
            return factory.create(channelId, entry.toChannelConfig(channelId), properties);
        } catch (RuntimeException ex) {
            log.warn(
                    "Failed to instantiate channel '{}' of type '{}': {}",
                    channelId,
                    type,
                    ex.getMessage());
            lastErrors.put(channelId, ex.getMessage());
            return null;
        }
    }

    /**
     * A Weixin channel that failed to build or start never acquires a lease, so it reports the
     * failure without lease authority: the control plane records it on the channel row and leaves
     * the connection's lease fields to the replica that actually holds the lease.
     */
    private static Map<String, Object> weixinStartFailure(
            String id, ChannelConfigEntry entry, String error) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("channelId", id);
        Object accountId = entry.getProperties().get("accountId");
        if (accountId != null) {
            item.put("accountId", accountId);
        }
        Long credentialRevision = credentialRevision(entry.getProperties());
        if (credentialRevision != null) {
            item.put("credentialRevision", credentialRevision);
        }
        item.put("started", false);
        item.put("error", error);
        return item;
    }

    /**
     * Reads the credential revision without letting one malformed entry abort the whole status
     * report — a missing or non-numeric value must not stop every other channel's state from
     * reaching the control plane. Returns {@code null} when the value cannot be read.
     */
    static Long credentialRevision(Map<String, Object> properties) {
        Object raw = properties == null ? null : properties.get("credentialRevision");
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /**
     * The control plane fences credentials by revision, so a channel without a readable revision
     * must not start. A described error keeps the failure local to this channel: the caller
     * records it against the id instead of letting a raw {@code NumberFormatException} escape.
     */
    private static long requireCredentialRevision(Map<String, Object> properties) {
        Long revision = credentialRevision(properties);
        if (revision == null) {
            throw new IllegalArgumentException("credentialRevision is missing or not a number");
        }
        return revision;
    }

    private Map<String, ChannelConfigEntry> fetchChannelConfigWithRetry() {
        for (int attempt = 1; attempt <= Math.max(1, configFetchRetries); attempt++) {
            Map<String, ChannelConfigEntry> configs = fetchChannelConfigOnce();
            if (configs != null) {
                return configs;
            }
            if (attempt < configFetchRetries) {
                try {
                    Thread.sleep(configFetchBackoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Map.of();
                }
            }
        }
        log.warn(
                "Control plane unreachable after {} attempt(s); starting with zero channels.",
                configFetchRetries);
        return Map.of();
    }

    /** @return config map, or {@code null} when the fetch failed */
    private Map<String, ChannelConfigEntry> fetchChannelConfigOnce() {
        try {
            String json =
                    controlPlane
                            .get()
                            .uri("/api/internal/channels/config")
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(Duration.ofSeconds(30));
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            Map<String, ChannelConfigEntry> configs =
                    objectMapper.readValue(json, CHANNEL_CONFIG_MAP);
            log.info(
                    "Fetched {} channel config(s) from control plane: {}",
                    configs.size(),
                    configs.keySet());
            return configs;
        } catch (Exception ex) {
            log.warn("Channel config fetch failed: {}", ex.getMessage());
            return null;
        }
    }

    private String fingerprint(ChannelConfigEntry entry) {
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (Exception e) {
            return String.valueOf(Objects.hashCode(entry));
        }
    }

    private WeixinRuntimeListener managedWeixinRuntimeListener(
            String channelId, Object listenerToken) {
        return new WeixinRuntimeListener() {
            @Override
            public void onLeaseAcquired(String accountId, WeixinLease lease) {
                synchronized (SchedulerChannelRuntime.this) {
                    if (runtimeListenerTokens.get(channelId) != listenerToken) return;
                    runtimeObservations.put(
                            channelId, new RuntimeObservation(false, null, lease, 1));
                }
            }

            @Override
            public void onRunning(String accountId) {
                recordRuntimeObservation(channelId, listenerToken, true, null);
            }

            @Override
            public void onStopped(String accountId) {
                recordRuntimeStopped(channelId, listenerToken);
            }

            @Override
            public void onCredentialRejected(String accountId, String reason) {
                recordRuntimeObservation(channelId, listenerToken, false, "CREDENTIAL_REJECTED");
            }

            @Override
            public void onTransientFailure(String accountId, String reason) {
                recordRuntimeObservation(channelId, listenerToken, false, "TRANSIENT_FAILURE");
            }

            @Override
            public void onRecovered(String accountId) {
                recordRuntimeObservation(channelId, listenerToken, true, null);
            }
        };
    }

    private void recordRuntimeObservation(
            String channelId, Object listenerToken, boolean started, String errorCode) {
        synchronized (this) {
            if (runtimeListenerTokens.get(channelId) != listenerToken) {
                return;
            }
            RuntimeObservation prior = runtimeObservations.get(channelId);
            if (prior == null || prior.lease() == null) return;
            if (prior.started() == started && Objects.equals(prior.errorCode(), errorCode)) return;
            runtimeObservations.put(
                    channelId,
                    new RuntimeObservation(
                            started, errorCode, prior.lease(), prior.sequence() + 1));
        }
        try {
            refreshExecutor.execute(this::reportRuntimeStatus);
        } catch (RuntimeException ex) {
            log.debug(
                    "Runtime observation report was not scheduled: {}",
                    ex.getClass().getSimpleName());
        }
    }

    private void recordRuntimeStopped(String channelId, Object listenerToken) {
        synchronized (this) {
            if (runtimeListenerTokens.get(channelId) != listenerToken) {
                return;
            }
            RuntimeObservation prior = runtimeObservations.get(channelId);
            if (prior == null || prior.lease() == null) return;
            runtimeObservations.put(
                    channelId,
                    new RuntimeObservation(
                            false, prior.errorCode(), prior.lease(), prior.sequence() + 1));
        }
        try {
            refreshExecutor.execute(this::reportRuntimeStatus);
        } catch (RuntimeException ex) {
            log.debug(
                    "Runtime observation report was not scheduled: {}",
                    ex.getClass().getSimpleName());
        }
    }

    private record RuntimeObservation(
            boolean started, String errorCode, WeixinLease lease, long sequence) {}
}
