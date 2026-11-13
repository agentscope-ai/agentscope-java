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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.journal.JournalException;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentExecution;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;

@Timeout(10)
class AgentSpawnToolExecutionHostTest {
    @Test
    void persistentSpawnAndSendRetainTheHostOwnedSession() {
        var calls = new AtomicInteger();
        var host =
                new SubagentExecution() {
                    @Override
                    public String childSessionId(RuntimeContext parent) {
                        return "host-owned-child";
                    }

                    @Override
                    public Mono<Msg> invoke(
                            Agent agent,
                            RuntimeContext parent,
                            RuntimeContext child,
                            Supplier<Mono<Msg>> local) {
                        assertEquals("host-owned-child", child.getSessionId());
                        calls.incrementAndGet();
                        return Mono.just(new AssistantMessage("host result"));
                    }
                };
        var tool = tool();
        var context =
                RuntimeContext.builder()
                        .sessionId("parent")
                        .userId("user")
                        .put(SubagentExecution.class, host)
                        .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                        .build();
        String first =
                assertText(
                        tool.agentSpawn(context, null, "worker", "first", "label", 5, null).block(),
                        ToolResultState.SUCCESS);
        String second =
                assertText(
                        tool.agentSpawn(context, null, "worker", "second", "label", 5, null)
                                .block(),
                        ToolResultState.SUCCESS);
        assertTrue(first.contains("session_id: host-owned-child"), first);
        assertTrue(second.contains("session_id: host-owned-child"), second);
        assertEquals(2, calls.get());
    }

    @Test
    void durableHostFailuresPropagateInsteadOfBecomingSuccessfulToolText() {
        var failure = new JournalException("durable host unavailable");
        var host =
                new SubagentExecution() {
                    @Override
                    public Mono<Msg> invoke(
                            Agent agent,
                            RuntimeContext parent,
                            RuntimeContext child,
                            Supplier<Mono<Msg>> local) {
                        return Mono.error(failure);
                    }
                };
        var context =
                RuntimeContext.builder()
                        .sessionId("parent")
                        .put(SubagentExecution.class, host)
                        .build();
        assertSame(
                failure,
                assertThrows(
                        JournalException.class,
                        () ->
                                tool().agentSpawn(context, null, "worker", "task", null, 5, null)
                                        .block()));
    }

    private static AgentSpawnTool tool() {
        var declaration =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("worker")
                        .persistSession(true)
                        .build();
        var manager =
                new DefaultAgentManager(
                        List.of(
                                new SubagentEntry(
                                        "worker",
                                        "worker",
                                        parent -> mock(Agent.class),
                                        declaration)),
                        null);
        return new AgentSpawnTool(manager, mock(TaskRepository.class), 0);
    }

    @Test
    void hostOwnsChildStateWithoutReadingOrMutatingPrototypeSlots() {
        var prototype = mock(HarnessAgent.class);
        var host =
                new SubagentExecution() {
                    @Override
                    public boolean ownsChildState() {
                        return true;
                    }

                    @Override
                    public Mono<Msg> invoke(
                            Agent agent,
                            RuntimeContext parent,
                            RuntimeContext child,
                            Supplier<Mono<Msg>> local) {
                        return Mono.just(new AssistantMessage("durable child result"));
                    }
                };
        var state = AgentState.builder().build();
        state.getPlanModeContext().setPlanActive(true);
        var manager =
                new DefaultAgentManager(
                        List.of(new SubagentEntry("worker", "worker", parent -> prototype)), null);
        var tool = new AgentSpawnTool(manager, mock(TaskRepository.class), 0);
        var context =
                RuntimeContext.builder()
                        .sessionId("parent")
                        .userId("user")
                        .put(SubagentExecution.class, host)
                        .build();
        assertTrue(
                assertText(
                                tool.agentSpawn(context, state, "worker", "task", null, 5, null)
                                        .block(),
                                ToolResultState.SUCCESS)
                        .contains("durable child result"));
        verify(prototype, never()).enterPlanMode(anyString(), anyString());
        verify(prototype, never()).getDelegate();
        assertTrue(state.getPlanModeContext().isPlanActive());
    }
}
