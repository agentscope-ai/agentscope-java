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
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.List;
import java.util.StringJoiner;
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
 */
public class MemorySearchTool {

    private static final Logger log = LoggerFactory.getLogger(MemorySearchTool.class);

    /** Default number of matching lines returned, matching the documented "up to 30 hits". */
    static final int DEFAULT_MAX_RESULTS = 30;

    /**
     * Hard ceiling for the model-controlled {@code maxResults} parameter. 100 × ~550 chars
     * per hit (MAX_LINE_CHARS + Source prefix + truncation suffix) ≈ 55K chars, under
     * {@code ToolResultEvictionConfig.DEFAULT_MAX_RESULT_CHARS} (80K) with headroom —
     * and {@code memory_search} is in {@code DEFAULT_EXCLUDED_TOOLS}, so nothing
     * downstream trims an oversized result.
     */
    static final int MAX_RESULTS_CEILING = 100;

    /**
     * Maximum length of a single returned match line (the {@code Source: <file>#<line>: } prefix
     * excluded). Longer lines are truncated; {@code memory_get} remains the way to read the full
     * context around a hit.
     */
    static final int MAX_LINE_CHARS = 500;

    private final WorkspaceManager workspaceManager;

    public MemorySearchTool(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    public String memorySearch(RuntimeContext runtimeContext, String query) {
        return memorySearch(runtimeContext, query, null, null);
    }

    public String memorySearch(RuntimeContext runtimeContext, String query, String matchMode) {
        return memorySearch(runtimeContext, query, matchMode, null);
    }

    @Tool(
            name = "memory_search",
            readOnly = true,
            description =
                    "Search through long-term memory files (MEMORY.md and memory/*.md) for"
                            + " relevant information. Use before answering questions about prior"
                            + " work, decisions, dates, people, preferences, or todos.")
    public String memorySearch(
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
                            name = "maxResults",
                            description =
                                    "Maximum number of matching lines to return"
                                            + " (default: 30, max: 100). Use memory_get to read"
                                            + " full context around a match.",
                            required = false)
                    Integer maxResults) {
        if (query == null || query.isBlank()) {
            return "No query provided";
        }

        RuntimeContext rc = runtimeContext != null ? runtimeContext : RuntimeContext.empty();
        // Clamp model-supplied maxResults to a hard ceiling so a maxResults=100000 call
        // cannot re-open the context-overflow path the bounding exists to close (#3266).
        int limit =
                maxResults != null && maxResults > 0
                        ? Math.min(maxResults, MAX_RESULTS_CEILING)
                        : DEFAULT_MAX_RESULTS;

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
            return "Error: " + e.getMessage();
        }
        return keywordSearch(rc, query, matcher, limit);
    }

    private String keywordSearch(
            RuntimeContext rc, String query, Predicate<String> matcher, int maxResults) {
        StringJoiner results = new StringJoiner("\n");
        int matchCount = 0;
        boolean hasMoreMatches = false;

        List<String> memoryPaths = workspaceManager.listMemoryFilePaths(rc);

        outer:
        for (String relativePath : memoryPaths) {
            String content = workspaceManager.readManagedWorkspaceFileUtf8(rc, relativePath);
            if (content == null || content.isEmpty()) {
                continue;
            }
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                // Test the line before consulting the cap, so the line that trips the cap is
                // still examined: a match sitting exactly at the cap boundary is flagged as
                // "more" rather than silently dropped. This also scans every later file for a
                // real extra match, so hasMoreMatches is never a false positive and never
                // misses one (#3266 review).
                if (!matcher.test(lines[i])) {
                    continue;
                }
                if (matchCount >= maxResults) {
                    hasMoreMatches = true;
                    break outer;
                }
                results.add(
                        String.format(
                                "Source: %s#%d: %s", relativePath, i + 1, truncateLine(lines[i])));
                matchCount++;
            }
        }

        if (matchCount == 0) {
            return "No matching memories found for: " + query;
        }
        String header =
                "Found "
                        + matchCount
                        + (hasMoreMatches ? "+" : "")
                        + " matches"
                        + ":\n\n"
                        + results;
        if (hasMoreMatches) {
            header +=
                    "\n\n[Results truncated at "
                            + matchCount
                            + " matches — refine the query or"
                            + " use memory_get to read specific files]";
        }
        return header;
    }

    private static String truncateLine(String line) {
        if (line.length() <= MAX_LINE_CHARS) {
            return line;
        }
        // Back off a high surrogate so we never split a UTF-16 surrogate pair
        // (emoji, CJK Extension B, etc.).
        int cut = MAX_LINE_CHARS;
        if (Character.isHighSurrogate(line.charAt(cut - 1))) {
            cut--;
        }
        return line.substring(0, cut) + "... [line truncated, use memory_get]";
    }
}
