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
package io.agentscope.dataagent.web.catalog;

import io.agentscope.dataagent.web.audit.ActivityEvent;
import io.agentscope.dataagent.web.audit.AgentActivityStore;
import io.agentscope.dataagent.web.catalog.AgentCatalogService.AgentCreateRequest;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * REST controller for the agent catalog.
 *
 * <ul>
 *   <li>{@code GET /api/agents} — list all visible agent definitions (global + own custom)
 *   <li>{@code GET /api/agents/{id}} — get a single definition
 *   <li>{@code POST /api/agents} — create a user-custom agent
 *   <li>{@code PUT /api/agents/{id}} — update a user-custom agent (own only)
 *   <li>{@code DELETE /api/agents/{id}} — delete a user-custom agent (own only)
 * </ul>
 */
@RestController
@RequestMapping("/api/agents")
public class AgentCatalogController {

    private final AgentCatalogService catalogService;
    private final AgentAclService aclService;
    private final AgentAccessGuard guard;
    private final AgentActivityStore activity;

    public AgentCatalogController(
            AgentCatalogService catalogService,
            AgentAclService aclService,
            AgentAccessGuard guard,
            AgentActivityStore activity) {
        this.catalogService = catalogService;
        this.aclService = aclService;
        this.guard = guard;
        this.activity = activity;
    }

    /**
     * Lists all agent definitions visible to the authenticated user: global agents first, then
     * the user's own custom agents.
     */
    @GetMapping
    public Mono<List<AgentDefinition>> listAgents(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () ->
                        catalogService.listVisible(userId).stream()
                                .map(def -> withTier(userId, def))
                                .toList());
    }

    /** Gets a single agent definition visible to the authenticated user. */
    @GetMapping("/{id}")
    public Mono<AgentDefinition> getAgent(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () ->
                        withTier(
                                userId,
                                catalogService
                                        .findVisible(userId, id)
                                        .orElseThrow(
                                                () ->
                                                        new org.springframework.web.server
                                                                .ResponseStatusException(
                                                                org.springframework.http.HttpStatus
                                                                        .NOT_FOUND,
                                                                "Agent not found: " + id))));
    }

    /**
     * Decorates an {@link AgentDefinition} returned to the frontend with the calling user's
     * effective tier so the UI can gate tabs and affordances without re-implementing ACL.
     */
    private AgentDefinition withTier(String userId, AgentDefinition def) {
        Tier t = aclService.tierFor(userId, def);
        return def.withTierForCurrentUser(t == null ? null : t.name());
    }

    /** Creates a new user-custom agent definition. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AgentDefinition> createAgent(
            @RequestBody AgentCreateRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    AgentDefinition created = catalogService.createUserAgent(userId, req);
                    activity.record(
                            userId,
                            created.id(),
                            activity.actor(userId),
                            ActivityEvent.Action.CREATE);
                    return created;
                });
    }

    /**
     * Updates a user-custom agent definition. Owner or any EDIT-tier grantee may update; the
     * change is persisted to the owner's namespace regardless of who triggered it.
     */
    @PutMapping("/{id}")
    public Mono<AgentDefinition> updateAgent(
            @PathVariable String id, @RequestBody AgentCreateRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    AgentDefinition def = guard.require(userId, id, Tier.EDIT);
                    String ownerId = def.ownerId();
                    if (ownerId == null) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Global agents cannot be edited via the catalog API");
                    }
                    AgentDefinition updated = catalogService.updateUserAgent(ownerId, id, req);
                    activity.record(
                            ownerId,
                            id,
                            activity.actor(userId),
                            ActivityEvent.Action.EDIT_SETTINGS);
                    return withTier(userId, updated);
                });
    }

    /**
     * Deletes a user-custom agent definition. Owner or EDIT-tier grantee may delete; only the
     * owner's namespace copy is removed.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteAgent(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(
                () -> {
                    AgentDefinition def = guard.require(userId, id, Tier.EDIT);
                    String ownerId = def.ownerId();
                    if (ownerId == null) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Global agents cannot be deleted via the catalog API");
                    }
                    catalogService.deleteUserAgent(ownerId, id);
                    // The owning namespace tree (including activity.jsonl) is removed when the
                    // agent is deleted; we still emit one final event so a workspace audit
                    // sweep can see who triggered the deletion before the log went away.
                    activity.record(
                            ownerId, id, activity.actor(userId), ActivityEvent.Action.DELETE_AGENT);
                });
    }
}
