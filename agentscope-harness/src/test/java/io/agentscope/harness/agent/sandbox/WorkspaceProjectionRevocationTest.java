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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Comparator;
import java.util.List;
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
        // single-quoted paths (no shell expansion) — a future edit back to recursive rm,
        // unquoted or double-quoted paths fails here.
        assertTrue(
                !sandbox.deleteCommands().isEmpty()
                        && sandbox.deleteCommands().stream()
                                .allMatch(c -> c.startsWith("rm -f -- '")),
                "deletion commands must be 'rm -f --' with single-quoted absolute paths");
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
    @DisplayName(
            "legal filenames (spaces, unicode, metacharacters, apostrophes) round-trip"
                    + " through revocation")
    void legalFilenamesRoundTripThroughRevocation() throws Exception {
        Files.createDirectories(hostSource.resolve("中文目录"));
        Files.writeString(hostSource.resolve("user guide.md"), "guide");
        Files.writeString(hostSource.resolve("中文目录/说明.md"), "zh");
        Files.writeString(hostSource.resolve("a$(x)b`c`.md"), "meta");
        Files.writeString(hostSource.resolve("it's.md"), "apostrophe");

        FakeState state = new FakeState();
        state.setWorkspaceSpec(specWithRoots("user guide.md", "中文目录", "a$(x)b`c`.md", "it's.md"));
        FakeSandbox sandbox = new FakeSandbox(state);
        sandbox.start();
        assertTrue(sandbox.exists("user guide.md"));
        assertTrue(sandbox.exists("中文目录/说明.md"));
        assertTrue(sandbox.exists("it's.md"));
        assertTrue(sandbox.exists("a$(x)b`c`.md"));

        sandbox.stop();
        state.setWorkspaceSpec(specWithRoots());
        sandbox.start(); // revocation must revoke legal names, not reject them

        assertFalse(sandbox.exists("user guide.md"));
        assertFalse(sandbox.exists("中文目录/说明.md"));
        assertFalse(sandbox.exists("a$(x)b`c`.md"));
        assertFalse(sandbox.exists("it's.md"), "the '\\''-escaped name must be revoked too");
    }

    @Test
    @DisplayName("boundary violations in the manifest (traversal, absolute) fail closed")
    void boundaryViolationsInManifestFailClosed() throws Exception {
        FakeState state = new FakeState();
        state.setWorkspaceSpec(specWithRoots(AGENTS));
        FakeSandbox sandbox = new FakeSandbox(state);
        // Persisted manifests are untrusted: only boundary violations are rejected
        // (traversal, absolute paths, backslashes); legal filenames always pass.
        assertThrows(
                IllegalStateException.class,
                () -> sandbox.doDeleteWorkspacePaths(List.of("../escape.txt")));
        assertThrows(
                IllegalStateException.class,
                () -> sandbox.doDeleteWorkspacePaths(List.of("/etc/x")));
        assertThrows(
                IllegalStateException.class, () -> sandbox.doDeleteWorkspacePaths(List.of("a\\b")));
        assertThrows(
                IllegalStateException.class, () -> sandbox.doDeleteWorkspacePaths(List.of("a/")));
        assertThrows(
                IllegalStateException.class, () -> sandbox.doDeleteWorkspacePaths(List.of("a//b")));
    }

    @Test
    @DisplayName("unmanaged projection keeps the generic overlay semantics (no deletion)")
    void unmanagedProjectionKeepsGenericOverlaySemantics() throws Exception {
        FakeState state = new FakeState();
        state.setWorkspaceSpec(specWithRoots(false, AGENTS, ".skills-cache/u1/market/beta"));
        FakeSandbox sandbox = new FakeSandbox(state);
        sandbox.start();
        assertTrue(sandbox.exists(BETA));

        sandbox.stop();
        // A narrower UNMANAGED projection (no grant layer engaged): the historic overlay
        // behaviour — stale files stay, nothing is deleted, nothing refuses.
        state.setWorkspaceSpec(specWithRoots(false, AGENTS, ".skills-cache/u1/market/alpha"));
        sandbox.start();

        assertTrue(sandbox.exists(ALPHA), "newly projected");
        assertTrue(sandbox.exists(BETA), "generic semantics: unmanaged narrowing never deletes");
    }

    @Test
    @DisplayName(
            "legacy state (hash without manifest): managed projection rebuilds the namespace"
                    + " instead of trusting missing bookkeeping")
    void legacyStateWithoutManifestRebuildsOnManagedProjection() throws Exception {
        FakeState state = new FakeState();
        state.setWorkspaceSpec(specWithRoots(AGENTS, ".skills-cache/u1/market/beta"));
        FakeSandbox sandbox = new FakeSandbox(state);
        sandbox.start();
        sandbox.stop();
        // Simulate an upgraded pre-manifest state: a projection hash was kept, the
        // revocation manifest was not.
        state.setProjectedWorkspacePaths(null);

        // Changed managed projection: the unaccounted workspace is rebuilt from scratch —
        // the cache namespace is wiped and re-projected, no stale file survives, no refusal.
        state.setWorkspaceSpec(specWithRoots(AGENTS, ".skills-cache/u1/market/alpha"));
        sandbox.start();
        assertFalse(sandbox.exists(BETA), "unaccounted beta must not survive the rebuild");
        assertTrue(sandbox.exists(ALPHA), "newly granted alpha is re-projected");
        assertTrue(sandbox.exists(AGENTS), "non-cache roots are re-projected too");
        assertTrue(
                !sandbox.rootDeleteCommands().isEmpty(),
                "the rebuild must clear the managed namespaces");
    }

    @Test
    @DisplayName("unmanaged content change invalidates the manifest; next managed pass rebuilds")
    void unmanagedChangeInvalidatesManifestForNextManagedPass() throws Exception {
        FakeState state = new FakeState();
        state.setWorkspaceSpec(specWithRoots(true, AGENTS, ".skills-cache/u1/market/beta"));
        FakeSandbox sandbox = new FakeSandbox(state);
        sandbox.start();
        sandbox.stop();

        // An unmanaged change overlays content outside managed bookkeeping.
        state.setWorkspaceSpec(specWithRoots(false, ".skills-cache/u1/market/alpha"));
        sandbox.start();
        assertTrue(sandbox.exists(ALPHA));
        sandbox.stop();

        // The next managed pass must not trust the stale manifest: rebuild, so no
        // under-revocation against bookkeeping that no longer describes the workspace.
        state.setWorkspaceSpec(specWithRoots(true, AGENTS, ".skills-cache/u1/market/alpha"));
        sandbox.start();
        assertFalse(sandbox.exists(BETA), "beta must not survive the rebuild");
        assertTrue(sandbox.exists(ALPHA), "alpha is re-projected");
        assertTrue(sandbox.exists(AGENTS), "non-cache roots survive via re-projection");
    }

    @Test
    @DisplayName(
            "resume-failure carry-over: fresh state + old snapshot rebuilds, stale files"
                    + " cannot survive (audit S1)")
    void resumeCarryOverRebuildsUnaccountedWorkspace() throws Exception {
        InMemorySnapshot snapshot = new InMemorySnapshot();
        FakeState wide = new FakeState();
        wide.setSnapshot(snapshot);
        wide.setWorkspaceSpec(specWithRoots(AGENTS, ".skills-cache/u1/market/beta"));
        FakeSandbox first = new FakeSandbox(wide);
        first.start();
        first.stop(); // the snapshot now contains the wide-era cache

        // The manager's resume-failure fallback: a FRESH state (no hash, no manifest)
        // pointed at the OLD snapshot, with the grant narrowed.
        FakeState fresh = new FakeState();
        fresh.setSnapshot(snapshot);
        fresh.setWorkspaceRootReady(false);
        fresh.setWorkspaceSpec(specWithRoots(AGENTS, ".skills-cache/u1/market/alpha"));
        FakeSandbox second = new FakeSandbox(fresh);
        second.start(); // Branch C restores wide-era beta, then the rebuild wipes the cache

        assertFalse(second.exists(BETA), "carried-over beta must not survive the narrowed call");
        assertTrue(second.exists(ALPHA), "the newly granted alpha is projected");
    }

    @Test
    @DisplayName("snapshot restore with an unchanged projection still rebuilds (force is pinned)")
    void snapshotRestoreWithUnchangedProjectionStillRebuilds() throws Exception {
        InMemorySnapshot snapshot = new InMemorySnapshot();
        FakeState state = new FakeState();
        state.setSnapshot(snapshot);
        state.setWorkspaceSpec(specWithRoots(AGENTS, ".skills-cache/u1/market/beta"));
        FakeSandbox sandbox = new FakeSandbox(state);
        sandbox.start();
        sandbox.stop();
        // Unchanged spec, intact manifest: the hash matches and the manifest is trusted,
        // so ONLY the restore (force) semantics can justify a rebuild — without force
        // this start would hash-skip. Pins the audit finding that restored archives are
        // untrusted events (persist-order skew, snapshot rebinding).
        sandbox.wipeWorkspace();
        state.setWorkspaceRootReady(false);
        sandbox.start();
        assertTrue(
                !sandbox.rootDeleteCommands().isEmpty(),
                "a managed restore must rebuild even with an intact manifest and equal hash");
        assertTrue(sandbox.exists(BETA), "the granted beta is re-projected after the wipe");
    }

    @Test
    @DisplayName("legacy manifest-less state narrowed to empty rebuilds the cache away")
    void legacyManifestLessStateNarrowedToEmptyRebuildsCacheAway() throws Exception {
        FakeState state = new FakeState();
        state.setWorkspaceSpec(specWithRoots(AGENTS, ".skills-cache/u1/market/beta"));
        FakeSandbox sandbox = new FakeSandbox(state);
        sandbox.start();
        sandbox.stop();
        state.setProjectedWorkspacePaths(null); // pre-manifest persisted state

        // The reviewer's literal probe: narrow to EMPTY — nothing survives the rebuild.
        state.setWorkspaceSpec(specWithRoots(AGENTS));
        sandbox.start();
        assertFalse(sandbox.exists(BETA), "narrowing to empty must revoke the whole cache");
        assertTrue(sandbox.exists(AGENTS), "non-cache roots are re-projected");
    }

    @Test
    @DisplayName("rebuild owns the cache namespace: foreign files under it are wiped")
    void rebuildWipesForeignFilesUnderTheCacheNamespace() throws Exception {
        FakeState state = new FakeState();
        state.setWorkspaceSpec(specWithRoots(AGENTS, ".skills-cache/u1/market/beta"));
        FakeSandbox sandbox = new FakeSandbox(state);
        sandbox.start();
        // A foreign (agent-authored) file inside the managed namespace: the namespace is
        // exclusively grant-managed, so a rebuild clears it wholesale — pinned on purpose.
        Files.createDirectories(sandbox.workspaceDir.resolve(".skills-cache/foreign"));
        Files.writeString(sandbox.workspaceDir.resolve(".skills-cache/foreign/notes.txt"), "x");
        sandbox.stop();
        state.setProjectedWorkspacePaths(null);

        state.setWorkspaceSpec(specWithRoots(AGENTS, ".skills-cache/u1/market/alpha"));
        sandbox.start();
        assertFalse(
                sandbox.exists(".skills-cache/foreign/notes.txt"),
                "the cache namespace is exclusively managed; rebuild clears it entirely");
    }

    @Test
    @DisplayName("managed projection without revokeRoots fails closed")
    void managedProjectionWithoutRevokeRootsFailsClosed() throws Exception {
        FakeState state = new FakeState();
        WorkspaceProjectionEntry entry = new WorkspaceProjectionEntry();
        entry.setSourceRoot(hostSource.toString());
        entry.setIncludeRoots(List.of(".skills-cache/u1/market/alpha"));
        entry.setAuthorizationManaged(true); // marked, but no rebuild scope declared
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.getEntries().put("__workspace_projection__", entry);
        state.setWorkspaceSpec(spec);
        state.setWorkspaceProjectionHash("stale-hash");
        state.setProjectedWorkspacePaths(null);
        FakeSandbox sandbox = new FakeSandbox(state);
        assertThrows(
                SandboxException.WorkspaceStartException.class,
                sandbox::start,
                "a marked entry without rebuild scope must fail closed, not assume empty");
    }

    @Test
    @DisplayName("revocation beyond the chunk size splits into multiple commands")
    void revocationBeyondChunkSizeSplitsIntoChunks() throws Exception {
        Path dir = hostSource.resolve("many");
        Files.createDirectories(dir);
        for (int i = 0; i < 51; i++) {
            Files.writeString(dir.resolve("f" + i + ".txt"), "x");
        }
        FakeState state = new FakeState();
        state.setWorkspaceSpec(specWithRoots("many"));
        FakeSandbox sandbox = new FakeSandbox(state);
        sandbox.start();
        sandbox.stop();
        state.setWorkspaceSpec(specWithRoots());
        sandbox.start();
        assertEquals(2, sandbox.deleteCommands().size(), "51 paths must split into 2 chunks");
        assertFalse(sandbox.exists("many/f50.txt"));
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

    /** Grant-managed spec by default — the scenario every revocation test exercises. */
    private WorkspaceSpec specWithRoots(String... includeRoots) {
        return specWithRoots(true, includeRoots);
    }

    private WorkspaceSpec specWithRoots(boolean authorizationManaged, String... includeRoots) {
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(hostSource.toString());
        projection.setIncludeRoots(List.of(includeRoots));
        projection.setAuthorizationManaged(authorizationManaged);
        if (authorizationManaged) {
            projection.setRevokeRoots(List.of(".skills-cache"));
        }
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
        private final List<String> rootDeleteCommands = new ArrayList<>();

        FakeSandbox(SandboxState state) throws Exception {
            super(state);
            workspaceDir = Files.createTempDirectory("projection-revocation");
        }

        boolean exists(String relativePath) {
            return Files.isRegularFile(workspaceDir.resolve(relativePath));
        }

        void wipeWorkspace() throws Exception {
            try (var walk = Files.walk(workspaceDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }

        List<String> deleteCommands() {
            return deleteCommands;
        }

        List<String> rootDeleteCommands() {
            return rootDeleteCommands;
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
                for (String operand : parseQuotedOperands(command)) {
                    try {
                        Files.deleteIfExists(workspaceDir.resolve(toRelative(operand)));
                    } catch (Exception e) {
                        return new ExecResult(1, "", e.getMessage(), false);
                    }
                }
                return new ExecResult(0, "", "", false);
            }
            if (command.startsWith("rm -rf --")) {
                rootDeleteCommands.add(command);
                for (String operand : parseQuotedOperands(command)) {
                    deleteRecursively(workspaceDir.resolve(toRelative(operand)));
                }
                return new ExecResult(0, "", "", false);
            }
            if (command.startsWith("rmdir --")) {
                for (String operand : parseQuotedOperands(command)) {
                    try {
                        // deleteIfExists succeeds only for empty directories, like rmdir
                        Files.deleteIfExists(workspaceDir.resolve(toRelative(operand)));
                    } catch (Exception ignored) {
                        // non-empty directories stay, mirroring rmdir semantics
                    }
                }
                return new ExecResult(0, "", "", false);
            }
            return new ExecResult(1, "", "unsupported command: " + command, false);
        }

        /**
         * Parses the shell single-quoted operands the base class emits, including the
         * {@code '\''} escape sequence: closing quotes may be followed by more quoting of
         * the same operand. Everything unquoted is the command prefix and is ignored.
         */
        private static List<String> parseQuotedOperands(String command) {
            List<String> operands = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inQuote = false;
            boolean any = false;
            for (int i = 0; i < command.length(); i++) {
                char c = command.charAt(i);
                if (inQuote) {
                    if (c == '\'') {
                        inQuote = false; // the operand may continue with more quoting
                    } else {
                        current.append(c);
                    }
                } else if (c == '\'') {
                    inQuote = true;
                    any = true;
                } else if (c == '\\' && any) {
                    current.append(++i < command.length() ? command.charAt(i) : c);
                } else if (c == ' ' && any) {
                    operands.add(current.toString());
                    current.setLength(0);
                    any = false;
                }
            }
            if (any) {
                operands.add(current.toString());
            }
            return operands;
        }

        private static String toRelative(String operand) {
            return operand.startsWith("/workspace/")
                    ? operand.substring("/workspace/".length())
                    : operand;
        }

        private void deleteRecursively(Path target) {
            try (var walk = Files.walk(target)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (Exception ignored) {
                // missing paths are fine
            }
        }

        @Override
        protected InputStream doPersistWorkspace() throws Exception {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // UTF-8 entry names to match WorkspaceArchiveExtractor (the reader of this
            // archive) — the platform default would disagree with it on non-UTF-8 JVMs.
            try (TarArchiveOutputStream tar =
                            new TarArchiveOutputStream(
                                    baos, java.nio.charset.StandardCharsets.UTF_8.name());
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
