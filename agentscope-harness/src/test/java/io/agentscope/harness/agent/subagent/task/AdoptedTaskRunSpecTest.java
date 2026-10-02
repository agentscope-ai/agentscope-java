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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link TaskRunSpec.AdoptedTaskRunSpec} — the variant used when a sync execution is
 * promoted to async on timeout. Verifies that an externally-created {@link CompletableFuture} is
 * correctly tracked by {@link WorkspaceTaskRepository}: status transitions, result persistence,
 * and push delivery eligibility.
 */
class AdoptedTaskRunSpecTest {

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

    private static void awaitCondition(BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.get()) {
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("Condition not met within 5 seconds");
            }
            Thread.sleep(50);
        }
    }

    @FunctionalInterface
    interface BooleanSupplier {
        boolean get() throws Exception;
    }

    @Test
    @DisplayName("Adopted future that completes successfully transitions to COMPLETED")
    void adoptedFuture_completesSuccessfully() throws Exception {
        String session = "sess-adopted-1";
        String taskId = "task-adopted-ok";
        CompletableFuture<String> future = new CompletableFuture<>();

        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "general-purpose",
                session,
                new TaskRunSpec.AdoptedTaskRunSpec(future));

        BackgroundTask task = repo.getTask(RuntimeContext.empty(), session, taskId);
        assertNotNull(task);
        assertEquals(TaskStatus.RUNNING, task.getTaskStatus());

        future.complete("adopted result");

        awaitCondition(
                () -> {
                    BackgroundTask t = repo.getTask(RuntimeContext.empty(), session, taskId);
                    return t != null && t.getTaskStatus() == TaskStatus.COMPLETED;
                });

        BackgroundTask completed = repo.getTask(RuntimeContext.empty(), session, taskId);
        assertEquals("adopted result", completed.getResult());
    }

    @Test
    @DisplayName("Adopted future that fails transitions to FAILED")
    void adoptedFuture_failsTransitionsToFailed() throws Exception {
        String session = "sess-adopted-2";
        String taskId = "task-adopted-fail";
        CompletableFuture<String> future = new CompletableFuture<>();

        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "general-purpose",
                session,
                new TaskRunSpec.AdoptedTaskRunSpec(future));

        future.completeExceptionally(new RuntimeException("agent crashed"));

        awaitCondition(
                () -> {
                    BackgroundTask t = repo.getTask(RuntimeContext.empty(), session, taskId);
                    return t != null && t.getTaskStatus() == TaskStatus.FAILED;
                });

        BackgroundTask failed = repo.getTask(RuntimeContext.empty(), session, taskId);
        assertEquals(TaskStatus.FAILED, failed.getTaskStatus());
    }

    @Test
    @DisplayName("Adopted future that is already completed registers as COMPLETED immediately")
    void adoptedFuture_alreadyCompleted() throws Exception {
        String session = "sess-adopted-3";
        String taskId = "task-adopted-pre";
        CompletableFuture<String> future = CompletableFuture.completedFuture("pre-completed");

        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "general-purpose",
                session,
                new TaskRunSpec.AdoptedTaskRunSpec(future));

        awaitCondition(
                () -> {
                    BackgroundTask t = repo.getTask(RuntimeContext.empty(), session, taskId);
                    return t != null && t.getTaskStatus() == TaskStatus.COMPLETED;
                });

        BackgroundTask task = repo.getTask(RuntimeContext.empty(), session, taskId);
        assertEquals("pre-completed", task.getResult());
    }

    @Test
    @DisplayName("Adopted future appears in task listing")
    void adoptedFuture_appearsInTaskList() throws Exception {
        String session = "sess-adopted-4";
        String taskId = "task-adopted-list";
        CompletableFuture<String> future = new CompletableFuture<>();

        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "general-purpose",
                session,
                new TaskRunSpec.AdoptedTaskRunSpec(future));

        Collection<BackgroundTask> tasks = repo.listTasks(RuntimeContext.empty(), session, null);
        assertTrue(tasks.stream().anyMatch(t -> t.getTaskId().equals(taskId)));

        future.complete("done");
    }

    @Test
    @DisplayName("Completed adopted future is eligible for push delivery")
    void adoptedFuture_eligibleForPushDelivery() throws Exception {
        String session = "sess-adopted-5";
        String taskId = "task-adopted-push";
        CompletableFuture<String> future = new CompletableFuture<>();

        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "general-purpose",
                session,
                new TaskRunSpec.AdoptedTaskRunSpec(future));

        future.complete("push me");

        awaitCondition(
                () -> {
                    BackgroundTask t = repo.getTask(RuntimeContext.empty(), session, taskId);
                    return t != null && t.getTaskStatus() == TaskStatus.COMPLETED;
                });

        List<TaskDelivery> deliveries = repo.findPendingDeliveries(RuntimeContext.empty(), session);
        assertTrue(
                deliveries.stream().anyMatch(d -> d.taskId().equals(taskId)),
                "Adopted task should appear in pending deliveries");

        TaskDelivery delivery =
                deliveries.stream()
                        .filter(d -> d.taskId().equals(taskId))
                        .findFirst()
                        .orElseThrow();
        assertEquals(TaskStatus.COMPLETED, delivery.status());
        assertEquals("push me", delivery.result());
    }
}
