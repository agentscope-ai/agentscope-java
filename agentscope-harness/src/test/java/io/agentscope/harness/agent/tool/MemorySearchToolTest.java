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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolErrorKind;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MemorySearchToolTest {
    private final WorkspaceManager workspace = mock(WorkspaceManager.class);
    private final MemorySearchTool tool = new MemorySearchTool(workspace);
    private final RuntimeContext context = RuntimeContext.empty();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void missingQueryIsAnError(String query) {
        ToolResultBlock result = tool.memorySearch(context, query);
        String text = assertText(result, ToolResultState.ERROR);
        assertErrorKind(result, ToolErrorKind.INVALID_ARGUMENT, null);
        assertTrue(text.contains("query is required"), text);
        assertTrue(text.contains("query=\"deployment decision\""), text);
        verifyNoInteractions(workspace);
    }

    @Test
    void noMatchesIsASuccessfulSearchWithGuidance() {
        when(workspace.listMemoryFilePaths(context)).thenReturn(List.of("MEMORY.md"));
        when(workspace.readMemoryFileUtf8(context, "MEMORY.md")).thenReturn("Nothing relevant");
        ToolResultBlock result = tool.memorySearch(context, "timeout");
        String text = assertText(result, ToolResultState.SUCCESS);
        assertNoErrorMetadata(result);
        assertTrue(text.startsWith("No matching memories found for: timeout"), text);
        assertTrue(text.contains("matchMode=all"), text);
        assertTrue(text.contains("memory_get"), text);
    }

    @Test
    void errorLookingMemoryIsData() {
        when(workspace.listMemoryFilePaths(context))
                .thenReturn(List.of("MEMORY.md", "memory/empty.md"));
        when(workspace.readMemoryFileUtf8(context, "MEMORY.md"))
                .thenReturn("[ERROR] timeout\nError: timeout");
        String text = assertText(tool.memorySearch(context, "TIMEOUT"), ToolResultState.SUCCESS);
        assertTrue(text.contains("Found 2 matches:"));
        assertTrue(text.contains("MEMORY.md#1: [ERROR] timeout"));
        assertTrue(text.contains("MEMORY.md#2: Error: timeout"));
    }

    @Test
    void nullContextUsesEmptyContext() {
        when(workspace.listMemoryFilePaths(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        assertText(tool.memorySearch(null, "anything"), ToolResultState.SUCCESS);
    }

    @Test
    void defaultLimitBoundsTheNumberOfMatchesAndKeepsAContinuationNotice() {
        when(workspace.listMemoryFilePaths(context)).thenReturn(List.of("MEMORY.md"));
        when(workspace.readMemoryFileUtf8(context, "MEMORY.md")).thenReturn(matchingLines(300, 20));

        String text = assertText(tool.memorySearch(context, "timeout"), ToolResultState.SUCCESS);

        assertTrue(text.startsWith("Found 300 matches (showing 100):"), text);
        assertEquals(100, countOccurrences(text, "Source: "), text);
        assertTrue(text.contains("limit=100"), text);
        assertTrue(text.contains("max 200"), text);
        assertTrue(text.contains("memory_get path="), text);
        assertTrue(text.length() <= MemorySearchTool.MAX_RESULT_CHARS, text);
    }

    @Test
    void characterBudgetLeavesRoomForTheTrailer() {
        when(workspace.listMemoryFilePaths(context)).thenReturn(List.of("MEMORY.md"));
        when(workspace.readMemoryFileUtf8(context, "MEMORY.md"))
                .thenReturn(matchingLines(200, MemorySearchTool.MAX_LINE_CHARS));

        String text = assertText(tool.memorySearch(context, "timeout"), ToolResultState.SUCCESS);

        assertTrue(text.startsWith("Found 200 matches (showing "), text);
        assertTrue(text.contains("result budget was reached"), text);
        assertTrue(text.contains("of 200 matches"), text);
        assertTrue(text.length() <= MemorySearchTool.MAX_RESULT_CHARS, text);
    }

    @Test
    void limitIsCappedAtTheHardMaximum() {
        when(workspace.listMemoryFilePaths(context)).thenReturn(List.of("MEMORY.md"));
        when(workspace.readMemoryFileUtf8(context, "MEMORY.md")).thenReturn(matchingLines(300, 20));

        String text =
                assertText(
                        tool.memorySearch(context, "timeout", null, 10_000),
                        ToolResultState.SUCCESS);

        assertTrue(text.contains("(showing 200)"), text);
        assertEquals(200, countOccurrences(text, "Source: "), text);
        assertTrue(text.contains("limit=200"), text);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonPositiveLimitIsAnError(int limit) {
        ToolResultBlock result = tool.memorySearch(context, "timeout", null, limit);
        String text = assertText(result, ToolResultState.ERROR);
        assertErrorKind(result, ToolErrorKind.INVALID_ARGUMENT, null);
        assertEquals("Error: limit must be a positive integer (hard maximum: 200).", text);
    }

    @Test
    void invalidMatchModeIsClassifiedAsInvalidArgument() {
        ToolResultBlock result = tool.memorySearch(context, "timeout", "bogus", null);

        assertText(result, ToolResultState.ERROR);
        assertErrorKind(result, ToolErrorKind.INVALID_ARGUMENT, null);
    }

    @Test
    void oversizedLineKeepsItsHeadAndTail() {
        when(workspace.listMemoryFilePaths(context)).thenReturn(List.of("MEMORY.md"));
        String longLine = "timeout-start " + "x".repeat(2_000) + " timeout-END";
        when(workspace.readMemoryFileUtf8(context, "MEMORY.md")).thenReturn(longLine);

        String text = assertText(tool.memorySearch(context, "timeout"), ToolResultState.SUCCESS);

        assertTrue(text.contains("timeout-start"), text);
        assertTrue(text.contains("timeout-END"), text);
        assertTrue(text.contains(" ... "), text);
        String sourceLine = text.lines().skip(2).findFirst().orElseThrow();
        assertTrue(
                sourceLine.length()
                        <= "Source: MEMORY.md#1: ".length() + MemorySearchTool.MAX_LINE_CHARS,
                sourceLine);
    }

    private static String matchingLines(int count, int bodyChars) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> "timeout " + i + " " + "x".repeat(Math.max(0, bodyChars - 12)))
                .collect(Collectors.joining("\n"));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
