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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two read paths must report the same status for the same task. A cancellation request can
 * arrive after the task already finished (the force-sync timeout path calls the repository
 * directly), and it must not make one view report CANCELLED while the other reports COMPLETED.
 */
class WorkspaceTaskRepositoryStatusReconcileTest {

    private static final String SESSION = "session-1";
    private static final String TASK = "task-1";

    @TempDir Path tempDir;

    private WorkspaceTaskRepository repo;

    @BeforeEach
    void setUp() {
        repo = WorkspaceTaskRepository.forTests(new WorkspaceManager(tempDir), "test-agent");
    }

    @AfterEach
    void tearDown() {
        repo.shutdown();
    }

    @Test
    void cancelledCompletedTaskReadsTheSameThroughBothPaths() throws Exception {
        repo.putTask(
                RuntimeContext.empty(),
                TASK,
                "sub-agent",
                SESSION,
                new TaskRunSpec.LocalTaskRunSpec(() -> "done"));

        awaitStatus(TaskStatus.COMPLETED);
        awaitWorkspaceStatus(TaskStatus.COMPLETED);

        // A late cancellation lands on a task that already completed.
        repo.cancelTask(RuntimeContext.empty(), SESSION, TASK);

        TaskStatus fromGet = getStatus();
        TaskStatus fromList = listStatus();
        assertEquals(
                fromList,
                fromGet,
                "getTask and listTasks disagree: get=" + fromGet + " list=" + fromList);
        assertEquals(TaskStatus.COMPLETED, fromGet, "a completed task must not become cancelled");
    }

    private TaskStatus getStatus() {
        var task = repo.getTask(RuntimeContext.empty(), SESSION, TASK);
        return task == null ? null : task.getTaskStatus();
    }

    private TaskStatus listStatus() {
        return repo.listTasks(RuntimeContext.empty(), SESSION, null).stream()
                .filter(task -> TASK.equals(task.getTaskId()))
                .findFirst()
                .map(BackgroundTask::getTaskStatus)
                .orElse(null);
    }

    private void awaitStatus(TaskStatus expected) throws Exception {
        await(() -> getStatus() == expected, "local handle never reached " + expected);
    }

    private void awaitWorkspaceStatus(TaskStatus expected) throws Exception {
        await(
                () -> {
                    var records =
                            repo.listTasks(RuntimeContext.empty(), SESSION, null).stream()
                                    .filter(task -> TASK.equals(task.getTaskId()))
                                    .findFirst();
                    return records.isPresent() && records.get().getTaskStatus() == expected;
                },
                "workspace record never reached " + expected);
    }

    private static void await(Callable<Boolean> condition, String message) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.call()) {
            if (System.nanoTime() >= deadline) throw new AssertionError(message);
            Thread.sleep(10);
        }
    }
}
