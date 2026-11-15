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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolErrorKind;
import io.agentscope.core.message.ToolErrorMetadata;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.journal.WorkspaceThreadJournalStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /**
     * Per-message content budget before a match is summarized. Uses the same 2,000-character
     * preview size as {@code ToolResultEvictionConfig} and compaction's prune preview, so a
     * summarized match has exactly the detail the harness already shows for an evicted result.
     */
    static final int MAX_MATCH_CHARS = 2_000;

    /**
     * Total serialized budget for one search result. Matches {@code
     * FilesystemTool.DEFAULT_MAX_READ_CHARS}, the largest single read the harness accepts, and
     * stays well below the 80,000-character eviction trigger so the result remains inline.
     */
    static final int MAX_RESULT_CHARS = 32_000;

    /** Minimum preview allowance so a summary never collapses to only its reference line. */
    private static final int MIN_PREVIEW_CHARS = 200;

    /**
     * Room reserved for the truncation note. The longest note is ~330 characters; the whole
     * result staying within {@link #MAX_RESULT_CHARS} (asserted by tests) proves the reserve is
     * sufficient and that the note can never be crowded out.
     */
    static final int TRAILER_RESERVE = 400;

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
                            + " keyword or phrase. Oversized matches are summarized; read a full"
                            + " message with thread_history using its messageId.")
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
        if (query == null || query.isBlank()) {
            return ToolResultBlock.error(
                            "query is required: pass a literal phrase or whitespace-separated"
                                    + " keywords, e.g. query=\"deployment error\".")
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT));
        }
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
            return ToolResultBlock.error(error.getMessage())
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT));
        }
        var matches = new ArrayList<Map<String, Object>>();
        int totalChars = 2; // enclosing brackets
        int budget = MAX_RESULT_CHARS - TRAILER_RESERVE;
        boolean limitReached = false;
        boolean budgetReached = false;
        search:
        for (ThreadKey key : threads(rc, agentId)) {
            RuntimeContext sessionContext =
                    RuntimeContext.builder(rc)
                            .sessionId(key.sessionId())
                            .threadId(key.threadId())
                            .build();
            var view = ThreadHistory.transcript(store.open(key, sessionContext));
            for (var message : view.messages()) {
                String text = JsonUtils.getJsonCodec().toJson(message.getContent());
                if (!matcher.test(text.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                // Prove that at least one later match exists before reporting truncation.
                if (matches.size() >= limit) {
                    limitReached = true;
                    break search;
                }
                Map<String, Object> match = new LinkedHashMap<>();
                match.put("agentId", key.agentId());
                match.put("sessionId", key.sessionId());
                match.put("threadId", key.threadId());
                match.put("asOfSeq", view.asOfSeq());
                match.put("messageId", message.getId());
                match.put("message", bounded(message, key));
                int size = matchSize(match);
                if (!matches.isEmpty() && totalChars + size + 1 > budget) {
                    budgetReached = true;
                    break search;
                }
                matches.add(match);
                totalChars += size + 1; // separator
            }
        }
        if ((limitReached || budgetReached) && !matches.isEmpty()) {
            // Keep the result a pure list of matches: attach the truncation facts to the last
            // returned match instead of appending a metadata element that would be counted as
            // one more result.
            Map<String, Object> last = matches.get(matches.size() - 1);
            last.put("truncated", true);
            if (limitReached) {
                last.put("limit", limit);
            }
            last.put(
                    "note",
                    budgetReached
                            ? "Result budget reached; later matches were omitted. Narrow the query,"
                                    + " use matchMode, or read one message with thread_history"
                                    + " messageId."
                            : "Limit reached; later matches were omitted. Raise maxResults (hard"
                                    + " maximum "
                                    + MAX_RESULTS_CEILING
                                    + "), narrow the query, or read one message with"
                                    + " thread_history messageId.");
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
        if (agentId == null || agentId.isBlank()) {
            return ToolResultBlock.error(
                            "agentId is required: pass the agent ID whose Threads you want to"
                                    + " list.")
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT));
        }
        RuntimeContext rc = context(runtimeContext);
        var entries = new ArrayList<Map<String, Object>>();
        int totalChars = 2; // enclosing brackets
        int budget = MAX_RESULT_CHARS - TRAILER_RESERVE;
        boolean truncated = false;
        var keys = threads(rc, agentId);
        for (ThreadKey key : keys) {
            String serialized = JsonUtils.getJsonCodec().toJson(key);
            if (!entries.isEmpty() && totalChars + serialized.length() + 1 > budget) {
                truncated = true;
                break;
            }
            entries.add(
                    Map.of(
                            "agentId", key.agentId(),
                            "sessionId", key.sessionId(),
                            "threadId", key.threadId()));
            totalChars += serialized.length() + 1; // separator
        }
        if (truncated) {
            var note = new LinkedHashMap<String, Object>();
            note.put("truncated", true);
            note.put("returned", entries.size());
            note.put("total", keys.size());
            note.put(
                    "note",
                    "Result budget reached; the remaining Threads were omitted. Narrow the scope,"
                            + " or read a known session directly with thread_history.");
            entries.add(note);
        }
        return ToolResultBlock.success(JsonUtils.getJsonCodec().toJson(entries));
    }

    public ToolResultBlock threadHistory(
            RuntimeContext runtimeContext,
            String agentId,
            String sessionId,
            String threadId,
            Integer lastN) {
        return threadHistory(runtimeContext, agentId, sessionId, threadId, lastN, null);
    }

    @Tool(
            name = "thread_history",
            readOnly = true,
            description =
                    "Read committed conversation history including messages before context"
                            + " compaction. Pass messageId to read one full message referenced by"
                            + " thread_search.")
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
                            description =
                                    "Number of recent messages (default: 20, capped at 100);"
                                            + " ignored when messageId is given",
                            required = false)
                    Integer lastN,
            @ToolParam(
                            name = "messageId",
                            description =
                                    "Read this exact message instead of the lastN recent messages",
                            required = false)
                    String messageId) {
        if (agentId == null || agentId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return ToolResultBlock.error(
                            "agentId and sessionId are required: take both from thread_list"
                                    + " output. threadId is optional and defaults to main.")
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT));
        }
        RuntimeContext rc = context(runtimeContext);
        RuntimeContext sessionContext =
                RuntimeContext.builder(rc).sessionId(sessionId).threadId(threadId).build();
        ThreadKey key =
                new ThreadKey(rc.getUserId(), agentId, sessionId, sessionContext.getThreadId());
        var log = store.open(key, sessionContext);
        if (log.head().seq() == 0) {
            return ToolResultBlock.error(
                            "Thread not found: agentId="
                                    + agentId
                                    + ", sessionId="
                                    + sessionId
                                    + ", threadId="
                                    + key.threadId()
                                    + ". Run thread_list agentId=\""
                                    + agentId
                                    + "\" to see existing sessions.")
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.NOT_FOUND, threadScope(key)));
        }
        var view = ThreadHistory.transcript(log);
        var messages = view.messages();
        if (messageId != null && !messageId.isBlank()) {
            Msg found =
                    messages.stream()
                            .filter(message -> messageId.equals(message.getId()))
                            .findFirst()
                            .orElse(null);
            if (found == null) {
                return ToolResultBlock.error(
                                "Message not found: messageId="
                                        + messageId
                                        + " in sessionId="
                                        + sessionId
                                        + ", threadId="
                                        + key.threadId()
                                        + ". Locate it with thread_search and pass the messageId"
                                        + " exactly as returned.")
                        .withMetadata(
                                ToolErrorMetadata.of(ToolErrorKind.NOT_FOUND, threadScope(key)));
            }
            return ToolResultBlock.success(
                    JsonUtils.getJsonCodec()
                            .toJson(Map.of("asOfSeq", view.asOfSeq(), "message", found)));
        }
        int limit = positiveLimit(lastN, 20);
        int windowStart = Math.max(0, messages.size() - limit);
        var window = messages.subList(windowStart, messages.size());
        var selected = new ArrayList<Msg>();
        int totalChars = 0;
        int omitted = 0;
        int budget = MAX_RESULT_CHARS - TRAILER_RESERVE;
        for (int i = window.size() - 1; i >= 0; i--) {
            Msg message = bounded(window.get(i), key);
            int size = JsonUtils.getJsonCodec().toJson(message).length();
            if (!selected.isEmpty() && totalChars + size + 1 > budget) {
                omitted = i + 1;
                break;
            }
            selected.add(0, message);
            totalChars += size + 1; // separator
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("asOfSeq", view.asOfSeq());
        result.put("totalMessages", messages.size());
        result.put("messages", selected);
        if (omitted > 0) {
            result.put("truncated", true);
            result.put("omittedFromWindow", omitted);
            result.put(
                    "note",
                    "Result budget reached; the "
                            + omitted
                            + " oldest message(s) of the requested lastN window were omitted."
                            + " Locate them with thread_search, then read one at a time with"
                            + " thread_history messageId=<id>.");
        }
        return ToolResultBlock.success(JsonUtils.getJsonCodec().toJson(result));
    }

    /**
     * Summarizes a match whose serialized form exceeds {@link #MAX_MATCH_CHARS}. The summary keeps
     * the message identity and a head/tail preview, and points at the full message through
     * {@code thread_history} so the original stays recoverable without being copied into every
     * later search result.
     */
    private Msg bounded(Msg message, ThreadKey key) {
        String serialized = JsonUtils.getJsonCodec().toJson(message);
        if (serialized.length() <= MAX_MATCH_CHARS) return message;
        String reference = reference(key, message.getId(), serialized.length());
        int previewChars = Math.max(MIN_PREVIEW_CHARS, MAX_MATCH_CHARS - reference.length() - 2);
        String text = messageText(message.getContent());
        String preview;
        if (text.length() <= previewChars) {
            preview = text;
        } else {
            int head = previewChars / 2;
            int tail = previewChars - head;
            preview = text.substring(0, head) + "\n...\n" + text.substring(text.length() - tail);
        }
        return Msg.builderForRole(message.getRole())
                .id(message.getId())
                .name(message.getName())
                .content(TextBlock.builder().text(reference + "\n" + preview).build())
                .timestamp(message.getTimestamp())
                .build();
    }

    private static String messageText(List<ContentBlock> blocks) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                text.append(textBlock.getText());
            } else if (block instanceof ToolResultBlock result && result.getOutput() != null) {
                for (ContentBlock nested : result.getOutput()) {
                    if (nested instanceof TextBlock nestedText && nestedText.getText() != null) {
                        text.append(nestedText.getText());
                    }
                }
            }
        }
        return text.toString();
    }

    private static String reference(ThreadKey key, String messageId, int totalChars) {
        return String.format(
                "[Message truncated: %,d characters total; showing a preview only. Full message:"
                        + " thread_history(agentId=%s, sessionId=%s, threadId=%s, messageId=%s)]",
                totalChars,
                quote(key.agentId()),
                quote(key.sessionId()),
                quote(key.threadId()),
                quote(messageId));
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static int matchSize(Map<String, Object> match) {
        return JsonUtils.getJsonCodec().toJson(match).length();
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

    private static String threadScope(ThreadKey key) {
        return key.agentId() + "/" + key.sessionId() + "/" + key.threadId();
    }
}
