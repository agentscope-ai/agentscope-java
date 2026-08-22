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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FilesystemTool}. */
class FilesystemToolTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    private AbstractFilesystem filesystem;
    private FilesystemTool tool;

    @BeforeEach
    void setUp() {
        filesystem = mock(AbstractFilesystem.class);
        tool = new FilesystemTool(filesystem);
    }

    /** Extracts the concatenated text output of a tool result. */
    private static String textOf(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .collect(Collectors.joining());
    }

    @Test
    void editFile_omittedReplaceAll_defaultsToFalse() {
        when(filesystem.edit(eq(RT), eq("f.txt"), eq("old"), eq("new"), eq(false)))
                .thenReturn(EditResult.ok("f.txt", 1));

        ToolResultBlock result = tool.editFile(RT, "f.txt", "old", "new", null);

        assertTrue(textOf(result).startsWith("Edited "));
        assertEquals(ToolResultState.SUCCESS, result.getState());
        verify(filesystem).edit(RT, "f.txt", "old", "new", false);
    }

    @Test
    void editFile_replaceAllTrue_passesTrueToFilesystem() {
        when(filesystem.edit(eq(RT), eq("f.txt"), eq("old"), eq("new"), eq(true)))
                .thenReturn(EditResult.ok("f.txt", 2));

        ToolResultBlock result = tool.editFile(RT, "f.txt", "old", "new", true);

        assertTrue(textOf(result).contains("2 replacement"));
        assertEquals(ToolResultState.SUCCESS, result.getState());
        verify(filesystem).edit(RT, "f.txt", "old", "new", true);
    }

    @Test
    void listFiles_normalizesWindowsAbsoluteWorkspacePath() {
        WorkspacePathNormalizer normalizer =
                WorkspacePathNormalizer.of(
                        "D:\\workspace\\my-learn\\agentscope-v2\\.agentscope\\workspace");
        tool = new FilesystemTool(filesystem, normalizer);

        when(filesystem.ls(RT, "memory"))
                .thenReturn(LsResult.success(List.of(FileInfo.ofDir("memory", ""))));

        ToolResultBlock result =
                tool.listFiles(
                        RT,
                        "D:\\workspace\\my-learn\\agentscope-v2\\.agentscope\\workspace\\memory");

        assertTrue(textOf(result).contains("[DIR]"));
        assertEquals(ToolResultState.SUCCESS, result.getState());
        verify(filesystem).ls(RT, "memory");
    }

    @Test
    void readFile_omittedOffsetAndLimit_defaultToZero() {
        when(filesystem.read(eq(RT), eq("f.txt"), eq(0), eq(0)))
                .thenReturn(ReadResult.success(new FileData("hello", "utf-8")));

        ToolResultBlock result = tool.readFile(RT, "f.txt", null, null);

        assertEquals("hello", textOf(result));
        assertEquals(ToolResultState.SUCCESS, result.getState());
        verify(filesystem).read(RT, "f.txt", 0, 0);
    }

    @Test
    void readFile_explicitOffsetAndLimit_arePassedThrough() {
        when(filesystem.read(eq(RT), eq("f.txt"), eq(2), eq(5)))
                .thenReturn(ReadResult.success(new FileData("world", "utf-8")));

        ToolResultBlock result = tool.readFile(RT, "f.txt", 2, 5);

        assertEquals("world", textOf(result));
        assertEquals(ToolResultState.SUCCESS, result.getState());
        verify(filesystem).read(RT, "f.txt", 2, 5);
    }

    @Test
    void readFile_contentLookingLikeAnError_isStillSuccess() {
        // Regression for the textual-prefix heuristic: successfully reading a log file whose
        // content begins with "Error: " must not be reported as a failed tool call.
        when(filesystem.read(eq(RT), eq("app.log"), eq(0), eq(0)))
                .thenReturn(ReadResult.success(new FileData("Error: connection refused", "utf-8")));

        ToolResultBlock result = tool.readFile(RT, "app.log", null, null);

        assertEquals("Error: connection refused", textOf(result));
        assertEquals(ToolResultState.SUCCESS, result.getState());
    }

    @Test
    void readFile_failure_reportsErrorState() {
        when(filesystem.read(eq(RT), eq("missing.txt"), eq(0), eq(0)))
                .thenReturn(ReadResult.fail("no such file"));

        ToolResultBlock result = tool.readFile(RT, "missing.txt", null, null);

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(textOf(result).contains("no such file"));
    }
}
