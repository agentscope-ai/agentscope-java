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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scheduler channel runtime: owns the live set of IM channel adapters.
 *
 * <p>Startup sequence (mirrors the monolith's bootstrap, with config now sourced from the
 * control plane instead of a local file):
 *
 * <ol>
 *   <li>register the bundled channel factories ({@code dingtalk}, {@code feishu}, {@code wecom},
 *       {@code github}, {@code gitlab}) into {@link ChannelTypeRegistry};
 *   <li>fetch the full channel configuration map from the control plane's internal API
 *       ({@code GET /api/internal/channels/config}), retrying while the control plane is still
 *       coming up;
 *   <li>instantiate each configured channel through its factory, register it on the {@link
 *       ChannelManager}, then {@code initAll(gateway)} + {@code startAll()}.
 * </ol>
 *
 * <p>When the control plane stays unreachable the runtime starts with zero channels — outbound
 * delivery then fails fast per request, and a restart re-attempts the fetch. (Dynamic
 * config-refresh is a later hardening step.)
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
    private final int configFetchRetries;
    private final long configFetchBackoffMs;

    private volatile boolean running;

    public SchedulerChannelRuntime(
            ChannelManager channelManager,
            Gateway gateway,
            @Qualifier("controlPlaneWebClient") WebClient controlPlane,
            ObjectMapper objectMapper,
            @Value("${builder.scheduler.channel-config-retries:12}") int configFetchRetries,
            @Value("${builder.scheduler.channel-config-backoff-ms:5000}") long backoffMs) {
        this.channelManager = channelManager;
        this.gateway = gateway;
        this.controlPlane = controlPlane;
        this.objectMapper = objectMapper;
        this.configFetchRetries = configFetchRetries;
        this.configFetchBackoffMs = backoffMs;
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
        Map<String, ChannelConfigEntry> configs = fetchChannelConfig();
        for (Map.Entry<String, ChannelConfigEntry> e : configs.entrySet()) {
            Channel channel = buildChannel(e.getKey(), e.getValue());
            if (channel != null) {
                channelManager.register(channel);
            }
        }
        channelManager.initAll(gateway);
        channelManager.startAll();
        running = true;
        log.info(
                "Scheduler channel runtime started: {} channel(s) active",
                channelManager.channelIds().size());
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        try {
            channelManager.stopAll();
        } catch (Exception ex) {
            log.warn("Channel stopAll failed: {}", ex.getMessage());
        }
        running = false;
        log.info("Scheduler channel runtime stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Builds a channel from a config entry; returns {@code null} (with a warning) for entries
     * that are chat-UI, untyped, unknown-typed, or fail construction.
     */
    private Channel buildChannel(String channelId, ChannelConfigEntry entry) {
        String type = entry != null ? entry.getType() : null;
        if (type == null || type.isBlank()) {
            log.warn("Channel '{}' has no 'type'; skipping.", channelId);
            return null;
        }
        if ("chatui".equals(type)) {
            // The chat UI talks to the gateway/data plane directly; no adapter needed here.
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
            return null;
        }
    }

    /**
     * Fetches the channel configuration map from the control plane, retrying while it is still
     * coming up. Returns an empty map when all attempts fail.
     */
    private Map<String, ChannelConfigEntry> fetchChannelConfig() {
        for (int attempt = 1; attempt <= Math.max(1, configFetchRetries); attempt++) {
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
                log.warn(
                        "Channel config fetch attempt {}/{} failed: {}",
                        attempt,
                        configFetchRetries,
                        ex.getMessage());
                if (attempt < configFetchRetries) {
                    try {
                        Thread.sleep(configFetchBackoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return Map.of();
                    }
                }
            }
        }
        log.warn(
                "Control plane unreachable after {} attempt(s); starting with zero channels.",
                configFetchRetries);
        return Map.of();
    }
}
