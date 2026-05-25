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

import io.agentscope.builder.web.audit.ActivityEvent;
import io.agentscope.builder.web.audit.AgentActivityStore;
import io.agentscope.builder.web.catalog.AgentDefinition;
import io.agentscope.builder.web.catalog.UserAgentDefinitionStore;
import io.agentscope.builder.web.catalog.UserAgentDefinitionStore.StoredEntry;
import io.agentscope.builder.web.share.AgentAccessGuard;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import io.agentscope.builder.web.share.AgentShareGrant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Sharing endpoints on user-custom agents. Globals are not share-managed (they're config-defined,
 * RUN-to-everyone via the ACL service).
 *
 * <ul>
 *   <li>{@code GET /api/agents/{id}/shares} — read grants (anyone ≥ RUN).
 *   <li>{@code POST /api/agents/{id}/shares} — add or upsert a grant (EDIT only).
 *   <li>{@code DELETE /api/agents/{id}/shares/{granteeType}/{granteeId}} — revoke (EDIT only).
 * </ul>
 */
@RestController
@RequestMapping("/api/agents/{id}/shares")
public class AgentShareController {

    private static final Logger log = LoggerFactory.getLogger(AgentShareController.class);

    private final UserAgentDefinitionStore store;
    private final AgentAccessGuard guard;
    private final AgentActivityStore activity;

    public AgentShareController(
            UserAgentDefinitionStore store, AgentAccessGuard guard, AgentActivityStore activity) {
        this.store = store;
        this.guard = guard;
        this.activity = activity;
    }

    @GetMapping
    public Mono<List<AgentShareGrant>> list(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    AgentDefinition def = guard.require(userId, id, Tier.RUN);
                    if (!AgentDefinition.SCOPE_USER.equals(def.scope())) {
                        // Globals don't have a share list; return empty rather than 404.
                        return List.<AgentShareGrant>of();
                    }
                    List<AgentShareGrant> shares = def.shares();
                    return shares != null ? shares : List.<AgentShareGrant>of();
                });
    }

    @PostMapping
    public Mono<List<AgentShareGrant>> add(
            @PathVariable String id, @RequestBody AddShareRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    if (req == null || req.granteeType() == null || req.tier() == null) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "granteeType and tier are required");
                    }
                    AgentDefinition def = guard.require(userId, id, Tier.EDIT);
                    if (!AgentDefinition.SCOPE_USER.equals(def.scope())) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Cannot share a global agent; edit agentscope.json instead");
                    }
                    String granteeType = req.granteeType().trim().toUpperCase(Locale.ROOT);
                    String granteeId;
                    if (AgentShareGrant.GRANTEE_WORKSPACE.equals(granteeType)) {
                        granteeId = AgentShareGrant.WORKSPACE_ID;
                    } else if (AgentShareGrant.GRANTEE_USER.equals(granteeType)) {
                        if (req.granteeId() == null || req.granteeId().isBlank()) {
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "granteeId is required for USER grants");
                        }
                        granteeId = req.granteeId().trim();
                    } else {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "granteeType must be USER or WORKSPACE");
                    }
                    String tier = req.tier().trim().toUpperCase(Locale.ROOT);
                    if (!AgentShareGrant.TIER_CLONE.equals(tier)
                            && !AgentShareGrant.TIER_RUN.equals(tier)
                            && !AgentShareGrant.TIER_EDIT.equals(tier)) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "tier must be CLONE, RUN, or EDIT");
                    }
                    String ownerId = def.ownerId();
                    StoredEntry entry =
                            store.findById(ownerId, id)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND,
                                                            "Agent not found: " + id));
                    List<AgentShareGrant> next = new ArrayList<>();
                    if (entry.shares() != null) {
                        for (AgentShareGrant g : entry.shares()) {
                            if (g.granteeType().equals(granteeType)
                                    && g.granteeId().equals(granteeId)) {
                                continue; // replace
                            }
                            next.add(g);
                        }
                    }
                    next.add(
                            new AgentShareGrant(
                                    granteeType,
                                    granteeId,
                                    tier,
                                    System.currentTimeMillis(),
                                    userId));
                    StoredEntry updated = withShares(entry, next);
                    store.save(ownerId, updated);
                    log.info(
                            "Share granted on {}/{}: ({}, {}, {}) by {}",
                            ownerId,
                            id,
                            granteeType,
                            granteeId,
                            tier,
                            userId);
                    activity.record(
                            ownerId,
                            id,
                            activity.actor(userId),
                            ActivityEvent.Action.GRANT_SHARE,
                            granteeType + ":" + granteeId,
                            Map.of("tier", tier));
                    return List.copyOf(next);
                });
    }

    @DeleteMapping("/{granteeType}/{granteeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> revoke(
            @PathVariable String id,
            @PathVariable String granteeType,
            @PathVariable String granteeId,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(
                () -> {
                    AgentDefinition def = guard.require(userId, id, Tier.EDIT);
                    if (!AgentDefinition.SCOPE_USER.equals(def.scope())) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Globals have no shares to revoke");
                    }
                    String ownerId = def.ownerId();
                    StoredEntry entry =
                            store.findById(ownerId, id)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND,
                                                            "Agent not found: " + id));
                    if (entry.shares() == null || entry.shares().isEmpty()) {
                        return;
                    }
                    String gType = granteeType.toUpperCase(Locale.ROOT);
                    List<AgentShareGrant> next = new ArrayList<>();
                    boolean changed = false;
                    for (AgentShareGrant g : entry.shares()) {
                        if (g.granteeType().equals(gType) && g.granteeId().equals(granteeId)) {
                            changed = true;
                            continue;
                        }
                        next.add(g);
                    }
                    if (!changed) return;
                    store.save(ownerId, withShares(entry, next));
                    log.info(
                            "Share revoked on {}/{}: ({}, {}) by {}",
                            ownerId,
                            id,
                            gType,
                            granteeId,
                            userId);
                    activity.record(
                            ownerId,
                            id,
                            activity.actor(userId),
                            ActivityEvent.Action.REVOKE_SHARE,
                            gType + ":" + granteeId,
                            null);
                });
    }

    private static StoredEntry withShares(StoredEntry e, List<AgentShareGrant> newShares) {
        return new StoredEntry(
                e.id(),
                e.name(),
                e.description(),
                e.sysPrompt(),
                e.model(),
                e.maxIters(),
                e.toolsAllow(),
                e.toolsDeny(),
                e.identityName(),
                e.identityEmoji(),
                e.groupChatMentionPatterns(),
                e.groupChatRequireMention(),
                e.skillsAllow(),
                e.skillsDeny(),
                e.createdAt(),
                e.updatedAt(),
                newShares == null || newShares.isEmpty() ? null : List.copyOf(newShares),
                e.runAs(),
                e.forkOf(),
                e.workspacePath(),
                e.skillRepositories(),
                e.sandboxMode(),
                e.sandboxScope());
    }

    public record AddShareRequest(String granteeType, String granteeId, String tier) {}
}
