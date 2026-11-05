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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedFileReadTest {
    @TempDir Path workspace;

    private FilesystemTool tool() {
        return new FilesystemTool(new LocalFilesystem(workspace));
    }

    private String text(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining());
    }

    @Test
    void singleRequestedLineHasBoundedOutput() throws Exception {
        Files.writeString(workspace.resolve("single.txt"), "x".repeat(200_000));
        String output = text(tool().readFile(RuntimeContext.empty(), "single.txt", 0, 1));
        assertTrue(output.length() < 40_000, "line pagination must also bound a single long line");
        assertTrue(output.contains("char_offset"));
    }

    @Test
    void omittedLineLimitDoesNotReadAnEvictedArtifactBackIntoContext() throws Exception {
        Files.writeString(workspace.resolve("snapshot.txt"), "- link example\n".repeat(20_000));
        String output = text(tool().readFile(RuntimeContext.empty(), "snapshot.txt", null, null));
        assertTrue(output.length() < 40_000, "default read must remain bounded");
    }

    @Test
    void unicodePagesReassembleSelectedLineWithoutSplittingSurrogates() throws Exception {
        String selected = "a\uD83D\uDE00中".repeat(19);
        Files.writeString(workspace.resolve("unicode.txt"), "before\n" + selected + "\nafter");
        StringBuilder restored = new StringBuilder();
        for (int start = 0; start < 57; start += 5) {
            String output =
                    text(tool().readFile(RuntimeContext.empty(), "unicode.txt", 1, 1, start, 5));
            int marker = output.indexOf("\n[Output truncated.");
            String page = marker < 0 ? output : output.substring(0, marker);
            assertTrue(page.codePointCount(0, page.length()) <= 5);
            assertFalse(Character.isLowSurrogate(page.charAt(0)));
            assertFalse(Character.isHighSurrogate(page.charAt(page.length() - 1)));
            if (marker >= 0) assertTrue(output.contains("char_offset=" + (start + 5)));
            restored.append(page);
        }
        assertEquals(selected, restored.toString());
    }

    @Test
    void explicitLimitCannotBypassHardMaximum() throws Exception {
        Files.writeString(workspace.resolve("large.txt"), "\uD83D\uDE00".repeat(100_000));
        String output =
                text(
                        tool().readFile(
                                        RuntimeContext.empty(),
                                        "large.txt",
                                        0,
                                        1,
                                        0,
                                        Integer.MAX_VALUE));
        assertTrue(output.startsWith("\uD83D\uDE00".repeat(32_000) + "\n[Output truncated."));
        assertTrue(output.length() < 65_000);
        assertTrue(output.contains("char_offset=32000"));
    }

    @Test
    void smallOutputAndEndOffsetRemainExact() throws Exception {
        Files.writeString(workspace.resolve("small.txt"), "hello");
        assertEquals(
                "hello", text(tool().readFile(RuntimeContext.empty(), "small.txt", null, null)));
        assertEquals(
                "", text(tool().readFile(RuntimeContext.empty(), "small.txt", null, null, 5, 10)));
        assertTrue(
                text(tool().readFile(RuntimeContext.empty(), "small.txt", null, null, 6, 10))
                        .contains("exceeds"));
    }

    @Test
    void invalidPaginationDoesNotAccessFilesystem() {
        AbstractFilesystem filesystem = mock(AbstractFilesystem.class);
        FilesystemTool tool = new FilesystemTool(filesystem);
        assertTrue(
                text(tool.readFile(RuntimeContext.empty(), "file", 0, 1, -1, 1))
                        .contains("non-negative"));
        assertTrue(
                text(tool.readFile(RuntimeContext.empty(), "file", 0, 1, 0, 0))
                        .contains("greater than 0"));
        verifyNoInteractions(filesystem);
    }

    @Test
    void toolkitRegistersOnlyOneReadFileTool() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool());
        assertEquals(
                1,
                toolkit.getToolSchemas().stream()
                        .filter(schema -> "read_file".equals(schema.getName()))
                        .count());
    }
}
