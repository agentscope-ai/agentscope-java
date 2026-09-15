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
package io.agentscope.harness.agent.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link LocalFilesystemSpec} default behavior when {@code project} is not set.
 *
 * <p>When {@code project} is null, the lower layer should default to the agent workspace,
 * preventing unintended exposure of the JVM process's working directory ({@code user.dir}).
 */
class LocalFilesystemSpecTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    @Test
    void nullProject_usesWorkspaceAsLowerLayer(@TempDir Path workspace) throws Exception {
        // Create a file in workspace that the agent should be able to read
        Path workspaceFile = workspace.resolve("workspace-file.txt");
        Files.writeString(workspaceFile, "content from workspace", StandardCharsets.UTF_8);

        // Create spec without setting project (simulates framework default)
        LocalFilesystemSpec spec = new LocalFilesystemSpec();

        // Build filesystem
        AbstractFilesystem fs = spec.toFilesystem(workspace, ns -> List.of("test"));

        // Agent should be able to read workspace files
        ReadResult result = fs.read(RT, workspaceFile.toAbsolutePath().toString(), 0, 0);
        assertTrue(result.isSuccess(), "Agent should be able to read workspace files");
        assertEquals("content from workspace", result.fileData().content());
    }

    @Test
    void nullProject_cannotReadFilesOutsideWorkspace(@TempDir Path workspace, @TempDir Path outside)
            throws Exception {
        // Create a file outside workspace that the agent should NOT be able to read
        Path outsideFile = outside.resolve("secret.txt");
        Files.writeString(outsideFile, "secret content", StandardCharsets.UTF_8);

        // Create spec without setting project
        LocalFilesystemSpec spec = new LocalFilesystemSpec();

        // Build filesystem
        AbstractFilesystem fs = spec.toFilesystem(workspace, ns -> List.of("test"));

        // Agent should NOT be able to read files outside workspace via lower layer
        ReadResult result = fs.read(RT, outsideFile.toAbsolutePath().toString(), 0, 0);
        assertFalse(
                result.isSuccess(),
                "Agent should NOT be able to read files outside workspace when project is not set");
    }

    @Test
    void projectSet_usesProjectAsLowerLayer(@TempDir Path project, @TempDir Path workspace)
            throws Exception {
        // Create a file in project directory
        Path projectFile = project.resolve("project-file.txt");
        Files.writeString(projectFile, "content from project", StandardCharsets.UTF_8);

        // Create spec with explicit project
        LocalFilesystemSpec spec = new LocalFilesystemSpec().project(project);

        // Build filesystem
        AbstractFilesystem fs = spec.toFilesystem(workspace, ns -> List.of("test"));

        // Agent should be able to read project files via lower layer
        ReadResult result = fs.read(RT, projectFile.toAbsolutePath().toString(), 0, 0);
        assertTrue(result.isSuccess(), "Agent should be able to read project files");
        assertEquals("content from project", result.fileData().content());
    }

    @Test
    void projectSet_cannotReadUserDir(@TempDir Path project, @TempDir Path workspace)
            throws Exception {
        // Create a file in user.dir that the agent should NOT be able to read
        String userDir = System.getProperty("user.dir");
        Path userDirFile = Path.of(userDir, "test-secret-userdir.txt");
        Files.writeString(userDirFile, "secret from user.dir", StandardCharsets.UTF_8);
        try {
            // Create spec with explicit project (different from user.dir)
            LocalFilesystemSpec spec = new LocalFilesystemSpec().project(project);

            // Build filesystem
            AbstractFilesystem fs = spec.toFilesystem(workspace, ns -> List.of("test"));

            // Agent should NOT be able to read files from user.dir via lower layer
            ReadResult result = fs.read(RT, userDirFile.toAbsolutePath().toString(), 0, 0);
            assertFalse(
                    result.isSuccess(),
                    "Agent should NOT be able to read files from user.dir when project is set"
                            + " explicitly");
        } finally {
            Files.deleteIfExists(userDirFile);
        }
    }
}
