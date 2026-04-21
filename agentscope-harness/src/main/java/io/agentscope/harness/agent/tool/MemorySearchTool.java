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

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.memory.MemoryIndex;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool for searching through persisted memories (MEMORY.md and memory/*.md files).
 *
 * <p>When a {@link MemoryIndex} is available, uses SQLite FTS5 for ranked full-text
 * search. Falls back to naive keyword search when the index is unavailable.
 */
public class MemorySearchTool {

    private static final Logger log = LoggerFactory.getLogger(MemorySearchTool.class);
    private static final int MAX_RESULTS = 30;

    private final WorkspaceManager workspaceManager;
    private volatile MemoryIndex memoryIndex;

    public MemorySearchTool(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    public void setMemoryIndex(MemoryIndex memoryIndex) {
        this.memoryIndex = memoryIndex;
    }

    @Tool(
            name = "memory_search",
            description =
                    "Search through long-term memory files (MEMORY.md and memory/*.md) for"
                            + " relevant information. Use before answering questions about prior"
                            + " work, decisions, dates, people, preferences, or todos.")
    public String memorySearch(
            @ToolParam(name = "query", description = "Keywords to search for in memory files")
                    String query) {
        if (query == null || query.isBlank()) {
            return "No query provided";
        }

        MemoryIndex idx = this.memoryIndex;
        if (idx != null) {
            try {
                List<MemoryIndex.SearchHit> hits = idx.search(query, MAX_RESULTS);
                if (!hits.isEmpty()) {
                    StringJoiner sj = new StringJoiner("\n");
                    for (MemoryIndex.SearchHit hit : hits) {
                        sj.add(
                                String.format(
                                        "Source: %s#%d: %s",
                                        hit.path(), hit.lineNumber(), hit.content()));
                    }
                    return "Found " + hits.size() + " matches:\n\n" + sj;
                }
                return "No matching memories found for: " + query;
            } catch (Exception e) {
                log.warn("FTS5 search failed, falling back to keyword search: {}", e.getMessage());
            }
        }

        return keywordSearch(query);
    }

    private String keywordSearch(String query) {
        StringJoiner results = new StringJoiner("\n");
        int matchCount = 0;

        List<String> memoryPaths = workspaceManager.listMemoryFilePaths();
        Pattern pattern = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);

        for (String relativePath : memoryPaths) {
            String content = workspaceManager.readManagedWorkspaceFileUtf8(relativePath);
            if (content == null || content.isEmpty()) {
                continue;
            }
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (pattern.matcher(lines[i]).find()) {
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
