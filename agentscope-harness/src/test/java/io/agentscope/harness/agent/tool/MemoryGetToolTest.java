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

import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertErrorKind;
import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertNoErrorMetadata;
import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolErrorKind;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryGetToolTest {
    private static final Pattern NEXT_START = Pattern.compile("startLine=(\\d+) endLine=");

    @TempDir Path workspace;

    private MemoryGetTool tool;
    private final RuntimeContext context = RuntimeContext.empty();

    @BeforeEach
    void setUp() {
        tool = new MemoryGetTool(new WorkspaceManager(workspace));
    }

    @Test
    void readsTheRequestedInclusiveRangeWithLineNumbers() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "a\nb\nc\nd\ne\n");

        ToolResultBlock result = tool.memoryGet(context, "MEMORY.md", 2, 4);
        String text = assertText(result, ToolResultState.SUCCESS);

        assertNoErrorMetadata(result);
        assertTrue(text.contains("2|b"), text);
        assertTrue(text.contains("3|c"), text);
        assertTrue(text.contains("4|d"), text);
        assertTrue(!text.contains("1|a") && !text.contains("5|e"), text);
    }

    @Test
    void pageIsBoundedAndPointsAtTheNextStartLine() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), longLines(30, 1_000));

        String text =
                assertText(tool.memoryGet(context, "MEMORY.md", 1, 30), ToolResultState.SUCCESS);

        assertTrue(text.contains("Output truncated"), text);
        assertTrue(text.contains("Continue with memory_get path=MEMORY.md"), text);
        assertTrue(text.length() <= MemoryGetTool.MAX_OUTPUT_CHARS, text);
        Matcher matcher = NEXT_START.matcher(text);
        assertTrue(matcher.find(), text);
        int next = Integer.parseInt(matcher.group(1));
        assertTrue(next > 1 && next <= 30, text);

        // The next page must continue from where this one stopped, without duplicating lines.
        String second =
                assertText(tool.memoryGet(context, "MEMORY.md", next, 30), ToolResultState.SUCCESS);
        assertTrue(second.startsWith(next + "|line-" + next), second);
    }

    @Test
    void oversizedSingleLineKeepsHeadAndTail() throws Exception {
        String longLine = "line-start " + "x".repeat(5_000) + " line-END";
        Files.writeString(workspace.resolve("MEMORY.md"), longLine + "\n");

        String text =
                assertText(tool.memoryGet(context, "MEMORY.md", 1, 1), ToolResultState.SUCCESS);

        assertTrue(text.contains("line-start"), text);
        assertTrue(text.contains("line-END"), text);
        assertTrue(text.contains("[truncated]"), text);
        assertTrue(text.length() <= MemoryGetTool.MAX_OUTPUT_CHARS, text);
    }

    @Test
    void missingFileErrorExplainsHowToFindMemory() {
        ToolResultBlock result = tool.memoryGet(context, "memory/missing.md", 1, 10);
        String text = assertText(result, ToolResultState.ERROR);
        assertErrorKind(result, ToolErrorKind.NOT_FOUND, "memory/missing.md");
        assertTrue(text.contains("Memory file not found or empty: memory/missing.md"), text);
        assertTrue(text.contains("memory_search"), text);
    }

    @Test
    void traversalIsRejectedWithAnActionableMessage() {
        ToolResultBlock result = tool.memoryGet(context, "../MEMORY.md", 1, 10);
        String text = assertText(result, ToolResultState.ERROR);
        assertErrorKind(result, ToolErrorKind.PERMISSION_DENIED, "../MEMORY.md");
        assertTrue(text.contains("relative path inside the memory namespace"), text);
    }

    @Test
    void startLineBeyondTheFileSuggestsAValidLine() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "a\nb\n");

        ToolResultBlock result = tool.memoryGet(context, "MEMORY.md", 9, 12);
        String text = assertText(result, ToolResultState.ERROR);
        assertErrorKind(result, ToolErrorKind.INVALID_ARGUMENT, "MEMORY.md");

        assertTrue(text.contains("exceeds file length 3"), text);
        assertTrue(text.contains("retry with startLine <= 3"), text);
    }

    @Test
    void reversedRangeIsRejected() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "a\nb\nc\n");

        ToolResultBlock result = tool.memoryGet(context, "MEMORY.md", 3, 2);
        String text = assertText(result, ToolResultState.ERROR);
        assertErrorKind(result, ToolErrorKind.INVALID_ARGUMENT, "MEMORY.md");

        assertTrue(text.contains("must be >= startLine"), text);
    }

    @Test
    void nonPositiveStartLineIsRejected() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "a\nb\n");

        ToolResultBlock result = tool.memoryGet(context, "MEMORY.md", 0, 2);
        String text = assertText(result, ToolResultState.ERROR);
        assertErrorKind(result, ToolErrorKind.INVALID_ARGUMENT, "MEMORY.md");

        assertEquals("Error: startLine must be >= 1 (1-based); got 0.", text);
    }

    private static String longLines(int count, int lineChars) {
        String filler = "x".repeat(lineChars - 10);
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> "line-" + i + " " + filler)
                .collect(Collectors.joining("\n"));
    }
}
