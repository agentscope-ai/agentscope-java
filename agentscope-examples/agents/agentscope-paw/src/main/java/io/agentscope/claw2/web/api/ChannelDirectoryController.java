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

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.config.ChannelConfigEntry;
import io.agentscope.claw2.runtime.config.ChannelTypeRegistry;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Channel directory and channel lifecycle management.
 *
 * <ul>
 *   <li>{@code GET    /api/channels} — list every channel known to the runtime, merging registered
 *       (live) channels with persisted-but-disabled entries from {@code agentscope.json}.
 *   <li>{@code GET    /api/channels/types} — list available channel type ids from
 *       {@link ChannelTypeRegistry} (for UI dropdowns).
 *   <li>{@code GET    /api/channels/{channelId}} — full per-channel config view (type, properties,
 *       dmScope, defaultAgentId, disabled, bindings).
 *   <li>{@code POST   /api/channels} — create a new channel entry.
 *   <li>{@code PUT    /api/channels/{channelId}} — replace an existing channel entry (preserves
 *       bindings unless the request supplies a new list).
 *   <li>{@code DELETE /api/channels/{channelId}} — remove a channel entry and unregister its live
 *       instance.
 *   <li>{@code POST   /api/channels/{channelId}/enable} — clear the {@code disabled} flag and
 *       re-instantiate the channel.
 *   <li>{@code POST   /api/channels/{channelId}/disable} — set {@code disabled=true} and stop the
 *       live instance.
 *   <li>{@code POST   /api/agents/{agentId}/channels/{channelId}/default} — set the channel's
 *       {@code defaultAgentId} to the agent in the URL.
 * </ul>
 */
@RestController
public class ChannelDirectoryController {

    private static final Pattern CHANNEL_ID_PATTERN = Pattern.compile("[A-Za-z0-9_.-]+");

    private final ChannelManager channelManager;
    private final BindingPersistence persistence;

    public ChannelDirectoryController(ClawBootstrap bootstrap, BindingPersistence persistence) {
        this.channelManager = bootstrap.channelManager();
        this.persistence = persistence;
    }

    @GetMapping("/api/channels")
    public Mono<List<ChannelInfoView>> list() {
        return Mono.fromCallable(
                () -> {
                    Map<String, ChannelInfoView> merged = new LinkedHashMap<>();
                    Map<String, ChannelConfigEntry> fileChannels =
                            persistence.currentConfig().getChannels();

                    for (Channel ch : channelManager.getAllChannels()) {
                        ChannelConfig cfg = ch.config();
                        ChannelConfigEntry fileEntry =
                                fileChannels != null ? fileChannels.get(ch.channelId()) : null;
                        merged.put(
                                ch.channelId(),
                                new ChannelInfoView(
                                        ch.channelId(),
                                        fileEntry != null ? fileEntry.getType() : ch.channelId(),
                                        cfg.dmScope() != null ? cfg.dmScope().name() : null,
                                        cfg.defaultAgentId(),
                                        false,
                                        channelManager.isStarted()));
                    }

                    if (fileChannels != null) {
                        for (Map.Entry<String, ChannelConfigEntry> e : fileChannels.entrySet()) {
                            if (merged.containsKey(e.getKey())) continue;
                            ChannelConfigEntry ce = e.getValue();
                            if (ce == null) continue;
                            merged.put(
                                    e.getKey(),
                                    new ChannelInfoView(
                                            e.getKey(),
                                            ce.getType(),
                                            ce.getDmScope(),
                                            ce.getDefaultAgentId(),
                                            Boolean.TRUE.equals(ce.getDisabled()),
                                            false));
                        }
                    }

                    return new ArrayList<>(merged.values());
                });
    }

    @GetMapping("/api/channels/types")
    public Mono<List<String>> listTypes() {
        return Mono.fromCallable(
                () -> {
                    Set<String> types = ChannelTypeRegistry.registeredTypes();
                    List<String> sorted = new ArrayList<>(types);
                    sorted.sort(String::compareTo);
                    return sorted;
                });
    }

    @GetMapping("/api/channels/{channelId}")
    public Mono<ChannelDetailView> detail(@PathVariable String channelId) {
        return Mono.fromCallable(
                () -> {
                    Map<String, ChannelConfigEntry> fileChannels =
                            persistence.currentConfig().getChannels();
                    ChannelConfigEntry entry =
                            fileChannels != null ? fileChannels.get(channelId) : null;
                    if (entry == null) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Channel not found: " + channelId);
                    }
                    boolean started =
                            channelManager.getChannel(channelId).isPresent()
                                    && channelManager.isStarted();
                    return new ChannelDetailView(
                            channelId,
                            entry.getType(),
                            entry.getDmScope(),
                            entry.getDefaultAgentId(),
                            Boolean.TRUE.equals(entry.getDisabled()),
                            started,
                            entry.getProperties(),
                            entry.getBindings() != null ? entry.getBindings() : List.of());
                });
    }

    @PostMapping("/api/channels")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ChannelDetailView> create(@RequestBody ChannelUpsertRequest req) {
        return Mono.fromCallable(
                () -> {
                    validateUpsert(req, true);
                    String id = req.channelId().trim();
                    return persistence.mutate(
                            channels -> {
                                if (channels.containsKey(id)) {
                                    throw new ResponseStatusException(
                                            HttpStatus.CONFLICT, "Channel already exists: " + id);
                                }
                                ChannelConfigEntry entry = new ChannelConfigEntry();
                                applyUpsert(entry, req);
                                channels.put(id, entry);
                                return new ChannelDetailView(
                                        id,
                                        entry.getType(),
                                        entry.getDmScope(),
                                        entry.getDefaultAgentId(),
                                        Boolean.TRUE.equals(entry.getDisabled()),
                                        true,
                                        entry.getProperties(),
                                        entry.getBindings() != null
                                                ? entry.getBindings()
                                                : List.of());
                            },
                            List.of(id));
                });
    }

    @PutMapping("/api/channels/{channelId}")
    public Mono<ChannelDetailView> update(
            @PathVariable String channelId, @RequestBody ChannelUpsertRequest req) {
        return Mono.fromCallable(
                () -> {
                    validateUpsert(req, false);
                    return persistence.mutate(
                            channels -> {
                                ChannelConfigEntry entry = channels.get(channelId);
                                if (entry == null) {
                                    throw new ResponseStatusException(
                                            HttpStatus.NOT_FOUND,
                                            "Channel not found: " + channelId);
                                }
                                applyUpsert(entry, req);
                                return new ChannelDetailView(
                                        channelId,
                                        entry.getType(),
                                        entry.getDmScope(),
                                        entry.getDefaultAgentId(),
                                        Boolean.TRUE.equals(entry.getDisabled()),
                                        !Boolean.TRUE.equals(entry.getDisabled()),
                                        entry.getProperties(),
                                        entry.getBindings() != null
                                                ? entry.getBindings()
                                                : List.of());
                            },
                            List.of(channelId));
                });
    }

    @DeleteMapping("/api/channels/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String channelId) {
        return Mono.fromRunnable(
                () ->
                        persistence.mutate(
                                channels -> {
                                    if (!channels.containsKey(channelId)) {
                                        throw new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Channel not found: " + channelId);
                                    }
                                    channels.remove(channelId);
                                    return null;
                                },
                                List.of(channelId)));
    }

    @PostMapping("/api/channels/{channelId}/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> enable(@PathVariable String channelId) {
        return Mono.fromRunnable(() -> setDisabled(channelId, false));
    }

    @PostMapping("/api/channels/{channelId}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> disable(@PathVariable String channelId) {
        return Mono.fromRunnable(() -> setDisabled(channelId, true));
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

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    private void setDisabled(String channelId, boolean disabled) {
        persistence.mutate(
                channels -> {
                    ChannelConfigEntry ch = channels.get(channelId);
                    if (ch == null) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Channel not found: " + channelId);
                    }
                    ch.setDisabled(disabled ? Boolean.TRUE : null);
                    return null;
                },
                List.of(channelId));
    }

    private static void validateUpsert(ChannelUpsertRequest req, boolean requireId) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
        if (requireId) {
            String id = req.channelId();
            if (id == null || id.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channelId is required");
            }
            if (!CHANNEL_ID_PATTERN.matcher(id).matches()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "channelId may only contain letters, digits, '_', '-', '.'");
            }
        }
        String type = req.type();
        if (type == null || type.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required");
        }
        if (ChannelTypeRegistry.get(type).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unknown channel type '"
                            + type
                            + "'. Known types: "
                            + ChannelTypeRegistry.registeredTypes());
        }
    }

    private static void applyUpsert(ChannelConfigEntry entry, ChannelUpsertRequest req) {
        entry.setType(req.type());
        entry.setDmScope(req.dmScope());
        entry.setDefaultAgentId(req.defaultAgentId());
        if (req.properties() != null) {
            entry.setProperties(new LinkedHashMap<>(req.properties()));
        }
        if (req.disabled() != null) {
            entry.setDisabled(Boolean.TRUE.equals(req.disabled()) ? Boolean.TRUE : null);
        }
        // Preserve existing bindings unless explicitly supplied in the request.
        if (req.bindings() != null) {
            entry.setBindings(new ArrayList<>(req.bindings()));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChannelInfoView(
            String channelId,
            String type,
            String dmScope,
            String defaultAgentId,
            boolean disabled,
            boolean started) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChannelDetailView(
            String channelId,
            String type,
            String dmScope,
            String defaultAgentId,
            boolean disabled,
            boolean started,
            Map<String, Object> properties,
            List<io.agentscope.claw2.runtime.config.BindingConfigEntry> bindings) {}

    /** Request body for create/update. {@code channelId} is required only for create. */
    public record ChannelUpsertRequest(
            String channelId,
            String type,
            String dmScope,
            String defaultAgentId,
            Boolean disabled,
            Map<String, Object> properties,
            List<io.agentscope.claw2.runtime.config.BindingConfigEntry> bindings) {}
}
