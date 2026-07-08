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
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    // ==================== Namespace + absolute path tests ====================

    private static final NamespaceFactory USER_NS = rc -> List.of("user-1");

    @Test
    void rooted_absolutePathNotCorruptedByNamespace(@TempDir Path workspace, @TempDir Path project)
            throws IOException {
        Path projectFile = project.resolve("Main.java");
        Files.writeString(projectFile, "class Main {}", StandardCharsets.UTF_8);

        PathPolicy policy = PathPolicy.of(project, workspace);
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, policy, 10, USER_NS);
        RuntimeContext rc = RuntimeContext.builder().userId("user-1").build();

        ReadResult r = fs.read(rc, projectFile.toString(), 0, 0);
        assertTrue(
                r.isSuccess(), () -> "absolute path should resolve correctly, got: " + r.error());
        assertEquals("class Main {}", r.fileData().content());
    }

    @Test
    void rooted_absolutePathLsNotCorruptedByNamespace(
            @TempDir Path workspace, @TempDir Path project) throws IOException {
        Path subDir = project.resolve("src");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("App.java"), "class App {}", StandardCharsets.UTF_8);

        PathPolicy policy = PathPolicy.of(project, workspace);
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, policy, 10, USER_NS);
        RuntimeContext rc = RuntimeContext.builder().userId("user-1").build();

        LsResult r = fs.ls(rc, subDir.toString());
        assertTrue(r.isSuccess());
        assertFalse(r.entries().isEmpty(), "ls should find files in the project directory");
    }

    @Test
    void rooted_relativePathStillNamespaced(@TempDir Path workspace) throws IOException {
        Path nsDir = workspace.resolve("user-1");
        Files.createDirectories(nsDir);
        Files.writeString(nsDir.resolve("notes.md"), "hello", StandardCharsets.UTF_8);

        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, USER_NS);
        RuntimeContext rc = RuntimeContext.builder().userId("user-1").build();

        ReadResult r = fs.read(rc, "notes.md", 0, 0);
        assertTrue(r.isSuccess(), () -> "relative path should be namespaced, got: " + r.error());
        assertEquals("hello", r.fileData().content());
    }

    // ==================== ROOTED mode with leading "/" paths ====================

    @Test
    void rooted_leadingSlashResolvesRelativeToWorkspace(@TempDir Path workspace)
            throws IOException {
        // Create a "skills" subdirectory in the workspace
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("tool.md"), "skill content", StandardCharsets.UTF_8);

        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);

        // "/skills" should resolve to <workspace>/skills, not host absolute path
        ReadResult r = fs.read(RuntimeContext.empty(), "/skills/tool.md", 0, 0);
        assertTrue(
                r.isSuccess(),
                () -> "leading '/' should resolve relative to workspace, got: " + r.error());
        assertEquals("skill content", r.fileData().content());
    }

    @Test
    void rooted_leadingSlashLsWorks(@TempDir Path workspace) throws IOException {
        // Create a "skills" subdirectory with files
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("tool1.md"), "content1", StandardCharsets.UTF_8);
        Files.writeString(skillsDir.resolve("tool2.md"), "content2", StandardCharsets.UTF_8);

        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);

        // "/skills" should list files in <workspace>/skills
        LsResult r = fs.ls(RuntimeContext.empty(), "/skills");
        assertTrue(r.isSuccess(), () -> "ls with leading '/' should work, got: " + r.error());
        assertFalse(r.entries().isEmpty(), "ls should find files in the skills directory");
        assertEquals(2, r.entries().size());
    }

    @Test
    void rooted_leadingSlashAloneResolvesToWorkspace(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("root.txt"), "root content", StandardCharsets.UTF_8);

        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);

        // "/" should resolve to workspace root
        ReadResult r = fs.read(RuntimeContext.empty(), "/root.txt", 0, 0);
        assertTrue(r.isSuccess(), () -> "'/' should resolve to workspace root, got: " + r.error());
        assertEquals("root content", r.fileData().content());
    }

    @Test
    void rooted_leadingSlashWithNamespace(@TempDir Path workspace) throws IOException {
        // With namespace, "/skills" should still resolve to <workspace>/skills (not namespaced)
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("tool.md"), "global skill", StandardCharsets.UTF_8);

        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, USER_NS);
        RuntimeContext rc = RuntimeContext.builder().userId("user-1").build();

        // Absolute paths (starting with "/") should NOT be namespace-scoped
        ReadResult r = fs.read(rc, "/skills/tool.md", 0, 0);
        assertTrue(
                r.isSuccess(), () -> "absolute path should not be namespaced, got: " + r.error());
        assertEquals("global skill", r.fileData().content());
    }

    @Test
    void rooted_leadingSlashAllowsLiteralDotDotInPathName(@TempDir Path workspace)
            throws IOException {
        Path dir = workspace.resolve("some..dir");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("note.txt"), "literal name", StandardCharsets.UTF_8);

        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);

        ReadResult r = fs.read(RuntimeContext.empty(), "/some..dir/note.txt", 0, 0);
        assertTrue(
                r.isSuccess(),
                () -> "literal '..' inside a path segment should be allowed, got: " + r.error());
        assertEquals("literal name", r.fileData().content());
    }

    @Test
    void rooted_leadingSlashPathTraversalRejected(@TempDir Path workspace) {
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);

        // Path traversal with leading "/" should be rejected
        org.junit.jupiter.api.Assertions.assertThrows(
                SecurityException.class,
                () -> fs.read(RuntimeContext.empty(), "/../etc/passwd", 0, 0));
    }
}
