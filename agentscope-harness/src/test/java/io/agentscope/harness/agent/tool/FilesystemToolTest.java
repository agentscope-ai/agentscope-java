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

    @Test
    void editFile_omittedReplaceAll_defaultsToFalse() {
        when(filesystem.edit(eq(RT), eq("f.txt"), eq("old"), eq("new"), eq(false)))
                .thenReturn(EditResult.ok("f.txt", 1));

        String result = tool.editFile(RT, "f.txt", "old", "new", null);

        assertTrue(result.startsWith("Edited "));
        verify(filesystem).edit(RT, "f.txt", "old", "new", false);
    }

    @Test
    void editFile_replaceAllTrue_passesTrueToFilesystem() {
        when(filesystem.edit(eq(RT), eq("f.txt"), eq("old"), eq("new"), eq(true)))
                .thenReturn(EditResult.ok("f.txt", 2));

        String result = tool.editFile(RT, "f.txt", "old", "new", true);

        assertTrue(result.contains("2 replacement"));
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

        String result =
                tool.listFiles(
                        RT,
                        "D:\\workspace\\my-learn\\agentscope-v2\\.agentscope\\workspace\\memory");

        assertTrue(result.contains("[DIR]"));
        verify(filesystem).ls(RT, "memory");
    }

    @Test
    void readFile_omittedOffsetAndLimit_defaultToZero() {
        when(filesystem.read(eq(RT), eq("f.txt"), eq(0), eq(0)))
                .thenReturn(ReadResult.success(new FileData("hello", "utf-8")));

        String result = tool.readFile(RT, "f.txt", null, null);

        assertEquals("hello", result);
        verify(filesystem).read(RT, "f.txt", 0, 0);
    }

    @Test
    void readFile_explicitOffsetAndLimit_arePassedThrough() {
        when(filesystem.read(eq(RT), eq("f.txt"), eq(2), eq(5)))
                .thenReturn(ReadResult.success(new FileData("world", "utf-8")));

        String result = tool.readFile(RT, "f.txt", 2, 5);

        assertEquals("world", result);
        verify(filesystem).read(RT, "f.txt", 2, 5);
    }

    @Test
    void grepFiles_omittedLimit_appliesServerDefaultAndReportsTruncation() {
        when(filesystem.grep(RT, "needle", ".", null))
                .thenReturn(GrepResult.success(grepMatches(FilesystemTool.DEFAULT_GREP_LIMIT + 1)));

        String result = tool.grepFiles(RT, "needle", ".", null, null);

        assertTrue(result.contains("file-99.txt:100:match-99"));
        assertFalse(result.contains("file-100.txt:101:match-100"));
        assertTrue(result.contains("showing 100 of 101 matches"));
    }

    @Test
    void grepFiles_explicitLimit_isApplied() {
        when(filesystem.grep(RT, "needle", ".", null))
                .thenReturn(GrepResult.success(grepMatches(3)));

        String result = tool.grepFiles(RT, "needle", ".", null, 2);

        assertTrue(result.contains("file-1.txt:2:match-1"));
        assertFalse(result.contains("file-2.txt:3:match-2"));
        assertTrue(result.contains("showing 2 of 3 matches"));
    }

    @Test
    void grepFiles_limitAboveMaximum_isCapped() {
        when(filesystem.grep(RT, "needle", ".", null))
                .thenReturn(GrepResult.success(grepMatches(FilesystemTool.MAX_SEARCH_LIMIT + 1)));

        String result = tool.grepFiles(RT, "needle", ".", null, Integer.MAX_VALUE);

        assertFalse(result.contains("file-1000.txt:1001:match-1000"));
        assertTrue(result.contains("showing 1000 of 1001 matches"));
        assertTrue(result.contains("Hard maximum of 1000 reached"));
        assertFalse(result.contains("increase limit"));
    }

    @Test
    void grepFiles_nonPositiveLimit_isRejectedBeforeSearch() {
        String result = tool.grepFiles(RT, "needle", ".", null, 0);

        assertEquals("Error: limit must be greater than 0", result);
        verifyNoInteractions(filesystem);
    }

    @Test
    void globFiles_omittedLimit_appliesServerDefaultAndReportsTruncation() {
        when(filesystem.glob(RT, "**/*.txt", "."))
                .thenReturn(GlobResult.success(files(FilesystemTool.DEFAULT_GLOB_LIMIT + 1)));

        String result = tool.globFiles(RT, "**/*.txt", ".", null);

        assertTrue(result.contains("file-199.txt (199 bytes)"));
        assertFalse(result.contains("file-200.txt (200 bytes)"));
        assertTrue(result.contains("showing 200 of 201 files"));
    }

    @Test
    void searchOverloads_remainBackwardCompatibleForDirectCallers() {
        when(filesystem.grep(RT, "needle", ".", null))
                .thenReturn(GrepResult.success(grepMatches(1)));
        when(filesystem.glob(RT, "*.txt", ".")).thenReturn(GlobResult.success(files(1)));

        assertEquals("file-0.txt:1:match-0", tool.grepFiles(RT, "needle", ".", null));
        assertEquals("file-0.txt (0 bytes)", tool.globFiles(RT, "*.txt", "."));
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
