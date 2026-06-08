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
package io.agentscope.claw2.web.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.config.AgentscopeConfig;
import io.agentscope.claw2.runtime.config.BindingConfigEntry;
import io.agentscope.claw2.runtime.config.ChannelConfigEntry;
import io.agentscope.claw2.runtime.config.ChannelTypeRegistry;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Helper that loads, mutates, and atomically rewrites {@code agentscope.json} for binding /
 * channel-default edits, then re-registers the affected channel with the live
 * {@link ChannelManager} so changes take effect immediately.
 *
 * <p>All mutations are serialised through a {@link ReentrantLock} to avoid lost-update races
 * between concurrent requests.
 */
@Component
public class BindingPersistence {

    private static final Logger log = LoggerFactory.getLogger(BindingPersistence.class);

    private final ClawBootstrap bootstrap;
    private final ChannelManager channelManager;
    private final ReentrantLock lock = new ReentrantLock();
    private final ObjectMapper mapper;

    public BindingPersistence(ClawBootstrap bootstrap) {
        this.bootstrap = bootstrap;
        this.channelManager = bootstrap.channelManager();
        this.mapper =
                new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Apply {@code mutator} to the live channels map of {@code agentscope.json}, persist the
     * result atomically, and re-register the supplied channel ids in the live
     * {@link ChannelManager}. Returns the value produced by {@code mutator} so callers can shape
     * the response.
     */
    public <T> T mutate(MutationFn<T> mutator, List<String> channelsToReload) {
        lock.lock();
        try {
            AgentscopeConfig cfg = loadConfig();
            Map<String, ChannelConfigEntry> channels =
                    cfg.getChannels() != null ? cfg.getChannels() : new LinkedHashMap<>();
            cfg.setChannels(channels);

            T result = mutator.apply(channels);
            writeAtomic(cfg);

            if (channelsToReload != null) {
                for (String id : channelsToReload) {
                    reloadChannel(id, channels.get(id));
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reads the current {@code agentscope.json} from disk (no lock — caller should not mutate the
     * returned config in place). Used by read-only endpoints that need the freshest channel state
     * after recent mutations, since {@link ClawBootstrap#loadedConfig()} is a bootstrap-time
     * snapshot.
     */
    public AgentscopeConfig currentConfig() {
        return loadConfig();
    }

    /** Returns the bindings list for the given channel, materialising it if absent. */
    public List<BindingConfigEntry> mutableBindings(ChannelConfigEntry channel) {
        if (channel.getBindings() == null) {
            channel.setBindings(new ArrayList<>());
        } else if (!(channel.getBindings() instanceof ArrayList)) {
            channel.setBindings(new ArrayList<>(channel.getBindings()));
        }
        return channel.getBindings();
    }

    /** Returns (and lazily creates) the {@link ChannelConfigEntry} for the given channel id. */
    public ChannelConfigEntry orCreate(Map<String, ChannelConfigEntry> channels, String channelId) {
        return channels.computeIfAbsent(channelId, k -> new ChannelConfigEntry());
    }

    /** Functional interface for {@link #mutate(MutationFn, List)} mutations. */
    @FunctionalInterface
    public interface MutationFn<T> {
        T apply(Map<String, ChannelConfigEntry> channels);
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    private AgentscopeConfig loadConfig() {
        try {
            return ClawBootstrap.loadConfigFile(bootstrap.configPath());
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to read agentscope.json: " + e.getMessage());
        }
    }

    private void writeAtomic(AgentscopeConfig cfg) {
        Path target = bootstrap.configPath();
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), cfg);
            Files.move(
                    tmp,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to write agentscope.json: " + e.getMessage());
        }
    }

    /**
     * Re-registers the given channel from its on-disk config so binding edits take effect for
     * subsequent inbound messages. Resolves the implementation via {@link ChannelTypeRegistry}
     * using the entry's {@code type}, so every adapter (chatui, dingtalk, wecom, feishu, github,
     * gitlab, plus any user-registered types) hot-reloads through the same path. {@link
     * ChannelManager#unregister} stops the previous instance before the new one is started, so
     * external transports (DingTalk Stream, webhook callbacks, ...) get a clean cycle.
     *
     * <p>When {@code entry} is {@code null} (channel removed) or has {@code disabled=true} the
     * channel is unregistered without being rebuilt.
     */
    private void reloadChannel(String channelId, ChannelConfigEntry entry) {
        if (channelId == null) return;
        if (entry == null || Boolean.TRUE.equals(entry.getDisabled())) {
            if (channelManager.unregister(channelId)) {
                log.info("Unregistered channel '{}' after edit (entry={})", channelId, entry);
            }
            return;
        }
        String type = entry.getType();
        if (type == null || type.isBlank()) {
            log.warn("Skipping reload of '{}': missing 'type' in agentscope.json entry", channelId);
            return;
        }
        ChannelFactory factory = ChannelTypeRegistry.get(type).orElse(null);
        if (factory == null) {
            log.warn(
                    "Skipping reload of '{}': no factory registered for type '{}' (known types:"
                            + " {})",
                    channelId,
                    type,
                    ChannelTypeRegistry.registeredTypes());
            return;
        }
        try {
            ChannelConfig newCfg = entry.toChannelConfig(channelId);
            Map<String, Object> props =
                    entry.getProperties() != null ? entry.getProperties() : Map.of();
            channelManager.unregister(channelId);
            Channel rebuilt = factory.create(channelId, newCfg, props);
            channelManager.register(rebuilt);
            rebuilt.init(bootstrap.gateway());
            if (channelManager.isStarted()) {
                rebuilt.start();
            }
            log.info("Reloaded channel '{}' (type={}) after edit", channelId, type);
        } catch (Exception e) {
            log.warn(
                    "Failed to reload channel '{}' (type={}): {}. Restart required for live"
                            + " changes.",
                    channelId,
                    type,
                    e.getMessage(),
                    e);
        }
    }
}
