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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

/** Regression coverage for task-specific cancellation of timeout-zero local subagents. */
class AgentSpawnToolBackgroundCancellationTest {

    @TempDir Path tempDir;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void timeoutZeroCancelStopsOnlyTheTrackedExecution() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch firstCancelled = new CountDownLatch(1);
        CountDownLatch secondCancelled = new CountDownLatch(1);
        Agent worker = Mockito.mock(Agent.class);
        when(worker.call(anyList()))
                .thenAnswer(
                        invocation -> {
                            List<Msg> messages = invocation.getArgument(0);
                            String prompt = messages.get(0).getTextContent();
                            CountDownLatch started;
                            CountDownLatch cancelled;
                            if ("run".equals(prompt)) {
                                started = firstStarted;
                                cancelled = firstCancelled;
                            } else if ("run again".equals(prompt)) {
                                started = secondStarted;
                                cancelled = secondCancelled;
                            } else {
                                throw new AssertionError("Unexpected prompt: " + prompt);
                            }
                            return Mono.<Msg>never()
                                    .doOnSubscribe(unused -> started.countDown())
                                    .doOnCancel(cancelled::countDown);
                        });

        DefaultAgentManager manager =
                new DefaultAgentManager(
                        List.of(new SubagentEntry("worker", "blocking worker", rc -> worker)),
                        null);
        WorkspaceTaskRepository repository =
                WorkspaceTaskRepository.forTests(
                        new WorkspaceManager(tempDir), "background-cancel-parent");
        try {
            AgentSpawnTool tool = new AgentSpawnTool(manager, repository, 0);
            RuntimeContext parentContext =
                    RuntimeContext.builder().userId("user-1").sessionId("parent-session").build();

            String firstAccepted =
                    tool.agentSpawn(parentContext, null, "worker", "run", null, 0, null).block();
            String secondAccepted =
                    tool.agentSpawn(parentContext, null, "worker", "run again", null, 0, null)
                            .block();
            assertNotNull(firstAccepted);
            assertNotNull(secondAccepted);
            String firstTaskId = extractField(firstAccepted, "task_id");
            String secondTaskId = extractField(secondAccepted, "task_id");
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));

            assertTrue(repository.cancelTask(parentContext, "parent-session", firstTaskId));
            assertTrue(
                    firstCancelled.await(5, TimeUnit.SECONDS),
                    "cancelling the repository task must cancel the exact blocked subscription");
            assertEquals(
                    1L,
                    secondCancelled.getCount(),
                    "cancelling one task must not cancel another run using the same Agent"
                            + " instance");

            BackgroundTask firstTask =
                    repository.getTask(parentContext, "parent-session", firstTaskId);
            BackgroundTask secondTask =
                    repository.getTask(parentContext, "parent-session", secondTaskId);
            assertNotNull(firstTask);
            assertNotNull(secondTask);
            assertEquals(TaskStatus.CANCELLED, firstTask.getTaskStatus());
            assertEquals(TaskStatus.RUNNING, secondTask.getTaskStatus());
            // timeout=0 cancellation is driven by the task's real execution Future. It must not
            // issue an agent-wide interrupt that could stop another queued run on the same child.
            verify(worker, never()).interrupt();

            assertTrue(repository.cancelTask(parentContext, "parent-session", secondTaskId));
            assertTrue(secondCancelled.await(5, TimeUnit.SECONDS));
        } finally {
            repository.shutdown();
        }
    }

    private static String extractField(String text, String field) {
        String prefix = field + ": ";
        return text.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()).trim())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + field + " in: " + text));
    }
}
