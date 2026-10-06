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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

    // ==================== Workspace-root skill cache + namespace ====================

    private static Path stageCachedScript(Path workspace) throws IOException {
        Path script = workspace.resolve(".skills-cache/user-1/database/demo/scripts/run.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "echo demo", StandardCharsets.UTF_8);
        return script;
    }

    @Test
    void rooted_relativeSkillsCachePathNotNamespaced(@TempDir Path workspace) throws IOException {
        stageCachedScript(workspace);
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, USER_NS);
        RuntimeContext rc = RuntimeContext.builder().userId("user-1").build();

        LsResult ls = fs.ls(rc, ".skills-cache/user-1/database/demo/scripts");
        assertTrue(ls.isSuccess(), () -> "skill cache must not be namespaced, got: " + ls.error());
        assertEquals(1, ls.entries().size());

        ReadResult r = fs.read(rc, "./.skills-cache/user-1/database/demo/scripts/run.sh", 0, 0);
        assertTrue(r.isSuccess(), () -> "skill cache must not be namespaced, got: " + r.error());
        assertEquals("echo demo", r.fileData().content());
    }

    @Test
    void rooted_skillsCacheLsEntryPathsRoundTrip(@TempDir Path workspace) throws IOException {
        Path script = stageCachedScript(workspace);
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, USER_NS);
        RuntimeContext rc = RuntimeContext.builder().userId("user-1").build();

        // ls on the absolute files-root hands back workspace-relative entry paths; feeding one
        // back must reach the same directory instead of <workspace>/user-1/.skills-cache/...
        LsResult root = fs.ls(rc, script.getParent().getParent().toString());
        assertTrue(root.isSuccess(), () -> "ls should succeed, got: " + root.error());
        String scriptsEntry = root.entries().get(0).path();
        assertEquals(".skills-cache/user-1/database/demo/scripts/", scriptsEntry);

        LsResult scripts = fs.ls(rc, scriptsEntry);
        assertTrue(scripts.isSuccess(), () -> "entry path should round-trip: " + scripts.error());
        String scriptEntry = scripts.entries().get(0).path();
        ReadResult r = fs.read(rc, scriptEntry, 0, 0);
        assertTrue(r.isSuccess(), () -> "entry path should round-trip: " + r.error());
        assertEquals("echo demo", r.fileData().content());
    }

    @Test
    void rooted_otherSessionSkillsCacheStaysNamespaced(@TempDir Path workspace) throws IOException {
        for (String sid : List.of("sess-a", "sess-b")) {
            Path secret = workspace.resolve(".skills-cache/" + sid + "/database/x/secret.txt");
            Files.createDirectories(secret.getParent());
            Files.writeString(secret, sid + " only", StandardCharsets.UTF_8);
        }
        LocalFilesystem fs =
                new LocalFilesystem(
                        workspace,
                        LocalFsMode.ROOTED,
                        PathPolicy.empty(),
                        10,
                        IsolationScope.SESSION.toNamespaceFactory());
        RuntimeContext rc = RuntimeContext.builder().sessionId("sess-a").build();

        ReadResult own = fs.read(rc, ".skills-cache/sess-a/database/x/secret.txt", 0, 0);
        assertTrue(own.isSuccess(), () -> "own cache must stay reachable: " + own.error());
        assertEquals("sess-a only", own.fileData().content());

        for (String foreign :
                List.of(
                        ".skills-cache/sess-b/database/x/secret.txt",
                        "./.skills-cache/./sess-b/database/x/secret.txt",
                        ".skills-cache//sess-b/database/x/secret.txt")) {
            ReadResult r = fs.read(rc, foreign, 0, 0);
            assertFalse(r.isSuccess(), () -> "another session's cache leaked via " + foreign);
        }
        // A '..' hop out of the caller's own subtree keeps the namespace, which ROOTED rejects.
        assertThrows(
                IllegalArgumentException.class,
                () -> fs.read(rc, ".skills-cache/sess-a/../sess-b/database/x/secret.txt", 0, 0));

        LsResult cacheRoot = fs.ls(rc, ".skills-cache");
        if (cacheRoot.isSuccess()) {
            assertTrue(
                    cacheRoot.entries().stream().noneMatch(e -> e.path().contains("sess-b")),
                    () -> "cache root listing exposed another session: " + cacheRoot.entries());
        }
    }

    @Test
    void rooted_skillsCacheOwnScopeFollowsStagerSegment(@TempDir Path workspace)
            throws IOException {
        String uid = "alice@corp.com";
        String segment = MarketplaceStager.scopeSegment(uid);
        Path own = workspace.resolve(".skills-cache/" + segment + "/database/x/run.sh");
        Files.createDirectories(own.getParent());
        Files.writeString(own, "alice", StandardCharsets.UTF_8);
        // What a different identity spelled "alice_corp.com" would be staged under.
        Path lookalike = workspace.resolve(".skills-cache/alice_corp.com/database/x/run.sh");
        Files.createDirectories(lookalike.getParent());
        Files.writeString(lookalike, "someone else", StandardCharsets.UTF_8);
        LocalFilesystem fs =
                new LocalFilesystem(
                        workspace,
                        LocalFsMode.ROOTED,
                        PathPolicy.empty(),
                        10,
                        IsolationScope.USER.toNamespaceFactory());
        RuntimeContext rc = RuntimeContext.builder().userId(uid).build();

        ReadResult r = fs.read(rc, ".skills-cache/" + segment + "/database/x/run.sh", 0, 0);
        assertTrue(r.isSuccess(), () -> "own cache must stay reachable: " + r.error());
        assertEquals("alice", r.fileData().content());
        assertFalse(
                fs.read(rc, ".skills-cache/alice_corp.com/database/x/run.sh", 0, 0).isSuccess(),
                "only the stager's segment for this identity is exempt");
    }

    @Test
    void unrestricted_skillsCacheTraversalKeepsNamespace(@TempDir Path workspace)
            throws IOException {
        Path other = workspace.resolve("user-2/private.txt");
        Files.createDirectories(other.getParent());
        Files.writeString(other, "user-2 only", StandardCharsets.UTF_8);
        LocalFilesystem fs =
                new LocalFilesystem(
                        workspace, LocalFsMode.UNRESTRICTED, PathPolicy.empty(), 10, USER_NS);
        RuntimeContext rc = RuntimeContext.builder().userId("user-1").build();

        ReadResult r = fs.read(rc, ".skills-cache/../user-2/private.txt", 0, 0);
        assertFalse(r.isSuccess(), "a '..' segment must not escape through the cache exemption");
    }

    @Test
    void rooted_skillsCacheLookalikeStillNamespaced(@TempDir Path workspace) throws IOException {
        Path nsFile = workspace.resolve("user-1/.skills-cache-old/note.txt");
        Files.createDirectories(nsFile.getParent());
        Files.writeString(nsFile, "namespaced", StandardCharsets.UTF_8);
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, USER_NS);
        RuntimeContext rc = RuntimeContext.builder().userId("user-1").build();

        ReadResult r = fs.read(rc, ".skills-cache-old/note.txt", 0, 0);
        assertTrue(r.isSuccess(), () -> "lookalike should stay namespaced, got: " + r.error());
        assertEquals("namespaced", r.fileData().content());
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
        assertThrows(
                IllegalArgumentException.class,
                () -> fs.read(RuntimeContext.empty(), "/../etc/passwd", 0, 0));
    }

    // ==================== Bug reproduction: ls silently swallows errors ====================

    @Test
    void ls_nonExistentPath_shouldReturnFail(@TempDir Path workspace) {
        LocalFilesystem fs = new LocalFilesystem(workspace);
        LsResult r = fs.ls(RuntimeContext.empty(), "/this/path/does/not/exist");
        assertFalse(r.isSuccess(), "ls on non-existent path should fail");
    }

    @Test
    void ls_filePath_shouldReturnFail(@TempDir Path workspace) throws IOException {
        Path file = workspace.resolve("foo.txt");
        Files.writeString(file, "content");
        LocalFilesystem fs = new LocalFilesystem(workspace);
        LsResult r = fs.ls(RuntimeContext.empty(), file.toAbsolutePath().toString());
        assertFalse(r.isSuccess(), "ls on a file path should fail");
    }

    @Test
    void rooted_relativePathTraversalRejected(@TempDir Path base) throws IOException {
        Path workspace = base.resolve("workspace");
        Files.createDirectories(workspace);
        Path outsideFile = base.resolve("secret.txt");
        Files.writeString(outsideFile, "secret", StandardCharsets.UTF_8);
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);

        assertThrows(
                IllegalArgumentException.class,
                () -> fs.read(RuntimeContext.empty(), "../secret.txt", 0, 0));
    }

    @Test
    void rooted_relativeUploadTraversalRejected(@TempDir Path base) throws IOException {
        Path workspace = base.resolve("workspace");
        Files.createDirectories(workspace);
        Path escaped = base.resolve("escape.txt");
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);

        var responses =
                fs.uploadFiles(
                        RuntimeContext.empty(),
                        List.of(
                                Map.entry(
                                        "../escape.txt",
                                        "escape".getBytes(StandardCharsets.UTF_8))));

        assertEquals(1, responses.size());
        assertFalse(responses.get(0).isSuccess());
        assertEquals("permission_denied", responses.get(0).error());
        assertFalse(Files.exists(escaped));
    }

    @Test
    void rooted_relativeTraversalCannotEscapeUserNamespace(@TempDir Path workspace)
            throws IOException {
        Files.createDirectories(workspace.resolve("user-1"));
        Files.createDirectories(workspace.resolve("user-2"));
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, USER_NS);
        RuntimeContext rc = RuntimeContext.builder().userId("user-1").build();

        var responses =
                fs.uploadFiles(
                        rc,
                        List.of(
                                Map.entry(
                                        "../user-2/escape.txt",
                                        "escape".getBytes(StandardCharsets.UTF_8))));

        assertEquals(1, responses.size());
        assertFalse(responses.get(0).isSuccess());
        assertFalse(Files.exists(workspace.resolve("user-2/escape.txt")));
    }

    @Test
    void rooted_literalDoubleDotInRelativeSegmentAllowed(@TempDir Path workspace)
            throws IOException {
        Path file = workspace.resolve("some..dir/note.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "literal name", StandardCharsets.UTF_8);
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);

        ReadResult result = fs.read(RuntimeContext.empty(), "some..dir/note.txt", 0, 0);

        assertTrue(result.isSuccess());
        assertEquals("literal name", result.fileData().content());
    }
}
