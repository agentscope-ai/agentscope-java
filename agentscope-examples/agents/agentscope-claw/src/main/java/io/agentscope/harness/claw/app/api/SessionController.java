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
package io.agentscope.harness.claw.app.api;

import io.agentscope.harness.claw.ClawBootstrap;
import io.agentscope.harness.claw.app.session.SessionTurnParser;
import io.agentscope.harness.claw.app.session.UserWorkspaceProvisioner;
import io.agentscope.harness.claw.app.toolbus.WorkspaceTimelineReader;
import io.agentscope.harness.claw.session.HistoryResult;
import io.agentscope.harness.claw.session.SessionAgentManager;
import io.agentscope.harness.claw.session.SessionEntry;
import io.agentscope.harness.claw.session.SessionView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * REST controller for user-scoped session management.
 *
 * <ul>
 *   <li>{@code GET /api/sessions} — lists the caller's sessions
 *   <li>{@code GET /api/sessions/{key}/history} — raw JSONL transcript
 *   <li>{@code GET /api/sessions/{key}/turns} — parsed turn-by-turn transcript
 *   <li>{@code POST /api/sessions/{key}/reset} — reset a session (owner or admin)
 * </ul>
 */
@RestController
public class SessionController {

    private final SessionAgentManager sessionAgentManager;
    private final UserWorkspaceProvisioner userWorkspaceProvisioner;

    public SessionController(
            ClawBootstrap clawBootstrap, UserWorkspaceProvisioner userWorkspaceProvisioner) {
        this.sessionAgentManager = clawBootstrap.gateway().sessionAgentManager();
        this.userWorkspaceProvisioner = userWorkspaceProvisioner;
    }

    @GetMapping("/api/sessions")
    public Mono<List<SessionView>> listSessions(
            @RequestParam(defaultValue = "50") int limit, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () ->
                        sessionAgentManager.allSessions().stream()
                                .filter(e -> Objects.equals(e.userId(), userId))
                                .sorted(
                                        java.util.Comparator.comparingLong(
                                                        SessionEntry::lastActivityMs)
                                                .reversed())
                                .limit(limit)
                                .map(SessionView::from)
                                .toList());
    }

    @GetMapping("/api/sessions/{key}/history")
    public Mono<HistoryResult> sessionHistory(@PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    SessionEntry entry = getSessionForUser(key, userId);
                    return sessionAgentManager.history(key, 0);
                });
    }

    @GetMapping("/api/sessions/{key}/turns")
    public Mono<List<SessionTurnParser.TurnEntry>> sessionTurns(
            @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    getSessionForUser(key, userId);
                    HistoryResult raw = sessionAgentManager.history(key, 0);
                    if (raw.error() != null) {
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR, raw.error());
                    }
                    return SessionTurnParser.parse(raw.content() != null ? raw.content() : "");
                });
    }

    @GetMapping("/api/sessions/{key}/workspace/events")
    public Mono<List<WorkspaceTimelineReader.MutationEntry>> workspaceEvents(
            @PathVariable String key,
            @RequestParam(required = false) Long since,
            @RequestParam(defaultValue = "200") int limit,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    SessionEntry entry = getSessionForUser(key, userId);
                    String ownerId = entry.userId() != null ? entry.userId() : userId;
                    return WorkspaceTimelineReader.readEvents(
                            userWorkspaceProvisioner.resolveUserWorkspace(ownerId),
                            entry.sessionId(),
                            since,
                            limit);
                });
    }

    /**
     * Returns the sub-agent fan-out tree rooted at one of the caller's own sessions. Mirrors the
     * admin equivalent at {@code /api/admin/runtime/sessions/{key}/tree} but is filtered to the
     * caller's ownership so users can debug their own multi-agent runs without admin privilege.
     */
    @GetMapping("/api/sessions/{key}/tree")
    public Mono<SessionTreeNode> sessionTree(@PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    SessionEntry root = getSessionForUser(key, userId);
                    Map<String, List<SessionEntry>> byParent = indexChildrenForUser(userId);
                    return buildTree(root, byParent, 0, 8);
                });
    }

    @PostMapping("/api/sessions/{key}/reset")
    public Mono<ResetResult> resetSession(@PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        boolean isAdmin =
                auth.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_ADMIN".equalsIgnoreCase(a.getAuthority()));
        return Mono.fromCallable(
                () -> {
                    SessionEntry entry =
                            sessionAgentManager
                                    .getSession(key)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND,
                                                            "Session not found: " + key));
                    if (!isAdmin && !Objects.equals(entry.userId(), userId)) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
                    }
                    boolean ok = sessionAgentManager.resetSession(key);
                    return new ResetResult(key, ok);
                });
    }

    private Map<String, List<SessionEntry>> indexChildrenForUser(String userId) {
        Map<String, List<SessionEntry>> byParent = new HashMap<>();
        for (SessionEntry e : sessionAgentManager.allSessions()) {
            if (!Objects.equals(e.userId(), userId)) continue;
            String parent = e.spawnedBy();
            if (parent != null && !parent.isBlank()) {
                byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(e);
            }
        }
        return byParent;
    }

    private static SessionTreeNode buildTree(
            SessionEntry node, Map<String, List<SessionEntry>> byParent, int depth, int maxDepth) {
        List<SessionTreeNode> children = new ArrayList<>();
        if (depth < maxDepth) {
            List<SessionEntry> kids = byParent.get(node.sessionKey());
            if (kids != null) {
                kids.sort(Comparator.comparingLong(SessionEntry::createdAtMs));
                for (SessionEntry kid : kids) {
                    children.add(buildTree(kid, byParent, depth + 1, maxDepth));
                }
            }
        }
        return new SessionTreeNode(
                node.sessionKey(),
                node.agentId(),
                node.sessionId(),
                node.label(),
                node.kind() != null ? node.kind().name() : "?",
                node.spawnedBy(),
                node.spawnDepth(),
                node.createdAtMs(),
                node.lastActivityMs(),
                children);
    }

    public record SessionTreeNode(
            String sessionKey,
            String agentId,
            String sessionId,
            String label,
            String kind,
            String spawnedBy,
            int spawnDepth,
            long createdAtMs,
            long lastActivityMs,
            List<SessionTreeNode> children) {}

    private SessionEntry getSessionForUser(String key, String userId) {
        SessionEntry entry =
                sessionAgentManager
                        .getSession(key)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Session not found: " + key));
        if (!Objects.equals(entry.userId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return entry;
    }

    public record ResetResult(String sessionKey, boolean reset) {}
}
