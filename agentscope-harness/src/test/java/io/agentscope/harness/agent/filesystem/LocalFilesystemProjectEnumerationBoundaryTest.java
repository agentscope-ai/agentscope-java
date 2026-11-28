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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
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
     * A relative key reaches the project through a link as well, and the project layer resolves
     * it that way. Walking there must skip the workspace exactly like the absolute spelling.
     */
    @Test
    void relativeKeyThroughAProjectAlias_cannotReachTheNamespaces() throws Exception {
        Path workspace = project.resolve("box/workspace");
        Files.createDirectories(workspace.resolve("alice"));
        Files.writeString(workspace.resolve("alice/MEMORY.md"), "alice-secret-memory");
        try {
            Files.createSymbolicLink(project.resolve("alias"), project);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.abort("symbolic links not supported here: " + unsupported.getMessage());
        }
        RuntimeContext bob = caller("bob");
        for (boolean projectWritable : new boolean[] {false, true}) {
            AbstractFilesystem fs =
                    new LocalFilesystemSpec()
                            .project(project)
                            .projectWritable(projectWritable)
                            .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
            assertTrue(
                    fs.ls(bob, "alias/box").entries().isEmpty(),
                    "ls() listed the workspace entry through a relative alias");
            assertFalse(
                    fs.glob(bob, "**/*.md", "alias/box").matches().stream()
                            .anyMatch(f -> f.path().contains("alice")),
                    "glob() listed a sibling namespace through a relative alias");
            assertTrue(
                    fs.grep(bob, "alice-secret-memory", "alias/box", null).matches().isEmpty(),
                    "grep() read a sibling namespace through a relative alias");
        }
    }

    /**
     * Enumeration that starts at the shared project root, in its plain spelling, meets the
     * workspace subtree at any depth: listing, glob and grep must skip it while the project's
     * own files stay searchable.
     */
    @Test
    void projectRootEnumeration_skipsTheWorkspaceSubtree() throws Exception {
        Path projectRoot = project.resolve("p");
        Path workspace = projectRoot.resolve("box/workspace");
        Files.createDirectories(workspace.resolve("alice"));
        Files.writeString(workspace.resolve("alice/MEMORY.md"), "alice-secret-memory");
        Files.writeString(projectRoot.resolve("note.md"), "project-note");
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(projectRoot)
                        .projectWritable(true)
                        .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
        RuntimeContext bob = caller("bob");
        String root = projectRoot.toString();

        assertTrue(
                fs.grep(bob, "alice-secret-memory", root, null).matches().isEmpty(),
                "grep() from the shared root read a sibling namespace");
        assertFalse(
                fs.glob(bob, "**/*.md", root).matches().stream()
                        .anyMatch(f -> f.path().contains("workspace")),
                "glob() from the shared root listed the workspace subtree");
        assertFalse(
                fs.ls(bob, root).entries().stream().anyMatch(e -> e.path().contains("workspace")),
                "ls() from the shared root listed the workspace entry");
        assertEquals(
                1,
                fs.grep(bob, "project-note", root, null).matches().size(),
                "the shared project itself must stay searchable");
    }

    /**
     * A search started above the workspace stays closed even when the process directory and the
     * search root are spelled differently -- on macOS a temp directory under {@code /var} reports
     * its physical {@code /private/var} form, which no longer relativizes against the exclusion.
     */
    @Test
    void aliasedAncestor_grepStaysClosed() throws Exception {
        Path real = project.resolve("real");
        Path projectRoot = real.resolve("p");
        Path workspace = projectRoot.resolve("box/workspace");
        Files.createDirectories(workspace.resolve("alice"));
        Files.writeString(workspace.resolve("alice/MEMORY.md"), "alice-secret-memory");
        Path link = project.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.abort("symbolic links not supported here: " + unsupported.getMessage());
        }
        Path spelledProject = link.resolve("p");
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(spelledProject)
                        .toFilesystem(
                                spelledProject.resolve("box/workspace"),
                                rc -> List.of(rc.getUserId()));

        assertTrue(
                fs.grep(
                                caller("bob"),
                                "alice-secret-memory",
                                spelledProject.resolve("box").toString(),
                                null)
                        .matches()
                        .isEmpty(),
                "grep() read a sibling namespace through an aliased ancestor");
        assertFalse(
                fs.grep(
                                caller("alice"),
                                "alice-secret-memory",
                                spelledProject.resolve("box/workspace/alice").toString(),
                                null)
                        .matches()
                        .isEmpty(),
                "the owner lost their own subtree to the filter");
    }

    /**
     * An absolute path above the workspace lies outside the workspace root but inside the
     * project, so the workspace layer resolves it. Recursive reads and writes there would walk
     * into the namespaces, while the project layer can answer around them.
     */
    @Test
    void absoluteAncestor_cannotBeScannedOrDeleted() throws Exception {
        RuntimeContext bob = caller("bob");
        for (boolean projectWritable : new boolean[] {false, true}) {
            Path projectRoot = project.resolve(projectWritable ? "writable" : "plain");
            Path workspace = projectRoot.resolve("box/workspace");
            Files.createDirectories(workspace.resolve("alice"));
            Files.writeString(workspace.resolve("alice/MEMORY.md"), "alice-secret-memory");
            Files.writeString(projectRoot.resolve("note.md"), "project-note");
            AbstractFilesystem fs =
                    new LocalFilesystemSpec()
                            .project(projectRoot)
                            .projectWritable(projectWritable)
                            .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
            String ancestor = projectRoot.resolve("box").toString();

            assertTrue(
                    fs.grep(bob, "alice-secret-memory", ancestor, null).matches().isEmpty(),
                    "grep() scanned a sibling namespace through an absolute ancestor");
            assertFalse(
                    fs.glob(bob, "**/*.md", ancestor).matches().stream()
                            .anyMatch(f -> f.path().contains("alice")),
                    "glob() listed a sibling namespace through an absolute ancestor");
            assertFalse(
                    fs.ls(bob, ancestor).entries().stream()
                            .anyMatch(e -> e.path().contains("workspace")),
                    "ls() listed the workspace entry through an absolute ancestor");
            assertFalse(
                    fs.delete(bob, ancestor).isSuccess(),
                    "delete() recursively deleted a namespace through an absolute ancestor");
            assertFalse(
                    fs.move(bob, ancestor, projectRoot.resolve("moved").toString()).isSuccess(),
                    "move() carried a namespace out through an absolute ancestor");
            assertTrue(
                    Files.isRegularFile(workspace.resolve("alice/MEMORY.md")),
                    "a sibling namespace file is gone");
            assertTrue(Files.isRegularFile(projectRoot.resolve("note.md")));
            // A project-wide scan still answers from the project layer, minus the workspace.
            assertEquals(
                    1,
                    fs.grep(bob, "project-note", projectRoot.toString(), null).matches().size(),
                    "grep() lost the project files around the hidden subtree");

            // The same ancestor spelled through a link must scan no further.
            Path alias = projectRoot.resolve("alias");
            Files.createSymbolicLink(alias, projectRoot);
            String aliasedAncestor = alias.resolve("box").toString();
            assertTrue(
                    fs.grep(bob, "alice-secret-memory", aliasedAncestor, null).matches().isEmpty(),
                    "grep() scanned a sibling namespace through an aliased ancestor");
            assertFalse(
                    fs.glob(bob, "**/*.md", aliasedAncestor).matches().stream()
                            .anyMatch(f -> f.path().contains("alice")),
                    "glob() listed a sibling namespace through an aliased ancestor");
            assertFalse(
                    fs.ls(bob, aliasedAncestor).entries().stream()
                            .anyMatch(e -> e.path().contains("workspace")),
                    "ls() listed the workspace entry through an aliased ancestor");
        }
    }

    /**
     * The guard closes the way in from above only. The layer that owns the workspace still walks
     * inside it, so a caller keeps finding their own files while a sibling finds nothing.
     */
    @Test
    void ownerLayer_stillWalksInsideTheGuardedSubtree() throws Exception {
        for (boolean projectWritable : new boolean[] {false, true}) {
            Path projectRoot = project.resolve(projectWritable ? "writable" : "plain");
            Path workspace = projectRoot.resolve("box/workspace");
            Files.createDirectories(workspace.resolve("alice"));
            Files.writeString(workspace.resolve("alice/MEMORY.md"), "alice-secret-memory");
            AbstractFilesystem fs =
                    new LocalFilesystemSpec()
                            .project(projectRoot)
                            .projectWritable(projectWritable)
                            .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
            RuntimeContext alice = caller("alice");

            assertFalse(
                    fs.grep(alice, "alice-secret-memory", "", null).matches().isEmpty(),
                    "the owner layer lost the subtree it is supposed to read");
            assertFalse(
                    fs.grep(
                                    alice,
                                    "alice-secret-memory",
                                    workspace.resolve("alice/MEMORY.md").toString(),
                                    null)
                            .matches()
                            .isEmpty(),
                    "the owner lost the subtree when it was spelled from inside");
            assertFalse(
                    fs.glob(alice, "**/*.md", "").matches().isEmpty(),
                    "the owner layer enumerated nothing inside the guarded subtree");
            assertFalse(
                    fs.ls(alice, "").entries().isEmpty(),
                    "ls() of the owner's own root came back empty");
            assertTrue(
                    fs.grep(caller("bob"), "alice-secret-memory", "", null).matches().isEmpty(),
                    "a sibling read the guarded subtree through the owner layer");
        }
    }

    /**
     * An alias spells the ancestor of the hidden subtree differently. Comparing normalized
     * spellings alone would let delete and move walk through it into the namespaces.
     */
    @Test
    void aliasedAncestor_cannotDeleteOrMoveTheHiddenSubtree() throws Exception {
        Path workspace = project.resolve("box/workspace");
        Files.createDirectories(workspace.resolve("alice"));
        Files.writeString(workspace.resolve("alice/MEMORY.md"), "alice-secret-memory");
        Path alias = project.resolve("alias");
        try {
            Files.createSymbolicLink(alias, project);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.abort("symbolic links not supported here: " + unsupported.getMessage());
        }
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(project)
                        .projectWritable(true)
                        .toFilesystem(workspace, rc -> List.of(rc.getUserId()));

        RuntimeContext bob = caller("bob");
        String aliasedAncestor = alias.resolve("box").toString();
        assertFalse(
                fs.delete(bob, aliasedAncestor).isSuccess(),
                "delete() walked into the namespaces through an aliased ancestor");
        assertTrue(
                Files.isRegularFile(workspace.resolve("alice/MEMORY.md")),
                "delete() removed a sibling namespace through an aliased ancestor");
        assertFalse(
                fs.move(bob, aliasedAncestor, project.resolve("moved").toString()).isSuccess(),
                "move() carried the namespaces out through an aliased ancestor");
        assertTrue(
                Files.isRegularFile(workspace.resolve("alice/MEMORY.md")),
                "move() relocated a sibling namespace through an aliased ancestor");
        assertFalse(Files.exists(project.resolve("moved")));
        // The alias entry itself stays deletable: unlinking it reaches no target.
        assertTrue(fs.delete(bob, alias.toString()).isSuccess());
        assertTrue(
                Files.isRegularFile(workspace.resolve("alice/MEMORY.md")),
                "unlinking the alias touched the namespaces");
    }

    /**
     * The same alias also spells a file that is physically inside the workspace from outside
     * it. The boundary is about where a path lands, so the aliased spelling must reach no
     * further than the plain one: read-style calls come back empty and edits do not land.
     */
    @Test
    void aliasedAbsolutePath_cannotReachAnotherNamespace() throws Exception {
        Path workspace = project.resolve("box/workspace");
        Files.createDirectories(workspace.resolve("alice"));
        Path memory = workspace.resolve("alice/MEMORY.md");
        Files.writeString(memory, "alice-secret-memory");
        Path alias = project.resolve("alias");
        try {
            Files.createSymbolicLink(alias, project);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.abort("symbolic links not supported here: " + unsupported.getMessage());
        }
        Path innerLink = workspace.resolve("alice/inner");
        Files.createSymbolicLink(innerLink, memory);
        String aliasedFile = alias.resolve("box/workspace/alice/MEMORY.md").toString();
        String aliasedLink = alias.resolve("box/workspace/alice/inner").toString();
        String aliasedDir = alias.resolve("box/workspace/alice").toString();
        String stolen = workspace.resolve("bob/stolen.md").toString();
        RuntimeContext bob = caller("bob");

        for (boolean projectWritable : new boolean[] {false, true}) {
            AbstractFilesystem fs =
                    new LocalFilesystemSpec()
                            .project(project)
                            .projectWritable(projectWritable)
                            .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
            assertFalse(
                    fs.read(bob, aliasedFile, 0, 100).isSuccess(),
                    "read() followed an alias into a sibling namespace");
            assertFalse(
                    fs.exists(bob, aliasedFile),
                    "exists() followed an alias into a sibling namespace");
            assertFalse(
                    fs.downloadFiles(bob, List.of(aliasedFile)).get(0).isSuccess(),
                    "downloadFiles() followed an alias into a sibling namespace");
            assertTrue(
                    fs.grep(bob, "alice-secret-memory", aliasedDir, null).matches().isEmpty(),
                    "grep() followed an alias into a sibling namespace");
            assertFalse(
                    fs.edit(bob, aliasedFile, "alice-secret-memory", "pwned", false).isSuccess(),
                    "edit() rewrote a sibling namespace through an alias");
            assertEquals("alice-secret-memory", Files.readString(memory));
            assertFalse(
                    fs.move(bob, aliasedFile, stolen).isSuccess(),
                    "move() carried a sibling namespace file out through an alias");
            assertTrue(Files.isRegularFile(memory));
            assertFalse(Files.exists(workspace.resolve("bob/stolen.md")));
            assertFalse(
                    fs.move(bob, aliasedLink, project.resolve("stolen/inner").toString())
                            .isSuccess(),
                    "move() took a link entry out of a sibling namespace");
            assertTrue(
                    Files.exists(innerLink, LinkOption.NOFOLLOW_LINKS),
                    "a sibling namespace link entry is gone");
        }
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

    /**
     * An absolute path inside the caller's own namespace is the same file as its relative
     * spelling. It used to fall through to the project view, which hides the workspace, so the
     * owner lost their own subtree whenever a tool reported an absolute path back. Only the
     * project-aware overlay carries the workspace metadata this needs. A subdirectory that is not
     * workspace metadata keeps the overlay's existing routing: absolute spellings of project
     * content stay project paths.
     */
    @Test
    void absolutePathInOwnNamespace_addressesTheSameFilesAsTheRelativeSpelling() throws Exception {
        Path projectRoot = project.resolve("p");
        Path workspace = projectRoot.resolve("box/workspace");
        Files.createDirectories(workspace.resolve("alice/notes"));
        Files.writeString(workspace.resolve("alice/MEMORY.md"), "alice-secret-memory");
        Files.writeString(workspace.resolve("alice/notes/todo.md"), "alice-secret-memory");
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(projectRoot)
                        .projectWritable(true)
                        .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
        RuntimeContext alice = caller("alice");

        assertFalse(
                fs.grep(alice, "alice-secret-memory", workspace.toString(), null)
                        .matches()
                        .isEmpty(),
                "grep() at the absolute workspace root missed the caller's own files");
        assertFalse(
                fs.grep(alice, "alice-secret-memory", workspace.resolve("alice").toString(), null)
                        .matches()
                        .isEmpty(),
                "grep() at the absolute namespace root missed the caller's own files");
        assertFalse(
                fs.glob(alice, "**/*.md", workspace.resolve("alice").toString())
                        .matches()
                        .isEmpty(),
                "glob() at the absolute namespace root listed nothing");
        assertTrue(
                fs.read(alice, workspace.resolve("alice/MEMORY.md").toString(), 0, 10).isSuccess(),
                "read() at the absolute file path failed");
        assertFalse(
                fs.ls(alice, workspace.resolve("alice").toString()).entries().isEmpty(),
                "ls() at the absolute namespace root listed nothing");

        // A sibling spelling the same absolute paths gets the hidden subtree's answer: an empty
        // result rather than a namespace error, which is what the workspace view already returns
        // for the subtree itself.
        RuntimeContext bob = caller("bob");
        assertTrue(
                fs.grep(bob, "alice-secret-memory", workspace.resolve("alice").toString(), null)
                        .matches()
                        .isEmpty(),
                "grep() read a sibling namespace through an absolute path");
        LsResult siblingListed = fs.ls(bob, workspace.resolve("alice").toString());
        assertTrue(
                siblingListed.entries() == null || siblingListed.entries().isEmpty(),
                "ls() listed a sibling namespace through an absolute path");
        assertFalse(
                fs.read(bob, workspace.resolve("alice/MEMORY.md").toString(), 0, 10).isSuccess(),
                "read() reached a sibling namespace through an absolute path");
    }

    /**
     * A segment that names another caller's namespace is still a key of the caller's own view:
     * the write lands under their own project namespace and never in the sibling's directory.
     * It must also be readable at that key, or the routing would report a success that no read
     * can observe.
     */
    @Test
    void absoluteSpellingOfASiblingSegment_staysInTheCallersOwnKeySpace() throws Exception {
        Path projectRoot = project.resolve("p");
        Path workspace = projectRoot.resolve("box/workspace");
        Files.createDirectories(workspace.resolve("alice"));
        Files.createDirectories(workspace.resolve("bob"));
        Files.writeString(workspace.resolve("bob/MEMORY.md"), "bob-secret-memory");
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(projectRoot)
                        .projectWritable(true)
                        .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
        RuntimeContext alice = caller("alice");

        String siblingSpelling = workspace.resolve("bob/notes.md").toString();
        assertTrue(
                fs.write(alice, siblingSpelling, "alice-note").isSuccess(),
                "write() at an absolute workspace spelling failed");
        ReadResult readBack = fs.read(alice, "bob/notes.md", 0, 100);
        assertTrue(
                readBack.isSuccess(),
                () -> "the write is unreadable at the key it used: " + readBack.error());
        assertFalse(
                Files.exists(workspace.resolve("bob/notes.md")),
                "write() landed in a sibling namespace");
        assertFalse(
                fs.exists(caller("bob"), "notes.md"),
                "a sibling can read the file through their own key");
        assertTrue(
                Files.isRegularFile(workspace.resolve("bob/MEMORY.md")),
                "the sibling namespace file is gone");
    }

    /**
     * A caller whose namespace list is empty -- the AGENT and GLOBAL isolation scopes, or a
     * context without a user id -- works at the workspace root itself. The workspace layer refuses
     * walks into its own root from above, and that refusal must not close the root for the layer
     * that owns it, or listing finds nothing while reading the same key still works.
     */
    @Test
    void emptyNamespace_keepsTheWorkspaceEnumerable() throws Exception {
        Path workspace = project.resolve(".agentscope/workspace");
        Files.createDirectories(workspace);
        AbstractFilesystem fs =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, rc -> List.of());
        RuntimeContext shared = RuntimeContext.empty();

        assertTrue(fs.write(shared, "notes.md", "own-note").isSuccess());
        LsResult listed = fs.ls(shared, "");
        assertTrue(listed.isSuccess(), listed::error);
        assertTrue(
                listed.entries().stream().anyMatch(entry -> entry.path().endsWith("notes.md")),
                () -> "ls() hid the caller's own file: " + listed.entries());
        assertTrue(fs.read(shared, "notes.md", 0, 100).isSuccess());
    }

    /**
     * A dot segment is part of the spelling, not part of the key: {@code ./MEMORY.md} names
     * workspace metadata exactly like {@code MEMORY.md}. It used to classify as workspace content
     * and route to the project layer, where the caller's own copy stayed readable by others.
     */
    @Test
    void dotPrefix_onWorkspaceMetadata_staysWorkspaceContent() throws Exception {
        Path workspace = project.resolve("box/workspace");
        Files.createDirectories(workspace.resolve("alice"));
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(project)
                        .projectWritable(true)
                        .toFilesystem(workspace, rc -> List.of(rc.getUserId()));

        assertTrue(fs.write(caller("alice"), "./MEMORY.md", "via-dot-slash").isSuccess());
        assertTrue(
                Files.isRegularFile(workspace.resolve("alice/MEMORY.md")),
                "the dot prefix routed workspace metadata out of the workspace");
        assertFalse(Files.exists(project.resolve("alice/MEMORY.md")));
        assertEquals(
                "via-dot-slash",
                fs.read(caller("alice"), "MEMORY.md", 0, 100).fileData().content());
    }

    /**
     * A directory link inside the caller's own tree that lands in a sibling's namespace is closed
     * to enumeration, not only to reads. A junction on Windows is not a symbolic link, so the link
     * flag alone does not catch it; the position of the entry is what does.
     */
    @Test
    void directoryAliasIntoASiblingNamespace_staysUnlisted() throws Exception {
        Path workspace = project.resolve("box/workspace");
        Path alice = workspace.resolve("alice");
        Path bob = workspace.resolve("bob");
        Files.createDirectories(alice);
        Files.createDirectories(bob);
        Files.writeString(bob.resolve("SECRET.md"), "bob-private-content");
        Path link = alice.resolve("j");
        if (!linkTo(link, bob)) {
            Assumptions.abort("directory links are not available here");
        }

        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(project)
                        .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
        RuntimeContext aliceCtx = caller("alice");
        assertTrue(
                fs.glob(aliceCtx, "**/*.md", "").matches().isEmpty(),
                "glob() listed a sibling namespace through a directory link");
        assertTrue(fs.grep(aliceCtx, "bob-private-content", "", null).matches().isEmpty());
        LsResult listed = fs.ls(aliceCtx, "j");
        assertTrue(
                listed.entries() == null || listed.entries().isEmpty(),
                () -> "ls() listed a sibling namespace through a directory link");
        assertFalse(fs.read(aliceCtx, "j/SECRET.md", 0, 100).isSuccess());
    }

    /**
     * Creates a directory link: a junction on Windows, because that is the spelling the link flag
     * does not report, and a symbolic link elsewhere.
     */
    private static boolean linkTo(Path link, Path target) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
            try {
                Files.createSymbolicLink(link, target);
                return true;
            } catch (IOException | UnsupportedOperationException symbolic) {
                return false;
            }
        }
        try {
            Process junction =
                    new ProcessBuilder(
                                    "cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                            .redirectErrorStream(true)
                            .start();
            junction.getInputStream().readAllBytes();
            if (junction.waitFor() == 0 && Files.exists(link)) {
                return true;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException symbolic) {
            return false;
        }
    }

    /**
     * Moving a link moves its entry: the subtree it points at never travels with it, so a link
     * that happens to point at an ancestor of the guarded root is still movable, as it is
     * deletable. Reading the directory flag through the link made the two disagree.
     */
    @Test
    void movingALinkToAnAncestor_movesTheEntryOnly() throws Exception {
        Path workspace = project.resolve("ws");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("MEMORY.md"), "alice-memory");
        Path up = project.resolve("up");
        try {
            Files.createSymbolicLink(up, project);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.abort("symbolic links not supported here: " + unsupported.getMessage());
        }
        LocalFilesystem fs = new LocalFilesystem(project, true, 10, null).excludeSubtree(workspace);

        assertTrue(
                fs.move(caller("alice"), "up", "up2").isSuccess(),
                "a link entry carries nothing, so moving it cannot move the guarded subtree");
        assertTrue(Files.isSymbolicLink(project.resolve("up2")));
        assertTrue(Files.isRegularFile(workspace.resolve("MEMORY.md")));
    }

    /**
     * The workspace may be configured through an alias, such as a symlinked deployment path. The
     * caller's own root is then spelled differently from the physical path the project layer
     * registered, and it must still count as the root itself rather than as a walk from above it.
     */
    @Test
    void workspaceThroughAnAlias_stillEnumerates() throws Exception {
        Path workspace = project.resolve("ws");
        Files.createDirectories(workspace.resolve("alice"));
        Files.writeString(workspace.resolve("alice/MEMORY.md"), "own-note");
        Path alias = project.resolve("current");
        try {
            Files.createSymbolicLink(alias, workspace);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.abort("symbolic links not supported here: " + unsupported.getMessage());
        }
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(project)
                        .toFilesystem(alias, rc -> List.of(rc.getUserId()));
        RuntimeContext alice = caller("alice");

        assertTrue(fs.read(alice, "MEMORY.md", 0, 100).isSuccess());
        assertTrue(
                fs.ls(alice, "").entries().stream()
                        .anyMatch(entry -> entry.path().endsWith("MEMORY.md")),
                () -> "ls() hid the caller's own file: " + fs.ls(alice, "").entries());
    }

    /**
     * A sibling's absolute path is absent for the caller in both overlay variants, whichever way
     * it is spelled. The view merges its layers, so a layer that refuses the path contributes
     * nothing; letting that refusal escape made one spelling list as an exception while the same
     * location read as not found.
     */
    @Test
    void siblingAbsolutePath_answersEmptyInBothOverlays() throws Exception {
        Path projectRoot = project.resolve("p");
        Path workspace = projectRoot.resolve("box/workspace");
        Files.createDirectories(workspace.resolve("alice"));
        Files.writeString(workspace.resolve("alice/MEMORY.md"), "alice-secret-memory");
        Path alias = projectRoot.resolve("alias");
        try {
            Files.createSymbolicLink(alias, projectRoot);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.abort("symbolic links not supported here: " + unsupported.getMessage());
        }
        RuntimeContext bob = caller("bob");

        for (boolean projectWritable : new boolean[] {false, true}) {
            AbstractFilesystem fs =
                    new LocalFilesystemSpec()
                            .project(projectRoot)
                            .projectWritable(projectWritable)
                            .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
            for (String spelling :
                    new String[] {
                        workspace.resolve("alice").toString(),
                        alias.resolve("box/workspace/alice").toString()
                    }) {
                LsResult listed =
                        assertDoesNotThrow(
                                () -> fs.ls(bob, spelling), () -> "ls() threw at " + spelling);
                assertTrue(
                        listed.entries() == null || listed.entries().isEmpty(),
                        () -> "ls() listed " + spelling);
                assertTrue(
                        assertDoesNotThrow(
                                        () -> fs.glob(bob, "**/*.md", spelling),
                                        () -> "glob() threw at " + spelling)
                                .matches()
                                .isEmpty(),
                        () -> "glob() listed " + spelling);
                assertTrue(
                        assertDoesNotThrow(
                                        () -> fs.grep(bob, "alice-secret-memory", spelling, null),
                                        () -> "grep() threw at " + spelling)
                                .matches()
                                .isEmpty(),
                        () -> "grep() read " + spelling);
                assertFalse(
                        fs.read(bob, spelling + "/MEMORY.md", 0, 100).isSuccess(),
                        () -> "read() reached " + spelling);
            }
        }

        // The owner still sees the same directory under the same spelling.
        AbstractFilesystem plain =
                new LocalFilesystemSpec()
                        .project(projectRoot)
                        .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
        assertFalse(
                plain.ls(caller("alice"), workspace.resolve("alice").toString())
                        .entries()
                        .isEmpty(),
                "the owner lost their own directory");
    }

    /**
     * A path that only reaches the guarded root through an alias is closed at any depth, and it
     * answers like the root entry does: empty, not a namespace error, in both overlay variants.
     */
    @Test
    void aliasDeeperThanTheGuardedRoot_isClosedLikeTheRootItself() throws Exception {
        Path projectRoot = project.resolve("p");
        Path workspace = projectRoot.resolve("box/workspace");
        Files.createDirectories(workspace.resolve("alice"));
        Files.writeString(workspace.resolve("alice/MEMORY.md"), "alice-secret-memory");
        Path alias = projectRoot.resolve("alias");
        try {
            Files.createSymbolicLink(alias, projectRoot);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.abort("symbolic links not supported here: " + unsupported.getMessage());
        }
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(projectRoot)
                        .projectWritable(true)
                        .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
        RuntimeContext bob = caller("bob");
        String hidden = alias.resolve("box/workspace").toString();
        String namespace = alias.resolve("box/workspace/alice").toString();
        String file = alias.resolve("box/workspace/alice/MEMORY.md").toString();

        assertTrue(
                fs.ls(bob, hidden).entries().isEmpty(),
                "ls() listed the guarded root through a deeper alias");
        assertTrue(
                fs.ls(bob, namespace).entries().isEmpty(),
                "ls() entered the guarded root below its first level");
        assertTrue(
                fs.glob(bob, "**/*.md", hidden).matches().isEmpty(),
                "glob() entered the guarded root below its first level");
        assertTrue(
                fs.grep(bob, "alice-secret-memory", hidden, null).matches().isEmpty(),
                "grep() read the guarded root below its first level");
        assertFalse(fs.read(bob, file, 0, 100).isSuccess(), "read() reached the guarded root");
        assertFalse(fs.exists(bob, file), "exists() saw the guarded root");
    }

    /**
     * The refused bound closes the way in from above at any depth, not just at the root entry.
     * The owner of the subtree keeps reading inside it.
     */
    @Test
    void refusedRoot_isClosedAtAnyDepthButReadableFromInside() throws Exception {
        Path projectRoot = project.resolve("p");
        Path workspace = projectRoot.resolve("box/workspace");
        Files.createDirectories(workspace.resolve("alice/notes"));
        Files.writeString(workspace.resolve("alice/notes/todo.md"), "alice-secret-memory");
        Path alias = projectRoot.resolve("alias");
        try {
            Files.createSymbolicLink(alias, projectRoot);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.abort("symbolic links not supported here: " + unsupported.getMessage());
        }
        LocalFilesystem fs =
                new LocalFilesystem(
                                projectRoot,
                                LocalFsMode.ROOTED,
                                PathPolicy.of(projectRoot),
                                10,
                                null)
                        .refuseTraversal(workspace);
        RuntimeContext rc = caller("alice");

        assertTrue(
                fs.ls(rc, alias.resolve("box/workspace").toString()).entries().isEmpty(),
                "ls() entered the refused root through an alias");
        assertTrue(
                fs.ls(rc, alias.resolve("box/workspace/alice").toString()).entries().isEmpty(),
                "ls() entered the refused root below its first level");
        assertTrue(
                fs.grep(rc, "alice-secret-memory", alias.resolve("box/workspace").toString(), null)
                        .matches()
                        .isEmpty(),
                "grep() entered the refused root below its first level");
        assertFalse(
                fs.ls(rc, workspace.resolve("alice").toString()).entries().isEmpty(),
                "the refused bound also hid the subtree from its owner");
    }

    /**
     * A link in the shared project that points into the caller's own namespace stays readable for
     * its owner: the guard protects a caller from a sibling, not from themselves. A sibling
     * spelling the same link still gets the hidden answer.
     */
    @Test
    void projectLinkIntoOwnNamespace_staysReadableForItsOwner() throws Exception {
        Path projectRoot = project.resolve("p");
        Path workspace = projectRoot.resolve("box/workspace");
        Files.createDirectories(workspace.resolve("alice"));
        Files.writeString(workspace.resolve("alice/MEMORY.md"), "alice-secret-memory");
        Path link = projectRoot.resolve("mine");
        try {
            Files.createSymbolicLink(link, workspace.resolve("alice"));
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.abort("symbolic links not supported here: " + unsupported.getMessage());
        }
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(projectRoot)
                        .projectWritable(true)
                        .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
        String linked = link.resolve("MEMORY.md").toString();

        ReadResult ownerRead = fs.read(caller("alice"), linked, 0, 100);
        assertTrue(
                ownerRead.isSuccess(),
                () -> "the owner lost their own file through a project link: " + ownerRead.error());
        assertFalse(
                fs.read(caller("bob"), linked, 0, 100).isSuccess(),
                "a sibling read a namespace through a project link");
        LsResult siblingListed = fs.ls(caller("bob"), link.toString());
        assertTrue(
                siblingListed.entries() == null || siblingListed.entries().isEmpty(),
                "ls() listed a namespace through a project link");
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
