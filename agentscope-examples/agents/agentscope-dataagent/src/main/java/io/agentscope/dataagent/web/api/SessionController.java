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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.session.HistoryResult;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.runtime.session.SessionKind;
import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import io.agentscope.dataagent.web.session.SessionReadStateStore;
import io.agentscope.dataagent.web.session.SessionTurnParser;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Session management endpoints, scoped to a specific agent.
 *
 * <ul>
 *   <li>{@code GET /api/agents/{agentId}/sessions/inbox} — paginated session list with previews
 *       and unread flags
 *   <li>{@code GET /api/agents/{agentId}/sessions/{key}} — structured turn-by-turn transcript
 *   <li>{@code POST /api/agents/{agentId}/sessions/{key}/reset} — clear conversation history
 *   <li>{@code PATCH /api/agents/{agentId}/sessions/{key}/read} — mark session read
 *   <li>{@code DELETE /api/agents/{agentId}/sessions/{key}} — drop the session entirely
 * </ul>
 *
 * <p>All endpoints require the session to belong to both the authenticated user <em>and</em> the
 * agent in the URL path; mismatches return 403.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/sessions")
public class SessionController {

    private final DataAgentBootstrap bootstrap;
    private final SessionAgentManager sessionAgentManager;
    private final SessionReadStateStore readStateStore;
    private final AgentCatalogService catalogService;

    public SessionController(
            DataAgentBootstrap builderBootstrap,
            SessionReadStateStore readStateStore,
            AgentCatalogService catalogService) {
        this.bootstrap = builderBootstrap;
        this.sessionAgentManager = builderBootstrap.gateway().sessionAgentManager();
        this.readStateStore = readStateStore;
        this.catalogService = catalogService;
    }

    @GetMapping("/inbox")
    public Mono<List<InboxEntry>> inbox(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    String gatewayAgentId = catalogService.peekGatewayAgentId(userId, agentId);
                    List<SessionEntry> matched =
                            sessionAgentManager.allSessions().stream()
                                    .filter(e -> Objects.equals(e.userId(), userId))
                                    .filter(e -> sessionMatchesAgent(e, gatewayAgentId))
                                    .sorted(
                                            Comparator.comparingLong(SessionEntry::lastActivityMs)
                                                    .reversed())
                                    .limit(limit)
                                    .toList();

                    List<InboxEntry> out = new ArrayList<>(matched.size());
                    for (SessionEntry e : matched) {
                        boolean unread =
                                readStateStore.isUnread(userId, e.sessionKey(), e.lastActivityMs());
                        if (unreadOnly && !unread) continue;
                        String preview = lastMessagePreview(agentId, e);
                        out.add(
                                new InboxEntry(
                                        e.sessionKey(),
                                        e.sessionId(),
                                        e.agentId(),
                                        extractConversationId(e.gateKey()),
                                        e.label(),
                                        e.lastActivityMs(),
                                        preview,
                                        unread));
                    }
                    return out;
                });
    }

    @GetMapping("/{key}")
    public Mono<List<SessionTurnParser.TurnEntry>> turns(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    SessionEntry entry = requireOwnedSession(agentId, key, userId);
                    String content = readSessionLogContent(agentId, entry);
                    return SessionTurnParser.parse(content != null ? content : "");
                });
    }

    @PostMapping("/{key}/reset")
    public Mono<ResetResult> reset(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    SessionEntry entry = requireOwnedSession(agentId, key, userId);
                    boolean ok = sessionAgentManager.resetSession(entry.sessionKey());
                    return new ResetResult(key, ok);
                });
    }

    @PatchMapping("/{key}/read")
    public Mono<ReadStateResult> markRead(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    SessionEntry entry = requireOwnedSession(agentId, key, userId);
                    long readAtMs = readStateStore.markRead(userId, entry.sessionKey());
                    return new ReadStateResult(key, readAtMs, false);
                });
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(
                () -> {
                    SessionEntry entry = requireOwnedSession(agentId, key, userId);
                    sessionAgentManager.removeSession(entry.sessionKey());
                });
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    /**
     * Resolves a path-level {@code key} to a {@link SessionEntry} owned by {@code userId} for the
     * URL {@code agentId}. {@code key} may be either the internal storage key (legacy callers) or
     * the conversationId surfaced via {@link InboxEntry#conversationId()} — the FE only ever sees
     * the latter for ChatGPT-style multi-session navigation.
     */
    private SessionEntry requireOwnedSession(String agentId, String key, String userId) {
        SessionEntry entry =
                sessionAgentManager
                        .getSession(key)
                        .orElseGet(() -> findSessionByConversationId(agentId, key, userId));
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + key);
        }
        String gatewayAgentId = catalogService.peekGatewayAgentId(userId, agentId);
        if (!Objects.equals(entry.userId(), userId)
                || !sessionMatchesAgent(entry, gatewayAgentId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return entry;
    }

    /**
     * Scans registered MAIN sessions for one whose {@code gateKey} carries {@code |t:<key>} and
     * matches the user+agent pair. Returns {@code null} if no match.
     */
    private SessionEntry findSessionByConversationId(String agentId, String key, String userId) {
        if (key == null || key.isBlank()) return null;
        String gatewayAgentId = catalogService.peekGatewayAgentId(userId, agentId);
        for (SessionEntry e : sessionAgentManager.allSessions()) {
            if (e.kind() != SessionKind.MAIN) continue;
            if (!Objects.equals(userId, e.userId())) continue;
            if (!sessionMatchesAgent(e, gatewayAgentId)) continue;
            if (key.equals(extractConversationId(e.gateKey()))) {
                return e;
            }
        }
        return null;
    }

    /**
     * Authorizes a session against the URL agent. {@link SessionEntry#agentId()} holds the
     * HarnessAgent's internal UUID (not the gateway/catalog id), so we cannot match by agent id
     * directly. Instead we look at the session's {@code gateKey} (which is deterministically
     * derived from {@code (userId, gatewayAgentId, conversationId)}) and check that it carries the
     * expected gatewayAgentId in its {@code |x:agentId=...} segment — independent of any
     * {@code |t:<conversationId>} that distinguishes ChatGPT-style sessions for the same agent.
     * Sub/group sessions that lack a gateKey fall through to a userId-only ownership check —
     * leaking these isn't possible across users because the inbox/turn endpoints are already
     * filtered by {@code entry.userId() == auth principal}.
     */
    private static boolean sessionMatchesAgent(SessionEntry e, String gatewayAgentId) {
        if (gatewayAgentId == null) return false;
        String gateKey = e.gateKey();
        if (e.kind() == SessionKind.MAIN) {
            return gateKey != null && extractGatewayAgentId(gateKey).equals(gatewayAgentId);
        }
        // For sub/group sessions, gateKey may be unset; userId match upstream is sufficient.
        return gateKey == null || extractGatewayAgentId(gateKey).equals(gatewayAgentId);
    }

    /**
     * Extracts the {@code agentId} value from a canonical gateKey segment of the form
     * {@code |x:agentId=<value>}. Returns an empty string if no such segment is present.
     */
    private static String extractGatewayAgentId(String gateKey) {
        String needle = "|x:agentId=";
        int i = gateKey.indexOf(needle);
        if (i < 0) return "";
        int start = i + needle.length();
        int end = gateKey.indexOf('|', start);
        return end < 0 ? gateKey.substring(start) : gateKey.substring(start, end);
    }

    /**
     * Extracts the conversationId (the threadId portion of {@link
     * io.agentscope.dataagent.runtime.gateway.MsgContext}) from a canonical gateKey segment of the
     * form {@code |t:<value>}. Returns {@code null} when the gateKey is missing or has no thread
     * segment — pre-multi-session sessions live with a {@code null} conversationId and can still be
     * addressed by their storage key.
     */
    static String extractConversationId(String gateKey) {
        if (gateKey == null) return null;
        String needle = "|t:";
        int i = gateKey.indexOf(needle);
        if (i < 0) return null;
        int start = i + needle.length();
        int end = gateKey.indexOf('|', start);
        String val = end < 0 ? gateKey.substring(start) : gateKey.substring(start, end);
        return val.isEmpty() ? null : val;
    }

    private String lastMessagePreview(String agentId, SessionEntry entry) {
        try {
            String content = readSessionLogContent(agentId, entry);
            if (content == null || content.isEmpty()) {
                return null;
            }
            List<SessionTurnParser.TurnEntry> turns = SessionTurnParser.parse(content);
            for (int i = turns.size() - 1; i >= 0; i--) {
                SessionTurnParser.TurnEntry t = turns.get(i);
                if (t.content() != null && !t.content().isBlank()) {
                    String trimmed = t.content().trim();
                    return trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    /**
     * Reads the chat content for a session. The actual transcript lives at the per-agent workspace
     * under {@code agents/<innerAgentId>/sessions/<sessionId>.log.jsonl}, written by the harness
     * memory hooks. The {@link SessionAgentManager} registry stores a stale legacy path
     * ({@code .json}) keyed by the harness UUID rooted at the main-agent workspace, so its
     * {@code history(...)} cannot be used here.
     *
     * <p>Reads go through the per-agent {@link WorkspaceManager}'s composite filesystem (which is
     * what the harness writes through), so multi-tenant deployments backed by the shared
     * {@link io.agentscope.harness.agent.store.BaseStore} stay correct. Falls back to
     * {@link SessionAgentManager#history} only if the WorkspaceManager cannot be resolved
     * (e.g. the agent has been unloaded).
     */
    private String readSessionLogContent(String urlAgentId, SessionEntry entry) {
        String gatewayAgentId = catalogService.peekGatewayAgentId(entry.userId(), urlAgentId);
        HarnessAgent ha =
                gatewayAgentId != null ? bootstrap.gateway().findAgent(gatewayAgentId) : null;
        if (ha != null) {
            WorkspaceManager wm = ha.getWorkspaceManager();
            String innerAgentId = ha.getName();
            if (wm != null && innerAgentId != null && !innerAgentId.isBlank()) {
                String relLog =
                        "agents/" + innerAgentId + "/sessions/" + entry.sessionId() + ".log.jsonl";
                String fromLog = wm.readManagedWorkspaceFileUtf8(RuntimeContext.empty(), relLog);
                if (fromLog != null && !fromLog.isEmpty()) {
                    return fromLog;
                }
                String relCtx =
                        "agents/" + innerAgentId + "/sessions/" + entry.sessionId() + ".jsonl";
                String fromCtx = wm.readManagedWorkspaceFileUtf8(RuntimeContext.empty(), relCtx);
                if (fromCtx != null && !fromCtx.isEmpty()) {
                    return fromCtx;
                }
            }
        }
        HistoryResult raw = sessionAgentManager.history(entry.sessionKey(), 0);
        if (raw == null || raw.error() != null) {
            return "";
        }
        return raw.content() != null ? raw.content() : "";
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InboxEntry(
            String sessionKey,
            String sessionId,
            String agentId,
            String conversationId,
            String label,
            long lastActivityMs,
            String lastMessage,
            boolean unread) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResetResult(String sessionKey, boolean reset) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReadStateResult(String sessionKey, long readAtMs, boolean unread) {}
}
