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
import io.agentscope.dataagent.runtime.config.BindingConfigEntry;
import io.agentscope.dataagent.runtime.config.ChannelConfigEntry;
import io.agentscope.dataagent.web.audit.ActivityEvent;
import io.agentscope.dataagent.web.audit.AgentActivityStore;
import io.agentscope.dataagent.web.catalog.AgentDefinition;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Per-agent channel binding management.
 *
 * <ul>
 *   <li>{@code GET    /api/agents/{agentId}/bindings} — list every binding for the agent across
 *       all channels
 *   <li>{@code POST   /api/agents/{agentId}/bindings} — append a new binding to a channel
 *   <li>{@code PUT    /api/agents/{agentId}/bindings/{index}?channelId=…} — replace a binding
 *   <li>{@code DELETE /api/agents/{agentId}/bindings/{index}?channelId=…} — remove a binding
 * </ul>
 *
 * <p>All edits are persisted to {@code agentscope.json} and applied to the live channel registry
 * via {@link BindingPersistence}.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/bindings")
public class AgentBindingController {

    private final BindingPersistence persistence;
    private final AgentAccessGuard guard;
    private final AgentActivityStore activity;

    public AgentBindingController(
            BindingPersistence persistence, AgentAccessGuard guard, AgentActivityStore activity) {
        this.persistence = persistence;
        this.guard = guard;
        this.activity = activity;
    }

    @GetMapping
    public Mono<List<AgentBindingView>> list(@PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        return Mono.fromCallable(
                () ->
                        persistence.mutate(
                                channels -> {
                                    List<AgentBindingView> out = new ArrayList<>();
                                    for (Map.Entry<String, ChannelConfigEntry> e :
                                            channels.entrySet()) {
                                        ChannelConfigEntry ch = e.getValue();
                                        if (ch == null || ch.getBindings() == null) continue;
                                        List<BindingConfigEntry> list = ch.getBindings();
                                        for (int i = 0; i < list.size(); i++) {
                                            BindingConfigEntry b = list.get(i);
                                            if (b == null) continue;
                                            if (!agentId.equals(b.getAgentId())) continue;
                                            out.add(toView(e.getKey(), i, b));
                                        }
                                    }
                                    return out;
                                },
                                List.of()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AgentBindingView> add(
            @PathVariable String agentId,
            @RequestBody BindingCreateRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        AgentDefinition def = guard.require(userId, agentId, Tier.EDIT);
        return Mono.fromCallable(
                () -> {
                    if (req == null || req.channelId() == null || req.channelId().isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "channelId is required");
                    }
                    AgentBindingView view =
                            persistence.mutate(
                                    channels -> {
                                        ChannelConfigEntry ch =
                                                persistence.orCreate(channels, req.channelId());
                                        List<BindingConfigEntry> list =
                                                persistence.mutableBindings(ch);
                                        BindingConfigEntry entry = fromRequest(agentId, req);
                                        list.add(entry);
                                        return toView(req.channelId(), list.size() - 1, entry);
                                    },
                                    List.of(req.channelId()));
                    if (def.ownerId() != null) {
                        activity.record(
                                def.ownerId(),
                                agentId,
                                activity.actor(userId),
                                ActivityEvent.Action.BIND_CHANNEL,
                                req.channelId(),
                                null);
                    }
                    return view;
                });
    }

    @PutMapping("/{index}")
    public Mono<AgentBindingView> update(
            @PathVariable String agentId,
            @PathVariable int index,
            @RequestParam("channelId") String channelId,
            @RequestBody BindingCreateRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        AgentDefinition def = guard.require(userId, agentId, Tier.EDIT);
        return Mono.fromCallable(
                () -> {
                    AgentBindingView view =
                            persistence.mutate(
                                    channels -> {
                                        ChannelConfigEntry ch = channels.get(channelId);
                                        if (ch == null || ch.getBindings() == null) {
                                            throw new ResponseStatusException(
                                                    HttpStatus.NOT_FOUND,
                                                    "Channel has no bindings: " + channelId);
                                        }
                                        List<BindingConfigEntry> list =
                                                persistence.mutableBindings(ch);
                                        if (index < 0 || index >= list.size()) {
                                            throw new ResponseStatusException(
                                                    HttpStatus.NOT_FOUND,
                                                    "Binding index out of range: " + index);
                                        }
                                        BindingConfigEntry existing = list.get(index);
                                        if (existing == null
                                                || !agentId.equals(existing.getAgentId())) {
                                            throw new ResponseStatusException(
                                                    HttpStatus.FORBIDDEN,
                                                    "Binding does not belong to agent: " + agentId);
                                        }
                                        BindingConfigEntry updated = fromRequest(agentId, req);
                                        list.set(index, updated);
                                        return toView(channelId, index, updated);
                                    },
                                    List.of(channelId));
                    if (def.ownerId() != null) {
                        activity.record(
                                def.ownerId(),
                                agentId,
                                activity.actor(userId),
                                ActivityEvent.Action.EDIT_BINDING,
                                channelId,
                                null);
                    }
                    return view;
                });
    }

    @DeleteMapping("/{index}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(
            @PathVariable String agentId,
            @PathVariable int index,
            @RequestParam("channelId") String channelId,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        AgentDefinition def = guard.require(userId, agentId, Tier.EDIT);
        return Mono.fromRunnable(
                () -> {
                    persistence.mutate(
                            channels -> {
                                ChannelConfigEntry ch = channels.get(channelId);
                                if (ch == null || ch.getBindings() == null) {
                                    throw new ResponseStatusException(
                                            HttpStatus.NOT_FOUND,
                                            "Channel has no bindings: " + channelId);
                                }
                                List<BindingConfigEntry> list = persistence.mutableBindings(ch);
                                if (index < 0 || index >= list.size()) {
                                    throw new ResponseStatusException(
                                            HttpStatus.NOT_FOUND,
                                            "Binding index out of range: " + index);
                                }
                                BindingConfigEntry existing = list.get(index);
                                if (existing == null || !agentId.equals(existing.getAgentId())) {
                                    throw new ResponseStatusException(
                                            HttpStatus.FORBIDDEN,
                                            "Binding does not belong to agent: " + agentId);
                                }
                                list.remove(index);
                                return null;
                            },
                            List.of(channelId));
                    if (def.ownerId() != null) {
                        activity.record(
                                def.ownerId(),
                                agentId,
                                activity.actor(userId),
                                ActivityEvent.Action.UNBIND_CHANNEL,
                                channelId,
                                null);
                    }
                });
    }

    // -----------------------------------------------------------------
    //  Mapping helpers
    // -----------------------------------------------------------------

    static BindingConfigEntry fromRequest(String agentId, BindingCreateRequest req) {
        BindingConfigEntry e = new BindingConfigEntry();
        e.setAgentId(agentId);
        e.setPeer(blankToNull(req.peer()));
        e.setParentPeer(blankToNull(req.parentPeer()));
        e.setGuild(blankToNull(req.guild()));
        if (req.roles() != null && !req.roles().isEmpty()) {
            e.setRoles(List.copyOf(req.roles()));
        }
        e.setTeam(blankToNull(req.team()));
        e.setAccount(blankToNull(req.account()));
        e.setChannel(blankToNull(req.channel()));
        e.setSessionScope(blankToNull(req.sessionScope()));
        return e;
    }

    static AgentBindingView toView(String channelId, int index, BindingConfigEntry b) {
        return new AgentBindingView(
                channelId,
                index,
                deriveTier(b),
                b.getPeer(),
                b.getParentPeer(),
                b.getGuild(),
                b.getRoles(),
                b.getTeam(),
                b.getAccount(),
                b.getChannel(),
                b.getSessionScope());
    }

    /**
     * Derives the matching tier label for the frontend, using the same priority order as
     * {@link io.agentscope.harness.agent.gateway.channel.ChannelRouter}.
     */
    static String deriveTier(BindingConfigEntry b) {
        if (notBlank(b.getPeer())) return "peer";
        if (notBlank(b.getParentPeer())) return "parentPeer";
        if (notBlank(b.getGuild())) {
            return b.getRoles() != null && !b.getRoles().isEmpty() ? "guildRoles" : "guild";
        }
        if (notBlank(b.getTeam())) return "team";
        if (notBlank(b.getAccount())) return "account";
        if (notBlank(b.getChannel())) return "channel";
        return "channel";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BindingCreateRequest(
            String channelId,
            String tier,
            String peer,
            String parentPeer,
            String guild,
            List<String> roles,
            String team,
            String account,
            String channel,
            String sessionScope) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentBindingView(
            String channelId,
            int index,
            String tier,
            String peer,
            String parentPeer,
            String guild,
            List<String> roles,
            String team,
            String account,
            String channel,
            String sessionScope) {}
}
