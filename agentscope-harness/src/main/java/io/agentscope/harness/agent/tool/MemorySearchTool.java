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

    private final WorkspaceManager workspaceManager;

    public MemorySearchTool(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    /** Retains the original Java API and its literal phrase matching behavior. */
    public String memorySearch(RuntimeContext runtimeContext, String query) {
        return memorySearch(runtimeContext, query, null);
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
                    String matchMode) {
        if (query == null || query.isBlank()) {
            return "No query provided";
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
            return "Error: " + e.getMessage();
        }
        return keywordSearch(rc, query, matcher);
    }

    private String keywordSearch(RuntimeContext rc, String query, Predicate<String> matcher) {
        StringJoiner results = new StringJoiner("\n");
        int matchCount = 0;

        List<String> memoryPaths = workspaceManager.listMemoryFilePaths(rc);

        for (String relativePath : memoryPaths) {
            String content = workspaceManager.readManagedWorkspaceFileUtf8(rc, relativePath);
            if (content == null || content.isEmpty()) {
                continue;
            }
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (matcher.test(lines[i])) {
                    results.add(String.format("Source: %s#%d: %s", relativePath, i + 1, lines[i]));
                    matchCount++;
                }
            }
        }

        if (matchCount == 0) {
            return "No matching memories found for: " + query;
        }
        return "Found " + matchCount + " matches:\n\n" + results;
    }
}
