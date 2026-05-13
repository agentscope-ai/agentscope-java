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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
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
        repo = new WorkspaceTaskRepository(workspaceManager, "test-agent");
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
                () -> {
                    executed.set(true);
                    return "done";
                });

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
                () -> {
                    throw new RuntimeException("intentional failure");
                });

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

        repo.putTask(taskId, "agent-y", session, () -> "cross-node result");

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

        repo.putTask("task-a1", "agent-a", sessionA, () -> "result-a");
        repo.putTask("task-b1", "agent-b", sessionB, () -> "result-b");

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
                () -> {
                    taskRunning.countDown();
                    try {
                        release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "slow result";
                });

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

        repo.putTask("task-c1", "agent-z", session, () -> "result-c1");
        repo.putTask("task-c2", "agent-z", session, () -> "result-c2");

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

        repo.putTask(taskId, "agent-t", session, () -> "terminal result");

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

        repo.putTask("task-ok", "agent-f", sessionOk, () -> "ok");
        repo.putTask(
                "task-err",
                "agent-f",
                sessionErr,
                () -> {
                    throw new RuntimeException("error");
                });

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
