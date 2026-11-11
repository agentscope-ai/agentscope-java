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
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.List;

/**
 * Tool for reading specific lines from memory files, typically used after
 * {@link MemorySearchTool} to fetch surrounding context.
 *
 * <p>Output is bounded: memory_get is excluded from tool-result eviction on the assumption
 * that its pagination keeps results small, so the page itself must carry a hard
 * character budget (same shape as {@code read_file}) no matter how the model drives
 * startLine/endLine.
 */
public class MemoryGetTool {

    /** Default memory read page size in Unicode code points. */
    static final int DEFAULT_GET_CHARS = 16_000;

    /** Default application limit for a memory read page in Unicode code points. */
    public static final int DEFAULT_MAX_GET_CHARS = 32_000;

    private final WorkspaceManager workspaceManager;
    private final int memoryGetMaxChars;

    public MemoryGetTool(WorkspaceManager workspaceManager) {
        this(workspaceManager, DEFAULT_MAX_GET_CHARS);
    }

    /**
     * Creates the tool with an application-defined read page limit.
     *
     * @param memoryGetMaxChars maximum Unicode code points per page, at least
     *     {@link #DEFAULT_GET_CHARS}
     */
    public MemoryGetTool(WorkspaceManager workspaceManager, int memoryGetMaxChars) {
        if (memoryGetMaxChars < DEFAULT_GET_CHARS) {
            throw new IllegalArgumentException(
                    "memoryGetMaxChars must be at least " + DEFAULT_GET_CHARS);
        }
        this.workspaceManager = workspaceManager;
        this.memoryGetMaxChars = memoryGetMaxChars;
    }

    @Tool(
            name = "memory_get",
            readOnly = true,
            description =
                    "Read specific lines from a memory file. Use after memory_search to pull"
                            + " full context around matched lines. Path is relative to workspace."
                            + " Output is bounded by max_chars (Unicode code points). Continue"
                            + " with char_offset, keeping path, startLine and endLine unchanged,"
                            + " instead of reading everything at once.")
    public ToolResultBlock memoryGet(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "path",
                            description =
                                    "Relative path to the memory file (e.g., MEMORY.md or"
                                            + " memory/2026-04-01.md)")
                    String path,
            @ToolParam(name = "startLine", description = "Start line number (1-based, inclusive)")
                    int startLine,
            @ToolParam(name = "endLine", description = "End line number (1-based, inclusive)")
                    int endLine,
            @ToolParam(
                            name = "char_offset",
                            description =
                                    "Unicode code point offset within the selected line range."
                                            + " Default: 0",
                            required = false)
                    Integer charOffset,
            @ToolParam(
                            name = "max_chars",
                            description =
                                    "Maximum Unicode code points per page. Default: 16000; clamped"
                                        + " to the application-configured maximum (default: 32000)",
                            required = false)
                    Integer maxChars) {
        if (path == null || path.isBlank()) {
            return ToolResultBlock.error("path is required");
        }

        Path resolved = workspaceManager.getWorkspace().resolve(path).normalize();
        if (!resolved.startsWith(workspaceManager.getWorkspace())) {
            return ToolResultBlock.error("path traversal not allowed");
        }

        RuntimeContext rc = runtimeContext != null ? runtimeContext : RuntimeContext.empty();
        String text = workspaceManager.readMemoryFileUtf8(rc, path);
        if (text == null || text.isBlank()) {
            return ToolResultBlock.error("file not found: " + path);
        }

        List<String> lines = List.of(text.split("\n", -1));
        int start = Math.max(0, startLine - 1);
        int end = Math.min(lines.size(), endLine);

        if (start >= lines.size()) {
            return ToolResultBlock.error(
                    "startLine " + startLine + " exceeds file length " + lines.size());
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(String.format("%d|%s%n", i + 1, lines.get(i)));
        }

        int off = charOffset != null ? charOffset : 0;
        int size = maxChars != null ? Math.min(maxChars, memoryGetMaxChars) : DEFAULT_GET_CHARS;
        if (off < 0 || size < 1) {
            return ToolResultBlock.error(
                    "char_offset must be non-negative and max_chars must be greater than 0");
        }
        int total = sb.codePointCount(0, sb.length());
        if (off > total) {
            return ToolResultBlock.error(
                    "char_offset exceeds the selected content length: " + total);
        }
        int pageEnd = off + Math.min(size, total - off);
        String page =
                sb.substring(sb.offsetByCodePoints(0, off), sb.offsetByCodePoints(0, pageEnd));
        if (pageEnd < total) {
            page +=
                    "\n[Output truncated. Continue with char_offset="
                            + pageEnd
                            + " and the same path, startLine and endLine. Offsets count Unicode"
                            + " code points within the selected line range.]";
        }
        return ToolResultBlock.success(page);
    }

    /** Reads a memory line range with the default page size. */
    public ToolResultBlock memoryGet(
            RuntimeContext runtimeContext, String path, int startLine, int endLine) {
        return memoryGet(runtimeContext, path, startLine, endLine, null, null);
    }
}
