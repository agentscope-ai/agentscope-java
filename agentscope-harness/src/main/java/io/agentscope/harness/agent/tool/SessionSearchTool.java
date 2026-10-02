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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLogStore;
import io.agentscope.core.session.SessionViews;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Read-only session discovery and full conversation views from the authoritative native log. */
public class SessionSearchTool {
    private final SessionLogStore store;

    public SessionSearchTool(WorkspaceManager workspace) {
        this(new WorkspaceSessionLogStore(workspace));
    }

    public SessionSearchTool(SessionLogStore store) {
        this.store = Objects.requireNonNull(store);
    }

    /** Compatibility constructor; the supplied native store owns discovery and history. */
    public SessionSearchTool(WorkspaceManager workspace, SessionLogStore store) {
        this(store == null ? new WorkspaceSessionLogStore(workspace) : store);
    }

    @Tool(
            name = "session_search",
            readOnly = true,
            description =
                    "Search native session conversation messages, tool calls and results for a"
                            + " keyword or phrase.")
    public String sessionSearch(
            RuntimeContext runtimeContext,
            @ToolParam(name = "query", description = "Search query (keyword or phrase)")
                    String query,
            @ToolParam(
                            name = "agentId",
                            description = "Agent ID to search (all accessible agents if omitted)",
                            required = false)
                    String agentId,
            @ToolParam(
                            name = "maxResults",
                            description = "Maximum number of results (default: 10)",
                            required = false)
                    Integer maxResults) {
        if (query == null || query.isBlank()) return "Error: query is required";
        RuntimeContext rc = context(runtimeContext);
        int limit = positiveLimit(maxResults, 10);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        var matches = new ArrayList<Map<String, Object>>();
        for (SessionKey key : sessions(rc, agentId)) {
            RuntimeContext sessionContext =
                    RuntimeContext.builder(rc).sessionId(key.sessionId()).build();
            var view = SessionViews.transcript(store.open(key, sessionContext));
            for (var message : view.messages()) {
                String text = JsonUtils.getJsonCodec().toJson(message.getContent());
                if (text.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                    matches.add(
                            Map.of(
                                    "agentId",
                                    key.agentId(),
                                    "sessionId",
                                    key.sessionId(),
                                    "asOfSeq",
                                    view.asOfSeq(),
                                    "message",
                                    message));
                    if (matches.size() >= limit) return JsonUtils.getJsonCodec().toJson(matches);
                }
            }
        }
        return JsonUtils.getJsonCodec().toJson(matches);
    }

    @Tool(
            name = "session_list",
            readOnly = true,
            description =
                    "List native sessions for an agent in the caller's workspace storage"
                            + " namespace.")
    public String sessionList(
            RuntimeContext runtimeContext,
            @ToolParam(name = "agentId", description = "Agent ID to list sessions for")
                    String agentId) {
        if (agentId == null || agentId.isBlank()) return "Error: agentId is required";
        RuntimeContext rc = context(runtimeContext);
        return JsonUtils.getJsonCodec().toJson(sessions(rc, agentId));
    }

    @Tool(
            name = "session_history",
            readOnly = true,
            description =
                    "Read committed conversation history including messages before context"
                            + " compaction.")
    public String sessionHistory(
            RuntimeContext runtimeContext,
            @ToolParam(name = "agentId", description = "Agent ID") String agentId,
            @ToolParam(name = "sessionId", description = "Session ID") String sessionId,
            @ToolParam(
                            name = "lastN",
                            description = "Number of recent messages (default: 20)",
                            required = false)
                    Integer lastN) {
        if (agentId == null || agentId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return "Error: agentId and sessionId are required";
        }
        RuntimeContext rc = context(runtimeContext);
        RuntimeContext sessionContext = RuntimeContext.builder(rc).sessionId(sessionId).build();
        var log = store.open(new SessionKey(rc.getUserId(), agentId, sessionId), sessionContext);
        if (log.head().seq() == 0) return "Session not found: " + sessionId;
        var view = SessionViews.transcript(log);
        var messages = view.messages();
        int limit = positiveLimit(lastN, 20);
        return JsonUtils.getJsonCodec()
                .toJson(
                        Map.of(
                                "asOfSeq",
                                view.asOfSeq(),
                                "totalMessages",
                                messages.size(),
                                "messages",
                                messages.subList(
                                        Math.max(0, messages.size() - limit), messages.size())));
    }

    private List<SessionKey> sessions(RuntimeContext rc, String agentId) {
        return store.list(rc).stream()
                .filter(
                        key ->
                                Objects.equals(
                                        normalizeUser(key.userId()), normalizeUser(rc.getUserId())))
                .filter(
                        key ->
                                agentId == null
                                        || agentId.isBlank()
                                        || key.agentId().equals(agentId))
                .toList();
    }

    private static String normalizeUser(String user) {
        return user == null || user.isEmpty() ? null : user;
    }

    private static RuntimeContext context(RuntimeContext context) {
        return context == null ? RuntimeContext.empty() : context;
    }

    private static int positiveLimit(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
