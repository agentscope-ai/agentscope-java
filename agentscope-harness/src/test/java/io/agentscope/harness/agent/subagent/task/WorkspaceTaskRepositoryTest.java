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
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.store.InMemoryStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *   <li>Session-scope isolation: different sessionIds are independent
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
                    BackgroundTask t = repo.getTask(session, taskId);
                    return t != null && t.getTaskStatus().isTerminal();
                });

        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord("test-agent", session, taskId);
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
                taskId,
                "sub-agent-fail",
                session,
                new TaskRunSpec.LocalTaskRunSpec(
                        () -> {
                            throw new RuntimeException("intentional failure");
                        }));

        awaitCondition(
                () -> {
                    BackgroundTask t = repo.getTask(session, taskId);
                    return t != null && t.getTaskStatus().isTerminal();
                });

        awaitCondition(
                () -> {
                    Optional<TaskRecord> r =
                            workspaceManager.readTaskRecord("test-agent", session, taskId);
                    return r.isPresent() && r.get().getStatus().isTerminal();
                });

        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord("test-agent", session, taskId);
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
                taskId,
                "agent-y",
                session,
                new TaskRunSpec.LocalTaskRunSpec(() -> "cross-node result"));

        awaitCondition(
                () -> {
                    Optional<TaskRecord> r =
                            workspaceManager.readTaskRecord("test-agent", session, taskId);
                    return r.isPresent() && r.get().getStatus().isTerminal();
                });

        // Simulate cross-node scenario: clear in-memory tasks
        repo.clear();

        BackgroundTask synthetic = repo.getTask(session, taskId);
        assertNotNull(synthetic);
        assertEquals(TaskStatus.COMPLETED, synthetic.getTaskStatus());
        assertEquals("cross-node result", synthetic.getResult());
    }

    @Test
    @DisplayName(
            "getTask returns null for unknown task on cross-node node without workspace record")
    void getTask_returnsNullWhenNothingFound() {
        assertNull(repo.getTask("no-session", "no-task"));
    }

    // ------------------------------------------------------------------
    //  Session-scope isolation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listTasks isolates tasks by sessionId")
    void listTasks_sessionIsolation() throws Exception {
        String sessionA = "sess-a";
        String sessionB = "sess-b";

        repo.putTask(
                "task-a1", "agent-a", sessionA, new TaskRunSpec.LocalTaskRunSpec(() -> "result-a"));
        repo.putTask(
                "task-b1", "agent-b", sessionB, new TaskRunSpec.LocalTaskRunSpec(() -> "result-b"));

        awaitCondition(
                () -> {
                    BackgroundTask a = repo.getTask(sessionA, "task-a1");
                    BackgroundTask b = repo.getTask(sessionB, "task-b1");
                    return a != null
                            && a.getTaskStatus().isTerminal()
                            && b != null
                            && b.getTaskStatus().isTerminal();
                });

        Collection<BackgroundTask> tasksA = repo.listTasks(sessionA, null);
        Collection<BackgroundTask> tasksB = repo.listTasks(sessionB, null);

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
                            workspaceManager.readTaskRecord("test-agent", session, taskId);
                    return r.isPresent() && r.get().getStatus() == TaskStatus.RUNNING;
                });

        boolean cancelled = repo.cancelTask(session, taskId);
        assertTrue(cancelled);

        // Read workspace before releasing the worker: once the latch opens, the async path
        // may persist COMPLETED and would race this assertion under full-suite load.
        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord("test-agent", session, taskId);
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
                "task-c1", "agent-z", session, new TaskRunSpec.LocalTaskRunSpec(() -> "result-c1"));
        repo.putTask(
                "task-c2", "agent-z", session, new TaskRunSpec.LocalTaskRunSpec(() -> "result-c2"));

        awaitCondition(
                () -> {
                    Optional<TaskRecord> r1 =
                            workspaceManager.readTaskRecord("test-agent", session, "task-c1");
                    Optional<TaskRecord> r2 =
                            workspaceManager.readTaskRecord("test-agent", session, "task-c2");
                    return r1.map(r -> r.getStatus().isTerminal()).orElse(false)
                            && r2.map(r -> r.getStatus().isTerminal()).orElse(false);
                });

        repo.clear();

        Collection<BackgroundTask> tasks = repo.listTasks(session, null);
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
                taskId,
                "agent-t",
                session,
                new TaskRunSpec.LocalTaskRunSpec(() -> "terminal result"));

        awaitCondition(
                () -> {
                    BackgroundTask t = repo.getTask(session, taskId);
                    return t != null && t.getTaskStatus().isTerminal();
                });

        Collection<BackgroundTask> tasks = repo.listTasks(session, null);
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

        repo.putTask("task-ok", "agent-f", sessionOk, new TaskRunSpec.LocalTaskRunSpec(() -> "ok"));
        repo.putTask(
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
                            workspaceManager.readTaskRecord("test-agent", sessionOk, "task-ok");
                    return r1.map(r -> r.getStatus().isTerminal()).orElse(false);
                });
        awaitCondition(
                () -> {
                    Optional<TaskRecord> r2 =
                            workspaceManager.readTaskRecord("test-agent", sessionErr, "task-err");
                    return r2.map(r -> r.getStatus().isTerminal()).orElse(false);
                });

        repo.clear();

        Collection<BackgroundTask> completed = repo.listTasks(sessionOk, TaskStatus.COMPLETED);
        assertEquals(1, completed.size());
        assertEquals("task-ok", completed.iterator().next().getTaskId());

        Collection<BackgroundTask> failed = repo.listTasks(sessionErr, TaskStatus.FAILED);
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

        workspaceManager.writeTaskRecord("parent", "sess-rt", r1);
        workspaceManager.writeTaskRecord("parent", "sess-rt", r2);

        Optional<TaskRecord> read1 = workspaceManager.readTaskRecord("parent", "sess-rt", "t1");
        assertTrue(read1.isPresent());
        assertEquals(TaskStatus.RUNNING, read1.get().getStatus());

        Optional<TaskRecord> read2 = workspaceManager.readTaskRecord("parent", "sess-rt", "t2");
        assertTrue(read2.isPresent());
        assertEquals("my result", read2.get().getResult());

        Collection<TaskRecord> all = workspaceManager.listTaskRecords("parent", "sess-rt");
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
                            workspaceManager.readTaskRecord("test-agent", session, taskId);
                    return r.isPresent() && r.get().getStatus() == TaskStatus.RUNNING;
                });

        Instant before =
                workspaceManager
                        .readTaskRecord("test-agent", session, taskId)
                        .map(TaskRecord::getLastUpdatedAt)
                        .orElseThrow();

        // Small sleep so the clock advances at least 1 ms
        Thread.sleep(10);
        repo.heartbeat();

        Instant after =
                workspaceManager
                        .readTaskRecord("test-agent", session, taskId)
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
                taskId, "agent-hb-done", session, new TaskRunSpec.LocalTaskRunSpec(() -> "quick"));

        awaitCondition(
                () ->
                        workspaceManager
                                .readTaskRecord("test-agent", session, taskId)
                                .map(r -> r.getStatus().isTerminal())
                                .orElse(false));

        Instant before =
                workspaceManager
                        .readTaskRecord("test-agent", session, taskId)
                        .map(TaskRecord::getLastUpdatedAt)
                        .orElseThrow();

        Thread.sleep(10);
        repo.heartbeat();

        Instant after =
                workspaceManager
                        .readTaskRecord("test-agent", session, taskId)
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
        workspaceManager.writeTaskRecord("test-agent", session, stale);

        // orphanTimeout=ZERO: every record is instantly "stale".
        // recentWindow=1 day: scan all session files regardless of disk mtime.
        repo.sweepOrphanedTasks(Duration.ZERO, Duration.ofDays(1));

        Optional<TaskRecord> swept = workspaceManager.readTaskRecord("test-agent", session, taskId);
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
                                .readTaskRecord("test-agent", session, taskId)
                                .map(r -> r.getStatus() == TaskStatus.RUNNING)
                                .orElse(false));

        BackgroundTask live = repo.getTask(session, taskId);
        assertNotNull(live, "Live task handle must exist before sweep");
        assertTrue(!live.isCompleted(), "Task must still be running before sweep");

        // orphanTimeout=ZERO, recentWindow=1 day: everything would qualify, but the live
        // future should still protect this task.
        repo.sweepOrphanedTasks(Duration.ZERO, Duration.ofDays(1));

        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord("test-agent", session, taskId);
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
        workspaceManager.writeTaskRecord("test-agent", session, completed);

        repo.sweepOrphanedTasks(Duration.ZERO, Duration.ofDays(1));

        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord("test-agent", session, taskId);
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
        workspaceManager.writeTaskRecord("test-agent", session, remote);

        repo.sweepOrphanedTasks(Duration.ZERO, Duration.ofDays(1));

        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord("test-agent", session, taskId);
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
        assertTrue(workspaceManager.readSweepMarker("test-agent").isEmpty());

        // Trigger the default sweep path (uses the marker internally)
        repo.sweepOrphanedTasksDefault_forTest();

        // Marker should now be present and recent
        Optional<java.time.Instant> marker = workspaceManager.readSweepMarker("test-agent");
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
        workspaceManager.writeTaskRecord("test-agent", session, stale);

        // Write a fresh marker (simulates another node just swept)
        workspaceManager.writeSweepMarker("test-agent");

        // The default sweep should be skipped due to the fresh marker
        repo.sweepOrphanedTasksDefault_forTest();

        // Orphan should NOT have been swept
        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord("test-agent", session, taskId);
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

        workspaceManager.writeTaskRecord("test-agent", "sess-all-1", r1);
        workspaceManager.writeTaskRecord("test-agent", "sess-all-2", r2);
        workspaceManager.writeTaskRecord("test-agent", "sess-all-1", r3);

        Collection<TaskRecord> all =
                workspaceManager.listAllTaskRecords("test-agent", Duration.ofDays(1));
        assertEquals(3, all.size(), "Should return all records across all sessions");
        assertTrue(all.stream().anyMatch(r -> "task-all-1".equals(r.getTaskId())));
        assertTrue(all.stream().anyMatch(r -> "task-all-2".equals(r.getTaskId())));
        assertTrue(all.stream().anyMatch(r -> "task-all-3".equals(r.getTaskId())));
    }

    @Test
    @DisplayName("listAllTaskRecords returns empty for unknown agent")
    void listAllTaskRecords_emptyForUnknownAgent() {
        Collection<TaskRecord> all =
                workspaceManager.listAllTaskRecords("unknown-agent", Duration.ofDays(1));
        assertTrue(all.isEmpty());
    }

    @Test
    @DisplayName("listAllTaskRecords skips files older than recentWindow")
    void listAllTaskRecords_skipsOldFiles() throws Exception {
        // task-new: in a recently-modified file (write it after the old one)
        TaskRecord recent = new TaskRecord("task-new", "a", "test-agent", "sess-recent", null);
        recent.setStatus(TaskStatus.RUNNING);
        workspaceManager.writeTaskRecord("test-agent", "sess-recent", recent);

        // task-old: force its session file's mtime to be 2 days in the past via reflection
        // We write it first so the file exists, then back-date the file.
        TaskRecord old = new TaskRecord("task-old", "b", "test-agent", "sess-old", null);
        old.setStatus(TaskStatus.RUNNING);
        workspaceManager.writeTaskRecord("test-agent", "sess-old", old);

        Path oldFile = tempDir.resolve("agents/test-agent/tasks/sess-old.json");
        assertTrue(Files.exists(oldFile), "old session file must exist");
        oldFile.toFile().setLastModified(System.currentTimeMillis() - 2 * 86_400_000L);

        // recentWindow = 1 day → the 2-day-old file should be skipped
        Collection<TaskRecord> result =
                workspaceManager.listAllTaskRecords("test-agent", Duration.ofDays(1));

        assertTrue(
                result.stream().anyMatch(r -> "task-new".equals(r.getTaskId())),
                "Recent task should be included");
        assertTrue(
                result.stream().noneMatch(r -> "task-old".equals(r.getTaskId())),
                "Old task file should be skipped by recentWindow filter");
    }

    // ------------------------------------------------------------------
    //  Terminal-status immutability (updateStatus guards)
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "CANCELLED status is never overwritten by COMPLETED when task finishes after cancel")
    void cancelledStatus_notOverwrittenByCompleted() throws Exception {
        String session = "sess-guard-completed";
        String taskId = "task-guard-completed";

        // Latch keeps the task body blocked until we explicitly release it, giving us a
        // deterministic window to cancel before the task finishes.
        CountDownLatch taskRunning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        repo.putTask(
                taskId,
                "agent-guard",
                session,
                new TaskRunSpec.LocalTaskRunSpec(
                        () -> {
                            taskRunning.countDown();
                            try {
                                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return "done";
                        }));

        // Wait until the task is confirmed RUNNING in the workspace
        taskRunning.await(5, java.util.concurrent.TimeUnit.SECONDS);
        awaitCondition(
                () ->
                        workspaceManager
                                .readTaskRecord("test-agent", session, taskId)
                                .map(r -> r.getStatus() == TaskStatus.RUNNING)
                                .orElse(false));

        // Cancel while the task is still blocked
        repo.cancelTask(session, taskId);

        // Verify CANCELLED is persisted before we release the worker
        awaitCondition(
                () ->
                        workspaceManager
                                .readTaskRecord("test-agent", session, taskId)
                                .map(r -> r.getStatus() == TaskStatus.CANCELLED)
                                .orElse(false));

        // Let the worker finish — it would normally try to write COMPLETED
        release.countDown();

        // Give the async thread a moment to attempt the overwrite, then assert
        Thread.sleep(200);

        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord("test-agent", session, taskId);
        assertTrue(record.isPresent());
        assertEquals(
                TaskStatus.CANCELLED,
                record.get().getStatus(),
                "CANCELLED must not be overwritten by the subsequent COMPLETED write");
    }

    @Test
    @DisplayName("heartbeat does not overwrite CANCELLED status with RUNNING")
    void heartbeat_doesNotOverwriteCancelledWithRunning() throws Exception {
        String session = "sess-hb-cancel";
        String taskId = "task-hb-cancel";

        CountDownLatch taskRunning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        repo.putTask(
                taskId,
                "agent-hb-cancel",
                session,
                new TaskRunSpec.LocalTaskRunSpec(
                        () -> {
                            taskRunning.countDown();
                            try {
                                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return "cancelled";
                        }));

        taskRunning.await(5, java.util.concurrent.TimeUnit.SECONDS);
        awaitCondition(
                () ->
                        workspaceManager
                                .readTaskRecord("test-agent", session, taskId)
                                .map(r -> r.getStatus() == TaskStatus.RUNNING)
                                .orElse(false));

        // Cancel the task — writes CANCELLED + cancelRequested=true to workspace
        repo.cancelTask(session, taskId);

        awaitCondition(
                () ->
                        workspaceManager
                                .readTaskRecord("test-agent", session, taskId)
                                .map(r -> r.getStatus() == TaskStatus.CANCELLED)
                                .orElse(false));

        // Simulate a heartbeat tick while the local future is still considered "not completed"
        repo.heartbeat();

        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord("test-agent", session, taskId);
        assertTrue(record.isPresent());
        assertEquals(
                TaskStatus.CANCELLED,
                record.get().getStatus(),
                "heartbeat must not overwrite CANCELLED with RUNNING");

        release.countDown();
    }

    // ------------------------------------------------------------------
    //  RemoteFilesystemSpec tasks route
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RemoteFilesystemSpec includes agents/<agentId>/tasks/ as shared route")
    void remoteFilesystemSpec_includesTasksRoute() throws Exception {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystemSpec spec = new RemoteFilesystemSpec(store);

        var fs = spec.toFilesystem(tempDir, "my-agent", () -> null, () -> null);

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
