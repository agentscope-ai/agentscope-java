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
package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Grant-layer revocation: a narrowed projection must REMOVE previously projected files from
 * a reused or snapshot-restored sandbox workspace, not overlay the new set on top of the old
 * one (maintainer review P1: residue staged under earlier, wider grants must stop being
 * readable once the current call's grant set no longer authorizes it).
 */
@DisplayName("Workspace projection revocation (authoritative replace)")
class WorkspaceProjectionRevocationTest {

    private static final String ALPHA = ".skills-cache/u1/market/alpha/SKILL.md";
    private static final String BETA = ".skills-cache/u1/market/beta/SKILL.md";
    private static final String AGENTS = "AGENTS.md";

    @TempDir Path tempDir;

    private Path hostSource;

    @BeforeEach
    void stageHostFiles() throws Exception {
        hostSource = tempDir.resolve("src");
        Files.createDirectories(hostSource.resolve(".skills-cache/u1/market/alpha"));
        Files.createDirectories(hostSource.resolve(".skills-cache/u1/market/beta"));
        Files.writeString(hostSource.resolve(AGENTS), "agents");
        Files.writeString(hostSource.resolve(ALPHA), "alpha");
        Files.writeString(hostSource.resolve(BETA), "beta");
    }

    @Test
    @DisplayName("reused workspace: narrowed projection revokes the stale files (Branch A)")
    void reusedWorkspaceRevokesNarrowedFiles() throws Exception {
        FakeState state = new FakeState();
        state.setWorkspaceSpec(specWithRoots(AGENTS, ".skills-cache/u1/market/beta"));
        FakeSandbox sandbox = new FakeSandbox(state);
        sandbox.start();
        assertTrue(sandbox.exists(AGENTS));
        assertTrue(sandbox.exists(BETA), "beta granted on the first call");

        sandbox.stop();
        state.setWorkspaceSpec(specWithRoots(AGENTS, ".skills-cache/u1/market/alpha"));
        sandbox.start();

        assertTrue(sandbox.exists(AGENTS), "still granted");
        assertTrue(sandbox.exists(ALPHA), "newly granted");
        assertFalse(
                sandbox.exists(BETA),
                "beta was staged under an earlier, wider grant and must be revoked");
        // Containment shape, pinned: the generated commands are file-only deletions with
        // quoted paths — a future edit back to recursive rm or unquoted paths fails here.
        assertTrue(
                !sandbox.deleteCommands().isEmpty()
                        && sandbox.deleteCommands().stream()
                                .allMatch(c -> c.startsWith("rm -f -- \"")),
                "deletion commands must be 'rm -f --' with quoted absolute paths");
        assertTrue(
                sandbox.deleteCommands().stream().noneMatch(c -> c.startsWith("rm -r")),
                "recursive deletion must never be generated");
    }

    @Test
    @DisplayName("traversal entry in the manifest aborts the start (end-to-end fail-closed)")
    void traversalManifestEntryFailsTheStartClosed() throws Exception {
        FakeState state = new FakeState();
        state.setWorkspaceSpec(specWithRoots(AGENTS));
        FakeSandbox sandbox = new FakeSandbox(state);
        sandbox.start();
        sandbox.stop();
        state.setProjectedWorkspacePaths(List.of("../escape.txt", AGENTS));
        state.setWorkspaceSpec(specWithRoots(".skills-cache/u1/market/alpha"));
        assertThrows(
                SandboxException.WorkspaceStartException.class,
                sandbox::start,
                "a traversal entry in the persisted manifest must abort the start, not skip");
    }

    @Test
    @DisplayName("projection emptied: everything previously projected is revoked")
    void emptyProjectionRevokesEverything() throws Exception {
        FakeState state = new FakeState();
        state.setWorkspaceSpec(specWithRoots(AGENTS, ".skills-cache/u1/market/beta"));
        FakeSandbox sandbox = new FakeSandbox(state);
        sandbox.start();
        assertTrue(sandbox.exists(BETA));

        sandbox.stop();
        state.setWorkspaceSpec(specWithRoots());
        sandbox.start();

        assertFalse(sandbox.exists(AGENTS));
        assertFalse(sandbox.exists(BETA), "DENY_ALL-style empty projection revokes all");
    }

    @Test
    @DisplayName("snapshot restore: stale projected files are revoked after hydration")
    void snapshotRestoreRevokesStaleProjectedFiles() throws Exception {
        FakeState state = new FakeState();
        state.setSnapshot(new InMemorySnapshot());
        state.setWorkspaceSpec(specWithRoots(AGENTS, ".skills-cache/u1/market/beta"));
        FakeSandbox sandbox = new FakeSandbox(state);
        sandbox.start();
        assertTrue(sandbox.exists(BETA));
        sandbox.stop(); // snapshot now contains beta

        // Simulate a lost workspace (crashed run): root gone, never marked ready.
        sandbox.wipeWorkspace();
        state.setWorkspaceRootReady(false);
        state.setWorkspaceSpec(specWithRoots(AGENTS, ".skills-cache/u1/market/alpha"));

        sandbox.start(); // Branch C: snapshot restores beta, then the forced pass revokes it

        assertTrue(sandbox.exists(ALPHA));
        assertFalse(
                sandbox.exists(BETA),
                "snapshot-restored beta must not survive the narrowed projection");
    }

    @Test
    @DisplayName("tampered manifest path (shell metacharacters) fails closed")
    void tamperedManifestPathFailsClosed() throws Exception {
        FakeState state = new FakeState();
        state.setWorkspaceSpec(specWithRoots(AGENTS));
        FakeSandbox sandbox = new FakeSandbox(state);
        // Persisted manifests are untrusted: command-substitution and backtick payloads
        // must be rejected by the character whitelist before any command is built.
        assertThrows(
                IllegalStateException.class,
                () -> sandbox.doDeleteWorkspacePaths(List.of("x$(rm -rf /)y", "a`b`c")));
    }

    @Test
    @DisplayName("pruning removes equal-depth sibling trees (no comparator collisions)")
    void pruningRemovesEqualDepthSiblingTrees() throws Exception {
        Files.createDirectories(hostSource.resolve("one/x"));
        Files.createDirectories(hostSource.resolve("two/y"));
        Files.writeString(hostSource.resolve("one/x/f.txt"), "f");
        Files.writeString(hostSource.resolve("two/y/g.txt"), "g");

        FakeState state = new FakeState();
        state.setWorkspaceSpec(specWithRoots("one", "two"));
        FakeSandbox sandbox = new FakeSandbox(state);
        sandbox.start();
        assertTrue(sandbox.exists("one/x/f.txt"));

        sandbox.stop();
        state.setWorkspaceSpec(specWithRoots());
        sandbox.start();

        assertFalse(sandbox.exists("one/x/f.txt"));
        assertFalse(sandbox.exists("two/y/g.txt"));
        assertFalse(
                Files.exists(sandbox.workspaceDir.resolve("one")),
                "empty sibling tree with an equal-depth sibling must still be pruned");
        assertFalse(Files.exists(sandbox.workspaceDir.resolve("two")), "empty tree pruned");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private WorkspaceSpec specWithRoots(String... includeRoots) {
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(hostSource.toString());
        projection.setIncludeRoots(List.of(includeRoots));
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.getEntries().put("__workspace_projection__", projection);
        return spec;
    }

    /** Minimal concrete state: no backend fields, just the base bookkeeping. */
    private static final class FakeState extends SandboxState {}

    /** Keeps the persisted archive in memory. */
    private static final class InMemorySnapshot implements SandboxSnapshot {
        private byte[] archive;

        @Override
        public void persist(InputStream workspaceArchive) throws Exception {
            archive = workspaceArchive.readAllBytes();
        }

        @Override
        public InputStream restore() throws Exception {
            return new ByteArrayInputStream(archive);
        }

        @Override
        public boolean isRestorable() throws Exception {
            return archive != null;
        }

        @Override
        public String getId() {
            return "in-memory";
        }

        @Override
        public String getType() {
            return "in-memory";
        }

        @Override
        public boolean isPersistenceEnabled() {
            return true;
        }
    }

    /** Filesystem-backed fake: the workspace is a temp dir, exec is parsed by hand. */
    private static final class FakeSandbox extends AbstractBaseSandbox {
        private final Path workspaceDir;
        private final List<String> deleteCommands = new ArrayList<>();
        // Matches the single-quoted operands the base class emits ('...' — no escaping).
        private static final Pattern QUOTED = Pattern.compile("'([^']+)'");

        FakeSandbox(SandboxState state) throws Exception {
            super(state);
            workspaceDir = Files.createTempDirectory("projection-revocation");
        }

        boolean exists(String relativePath) {
            return Files.isRegularFile(workspaceDir.resolve(relativePath));
        }

        void wipeWorkspace() throws Exception {
            try (var walk = Files.walk(workspaceDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }

        List<String> deleteCommands() {
            return deleteCommands;
        }

        @Override
        protected ExecResult doExec(
                RuntimeContext runtimeContext, String command, int timeoutSeconds) {
            if (command.startsWith("test -d ")) {
                return Files.isDirectory(workspaceDir)
                        ? new ExecResult(0, "", "", false)
                        : new ExecResult(1, "", "missing", false);
            }
            if (command.startsWith("rm -f --")) {
                deleteCommands.add(command);
                Matcher quoted = QUOTED.matcher(command);
                while (quoted.find()) {
                    String absolute = quoted.group(1);
                    String relative = absolute.substring("/workspace/".length());
                    try {
                        Files.deleteIfExists(workspaceDir.resolve(relative));
                    } catch (Exception e) {
                        return new ExecResult(1, "", e.getMessage(), false);
                    }
                }
                return new ExecResult(0, "", "", false);
            }
            if (command.startsWith("rmdir --")) {
                Matcher quoted = QUOTED.matcher(command);
                while (quoted.find()) {
                    String relative = quoted.group(1).substring("/workspace/".length());
                    try {
                        // deleteIfExists succeeds only for empty directories, like rmdir
                        Files.deleteIfExists(workspaceDir.resolve(relative));
                    } catch (Exception ignored) {
                        // non-empty directories stay, mirroring rmdir semantics
                    }
                }
                return new ExecResult(0, "", "", false);
            }
            return new ExecResult(1, "", "unsupported command: " + command, false);
        }

        @Override
        protected InputStream doPersistWorkspace() throws Exception {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(baos);
                    var walk = Files.walk(workspaceDir)) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                walk.filter(Files::isRegularFile)
                        .forEach(
                                p -> {
                                    try {
                                        String rel =
                                                workspaceDir
                                                        .relativize(p)
                                                        .toString()
                                                        .replace('\\', '/');
                                        byte[] content = Files.readAllBytes(p);
                                        TarArchiveEntry entry = new TarArchiveEntry(rel);
                                        entry.setSize(content.length);
                                        tar.putArchiveEntry(entry);
                                        tar.write(content);
                                        tar.closeArchiveEntry();
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                });
            }
            return new ByteArrayInputStream(baos.toByteArray());
        }

        @Override
        protected void doHydrateWorkspace(InputStream archive) throws Exception {
            WorkspaceArchiveExtractor.extractTarArchive(workspaceDir, archive);
        }

        @Override
        protected void doSetupWorkspace() throws Exception {
            Files.createDirectories(workspaceDir);
        }

        @Override
        protected void doDestroyWorkspace() throws Exception {
            wipeWorkspace();
        }

        @Override
        protected String getWorkspaceRoot() {
            return "/workspace";
        }
    }
}
