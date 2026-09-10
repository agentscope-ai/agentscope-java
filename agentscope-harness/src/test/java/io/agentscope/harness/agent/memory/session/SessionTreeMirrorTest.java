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
package io.agentscope.harness.agent.memory.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.BakedContextFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxMirrorReleaseCoordinator;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.transcript.ObjectStoreTranscriptStore;
import io.agentscope.harness.agent.transcript.TranscriptRef;
import io.agentscope.harness.agent.transcript.TranscriptStore;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionTreeMirrorTest {

    @TempDir Path workspace;

    @BeforeEach
    void setUpCoordinator() {
        SandboxMirrorReleaseCoordinator.resetForTests();
    }

    @AfterEach
    void tearDownCoordinator() {
        SandboxMirrorReleaseCoordinator.resetForTests();
    }

    private AbstractFilesystem buildFs(InMemoryStore store) {
        AbstractFilesystem raw =
                new RemoteFilesystemSpec(store)
                        .toFilesystem(workspace, "agent-a", rc -> List.of("user-1"));
        // Bake user-1 into the RC so that storeNamespace resolves to user-1 even when SessionTree
        // calls without an explicit runtime context.
        return new BakedContextFilesystem(raw, RuntimeContext.builder().userId("user-1").build());
    }

    // -----------------------------------------------------------------------
    //  load(): local-only, cold-start restore when local file is absent
    // -----------------------------------------------------------------------

    @Test
    void load_restoresFromRemote_whenLocalFilesMissing() throws Exception {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem fs = buildFs(store);
        Path context = workspace.resolve("agents/agent-a/sessions/s1.jsonl");

        // Machine A writes and flushes (mirrors to remote)
        SessionTree writer = new SessionTree(context, workspace, fs);
        writer.append(new SessionEntry.MessageEntry(null, null, null, "USER", "hello", null));
        writer.flush();
        awaitMirror();

        // Delete local files — simulate cold-start on a new machine
        Files.deleteIfExists(context);
        Files.deleteIfExists(writer.getLogFile());

        // Machine B: load() alone should restore from remote (cold-start path)
        SessionTree reader = new SessionTree(context, workspace, fs);
        reader.load();

        assertEquals(1, reader.size(), "load() should restore from remote when local is absent");
        assertTrue(Files.isRegularFile(context), "local file should be recreated");
    }

    @Test
    void load_readsLocalOnly_whenLocalFileExists() throws Exception {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem fs = buildFs(store);
        Path context = workspace.resolve("agents/agent-a/sessions/s1b.jsonl");

        // Write two entries; mirror first entry to remote, keep second local-only
        SessionTree t1 = new SessionTree(context, workspace, fs);
        t1.append(new SessionEntry.MessageEntry(null, null, null, "USER", "entry-1", null));
        t1.flush();
        awaitMirror(); // remote has entry-1

        SessionTree t2 = new SessionTree(context, workspace, fs);
        t2.load(); // local has entry-1
        t2.append(new SessionEntry.MessageEntry(null, null, null, "ASSISTANT", "entry-2", null));
        t2.flush(); // local has entry-1+2; async mirror not awaited

        // A new SessionTree.load() on the same local file should see both entries immediately
        // (local has them) WITHOUT touching remote.
        SessionTree t3 = new SessionTree(context, workspace, fs);
        t3.load();
        assertEquals(2, t3.size(), "load() should read both entries from local cache");
    }

    // -----------------------------------------------------------------------
    //  syncFromRemote(): cross-machine handoff
    // -----------------------------------------------------------------------

    @Test
    void syncFromRemote_mergesRemoteAheadEntries_intoStaleLocal() throws Exception {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem fs = buildFs(store);
        Path context = workspace.resolve("agents/agent-a/sessions/s2.jsonl");

        // Round 1 — Machine A: write, flush, mirror to remote
        SessionTree machineA = new SessionTree(context, workspace, fs);
        machineA.append(new SessionEntry.MessageEntry(null, null, null, "USER", "round-1", null));
        machineA.flush();
        awaitMirror(); // remote: round-1

        // Simulate machine B having a stale local: only round-1 on disk.
        // Machine A then writes round-2 and mirrors.
        SessionTree machineA2 = new SessionTree(context, workspace, fs);
        machineA2.load();
        machineA2.append(
                new SessionEntry.MessageEntry(null, null, null, "ASSISTANT", "round-2", null));
        machineA2.flush();
        awaitMirror(); // remote: round-1 + round-2

        // Truncate local to only round-1 (simulate stale machine B)
        List<String> lines = Files.readAllLines(context);
        Files.writeString(context, lines.get(0) + "\n");

        // Machine B: load() sees local (stale, round-1 only)
        SessionTree machineB = new SessionTree(context, workspace, fs);
        machineB.load();
        assertEquals(1, machineB.size(), "load() should only see local (stale) content");

        // syncFromRemote() picks up round-2 from remote
        machineB.syncFromRemote();
        assertEquals(2, machineB.size(), "syncFromRemote() should union-merge round-2 from remote");
    }

    @Test
    void syncFromRemote_preservesLocalOnlyEntries_notYetPushed() throws Exception {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem fs = buildFs(store);
        Path context = workspace.resolve("agents/agent-a/sessions/s3.jsonl");

        // Write entry-1 and mirror
        SessionTree t1 = new SessionTree(context, workspace, fs);
        t1.append(new SessionEntry.MessageEntry(null, null, null, "USER", "entry-1", null));
        t1.flush();
        awaitMirror(); // remote: entry-1

        // Write entry-2 locally but don't wait for mirror (still in-flight)
        SessionTree t2 = new SessionTree(context, workspace, fs);
        t2.load();
        t2.append(new SessionEntry.MessageEntry(null, null, null, "ASSISTANT", "entry-2", null));
        t2.flush(); // local: entry-1+2; remote may still only have entry-1

        // Load a fresh tree — local has entry-1+2
        SessionTree t3 = new SessionTree(context, workspace, fs);
        t3.load();
        assertEquals(2, t3.size());

        // syncFromRemote() — remote has entry-1 only; local-only entry-2 must survive
        t3.syncFromRemote();
        assertEquals(2, t3.size(), "local-only entry-2 must survive union-merge");
        assertTrue(
                t3.getMessageEntries().stream().anyMatch(m -> "entry-2".equals(m.getContent())),
                "entry-2 must be present after sync");
    }

    @Test
    void syncFromRemote_isNoOp_whenFilesystemIsNull() throws Exception {
        Path context = workspace.resolve("agents/agent-a/sessions/s4.jsonl");
        SessionTree tree = new SessionTree(context, workspace, null);
        tree.append(new SessionEntry.MessageEntry(null, null, null, "USER", "local", null));
        tree.flush();

        SessionTree reader = new SessionTree(context, workspace, null);
        reader.load();
        reader.syncFromRemote(); // must not throw
        assertEquals(1, reader.size());
    }

    // -----------------------------------------------------------------------
    //  flush(): local write is synchronous, remote mirror is async
    // -----------------------------------------------------------------------

    @Test
    void flush_localWriteCompletesImmediately() throws Exception {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem fs = buildFs(store);
        Path context = workspace.resolve("agents/agent-a/sessions/s5.jsonl");

        SessionTree tree = new SessionTree(context, workspace, fs);
        tree.append(new SessionEntry.MessageEntry(null, null, null, "USER", "hi", null));
        tree.flush();

        assertTrue(
                Files.isRegularFile(context),
                "local context file must exist immediately after flush()");
        assertTrue(
                Files.isRegularFile(tree.getLogFile()),
                "local log file must exist immediately after flush()");
    }

    @Test
    void flush_withTranscriptStore_stillMirrorsCanonicalFiles() throws Exception {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem fs = buildFs(store);
        Path context = workspace.resolve("agents/agent-a/sessions/s-canon.jsonl");

        TranscriptStore segments = new ObjectStoreTranscriptStore(fs);
        SessionTree tree = new SessionTree(context, workspace, fs);
        tree.setRuntimeContext(RuntimeContext.builder().userId("user-1").build());
        tree.setTranscriptStore(segments, new TranscriptRef("tenant", "agent-a", "s-canon"));
        tree.append(new SessionEntry.MessageEntry(null, null, null, "USER", "hello", null));
        tree.flush();
        awaitMirror();

        assertTrue(
                Files.isRegularFile(tree.getLogFile()),
                "local canonical log must exist after flush");
        RuntimeContext rc = RuntimeContext.builder().userId("user-1").build();
        var logRead = fs.read(rc, "agents/agent-a/sessions/s-canon.log.jsonl", 0, 0);
        assertTrue(
                logRead.isSuccess()
                        && logRead.fileData() != null
                        && logRead.fileData().content() != null
                        && logRead.fileData().content().contains("hello"),
                "canonical .log.jsonl must be mirrored even when TranscriptStore is bound");
        var ctxRead = fs.read(rc, "agents/agent-a/sessions/s-canon.jsonl", 0, 0);
        assertTrue(
                ctxRead.isSuccess()
                        && ctxRead.fileData() != null
                        && ctxRead.fileData().content() != null
                        && ctxRead.fileData().content().contains("hello"),
                "canonical .jsonl must be mirrored even when TranscriptStore is bound");
    }

    @Test
    void flush_defersSelfManagedReleaseUntilMirrorCompletes() throws Exception {
        CountDownLatch uploadStarted = new CountDownLatch(1);
        CountDownLatch allowUpload = new CountDownLatch(1);
        AtomicInteger shutdownCalls = new AtomicInteger();
        BlockingTransferSandbox sandbox =
                new BlockingTransferSandbox(uploadStarted, allowUpload, shutdownCalls);

        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        filesystem.setSandbox(sandbox);
        Path context = workspace.resolve("agents/agent-a/sessions/deferred.jsonl");

        SandboxManager manager = mock(SandboxManager.class);
        doAnswer(
                        invocation -> {
                            SandboxAcquireResult result = invocation.getArgument(0);
                            result.getSandbox().stop();
                            result.getSandbox().shutdown();
                            return null;
                        })
                .when(manager)
                .release(any());

        SessionTree tree = new SessionTree(context, workspace, filesystem);
        RuntimeContext callRc = RuntimeContext.builder().userId("user-1").build();
        callRc.put(SandboxAcquireResult.class, SandboxAcquireResult.selfManaged(sandbox));
        tree.setRuntimeContext(callRc);
        tree.append(new SessionEntry.MessageEntry(null, null, null, "USER", "hello", null));
        tree.flush();

        assertTrue(uploadStarted.await(5, TimeUnit.SECONDS), "mirror should start upload");

        SandboxAcquireResult acquireResult = SandboxAcquireResult.selfManaged(sandbox);
        SandboxMirrorReleaseCoordinator.requestRelease(manager, acquireResult);

        assertEquals(
                0,
                shutdownCalls.get(),
                "self-managed shutdown must wait for outstanding session mirror");
        verify(manager, times(0)).release(any());

        allowUpload.countDown();
        assertTrue(SessionTree.awaitMirrorQuiescence(5, TimeUnit.SECONDS));

        assertEquals(1, shutdownCalls.get(), "shutdown runs after mirror finishes");
        verify(manager, times(1)).release(acquireResult);
    }

    @Test
    void flush_defersReleaseUntilBothSegmentAndCanonicalMirrorsComplete() throws Exception {
        // flush() schedules two pinned tasks when TranscriptStore is bound: segment + canonical.
        // Release must wait until both releaseMirror calls run.
        Semaphore uploadPermits = new Semaphore(0);
        AtomicInteger uploadsStarted = new AtomicInteger();
        AtomicInteger shutdownCalls = new AtomicInteger();
        BlockingTransferSandbox sandbox =
                new BlockingTransferSandbox(uploadPermits, uploadsStarted, shutdownCalls);

        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        filesystem.setSandbox(sandbox);
        Path context = workspace.resolve("agents/agent-a/sessions/dual-task.jsonl");

        SandboxManager manager = mock(SandboxManager.class);
        doAnswer(
                        invocation -> {
                            SandboxAcquireResult result = invocation.getArgument(0);
                            result.getSandbox().stop();
                            result.getSandbox().shutdown();
                            return null;
                        })
                .when(manager)
                .release(any());

        SessionTree tree = new SessionTree(context, workspace, filesystem);
        tree.setTranscriptStore(
                new ObjectStoreTranscriptStore(filesystem),
                new TranscriptRef("tenant", "agent-a", "dual-task"));
        tree.append(new SessionEntry.MessageEntry(null, null, null, "USER", "hello", null));
        tree.flush();

        SandboxAcquireResult acquireResult = SandboxAcquireResult.selfManaged(sandbox);
        SandboxMirrorReleaseCoordinator.requestRelease(manager, acquireResult);

        assertEquals(0, shutdownCalls.get(), "two retains must keep shutdown deferred");

        // Let the first mirror task finish one transfer; with a single-thread executor the
        // segment task typically runs first and needs one upload, then releaseMirror drops to 1.
        assertTrue(
                waitUntil(() -> uploadsStarted.get() >= 1, 5, TimeUnit.SECONDS),
                "at least one mirror upload should start");
        uploadPermits.release(1);
        assertTrue(
                waitUntil(() -> uploadsStarted.get() >= 2, 5, TimeUnit.SECONDS),
                "second mirror task should start while release is still deferred");
        assertEquals(
                0,
                shutdownCalls.get(),
                "shutdown must still wait for the second outstanding mirror task");

        uploadPermits.release(8);
        assertTrue(SessionTree.awaitMirrorQuiescence(5, TimeUnit.SECONDS));
        assertEquals(1, shutdownCalls.get(), "shutdown after both mirror tasks finish");
        verify(manager, times(1)).release(acquireResult);
    }

    @Test
    void flush_retainsPerCallSandboxFromFsRc_notSharedProxyField() throws Exception {
        // Concurrent distinct sessions share one SandboxBackedFilesystem proxy field (last-writer
        // wins). Pin/retain must follow SandboxAcquireResult on fsRc (#2490), not that field —
        // otherwise retain lands on the sibling sandbox and this call's release is immediate.
        CountDownLatch uploadStarted = new CountDownLatch(1);
        CountDownLatch allowUpload = new CountDownLatch(1);
        AtomicInteger shutdownA = new AtomicInteger();
        AtomicInteger shutdownB = new AtomicInteger();
        BlockingTransferSandbox sandboxA =
                new BlockingTransferSandbox(uploadStarted, allowUpload, shutdownA);
        BlockingTransferSandbox sandboxB =
                new BlockingTransferSandbox(
                        new CountDownLatch(1), new CountDownLatch(0), shutdownB);

        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        // Sibling call "won" the shared fallback slot.
        filesystem.setSandbox(sandboxB);
        Path context = workspace.resolve("agents/agent-a/sessions/per-call-pin.jsonl");

        SandboxManager manager = mock(SandboxManager.class);
        doAnswer(
                        invocation -> {
                            SandboxAcquireResult result = invocation.getArgument(0);
                            result.getSandbox().stop();
                            result.getSandbox().shutdown();
                            return null;
                        })
                .when(manager)
                .release(any());

        SessionTree tree = new SessionTree(context, workspace, filesystem);
        RuntimeContext callRc =
                RuntimeContext.builder().userId("user-a").sessionId("sess-a").build();
        callRc.put(SandboxAcquireResult.class, SandboxAcquireResult.selfManaged(sandboxA));
        tree.setRuntimeContext(callRc);
        tree.append(new SessionEntry.MessageEntry(null, null, null, "USER", "hello", null));
        tree.flush();

        assertTrue(
                uploadStarted.await(5, TimeUnit.SECONDS),
                "mirror must upload via per-call sandbox A, not shared-field sandbox B");

        SandboxAcquireResult resultA = SandboxAcquireResult.selfManaged(sandboxA);
        SandboxAcquireResult resultB = SandboxAcquireResult.selfManaged(sandboxB);
        SandboxMirrorReleaseCoordinator.requestRelease(manager, resultA);
        SandboxMirrorReleaseCoordinator.requestRelease(manager, resultB);

        assertEquals(0, shutdownA.get(), "call A still has an outstanding mirror retain");
        assertEquals(
                1,
                shutdownB.get(),
                "call B has no retain from A's flush — release must not be deferred on B");
        verify(manager, times(1)).release(resultB);
        verify(manager, times(0)).release(resultA);

        allowUpload.countDown();
        assertTrue(SessionTree.awaitMirrorQuiescence(5, TimeUnit.SECONDS));
        assertEquals(1, shutdownA.get(), "call A shuts down after its mirror finishes");
        verify(manager, times(1)).release(resultA);
    }

    // -----------------------------------------------------------------------
    //  Helper
    // -----------------------------------------------------------------------

    private static void awaitMirror() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(300);
    }

    private static boolean waitUntil(
            java.util.function.BooleanSupplier condition, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private static final class BlockingTransferSandbox implements Sandbox, SandboxFileTransfer {

        private final CountDownLatch legacyUploadStarted;
        private final CountDownLatch legacyAllowUpload;
        private final Semaphore uploadPermits;
        private final AtomicInteger uploadsStarted;
        private final AtomicInteger shutdownCalls;
        private volatile boolean running = true;

        private BlockingTransferSandbox(
                CountDownLatch uploadStarted,
                CountDownLatch allowUpload,
                AtomicInteger shutdownCalls) {
            this.legacyUploadStarted = uploadStarted;
            this.legacyAllowUpload = allowUpload;
            this.uploadPermits = null;
            this.uploadsStarted = null;
            this.shutdownCalls = shutdownCalls;
        }

        private BlockingTransferSandbox(
                Semaphore uploadPermits,
                AtomicInteger uploadsStarted,
                AtomicInteger shutdownCalls) {
            this.legacyUploadStarted = null;
            this.legacyAllowUpload = null;
            this.uploadPermits = uploadPermits;
            this.uploadsStarted = uploadsStarted;
            this.shutdownCalls = shutdownCalls;
        }

        @Override
        public void start() {}

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public void shutdown() {
            shutdownCalls.incrementAndGet();
            running = false;
        }

        @Override
        public void close() {}

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public SandboxState getState() {
            return null;
        }

        @Override
        public ExecResult exec(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            throw new AssertionError("not used");
        }

        @Override
        public InputStream persistWorkspace() {
            throw new AssertionError("not used");
        }

        @Override
        public void hydrateWorkspace(InputStream archive) {
            // Archive fallback when workspace root is unavailable — still holds the mirror gate.
            blockUntilAllowed();
        }

        @Override
        public boolean supportsFileTransfer(String absolutePath) {
            return true;
        }

        @Override
        public void uploadFile(String absolutePath, byte[] content) {
            blockUntilAllowed();
        }

        @Override
        public byte[] downloadFile(String absolutePath) {
            throw new AssertionError("not used");
        }

        private void blockUntilAllowed() {
            if (uploadPermits != null) {
                uploadsStarted.incrementAndGet();
                try {
                    if (!uploadPermits.tryAcquire(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting for upload permit");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return;
            }
            legacyUploadStarted.countDown();
            try {
                if (!legacyAllowUpload.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to finish upload");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
