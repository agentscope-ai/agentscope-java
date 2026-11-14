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
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The default workspace lives inside the project directory, so the overlay's lower layer walks
 * the workspace subtree when it enumerates the project. One caller's search must not see the
 * namespaces under it.
 */
class LocalFilesystemProjectEnumerationBoundaryTest {

    @TempDir Path project;

    private static RuntimeContext caller(String userId) {
        return RuntimeContext.builder().userId(userId).sessionId("s-" + userId).build();
    }

    @Test
    void projectLayer_doesNotEnumerateTheWorkspaceSubtree() throws Exception {
        Path workspace = project.resolve(".agentscope/workspace");
        Files.createDirectories(workspace);
        Files.writeString(project.resolve("project-note.txt"), "shared-project-note");
        AbstractFilesystem overlay =
                new LocalFilesystemSpec()
                        .project(project)
                        .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
        assertTrue(overlay.write(caller("alice"), "MEMORY.md", "alice-secret-memory").isSuccess());
        assertTrue(Files.isRegularFile(workspace.resolve("alice/MEMORY.md")));

        // The writable-project variant routes writes to projectFs, but enumeration still goes
        // through the same lower layer, so the boundary must hold for it as well.
        AbstractFilesystem projectAware =
                new LocalFilesystemSpec()
                        .project(project)
                        .projectWritable(true)
                        .toFilesystem(workspace, rc -> List.of(rc.getUserId()));

        for (AbstractFilesystem fs : new AbstractFilesystem[] {overlay, projectAware}) {
            RuntimeContext bob = caller("bob");
            // The grep pattern is the secret content itself, so an empty match list is the
            // strongest assertion and does not depend on how each layer spells its paths.
            assertTrue(
                    fs.grep(bob, "alice-secret-memory", "", null).matches().isEmpty(),
                    "grep('') read a sibling namespace through the project layer");
            assertTrue(
                    fs.grep(bob, "alice-secret-memory", ".", null).matches().isEmpty(),
                    "grep('.') read a sibling namespace through the project layer");
            assertTrue(
                    fs.grep(bob, "alice-secret-memory", ".agentscope/workspace", null)
                            .matches()
                            .isEmpty(),
                    "grep at an explicit workspace path read a sibling namespace");
            assertFalse(
                    fs.glob(bob, "**/*.md", "").matches().stream()
                            .anyMatch(f -> f.path().contains("alice")),
                    "glob('') listed a sibling namespace through the project layer");
            assertFalse(
                    fs.ls(bob, ".agentscope/workspace").entries().stream()
                            .anyMatch(e -> e.path().contains("alice")),
                    "ls at an explicit workspace path listed a sibling namespace");

            // Content operations must not reach the sibling namespace through the physical
            // layout either, while project files outside the workspace subtree stay shared.
            assertFalse(
                    fs.exists(bob, ".agentscope/workspace/alice/MEMORY.md"),
                    "exists() saw a sibling namespace through the project layer");
            assertFalse(
                    fs.read(bob, ".agentscope/workspace/alice/MEMORY.md", 0, 10).isSuccess(),
                    "read() reached a sibling namespace through the project layer");
            assertFalse(
                    fs.edit(bob, ".agentscope/workspace/alice/MEMORY.md", "alice", "bob", false)
                            .isSuccess(),
                    "edit() reached a sibling namespace through the project layer");
            assertFalse(
                    fs.downloadFiles(bob, List.of(".agentscope/workspace/alice/MEMORY.md"))
                            .get(0)
                            .isSuccess(),
                    "downloadFiles() reached a sibling namespace through the project layer");
            assertTrue(
                    fs.read(bob, "project-note.txt", 0, 10).isSuccess(),
                    "project files outside the workspace subtree must stay readable");
        }

        // A caller's own namespace stays reachable through the workspace layer.
        assertTrue(
                overlay.read(caller("alice"), "MEMORY.md", 0, 10).isSuccess(),
                "a caller must still read their own namespace through the workspace layer");
    }

    /**
     * Direct use of the lower layer: an excluded subtree is absent for delete and move, not
     * just for enumeration and the read-style operations.
     */
    @Test
    void excludedSubtree_staysAbsentForDeleteAndMove() throws Exception {
        Path workspace = project.resolve(".agentscope/workspace");
        Files.createDirectories(workspace.resolve("alice"));
        Files.writeString(workspace.resolve("alice/MEMORY.md"), "alice-secret-memory");
        Files.writeString(project.resolve("origin.txt"), "move-me");
        LocalFilesystem lower =
                new LocalFilesystem(project, true, 10, null).excludeSubtree(workspace);

        RuntimeContext bob = caller("bob");
        // delete treats the excluded subtree as absent: idempotent success, nothing removed.
        assertTrue(lower.delete(bob, ".agentscope/workspace").isSuccess());
        assertTrue(
                Files.isRegularFile(workspace.resolve("alice/MEMORY.md")),
                "delete() must not walk an excluded subtree");
        // move can neither move a file out of the subtree nor into it.
        assertFalse(
                lower.move(bob, ".agentscope/workspace/alice/MEMORY.md", "stolen.md").isSuccess(),
                "move() moved a file out of an excluded subtree");
        assertFalse(Files.exists(project.resolve("stolen.md")));
        assertFalse(
                lower.move(bob, "origin.txt", ".agentscope/workspace/alice/injected.md")
                        .isSuccess(),
                "move() moved a file into an excluded subtree");
        assertFalse(Files.exists(workspace.resolve("alice/injected.md")));
        // write() cannot land inside the subtree: the target does not exist yet, so the
        // assertion cannot pass merely because the file is already there.
        assertFalse(
                lower.write(bob, ".agentscope/workspace/alice/injected.md", "injected").isSuccess(),
                "write() reached an excluded subtree");
        assertFalse(
                Files.exists(workspace.resolve("alice/injected.md")),
                "write() created a file inside an excluded subtree");
        assertFalse(
                lower.uploadFiles(
                                bob,
                                List.of(
                                        Map.entry(
                                                ".agentscope/workspace/alice/MEMORY.md",
                                                "injected".getBytes())))
                        .get(0)
                        .isSuccess(),
                "uploadFiles() reached an excluded subtree");

        // Operations that start at an ancestor refuse outright rather than touch the hidden
        // subtree, symmetric for delete and move: nothing is partially destroyed.
        assertFalse(
                lower.delete(bob, ".").isSuccess(),
                "delete('.') partially deleted around the excluded subtree");
        assertTrue(
                Files.isRegularFile(project.resolve("origin.txt")),
                "delete('.') destroyed visible content around the excluded subtree");
        assertTrue(
                Files.isRegularFile(workspace.resolve("alice/MEMORY.md")),
                "delete('.') removed the excluded subtree");
        assertFalse(
                lower.move(bob, ".agentscope", "moved-agentscope").isSuccess(),
                "move() relocated a directory containing the excluded subtree");
        assertTrue(
                Files.isRegularFile(workspace.resolve("alice/MEMORY.md")),
                "move() carried the excluded subtree out of its hidden location");
    }

    /**
     * The ripgrep path of {@code grep} aligns the process working directory with the search
     * root: {@code --glob} patterns match against paths relative to the working directory,
     * so the excluded-subtree globs only bite when the two agree. Skipped when ripgrep is
     * not installed.
     */
    @Test
    void ripgrepExclusions_surviveAnUnrelatedProcessCwd() throws Exception {
        boolean rgAvailable;
        try {
            rgAvailable = new ProcessBuilder("rg", "--version").start().waitFor() == 0;
        } catch (IOException e) {
            rgAvailable = false;
        }
        Assumptions.assumeTrue(rgAvailable, "ripgrep is not installed");

        Path excluded = project.resolve("visible-excluded");
        Files.createDirectories(excluded);
        Files.writeString(excluded.resolve("secret.txt"), "rg-secret-content");
        Files.writeString(project.resolve("open.txt"), "rg-open-content");
        LocalFilesystem lower =
                new LocalFilesystem(project, true, 10, null).excludeSubtree(excluded);

        // This JVM's cwd (the module directory) is not an ancestor of @TempDir - exactly
        // the misaligned case. The directory is deliberately not hidden, so ripgrep's
        // default hidden-file skip cannot mask the result.
        GrepResult leaked = lower.grep(caller("bob"), "rg-secret-content", "", null);
        assertTrue(leaked.isSuccess());
        assertTrue(leaked.matches().isEmpty(), "grep() leaked excluded content through ripgrep");

        // Sanity: the non-excluded content is still found.
        GrepResult kept = lower.grep(caller("bob"), "rg-open-content", "", null);
        assertTrue(kept.isSuccess());
        assertEquals(1, kept.matches().size(), "grep() lost the non-excluded content");
    }

    /**
     * A workspace directory whose name contains glob metacharacters must not widen the
     * exclusion: ripgrep reads {@code work[1]} as a character class, so the pattern has to be
     * escaped. Skipped when ripgrep is not installed.
     */
    @Test
    void ripgrepExclusion_escapesGlobMetacharactersInTheWorkspacePath() throws Exception {
        boolean rgAvailable;
        try {
            rgAvailable = new ProcessBuilder("rg", "--version").start().waitFor() == 0;
        } catch (IOException e) {
            rgAvailable = false;
        }
        Assumptions.assumeTrue(rgAvailable, "ripgrep is not installed");

        // Not dot-prefixed: ripgrep's default hidden-file skip would otherwise mask the
        // result, and the escaped pattern must hold on its own.
        Path workspace = project.resolve("work[1]");
        Files.createDirectories(workspace.resolve("alice"));
        Files.writeString(workspace.resolve("alice/MEMORY.md"), "alice-bracket-secret");
        LocalFilesystem lower =
                new LocalFilesystem(project, true, 10, null).excludeSubtree(workspace);

        GrepResult leaked = lower.grep(caller("bob"), "alice-bracket-secret", "", null);
        assertTrue(leaked.isSuccess());
        assertTrue(
                leaked.matches().isEmpty(),
                "a '[' in the excluded path defeated the ripgrep exclusion: " + leaked.matches());
    }

    /** A link in the shared project must not carry a sibling's file past the boundary. */
    @Test
    void symlinkIntoTheHiddenSubtree_staysHidden() throws Exception {
        Path workspace = project.resolve(".agentscope/workspace");
        Files.createDirectories(workspace.resolve("alice"));
        Files.writeString(workspace.resolve("alice/MEMORY.md"), "alice-linked-secret");
        Path link = project.resolve("link");
        try {
            Files.createSymbolicLink(link, workspace.resolve("alice"));
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.abort("symbolic links not supported here: " + unsupported.getMessage());
        }
        LocalFilesystem lower =
                new LocalFilesystem(project, true, 10, null).excludeSubtree(workspace);

        assertFalse(
                lower.read(caller("bob"), "link/MEMORY.md", 0, 10).isSuccess(),
                "read() followed a symlink into the excluded subtree");
        assertTrue(
                lower.ls(caller("bob"), "link").entries().isEmpty(),
                "ls() listed a symlinked excluded subtree");
        assertTrue(
                lower.ls(caller("bob"), ".").entries().stream()
                        .noneMatch(e -> e.path().contains("link")),
                "ls() of the parent listed the link entry itself");
        assertFalse(
                lower.write(caller("bob"), "link/MEMORY2.md", "x").isSuccess(),
                "write() created a file inside a symlinked excluded subtree");

        // The link entry itself stays deletable: unlinking never reaches the target.
        assertTrue(lower.delete(caller("bob"), "link").isSuccess());
        assertFalse(
                Files.exists(link, LinkOption.NOFOLLOW_LINKS),
                "delete() left the link entry behind");
        assertTrue(
                Files.isRegularFile(workspace.resolve("alice/MEMORY.md")),
                "the hidden file must be untouched");
    }

    /** A caller whose namespace directory does not exist yet has an empty root, not an error. */
    @Test
    void blankRootOfAnAbsentNamespace_listsAsEmpty() {
        LocalFilesystem fs = new LocalFilesystem(project, true, 10, rc -> List.of(rc.getUserId()));

        LsResult listed = fs.ls(caller("carol"), "");

        assertTrue(listed.isSuccess(), "ls('') failed for a caller with no directory yet");
        assertTrue(listed.entries().isEmpty(), "ls('') returned entries: " + listed.entries());
    }

    /** A shared workspace (no namespace factory) is not excluded from the project layer. */
    @Test
    void sharedWorkspace_staysEnumerableFromTheProjectLayer() throws Exception {
        Path workspace = project.resolve(".agentscope/workspace");
        Files.createDirectories(workspace);
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(project)
                        .sharedLocalWorkspace(true)
                        .toFilesystem(workspace, null);
        assertTrue(fs.write(caller("alice"), "MEMORY.md", "shared-note").isSuccess());

        // The upper layer resolves this path against the workspace (a miss); the hit comes
        // from the project layer, which must still walk the shared workspace subtree.
        assertFalse(
                fs.grep(caller("alice"), "shared-note", ".agentscope/workspace", null)
                        .matches()
                        .isEmpty(),
                "a shared workspace must stay enumerable through the project layer");
    }
}
