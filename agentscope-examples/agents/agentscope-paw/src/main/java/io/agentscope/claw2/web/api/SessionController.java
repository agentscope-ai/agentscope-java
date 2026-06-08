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
import io.agentscope.claw2.runtime.session.HistoryResult;
import io.agentscope.claw2.runtime.session.SessionAgentManager;
import io.agentscope.claw2.runtime.session.SessionEntry;
import io.agentscope.claw2.runtime.session.SessionKind;
import io.agentscope.claw2.web.catalog.AgentCatalogService;
import io.agentscope.claw2.web.session.SessionReadStateStore;
import io.agentscope.claw2.web.session.SessionTurnParser;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
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
 * AgentStateStore management endpoints, scoped to a specific agent.
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
 * <p>Sessions are filtered by the {@code gateKey} the gateway uses to register them; sessions
 * created for a different agent return 403 even though the local single-user app has no
 * cross-user concerns.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/sessions")
public class SessionController {

    private final ClawBootstrap bootstrap;
    private final SessionAgentManager sessionAgentManager;
    private final SessionReadStateStore readStateStore;
    private final AgentCatalogService catalogService;

    public SessionController(
            ClawBootstrap bootstrap,
            SessionReadStateStore readStateStore,
            AgentCatalogService catalogService) {
        this.bootstrap = bootstrap;
        this.sessionAgentManager = bootstrap.gateway().sessionAgentManager();
        this.readStateStore = readStateStore;
        this.catalogService = catalogService;
    }

    @GetMapping("/inbox")
    public Mono<List<InboxEntry>> inbox(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return Mono.fromCallable(
                () -> {
                    String expectedGateKey = expectedChatGateKey(agentId);
                    List<SessionEntry> matched =
                            sessionAgentManager.allSessions().stream()
                                    .filter(e -> sessionMatchesAgent(e, expectedGateKey))
                                    .sorted(
                                            Comparator.comparingLong(SessionEntry::lastActivityMs)
                                                    .reversed())
                                    .limit(limit)
                                    .toList();

                    List<InboxEntry> out = new ArrayList<>(matched.size());
                    for (SessionEntry e : matched) {
                        boolean unread =
                                readStateStore.isUnread(e.sessionKey(), e.lastActivityMs());
                        if (unreadOnly && !unread) continue;
                        String preview = lastMessagePreview(agentId, e);
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
            @PathVariable String agentId, @PathVariable String key) {
        return Mono.fromCallable(
                () -> {
                    SessionEntry entry = requireSession(agentId, key);
                    String content = readSessionLogContent(agentId, entry);
                    return SessionTurnParser.parse(content != null ? content : "");
                });
    }

    @PostMapping("/{key}/reset")
    public Mono<ResetResult> reset(@PathVariable String agentId, @PathVariable String key) {
        return Mono.fromCallable(
                () -> {
                    requireSession(agentId, key);
                    boolean ok = sessionAgentManager.resetSession(key);
                    return new ResetResult(key, ok);
                });
    }

    @PatchMapping("/{key}/read")
    public Mono<ReadStateResult> markRead(@PathVariable String agentId, @PathVariable String key) {
        return Mono.fromCallable(
                () -> {
                    requireSession(agentId, key);
                    long readAtMs = readStateStore.markRead(key);
                    return new ReadStateResult(key, readAtMs, false);
                });
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String agentId, @PathVariable String key) {
        return Mono.fromRunnable(
                () -> {
                    requireSession(agentId, key);
                    sessionAgentManager.removeSession(key);
                });
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    private SessionEntry requireSession(String agentId, String key) {
        SessionEntry entry =
                sessionAgentManager
                        .getSession(key)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "AgentStateStore not found: " + key));
        String expectedGateKey = expectedChatGateKey(agentId);
        if (!sessionMatchesAgent(entry, expectedGateKey)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return entry;
    }

    /**
     * Computes the gateway routing key the chat-ui channel uses for {@code agentId}. Mirrors
     * {@code ChatController#resolveGateKey} so this controller can authorize against the same
     * key the gateway uses to register the session.
     */
    private String expectedChatGateKey(String agentId) {
        if (agentId == null) return null;
        String gatewayAgentId;
        try {
            gatewayAgentId = catalogService.resolveGatewayAgentId(agentId);
        } catch (ResponseStatusException ex) {
            return null;
        }
        if (gatewayAgentId == null) return null;
        MsgContext ctx =
                new MsgContext(
                        ChatUiChannel.CHANNEL_ID,
                        null,
                        null,
                        null,
                        null,
                        Map.of("agentId", gatewayAgentId));
        return ctx.canonicalKey();
    }

    /**
     * Authorizes a session against the URL agent. {@link SessionEntry#agentId()} holds the
     * HarnessAgent's internal UUID (not the gateway/catalog id), so we cannot match by agent id
     * directly. Instead we compare the session's {@code gateKey} (which is deterministically
     * derived from {@code gatewayAgentId}) against the expected one for the URL agent.
     * Sub/group sessions that lack a {@code gateKey} are passed through (they have no inbox
     * visibility regardless).
     */
    private static boolean sessionMatchesAgent(SessionEntry e, String expectedGateKey) {
        if (expectedGateKey == null) return false;
        if (e.kind() == SessionKind.MAIN) {
            return Objects.equals(e.gateKey(), expectedGateKey);
        }
        return e.gateKey() == null || Objects.equals(e.gateKey(), expectedGateKey);
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
     * Reads the chat content for a session. The actual transcript lives at the per-agent
     * workspace under {@code agents/<delegate-name>/sessions/<sessionId>.log.jsonl} (written by
     * the harness {@code MemoryFlushHook}/{@code SessionTree}). The {@link SessionAgentManager}
     * registry stores a stale legacy path ({@code .json}) keyed by the harness UUID rooted at
     * the main-agent workspace, so it cannot be used directly.
     *
     * <p>Falls back to {@link SessionAgentManager#history} when the per-agent workspace is not
     * resolvable (e.g. the agent has been unloaded).
     */
    private String readSessionLogContent(String urlAgentId, SessionEntry entry) {
        // Look up via the gateway registry (not bootstrap.agents()): the gateway holds
        // both built-in agents and custom agents added at runtime via
        // AgentCatalogService.buildAndRegisterCustom, while bootstrap.agents() is a
        // build-time-only snapshot.
        HarnessAgent ha = bootstrap.gateway().getAgent(urlAgentId);
        if (ha != null) {
            WorkspaceManager wm = ha.getWorkspaceManager();
            String innerAgentId = ha.getName();
            if (wm != null && innerAgentId != null && !innerAgentId.isBlank()) {
                Path logFile =
                        wm.resolveSessionLogFile(
                                RuntimeContext.empty(), innerAgentId, entry.sessionId());
                if (Files.isRegularFile(logFile)) {
                    try {
                        return Files.readString(logFile, StandardCharsets.UTF_8);
                    } catch (Exception ignored) {
                        // fall through to history()
                    }
                }
                Path contextFile =
                        wm.resolveSessionContextFile(
                                RuntimeContext.empty(), innerAgentId, entry.sessionId());
                if (Files.isRegularFile(contextFile)) {
                    try {
                        return Files.readString(contextFile, StandardCharsets.UTF_8);
                    } catch (Exception ignored) {
                        // fall through to history()
                    }
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
            String label,
            long lastActivityMs,
            String lastMessage,
            boolean unread) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResetResult(String sessionKey, boolean reset) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReadStateResult(String sessionKey, long readAtMs, boolean unread) {}
}
