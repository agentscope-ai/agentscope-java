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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * memory_get reads must stay bounded no matter how the model drives startLine/endLine —
 * the tool is excluded from tool-result eviction, so an oversized page goes straight into
 * the next model request. Mirrors the read_file guarantees of {@link BoundedFileReadTest}.
 */
class BoundedMemoryGetTest {
    @TempDir Path workspace;

    private MemoryGetTool tool() {
        return new MemoryGetTool(new WorkspaceManager(workspace));
    }

    private String text(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining());
    }

    @Test
    void wholeFileRequestStaysBounded() throws Exception {
        Files.writeString(
                workspace.resolve("MEMORY.md"), "line of memory content\n".repeat(50_000));
        String output =
                text(tool().memoryGet(RuntimeContext.empty(), "MEMORY.md", 1, Integer.MAX_VALUE));
        assertTrue(
                output.codePointCount(0, output.length()) < 40_000,
                "memory_get must bound a whole-file request");
        assertTrue(
                output.contains("[Output truncated"),
                "oversized pages must say they were truncated");
        assertTrue(output.contains("char_offset="), "truncated pages must carry a resume offset");
    }

    @Test
    void singleLongLineStaysBounded() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "x".repeat(200_000) + "\nshort tail");
        String output = text(tool().memoryGet(RuntimeContext.empty(), "MEMORY.md", 1, 2));
        assertTrue(
                output.codePointCount(0, output.length()) < 40_000,
                "line pagination must also bound a single long line");
        assertTrue(output.contains("[Output truncated"));
    }

    @Test
    void unicodePagesReassembleWithoutSplittingSurrogates() throws Exception {
        String line = "a\uD83D\uDE00中".repeat(19);
        Files.writeString(workspace.resolve("MEMORY.md"), line);
        // memory_get numbers each line and terminates it with the platform line
        // separator (%n: "\n" on Linux, "\r\n" on Windows), so page through the
        // formatted content rather than assuming the raw line's length.
        String formatted = "1|" + line + System.lineSeparator();
        int total = formatted.codePointCount(0, formatted.length());
        StringBuilder restored = new StringBuilder();
        for (int start = 0; start < total; start += 5) {
            String output =
                    text(tool().memoryGet(RuntimeContext.empty(), "MEMORY.md", 1, 1, start, 5));
            int marker = output.indexOf("\n[Output truncated.");
            String page = marker < 0 ? output : output.substring(0, marker);
            assertTrue(page.codePointCount(0, page.length()) <= 5);
            assertFalse(Character.isLowSurrogate(page.charAt(0)));
            assertFalse(Character.isHighSurrogate(page.charAt(page.length() - 1)));
            if (marker >= 0) assertTrue(output.contains("char_offset=" + (start + 5)));
            restored.append(page);
        }
        assertEquals(formatted, restored.toString());
    }

    @Test
    void explicitEndLineAndMaxCharsCannotBypassMaximum() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "\uD83D\uDE00".repeat(100_000));
        String output =
                text(
                        tool().memoryGet(
                                        RuntimeContext.empty(),
                                        "MEMORY.md",
                                        1,
                                        Integer.MAX_VALUE,
                                        0,
                                        Integer.MAX_VALUE));
        assertTrue(
                output.codePointCount(0, output.length()) < 40_000,
                "explicit params must be clamped to the application maximum");
    }

    @Test
    void normalSmallReadsKeepLineNumbersAndRange() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "alpha\nbeta\ngamma\n");
        String output = text(tool().memoryGet(RuntimeContext.empty(), "MEMORY.md", 2, 3));
        assertEquals("2|beta%n3|gamma%n".replace("%n", System.lineSeparator()), output);
        assertFalse(output.contains("[Output truncated"));
    }
}
