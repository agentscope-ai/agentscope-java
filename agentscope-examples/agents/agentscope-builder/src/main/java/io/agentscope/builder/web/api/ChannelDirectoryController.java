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
package io.agentscope.builder.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.builder.runtime.BuilderBootstrap;
import io.agentscope.builder.runtime.config.BindingConfigEntry;
import io.agentscope.builder.runtime.config.ChannelConfigEntry;
import io.agentscope.builder.runtime.config.ChannelTypeRegistry;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
 * Channel directory and channel-instance management.
 *
 * <ul>
 *   <li>{@code GET  /api/channels} — list every channel known to the runtime, merging registered
 *       (live) channels with persisted-but-disabled entries from {@code agentscope.json}.
 *   <li>{@code GET  /api/channels/types} — list registered {@link ChannelTypeRegistry} type ids.
 *   <li>{@code GET  /api/channels/{id}} — admin-only channel detail (with credentials masked).
 *   <li>{@code POST /api/channels} — admin-only create new channel instance.
 *   <li>{@code PUT  /api/channels/{id}} — admin-only update channel instance.
 *   <li>{@code DELETE /api/channels/{id}} — admin-only remove channel instance.
 *   <li>{@code POST /api/channels/{id}/enable} — admin-only enable.
 *   <li>{@code POST /api/channels/{id}/disable} — admin-only disable.
 *   <li>{@code POST /api/agents/{agentId}/channels/{channelId}/default} — set the channel's
 *       {@code defaultAgentId} to the agent in the URL.
 * </ul>
 *
 * <p>Channel instance config is platform-level: only admins may create/update/delete it. Every
 * write goes through {@link BindingPersistence#mutate} so the change is persisted atomically and
 * the live {@link ChannelManager} reflects it without a process restart. Provider credentials
 * (matched by name in {@link #CHANNEL_SECRET_KEYS}) are stripped from every response so the JSON
 * never echoes secrets back over the wire.
 */
@RestController
public class ChannelDirectoryController {

    /**
     * Property keys whose values are always masked on response. Matches the credential field names
     * used by the bundled IM channel adapters (DingTalk / WeCom / Feishu / GitHub / GitLab).
     */
    private static final Set<String> CHANNEL_SECRET_KEYS =
            Set.of(
                    "appSecret",
                    "encodingAesKey",
                    "verifyToken",
                    "webhookSecret",
                    "token",
                    "secret",
                    "password");

    private static final String SECRET_MASK = "••••";

    private final BuilderBootstrap bootstrap;
    private final ChannelManager channelManager;
    private final BindingPersistence persistence;

    public ChannelDirectoryController(BuilderBootstrap bootstrap, BindingPersistence persistence) {
        this.bootstrap = bootstrap;
        this.channelManager = bootstrap.channelManager();
        this.persistence = persistence;
    }

    @GetMapping("/api/channels")
    public Mono<List<ChannelInfoView>> list() {
        return Mono.fromCallable(
                () -> {
                    Map<String, ChannelInfoView> merged = new LinkedHashMap<>();

                    Map<String, ChannelConfigEntry> fileChannels =
                            bootstrap.loadedConfig().getChannels();
                    Map<String, ChannelConfigEntry> entries =
                            fileChannels != null ? fileChannels : Map.of();

                    for (Channel ch : channelManager.getAllChannels()) {
                        ChannelConfig cfg = ch.config();
                        ChannelConfigEntry entry = entries.get(ch.channelId());
                        merged.put(
                                ch.channelId(),
                                new ChannelInfoView(
                                        ch.channelId(),
                                        entry != null ? entry.getType() : null,
                                        cfg.dmScope() != null ? cfg.dmScope().name() : null,
                                        cfg.defaultAgentId(),
                                        Boolean.TRUE.equals(
                                                entry != null ? entry.getDisabled() : null),
                                        channelManager.isStarted()));
                    }

                    for (Map.Entry<String, ChannelConfigEntry> e : entries.entrySet()) {
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

                    return new ArrayList<>(merged.values());
                });
    }

    /**
     * Returns the channel types the runtime knows how to instantiate (chatui, dingtalk, wecom,
     * feishu, github, gitlab, plus any types registered via
     * {@link ChannelTypeRegistry#register(String, ChannelFactory)}). Used by the binding form's
     * type-select dropdown.
     */
    @GetMapping("/api/channels/types")
    public Mono<List<String>> listTypes() {
        return Mono.fromCallable(
                () -> new ArrayList<>(new TreeSet<>(ChannelTypeRegistry.registeredTypes())));
    }

    @GetMapping("/api/channels/{channelId}")
    public Mono<ChannelDetailView> getDetail(@PathVariable String channelId, Authentication auth) {
        return Mono.fromCallable(
                () -> {
                    requireAdmin(auth);
                    Map<String, ChannelConfigEntry> channels =
                            bootstrap.loadedConfig().getChannels();
                    ChannelConfigEntry entry = channels != null ? channels.get(channelId) : null;
                    if (entry == null) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Channel not found: " + channelId);
                    }
                    boolean started =
                            channelManager.getChannel(channelId).isPresent()
                                    && channelManager.isStarted();
                    return toDetailView(channelId, entry, started);
                });
    }

    @PostMapping("/api/channels")
    public Mono<ChannelDetailView> create(
            @RequestBody ChannelUpsertRequest body, Authentication auth) {
        return Mono.fromCallable(
                () -> {
                    requireAdmin(auth);
                    String channelId = trimToNull(body.channelId());
                    if (channelId == null) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "channelId is required");
                    }
                    String type = trimToNull(body.type());
                    if (type == null) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "type is required");
                    }
                    if (ChannelTypeRegistry.get(type).isEmpty()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Unknown channel type: " + type);
                    }
                    return persistence.mutate(
                            channels -> {
                                if (channels.containsKey(channelId)) {
                                    throw new ResponseStatusException(
                                            HttpStatus.CONFLICT,
                                            "Channel already exists: " + channelId);
                                }
                                ChannelConfigEntry entry = new ChannelConfigEntry();
                                applyUpsert(entry, body, type);
                                channels.put(channelId, entry);
                                applyToRuntime(channelId, entry);
                                boolean started = !Boolean.TRUE.equals(entry.getDisabled());
                                return toDetailView(channelId, entry, started);
                            },
                            List.of(channelId));
                });
    }

    @PutMapping("/api/channels/{channelId}")
    public Mono<ChannelDetailView> update(
            @PathVariable String channelId,
            @RequestBody ChannelUpsertRequest body,
            Authentication auth) {
        return Mono.fromCallable(
                () -> {
                    requireAdmin(auth);
                    return persistence.mutate(
                            channels -> {
                                ChannelConfigEntry entry = channels.get(channelId);
                                if (entry == null) {
                                    throw new ResponseStatusException(
                                            HttpStatus.NOT_FOUND,
                                            "Channel not found: " + channelId);
                                }
                                String type =
                                        trimToNull(body.type()) != null
                                                ? trimToNull(body.type())
                                                : entry.getType();
                                if (type != null && ChannelTypeRegistry.get(type).isEmpty()) {
                                    throw new ResponseStatusException(
                                            HttpStatus.BAD_REQUEST,
                                            "Unknown channel type: " + type);
                                }
                                applyUpsert(entry, body, type);
                                applyToRuntime(channelId, entry);
                                boolean started =
                                        !Boolean.TRUE.equals(entry.getDisabled())
                                                && channelManager.getChannel(channelId).isPresent();
                                return toDetailView(channelId, entry, started);
                            },
                            List.of(channelId));
                });
    }

    @DeleteMapping("/api/channels/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String channelId, Authentication auth) {
        return Mono.fromRunnable(
                () -> {
                    requireAdmin(auth);
                    persistence.mutate(
                            channels -> {
                                if (!channels.containsKey(channelId)) {
                                    throw new ResponseStatusException(
                                            HttpStatus.NOT_FOUND,
                                            "Channel not found: " + channelId);
                                }
                                channels.remove(channelId);
                                channelManager.unregister(channelId);
                                return null;
                            },
                            List.of());
                });
    }

    @PostMapping("/api/channels/{channelId}/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> enable(@PathVariable String channelId, Authentication auth) {
        return setDisabled(channelId, false, auth);
    }

    @PostMapping("/api/channels/{channelId}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> disable(@PathVariable String channelId, Authentication auth) {
        return setDisabled(channelId, true, auth);
    }

    private Mono<Void> setDisabled(String channelId, boolean disabled, Authentication auth) {
        return Mono.fromRunnable(
                () -> {
                    requireAdmin(auth);
                    persistence.mutate(
                            channels -> {
                                ChannelConfigEntry entry = channels.get(channelId);
                                if (entry == null) {
                                    throw new ResponseStatusException(
                                            HttpStatus.NOT_FOUND,
                                            "Channel not found: " + channelId);
                                }
                                entry.setDisabled(disabled);
                                if (disabled) {
                                    channelManager.unregister(channelId);
                                } else {
                                    applyToRuntime(channelId, entry);
                                }
                                return null;
                            },
                            List.of(channelId));
                });
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
    //  Helpers
    // -----------------------------------------------------------------

    private void applyUpsert(ChannelConfigEntry entry, ChannelUpsertRequest body, String type) {
        if (type != null) {
            entry.setType(type);
        }
        if (body.dmScope() != null) {
            entry.setDmScope(trimToNull(body.dmScope()));
        }
        if (body.defaultAgentId() != null) {
            entry.setDefaultAgentId(trimToNull(body.defaultAgentId()));
        }
        if (body.disabled() != null) {
            entry.setDisabled(body.disabled());
        }
        if (body.properties() != null) {
            Map<String, Object> incoming = new LinkedHashMap<>(body.properties());
            Map<String, Object> existing =
                    entry.getProperties() != null
                            ? new LinkedHashMap<>(entry.getProperties())
                            : new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : incoming.entrySet()) {
                Object v = e.getValue();
                if (CHANNEL_SECRET_KEYS.contains(e.getKey())
                        && v instanceof String s
                        && SECRET_MASK.equals(s)) {
                    continue;
                }
                if (v == null) {
                    existing.remove(e.getKey());
                } else {
                    existing.put(e.getKey(), v);
                }
            }
            entry.setProperties(existing);
        }
        if (body.bindings() != null) {
            entry.setBindings(new ArrayList<>(body.bindings()));
        }
    }

    private void applyToRuntime(String channelId, ChannelConfigEntry entry) {
        if (Boolean.TRUE.equals(entry.getDisabled())) {
            channelManager.unregister(channelId);
            return;
        }
        String type = entry.getType();
        if (type == null) {
            return;
        }
        ChannelFactory factory = ChannelTypeRegistry.get(type).orElse(null);
        if (factory == null) {
            return;
        }
        ChannelConfig routing = entry.toChannelConfig(channelId);
        Channel channel = factory.create(channelId, routing, entry.getProperties());
        channelManager.unregister(channelId);
        channelManager.register(channel);
        Gateway gateway = bootstrap.gateway();
        if (gateway != null) {
            try {
                channel.init(gateway);
            } catch (RuntimeException ignored) {
                // gateway-init failures should not fail the API call; logged inside the channel
            }
        }
        if (channelManager.isStarted()) {
            try {
                channel.start();
            } catch (RuntimeException ignored) {
                // start failures are logged inside the channel
            }
        }
    }

    private static ChannelDetailView toDetailView(
            String channelId, ChannelConfigEntry entry, boolean started) {
        List<BindingConfigEntry> bindings =
                entry.getBindings() != null ? entry.getBindings() : List.of();
        return new ChannelDetailView(
                channelId,
                entry.getType(),
                entry.getDmScope(),
                entry.getDefaultAgentId(),
                Boolean.TRUE.equals(entry.getDisabled()),
                started,
                maskProperties(entry.getProperties()),
                new ArrayList<>(bindings));
    }

    private static Map<String, Object> maskProperties(Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> masked = new LinkedHashMap<>(properties.size());
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            Object v = e.getValue();
            if (v != null
                    && CHANNEL_SECRET_KEYS.contains(e.getKey())
                    && v instanceof String s
                    && !s.isEmpty()) {
                masked.put(e.getKey(), SECRET_MASK);
            } else {
                masked.put(e.getKey(), v);
            }
        }
        return masked;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static void requireAdmin(Authentication auth) {
        if (auth == null
                || auth.getAuthorities() == null
                || auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .noneMatch("ROLE_ADMIN"::equals)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
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
            List<BindingConfigEntry> bindings) {}

    /** Request payload for {@code POST /api/channels} and {@code PUT /api/channels/{id}}. */
    public record ChannelUpsertRequest(
            String channelId,
            String type,
            String dmScope,
            String defaultAgentId,
            Boolean disabled,
            Map<String, Object> properties,
            List<BindingConfigEntry> bindings) {}
}
