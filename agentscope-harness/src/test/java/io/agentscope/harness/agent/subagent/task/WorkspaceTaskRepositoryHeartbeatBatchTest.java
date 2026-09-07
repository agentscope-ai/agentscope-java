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
package io.agentscope.harness.agent.subagent.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Batching contract for the heartbeat's task-record persistence.
 *
 * <p>Every heartbeat refresh used to run one full read-modify-write of the session's task store
 * <em>per running task</em>: with {@code R} running tasks sharing one store file the sweeper-scale
 * cost is {@code O(R x store)} parses, serializations and whole-file writes per 30s cycle. The
 * heartbeat now batches all refreshes that resolve to the same store file into a single
 * read-modify-write. These tests pin the batching by counting backend writes, and lock the
 * behavioural contracts that must not change: all tasks are still refreshed, terminal records
 * are never clobbered, and per-task {@link RuntimeContext} instances that resolve to the same
 * store file still share one batch.
 */
class WorkspaceTaskRepositoryHeartbeatBatchTest {

    @TempDir java.nio.file.Path tempDir;

    private WorkspaceTaskRepository repo;
    private WorkspaceManager workspaceManager;
    private CountingMapFs persistent;

    @AfterEach
    void tearDown() throws Exception {
        if (repo != null) {
            repo.shutdown();
        }
        if (workspaceManager != null) {
            workspaceManager.close();
        }
    }

    @Test
    @DisplayName("heartbeat persists one store write per session file, not per task")
    void heartbeat_batchesRefreshesPerSessionFile() throws Exception {
        assembleRouted();
        CountDownLatch running = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 1; i <= 3; i++) {
                // Distinct RuntimeContext instances on purpose: batching groups by resolved
                // store path, not by context identity.
                repo.putTask(
                        RuntimeContext.empty(),
                        "task-" + i,
                        "sub-" + i,
                        "sess",
                        blockingSpec(running, release));
            }
            assertTrue(running.await(5, TimeUnit.SECONDS), "tasks never started");
            awaitWriteQuiescence();

            long before = persistent.writesOf("test-agent/tasks/sess.json");
            repo.heartbeat();

            assertEquals(
                    before + 1,
                    persistent.writesOf("test-agent/tasks/sess.json"),
                    "three running tasks in one session file must produce exactly one store"
                            + " write per heartbeat cycle, not one per task");

            for (int i = 1; i <= 3; i++) {
                Optional<TaskRecord> record =
                        workspaceManager.readTaskRecord(
                                RuntimeContext.empty(), "test-agent", "sess", "task-" + i);
                assertTrue(record.isPresent(), "task-" + i + " record must survive batching");
                assertEquals(TaskStatus.RUNNING, record.get().getStatus());
                assertTrue(
                        record.get().getLastUpdatedAt().isAfter(Instant.now().minusSeconds(60)),
                        "task-" + i + " lastUpdatedAt must be refreshed by the heartbeat");
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("heartbeat writes once per session file across different sessions")
    void heartbeat_oneWritePerSessionFileAcrossSessions() throws Exception {
        assembleRouted();
        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            repo.putTask(
                    RuntimeContext.empty(),
                    "task-a1",
                    "sub-a1",
                    "sess-a",
                    blockingSpec(running, release));
            repo.putTask(
                    RuntimeContext.empty(),
                    "task-a2",
                    "sub-a2",
                    "sess-a",
                    blockingSpec(running, release));
            repo.putTask(
                    RuntimeContext.empty(),
                    "task-b1",
                    "sub-b1",
                    "sess-b",
                    blockingSpec(running, release));
            assertTrue(running.await(5, TimeUnit.SECONDS), "tasks never started");
            awaitWriteQuiescence();

            long beforeA = persistent.writesOf("test-agent/tasks/sess-a.json");
            long beforeB = persistent.writesOf("test-agent/tasks/sess-b.json");
            repo.heartbeat();

            assertEquals(
                    beforeA + 1,
                    persistent.writesOf("test-agent/tasks/sess-a.json"),
                    "sess-a holds two running tasks and must be written once");
            assertEquals(
                    beforeB + 1,
                    persistent.writesOf("test-agent/tasks/sess-b.json"),
                    "sess-b holds one running task and must be written once");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("heartbeat never clobbers terminal records while batching")
    void heartbeat_doesNotClobberTerminalRecords() throws Exception {
        assembleRouted();
        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            repo.putTask(
                    RuntimeContext.empty(),
                    "task-1",
                    "sub-1",
                    "sess",
                    blockingSpec(running, release));
            repo.putTask(
                    RuntimeContext.empty(),
                    "task-2",
                    "sub-2",
                    "sess",
                    blockingSpec(running, release));
            assertTrue(running.await(5, TimeUnit.SECONDS), "tasks never started");

            // Take task-1 terminal behind the repository's back, then heartbeat.
            TaskRecord terminal =
                    workspaceManager
                            .readTaskRecord(RuntimeContext.empty(), "test-agent", "sess", "task-1")
                            .orElseThrow();
            terminal.setStatus(TaskStatus.COMPLETED);
            workspaceManager.writeTaskRecord(
                    RuntimeContext.empty(), "test-agent", "sess", terminal);
            Instant terminalStamp =
                    workspaceManager
                            .readTaskRecord(RuntimeContext.empty(), "test-agent", "sess", "task-1")
                            .orElseThrow()
                            .getLastUpdatedAt();

            repo.heartbeat();

            TaskRecord after =
                    workspaceManager
                            .readTaskRecord(RuntimeContext.empty(), "test-agent", "sess", "task-1")
                            .orElseThrow();
            assertEquals(
                    TaskStatus.COMPLETED,
                    after.getStatus(),
                    "terminal records are immutable and must be skipped by the heartbeat");
            assertEquals(
                    terminalStamp,
                    after.getLastUpdatedAt(),
                    "a skipped terminal record must not even be touched");
            TaskRecord survivor =
                    workspaceManager
                            .readTaskRecord(RuntimeContext.empty(), "test-agent", "sess", "task-2")
                            .orElseThrow();
            assertEquals(
                    TaskStatus.RUNNING,
                    survivor.getStatus(),
                    "non-terminal siblings must still be refreshed in the same batch");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("a grouping failure skips one task but never kills the heartbeat")
    void heartbeat_survivesGroupingFailure() throws Exception {
        // The backend's storageKey throws on its second resolution: one task's grouping
        // fails, the other must still be batched and persisted. Before per-task isolation
        // this escaped the forEach and permanently suppressed every later heartbeat on the
        // scheduler.
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        CountingMapFs flakyKey =
                new CountingMapFs() {
                    @Override
                    public Object storageKey(RuntimeContext rc, String path) {
                        if (calls.incrementAndGet() == 2) {
                            throw new IllegalStateException("storage key resolution boom");
                        }
                        return super.storageKey(rc, path);
                    }
                };
        RoutedSandboxFilesystem routed =
                new RoutedSandboxFilesystem(
                        new SandboxBackedFilesystem(), java.util.Map.of("agents/", flakyKey));
        workspaceManager = new WorkspaceManager(tempDir, routed);
        repo = WorkspaceTaskRepository.forTests(workspaceManager, "test-agent");

        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 1; i <= 2; i++) {
                repo.putTask(
                        RuntimeContext.empty(),
                        "task-" + i,
                        "sub-" + i,
                        "sess",
                        blockingSpec(running, release));
            }
            assertTrue(running.await(5, TimeUnit.SECONDS), "tasks never started");

            java.util.Map<String, Instant> stampsBefore = new java.util.LinkedHashMap<>();
            for (int i = 1; i <= 2; i++) {
                stampsBefore.put(
                        "task-" + i,
                        workspaceManager
                                .readTaskRecord(
                                        RuntimeContext.empty(), "test-agent", "sess", "task-" + i)
                                .orElseThrow()
                                .getLastUpdatedAt());
            }
            Thread.sleep(50); // guarantee the clock advances past the seeded stamps

            repo.heartbeat(); // must not throw

            int refreshed = 0;
            for (int i = 1; i <= 2; i++) {
                Optional<TaskRecord> record =
                        workspaceManager.readTaskRecord(
                                RuntimeContext.empty(), "test-agent", "sess", "task-" + i);
                assertTrue(record.isPresent(), "task-" + i + " record must survive");
                if (record.get().getLastUpdatedAt().isAfter(stampsBefore.get("task-" + i))) {
                    refreshed++;
                }
            }
            assertEquals(
                    1,
                    refreshed,
                    "exactly the successfully grouped task must be refreshed; the failed one"
                            + " is skipped for this cycle only");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("heartbeat does not refresh records with a pending cancellation request")
    void heartbeat_doesNotRefreshCancelRequestedRecords() throws Exception {
        assembleRouted();
        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            repo.putTask(
                    RuntimeContext.empty(),
                    "task-1",
                    "sub-1",
                    "sess",
                    blockingSpec(running, release));
            repo.putTask(
                    RuntimeContext.empty(),
                    "task-2",
                    "sub-2",
                    "sess",
                    blockingSpec(running, release));
            assertTrue(running.await(5, TimeUnit.SECONDS), "tasks never started");

            TaskRecord cancelling =
                    workspaceManager
                            .readTaskRecord(RuntimeContext.empty(), "test-agent", "sess", "task-1")
                            .orElseThrow();
            cancelling.setCancelRequested(true);
            workspaceManager.writeTaskRecord(
                    RuntimeContext.empty(), "test-agent", "sess", cancelling);
            Instant cancelStamp =
                    workspaceManager
                            .readTaskRecord(RuntimeContext.empty(), "test-agent", "sess", "task-1")
                            .orElseThrow()
                            .getLastUpdatedAt();

            repo.heartbeat();

            TaskRecord after =
                    workspaceManager
                            .readTaskRecord(RuntimeContext.empty(), "test-agent", "sess", "task-1")
                            .orElseThrow();
            assertTrue(after.isCancelRequested(), "the cancellation flag must survive");
            assertEquals(
                    cancelStamp,
                    after.getLastUpdatedAt(),
                    "a record with a pending cancellation request must refuse non-terminal"
                            + " refreshes, without even being touched");
            TaskRecord survivor =
                    workspaceManager
                            .readTaskRecord(RuntimeContext.empty(), "test-agent", "sess", "task-2")
                            .orElseThrow();
            assertEquals(
                    TaskStatus.RUNNING,
                    survivor.getStatus(),
                    "siblings without a cancellation request must still be refreshed");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("heartbeat never reinitializes a corrupt store while batching")
    void heartbeat_doesNotOverwriteCorruptStore() throws Exception {
        assembleRouted();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            repo.putTask(
                    RuntimeContext.empty(),
                    "task-1",
                    "sub-1",
                    "sess",
                    blockingSpec(running, release));
            assertTrue(running.await(5, TimeUnit.SECONDS), "task never started");

            String corrupt = "{\"task-1\": {\"brok";
            persistent.putRaw("test-agent/tasks/sess.json", corrupt);
            long writesBefore = persistent.writesOf("test-agent/tasks/sess.json");

            repo.heartbeat();

            assertEquals(
                    corrupt,
                    persistent.contentOf("test-agent/tasks/sess.json"),
                    "a failed parse must abort the batched write — never overwrite a malformed"
                            + " store with partial data");
            assertEquals(
                    writesBefore,
                    persistent.writesOf("test-agent/tasks/sess.json"),
                    "no write may reach the backend when the store cannot be parsed");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("batched status update skips invalid entries instead of failing")
    void updateTaskRecordStatuses_skipsInvalidEntries() throws Exception {
        assembleRouted();
        WorkspaceManager wm = workspaceManager;

        TaskRecord seeded = new TaskRecord("task-1", "sub-1", "test-agent", "sess", null);
        seeded.setStatus(TaskStatus.RUNNING);
        seeded.setLastUpdatedAt(Instant.now().minusSeconds(60));
        wm.writeTaskRecord(RuntimeContext.empty(), "test-agent", "sess", seeded);
        // writeTaskRecord touches the record, so the persisted stamp is the write-time now —
        // read it back as the baseline the batched refresh must advance past.
        Instant persistedStamp =
                wm.readTaskRecord(RuntimeContext.empty(), "test-agent", "sess", "task-1")
                        .orElseThrow()
                        .getLastUpdatedAt();
        Thread.sleep(50); // guarantee the clock advances past the persisted stamp

        java.util.Map<String, TaskStatus> mixed = new java.util.LinkedHashMap<>();
        mixed.put("task-1", TaskStatus.RUNNING);
        mixed.put("", TaskStatus.RUNNING); // blank task ID
        mixed.put(null, TaskStatus.RUNNING); // null task ID
        mixed.put("task-2", null); // null status

        wm.updateTaskRecordStatuses(RuntimeContext.empty(), "test-agent", "sess", mixed);

        TaskRecord refreshed =
                wm.readTaskRecord(RuntimeContext.empty(), "test-agent", "sess", "task-1")
                        .orElseThrow();
        assertTrue(
                refreshed.getLastUpdatedAt().isAfter(persistedStamp),
                "the one valid entry must still be refreshed");
        assertTrue(
                wm.readTaskRecord(RuntimeContext.empty(), "test-agent", "sess", "task-2").isEmpty(),
                "an entry with a null status must be skipped, not persisted");
    }

    @Test
    @DisplayName("a context-free backend batches across contexts even with manager namespaces")
    void heartbeat_contextFreeBackend_mergesAcrossManagerNamespaces() throws Exception {
        // A manager-level NamespaceFactory does not partition task-record storage: the
        // routed persistent backend is context-free, so both contexts share one batch.
        io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory ns =
                rc ->
                        java.util.List.of(
                                "user", rc.getSessionId() == null ? "anon" : rc.getSessionId());
        persistent = new CountingMapFs();
        RoutedSandboxFilesystem routed =
                new RoutedSandboxFilesystem(
                        new SandboxBackedFilesystem(), java.util.Map.of("agents/", persistent));
        workspaceManager = new WorkspaceManager(tempDir, routed, null, ns);
        repo = WorkspaceTaskRepository.forTests(workspaceManager, "test-agent");

        RuntimeContext alice = RuntimeContext.builder().sessionId("alice").build();
        RuntimeContext bob = RuntimeContext.builder().sessionId("bob").build();
        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            repo.putTask(alice, "task-1", "sub-1", "sess", blockingSpec(running, release));
            repo.putTask(bob, "task-2", "sub-2", "sess", blockingSpec(running, release));
            assertTrue(running.await(5, TimeUnit.SECONDS), "tasks never started");
            awaitWriteQuiescence();

            long before = persistent.writesOf("test-agent/tasks/sess.json");

            repo.heartbeat();

            assertEquals(
                    before + 1,
                    persistent.writesOf("test-agent/tasks/sess.json"),
                    "a context-free backend serves one physical store, so both contexts must"
                            + " share a single batched write");
            for (int i = 1; i <= 2; i++) {
                assertTrue(
                        workspaceManager
                                .readTaskRecord(
                                        RuntimeContext.empty(), "test-agent", "sess", "task-" + i)
                                .isPresent(),
                        "task-" + i + " record must survive");
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("per-user remote namespaces never receive each other's task records")
    void heartbeat_userNamespacedRemoteStore_keepsRecordsPerUser() throws Exception {
        io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore store =
                new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem remoteFs =
                new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(
                        store,
                        rc ->
                                java.util.List.of(
                                        "user", rc.getUserId() == null ? "anon" : rc.getUserId()));
        workspaceManager = new WorkspaceManager(tempDir, remoteFs);
        repo = WorkspaceTaskRepository.forTests(workspaceManager, "test-agent");

        RuntimeContext alice = RuntimeContext.builder().userId("alice").build();
        RuntimeContext bob = RuntimeContext.builder().userId("bob").build();
        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            repo.putTask(alice, "task-1", "sub-1", "sess", blockingSpec(running, release));
            repo.putTask(bob, "task-2", "sub-2", "sess", blockingSpec(running, release));
            assertTrue(running.await(5, TimeUnit.SECONDS), "tasks never started");

            java.util.Map<String, Instant> stampsBefore = new java.util.LinkedHashMap<>();
            for (RuntimeContext view : new RuntimeContext[] {alice, bob}) {
                stampsBefore.put(
                        view.getUserId(),
                        workspaceManager
                                .readTaskRecord(
                                        view,
                                        "test-agent",
                                        "sess",
                                        "task-" + (view == alice ? 1 : 2))
                                .orElseThrow()
                                .getLastUpdatedAt());
            }
            Thread.sleep(50);

            repo.heartbeat();

            // Alice's namespace: her task refreshed, Bob's record must not leak into it.
            TaskRecord aliceOwn =
                    workspaceManager
                            .readTaskRecord(alice, "test-agent", "sess", "task-1")
                            .orElseThrow();
            assertTrue(
                    aliceOwn.getLastUpdatedAt().isAfter(stampsBefore.get("alice")),
                    "alice's own task must be refreshed in her namespace");
            assertFalse(
                    remoteStoreContains(store, alice, "task-2"),
                    "bob's task record must never be written into alice's namespace");
            assertFalse(
                    remoteStoreContains(store, bob, "task-1"),
                    "alice's task record must never be written into bob's namespace");
            // Bob's namespace: his task refreshed under his own context.
            TaskRecord bobOwn =
                    workspaceManager
                            .readTaskRecord(bob, "test-agent", "sess", "task-2")
                            .orElseThrow();
            assertTrue(
                    bobOwn.getLastUpdatedAt().isAfter(stampsBefore.get("bob")),
                    "bob's own task must be refreshed in his own namespace");
        } finally {
            release.countDown();
        }
    }

    private static boolean remoteStoreContains(
            io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore store,
            RuntimeContext user,
            String taskId) {
        io.agentscope.harness.agent.filesystem.remote.store.StoreItem item =
                store.get(
                        java.util.List.of("user", user.getUserId()),
                        "agents/test-agent/tasks/sess.json");
        return item != null && String.valueOf(item.value()).contains("\"" + taskId + "\"");
    }

    @Test
    @DisplayName("a baked-context view batches across caller contexts")
    void heartbeat_bakedContextView_batchesAcrossCallerContexts() throws Exception {
        // BakedContextFilesystem substitutes its baked context on every delegated call, so the
        // storage identity follows the baked context — distinct caller contexts must share one
        // batch instead of being split per caller rc instance.
        persistent = new CountingMapFs();
        io.agentscope.harness.agent.filesystem.BakedContextFilesystem baked =
                new io.agentscope.harness.agent.filesystem.BakedContextFilesystem(
                        persistent, RuntimeContext.builder().userId("baked-user").build());
        workspaceManager = new WorkspaceManager(tempDir, baked);
        repo = WorkspaceTaskRepository.forTests(workspaceManager, "test-agent");

        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // Distinct caller contexts on purpose.
            repo.putTask(
                    RuntimeContext.empty(),
                    "task-1",
                    "sub-1",
                    "sess",
                    blockingSpec(running, release));
            repo.putTask(
                    RuntimeContext.empty(),
                    "task-2",
                    "sub-2",
                    "sess",
                    blockingSpec(running, release));
            assertTrue(running.await(5, TimeUnit.SECONDS), "tasks never started");
            awaitWriteQuiescence();

            // No routing composite in front of the backend, so MapFs keys carry the full
            // workspace-relative path including the agents/ prefix.
            long before = persistent.writesOf("agents/test-agent/tasks/sess.json");

            repo.heartbeat();

            assertEquals(
                    before + 1,
                    persistent.writesOf("agents/test-agent/tasks/sess.json"),
                    "a baked-context view resolves storage under the baked context, so both"
                            + " caller contexts must share a single batched write");
            for (int i = 1; i <= 2; i++) {
                assertTrue(
                        workspaceManager
                                .readTaskRecord(
                                        RuntimeContext.empty(), "test-agent", "sess", "task-" + i)
                                .isPresent(),
                        "task-" + i + " record must survive");
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("per-user local namespaces never receive each other's task records")
    void heartbeat_userNamespacedLocalStore_keepsRecordsPerUser() throws Exception {
        java.nio.file.Path backend =
                java.nio.file.Files.createDirectories(tempDir.resolve("backend"));
        io.agentscope.harness.agent.filesystem.local.LocalFilesystem localFs =
                new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(
                        backend,
                        io.agentscope.harness.agent.workspace.LocalFsMode.ROOTED,
                        io.agentscope.harness.agent.workspace.PathPolicy.empty(),
                        10,
                        rc ->
                                java.util.List.of(
                                        "user-"
                                                + (rc.getUserId() == null
                                                        ? "anon"
                                                        : rc.getUserId())));
        workspaceManager = new WorkspaceManager(tempDir.resolve("ws"), localFs);
        repo = WorkspaceTaskRepository.forTests(workspaceManager, "test-agent");

        RuntimeContext alice = RuntimeContext.builder().userId("alice").build();
        RuntimeContext bob = RuntimeContext.builder().userId("bob").build();
        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            repo.putTask(alice, "task-1", "sub-1", "sess", blockingSpec(running, release));
            repo.putTask(bob, "task-2", "sub-2", "sess", blockingSpec(running, release));
            assertTrue(running.await(5, TimeUnit.SECONDS), "tasks never started");

            java.util.Map<String, Instant> stampsBefore = new java.util.LinkedHashMap<>();
            stampsBefore.put(
                    "alice",
                    workspaceManager
                            .readTaskRecord(alice, "test-agent", "sess", "task-1")
                            .orElseThrow()
                            .getLastUpdatedAt());
            stampsBefore.put(
                    "bob",
                    workspaceManager
                            .readTaskRecord(bob, "test-agent", "sess", "task-2")
                            .orElseThrow()
                            .getLastUpdatedAt());
            Thread.sleep(50);

            repo.heartbeat();

            TaskRecord aliceOwn =
                    workspaceManager
                            .readTaskRecord(alice, "test-agent", "sess", "task-1")
                            .orElseThrow();
            assertTrue(
                    aliceOwn.getLastUpdatedAt().isAfter(stampsBefore.get("alice")),
                    "alice's own task must be refreshed in her namespace directory");
            TaskRecord bobOwn =
                    workspaceManager
                            .readTaskRecord(bob, "test-agent", "sess", "task-2")
                            .orElseThrow();
            assertTrue(
                    bobOwn.getLastUpdatedAt().isAfter(stampsBefore.get("bob")),
                    "bob's own task must be refreshed in his own namespace directory");
            String aliceFile =
                    java.nio.file.Files.readString(
                            backend.resolve("user-alice/agents/test-agent/tasks/sess.json"),
                            java.nio.charset.StandardCharsets.UTF_8);
            String bobFile =
                    java.nio.file.Files.readString(
                            backend.resolve("user-bob/agents/test-agent/tasks/sess.json"),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(
                    aliceFile.contains("\"task-2\""),
                    "bob's task record must never be written into alice's namespace file");
            assertFalse(
                    bobFile.contains("\"task-1\""),
                    "alice's task record must never be written into bob's namespace file");
        } finally {
            release.countDown();
        }
    }

    private io.agentscope.harness.agent.filesystem.local.LocalFilesystem userNamespacedLocal(
            java.nio.file.Path backend) {
        return new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(
                backend,
                io.agentscope.harness.agent.workspace.LocalFsMode.ROOTED,
                io.agentscope.harness.agent.workspace.PathPolicy.empty(),
                10,
                rc ->
                        java.util.List.of(
                                "user-" + (rc.getUserId() == null ? "anon" : rc.getUserId())));
    }

    @Test
    @DisplayName("a routed store batches under the leaf's actual path semantics")
    void heartbeat_routedStore_followsLeafPathSemantics() throws Exception {
        // CompositeFilesystem hands the leaf a slash-prefixed backend path, and
        // LocalFilesystem deliberately skips namespace scoping for absolute-form paths —
        // so through a route, a namespaced leaf serves every context from one shared file.
        // The grouping key mirrors exactly that resolution (no key/IO divergence); this test
        // pins the composed behaviour end to end through a real routed LocalFilesystem.
        java.nio.file.Path backend =
                java.nio.file.Files.createDirectories(tempDir.resolve("backend"));
        RoutedSandboxFilesystem routed =
                new RoutedSandboxFilesystem(
                        new SandboxBackedFilesystem(),
                        java.util.Map.of("agents/", userNamespacedLocal(backend)));
        workspaceManager = new WorkspaceManager(tempDir.resolve("ws"), routed);
        repo = WorkspaceTaskRepository.forTests(workspaceManager, "test-agent");

        RuntimeContext alice = RuntimeContext.builder().userId("alice").build();
        RuntimeContext bob = RuntimeContext.builder().userId("bob").build();
        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            repo.putTask(alice, "task-1", "sub-1", "sess", blockingSpec(running, release));
            repo.putTask(bob, "task-2", "sub-2", "sess", blockingSpec(running, release));
            assertTrue(running.await(5, TimeUnit.SECONDS), "tasks never started");

            java.util.Map<String, Instant> stampsBefore = new java.util.LinkedHashMap<>();
            stampsBefore.put(
                    "alice",
                    workspaceManager
                            .readTaskRecord(alice, "test-agent", "sess", "task-1")
                            .orElseThrow()
                            .getLastUpdatedAt());
            stampsBefore.put(
                    "bob",
                    workspaceManager
                            .readTaskRecord(bob, "test-agent", "sess", "task-2")
                            .orElseThrow()
                            .getLastUpdatedAt());
            Thread.sleep(50);

            repo.heartbeat();

            assertTrue(
                    workspaceManager
                            .readTaskRecord(alice, "test-agent", "sess", "task-1")
                            .orElseThrow()
                            .getLastUpdatedAt()
                            .isAfter(stampsBefore.get("alice")),
                    "alice's task must be refreshed through the routed backend");
            assertTrue(
                    workspaceManager
                            .readTaskRecord(bob, "test-agent", "sess", "task-2")
                            .orElseThrow()
                            .getLastUpdatedAt()
                            .isAfter(stampsBefore.get("bob")),
                    "bob's task must be refreshed through the routed backend");
            // The agents/ route prefix is stripped and the leaf receives a slash-prefixed
            // path, so namespace scoping is skipped by the leaf's own semantics: one shared
            // file holds both users' records, and the grouping key resolves identically.
            String sharedFile =
                    java.nio.file.Files.readString(
                            backend.resolve("test-agent/tasks/sess.json"),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(sharedFile.contains("\"task-1\""), "alice's record must be persisted");
            assertTrue(sharedFile.contains("\"task-2\""), "bob's record must be persisted");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("an overlay over a namespaced upper layer keeps per-user files isolated")
    void heartbeat_overlayNamespacedUpper_keepsRecordsPerUser() throws Exception {
        java.nio.file.Path upper = java.nio.file.Files.createDirectories(tempDir.resolve("upper"));
        java.nio.file.Path lower = java.nio.file.Files.createDirectories(tempDir.resolve("lower"));
        io.agentscope.harness.agent.filesystem.OverlayFilesystem overlay =
                new io.agentscope.harness.agent.filesystem.OverlayFilesystem(
                        userNamespacedLocal(upper),
                        new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(lower));
        workspaceManager = new WorkspaceManager(tempDir.resolve("ws"), overlay);
        repo = WorkspaceTaskRepository.forTests(workspaceManager, "test-agent");

        RuntimeContext alice = RuntimeContext.builder().userId("alice").build();
        RuntimeContext bob = RuntimeContext.builder().userId("bob").build();
        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            repo.putTask(alice, "task-1", "sub-1", "sess", blockingSpec(running, release));
            repo.putTask(bob, "task-2", "sub-2", "sess", blockingSpec(running, release));
            assertTrue(running.await(5, TimeUnit.SECONDS), "tasks never started");

            java.util.Map<String, Instant> stampsBefore = new java.util.LinkedHashMap<>();
            stampsBefore.put(
                    "alice",
                    workspaceManager
                            .readTaskRecord(alice, "test-agent", "sess", "task-1")
                            .orElseThrow()
                            .getLastUpdatedAt());
            stampsBefore.put(
                    "bob",
                    workspaceManager
                            .readTaskRecord(bob, "test-agent", "sess", "task-2")
                            .orElseThrow()
                            .getLastUpdatedAt());
            Thread.sleep(50);

            repo.heartbeat();

            assertTrue(
                    workspaceManager
                            .readTaskRecord(alice, "test-agent", "sess", "task-1")
                            .orElseThrow()
                            .getLastUpdatedAt()
                            .isAfter(stampsBefore.get("alice")),
                    "alice's task must be refreshed in the overlay's namespaced upper");
            assertTrue(
                    workspaceManager
                            .readTaskRecord(bob, "test-agent", "sess", "task-2")
                            .orElseThrow()
                            .getLastUpdatedAt()
                            .isAfter(stampsBefore.get("bob")),
                    "bob's task must be refreshed in the overlay's namespaced upper");
            String aliceFile =
                    java.nio.file.Files.readString(
                            upper.resolve("user-alice/agents/test-agent/tasks/sess.json"),
                            java.nio.charset.StandardCharsets.UTF_8);
            String bobFile =
                    java.nio.file.Files.readString(
                            upper.resolve("user-bob/agents/test-agent/tasks/sess.json"),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(aliceFile.contains("\"task-2\""), "no cross-user write into alice");
            assertFalse(bobFile.contains("\"task-1\""), "no cross-user write into bob");
        } finally {
            release.countDown();
        }
    }

    /**
     * Waits until backend write activity settles. A task supplier's own start-up RUNNING
     * transition writes asynchronously and can otherwise land inside a test's counting window.
     */
    private void awaitWriteQuiescence() throws InterruptedException {
        long previous = persistent.totalWrites();
        for (int i = 0; i < 25; i++) {
            Thread.sleep(100);
            long current = persistent.totalWrites();
            if (current == previous) {
                return;
            }
            previous = current;
        }
    }

    private TaskRunSpec blockingSpec(CountDownLatch running, CountDownLatch release) {
        return new TaskRunSpec.LocalTaskRunSpec(
                () -> {
                    running.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "done";
                });
    }

    private void assembleRouted() {
        persistent = new CountingMapFs();
        RoutedSandboxFilesystem routed =
                new RoutedSandboxFilesystem(
                        new SandboxBackedFilesystem(), Map.of("agents/", persistent));
        workspaceManager = new WorkspaceManager(tempDir, routed);
        repo = WorkspaceTaskRepository.forTests(workspaceManager, "test-agent");
    }

    /**
     * Minimal in-memory persistent backend that counts store writes per workspace-relative
     * path. Paths arrive prefix-stripped from {@code CompositeFilesystem} routing, so keys are
     * stored as received — the assertions go through the counters, never inspect them directly.
     */
    private static class CountingMapFs implements AbstractFilesystem {
        private final Map<String, String> files = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> writes = new ConcurrentHashMap<>();

        void putRaw(String path, String content) {
            files.put(normalize(path), content);
        }

        String contentOf(String path) {
            return files.get(normalize(path));
        }

        long totalWrites() {
            return writes.values().stream().mapToLong(AtomicInteger::get).sum();
        }

        int writesOf(String path) {
            AtomicInteger n = writes.get(normalize(path));
            return n == null ? 0 : n.get();
        }

        @Override
        public ReadResult read(RuntimeContext rc, String filePath, int offset, int limit) {
            String content = files.get(normalize(filePath));
            return content == null
                    ? ReadResult.fail("not found: " + filePath)
                    : ReadResult.success(new FileData(content, "utf-8"));
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext rc, List<Map.Entry<String, byte[]>> filesToUpload) {
            List<FileUploadResponse> results = new ArrayList<>();
            for (Map.Entry<String, byte[]> f : filesToUpload) {
                String key = normalize(f.getKey());
                files.put(key, new String(f.getValue(), StandardCharsets.UTF_8));
                writes.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                results.add(FileUploadResponse.success(f.getKey()));
            }
            return results;
        }

        @Override
        public GlobResult glob(RuntimeContext rc, String pattern, String path) {
            String base = normalize(path);
            List<FileInfo> matches = new ArrayList<>();
            for (Map.Entry<String, String> e : files.entrySet()) {
                if ((base.isEmpty() || e.getKey().startsWith(base + "/"))
                        && e.getKey().endsWith(".json")
                        && "*.json".equals(pattern)) {
                    matches.add(
                            FileInfo.ofFile(
                                    "/" + e.getKey(),
                                    e.getValue().length(),
                                    System.currentTimeMillis()));
                }
            }
            return GlobResult.success(matches);
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
        public LsResult ls(RuntimeContext rc, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WriteResult write(RuntimeContext rc, String filePath, String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EditResult edit(
                RuntimeContext rc, String filePath, String old, String newStr, boolean all) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GrepResult grep(RuntimeContext rc, String pattern, String path, String glob) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(RuntimeContext rc, List<String> paths) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WriteResult delete(RuntimeContext rc, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WriteResult move(RuntimeContext rc, String from, String to) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(RuntimeContext rc, String path) {
            return files.containsKey(normalize(path));
        }

        @Override
        public Object storageKey(RuntimeContext rc, String path) {
            return path;
        }
    }
}
