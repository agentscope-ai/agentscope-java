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
import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.journal.ThreadHistory;
import io.agentscope.core.journal.ThreadJournalStore;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.journal.WorkspaceThreadJournalStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Read-only Thread discovery and full conversation views from the authoritative native log. */
public class ThreadHistoryTool {
    /**
     * Hard ceiling for model-supplied result limits (#3270). This bounds the number of matches,
     * not their size; oversized results are left to {@code ToolResultEvictionConfig}.
     */
    private static final int MAX_RESULTS_CEILING = 100;

    private final ThreadJournalStore store;

    public ThreadHistoryTool(WorkspaceManager workspace) {
        this(new WorkspaceThreadJournalStore(workspace));
    }

    public ThreadHistoryTool(ThreadJournalStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public ToolResultBlock threadSearch(
            RuntimeContext runtimeContext, String query, String agentId, Integer maxResults) {
        return threadSearch(runtimeContext, query, agentId, maxResults, null);
    }

    @Tool(
            name = "thread_search",
            readOnly = true,
            description =
                    "Search native Thread conversation messages, tool calls and results for a"
                            + " keyword or phrase.")
    public ToolResultBlock threadSearch(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "query",
                            description =
                                    "Literal phrase, or whitespace-separated keywords when"
                                            + " matchMode is all/any; no automatic Chinese word"
                                            + " segmentation")
                    String query,
            @ToolParam(
                            name = "agentId",
                            description = "Agent ID to search (all accessible agents if omitted)",
                            required = false)
                    String agentId,
            @ToolParam(
                            name = "maxResults",
                            description = "Maximum number of results (default: 10, capped at 100)",
                            required = false)
                    Integer maxResults,
            @ToolParam(
                            name = "matchMode",
                            description =
                                    "phrase (default): exact substring; all: every keyword in the"
                                            + " same message; any: at least one keyword in that"
                                            + " message. Case-insensitive literal matching.",
                            required = false)
                    String matchMode) {
        if (query == null || query.isBlank()) return ToolResultBlock.error("query is required");
        RuntimeContext rc = context(runtimeContext);
        int limit = positiveLimit(maxResults, 10);
        Predicate<String> matcher;
        try {
            matcher =
                    KeywordMatcher.compile(
                            query,
                            matchMode,
                            term -> {
                                String lowerTerm = term.toLowerCase(Locale.ROOT);
                                return text -> text.contains(lowerTerm);
                            });
        } catch (IllegalArgumentException error) {
            return ToolResultBlock.error(error.getMessage());
        }
        var matches = new ArrayList<Map<String, Object>>();
        for (ThreadKey key : threads(rc, agentId)) {
            RuntimeContext sessionContext =
                    RuntimeContext.builder(rc)
                            .sessionId(key.sessionId())
                            .threadId(key.threadId())
                            .build();
            var view = ThreadHistory.transcript(store.open(key, sessionContext));
            for (var message : view.messages()) {
                String text = JsonUtils.getJsonCodec().toJson(message.getContent());
                if (matcher.test(text.toLowerCase(Locale.ROOT))) {
                    matches.add(
                            Map.of(
                                    "agentId",
                                    key.agentId(),
                                    "sessionId",
                                    key.sessionId(),
                                    "threadId",
                                    key.threadId(),
                                    "asOfSeq",
                                    view.asOfSeq(),
                                    "message",
                                    message));
                    if (matches.size() >= limit)
                        return ToolResultBlock.success(JsonUtils.getJsonCodec().toJson(matches));
                }
            }
        }
        return ToolResultBlock.success(JsonUtils.getJsonCodec().toJson(matches));
    }

    @Tool(
            name = "thread_list",
            readOnly = true,
            description =
                    "List native Threads for an agent in the caller's workspace storage"
                            + " namespace.")
    public ToolResultBlock threadList(
            RuntimeContext runtimeContext,
            @ToolParam(name = "agentId", description = "Agent ID to list Threads for")
                    String agentId) {
        if (agentId == null || agentId.isBlank())
            return ToolResultBlock.error("agentId is required");
        RuntimeContext rc = context(runtimeContext);
        return ToolResultBlock.success(JsonUtils.getJsonCodec().toJson(threads(rc, agentId)));
    }

    @Tool(
            name = "thread_history",
            readOnly = true,
            description =
                    "Read committed conversation history including messages before context"
                            + " compaction.")
    public ToolResultBlock threadHistory(
            RuntimeContext runtimeContext,
            @ToolParam(name = "agentId", description = "Agent ID") String agentId,
            @ToolParam(name = "sessionId", description = "Session ID") String sessionId,
            @ToolParam(
                            name = "threadId",
                            description = "Thread ID (default: main)",
                            required = false)
                    String threadId,
            @ToolParam(
                            name = "lastN",
                            description = "Number of recent messages (default: 20, capped at 100)",
                            required = false)
                    Integer lastN) {
        if (agentId == null || agentId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return ToolResultBlock.error("agentId and sessionId are required");
        }
        RuntimeContext rc = context(runtimeContext);
        RuntimeContext sessionContext =
                RuntimeContext.builder(rc).sessionId(sessionId).threadId(threadId).build();
        var log =
                store.open(
                        new ThreadKey(
                                rc.getUserId(), agentId, sessionId, sessionContext.getThreadId()),
                        sessionContext);
        if (log.head().seq() == 0)
            return ToolResultBlock.error(
                    "Thread not found: " + sessionId + "/" + sessionContext.getThreadId());
        var view = ThreadHistory.transcript(log);
        var messages = view.messages();
        int limit = positiveLimit(lastN, 20);
        return ToolResultBlock.success(
                JsonUtils.getJsonCodec()
                        .toJson(
                                Map.of(
                                        "asOfSeq",
                                        view.asOfSeq(),
                                        "totalMessages",
                                        messages.size(),
                                        "messages",
                                        messages.subList(
                                                Math.max(0, messages.size() - limit),
                                                messages.size()))));
    }

    private List<ThreadKey> threads(RuntimeContext rc, String agentId) {
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
        return value == null || value <= 0 ? fallback : Math.min(value, MAX_RESULTS_CEILING);
    }
}
