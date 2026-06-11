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
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceManagerListingTest {

    @Test
    void listKnowledgeFiles_returnsWorkspaceRelativePaths(
            @TempDir Path project, @TempDir Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve("knowledge"));
        Files.writeString(workspace.resolve("knowledge/guide.md"), "guide");

        AbstractFilesystem fs = new LocalFilesystemSpec().project(project).toFilesystem(workspace, null);
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs)) {
            List<Path> knowledgeFiles = wm.listKnowledgeFiles(RuntimeContext.empty());
            assertEquals(1, knowledgeFiles.size(), () -> "Unexpected knowledge files: " + knowledgeFiles);
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

        AbstractFilesystem fs = new LocalFilesystemSpec().project(project).toFilesystem(workspace, null);
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs)) {
            List<String> memoryFiles = wm.listMemoryFilePaths(RuntimeContext.empty());
            assertEquals(2, memoryFiles.size(), () -> "Unexpected memory files: " + memoryFiles);
            assertTrue(memoryFiles.contains("MEMORY.md"));
            assertTrue(memoryFiles.contains("memory/notes.md"));
        }
    }
}
