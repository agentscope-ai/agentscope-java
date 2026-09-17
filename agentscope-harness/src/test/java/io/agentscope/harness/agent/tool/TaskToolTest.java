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

import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertText;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class TaskToolTest {
    private final RuntimeContext context = RuntimeContext.empty();
    private final TaskRepository repository = mock(TaskRepository.class);
    private final TaskTool tool = new TaskTool(repository);

    @Test
    void invalidTaskIdsAreErrorsBeforeRepositoryAccess() {
        assertTrue(
                assertText(tool.taskOutput(context, " ", false, null), ToolResultState.ERROR)
                        .contains("status: invalid_request"));
        assertTrue(
                assertText(tool.taskCancel(context, null), ToolResultState.ERROR)
                        .contains("status: invalid_request"));
        verifyNoInteractions(repository);
    }

    @Test
    void unknownTaskIdsRetainNotFoundStatus() {
        String output =
                assertText(tool.taskOutput(context, "missing", false, null), ToolResultState.ERROR);
        assertTrue(output.contains("status: not_found\ntask_id: missing"));
        assertTrue(output.contains("This is not running"));
        assertTrue(
                assertText(tool.taskCancel(context, "missing"), ToolResultState.ERROR)
                        .contains("status: not_found\ntask_id: missing"));
    }

    @Test
    void completedErrorLookingContentIsRetrievedSuccessfullyAndMarkedDelivered() {
        BackgroundTask task =
                new BackgroundTask(
                        "done", "agent", CompletableFuture.completedFuture("Error: log content"));
        when(repository.getTask(context, context.getSessionId(), "done")).thenReturn(task);

        String output =
                assertText(tool.taskOutput(context, "done", false, null), ToolResultState.SUCCESS);

        assertTrue(output.contains("Error: log content"));
        verify(repository).markDelivered(context, context.getSessionId(), "done");
    }

    @Test
    void interruptedWaitPreservesThreadFlagAndReportsInterruptedState() {
        BackgroundTask task = new BackgroundTask("pending", "agent", new CompletableFuture<>());
        when(repository.getTask(context, context.getSessionId(), "pending")).thenReturn(task);
        Thread.currentThread().interrupt();
        try {
            String output =
                    assertText(
                            tool.taskOutput(context, "pending", true, 100L),
                            ToolResultState.INTERRUPTED);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(output.contains("status: interrupted\ntask_id: pending"));
        } finally {
            Thread.interrupted();
        }
    }
}
