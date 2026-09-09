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
package io.agentscope.extensions.aistio.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.aistio.model.AgentTaskAssignment;
import io.agentscope.extensions.aistio.transport.CollaborationClient;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class HarnessAgentTaskOutcomeTest {
    private final CollaborationClient client = mock(CollaborationClient.class);
    private final HarnessAgent agent = mock(HarnessAgent.class);
    private final TaskRepository repo = mock(TaskRepository.class);
    private final AgentTaskAssignment assignment =
            new AgentTaskAssignment(
                    "attempt",
                    "task",
                    "run",
                    "node",
                    1,
                    "dispatch",
                    "",
                    "secret-token",
                    "attempt-secret",
                    "assigned-session",
                    new byte[0],
                    1);

    private HarnessAgentTaskStarter starter() throws Exception {
        when(client.taskContext("task", "secret-token"))
                .thenReturn(
                        ControlPlaneHttpClient.mapper()
                                .readTree(
                                        "{\"task\":{\"status\":\"running\",\"version\":4},\"taskToken\":\"secret-token\",\"availableActions\":[]}"
                                            + " "));
        when(client.tools(anyString(), anyString()))
                .thenReturn(ControlPlaneHttpClient.mapper().createArrayNode());
        when(agent.getToolkit()).thenReturn(new Toolkit());
        when(agent.getTaskRepository()).thenReturn(repo);
        when(repo.listTasks(any(), any(), isNull())).thenReturn(List.of());
        return new HarnessAgentTaskStarter(() -> agent, client);
    }

    @Test
    void plainWaitingPromiseCannotBecomeSuccessfulBusinessCompletion() throws Exception {
        var starter = starter();
        when(agent.call(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(
                        Mono.just(
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .textContent("I will keep checking my subagent")
                                        .build()));
        starter.start(assignment).block();
        verify(agent, times(2)).call(any(Msg.class), any(RuntimeContext.class));
        verify(client)
                .finish(
                        eq("task"),
                        eq("secret-token"),
                        eq(4L),
                        eq("blocked"),
                        contains("missing_outcome"),
                        eq(""),
                        eq(List.of()),
                        eq(List.of()));
    }

    @Test
    void waitsForRealDependencyAndResumesSameSessionWithActualResult() throws Exception {
        var starter = starter();
        var future = new CompletableFuture<String>();
        var child = new BackgroundTask("child", "researcher", future);
        when(repo.listTasks(any(), eq("assigned-session"), isNull())).thenReturn(List.of(child));
        when(repo.getTask(any(), eq("assigned-session"), eq("child"))).thenReturn(child);
        var calls = new AtomicInteger();
        when(agent.call(any(Msg.class), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            Msg input = invocation.getArgument(0);
                            RuntimeContext ctx = invocation.getArgument(1);
                            assertEquals("assigned-session", ctx.getSessionId());
                            assertEquals(
                                    Boolean.TRUE,
                                    ctx.get(TaskRepository.SUPPRESS_COMPLETION_CALLBACK));
                            if (calls.getAndIncrement() == 0) {
                                assertFalse(input.getTextContent().contains("secret-token"));
                                ctx.get(AgentTaskOutcome.State.class)
                                        .submit(
                                                new AgentTaskOutcome(
                                                        "waiting",
                                                        "",
                                                        "collect EV evidence",
                                                        List.of("child")));
                                future.complete("EV research with evidence");
                            } else {
                                assertTrue(
                                        input.getTextContent()
                                                .contains("EV research with evidence"));
                                ctx.get(AgentTaskOutcome.State.class)
                                        .submit(
                                                new AgentTaskOutcome(
                                                        "succeeded",
                                                        "verified EV report",
                                                        "",
                                                        List.of()));
                            }
                            return Mono.just(
                                    Msg.builder()
                                            .role(MsgRole.ASSISTANT)
                                            .textContent("done")
                                            .build());
                        });
        starter.start(assignment).block();
        assertEquals(2, calls.get());
        verify(client)
                .finish(
                        eq("task"),
                        eq("secret-token"),
                        eq(4L),
                        eq("succeeded"),
                        eq(""),
                        eq("verified EV report"),
                        eq(List.of()),
                        eq(List.of()));
    }

    @Test
    void nonexistentDependencyBlocksInsteadOfPollingForever() throws Exception {
        var starter = starter();
        when(agent.call(any(Msg.class), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            RuntimeContext ctx = invocation.getArgument(1);
                            ctx.get(AgentTaskOutcome.State.class)
                                    .submit(
                                            new AgentTaskOutcome(
                                                    "waiting",
                                                    "partial",
                                                    "await child",
                                                    List.of("missing")));
                            return Mono.just(
                                    Msg.builder()
                                            .role(MsgRole.ASSISTANT)
                                            .textContent("waiting")
                                            .build());
                        });
        starter.start(assignment).block();
        verify(client)
                .finish(
                        any(),
                        any(),
                        eq(4L),
                        eq("blocked"),
                        contains("dependency_not_found"),
                        eq("partial"),
                        any(),
                        any());
    }

    @Test
    void prematureSuccessIsRejectedAndUnfinishedDependencyIsCancelled() throws Exception {
        var starter = starter();
        var child = new BackgroundTask("child", "researcher", new CompletableFuture<>());
        when(repo.listTasks(any(), eq("assigned-session"), isNull())).thenReturn(List.of(child));
        when(agent.call(any(Msg.class), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            RuntimeContext ctx = invocation.getArgument(1);
                            ctx.get(AgentTaskOutcome.State.class)
                                    .submit(
                                            new AgentTaskOutcome(
                                                    "succeeded",
                                                    "premature report",
                                                    "",
                                                    List.of()));
                            return Mono.just(
                                    Msg.builder()
                                            .role(MsgRole.ASSISTANT)
                                            .textContent("done")
                                            .build());
                        });
        starter.start(assignment).block();
        verify(client)
                .finish(
                        any(),
                        any(),
                        eq(4L),
                        eq("blocked"),
                        contains("uncollected_dependencies"),
                        eq("premature report"),
                        any(),
                        any());
        verify(repo).cancelTask(any(), eq("assigned-session"), eq("child"));
    }

    @Test
    void abnormalRuntimeStopCannotCommitSubmittedSuccess() throws Exception {
        var starter = starter();
        when(agent.call(any(Msg.class), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            RuntimeContext ctx = invocation.getArgument(1);
                            ctx.get(AgentTaskOutcome.State.class)
                                    .submit(
                                            new AgentTaskOutcome(
                                                    "succeeded", "partial report", "", List.of()));
                            return Mono.just(
                                    Msg.builder()
                                            .role(MsgRole.ASSISTANT)
                                            .textContent("stopped")
                                            .generateReason(
                                                    io.agentscope.core.message.GenerateReason
                                                            .MAX_ITERATIONS)
                                            .build());
                        });
        starter.start(assignment).block();
        verify(client)
                .finish(
                        any(),
                        any(),
                        eq(4L),
                        eq("blocked"),
                        contains("MAX_ITERATIONS"),
                        eq("partial report"),
                        any(),
                        any());
    }
}
