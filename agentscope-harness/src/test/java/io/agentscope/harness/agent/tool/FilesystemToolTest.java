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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
        assertEquals(ToolResultState.SUCCESS, result.getState());

        assertTrue(textOf(result).startsWith("Edited "));
        verify(filesystem).edit(RT, "f.txt", "old", "new", false);
    }

    @Test
    void editFile_replaceAllTrue_passesTrueToFilesystem() {
        when(filesystem.edit(eq(RT), eq("f.txt"), eq("old"), eq("new"), eq(true)))
                .thenReturn(EditResult.ok("f.txt", 2));

        ToolResultBlock result = tool.editFile(RT, "f.txt", "old", "new", true);
        assertEquals(ToolResultState.SUCCESS, result.getState());

        assertTrue(textOf(result).contains("2 replacement"));
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
        assertEquals(ToolResultState.SUCCESS, result.getState());

        assertTrue(textOf(result).contains("[DIR]"));
        verify(filesystem).ls(RT, "memory");
    }

    // ==================== Bug reproduction: listFiles ambiguous error message ====================

    @Test
    void listFiles_nonExistentPath_returnsError() {
        when(filesystem.ls(RT, "/nonexistent"))
                .thenReturn(LsResult.fail("Path does not exist: /nonexistent"));

        ToolResultBlock result = tool.listFiles(RT, "/nonexistent");
        assertEquals(ToolResultState.ERROR, result.getState());

        assertTrue(
                textOf(result).startsWith("Error:"), "should report error for non-existent path");
        assertTrue(
                textOf(result).contains("does not exist"), "error should mention 'does not exist'");
    }

    @Test
    void listFiles_filePath_returnsError() {
        when(filesystem.ls(RT, "/path/to/file.txt"))
                .thenReturn(LsResult.fail("Not a directory: /path/to/file.txt"));

        ToolResultBlock result = tool.listFiles(RT, "/path/to/file.txt");
        assertEquals(ToolResultState.ERROR, result.getState());

        assertTrue(textOf(result).startsWith("Error:"), "should report error for file path");
        assertTrue(
                textOf(result).contains("Not a directory"),
                "error should mention 'Not a directory'");
    }

    @Test
    void listFiles_emptyDirectory_returnsEmptyDirMessage() {
        when(filesystem.ls(RT, "/empty/dir")).thenReturn(LsResult.success(List.of()));

        ToolResultBlock result = tool.listFiles(RT, "/empty/dir");
        assertEquals(ToolResultState.SUCCESS, result.getState());

        assertEquals("Empty directory: /empty/dir", textOf(result));
    }

    @Test
    void readFile_omittedOffsetAndLimit_defaultToZero() {
        when(filesystem.read(eq(RT), eq("f.txt"), eq(0), eq(0)))
                .thenReturn(ReadResult.success(new FileData("hello", "utf-8")));

        ToolResultBlock result = tool.readFile(RT, "f.txt", null, null);
        assertEquals(ToolResultState.SUCCESS, result.getState());

        assertEquals("hello", textOf(result));
        verify(filesystem).read(RT, "f.txt", 0, 0);
    }

    @Test
    void readFile_explicitOffsetAndLimit_arePassedThrough() {
        when(filesystem.read(eq(RT), eq("f.txt"), eq(2), eq(5)))
                .thenReturn(ReadResult.success(new FileData("world", "utf-8")));

        ToolResultBlock result = tool.readFile(RT, "f.txt", 2, 5);
        assertEquals(ToolResultState.SUCCESS, result.getState());

        assertEquals("world", textOf(result));
        verify(filesystem).read(RT, "f.txt", 2, 5);
    }

    @Test
    void readFile_contentLookingLikeAnError_isStillSuccess() {
        // Regression for the textual-prefix heuristic: successfully reading a log file whose
        // content begins with "Error: " must not be reported as a failed tool call.
        when(filesystem.read(eq(RT), eq("app.log"), eq(0), eq(0)))
                .thenReturn(ReadResult.success(new FileData("Error: connection refused", "utf-8")));

        ToolResultBlock result = tool.readFile(RT, "app.log", null, null);
        assertEquals(ToolResultState.SUCCESS, result.getState());

        assertEquals("Error: connection refused", textOf(result));
    }

    @Test
    void readFile_failure_reportsErrorState() {
        when(filesystem.read(eq(RT), eq("missing.txt"), eq(0), eq(0)))
                .thenReturn(ReadResult.fail("no such file"));

        ToolResultBlock result = tool.readFile(RT, "missing.txt", null, null);
        assertEquals(ToolResultState.ERROR, result.getState());

        assertTrue(textOf(result).contains("no such file"));
    }

    @Test
    void broadListingsAreBoundedAndExplicitlyTruncated() {
        var entries =
                java.util.stream.IntStream.range(0, 1000)
                        .mapToObj(i -> FileInfo.ofDir("directory-" + i, ""))
                        .toList();
        when(filesystem.glob(RT, "**/*", "."))
                .thenReturn(
                        io.agentscope.harness.agent.filesystem.model.GlobResult.success(entries));
        when(filesystem.ls(RT, ".")).thenReturn(LsResult.success(entries));
        for (ToolResultBlock result :
                List.of(tool.globFiles(RT, "**/*", "."), tool.listFiles(RT, "."))) {
            assertTrue(textOf(result).contains("truncated"));
            assertTrue(textOf(result).length() < 17000);
            assertTrue(textOf(result).lines().count() <= 202);
        }
    }

    @Test
    void hugeGrepLineCannotFloodModelContext() {
        when(filesystem.grep(RT, "pattern", ".", null))
                .thenReturn(
                        io.agentscope.harness.agent.filesystem.model.GrepResult.success(
                                List.of(
                                        new io.agentscope.harness.agent.filesystem.model.GrepMatch(
                                                "file", 1, "x".repeat(300000)))));
        ToolResultBlock result = tool.grepFiles(RT, "pattern", ".", null);
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertTrue(textOf(result).contains("truncated"));
        assertTrue(textOf(result).length() < 17000);
    }

    @Test
    void grepFiles_omittedLimit_appliesServerDefaultAndReportsTruncation() {
        when(filesystem.grep(RT, "needle", ".", null))
                .thenReturn(GrepResult.success(grepMatches(FilesystemTool.DEFAULT_GREP_LIMIT + 1)));

        ToolResultBlock result = tool.grepFiles(RT, "needle", ".", null, null);
        assertEquals(ToolResultState.SUCCESS, result.getState());

        assertTrue(textOf(result).contains("file-99.txt:100:match-99"));
        assertFalse(textOf(result).contains("file-100.txt:101:match-100"));
        assertTrue(textOf(result).contains("showing 100 of 101 matches"));
    }

    @Test
    void grepFiles_explicitLimit_isApplied() {
        when(filesystem.grep(RT, "needle", ".", null))
                .thenReturn(GrepResult.success(grepMatches(3)));

        ToolResultBlock result = tool.grepFiles(RT, "needle", ".", null, 2);
        assertEquals(ToolResultState.SUCCESS, result.getState());

        assertTrue(textOf(result).contains("file-1.txt:2:match-1"));
        assertFalse(textOf(result).contains("file-2.txt:3:match-2"));
        assertTrue(textOf(result).contains("showing 2 of 3 matches"));
    }

    @Test
    void grepFiles_limitAboveMaximum_isCapped() {
        when(filesystem.grep(RT, "needle", ".", null))
                .thenReturn(
                        GrepResult.success(
                                IntStream.range(0, FilesystemTool.MAX_SEARCH_LIMIT + 1)
                                        .mapToObj(i -> new GrepMatch("f", i, "x"))
                                        .toList()));

        ToolResultBlock result = tool.grepFiles(RT, "needle", ".", null, Integer.MAX_VALUE);
        assertEquals(ToolResultState.SUCCESS, result.getState());

        assertFalse(textOf(result).contains("f:1000:x"));
        assertTrue(textOf(result).contains("showing 1000 of 1001 matches"));
        assertTrue(textOf(result).contains("Hard maximum of 1000 reached"));
        assertFalse(textOf(result).contains("increase limit"));
    }

    @Test
    void grepFiles_nonPositiveLimit_isRejectedBeforeSearch() {
        ToolResultBlock result = tool.grepFiles(RT, "needle", ".", null, 0);
        assertEquals(ToolResultState.ERROR, result.getState());

        assertEquals("Error: limit must be greater than 0", textOf(result));
        verifyNoInteractions(filesystem);
    }

    @Test
    void globFiles_omittedLimit_appliesServerDefaultAndReportsTruncation() {
        when(filesystem.glob(RT, "**/*.txt", "."))
                .thenReturn(GlobResult.success(files(FilesystemTool.DEFAULT_GLOB_LIMIT + 1)));

        ToolResultBlock result = tool.globFiles(RT, "**/*.txt", ".", null);
        assertEquals(ToolResultState.SUCCESS, result.getState());

        assertTrue(textOf(result).contains("file-199.txt (199 bytes)"));
        assertFalse(textOf(result).contains("file-200.txt (200 bytes)"));
        assertTrue(textOf(result).contains("showing 200 of 201 files"));
    }

    @Test
    void searchOverloads_useDefaultLimitsForDirectCallers() {
        when(filesystem.grep(RT, "needle", ".", null))
                .thenReturn(GrepResult.success(grepMatches(1)));
        when(filesystem.glob(RT, "*.txt", ".")).thenReturn(GlobResult.success(files(1)));

        assertEquals("file-0.txt:1:match-0", textOf(tool.grepFiles(RT, "needle", ".", null)));
        assertEquals("file-0.txt (0 bytes)", textOf(tool.globFiles(RT, "*.txt", ".")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchToolSchemas_exposeLimitAsOptional() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);

        for (String toolName : List.of("grep_files", "glob_files")) {
            AgentTool registered = toolkit.getTool(toolName);
            Map<String, Object> schema = registered.getParameters();
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            List<String> required = (List<String>) schema.get("required");

            assertTrue(properties.containsKey("limit"));
            assertFalse(required.contains("limit"));
        }
    }

    @Test
    void explicitGlobLimitAboveDefault_stillRespectsCharacterBudget() {
        when(filesystem.glob(RT, "**/*.txt", ".")).thenReturn(GlobResult.success(files(351)));

        ToolResultBlock result = tool.globFiles(RT, "**/*.txt", ".", 350);
        assertEquals(ToolResultState.SUCCESS, result.getState());

        assertTrue(textOf(result).contains("file-349.txt (349 bytes)"));
        assertFalse(textOf(result).contains("file-350.txt (350 bytes)"));
        assertTrue(textOf(result).contains("showing 350 of 351 files"));
        assertTrue(textOf(result).length() < 17000);
    }

    @Test
    void characterBudget_reportsActualCountInsteadOfRequestedLimit() {
        when(filesystem.grep(RT, "needle", ".", null))
                .thenReturn(
                        GrepResult.success(
                                List.of(
                                        new GrepMatch("small", 1, "match"),
                                        new GrepMatch("large", 2, "x".repeat(300000)),
                                        new GrepMatch("last", 3, "match"))));

        ToolResultBlock result = tool.grepFiles(RT, "needle", ".", null, 1000);
        assertEquals(ToolResultState.SUCCESS, result.getState());

        assertTrue(textOf(result).contains("small:1:match"));
        assertTrue(textOf(result).contains("showing 1 of 3 matches"));
        assertTrue(textOf(result).contains("character limit"));
        assertFalse(textOf(result).contains("increase limit"));
        assertTrue(textOf(result).length() < 17000);
    }

    private static List<GrepMatch> grepMatches(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new GrepMatch("file-" + i + ".txt", i + 1, "match-" + i))
                .toList();
    }

    private static List<FileInfo> files(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> FileInfo.ofFile("file-" + i + ".txt", i, ""))
                .toList();
    }
}
