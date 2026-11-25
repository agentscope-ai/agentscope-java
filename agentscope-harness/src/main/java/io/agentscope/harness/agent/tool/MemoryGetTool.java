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

    public ToolResultBlock memoryGet(
            RuntimeContext runtimeContext, String path, int startLine, int endLine) {
        return memoryGet(runtimeContext, path, startLine, endLine, null);
    }

    @Tool(
            name = "memory_get",
            readOnly = true,
            evictable = false,
            prunable = false,
            description =
                    "Read specific lines from a memory file. Use after memory_search to pull full"
                        + " context around matched lines. Path is relative to the caller's memory"
                        + " namespace. Follow returned continuation parameters. For a long line,"
                        + " use startLine=endLine and charOffset to read consecutive segments.")
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
                            name = "charOffset",
                            description =
                                    "Optional zero-based Unicode codepoint offset; only valid when"
                                            + " startLine=endLine. Returns at most 2000 codepoints"
                                            + " with nextCharOffset when more remain.",
                            required = false)
                    Integer charOffset) {
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

        if (charOffset != null) {
            String line = lines.get(start);
            int total = line.codePointCount(0, line.length());
            if (startLine != endLine || charOffset < 0 || charOffset > total) {
                return ToolResultBlock.error(
                                "charOffset requires startLine=endLine and must be between 0 and "
                                        + total
                                        + " Unicode codepoints (inclusive).")
                        .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT, scope));
            }
            int next = (int) Math.min((long) total, (long) charOffset + MAX_LINE_CHARS);
            String segment =
                    line.substring(
                            line.offsetByCodePoints(0, charOffset),
                            line.offsetByCodePoints(0, next));
            String entry = String.format("%d|%s%n", startLine, segment);
            String trailer =
                    next < total
                            ? "[nextCharOffset="
                                    + next
                                    + "; continue memory_get with the same path and startLine="
                                    + startLine
                                    + " endLine="
                                    + startLine
                                    + " charOffset="
                                    + next
                                    + ".]"
                            : "";
            // A 2000-codepoint segment occupies at most 4000 UTF-16 units. Include the
            // actual line prefix and footer, rather than treating codepoints as chars.
            if (entry.length() + trailer.length() > MAX_OUTPUT_CHARS) {
                return ToolResultBlock.error("Line segment exceeds the memory_get output budget.")
                        .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT, scope));
            }
            return ToolResultBlock.success(entry + trailer);
        }

        StringBuilder sb = new StringBuilder();
        int lastIncluded = startLine - 1;
        boolean pageTruncated = false;
        boolean sawLongLine = false;
        for (int i = start; i < end; i++) {
            String line = lines.get(i);
            boolean longLine = line.codePointCount(0, line.length()) > MAX_LINE_CHARS;
            String entry = String.format("%d|%s%n", i + 1, boundedLine(line));
            String trailer = i + 1 < end ? pageTrailer(path, startLine, i + 1, end) : "";
            String recovery = sawLongLine || longLine ? longLineTrailer() : "";
            if (sb.length() + entry.length() > MAX_OUTPUT_CHARS - TRAILER_RESERVE
                    || sb.length() + entry.length() + trailer.length() + recovery.length()
                            > MAX_OUTPUT_CHARS) {
                pageTruncated = true;
                break;
            }
            sb.append(entry);
            sawLongLine |= longLine;
            lastIncluded = i + 1;
        }
        if (pageTruncated) {
            sb.append(pageTrailer(path, startLine, lastIncluded, end));
        }
        if (sawLongLine) {
            sb.append(longLineTrailer());
        }
        return ToolResultBlock.success(sb.toString());
    }

    private static String pageTrailer(String path, int start, int last, int end) {
        String continuation = "path=" + path;
        String trailer = pageTrailerText(continuation, start, last, end);
        // Do not let a long path crowd out data or exceed the existing trailer allowance.
        return trailer.length() <= TRAILER_RESERVE
                ? trailer
                : pageTrailerText("using the same path", start, last, end);
    }

    private static String pageTrailerText(String path, int start, int last, int end) {
        return "[Output truncated: showing lines "
                + start
                + '-'
                + last
                + " of the requested "
                + start
                + '-'
                + end
                + ". Continue with memory_get "
                + path
                + " startLine="
                + (last + 1)
                + " endLine="
                + end
                + ".]";
    }

    private static String longLineTrailer() {
        return "[Long lines are previews only. Recover each with memory_get using the same path,"
                + " startLine=endLine set to its line number and charOffset=0; continue with"
                + " nextCharOffset until absent.]";
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
