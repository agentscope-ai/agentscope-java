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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the three {@link LocalFsMode} paths through {@link LocalFilesystem#read}: that
 * SANDBOXED rejects escaping absolute paths, ROOTED rejects paths outside policy roots, and
 * UNRESTRICTED accepts any absolute path.
 */
class LocalFilesystemModeTest {

    @Test
    void sandboxed_rejectsAbsolutePathOutsideRoot(@TempDir Path workspace, @TempDir Path other)
            throws IOException {
        Path outsideFile = other.resolve("secret.txt");
        Files.writeString(outsideFile, "leaked", StandardCharsets.UTF_8);

        LocalFilesystem fs = new LocalFilesystem(workspace, LocalFsMode.SANDBOXED, null, 10, null);
        ReadResult r = fs.read(RuntimeContext.empty(), outsideFile.toString(), 0, 0);
        assertFalse(r.isSuccess());
        assertNotNull(r.error());
        // SANDBOXED resolves all paths under workspace; the absolute path becomes a non-existent
        // relative under the root.
        assertTrue(r.error().toLowerCase().contains("not found"));
    }

    @Test
    void rooted_acceptsAbsolutePathUnderPolicyRoot(@TempDir Path workspace, @TempDir Path project)
            throws IOException {
        Path projectFile = project.resolve("AGENTS.md");
        Files.writeString(projectFile, "hello", StandardCharsets.UTF_8);

        PathPolicy policy = PathPolicy.of(project, workspace);
        LocalFilesystem fs = new LocalFilesystem(workspace, LocalFsMode.ROOTED, policy, 10, null);

        ReadResult r = fs.read(RuntimeContext.empty(), projectFile.toString(), 0, 0);
        assertTrue(r.isSuccess(), () -> "expected success, got: " + r.error());
        assertEquals("hello", r.fileData().content());
    }

    @Test
    void rooted_rejectsAbsolutePathOutsideAllRoots(
            @TempDir Path workspace, @TempDir Path project, @TempDir Path forbidden)
            throws IOException {
        Path outsideFile = forbidden.resolve("secret.txt");
        Files.writeString(outsideFile, "leaked", StandardCharsets.UTF_8);

        PathPolicy policy = PathPolicy.of(project, workspace);
        LocalFilesystem fs = new LocalFilesystem(workspace, LocalFsMode.ROOTED, policy, 10, null);

        Throwable t =
                org.junit.jupiter.api.Assertions.assertThrows(
                        SecurityException.class,
                        () -> fs.read(RuntimeContext.empty(), outsideFile.toString(), 0, 0));
        assertTrue(t.getMessage().contains(outsideFile.toString()));
    }

    @Test
    void unrestricted_acceptsAnyAbsolutePath(@TempDir Path workspace, @TempDir Path elsewhere)
            throws IOException {
        Path file = elsewhere.resolve("note.txt");
        Files.writeString(file, "free", StandardCharsets.UTF_8);

        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.UNRESTRICTED, null, 10, null);

        ReadResult r = fs.read(RuntimeContext.empty(), file.toString(), 0, 0);
        assertTrue(r.isSuccess(), () -> "expected success, got: " + r.error());
        assertEquals("free", r.fileData().content());
    }

    @Test
    void rooted_workspaceItselfIsImplicitlyAllowed(@TempDir Path workspace) throws IOException {
        Path file = workspace.resolve("inside.txt");
        Files.writeString(file, "here", StandardCharsets.UTF_8);

        // Empty policy — only the cwd root is implicitly accepted.
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);

        ReadResult r = fs.read(RuntimeContext.empty(), file.toString(), 0, 0);
        assertTrue(r.isSuccess());
        assertEquals("here", r.fileData().content());
    }

    @Test
    void legacy_booleanConstructor_mapsTrueToSandboxed(@TempDir Path workspace, @TempDir Path other)
            throws IOException {
        Path outsideFile = other.resolve("secret.txt");
        Files.writeString(outsideFile, "leaked", StandardCharsets.UTF_8);

        LocalFilesystem fs = new LocalFilesystem(workspace, true, 10, null);
        ReadResult r = fs.read(RuntimeContext.empty(), outsideFile.toString(), 0, 0);
        assertFalse(r.isSuccess(), "SANDBOXED should refuse outside absolute path");
    }

    @Test
    void legacy_booleanConstructor_mapsFalseToUnrestricted(
            @TempDir Path workspace, @TempDir Path elsewhere) throws IOException {
        Path file = elsewhere.resolve("note.txt");
        Files.writeString(file, "free", StandardCharsets.UTF_8);

        LocalFilesystem fs = new LocalFilesystem(workspace, false, 10, null);
        ReadResult r = fs.read(RuntimeContext.empty(), file.toString(), 0, 0);
        assertTrue(r.isSuccess(), "UNRESTRICTED should accept any absolute path");
        assertEquals("free", r.fileData().content());
    }
}
