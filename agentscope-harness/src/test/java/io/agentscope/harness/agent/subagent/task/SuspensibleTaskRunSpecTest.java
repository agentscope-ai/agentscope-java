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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuspensibleTaskRunSpecTest {

    @TempDir Path tempDir;

    private WorkspaceManager workspaceManager;
    private WorkspaceTaskRepository repo;
    private RuntimeContext runtimeContext;

    @BeforeEach
    void setUp() {
        workspaceManager = new WorkspaceManager(tempDir);
        repo = WorkspaceTaskRepository.forTests(workspaceManager, "parent-agent");
        runtimeContext =
                RuntimeContext.builder().userId("user-a").sessionId("parent-session").build();
    }

    @AfterEach
    void tearDown() {
        repo.shutdown();
    }

    @Test
    void waitingOutcomeRemainsNonTerminalAndPersistsResumeIdentity() throws Exception {
        submitWaiting("task-wait");

        awaitStatus("task-wait", TaskStatus.WAITING_FOR_APPROVAL);

        BackgroundTask task = repo.getTask(runtimeContext, "parent-session", "task-wait");
        assertNotNull(task);
        assertFalse(task.isCompleted());
        Optional<TaskRecord> record = readRecord("task-wait");
        assertTrue(record.isPresent());
        assertEquals("user-a", record.get().getUserId());
        assertEquals("child-session", record.get().getSubSessionId());
        assertEquals(suspension(), record.get().getSuspension());
        assertTrue(repo.findPendingDeliveries(runtimeContext, "parent-session").isEmpty());
    }

    @Test
    void repositoryOwnsResumeExecutionAndCompletesExactlyOnce() throws Exception {
        submitWaiting("task-resume");
        awaitStatus("task-resume", TaskStatus.WAITING_FOR_APPROVAL);
        CountDownLatch continuationStarted = new CountDownLatch(1);
        CountDownLatch releaseContinuation = new CountDownLatch(1);

        assertTrue(
                repo.resumeTask(
                        runtimeContext,
                        "parent-session",
                        "task-resume",
                        () -> {
                            continuationStarted.countDown();
                            await(releaseContinuation);
                            return new TaskRunOutcome.Completed("approved result");
                        }));
        continuationStarted.await();
        awaitStatus("task-resume", TaskStatus.RUNNING);
        assertFalse(
                repo.resumeTask(
                        runtimeContext,
                        "parent-session",
                        "task-resume",
                        () -> new TaskRunOutcome.Completed("duplicate")));

        releaseContinuation.countDown();
        awaitStatus("task-resume", TaskStatus.COMPLETED);

        assertEquals("approved result", readRecord("task-resume").orElseThrow().getResult());
        assertFalse(
                repo.resumeTask(
                        runtimeContext,
                        "parent-session",
                        "task-resume",
                        () -> new TaskRunOutcome.Completed("late")));
    }

    @Test
    void waitingTaskCanResumeAfterRepositoryRestart() throws Exception {
        submitWaiting("task-restart");
        awaitStatus("task-restart", TaskStatus.WAITING_FOR_APPROVAL);
        repo.shutdown();
        repo = WorkspaceTaskRepository.forTests(workspaceManager, "parent-agent");

        BackgroundTask restored = repo.getTask(runtimeContext, "parent-session", "task-restart");
        assertEquals(TaskStatus.WAITING_FOR_APPROVAL, restored.getTaskStatus());
        assertFalse(restored.isCompleted());
        assertTrue(
                repo.resumeTask(
                        runtimeContext,
                        "parent-session",
                        "task-restart",
                        () -> new TaskRunOutcome.Denied("user denied")));

        awaitStatus("task-restart", TaskStatus.DENIED);
        assertEquals("user denied", readRecord("task-restart").orElseThrow().getResult());
    }

    @Test
    void cancellationWinsOverWaitingContinuation() throws Exception {
        submitWaiting("task-cancel");
        awaitStatus("task-cancel", TaskStatus.WAITING_FOR_APPROVAL);

        assertTrue(repo.cancelTask(runtimeContext, "parent-session", "task-cancel"));
        assertEquals(TaskStatus.CANCELLED, readRecord("task-cancel").orElseThrow().getStatus());
        assertFalse(
                repo.resumeTask(
                        runtimeContext,
                        "parent-session",
                        "task-cancel",
                        () -> new TaskRunOutcome.Completed("must not run")));
    }

    @Test
    void cancellationDuringContinuationSuppressesLateCompletionAndDelivery() throws Exception {
        submitWaiting("task-race");
        awaitStatus("task-race", TaskStatus.WAITING_FOR_APPROVAL);
        CountDownLatch continuationStarted = new CountDownLatch(1);
        CountDownLatch releaseContinuation = new CountDownLatch(1);
        AtomicBoolean continuationReturned = new AtomicBoolean();
        AtomicBoolean completionCallbackFired = new AtomicBoolean();
        repo.setCompletionCallback(
                (rc, taskId, agentId, sessionId, result) -> completionCallbackFired.set(true));

        assertTrue(
                repo.resumeTask(
                        runtimeContext,
                        "parent-session",
                        "task-race",
                        () -> {
                            continuationStarted.countDown();
                            await(releaseContinuation);
                            continuationReturned.set(true);
                            return new TaskRunOutcome.Completed("late result");
                        }));
        continuationStarted.await();
        assertTrue(repo.cancelTask(runtimeContext, "parent-session", "task-race"));
        releaseContinuation.countDown();
        awaitCondition(continuationReturned::get);

        TaskRecord record = readRecord("task-race").orElseThrow();
        assertEquals(TaskStatus.CANCELLED, record.getStatus());
        assertFalse(completionCallbackFired.get());
        assertTrue(
                repo.findPendingDeliveries(runtimeContext, "parent-session").stream()
                        .anyMatch(delivery -> delivery.status() == TaskStatus.CANCELLED));
    }

    private void submitWaiting(String taskId) {
        repo.putTask(
                runtimeContext,
                taskId,
                "worker",
                "parent-session",
                new TaskRunSpec.SuspensibleLocalTaskRunSpec(
                        () -> new TaskRunOutcome.WaitingForApproval(suspension()),
                        "child-session"));
    }

    private Optional<TaskRecord> readRecord(String taskId) {
        return workspaceManager.readTaskRecord(
                runtimeContext, "parent-agent", "parent-session", taskId);
    }

    private void awaitStatus(String taskId, TaskStatus status) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Optional<TaskRecord> record = readRecord(taskId);
            if (record.isPresent() && record.get().getStatus() == status) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Task " + taskId + " did not reach " + status);
    }

    private static void awaitCondition(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Condition not met");
    }

    private static TaskSuspension suspension() {
        return new TaskSuspension(
                "user-a",
                "parent-session",
                "child-session",
                "reply-1",
                List.of(new TaskSuspension.PendingToolCall("tool-1", "edit_file")));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Continuation interrupted", e);
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean get();
    }
}
