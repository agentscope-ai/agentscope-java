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
import java.nio.file.Path;
import java.util.List;

/**
 * Tool for reading specific lines from memory files, typically used after
 * {@link MemorySearchTool} to fetch surrounding context.
 *
 * <p>Output is bounded by {@link #MAX_OUTPUT_CHARS} characters with a continuation trailer, and
 * each line is capped at {@link #MAX_LINE_CHARS}. Memory files are exempt from tool-result
 * eviction, so an unbounded line range would otherwise grow the context without limit.
 */
public class MemoryGetTool {

    /**
     * Total result budget, aligned with {@link MemorySearchTool#MAX_RESULT_CHARS}: codex's
     * representative tool-output policy (10,000 bytes) plus its 20% serialization allowance.
     */
    static final int MAX_OUTPUT_CHARS = 12_000;

    /**
     * Per-line cap; the same 2,000 characters as {@code ToolResultEvictionConfig}'s preview size,
     * so one long ledger line cannot consume the whole page.
     */
    static final int MAX_LINE_CHARS = 2_000;

    /**
     * Room reserved for the continuation trailer. The longest trailer is ~260 characters; tests
     * assert the whole result stays within {@link #MAX_OUTPUT_CHARS}, which proves the reserve.
     */
    static final int TRAILER_RESERVE = 400;

    private final WorkspaceManager workspaceManager;

    public MemoryGetTool(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    @Tool(
            name = "memory_get",
            readOnly = true,
            description =
                    "Read specific lines from a memory file. Use after memory_search to pull"
                            + " full context around matched lines. Path is relative to the"
                            + " caller's memory namespace. Output is bounded; continue with the"
                            + " startLine given in the truncation notice.")
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
                    int endLine) {
        if (path == null || path.isBlank()) {
            return ToolResultBlock.error("path is required")
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT));
        }
        String scope = path.strip();
        // Use the manager's own rule so the two checks cannot drift; it also normalizes leading
        // whitespace and slashes, so "  ../x" still names a traversal.
        try {
            WorkspaceManager.requireSafeRelativePath(path);
        } catch (IllegalArgumentException e) {
            return ToolResultBlock.error(
                            "path must be a relative path inside the memory namespace (no leading"
                                    + " '/' or '..' segments): "
                                    + path)
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.PERMISSION_DENIED, scope));
        }

        RuntimeContext rc = runtimeContext != null ? runtimeContext : RuntimeContext.empty();

        // Containment on the spelled path, which differs from the relative spelling once a
        // namespace applies; readMemoryFileUtf8 below does the real isolation.
        Path workspace = workspaceManager.getWorkspace();
        Path resolved = workspaceManager.resolveRuntimeDataPath(rc, path).normalize();
        if (!resolved.startsWith(workspace)) {
            return ToolResultBlock.error("path must be relative to the memory namespace: " + path)
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.PERMISSION_DENIED, scope));
        }

        String text = workspaceManager.readMemoryFileUtf8(rc, path);
        if (text == null || text.isBlank()) {
            return ToolResultBlock.error(
                            "Memory file not found or empty: "
                                    + path
                                    + ". Paths are relative to your memory namespace; use"
                                    + " memory_search to locate the content, or read MEMORY.md.")
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.NOT_FOUND, scope));
        }

        List<String> lines = List.of(text.split("\n", -1));
        int start = Math.max(0, startLine - 1);
        int end = Math.min(lines.size(), endLine);

        if (startLine < 1) {
            return ToolResultBlock.error("startLine must be >= 1 (1-based); got " + startLine + ".")
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT, scope));
        }
        if (endLine < startLine) {
            return ToolResultBlock.error(
                            "endLine ("
                                    + endLine
                                    + ") must be >= startLine ("
                                    + startLine
                                    + "); memory_get uses 1-based inclusive line numbers.")
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT, scope));
        }
        if (start >= lines.size()) {
            return ToolResultBlock.error(
                            "startLine "
                                    + startLine
                                    + " exceeds file length "
                                    + lines.size()
                                    + "; retry with startLine <= "
                                    + lines.size()
                                    + ".")
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT, scope));
        }

        StringBuilder sb = new StringBuilder();
        int lastIncluded = startLine - 1;
        boolean pageTruncated = false;
        for (int i = start; i < end; i++) {
            String entry = String.format("%d|%s%n", i + 1, boundedLine(lines.get(i)));
            if (sb.length() + entry.length() > MAX_OUTPUT_CHARS - TRAILER_RESERVE) {
                pageTruncated = true;
                break;
            }
            sb.append(entry);
            lastIncluded = i + 1;
        }
        if (pageTruncated) {
            sb.append("[Output truncated: showing lines ")
                    .append(startLine)
                    .append('-')
                    .append(lastIncluded)
                    .append(" of the requested ")
                    .append(startLine)
                    .append('-')
                    .append(end)
                    .append("; the ")
                    .append(MAX_OUTPUT_CHARS)
                    .append("-character page budget was reached. Continue with memory_get path=")
                    .append(path)
                    .append(" startLine=")
                    .append(lastIncluded + 1)
                    .append(" endLine=")
                    .append(end)
                    .append(".]");
        }
        return ToolResultBlock.success(sb.toString());
    }

    /** Keeps the head and tail of an oversized line so both ends stay visible. */
    private static String boundedLine(String line) {
        int total = line.codePointCount(0, line.length());
        if (total <= MAX_LINE_CHARS) {
            return line;
        }
        String marker = " ... [truncated] ... ";
        int body = MAX_LINE_CHARS - marker.length();
        int head = body / 2;
        int tail = body - head;
        int headEnd = line.offsetByCodePoints(0, head);
        int tailStart = line.offsetByCodePoints(0, total - tail);
        return line.substring(0, headEnd) + marker + line.substring(tailStart);
    }
}
