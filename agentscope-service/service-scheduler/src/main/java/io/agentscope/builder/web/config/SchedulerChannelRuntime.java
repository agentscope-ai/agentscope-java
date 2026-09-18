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

    private volatile boolean running;
    private ScheduledFuture<?> refreshTask;

    public SchedulerChannelRuntime(
            ChannelManager channelManager,
            Gateway gateway,
            @Qualifier("controlPlaneWebClient") WebClient controlPlane,
            ObjectMapper objectMapper,
            ChannelRuntimeCatalog catalog,
            @Value("${builder.scheduler.channel-config-retries:12}") int configFetchRetries,
            @Value("${builder.scheduler.channel-config-backoff-ms:5000}") long backoffMs,
            @Value("${builder.scheduler.channel-refresh-ms:15000}") long refreshIntervalMs) {
        this.channelManager = channelManager;
        this.gateway = gateway;
        this.controlPlane = controlPlane;
        this.objectMapper = objectMapper;
        this.catalog = catalog;
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
    public void stop() {
        if (!running) {
            return;
        }
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
                continue;
            }
            String fingerprint = fingerprint(entry);
            if (Objects.equals(fingerprint, lastConfigFingerprints.get(channelId))
                    && channelManager.getChannel(channelId).isPresent()) {
                continue;
            }
            channelManager.unregister(channelId);
            lastErrors.remove(channelId);
            Channel channel = buildChannel(channelId, entry);
            if (channel == null) {
                lastConfigFingerprints.remove(channelId);
                lastErrors.put(channelId, "failed to build channel");
                continue;
            }
            try {
                channelManager.register(channel);
                channel.init(gateway);
                channel.start();
                lastConfigFingerprints.put(channelId, fingerprint);
                lastErrors.remove(channelId);
                log.info("Channel '{}' started (type={})", channelId, entry.getType());
            } catch (Exception ex) {
                channelManager.unregister(channelId);
                lastConfigFingerprints.remove(channelId);
                lastErrors.put(channelId, ex.getMessage());
                log.warn("Failed to start channel '{}': {}", channelId, ex.getMessage());
            }
        }
        reportRuntimeStatus();
    }

    private void reportRuntimeStatus() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String id : catalog.snapshot().keySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("channelId", id);
            boolean started =
                    channelManager.getChannel(id).isPresent() && !lastErrors.containsKey(id);
            item.put("started", started);
            String err = lastErrors.get(id);
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
            return factory.create(
                    channelId, entry.toChannelConfig(channelId), entry.getProperties());
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
}
