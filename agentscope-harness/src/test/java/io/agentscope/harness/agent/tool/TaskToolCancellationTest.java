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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskCancellation;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class TaskToolCancellationTest {

    @Test
    void taskCancelReportsImmediateStopAcknowledgement() {
        CompletableFuture<TaskCancellation.Termination> signal = new CompletableFuture<>();
        StubRepository repository = new StubRepository(signal);
        signal.complete(
                new TaskCancellation.Termination(
                        repository.identity,
                        TaskCancellation.TerminationStatus.COOPERATIVE_STOP_CONFIRMED,
                        "stopped"));

        String output =
                new TaskTool(repository)
                        .taskCancel(
                                RuntimeContext.builder().sessionId("session").build(), "task-1");

        assertTrue(output.contains("request_status: accepted"));
        assertTrue(output.contains("stop_status: cooperative_stop_confirmed"));
    }

    @Test
    void taskCancelReportsPendingWhenExecutionHasNotStopped() {
        StubRepository repository = new StubRepository(new CompletableFuture<>());

        String output = new TaskTool(repository).taskCancel(RuntimeContext.empty(), "task-1");

        assertTrue(output.contains("request_status: accepted"));
        assertTrue(output.contains("stop_status: pending"));
    }

    private static final class StubRepository implements TaskRepository {

        private final CompletableFuture<TaskCancellation.Termination> signal;
        private final CompletableFuture<String> taskFuture = new CompletableFuture<>();
        private final TaskCancellation.ExecutionIdentity identity =
                new TaskCancellation.ExecutionIdentity(
                        "session",
                        "task-1",
                        "agent",
                        null,
                        TaskCancellation.ExecutionKind.LOCAL,
                        null);

        private StubRepository(CompletableFuture<TaskCancellation.Termination> signal) {
            this.signal = signal;
        }

        @Override
        public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
            return new BackgroundTask(taskId, "agent", taskFuture);
        }

        @Override
        public TaskCancellation cancelTaskWithAcknowledgement(
                RuntimeContext rc, String sessionId, String taskId) {
            return new TaskCancellation(identity, TaskCancellation.RequestStatus.ACCEPTED, signal);
        }

        @Override
        public BackgroundTask putTask(
                RuntimeContext rc,
                String taskId,
                String subAgentId,
                String sessionId,
                TaskRunSpec spec) {
            return null;
        }

        @Override
        public void removeTask(RuntimeContext rc, String sessionId, String taskId) {}

        @Override
        public void clear() {}

        @Override
        public Collection<BackgroundTask> listTasks(
                RuntimeContext rc, String sessionId, TaskStatus filter) {
            return List.of();
        }

        @Override
        public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
            return true;
        }
    }
}
