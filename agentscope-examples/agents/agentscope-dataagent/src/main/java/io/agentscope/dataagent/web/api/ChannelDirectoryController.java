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
package io.agentscope.dataagent.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.channel.Channel;
import io.agentscope.dataagent.runtime.channel.ChannelConfig;
import io.agentscope.dataagent.runtime.config.ChannelConfigEntry;
import io.agentscope.dataagent.runtime.config.ChannelTypeRegistry;
import io.agentscope.dataagent.runtime.gateway.ChannelManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Channel directory and channel-default management.
 *
 * <ul>
 *   <li>{@code GET  /api/channels} — list every channel known to the runtime, merging registered
 *       (live) channels with persisted-but-disabled entries from {@code agentscope.json}.
 *   <li>{@code POST /api/agents/{agentId}/channels/{channelId}/default} — set the channel's
 *       {@code defaultAgentId} to the agent in the URL.
 * </ul>
 *
 * <p>Note: the set-default endpoint is mounted under {@code /api/agents/...} (matching the
 * frontend's {@code setChannelDefault(agentId, channelId)} call site) instead of under
 * {@code /api/channels/...}; this is intentional and matches {@code frontend/src/api/channels.ts}.
 */
@RestController
public class ChannelDirectoryController {

    private final DataAgentBootstrap bootstrap;
    private final ChannelManager channelManager;
    private final BindingPersistence persistence;

    public ChannelDirectoryController(
            DataAgentBootstrap bootstrap, BindingPersistence persistence) {
        this.bootstrap = bootstrap;
        this.channelManager = bootstrap.channelManager();
        this.persistence = persistence;
    }

    @GetMapping("/api/channels")
    public Mono<List<ChannelInfoView>> list() {
        return Mono.fromCallable(
                () -> {
                    Map<String, ChannelInfoView> merged = new LinkedHashMap<>();

                    for (Channel ch : channelManager.getAllChannels()) {
                        ChannelConfig cfg = ch.config();
                        merged.put(
                                ch.channelId(),
                                new ChannelInfoView(
                                        ch.channelId(),
                                        cfg.dmScope() != null ? cfg.dmScope().name() : null,
                                        cfg.defaultAgentId(),
                                        channelManager.isStarted()));
                    }

                    Map<String, ChannelConfigEntry> fileChannels =
                            bootstrap.loadedConfig().getChannels();
                    if (fileChannels != null) {
                        for (Map.Entry<String, ChannelConfigEntry> e : fileChannels.entrySet()) {
                            if (merged.containsKey(e.getKey())) continue;
                            ChannelConfigEntry ce = e.getValue();
                            if (ce == null) continue;
                            merged.put(
                                    e.getKey(),
                                    new ChannelInfoView(
                                            e.getKey(),
                                            ce.getDmScope(),
                                            ce.getDefaultAgentId(),
                                            false));
                        }
                    }

                    return new ArrayList<>(merged.values());
                });
    }

    /**
     * Returns the channel types the runtime knows how to instantiate (chatui, dingtalk, wecom,
     * feishu, github, gitlab, plus any types registered via
     * {@link ChannelTypeRegistry#register(String, io.agentscope.dataagent.runtime.config.ChannelFactory)}).
     * Used by the binding form's type-select dropdown.
     */
    @GetMapping("/api/channels/types")
    public Mono<List<String>> listTypes() {
        return Mono.fromCallable(
                () -> new ArrayList<>(new TreeSet<>(ChannelTypeRegistry.registeredTypes())));
    }

    @PostMapping("/api/agents/{agentId}/channels/{channelId}/default")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> setDefault(@PathVariable String agentId, @PathVariable String channelId) {
        return Mono.fromRunnable(
                () ->
                        persistence.mutate(
                                channels -> {
                                    ChannelConfigEntry ch = channels.get(channelId);
                                    if (ch == null) {
                                        throw new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Channel not found: " + channelId);
                                    }
                                    ch.setDefaultAgentId(agentId);
                                    return null;
                                },
                                List.of(channelId)));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChannelInfoView(
            String channelId, String dmScope, String defaultAgentId, boolean started) {}
}
