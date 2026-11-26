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
import java.util.Comparator;
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

    /** Ceiling for a serialized recovery error, including JSON escaping and error metadata. */
    private static final int MAX_RECOVERY_ERROR_CHARS = 12_000;

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

    public ToolResultBlock threadSearch(
            RuntimeContext runtimeContext,
            String query,
            String agentId,
            Integer maxResults,
            String matchMode) {
        return threadSearch(runtimeContext, query, agentId, maxResults, matchMode, null);
    }

    @Tool(
            name = "thread_search",
            readOnly = true,
            prunable = false,
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
                    String matchMode,
            @ToolParam(
                            name = "offset",
                            description =
                                    "Non-negative match offset (default: 0). Continue with"
                                            + " nextOffset; pages re-read current data, not a"
                                            + " snapshot.",
                            required = false)
                    Integer offset) {
        if (offset != null && offset < 0) {
            return invalidOffset();
        }
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
        int startOffset = offset == null ? 0 : offset;
        long candidateOffset = 0;
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
                if (candidateOffset < startOffset) {
                    candidateOffset++;
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
                if (totalChars + size + 1 > budget) {
                    if (matches.isEmpty()) {
                        return oversizedIdentity(key, message.getId());
                    }
                    budgetReached = true;
                    break search;
                }
                matches.add(match);
                candidateOffset++;
                totalChars += size + 1; // separator
            }
        }
        if ((limitReached || budgetReached) && !matches.isEmpty()) {
            // Keep the result a pure list of matches: attach the truncation facts to the last
            // returned match instead of appending a metadata element that would be counted as
            // one more result.
            Map<String, Object> last = matches.get(matches.size() - 1);
            last.put("truncated", true);
            last.put("nextOffset", candidateOffset);
            if (limitReached) {
                last.put("limit", limit);
            }
            last.put(
                    "note",
                    budgetReached
                            ? "Result budget reached; continue thread_search with the same query"
                                    + " and filters and offset=nextOffset, or read one message with"
                                    + " thread_history messageId. Pages re-read current data."
                            : "Limit reached; continue thread_search with the same query and"
                                    + " filters and offset=nextOffset, or raise maxResults (hard"
                                    + " maximum "
                                    + MAX_RESULTS_CEILING
                                    + "). Pages re-read current data.");
        }
        return ToolResultBlock.success(JsonUtils.getJsonCodec().toJson(matches));
    }

    public ToolResultBlock threadList(RuntimeContext runtimeContext, String agentId) {
        return threadList(runtimeContext, agentId, null);
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
                    String agentId,
            @ToolParam(
                            name = "offset",
                            description =
                                    "Non-negative Thread offset (default: 0). Continue with"
                                            + " nextOffset; pages re-read current data, not a"
                                            + " snapshot.",
                            required = false)
                    Integer offset) {
        if (offset != null && offset < 0) {
            return invalidOffset();
        }
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
        int nextOffset = offset == null ? 0 : offset;
        while (nextOffset < keys.size()) {
            ThreadKey key = keys.get(nextOffset);
            Map<String, Object> entry =
                    Map.of(
                            "agentId", key.agentId(),
                            "sessionId", key.sessionId(),
                            "threadId", key.threadId());
            int size = matchSize(entry);
            if (totalChars + size + 1 > budget) {
                if (entries.isEmpty()) {
                    return oversizedIdentity(key, null);
                }
                truncated = true;
                break;
            }
            entries.add(entry);
            nextOffset++;
            totalChars += size + 1; // separator
        }
        if (truncated) {
            var note = new LinkedHashMap<String, Object>();
            note.put("truncated", true);
            note.put("returned", entries.size());
            note.put("total", keys.size());
            note.put("nextOffset", nextOffset);
            note.put(
                    "note",
                    "Result budget reached; continue thread_list with the same agentId and"
                            + " offset=nextOffset. Pages re-read current data; alternatively read a"
                            + " known session with thread_history.");
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

    public ToolResultBlock threadHistory(
            RuntimeContext runtimeContext,
            String agentId,
            String sessionId,
            String threadId,
            Integer lastN,
            String messageId) {
        return threadHistory(runtimeContext, agentId, sessionId, threadId, lastN, messageId, null);
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
                    String messageId,
            @ToolParam(
                            name = "beforeMessageId",
                            description =
                                    "Exclusive original message ID anchor for older history. Cannot"
                                            + " be combined with messageId; reads the latest"
                                            + " projection.",
                            required = false)
                    String beforeMessageId) {
        if (beforeMessageId != null && (beforeMessageId.isBlank() || messageId != null)) {
            return ToolResultBlock.error(
                            "beforeMessageId must be a non-blank original message ID and cannot be"
                                    + " combined with messageId.")
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT));
        }
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
        int end = messages.size();
        if (beforeMessageId != null) {
            end = -1;
            for (int i = 0; i < messages.size(); i++) {
                if (beforeMessageId.equals(messages.get(i).getId())) {
                    end = i;
                    break;
                }
            }
            if (end < 0) {
                return ToolResultBlock.error(
                                "Anchor not found in this Thread. Use an original message ID from"
                                        + " thread_history or thread_search in this Thread.")
                        .withMetadata(
                                ToolErrorMetadata.of(ToolErrorKind.NOT_FOUND, threadScope(key)));
            }
        }
        int limit = positiveLimit(lastN, 20);
        int windowStart = Math.max(0, end - limit);
        var selected = new ArrayList<Msg>();
        int totalChars = 2;
        int budget = MAX_RESULT_CHARS - TRAILER_RESERVE;
        for (int i = end - 1; i >= windowStart; i--) {
            Msg message = bounded(messages.get(i), key);
            int size = JsonUtils.getJsonCodec().toJson(message).length();
            selected.add(0, message);
            // Include the actual escaped anchor and trailer in the serialized budget, not
            // just the messages. A rejected candidate remains before the next page's anchor.
            Map<String, Object> candidate =
                    historyPage(view.asOfSeq(), messages.size(), selected, i, windowStart);
            if (totalChars + size + 1 > budget || matchSize(candidate) > MAX_RESULT_CHARS) {
                selected.remove(0);
                if (selected.isEmpty()) {
                    return oversizedIdentity(key, messages.get(i).getId());
                }
                break;
            }
            totalChars += size + 1;
        }
        return ToolResultBlock.success(
                JsonUtils.getJsonCodec()
                        .toJson(
                                historyPage(
                                        view.asOfSeq(),
                                        messages.size(),
                                        selected,
                                        end - selected.size(),
                                        windowStart)));
    }

    private static Map<String, Object> historyPage(
            long asOfSeq, int total, List<Msg> selected, int firstIncluded, int windowStart) {
        var result = new LinkedHashMap<String, Object>();
        result.put("asOfSeq", asOfSeq);
        result.put("totalMessages", total);
        result.put("messages", selected);
        result.put("hasMore", firstIncluded > 0);
        if (!selected.isEmpty()) {
            result.put("nextBeforeMessageId", selected.get(0).getId());
        }
        int omitted = firstIncluded - windowStart;
        if (omitted > 0) {
            result.put("truncated", true);
            result.put("omittedFromWindow", omitted);
            result.put(
                    "note",
                    "Continue thread_history with beforeMessageId=nextBeforeMessageId."
                            + " Pages read the latest projection, not a snapshot.");
        }
        return result;
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
                .sorted(
                        Comparator.comparing(ThreadKey::agentId)
                                .thenComparing(ThreadKey::sessionId)
                                .thenComparing(ThreadKey::threadId))
                .toList();
    }

    private static ToolResultBlock invalidOffset() {
        return ToolResultBlock.error("offset must be non-negative (default: 0).")
                .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT));
    }

    private static ToolResultBlock oversizedIdentity(ThreadKey key, String messageId) {
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("agentId", key.agentId());
        arguments.put("sessionId", key.sessionId());
        arguments.put("threadId", key.threadId());
        if (messageId != null) {
            arguments.put("messageId", messageId);
        }
        ToolResultBlock recovery =
                ToolResultBlock.error(
                                "Thread or message preview exceeds the result budget; read directly"
                                        + " with thread_history arguments: "
                                        + JsonUtils.getJsonCodec().toJson(arguments))
                        .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT));
        // Count the whole serialized error: identity quoting in the arguments is escaped again
        // inside the TextBlock. Never shorten an ID or let a recovery hint exceed its budget.
        if (JsonUtils.getJsonCodec().toJson(recovery).length() <= MAX_RECOVERY_ERROR_CHARS) {
            return recovery;
        }
        return oversizedIdentity();
    }

    private static ToolResultBlock oversizedIdentity() {
        return ToolResultBlock.error(
                        "Thread or message identity exceeds the result budget; read a known message"
                                + " directly with thread_history messageId.")
                .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT));
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
