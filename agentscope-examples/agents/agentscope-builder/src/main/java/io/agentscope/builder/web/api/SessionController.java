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
import io.agentscope.builder.runtime.channel.chatui.ChatUiChannel;
import io.agentscope.builder.runtime.gateway.MsgContext;
import io.agentscope.builder.runtime.session.HistoryResult;
import io.agentscope.builder.runtime.session.SessionAgentManager;
import io.agentscope.builder.runtime.session.SessionEntry;
import io.agentscope.builder.runtime.session.SessionKind;
import io.agentscope.builder.web.catalog.AgentCatalogService;
import io.agentscope.builder.web.catalog.AgentDefinition;
import io.agentscope.builder.web.session.SessionReadStateStore;
import io.agentscope.builder.web.session.SessionTurnParser;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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

    private final SessionAgentManager sessionAgentManager;
    private final SessionReadStateStore readStateStore;
    private final AgentCatalogService catalogService;

    public SessionController(
            BuilderBootstrap builderBootstrap,
            SessionReadStateStore readStateStore,
            AgentCatalogService catalogService) {
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
                    String expectedGateKey = expectedChatGateKey(userId, agentId);
                    List<SessionEntry> matched =
                            sessionAgentManager.allSessions().stream()
                                    .filter(e -> Objects.equals(e.userId(), userId))
                                    .filter(e -> sessionMatchesAgent(e, expectedGateKey))
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
                        String preview = lastMessagePreview(userId, agentId, e);
                        out.add(
                                new InboxEntry(
                                        e.sessionKey(),
                                        e.sessionId(),
                                        e.agentId(),
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
                    String content = readSessionLogContent(userId, agentId, entry);
                    return SessionTurnParser.parse(content);
                });
    }

    @PostMapping("/{key}/reset")
    public Mono<ResetResult> reset(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    requireOwnedSession(agentId, key, userId);
                    boolean ok = sessionAgentManager.resetSession(key);
                    return new ResetResult(key, ok);
                });
    }

    @PatchMapping("/{key}/read")
    public Mono<ReadStateResult> markRead(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    requireOwnedSession(agentId, key, userId);
                    long readAtMs = readStateStore.markRead(userId, key);
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
                    requireOwnedSession(agentId, key, userId);
                    sessionAgentManager.removeSession(key);
                });
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    private SessionEntry requireOwnedSession(String agentId, String key, String userId) {
        SessionEntry entry =
                sessionAgentManager
                        .getSession(key)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Session not found: " + key));
        String expectedGateKey = expectedChatGateKey(userId, agentId);
        if (!Objects.equals(entry.userId(), userId)
                || !sessionMatchesAgent(entry, expectedGateKey)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return entry;
    }

    /**
     * Computes the gateway routing key the chat-ui channel uses for this (userId, agentId) pair.
     * Mirrors {@code ChatController.resolveGateKey} so SessionController can authorize against
     * the same key the gateway uses to register the session, without depending on the
     * HarnessAgent's auto-generated internal id (which is what {@link SessionEntry#agentId()}
     * actually stores).
     */
    private String expectedChatGateKey(String userId, String agentId) {
        if (userId == null || agentId == null) return null;
        String gatewayAgentId = catalogService.peekGatewayAgentId(userId, agentId);
        if (gatewayAgentId == null) return null;
        MsgContext ctx =
                new MsgContext(
                        ChatUiChannel.CHANNEL_ID,
                        null,
                        userId,
                        null,
                        null,
                        Map.of("agentId", gatewayAgentId),
                        userId);
        return ctx.canonicalKey();
    }

    /**
     * Authorizes a session against the URL agent. {@link SessionEntry#agentId()} holds the
     * HarnessAgent's internal UUID (not the gateway/catalog id), so we cannot match by agent id
     * directly. Instead we compare the session's {@code gateKey} (which is deterministically
     * derived from {@code (userId, gatewayAgentId)}) against the expected one for the URL agent.
     * Sub/group sessions that lack a gateKey fall through to a userId-only ownership check —
     * leaking these isn't possible across users because the inbox/turn endpoints are already
     * filtered by {@code entry.userId() == auth principal}.
     */
    private static boolean sessionMatchesAgent(SessionEntry e, String expectedGateKey) {
        if (expectedGateKey == null) return false;
        if (e.kind() == SessionKind.MAIN) {
            return Objects.equals(e.gateKey(), expectedGateKey);
        }
        // For sub/group sessions, gateKey may be unset; userId match upstream is sufficient.
        return e.gateKey() == null || Objects.equals(e.gateKey(), expectedGateKey);
    }

    private String lastMessagePreview(String userId, String agentId, SessionEntry entry) {
        try {
            String content = readSessionLogContent(userId, agentId, entry);
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
     * Reads the chat content for a session. The actual transcript lives at the per-(user, agent)
     * workspace under {@code agents/<inner-agent-id>/sessions/<sessionId>.log.jsonl} (written by
     * the harness {@code MemoryFlushHook} / {@code SessionTree}). The {@link SessionAgentManager}
     * registry stores a stale legacy path ({@code .json}) keyed by the harness UUID rooted at the
     * main-agent workspace, so it cannot be used directly — that path is what produced the
     * "history disappears on remount" regression.
     *
     * <p>Reads go through {@link WorkspaceManager#readManagedWorkspaceFileUtf8} so the same code
     * works whether the workspace is backed by {@code LocalFilesystem} or {@code RemoteFilesystem}
     * — calling {@link Files#readString} on the {@link WorkspaceManager#resolveSessionLogFile}
     * {@link Path} returned by would fail under remote/store-backed setups (the {@link Path} is a
     * logical workspace-rooted path, not a real on-disk location). The relative path used here
     * mirrors {@code SessionTree.logRelativePath} (line 160), so the read is the conjugate of the
     * harness write.
     *
     * <p>{@code ctxUser} mirrors {@code AgentWorkspaceController#resolveContext}: the owner's id
     * for {@link AgentDefinition#SCOPE_USER} agents (so reads land in the same namespace where
     * the chat write happened) and the caller's id otherwise.
     *
     * <p>Falls back to {@link SessionAgentManager#history} when the per-agent workspace cannot be
     * resolved (e.g. the user has lost visibility or the agent definition was removed).
     */
    private String readSessionLogContent(String userId, String agentId, SessionEntry entry) {
        try {
            AgentDefinition def = catalogService.findVisible(userId, agentId).orElse(null);
            HarnessAgent agent =
                    def == null
                            ? null
                            : catalogService.getOrInstantiateRunningAgent(userId, agentId);
            if (agent != null) {
                String ctxUser =
                        AgentDefinition.SCOPE_USER.equals(def.scope())
                                ? (def.ownerId() != null ? def.ownerId() : userId)
                                : userId;
                WorkspaceManager wm = agent.workspaceFor(ctxUser, null);
                // Must match the segment SessionTree writes under: MemoryFlushHook reads
                // `agent.getName()` (the ReActAgent delegate name), not the random UUID
                // returned by getAgentId(). Mirrors claw's SessionController.
                String innerAgentId = agent.getName();
                if (wm != null && innerAgentId != null && !innerAgentId.isBlank()) {
                    String base = "agents/" + innerAgentId + "/sessions/" + entry.sessionId();
                    String log =
                            wm.readManagedWorkspaceFileUtf8(
                                    RuntimeContext.empty(), base + ".log.jsonl");
                    if (log != null && !log.isEmpty()) {
                        return log;
                    }
                    String ctx =
                            wm.readManagedWorkspaceFileUtf8(
                                    RuntimeContext.empty(), base + ".jsonl");
                    if (ctx != null && !ctx.isEmpty()) {
                        return ctx;
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through to history()
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
            String label,
            long lastActivityMs,
            String lastMessage,
            boolean unread) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResetResult(String sessionKey, boolean reset) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReadStateResult(String sessionKey, long readAtMs, boolean unread) {}
}
