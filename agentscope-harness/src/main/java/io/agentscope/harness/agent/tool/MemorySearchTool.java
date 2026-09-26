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
     * Hard ceiling for the model-supplied {@code maxResults}. The parameter is model-controlled,
     * so without a ceiling a {@code maxResults=100000} call re-opens the context-overflow path
     * this tool's bounding exists to close.
     *
     * <p>The value keeps the worst case under the project's tool-result budget: a hit is at most
     * {@link #MAX_LINE_CHARS} (500) plus the {@code Source: <path>#<line>: } prefix and the
     * truncation suffix (~550 chars), and {@code memory_search} is excluded from tool-result
     * eviction, so nothing downstream trims an oversized result. 100 x ~550 = ~55K chars stays
     * under {@code ToolResultEvictionConfig.DEFAULT_MAX_RESULT_CHARS} (80K).
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

    @Tool(
            name = "memory_search",
            readOnly = true,
            description =
                    "Search through long-term memory files (MEMORY.md and memory/*.md) for"
                            + " relevant information. Use before answering questions about prior"
                            + " work, decisions, dates, people, preferences, or todos.")
    public String memorySearch(
            RuntimeContext runtimeContext,
            @ToolParam(name = "query", description = "Keywords to search for in memory files")
                    String query,
            @ToolParam(
                            name = "maxResults",
                            description =
                                    "Maximum number of matching lines to return (default: 30,"
                                            + " max: 100). Use memory_get to read full context"
                                            + " around a match.",
                            required = false)
                    Integer maxResults) {
        if (query == null || query.isBlank()) {
            return "No query provided";
        }

        RuntimeContext rc = runtimeContext != null ? runtimeContext : RuntimeContext.empty();
        int limit =
                maxResults != null && maxResults > 0
                        ? Math.min(maxResults, MAX_RESULTS_CEILING)
                        : DEFAULT_MAX_RESULTS;
        return keywordSearch(rc, query, limit);
    }

    private String keywordSearch(RuntimeContext rc, String query, int maxResults) {
        StringJoiner results = new StringJoiner("\n");
        int matchCount = 0;
        // Set only when an actual extra match exists beyond the cap — not from loop position,
        // so the model is never told to "refine the query" when refining would change nothing.
        boolean hasMoreMatches = false;

        List<String> memoryPaths = workspaceManager.listMemoryFilePaths(rc);
        Pattern pattern = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);

        for (String relativePath : memoryPaths) {
            String content = workspaceManager.readManagedWorkspaceFileUtf8(rc, relativePath);
            if (content == null || content.isEmpty()) {
                continue;
            }
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!pattern.matcher(lines[i]).find()) {
                    continue;
                }
                if (matchCount >= maxResults) {
                    // Real extra hit past the cap — stop scanning; the flag is now accurate.
                    hasMoreMatches = true;
                    break;
                }
                results.add(
                        String.format(
                                "Source: %s#%d: %s", relativePath, i + 1, truncateLine(lines[i])));
                matchCount++;
            }
            if (hasMoreMatches) {
                break;
            }
        }

        if (matchCount == 0) {
            return "No matching memories found for: " + query;
        }
        String header =
                "Found "
                        + (hasMoreMatches ? matchCount + "+" : String.valueOf(matchCount))
                        + (matchCount == 1 && !hasMoreMatches ? " match" : " matches")
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
        int cut = MAX_LINE_CHARS;
        // Do not split a UTF-16 surrogate pair (emoji, CJK Extension B+ ideographs): a lone
        // surrogate surfaces as mojibake once the tool result is JSON-encoded for the model.
        if (Character.isHighSurrogate(line.charAt(cut - 1))) {
            cut--;
        }
        return line.substring(0, cut) + "... [line truncated, use memory_get]";
    }
}
