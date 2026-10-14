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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Sandbox reconciliation after files disappear from the host cache (#3469). Host removals are
 * filesystem fixtures; these tests do not exercise repository offboarding or MarketplaceStager GC.
 */
@EnabledOnOs(OS.LINUX)
class Issue3469SandboxProjectionTest {

    private static final String CACHE = ".skills-cache/alice/market/";

    @TempDir Path tempDir;

    @ParameterizedTest(name = "snapshot restore with workspaceRootReady={0}")
    @ValueSource(booleans = {true, false})
    void removedHostCacheFilesDisappearAfterRestoreAndDoNotEnterTheNextSnapshot(boolean rootReady)
            throws Exception {
        Path host = tempDir.resolve("host");
        writeHostSkill(host, "skill-a");
        writeHostSkill(host, "skill-b");
        SandboxSnapshot snapshot =
                new LocalSnapshotSpec(tempDir.resolve("snapshots")).build("user");
        LocalTarSandbox initial = sandbox(host, "initial", snapshot, null, false);
        initial.start();
        assertEquals(
                2,
                Files.readAllLines(initial.root.resolve(CACHE + "skill-b/scripts/run.sh")).size());
        writeFile(initial.root.resolve("outputs/result.txt"), "user output");
        writeFile(initial.root.resolve("skills/custom/notes.txt"), "user skill");
        writeFile(initial.root.resolve("knowledge/private.txt"), "user knowledge");
        writeFile(initial.root.resolve("AGENTS.md"), "user instructions");
        initial.stop();
        assertTrue(
                snapshot.isRestorable(), "the initial workspace must have a real local snapshot");

        deleteHostSkill(host, "skill-b");
        assertFalse(Files.exists(host.resolve(CACHE + "skill-b")), "B must be absent on the host");
        LocalTarSandbox restored =
                sandbox(
                        host,
                        "restored",
                        snapshot,
                        initial.getState().getWorkspaceProjectionHash(),
                        rootReady);
        assertFalse(Files.exists(restored.root), "restoration must start in a missing workspace");
        assertEquals(
                2,
                WorkspaceProjectionApplier.build(restored.getState().getWorkspaceSpec())
                        .fileCount());
        restored.start();
        assertEquals(
                2, restored.hydrations, "snapshot restore and changed projection must both run");
        assertNotEquals(
                initial.getState().getWorkspaceProjectionHash(),
                restored.getState().getWorkspaceProjectionHash());
        assertEquals(
                "# skill-a\n", Files.readString(restored.root.resolve(CACHE + "skill-a/SKILL.md")));
        assertUserFiles(restored.root);
        boolean firstDirectoryRemains = Files.exists(restored.root.resolve(CACHE + "skill-b"));
        boolean firstScriptRemains =
                Files.exists(restored.root.resolve(CACHE + "skill-b/scripts/run.sh"));

        // Persist again before asserting deletion, so a failure also proves re-persistence.
        restored.stop();
        LocalTarSandbox next =
                sandbox(
                        host,
                        "next",
                        snapshot,
                        restored.getState().getWorkspaceProjectionHash(),
                        rootReady);
        next.start();
        assertEquals(1, next.hydrations, "unchanged projection leaves only snapshot restoration");
        assertUserFiles(next.root);
        assertAll(
                () ->
                        assertFalse(
                                firstDirectoryRemains,
                                "removed B directory survives the first restore"),
                () ->
                        assertFalse(
                                firstScriptRemains, "removed B script survives the first restore"),
                () ->
                        assertFalse(
                                Files.exists(next.root.resolve(CACHE + "skill-b")),
                                "removed B directory is re-persisted into the next snapshot"),
                () ->
                        assertFalse(
                                Files.exists(next.root.resolve(CACHE + "skill-b/scripts/run.sh")),
                                "removed B script is re-persisted into the next snapshot"));
    }

    @Test
    void emptyHostProjectionClearsRestoredScopeAfterHostCacheFilesAreRemoved() throws Exception {
        Path host = tempDir.resolve("host");
        writeHostSkill(host, "skill-b");
        SandboxSnapshot snapshot =
                new LocalSnapshotSpec(tempDir.resolve("snapshots")).build("user");
        LocalTarSandbox initial = sandbox(host, "initial", snapshot, null, false);
        initial.start();
        assertTrue(Files.exists(initial.root.resolve(CACHE + "skill-b/scripts/run.sh")));
        initial.stop();

        deleteHostSkill(host, "skill-b");
        LocalTarSandbox restored =
                sandbox(
                        host,
                        "restored",
                        snapshot,
                        initial.getState().getWorkspaceProjectionHash(),
                        false);
        assertEquals(
                0,
                WorkspaceProjectionApplier.build(restored.getState().getWorkspaceSpec())
                        .fileCount());
        restored.start();
        assertEquals(1, restored.hydrations, "the zero-file projection performs no hydration");
        assertFalse(
                Files.exists(restored.root.resolve(CACHE + "skill-b")),
                "zero-file host projection leaves removed host cache files in the restored"
                        + " workspace");
    }

    @Test
    void snapshotAndChangedProjectionShouldRestoreUserFilesAndUpdateTheRemainingSkill()
            throws Exception {
        Path host = tempDir.resolve("host");
        writeHostSkill(host, "skill-a");
        SandboxSnapshot snapshot =
                new LocalSnapshotSpec(tempDir.resolve("snapshots")).build("user");
        LocalTarSandbox initial = sandbox(host, "initial", snapshot, null, false);
        initial.start();
        writeFile(initial.root.resolve("outputs/result.txt"), "user output");
        initial.stop();
        writeFile(host.resolve(CACHE + "skill-a/SKILL.md"), "updated skill");

        LocalTarSandbox restored =
                sandbox(
                        host,
                        "restored",
                        snapshot,
                        initial.getState().getWorkspaceProjectionHash(),
                        false);
        restored.start();
        assertEquals(2, restored.hydrations);
        assertEquals(
                "updated skill",
                Files.readString(restored.root.resolve(CACHE + "skill-a/SKILL.md")));
        assertEquals("user output", Files.readString(restored.root.resolve("outputs/result.txt")));
    }

    private LocalTarSandbox sandbox(
            Path host, String directory, SandboxSnapshot snapshot, String hash, boolean rootReady) {
        Path root = tempDir.resolve(directory);
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(host.toString());
        projection.setIncludeRoots(List.of(".skills-cache"));
        projection.setSyncRoots(List.of(".skills-cache/alice"));
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot(root.toString());
        spec.getEntries().put("__workspace_projection__", projection);
        SandboxState state = new SandboxState() {};
        state.setWorkspaceSpec(spec);
        state.setSnapshot(snapshot);
        state.setWorkspaceProjectionHash(hash);
        state.setWorkspaceRootReady(rootReady);
        return new LocalTarSandbox(state, root);
    }

    private static void writeHostSkill(Path host, String name) throws Exception {
        writeFile(host.resolve(CACHE + name + "/SKILL.md"), "# " + name + "\n");
        writeFile(host.resolve(CACHE + name + "/scripts/run.sh"), "#!/bin/sh\necho " + name + "\n");
    }

    /** Models absent host projection files; it does not simulate stager GC or repository removal. */
    private static void deleteHostSkill(Path host, String name) throws Exception {
        Path skill = host.resolve(CACHE + name);
        Files.delete(skill.resolve("scripts/run.sh"));
        Files.delete(skill.resolve("scripts"));
        Files.delete(skill.resolve("SKILL.md"));
        Files.delete(skill);
    }

    private static void writeFile(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static void assertUserFiles(Path root) throws Exception {
        assertEquals("user output", Files.readString(root.resolve("outputs/result.txt")));
        assertEquals("user skill", Files.readString(root.resolve("skills/custom/notes.txt")));
        assertEquals("user knowledge", Files.readString(root.resolve("knowledge/private.txt")));
        assertEquals("user instructions", Files.readString(root.resolve("AGENTS.md")));
    }

    /** Uses production start/stop, projection and extraction; only the backend is local. */
    static class LocalTarSandbox extends AbstractBaseSandbox {

        private final Path root;
        private int hydrations;

        LocalTarSandbox(SandboxState state, Path root) {
            super(state);
            this.root = root;
        }

        @Override
        protected boolean probeWorkspaceRootForPreservedResume() {
            return Files.isDirectory(root);
        }

        @Override
        protected void verifyExclusiveCacheProjection() {
            // These temporary workspaces have no independent writers; concurrent calls use a lease.
        }

        @Override
        protected ExecResult doExec(RuntimeContext context, String command, int timeoutSeconds)
                throws Exception {
            Path stdout = Files.createTempFile(root.getParent(), "projection-out-", ".txt");
            Path stderr = Files.createTempFile(root.getParent(), "projection-error-", ".txt");
            Process process =
                    new ProcessBuilder(shellExecutable(), "-s")
                            .redirectOutput(stdout.toFile())
                            .redirectError(stderr.toFile())
                            .start();
            // Feed the script through stdin; Windows native quoting corrupts nested -c quotes.
            try (var stdin = process.getOutputStream()) {
                stdin.write(command.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Cache reconciliation command timed out");
            }
            return new ExecResult(
                    process.exitValue(), Files.readString(stdout), Files.readString(stderr), false);
        }

        @Override
        protected ExecResult doExecWithInput(List<String> command, byte[] input, int timeoutSeconds)
                throws Exception {
            Path stdout = Files.createTempFile(root.getParent(), "projection-out-", ".txt");
            Path stderr = Files.createTempFile(root.getParent(), "projection-error-", ".txt");
            Process process =
                    new ProcessBuilder(command)
                            .redirectOutput(stdout.toFile())
                            .redirectError(stderr.toFile())
                            .start();
            var writer = java.util.concurrent.Executors.newSingleThreadExecutor();
            var delivered =
                    writer.submit(
                            () -> {
                                try (var stdin = process.getOutputStream()) {
                                    stdin.write(input);
                                }
                                return null;
                            });
            try {
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Cache reconciliation command timed out");
                }
                delivered.get();
                return new ExecResult(
                        process.exitValue(),
                        Files.readString(stdout),
                        Files.readString(stderr),
                        false);
            } finally {
                if (process.isAlive()) process.destroyForcibly();
                writer.shutdownNow();
            }
        }

        private static String shellExecutable() {
            if (java.io.File.separatorChar != '\\') {
                return "sh";
            }
            for (String directory : System.getenv("PATH").split(java.io.File.pathSeparator)) {
                Path git = Path.of(directory).resolve("git.exe");
                if (Files.exists(git)) {
                    Path shell = git.getParent().getParent().resolve("bin/bash.exe");
                    if (Files.exists(shell)) return shell.toString();
                }
            }
            throw new IllegalStateException("The Windows fixture requires Git for Windows bash");
        }

        @Override
        protected InputStream doPersistWorkspace() throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes);
                    var paths = Files.walk(root)) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    TarArchiveEntry entry = new TarArchiveEntry(relative);
                    entry.setSize(Files.size(file));
                    tar.putArchiveEntry(entry);
                    Files.copy(file, tar);
                    tar.closeArchiveEntry();
                }
                tar.finish();
            }
            return new ByteArrayInputStream(bytes.toByteArray());
        }

        @Override
        protected void doHydrateWorkspace(InputStream archive) throws Exception {
            WorkspaceArchiveExtractor.extractTarArchive(root, archive);
            hydrations++;
        }

        @Override
        protected void doSetupWorkspace() throws Exception {
            Files.createDirectories(root);
        }

        @Override
        protected void doDestroyWorkspace() {
            throw new UnsupportedOperationException("JUnit owns the temporary workspace");
        }

        @Override
        protected String getWorkspaceRoot() {
            return root.toString();
        }

        @Override
        public void shutdown() {
            // Each resume uses a different @TempDir child to model a missing container workspace.
        }
    }
}
