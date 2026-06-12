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
package io.agentscope.harness.agent.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceManagerListingTest {

    private static final RuntimeContext EMPTY_CONTEXT = RuntimeContext.empty();

    private static final class StaticGlobFilesystem implements AbstractFilesystem {
        private final GlobResult globResult;

        private StaticGlobFilesystem(GlobResult globResult) {
            this.globResult = globResult;
        }

        @Override
        public LsResult ls(RuntimeContext runtimeContext, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReadResult read(
                RuntimeContext runtimeContext, String filePath, int offset, int limit) {
            return ReadResult.fail("not found");
        }

        @Override
        public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EditResult edit(
                RuntimeContext runtimeContext,
                String filePath,
                String oldString,
                String newString,
                boolean replaceAll) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GrepResult grep(
                RuntimeContext runtimeContext, String pattern, String path, String glob) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
            return globResult;
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(
                RuntimeContext runtimeContext, List<String> paths) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WriteResult delete(RuntimeContext runtimeContext, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(RuntimeContext runtimeContext, String path) {
            return false;
        }
    }

    private static String invokeNormalizeListedPath(WorkspaceManager wm, String path) {
        try {
            Method method =
                    WorkspaceManager.class.getDeclaredMethod("normalizeListedPath", String.class);
            method.setAccessible(true);
            return (String) method.invoke(wm, path);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke normalizeListedPath", e);
        }
    }

    private static String stripLeadingSlashes(String value) {
        String normalized = value.replace('\\', '/').strip();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    @Test
    void listKnowledgeFiles_returnsWorkspaceRelativePaths(
            @TempDir Path project, @TempDir Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve("knowledge"));
        Files.writeString(workspace.resolve("knowledge/guide.md"), "guide");

        AbstractFilesystem fs =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, null);
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs)) {
            List<Path> knowledgeFiles = wm.listKnowledgeFiles(EMPTY_CONTEXT);
            assertEquals(
                    1,
                    knowledgeFiles.size(),
                    () -> "Unexpected knowledge files: " + knowledgeFiles);
            assertEquals(
                    workspace.resolve("knowledge/guide.md").normalize(),
                    knowledgeFiles.get(0).normalize());
        }
    }

    @Test
    void listKnowledgeFiles_normalizesProjectLayerAbsolutePaths(
            @TempDir Path project, @TempDir Path workspace) throws IOException {
        Files.createDirectories(project.resolve("knowledge"));
        Files.writeString(project.resolve("knowledge/guide.md"), "guide");

        AbstractFilesystem fs =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, null);
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs)) {
            List<Path> knowledgeFiles = wm.listKnowledgeFiles(EMPTY_CONTEXT);
            assertEquals(
                    1,
                    knowledgeFiles.size(),
                    () -> "Unexpected knowledge files: " + knowledgeFiles);
            assertEquals(
                    workspace.resolve("knowledge/guide.md").normalize(),
                    knowledgeFiles.get(0).normalize());
        }
    }

    @Test
    void listMemoryFilePaths_returnsWorkspaceRelativePaths(
            @TempDir Path project, @TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("MEMORY.md"), "memory");
        Files.createDirectories(workspace.resolve("memory"));
        Files.writeString(workspace.resolve("memory/notes.md"), "notes");

        AbstractFilesystem fs =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, null);
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs)) {
            List<String> memoryFiles = wm.listMemoryFilePaths(EMPTY_CONTEXT);
            assertEquals(2, memoryFiles.size(), () -> "Unexpected memory files: " + memoryFiles);
            assertTrue(memoryFiles.contains("MEMORY.md"));
            assertTrue(memoryFiles.contains("memory/notes.md"));
        }
    }

    @Test
    void listMemoryFilePaths_normalizesProjectLayerAbsolutePaths(
            @TempDir Path project, @TempDir Path workspace) throws IOException {
        Files.writeString(project.resolve("MEMORY.md"), "memory");
        Files.createDirectories(project.resolve("memory"));
        Files.writeString(project.resolve("memory/notes.md"), "notes");

        AbstractFilesystem fs =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, null);
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs)) {
            List<String> memoryFiles = wm.listMemoryFilePaths(EMPTY_CONTEXT);
            assertEquals(2, memoryFiles.size(), () -> "Unexpected memory files: " + memoryFiles);
            assertTrue(memoryFiles.contains("MEMORY.md"));
            assertTrue(memoryFiles.contains("memory/notes.md"));
        }
    }

    @Test
    void listMemoryFilePaths_normalizesRelativeAndForeignAbsolutePaths(@TempDir Path workspace)
            throws IOException {
        Path foreignRoot = Files.createTempDirectory("workspace-manager-foreign");
        Path foreignMemoryFile = foreignRoot.resolve("memory/foreign.md");
        Files.createDirectories(foreignMemoryFile.getParent());
        Files.writeString(foreignMemoryFile, "foreign");

        AbstractFilesystem fs =
                new StaticGlobFilesystem(
                        GlobResult.success(
                                List.of(
                                        FileInfo.ofFile("memory/relative.md", 1, ""),
                                        FileInfo.ofFile(foreignMemoryFile.toString(), 1, ""))));
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs)) {
            List<String> memoryFiles = wm.listMemoryFilePaths(EMPTY_CONTEXT);
            assertTrue(memoryFiles.contains("memory/relative.md"));
            assertTrue(memoryFiles.contains(stripLeadingSlashes(foreignMemoryFile.toString())));
        }
    }

    @Test
    void listAllTaskRecords_ignoresEntriesThatNormalizeToEmpty(@TempDir Path workspace) {
        AbstractFilesystem fs =
                new StaticGlobFilesystem(
                        GlobResult.success(
                                List.of(
                                        FileInfo.ofFile("/", 1, Instant.now().toString()),
                                        FileInfo.ofFile("   ", 1, Instant.now().toString()))));
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs)) {
            assertTrue(
                    wm.listAllTaskRecords(EMPTY_CONTEXT, "agent", Duration.ofMinutes(5)).isEmpty());
        }
    }

    @Test
    void normalizeListedPath_handlesBlankRelativeAndForeignAbsoluteInputs(@TempDir Path workspace) {
        AbstractFilesystem fs = new StaticGlobFilesystem(GlobResult.success(List.of()));
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs)) {
            assertEquals("", invokeNormalizeListedPath(wm, "   "));
            assertEquals("memory/relative.md", invokeNormalizeListedPath(wm, "memory/relative.md"));

            Path foreignAbsolute =
                    Path.of(System.getProperty("java.io.tmpdir")).resolve("outside.md");
            assertEquals(
                    stripLeadingSlashes(foreignAbsolute.toString()),
                    invokeNormalizeListedPath(wm, foreignAbsolute.toString()));
        }
    }
}
