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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link WorkspaceTaskRepository}:
 *
 * <ul>
 *   <li>Workspace write on task creation and completion
 *   <li>Cross-node fallback: no local future → read terminal state from workspace
 *   <li>AgentStateStore-scope isolation: different sessionIds are independent
 *   <li>Cancel coordination: cancelRequested flag persisted to workspace
 *   <li>Compaction simulation: task_list reads from workspace even after localTasks cleared
 *   <li>Terminal status never overridden by RUNNING overlay
 *   <li>Mode 1: RemoteFilesystemSpec routes include tasks path
 * </ul>
 */
class WorkspaceTaskRepositoryTest {

    @TempDir Path tempDir;

    private WorkspaceManager workspaceManager;
    private WorkspaceTaskRepository repo;

    @BeforeEach
    void setUp() {
        workspaceManager = new WorkspaceManager(tempDir);
        repo = WorkspaceTaskRepository.forTests(workspaceManager, "test-agent");
    }

    @AfterEach
    void tearDown() {
        repo.shutdown();
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /** Polls until the condition is true or 5 seconds elapses. */
    private static void awaitCondition(ConditionSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.get()) {
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("Condition not met within 5 seconds");
            }
            Thread.sleep(50);
        }
    }

    @FunctionalInterface
    interface ConditionSupplier {
        boolean get() throws Exception;
    }

    // ------------------------------------------------------------------
    //  Basic workspace write
    // ------------------------------------------------------------------

    @Test
    @DisplayName("putTask writes TaskRecord to workspace with COMPLETED status on success")
    void putTask_writesRecordToWorkspace() throws Exception {
        String session = "sess-1";
        String taskId = "task-write-test";
        AtomicBoolean executed = new AtomicBoolean(false);

        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "sub-agent-x",
                session,
                new TaskRunSpec.LocalTaskRunSpec(
                        () -> {
                            executed.set(true);
                            return "done";
                        }));

        awaitCondition(
                () -> {
                    BackgroundTask t = repo.getTask(RuntimeContext.empty(), session, taskId);
                    return t != null && t.getTaskStatus().isTerminal();
                });

        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord(
                        RuntimeContext.empty(), "test-agent", session, taskId);
        assertTrue(record.isPresent());
        assertEquals(TaskStatus.COMPLETED, record.get().getStatus());
        assertEquals("done", record.get().getResult());
        assertTrue(executed.get());
    }

    @Test
    @DisplayName("putTask writes FAILED status when task throws")
    void putTask_writesFailedOnException() throws Exception {
        String session = "sess-fail";
        String taskId = "task-fail-test";

        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "sub-agent-fail",
                session,
                new TaskRunSpec.LocalTaskRunSpec(
                        () -> {
                            throw new RuntimeException("intentional failure");
                        }));

        awaitCondition(
                () -> {
                    BackgroundTask t = repo.getTask(RuntimeContext.empty(), session, taskId);
                    return t != null && t.getTaskStatus().isTerminal();
                });

        awaitCondition(
                () -> {
                    Optional<TaskRecord> r =
                            workspaceManager.readTaskRecord(
                                    RuntimeContext.empty(), "test-agent", session, taskId);
                    return r.isPresent() && r.get().getStatus().isTerminal();
                });

        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord(
                        RuntimeContext.empty(), "test-agent", session, taskId);
        assertTrue(record.isPresent());
        assertEquals(TaskStatus.FAILED, record.get().getStatus());
        assertTrue(record.get().getErrorMessage().contains("intentional failure"));
    }

    // ------------------------------------------------------------------
    //  Cross-node fallback: no local future
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getTask falls back to workspace when localTasks cleared (cross-node simulation)")
    void getTask_fallsBackToWorkspaceAfterLocalTasksCleared() throws Exception {
        String session = "sess-cross";
        String taskId = "task-cross-node";

        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "agent-y",
                session,
                new TaskRunSpec.LocalTaskRunSpec(() -> "cross-node result"));

        awaitCondition(
                () -> {
                    Optional<TaskRecord> r =
                            workspaceManager.readTaskRecord(
                                    RuntimeContext.empty(), "test-agent", session, taskId);
                    return r.isPresent() && r.get().getStatus().isTerminal();
                });

        // Simulate cross-node scenario: clear in-memory tasks
        repo.clear();

        BackgroundTask synthetic = repo.getTask(RuntimeContext.empty(), session, taskId);
        assertNotNull(synthetic);
        assertEquals(TaskStatus.COMPLETED, synthetic.getTaskStatus());
        assertEquals("cross-node result", synthetic.getResult());
    }

    @Test
    @DisplayName(
            "getTask returns null for unknown task on cross-node node without workspace record")
    void getTask_returnsNullWhenNothingFound() {
        assertNull(repo.getTask(RuntimeContext.empty(), "no-session", "no-task"));
    }

    // ------------------------------------------------------------------
    //  AgentStateStore-scope isolation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listTasks isolates tasks by sessionId")
    void listTasks_sessionIsolation() throws Exception {
        String sessionA = "sess-a";
        String sessionB = "sess-b";

        repo.putTask(
                RuntimeContext.empty(),
                "task-a1",
                "agent-a",
                sessionA,
                new TaskRunSpec.LocalTaskRunSpec(() -> "result-a"));
        repo.putTask(
                RuntimeContext.empty(),
                "task-b1",
                "agent-b",
                sessionB,
                new TaskRunSpec.LocalTaskRunSpec(() -> "result-b"));

        awaitCondition(
                () -> {
                    BackgroundTask a = repo.getTask(RuntimeContext.empty(), sessionA, "task-a1");
                    BackgroundTask b = repo.getTask(RuntimeContext.empty(), sessionB, "task-b1");
                    return a != null
                            && a.getTaskStatus().isTerminal()
                            && b != null
                            && b.getTaskStatus().isTerminal();
                });

        Collection<BackgroundTask> tasksA = repo.listTasks(RuntimeContext.empty(), sessionA, null);
        Collection<BackgroundTask> tasksB = repo.listTasks(RuntimeContext.empty(), sessionB, null);

        assertEquals(1, tasksA.size());
        assertEquals("task-a1", tasksA.iterator().next().getTaskId());

        assertEquals(1, tasksB.size());
        assertEquals("task-b1", tasksB.iterator().next().getTaskId());
    }

    // ------------------------------------------------------------------
    //  Cancel coordination
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cancelTask writes cancelRequested=true to workspace and marks CANCELLED")
    void cancelTask_writesCancelRequestedToWorkspace() throws Exception {
        String session = "sess-cancel";
        String taskId = "task-cancel";

        // Use latches so task is confirmed RUNNING before we cancel
        CountDownLatch taskRunning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "agent-slow",
                session,
                new TaskRunSpec.LocalTaskRunSpec(
                        () -> {
                            taskRunning.countDown();
                            try {
                                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return "slow result";
                        }));

        // Wait until task is confirmed RUNNING so workspace has a RUNNING record
        taskRunning.await(5, java.util.concurrent.TimeUnit.SECONDS);
        awaitCondition(
                () -> {
                    Optional<TaskRecord> r =
                            workspaceManager.readTaskRecord(
                                    RuntimeContext.empty(), "test-agent", session, taskId);
                    return r.isPresent() && r.get().getStatus() == TaskStatus.RUNNING;
                });

        boolean cancelled = repo.cancelTask(RuntimeContext.empty(), session, taskId);
        assertTrue(cancelled);

        // Read workspace before releasing the worker: once the latch opens, the async path
        // may persist COMPLETED and would race this assertion under full-suite load.
        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord(
                        RuntimeContext.empty(), "test-agent", session, taskId);
        assertTrue(record.isPresent());
        assertTrue(record.get().isCancelRequested());
        assertEquals(TaskStatus.CANCELLED, record.get().getStatus());

        release.countDown();
    }

    // ------------------------------------------------------------------
    //  Compaction simulation: task_list from workspace after clear
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listTasks reads from workspace after localTasks cleared (compaction simulation)")
    void listTasks_readsFromWorkspaceAfterCompaction() throws Exception {
        String session = "sess-compact";

        repo.putTask(
                RuntimeContext.empty(),
                "task-c1",
                "agent-z",
                session,
                new TaskRunSpec.LocalTaskRunSpec(() -> "result-c1"));
        repo.putTask(
                RuntimeContext.empty(),
                "task-c2",
                "agent-z",
                session,
                new TaskRunSpec.LocalTaskRunSpec(() -> "result-c2"));

        awaitCondition(
                () -> {
                    Optional<TaskRecord> r1 =
                            workspaceManager.readTaskRecord(
                                    RuntimeContext.empty(), "test-agent", session, "task-c1");
                    Optional<TaskRecord> r2 =
                            workspaceManager.readTaskRecord(
                                    RuntimeContext.empty(), "test-agent", session, "task-c2");
                    return r1.map(r -> r.getStatus().isTerminal()).orElse(false)
                            && r2.map(r -> r.getStatus().isTerminal()).orElse(false);
                });

        repo.clear();

        Collection<BackgroundTask> tasks = repo.listTasks(RuntimeContext.empty(), session, null);
        assertEquals(2, tasks.size());
        assertTrue(tasks.stream().anyMatch(t -> t.getTaskId().equals("task-c1")));
        assertTrue(tasks.stream().anyMatch(t -> t.getTaskId().equals("task-c2")));
    }

    // ------------------------------------------------------------------
    //  Terminal status not overridden
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listTasks does not override COMPLETED workspace status with RUNNING")
    void listTasks_terminalStatusNotOverridden() throws Exception {
        String session = "sess-term";
        String taskId = "task-term";

        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "agent-t",
                session,
                new TaskRunSpec.LocalTaskRunSpec(() -> "terminal result"));

        awaitCondition(
                () -> {
                    BackgroundTask t = repo.getTask(RuntimeContext.empty(), session, taskId);
                    return t != null && t.getTaskStatus().isTerminal();
                });

        Collection<BackgroundTask> tasks = repo.listTasks(RuntimeContext.empty(), session, null);
        assertEquals(1, tasks.size());
        assertEquals(TaskStatus.COMPLETED, tasks.iterator().next().getTaskStatus());
    }

    // ------------------------------------------------------------------
    //  Status filter
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listTasks with filter returns only matching status tasks (cross-node)")
    void listTasks_withFilter_crossNode() throws Exception {
        // Use separate sessions to avoid concurrent writes to the same file
        String sessionOk = "sess-filter-ok";
        String sessionErr = "sess-filter-err";

        repo.putTask(
                RuntimeContext.empty(),
                "task-ok",
                "agent-f",
                sessionOk,
                new TaskRunSpec.LocalTaskRunSpec(() -> "ok"));
        repo.putTask(
                RuntimeContext.empty(),
                "task-err",
                "agent-f",
                sessionErr,
                new TaskRunSpec.LocalTaskRunSpec(
                        () -> {
                            throw new RuntimeException("error");
                        }));

        awaitCondition(
                () -> {
                    Optional<TaskRecord> r1 =
                            workspaceManager.readTaskRecord(
                                    RuntimeContext.empty(), "test-agent", sessionOk, "task-ok");
                    return r1.map(r -> r.getStatus().isTerminal()).orElse(false);
                });
        awaitCondition(
                () -> {
                    Optional<TaskRecord> r2 =
                            workspaceManager.readTaskRecord(
                                    RuntimeContext.empty(), "test-agent", sessionErr, "task-err");
                    return r2.map(r -> r.getStatus().isTerminal()).orElse(false);
                });

        repo.clear();

        Collection<BackgroundTask> completed =
                repo.listTasks(RuntimeContext.empty(), sessionOk, TaskStatus.COMPLETED);
        assertEquals(1, completed.size());
        assertEquals("task-ok", completed.iterator().next().getTaskId());

        Collection<BackgroundTask> failed =
                repo.listTasks(RuntimeContext.empty(), sessionErr, TaskStatus.FAILED);
        assertEquals(1, failed.size());
        assertEquals("task-err", failed.iterator().next().getTaskId());
    }

    // ------------------------------------------------------------------
    //  WorkspaceManager task record round-trip
    // ------------------------------------------------------------------

    @Test
    @DisplayName("WorkspaceManager writeTaskRecord / readTaskRecord / listTaskRecords round-trip")
    void workspaceManager_taskRecordRoundTrip() throws Exception {
        TaskRecord r1 = new TaskRecord("t1", "agent-a", "parent", "sess-rt", null);
        r1.setStatus(TaskStatus.RUNNING);
        TaskRecord r2 = new TaskRecord("t2", "agent-b", "parent", "sess-rt", null);
        r2.setStatus(TaskStatus.COMPLETED);
        r2.setResult("my result");

        workspaceManager.writeTaskRecord(RuntimeContext.empty(), "parent", "sess-rt", r1);
        workspaceManager.writeTaskRecord(RuntimeContext.empty(), "parent", "sess-rt", r2);

        Optional<TaskRecord> read1 =
                workspaceManager.readTaskRecord(RuntimeContext.empty(), "parent", "sess-rt", "t1");
        assertTrue(read1.isPresent());
        assertEquals(TaskStatus.RUNNING, read1.get().getStatus());

        Optional<TaskRecord> read2 =
                workspaceManager.readTaskRecord(RuntimeContext.empty(), "parent", "sess-rt", "t2");
        assertTrue(read2.isPresent());
        assertEquals("my result", read2.get().getResult());

        Collection<TaskRecord> all =
                workspaceManager.listTaskRecords(RuntimeContext.empty(), "parent", "sess-rt");
        assertEquals(2, all.size());

        Path file = tempDir.resolve("agents/parent/tasks/sess-rt.json");
        assertTrue(Files.exists(file), "Task record JSON file should exist on disk");
    }

    // ------------------------------------------------------------------
    //  Heartbeat: lastUpdatedAt is refreshed for live local tasks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("heartbeat refreshes lastUpdatedAt for a running local task")
    void heartbeat_refreshesLastUpdatedAt() throws Exception {
        String session = "sess-hb";
        String taskId = "task-hb";

        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "agent-hb",
                session,
                new TaskRunSpec.LocalTaskRunSpec(
                        () -> {
                            running.countDown();
                            try {
                                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return "done";
                        }));

        running.await(5, java.util.concurrent.TimeUnit.SECONDS);
        awaitCondition(
                () -> {
                    Optional<TaskRecord> r =
                            workspaceManager.readTaskRecord(
                                    RuntimeContext.empty(), "test-agent", session, taskId);
                    return r.isPresent() && r.get().getStatus() == TaskStatus.RUNNING;
                });

        Instant before =
                workspaceManager
                        .readTaskRecord(RuntimeContext.empty(), "test-agent", session, taskId)
                        .map(TaskRecord::getLastUpdatedAt)
                        .orElseThrow();

        // Small sleep so the clock advances at least 1 ms
        Thread.sleep(10);
        repo.heartbeat();

        Instant after =
                workspaceManager
                        .readTaskRecord(RuntimeContext.empty(), "test-agent", session, taskId)
                        .map(TaskRecord::getLastUpdatedAt)
                        .orElseThrow();

        assertTrue(
                after.isAfter(before),
                "heartbeat() should advance lastUpdatedAt for a live running task");

        release.countDown();
    }

    @Test
    @DisplayName("heartbeat does not touch completed tasks")
    void heartbeat_skipsCompletedTasks() throws Exception {
        String session = "sess-hb-done";
        String taskId = "task-hb-done";

        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "agent-hb-done",
                session,
                new TaskRunSpec.LocalTaskRunSpec(() -> "quick"));

        awaitCondition(
                () ->
                        workspaceManager
                                .readTaskRecord(
                                        RuntimeContext.empty(), "test-agent", session, taskId)
                                .map(r -> r.getStatus().isTerminal())
                                .orElse(false));

        Instant before =
                workspaceManager
                        .readTaskRecord(RuntimeContext.empty(), "test-agent", session, taskId)
                        .map(TaskRecord::getLastUpdatedAt)
                        .orElseThrow();

        Thread.sleep(10);
        repo.heartbeat();

        Instant after =
                workspaceManager
                        .readTaskRecord(RuntimeContext.empty(), "test-agent", session, taskId)
                        .map(TaskRecord::getLastUpdatedAt)
                        .orElseThrow();

        // Completed task's lastUpdatedAt should be unchanged by heartbeat
        assertEquals(before, after, "heartbeat() must not touch already-completed tasks");
    }

    // ------------------------------------------------------------------
    //  Orphan sweeper: stale RUNNING records become FAILED
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sweepOrphanedTasks marks stale RUNNING local task as FAILED")
    void sweepOrphanedTasks_marksStaleRunningAsFailed() throws Exception {
        String session = "sess-sweep";
        String taskId = "task-stale";

        // Write a RUNNING record with a lastUpdatedAt far in the past (simulates a dead node)
        TaskRecord stale = new TaskRecord(taskId, "agent-stale", "test-agent", session, null);
        stale.setStatus(TaskStatus.RUNNING);
        stale.setLastUpdatedAt(Instant.now().minusSeconds(3600));
        workspaceManager.writeTaskRecord(RuntimeContext.empty(), "test-agent", session, stale);

        // orphanTimeout=ZERO: every record is instantly "stale".
        // recentWindow=1 day: scan all session files regardless of disk mtime.
        repo.sweepOrphanedTasks(Duration.ZERO, Duration.ofDays(1));

        Optional<TaskRecord> swept =
                workspaceManager.readTaskRecord(
                        RuntimeContext.empty(), "test-agent", session, taskId);
        assertTrue(swept.isPresent());
        assertEquals(
                TaskStatus.FAILED,
                swept.get().getStatus(),
                "Orphaned task should be marked FAILED by sweeper");
        assertTrue(
                swept.get().getErrorMessage() != null
                        && swept.get().getErrorMessage().contains("executor lost"),
                "Error message should indicate executor loss");
    }

    @Test
    @DisplayName("sweepOrphanedTasks does not touch tasks with a live local future")
    void sweepOrphanedTasks_skipsLiveTasks() throws Exception {
        String session = "sess-sweep-live";
        String taskId = "task-live";

        CountDownLatch release = new CountDownLatch(1);
        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "agent-live",
                session,
                new TaskRunSpec.LocalTaskRunSpec(
                        () -> {
                            try {
                                release.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return "live";
                        }));

        awaitCondition(
                () ->
                        workspaceManager
                                .readTaskRecord(
                                        RuntimeContext.empty(), "test-agent", session, taskId)
                                .map(r -> r.getStatus() == TaskStatus.RUNNING)
                                .orElse(false));

        BackgroundTask live = repo.getTask(RuntimeContext.empty(), session, taskId);
        assertNotNull(live, "Live task handle must exist before sweep");
        assertTrue(!live.isCompleted(), "Task must still be running before sweep");

        // orphanTimeout=ZERO, recentWindow=1 day: everything would qualify, but the live
        // future should still protect this task.
        repo.sweepOrphanedTasks(Duration.ZERO, Duration.ofDays(1));

        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord(
                        RuntimeContext.empty(), "test-agent", session, taskId);
        assertTrue(record.isPresent());
        assertEquals(
                TaskStatus.RUNNING,
                record.get().getStatus(),
                "Live task with local future must not be swept");

        release.countDown();
        awaitCondition(() -> live.isCompleted());
    }

    @Test
    @DisplayName("sweepOrphanedTasks does not touch terminal tasks")
    void sweepOrphanedTasks_skipsTerminalTasks() throws Exception {
        String session = "sess-sweep-term";
        String taskId = "task-term-sweep";

        TaskRecord completed = new TaskRecord(taskId, "agent-x", "test-agent", session, null);
        completed.setStatus(TaskStatus.COMPLETED);
        completed.setResult("done");
        completed.setLastUpdatedAt(Instant.now().minusSeconds(7200));
        workspaceManager.writeTaskRecord(RuntimeContext.empty(), "test-agent", session, completed);

        repo.sweepOrphanedTasks(Duration.ZERO, Duration.ofDays(1));

        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord(
                        RuntimeContext.empty(), "test-agent", session, taskId);
        assertTrue(record.isPresent());
        assertEquals(
                TaskStatus.COMPLETED, record.get().getStatus(), "Terminal tasks must not be swept");
    }

    @Test
    @DisplayName("sweepOrphanedTasks does not touch remote agent-protocol tasks")
    void sweepOrphanedTasks_skipsRemoteTasks() throws Exception {
        String session = "sess-sweep-remote";
        String taskId = "task-remote-sweep";

        TaskRecord remote = new TaskRecord(taskId, "agent-r", "test-agent", session, null);
        remote.setStatus(TaskStatus.RUNNING);
        remote.setTransportType("agent-protocol");
        remote.setRemoteBaseUrl("http://remote-agent:8080");
        remote.setLastUpdatedAt(Instant.now().minusSeconds(7200));
        workspaceManager.writeTaskRecord(RuntimeContext.empty(), "test-agent", session, remote);

        repo.sweepOrphanedTasks(Duration.ZERO, Duration.ofDays(1));

        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord(
                        RuntimeContext.empty(), "test-agent", session, taskId);
        assertTrue(record.isPresent());
        assertEquals(
                TaskStatus.RUNNING,
                record.get().getStatus(),
                "Remote agent-protocol tasks must not be swept (they have their own liveness)");
    }

    // ------------------------------------------------------------------
    //  Sweep marker: distributed throttle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sweepOrphanedTasksDefault writes sweep marker after completing a sweep")
    void sweepMarker_isWrittenAfterSweep() throws Exception {
        // No marker initially
        assertTrue(
                workspaceManager.readSweepMarker(RuntimeContext.empty(), "test-agent").isEmpty());

        // Trigger the default sweep path (uses the marker internally)
        repo.sweepOrphanedTasksDefault_forTest();

        // Marker should now be present and recent
        Optional<java.time.Instant> marker =
                workspaceManager.readSweepMarker(RuntimeContext.empty(), "test-agent");
        assertTrue(marker.isPresent(), "Sweep marker must be written after a successful sweep");
        assertTrue(
                marker.get().isAfter(Instant.now().minusSeconds(5)),
                "Sweep marker should be a very recent timestamp");
    }

    @Test
    @DisplayName("sweepOrphanedTasksDefault skips sweep when marker is fresh")
    void sweepMarker_freshMarkerSkipsSweep() throws Exception {
        String session = "sess-marker-skip";
        String taskId = "task-marker-skip";

        // A stale orphan that would normally be swept
        TaskRecord stale = new TaskRecord(taskId, "a", "test-agent", session, null);
        stale.setStatus(TaskStatus.RUNNING);
        stale.setLastUpdatedAt(Instant.now().minusSeconds(3600));
        workspaceManager.writeTaskRecord(RuntimeContext.empty(), "test-agent", session, stale);

        // Write a fresh marker (simulates another node just swept)
        workspaceManager.writeSweepMarker(RuntimeContext.empty(), "test-agent");

        // The default sweep should be skipped due to the fresh marker
        repo.sweepOrphanedTasksDefault_forTest();

        // Orphan should NOT have been swept
        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord(
                        RuntimeContext.empty(), "test-agent", session, taskId);
        assertTrue(record.isPresent());
        assertEquals(
                TaskStatus.RUNNING,
                record.get().getStatus(),
                "Stale task should not be swept when the sweep marker is fresh");
    }

    // ------------------------------------------------------------------
    //  WorkspaceManager.listAllTaskRecords
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listAllTaskRecords returns records across all sessions for an agent")
    void listAllTaskRecords_returnsAcrossAllSessions() throws Exception {
        TaskRecord r1 = new TaskRecord("task-all-1", "a", "test-agent", "sess-all-1", null);
        r1.setStatus(TaskStatus.COMPLETED);
        TaskRecord r2 = new TaskRecord("task-all-2", "b", "test-agent", "sess-all-2", null);
        r2.setStatus(TaskStatus.FAILED);
        TaskRecord r3 = new TaskRecord("task-all-3", "c", "test-agent", "sess-all-1", null);
        r3.setStatus(TaskStatus.RUNNING);

        workspaceManager.writeTaskRecord(RuntimeContext.empty(), "test-agent", "sess-all-1", r1);
        workspaceManager.writeTaskRecord(RuntimeContext.empty(), "test-agent", "sess-all-2", r2);
        workspaceManager.writeTaskRecord(RuntimeContext.empty(), "test-agent", "sess-all-1", r3);

        Collection<TaskRecord> all =
                workspaceManager.listAllTaskRecords(
                        RuntimeContext.empty(), "test-agent", Duration.ofDays(1));
        assertEquals(3, all.size(), "Should return all records across all sessions");
        assertTrue(all.stream().anyMatch(r -> "task-all-1".equals(r.getTaskId())));
        assertTrue(all.stream().anyMatch(r -> "task-all-2".equals(r.getTaskId())));
        assertTrue(all.stream().anyMatch(r -> "task-all-3".equals(r.getTaskId())));
    }

    @Test
    @DisplayName("listAllTaskRecords returns empty for unknown agent")
    void listAllTaskRecords_emptyForUnknownAgent() {
        Collection<TaskRecord> all =
                workspaceManager.listAllTaskRecords(
                        RuntimeContext.empty(), "unknown-agent", Duration.ofDays(1));
        assertTrue(all.isEmpty());
    }

    @Test
    @DisplayName("listAllTaskRecords skips files older than recentWindow")
    void listAllTaskRecords_skipsOldFiles() throws Exception {
        // task-new: in a recently-modified file (write it after the old one)
        TaskRecord recent = new TaskRecord("task-new", "a", "test-agent", "sess-recent", null);
        recent.setStatus(TaskStatus.RUNNING);
        workspaceManager.writeTaskRecord(
                RuntimeContext.empty(), "test-agent", "sess-recent", recent);

        // task-old: force its session file's mtime to be 2 days in the past via reflection
        // We write it first so the file exists, then back-date the file.
        TaskRecord old = new TaskRecord("task-old", "b", "test-agent", "sess-old", null);
        old.setStatus(TaskStatus.RUNNING);
        workspaceManager.writeTaskRecord(RuntimeContext.empty(), "test-agent", "sess-old", old);

        Path oldFile = tempDir.resolve("agents/test-agent/tasks/sess-old.json");
        assertTrue(Files.exists(oldFile), "old session file must exist");
        oldFile.toFile().setLastModified(System.currentTimeMillis() - 2 * 86_400_000L);

        // recentWindow = 1 day → the 2-day-old file should be skipped
        Collection<TaskRecord> result =
                workspaceManager.listAllTaskRecords(
                        RuntimeContext.empty(), "test-agent", Duration.ofDays(1));

        assertTrue(
                result.stream().anyMatch(r -> "task-new".equals(r.getTaskId())),
                "Recent task should be included");
        assertTrue(
                result.stream().noneMatch(r -> "task-old".equals(r.getTaskId())),
                "Old task file should be skipped by recentWindow filter");
    }

    // ------------------------------------------------------------------
    //  Completion callback: terminal status and error propagation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("callback carries COMPLETED status, taskId, subAgentId, sessionId, and result")
    void completionCallback_completedStatusAndResult() throws Exception {
        String session = "sess-cb-ok";
        String taskId = "task-cb-ok";

        CopyOnWriteArrayList<TaskCompletionEvent> events = new CopyOnWriteArrayList<>();
        repo.setCompletionCallback(events::add);

        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "agent-cb-ok",
                session,
                new TaskRunSpec.LocalTaskRunSpec(() -> "success-result"));

        awaitCondition(
                () -> {
                    BackgroundTask t = repo.getTask(RuntimeContext.empty(), session, taskId);
                    return t != null && t.getTaskStatus().isTerminal();
                });

        assertEquals(1, events.size());
        TaskCompletionEvent e = events.get(0);
        assertEquals(TaskStatus.COMPLETED, e.status(), "status must be COMPLETED");
        assertEquals(taskId, e.taskId(), "taskId must match");
        assertEquals("agent-cb-ok", e.subAgentId(), "subAgentId must match");
        assertEquals(session, e.sessionId(), "sessionId must match");
        assertEquals("success-result", e.result(), "result must carry success payload");
        assertNull(e.errorMessage(), "errorMessage must be null for COMPLETED");
    }

    @Test
    @DisplayName(
            "callback carries FAILED status, taskId, subAgentId, sessionId, and authoritative"
                    + " errorMessage")
    void completionCallback_failedStatusAndErrorMessage() throws Exception {
        String session = "sess-cb-fail";
        String taskId = "task-cb-fail";

        CopyOnWriteArrayList<TaskCompletionEvent> events = new CopyOnWriteArrayList<>();
        repo.setCompletionCallback(events::add);

        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "agent-cb-fail",
                session,
                new TaskRunSpec.LocalTaskRunSpec(
                        () -> {
                            throw new RuntimeException("intentional-failure-marker");
                        }));

        awaitCondition(
                () -> {
                    BackgroundTask t = repo.getTask(RuntimeContext.empty(), session, taskId);
                    return t != null && t.getTaskStatus().isTerminal();
                });

        assertEquals(1, events.size());
        TaskCompletionEvent e = events.get(0);
        assertEquals(TaskStatus.FAILED, e.status(), "status must be FAILED");
        assertEquals(taskId, e.taskId(), "taskId must match");
        assertEquals("agent-cb-fail", e.subAgentId(), "subAgentId must match");
        assertEquals(session, e.sessionId(), "sessionId must match");
        assertNull(e.result(), "result must be null for FAILED");
        assertNotNull(e.errorMessage(), "errorMessage must carry the authoritative error");
        assertTrue(
                e.errorMessage().contains("intentional-failure-marker"),
                "errorMessage must contain the original exception message");
    }

    @Test
    @DisplayName("callback carries CANCELLED status, taskId, subAgentId, sessionId when cancelled")
    void completionCallback_cancelledStatus() throws Exception {
        String session = "sess-cb-cancel";
        String taskId = "task-cb-cancel";

        CopyOnWriteArrayList<TaskCompletionEvent> events = new CopyOnWriteArrayList<>();
        repo.setCompletionCallback(events::add);

        CountDownLatch taskRunning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "agent-cb-cancel",
                session,
                new TaskRunSpec.LocalTaskRunSpec(
                        () -> {
                            taskRunning.countDown();
                            try {
                                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return "should-not-appear";
                        }));

        taskRunning.await(5, java.util.concurrent.TimeUnit.SECONDS);
        awaitCondition(
                () -> {
                    Optional<TaskRecord> r =
                            workspaceManager.readTaskRecord(
                                    RuntimeContext.empty(), "test-agent", session, taskId);
                    return r.isPresent() && r.get().getStatus() == TaskStatus.RUNNING;
                });

        repo.cancelTask(RuntimeContext.empty(), session, taskId);

        awaitCondition(
                () -> {
                    Optional<TaskRecord> r =
                            workspaceManager.readTaskRecord(
                                    RuntimeContext.empty(), "test-agent", session, taskId);
                    return r.isPresent() && r.get().getStatus() == TaskStatus.CANCELLED;
                });

        release.countDown();
        Thread.sleep(200);

        TaskCompletionEvent cancelEvent =
                events.stream()
                        .filter(e -> e.status() == TaskStatus.CANCELLED)
                        .findFirst()
                        .orElse(null);
        assertNotNull(cancelEvent, "Callback must receive a CANCELLED event");
        assertEquals(TaskStatus.CANCELLED, cancelEvent.status(), "status must be CANCELLED");
        assertEquals(taskId, cancelEvent.taskId(), "taskId must match");
        assertEquals("agent-cb-cancel", cancelEvent.subAgentId(), "subAgentId must match");
        assertEquals(session, cancelEvent.sessionId(), "sessionId must match");
        assertNull(cancelEvent.result(), "result must be null for CANCELLED");
        assertNull(cancelEvent.errorMessage(), "errorMessage must be null for CANCELLED");
    }

    @Test
    @DisplayName(
            "adopted (promised) task delivers COMPLETED callback with result via"
                    + " future.whenComplete")
    void completionCallback_adoptedFuture_deliversCompletedStatus() throws Exception {
        String session = "sess-cb-adopted";
        String taskId = "task-cb-adopted";

        CopyOnWriteArrayList<TaskCompletionEvent> events = new CopyOnWriteArrayList<>();
        repo.setCompletionCallback(events::add);

        CompletableFuture<String> promised = new CompletableFuture<>();
        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "agent-adopted",
                session,
                new TaskRunSpec.AdoptedTaskRunSpec(promised));

        awaitCondition(
                () -> {
                    BackgroundTask t = repo.getTask(RuntimeContext.empty(), session, taskId);
                    return t != null && t.getTaskStatus() == TaskStatus.RUNNING;
                });

        promised.complete("adopted-result");

        awaitCondition(() -> events.stream().anyMatch(e -> e.status() == TaskStatus.COMPLETED));

        TaskCompletionEvent e =
                events.stream()
                        .filter(ev -> ev.status() == TaskStatus.COMPLETED)
                        .findFirst()
                        .orElseThrow();
        assertEquals(TaskStatus.COMPLETED, e.status(), "adopted task status must be COMPLETED");
        assertEquals(taskId, e.taskId(), "adopted task taskId must match");
        assertEquals("agent-adopted", e.subAgentId(), "adopted task subAgentId must match");
        assertEquals("adopted-result", e.result(), "adopted task result must carry the value");
    }

    @Test
    @DisplayName(
            "adopted (promised) task delivers FAILED callback with errorMessage via"
                    + " future.whenComplete")
    void completionCallback_adoptedFuture_deliversFailedStatus() throws Exception {
        String session = "sess-cb-adopted-fail";
        String taskId = "task-cb-adopted-fail";

        CopyOnWriteArrayList<TaskCompletionEvent> events = new CopyOnWriteArrayList<>();
        repo.setCompletionCallback(events::add);

        CompletableFuture<String> promised = new CompletableFuture<>();
        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "agent-adopted-fail",
                session,
                new TaskRunSpec.AdoptedTaskRunSpec(promised));

        awaitCondition(
                () -> {
                    BackgroundTask t = repo.getTask(RuntimeContext.empty(), session, taskId);
                    return t != null && t.getTaskStatus() == TaskStatus.RUNNING;
                });

        promised.completeExceptionally(new RuntimeException("adopted-failure-marker"));

        awaitCondition(() -> events.stream().anyMatch(e -> e.status() == TaskStatus.FAILED));

        TaskCompletionEvent e =
                events.stream()
                        .filter(ev -> ev.status() == TaskStatus.FAILED)
                        .findFirst()
                        .orElseThrow();
        assertEquals(TaskStatus.FAILED, e.status(), "adopted failed task status must be FAILED");
        assertEquals(taskId, e.taskId(), "adopted failed taskId must match");
        assertNotNull(e.errorMessage(), "adopted failed errorMessage must be non-null");
        assertTrue(
                e.errorMessage().contains("adopted-failure-marker"),
                "adopted failed errorMessage must contain the original cause");
    }

    // ------------------------------------------------------------------
    //  End-to-end: callback → MessageBus inbox → HintBlock content
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "end-to-end: FAILED task produces HintBlock with 'has FAILED' and error in parent"
                    + " reasoning context")
    void endToEnd_failedTask_hintBlockContainsFailedAndError() throws Exception {
        java.nio.file.Path busDir = tempDir.resolve("bus-e2e-fail");
        busDir.toFile().mkdirs();
        var busFs =
                new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(busDir, true, 10);
        var bus = new io.agentscope.harness.agent.bus.WorkspaceMessageBus(busFs, "/bus");
        String session = "sess-e2e-fail";

        repo.setCompletionCallback(
                event -> {
                    String userId = event.rc() != null ? event.rc().getUserId() : null;
                    String hintContent =
                            switch (event.status()) {
                                case COMPLETED ->
                                        String.format(
                                                "<system-notification>Background subagent task '%s'"
                                                        + " (agent=%s) has completed.\n\nResult:"
                                                        + "\n\n%s</system-notification>",
                                                event.taskId(),
                                                event.subAgentId(),
                                                event.result() != null
                                                        ? event.result()
                                                        : "(no output)");
                                case FAILED ->
                                        String.format(
                                                "<system-notification>Background subagent task '%s'"
                                                        + " (agent=%s) has FAILED.\n\nError:"
                                                        + "\n\n%s</system-notification>",
                                                event.taskId(),
                                                event.subAgentId(),
                                                event.errorMessage() != null
                                                        ? event.errorMessage()
                                                        : "(no error message)");
                                case CANCELLED ->
                                        String.format(
                                                "<system-notification>Background subagent task '%s'"
                                                        + " (agent=%s) was cancelled."
                                                        + "</system-notification>",
                                                event.taskId(), event.subAgentId());
                                default ->
                                        throw new IllegalStateException(
                                                "Unexpected: " + event.status());
                            };
                    bus.inboxPush(
                                    event.sessionId(),
                                    java.util.Map.of(
                                            "type",
                                            "hint",
                                            "id",
                                            java.util.UUID.randomUUID().toString().replace("-", ""),
                                            "hint",
                                            hintContent,
                                            "source",
                                            "subagent_task"))
                            .block();
                });

        repo.putTask(
                RuntimeContext.empty(),
                "e2e-fail-task",
                "e2e-fail-agent",
                session,
                new TaskRunSpec.LocalTaskRunSpec(
                        () -> {
                            throw new RuntimeException("e2e-failure-marker");
                        }));

        awaitCondition(
                () -> {
                    BackgroundTask t =
                            repo.getTask(RuntimeContext.empty(), session, "e2e-fail-task");
                    return t != null && t.getTaskStatus().isTerminal();
                });

        // Drain the inbox the same way InboxMiddleware does
        List<io.agentscope.harness.agent.bus.BusEntry> entries =
                bus.inboxDrain(session, 10).block();
        assertNotNull(entries, "inbox must have entries");
        assertTrue(!entries.isEmpty(), "inbox must not be empty after FAILED task");

        Map<String, Object> payload = entries.get(0).payload();
        String hint = (String) payload.get("hint");
        assertNotNull(hint, "deserialized hint must not be null");
        assertTrue(
                hint.contains("has FAILED"),
                "HintBlock must say 'has FAILED' for a failed task, got: " + hint);
        assertTrue(
                hint.contains("e2e-failure-marker"),
                "HintBlock must contain the authoritative error message, got: " + hint);
        assertTrue(
                hint.contains("e2e-fail-task"), "HintBlock must contain the taskId, got: " + hint);
        assertTrue(
                hint.contains("e2e-fail-agent"),
                "HintBlock must contain the subAgentId, got: " + hint);
    }

    @Test
    @DisplayName(
            "end-to-end: CANCELLED task produces HintBlock with 'was cancelled' in parent"
                    + " reasoning context")
    void endToEnd_cancelledTask_hintBlockContainsCancelled() throws Exception {
        java.nio.file.Path busDir = tempDir.resolve("bus-e2e-cancel");
        busDir.toFile().mkdirs();
        var busFs =
                new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(busDir, true, 10);
        var bus = new io.agentscope.harness.agent.bus.WorkspaceMessageBus(busFs, "/bus");
        String session = "sess-e2e-cancel";

        repo.setCompletionCallback(
                event -> {
                    String hintContent =
                            switch (event.status()) {
                                case COMPLETED -> "completed";
                                case FAILED -> "failed";
                                case CANCELLED ->
                                        String.format(
                                                "<system-notification>Background subagent task '%s'"
                                                        + " (agent=%s) was cancelled."
                                                        + "</system-notification>",
                                                event.taskId(), event.subAgentId());
                                default -> "unknown";
                            };
                    bus.inboxPush(
                                    event.sessionId(),
                                    java.util.Map.of(
                                            "type",
                                            "hint",
                                            "id",
                                            java.util.UUID.randomUUID().toString().replace("-", ""),
                                            "hint",
                                            hintContent,
                                            "source",
                                            "subagent_task"))
                            .block();
                });

        CountDownLatch taskRunning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repo.putTask(
                RuntimeContext.empty(),
                "e2e-cancel-task",
                "e2e-cancel-agent",
                session,
                new TaskRunSpec.LocalTaskRunSpec(
                        () -> {
                            taskRunning.countDown();
                            try {
                                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                            return "should-not-appear";
                        }));

        taskRunning.await(5, java.util.concurrent.TimeUnit.SECONDS);
        awaitCondition(
                () -> {
                    Optional<TaskRecord> r =
                            workspaceManager.readTaskRecord(
                                    RuntimeContext.empty(),
                                    "test-agent",
                                    session,
                                    "e2e-cancel-task");
                    return r.isPresent() && r.get().getStatus() == TaskStatus.RUNNING;
                });

        repo.cancelTask(RuntimeContext.empty(), session, "e2e-cancel-task");
        release.countDown();

        awaitCondition(
                () -> {
                    Optional<TaskRecord> r =
                            workspaceManager.readTaskRecord(
                                    RuntimeContext.empty(),
                                    "test-agent",
                                    session,
                                    "e2e-cancel-task");
                    return r.isPresent() && r.get().getStatus() == TaskStatus.CANCELLED;
                });

        Thread.sleep(200);
        List<io.agentscope.harness.agent.bus.BusEntry> entries =
                bus.inboxDrain(session, 10).block();
        assertNotNull(entries, "inbox must have entries after CANCELLED");
        assertTrue(!entries.isEmpty(), "inbox must not be empty after CANCELLED task");

        Map<String, Object> payload = entries.get(0).payload();
        String hint = (String) payload.get("hint");
        assertNotNull(hint, "deserialized hint must not be null");
        assertTrue(
                hint.contains("was cancelled"),
                "HintBlock must say 'was cancelled' for a cancelled task, got: " + hint);
        assertTrue(
                hint.contains("e2e-cancel-task"),
                "HintBlock must contain the taskId, got: " + hint);
        assertTrue(
                hint.contains("e2e-cancel-agent"),
                "HintBlock must contain the subAgentId, got: " + hint);
    }

    @Test
    @DisplayName(
            "end-to-end: COMPLETED task produces HintBlock with 'has completed' and result in"
                    + " parent reasoning context")
    void endToEnd_completedTask_hintBlockContainsCompletedAndResult() throws Exception {
        java.nio.file.Path busDir = tempDir.resolve("bus-e2e-ok");
        busDir.toFile().mkdirs();
        var busFs =
                new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(busDir, true, 10);
        var bus = new io.agentscope.harness.agent.bus.WorkspaceMessageBus(busFs, "/bus");
        String session = "sess-e2e-ok";

        repo.setCompletionCallback(
                event -> {
                    String hintContent =
                            switch (event.status()) {
                                case COMPLETED ->
                                        String.format(
                                                "<system-notification>Background subagent task '%s'"
                                                        + " (agent=%s) has completed.\n\nResult:"
                                                        + "\n\n%s</system-notification>",
                                                event.taskId(),
                                                event.subAgentId(),
                                                event.result() != null
                                                        ? event.result()
                                                        : "(no output)");
                                case FAILED -> "failed";
                                case CANCELLED -> "cancelled";
                                default -> "unknown";
                            };
                    bus.inboxPush(
                                    event.sessionId(),
                                    java.util.Map.of(
                                            "type",
                                            "hint",
                                            "id",
                                            java.util.UUID.randomUUID().toString().replace("-", ""),
                                            "hint",
                                            hintContent,
                                            "source",
                                            "subagent_task"))
                            .block();
                });

        repo.putTask(
                RuntimeContext.empty(),
                "e2e-ok-task",
                "e2e-ok-agent",
                session,
                new TaskRunSpec.LocalTaskRunSpec(() -> "e2e-success-payload"));

        awaitCondition(
                () -> {
                    BackgroundTask t = repo.getTask(RuntimeContext.empty(), session, "e2e-ok-task");
                    return t != null && t.getTaskStatus().isTerminal();
                });

        List<io.agentscope.harness.agent.bus.BusEntry> entries =
                bus.inboxDrain(session, 10).block();
        assertNotNull(entries, "inbox must have entries after COMPLETED");
        assertTrue(!entries.isEmpty(), "inbox must not be empty after COMPLETED task");

        Map<String, Object> payload = entries.get(0).payload();
        String hint = (String) payload.get("hint");
        assertNotNull(hint, "deserialized hint must not be null");
        assertTrue(
                hint.contains("has completed"),
                "HintBlock must say 'has completed' for a successful task, got: " + hint);
        assertTrue(
                hint.contains("e2e-success-payload"),
                "HintBlock must contain the result payload, got: " + hint);
        assertTrue(hint.contains("e2e-ok-task"), "HintBlock must contain the taskId, got: " + hint);
        assertTrue(
                hint.contains("e2e-ok-agent"),
                "HintBlock must contain the subAgentId, got: " + hint);
    }

    // ------------------------------------------------------------------
    //  RemoteFilesystemSpec tasks route
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RemoteFilesystemSpec includes agents/<agentId>/tasks/ as shared route")
    void remoteFilesystemSpec_includesTasksRoute() throws Exception {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystemSpec spec = new RemoteFilesystemSpec(store);

        var fs = spec.toFilesystem(tempDir, "my-agent", rc -> List.of());

        assertTrue(
                fs instanceof CompositeFilesystem,
                "Expected CompositeFilesystem for RemoteFilesystemSpec");

        // Write to the tasks path — should be routed to RemoteFilesystem (InMemoryStore)
        String taskPath = "agents/my-agent/tasks/sess-test.json";
        fs.uploadFiles(
                RuntimeContext.empty(), List.of(Map.entry(taskPath, "{\"test\":true}".getBytes())));

        // Read back via the filesystem — should succeed and return the content
        var readResult = fs.read(RuntimeContext.empty(), taskPath, 0, 0);
        assertTrue(
                readResult.isSuccess(), "Task record read should succeed via CompositeFilesystem");
        assertTrue(
                readResult.fileData().content().contains("test"),
                "Task record content should be readable");
    }
}
