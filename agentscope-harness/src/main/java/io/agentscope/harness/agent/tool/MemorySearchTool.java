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
import io.agentscope.core.message.ToolErrorKind;
import io.agentscope.core.message.ToolErrorMetadata;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool for searching through persisted memories (MEMORY.md and memory/*.md files).
 *
 * <p>Uses keyword-based search through all memory files visible via the configured
 * {@link io.agentscope.harness.agent.filesystem.AbstractFilesystem} (works across Local,
 * Sandbox, and Store stores).
 *
 * <p>Results are bounded like {@code grep_files}: at most {@link #DEFAULT_LIMIT} matches (up to
 * {@link #MAX_LIMIT}), each line capped at {@link #MAX_LINE_CHARS} characters, and the whole
 * result capped at {@link #MAX_RESULT_CHARS}. Memory files are exempt from tool-result eviction,
 * so an unbounded search would otherwise grow the context on every call.
 */
public class MemorySearchTool {

    private static final Logger log = LoggerFactory.getLogger(MemorySearchTool.class);

    /**
     * Matches returned when the caller does not ask for a specific limit. Mirrors the repository's
     * {@code grep_files} default (100) and pi's grep default; {@link #MAX_RESULT_CHARS} still caps
     * the payload.
     */
    static final int DEFAULT_LIMIT = 100;

    /** Hard ceiling for a model-supplied limit; mirrors the repository's {@code glob_files} cap. */
    static final int MAX_LIMIT = 200;

    /** Per-line cap; same value as pi's {@code GREP_MAX_LINE_LENGTH}. */
    static final int MAX_LINE_CHARS = 500;

    /**
     * Total result budget: codex's representative tool-output policy (10,000 bytes) plus its 20%
     * serialization allowance, and below {@code read_file}'s 16,000-character default so one
     * search never costs more than one ordinary file read.
     */
    static final int MAX_RESULT_CHARS = 12_000;

    /**
     * Room reserved for the header and the truncation trailer. The longest trailer is ~230
     * characters; the whole result staying within {@link #MAX_RESULT_CHARS} (asserted by tests)
     * proves this reserve is sufficient.
     */
    static final int BOUNDARY_RESERVE = 500;

    private final WorkspaceManager workspaceManager;

    public MemorySearchTool(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    public ToolResultBlock memorySearch(RuntimeContext runtimeContext, String query) {
        return memorySearch(runtimeContext, query, null, null);
    }

    public ToolResultBlock memorySearch(
            RuntimeContext runtimeContext, String query, String matchMode) {
        return memorySearch(runtimeContext, query, matchMode, null);
    }

    @Tool(
            name = "memory_search",
            readOnly = true,
            evictable = false,
            prunable = false,
            description =
                    "Search through long-term memory files (MEMORY.md and memory/*.md) for"
                            + " relevant information. Use before answering questions about prior"
                            + " work, decisions, dates, people, preferences, or todos. Results are"
                            + " bounded (default 100 matches); narrow the query instead of raising"
                            + " limit when the result is truncated.")
    public ToolResultBlock memorySearch(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "query",
                            description =
                                    "Literal phrase, or whitespace-separated keywords when"
                                            + " matchMode is all/any; no automatic Chinese word"
                                            + " segmentation")
                    String query,
            @ToolParam(
                            name = "matchMode",
                            description =
                                    "phrase (default): exact substring; all: every keyword in the"
                                            + " same memory line; any: at least one keyword in that"
                                            + " line. Case-insensitive literal matching.",
                            required = false)
                    String matchMode,
            @ToolParam(
                            name = "limit",
                            description =
                                    "Maximum matches to return (default: 100, hard maximum: 200)",
                            required = false)
                    Integer limit) {
        if (query == null || query.isBlank()) {
            return ToolResultBlock.error(
                            "query is required: pass a literal phrase or whitespace-separated"
                                    + " keywords, e.g. query=\"deployment decision\".")
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT));
        }
        int effectiveLimit =
                limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        if (limit != null && limit < 1) {
            return ToolResultBlock.error(
                            "limit must be a positive integer (hard maximum: " + MAX_LIMIT + ").")
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT));
        }

        RuntimeContext rc = runtimeContext != null ? runtimeContext : RuntimeContext.empty();
        Predicate<String> matcher;
        try {
            matcher =
                    KeywordMatcher.compile(
                            query,
                            matchMode,
                            term ->
                                    Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE)
                                            .asPredicate());
        } catch (IllegalArgumentException e) {
            return ToolResultBlock.error(e.getMessage())
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT));
        }
        return ToolResultBlock.success(keywordSearch(rc, query, matcher, effectiveLimit));
    }

    private String keywordSearch(
            RuntimeContext rc, String query, Predicate<String> matcher, int limit) {
        List<String> shown = new ArrayList<>();
        int total = 0;
        int bodyChars = 0;
        int bodyBudget = MAX_RESULT_CHARS - BOUNDARY_RESERVE;
        boolean budgetReached = false;

        List<String> memoryPaths = workspaceManager.listMemoryFilePaths(rc);

        for (String relativePath : memoryPaths) {
            String content = workspaceManager.readMemoryFileUtf8(rc, relativePath);
            if (content == null || content.isEmpty()) {
                continue;
            }
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!matcher.test(lines[i])) {
                    continue;
                }
                total++;
                // Keep counting after the output is full so the count in the trailer is accurate,
                // but stop building result text.
                if (budgetReached || shown.size() >= limit) {
                    continue;
                }
                String entry =
                        "Source: "
                                + relativePath
                                + "#"
                                + (i + 1)
                                + ": "
                                + preview(lines[i], MAX_LINE_CHARS);
                int addition = entry.length() + (shown.isEmpty() ? 0 : 1);
                if (bodyChars + addition > bodyBudget) {
                    budgetReached = true;
                    continue;
                }
                shown.add(entry);
                bodyChars += addition;
            }
        }

        if (total == 0) {
            return "No matching memories found for: "
                    + query
                    + ". Try matchMode=all or matchMode=any with shorter keywords, or read"
                    + " MEMORY.md with memory_get.";
        }

        StringBuilder out = new StringBuilder();
        out.append("Found ").append(total).append(" matches");
        if (shown.size() < total) {
            out.append(" (showing ").append(shown.size()).append(")");
        }
        out.append(":\n\n");
        for (int i = 0; i < shown.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(shown.get(i));
        }
        if (shown.size() < total) {
            out.append('\n').append(trailer(shown.size(), total, limit, budgetReached));
        }
        return out.toString();
    }

    private static String trailer(int shownCount, int total, int limit, boolean budgetReached) {
        if (!budgetReached && shownCount >= limit) {
            return "[Showing "
                    + shownCount
                    + " of "
                    + total
                    + " matches (limit="
                    + limit
                    + "). Narrow the query, switch matchMode, or raise limit (max "
                    + MAX_LIMIT
                    + "). Read one hit with memory_get path=<path> startLine=<line>"
                    + " endLine=<line>.]";
        }
        return "[Showing "
                + shownCount
                + " of "
                + total
                + " matches; the "
                + MAX_RESULT_CHARS
                + "-character result budget was reached. Narrow the query or read one hit with"
                + " memory_get path=<path> startLine=<line> endLine=<line>.]";
    }

    /** Keeps the head and tail of an oversized line so both ends stay visible. */
    static String preview(String text, int maxChars) {
        int total = text.codePointCount(0, text.length());
        if (total <= maxChars) {
            return text;
        }
        int marker = 5; // " ... "
        int head = (maxChars - marker) / 2;
        int tail = maxChars - marker - head;
        int headEnd = text.offsetByCodePoints(0, head);
        int tailStart = text.offsetByCodePoints(0, total - tail);
        return text.substring(0, headEnd) + " ... " + text.substring(tailStart);
    }
}
