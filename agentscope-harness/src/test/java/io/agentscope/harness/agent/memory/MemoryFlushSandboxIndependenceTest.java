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
package io.agentscope.harness.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Regression tests for #3110: under a sandbox-backed workspace the fire-and-forget memory flush
 * runs after the agent event stream terminates — by then the sandbox lease may already be
 * released, and the write through {@code SandboxBackedFilesystem} failed with "No active
 * sandbox". Memory files are cross-call metadata: their IO must be sandbox-independent, landing
 * on the host workspace (the same location used when no filesystem layer is configured).
 *
 * <p>The tests model the after-stream state with a {@code SandboxBackedFilesystem} that never
 * had a sandbox injected.
 */
class MemoryFlushSandboxIndependenceTest {

    @TempDir Path workspace;

    private static Model extractionModel(String memoryText) {
        Model model = mock(Model.class);
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text(memoryText).build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    @Test
    void flushAfterStreamTerminates_writesDailyLedgerHostSide() throws Exception {
        SandboxBackedFilesystem sandboxFs = new SandboxBackedFilesystem();
        try (WorkspaceManager wsm = new WorkspaceManager(workspace, sandboxFs)) {
            MemoryFlushManager flushManager =
                    new MemoryFlushManager(wsm, extractionModel("- learned: host-side memory"));
            RuntimeContext rc = RuntimeContext.builder().sessionId("s1").userId("u1").build();

            // Before the fix this completed "successfully" (errors were swallowed by the
            // flush's error resume) while the write itself failed with "No active sandbox" and
            // no file ever appeared.
            flushMemoriesQuietly(flushManager, rc);

            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            Path daily = workspace.resolve("memory/" + today + ".md");
            assertTrue(Files.isRegularFile(daily), "daily ledger must land on the host side");
            String content = Files.readString(daily);
            assertTrue(content.contains("host-side memory"), content);
        }
    }

    @Test
    void consolidationAfterStreamTerminates_rewritesMemoryMdHostSide() throws Exception {
        SandboxBackedFilesystem sandboxFs = new SandboxBackedFilesystem();
        try (WorkspaceManager wsm = new WorkspaceManager(workspace, sandboxFs)) {
            // Seed today's ledger host-side (as a previous flushed call would have) plus the
            // state watermark; the consolidator must read both host-side and rewrite
            // MEMORY.md host-side — never through the dead sandbox proxy.
            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            Path memDir = workspace.resolve("memory");
            Files.createDirectories(memDir);
            Files.writeString(memDir.resolve(today + ".md"), "- fact one\n");
            Files.writeString(memDir.resolve("memory_state.ts"), "1970-01-01T00:00:00Z");

            Model model = extractionModel("# Curated\n- fact one");
            MemoryConsolidator consolidator =
                    new MemoryConsolidator(
                            wsm,
                            model,
                            MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT,
                            4000,
                            null);
            consolidator.consolidate(RuntimeContext.empty()).block();

            Path memoryMd = workspace.resolve("MEMORY.md");
            assertTrue(Files.isRegularFile(memoryMd), "MEMORY.md must be rewritten host-side");
            assertTrue(Files.readString(memoryMd).contains("fact one"));
        }
    }

    @Test
    void readMemoryMd_prefersHostCopyUnderSandboxFilesystem() throws Exception {
        SandboxBackedFilesystem sandboxFs = new SandboxBackedFilesystem();
        try (WorkspaceManager wsm = new WorkspaceManager(workspace, sandboxFs)) {
            Files.writeString(workspace.resolve("MEMORY.md"), "host-authoritative");
            // Symmetry: a host-side write (from the post-stream flush) must never be shadowed
            // by a stale sandbox copy on read — with no live sandbox the fs layer cannot
            // answer anyway, and the read must succeed from the host copy.
            assertEquals("host-authoritative", wsm.readMemoryMd(RuntimeContext.empty()).strip());
            assertNotNull(wsm.listMemoryFilePaths(RuntimeContext.empty()));
            assertTrue(
                    wsm.listMemoryFilePaths(RuntimeContext.empty()).contains("MEMORY.md"),
                    "host memory files must be listed");
        }
    }

    private static void flushMemoriesQuietly(MemoryFlushManager fm, RuntimeContext rc)
            throws Exception {
        // The production pipeline resumes errors into empty; the assertion that matters is
        // whether the FILE landed, so mirror that contract here.
        try {
            fm.flushMemories(rc, List.of(userMsg("please remember this"))).block();
        } catch (Exception expectedOnUnfixedMain) {
            // red on unfixed main arrives as a swallowed error or a thrown one; either way the
            // file assertions below decide.
        }
    }

    @Test
    void memorySaveTool_landsHostSideAndIsReadableBack() throws Exception {
        SandboxBackedFilesystem sandboxFs = new SandboxBackedFilesystem();
        try (WorkspaceManager wsm = new WorkspaceManager(workspace, sandboxFs)) {
            io.agentscope.harness.agent.tool.MemorySaveTool saveTool =
                    new io.agentscope.harness.agent.tool.MemorySaveTool(wsm);
            saveTool.memorySave(RuntimeContext.empty(), "preference: dark mode");
            saveTool.memorySave(RuntimeContext.empty(), "fact: coffee");

            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            Path daily = workspace.resolve("memory/" + today + ".md");
            assertTrue(Files.isRegularFile(daily), "memory_save must land host-side");
            String content = Files.readString(daily);
            assertTrue(content.contains("dark mode"), content);
            assertTrue(content.contains("coffee"), content);

            // Read back through the scoped read (what flush/consolidation will see): the
            // tool write is immediately visible to the rest of the memory family — no split
            // storage.
            assertTrue(
                    wsm.readMemoryFileUtf8(RuntimeContext.empty(), "memory/" + today + ".md")
                            .contains("coffee"));
        }
    }

    @Test
    void routedSandboxConfiguration_consolidationListsAndReadsHostSide() throws Exception {
        // RoutedSandboxFilesystem wrapping the dead sandbox proxy: the unified probe must
        // unwrap the routing (a bare instanceof SandboxBackedFilesystem would not) and the
        // consolidation must still find and merge the host-side ledgers.
        SandboxBackedFilesystem dead = new SandboxBackedFilesystem();
        io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem routed =
                new io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem(
                        dead, java.util.Map.of());
        try (WorkspaceManager wsm = new WorkspaceManager(workspace, routed)) {
            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            Path memDir = workspace.resolve("memory");
            Files.createDirectories(memDir);
            Files.writeString(memDir.resolve(today + ".md"), "- routed fact\n");
            Files.writeString(memDir.resolve("memory_state.ts"), "1970-01-01T00:00:00Z");

            Model model = extractionModel("# Curated\n- routed fact");
            MemoryConsolidator consolidator =
                    new MemoryConsolidator(
                            wsm,
                            model,
                            MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT,
                            4000,
                            null);
            consolidator.consolidate(RuntimeContext.empty()).block();

            Path memoryMd = workspace.resolve("MEMORY.md");
            assertTrue(Files.isRegularFile(memoryMd), "MEMORY.md must be rewritten host-side");
            assertTrue(Files.readString(memoryMd).contains("routed fact"));
        }
    }

    @Test
    void watermarkPersists_consolidationSecondRunIsNoOp() throws Exception {
        // The watermark write went to the namespaced host side long ago; this regression
        // guards the READ: if it stayed on the old fs-first IO, a sandbox deployment would
        // read EPOCH every time and re-merge ALL historical ledgers on every consolidation.
        SandboxBackedFilesystem sandboxFs = new SandboxBackedFilesystem();
        try (WorkspaceManager wsm = new WorkspaceManager(workspace, sandboxFs)) {
            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            Path memDir = workspace.resolve("memory");
            Files.createDirectories(memDir);
            Files.writeString(memDir.resolve(today + ".md"), "- fact one\n");
            Files.writeString(memDir.resolve("memory_state.ts"), "1970-01-01T00:00:00Z");

            java.util.concurrent.atomic.AtomicInteger calls =
                    new java.util.concurrent.atomic.AtomicInteger();
            Model countingModel = mock(Model.class);
            when(countingModel.stream(anyList(), any(), any()))
                    .thenAnswer(
                            inv -> {
                                calls.incrementAndGet();
                                return Flux.just(
                                        new ChatResponse(
                                                "id",
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("# Curated\n- fact one")
                                                                .build()),
                                                null,
                                                Map.of(),
                                                "stop"));
                            });

            MemoryConsolidator consolidator =
                    new MemoryConsolidator(
                            wsm,
                            countingModel,
                            MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT,
                            4000,
                            null);
            consolidator.consolidate(RuntimeContext.empty()).block();
            assertEquals(1, calls.get(), "first consolidation runs the model");

            // Second run with NO new ledger content: the watermark from run 1 covers today's
            // file, so no eligible entries remain and the model must NOT be called again.
            consolidator.consolidate(RuntimeContext.empty()).block();
            assertEquals(
                    1,
                    calls.get(),
                    "second consolidation must be a no-op (watermark read from the same "
                            + "namespaced store the write used)");
        }
    }

    @Test
    void namespacedDeployment_memorySaveThenSearchReadsBack() throws Exception {
        // Production wiring passes a non-null NamespaceFactory (USER isolation is the default),
        // so the host fallback must observe the ROOT memory scope — the same scope the
        // memory-scoped IO writes. Before the scope alignment, the search listing looked under
        // workspace/<ns>/memory and found nothing.
        SandboxBackedFilesystem sandboxFs = new SandboxBackedFilesystem();
        io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory nsFactory =
                rc -> java.util.List.of("user-42");
        try (WorkspaceManager wsm = new WorkspaceManager(workspace, sandboxFs, null, nsFactory)) {
            io.agentscope.harness.agent.tool.MemorySaveTool saveTool =
                    new io.agentscope.harness.agent.tool.MemorySaveTool(wsm);
            saveTool.memorySave(RuntimeContext.empty(), "root-scoped fact");

            java.util.List<String> found = wsm.listMemoryFilePaths(RuntimeContext.empty());
            assertTrue(
                    found.stream().anyMatch(path -> !path.equals("MEMORY.md")),
                    "daily ledger must be discoverable by the search listing, got: " + found);

            // Isolation is REAL: with RuntimeContext.empty() the namespace factory returns
            // ["user-42"] (the test factory ignores rc), so the file must live under the
            // namespaced directory — not the bare workspace root.
            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            Path namespaced = workspace.resolve("user-42").resolve("memory").resolve(today + ".md");
            assertTrue(
                    Files.isRegularFile(namespaced),
                    "namespaced deployment must keep memory under the runtime-data namespace: "
                            + namespaced);
            assertFalse(
                    Files.isRegularFile(workspace.resolve("memory").resolve(today + ".md")),
                    "no bare-root memory copy in a namespaced deployment");
        }
    }

    @Test
    void routedMemoryDirPrefix_eachFamilyMemberFollowsItsOwnBackend() throws Exception {
        // filesystemRoute("memory/", store) moves the ledgers and the watermark to the routed
        // store, but MEMORY.md is a root-level path no memory/ route matches — its serving
        // backend stays the per-call sandbox proxy, so its authoritative copy lives
        // host-side. The search listing must anchor each family member on its own path: the
        // pre-fix guard probed only the directory, then sent the MEMORY.md existence check
        // through the dead sandbox proxy — the exact post-lease layer #3110 keeps memory IO
        // away from.
        MemoryDirStoreFs store = new MemoryDirStoreFs();
        io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem routed =
                new io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem(
                        new SandboxBackedFilesystem(), Map.of("memory/", store));
        try (WorkspaceManager wsm = new WorkspaceManager(workspace, routed)) {
            RuntimeContext rc = RuntimeContext.empty();
            wsm.appendMemoryFileUtf8(rc, "MEMORY.md", "# Host\n");
            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            wsm.appendMemoryFileUtf8(rc, "memory/" + today + ".md", "- routed ledger fact\n");

            assertTrue(wsm.readMemoryMd(rc).contains("# Host"), "MEMORY.md reads back host-side");
            assertTrue(
                    wsm.readMemoryFileUtf8(rc, "memory/" + today + ".md").contains("routed"),
                    "the daily ledger reads back through the routed store");
            assertTrue(
                    wsm.listMemoryFileInfos(rc).stream()
                            .anyMatch(fi -> ("memory/" + today + ".md").equals(fi.path())),
                    "the consolidator's listing must see the routed ledger");

            List<String> paths = wsm.listMemoryFilePaths(rc);
            assertTrue(
                    paths.contains("MEMORY.md"),
                    "host-side MEMORY.md must be listed, got: " + paths);
            assertTrue(
                    paths.contains("memory/" + today + ".md"),
                    "the routed ledger must be listed through the store glob, got: " + paths);
        }
    }

    @Test
    void upgradeShape_sandboxCopyDoesNotShadowAbsentHostCopy() throws Exception {
        // After upgrading a sandbox deployment the host copy does not exist yet while the
        // pre-fix sandbox snapshot may still hold one. The stale sandbox copy is deliberately
        // NOT adopted (every post-stream write was already lost from it, #3110) — the read
        // starts from the host copy, and the only sandbox interaction allowed on a host miss
        // is the existence probe that powers the upgrade log line.
        ProbeRecordingSandbox sandbox = new ProbeRecordingSandbox("yes\n");
        SandboxBackedFilesystem sandboxFs = new SandboxBackedFilesystem();
        sandboxFs.setSandbox(sandbox);
        try (WorkspaceManager wsm = new WorkspaceManager(workspace, sandboxFs)) {
            assertEquals(
                    "",
                    wsm.readMemoryFileUtf8(RuntimeContext.empty(), "MEMORY.md"),
                    "the sandbox copy must never shadow the absent host copy (no adoption)");
            assertEquals(
                    1,
                    sandbox.commands.size(),
                    "exactly one sandbox interaction — the existence probe — is allowed on a"
                            + " host miss; a content read would surface here too, got: "
                            + sandbox.commands);
        }
    }

    /**
     * Minimal in-memory persistent backend for the routed test. Paths arrive prefix-stripped
     * from {@code CompositeFilesystem} routing, so keys are stored as received — assertions go
     * through {@link WorkspaceManager} APIs and never inspect them directly.
     */
    private static final class MemoryDirStoreFs
            implements io.agentscope.harness.agent.filesystem.AbstractFilesystem {
        private final Map<String, String> files = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public io.agentscope.harness.agent.filesystem.model.ReadResult read(
                RuntimeContext rc, String filePath, int offset, int limit) {
            String content = files.get(normalize(filePath));
            return content == null
                    ? io.agentscope.harness.agent.filesystem.model.ReadResult.fail(
                            "not found: " + filePath)
                    : io.agentscope.harness.agent.filesystem.model.ReadResult.success(
                            new io.agentscope.harness.agent.filesystem.model.FileData(
                                    content, "utf-8"));
        }

        @Override
        public List<io.agentscope.harness.agent.filesystem.model.FileUploadResponse> uploadFiles(
                RuntimeContext rc, List<Map.Entry<String, byte[]>> filesToUpload) {
            List<io.agentscope.harness.agent.filesystem.model.FileUploadResponse> results =
                    new java.util.ArrayList<>();
            for (Map.Entry<String, byte[]> f : filesToUpload) {
                files.put(
                        normalize(f.getKey()),
                        new String(f.getValue(), java.nio.charset.StandardCharsets.UTF_8));
                results.add(
                        io.agentscope.harness.agent.filesystem.model.FileUploadResponse.success(
                                f.getKey()));
            }
            return results;
        }

        @Override
        public io.agentscope.harness.agent.filesystem.model.WriteResult write(
                RuntimeContext rc, String filePath, String content) {
            files.put(normalize(filePath), content);
            return new io.agentscope.harness.agent.filesystem.model.WriteResult(filePath, null);
        }

        @Override
        public io.agentscope.harness.agent.filesystem.model.GlobResult glob(
                RuntimeContext rc, String pattern, String path) {
            String base = normalize(path);
            List<io.agentscope.harness.agent.filesystem.model.FileInfo> matches =
                    new java.util.ArrayList<>();
            for (Map.Entry<String, String> e : files.entrySet()) {
                if ((base.isEmpty() || e.getKey().startsWith(base + "/"))
                        && e.getKey().endsWith(".md")) {
                    // Routed backends report leading-slash paths; CompositeFilesystem
                    // prependRoute re-joins the route prefix onto them.
                    matches.add(
                            io.agentscope.harness.agent.filesystem.model.FileInfo.ofFile(
                                    "/" + e.getKey(),
                                    e.getValue().length(),
                                    System.currentTimeMillis()));
                }
            }
            return io.agentscope.harness.agent.filesystem.model.GlobResult.success(matches);
        }

        @Override
        public boolean exists(RuntimeContext rc, String path) {
            return files.containsKey(normalize(path));
        }

        private static String normalize(String p) {
            String s = p == null ? "" : p;
            while (s.startsWith("/")) {
                s = s.substring(1);
            }
            return s;
        }

        // Unused in these tests.
        @Override
        public io.agentscope.harness.agent.filesystem.model.LsResult ls(
                RuntimeContext rc, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.agentscope.harness.agent.filesystem.model.EditResult edit(
                RuntimeContext rc, String filePath, String old, String newStr, boolean all) {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.agentscope.harness.agent.filesystem.model.GrepResult grep(
                RuntimeContext rc, String pattern, String path, String glob) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<io.agentscope.harness.agent.filesystem.model.FileDownloadResponse>
                downloadFiles(RuntimeContext rc, List<String> paths) {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.agentscope.harness.agent.filesystem.model.WriteResult delete(
                RuntimeContext rc, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.agentscope.harness.agent.filesystem.model.WriteResult move(
                RuntimeContext rc, String fromPath, String toPath) {
            throw new UnsupportedOperationException();
        }
    }

    /** Test-only {@link io.agentscope.harness.agent.sandbox.Sandbox} recording every exec. */
    private static final class ProbeRecordingSandbox
            implements io.agentscope.harness.agent.sandbox.Sandbox {
        final List<String> commands = new java.util.ArrayList<>();
        private final String probeAnswer;

        ProbeRecordingSandbox(String probeAnswer) {
            this.probeAnswer = probeAnswer;
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}

        @Override
        public void shutdown() {}

        @Override
        public void close() {}

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public io.agentscope.harness.agent.sandbox.SandboxState getState() {
            return null;
        }

        @Override
        public io.agentscope.harness.agent.sandbox.ExecResult exec(
                RuntimeContext rc, String command, Integer timeoutSeconds) {
            commands.add(command);
            return new io.agentscope.harness.agent.sandbox.ExecResult(0, probeAnswer, "", false);
        }

        @Override
        public java.io.InputStream persistWorkspace() {
            return java.io.InputStream.nullInputStream();
        }

        @Override
        public void hydrateWorkspace(java.io.InputStream archive) {}
    }
}
