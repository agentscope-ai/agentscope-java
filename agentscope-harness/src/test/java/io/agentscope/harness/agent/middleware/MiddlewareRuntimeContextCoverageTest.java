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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class MiddlewareRuntimeContextCoverageTest {

    @Test
    void dynamicSubagentsMiddlewarePassesRuntimeContextToTaskSummary() {
        RuntimeContext runtimeContext = runtimeContext("dyn-sub-session");
        Agent agent = mock(Agent.class);
        when(agent.getRuntimeContext()).thenReturn(runtimeContext);
        TaskRepository taskRepository = mock(TaskRepository.class);
        when(taskRepository.listTasks(
                        same(runtimeContext), eq("dyn-sub-session"), eq((TaskStatus) null)))
                .thenReturn(List.of());
        DynamicSubagentsMiddleware middleware =
                new DynamicSubagentsMiddleware(
                        List.of(), null, null, null, null, new Object(), taskRepository);

        middleware
                .onReasoning(
                        agent,
                        runtimeContext,
                        new ReasoningInput(List.of(), List.of(), null),
                        in -> Flux.<AgentEvent>empty())
                .blockLast();

        verify(taskRepository)
                .listTasks(same(runtimeContext), eq("dyn-sub-session"), eq((TaskStatus) null));
    }

    @Test
    void harnessSkillMiddlewarePassesRuntimeContextToVisibilityFilter() {
        RuntimeContext runtimeContext = runtimeContext("harness-skill-session");
        Agent agent = mock(Agent.class);
        when(agent.getRuntimeContext()).thenReturn(runtimeContext);
        AgentSkillRepository repo = mock(AgentSkillRepository.class);
        when(repo.getSource()).thenReturn("repo");
        when(repo.getAllSkills())
                .thenReturn(
                        List.of(
                                new AgentSkill(
                                        "sample",
                                        "Sample skill",
                                        "Use the sample skill.",
                                        Map.of())));
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        SkillVisibilityFilter filter =
                (all, ctx) -> {
                    seen.set(ctx);
                    return all;
                };
        HarnessSkillMiddleware middleware =
                new HarnessSkillMiddleware(List.of(repo), null, null, filter);

        String prompt = middleware.onSystemPrompt(agent, runtimeContext, "BASE").block();

        assertNotNull(prompt);
        assertSame(runtimeContext, seen.get());
    }

    @Test
    void compactionMiddlewareAllowsReasoningWithAgentRuntimeContext() {
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getRuntimeContext()).thenReturn(runtimeContext("compaction-session"));
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        mock(WorkspaceManager.class),
                        mock(Model.class),
                        CompactionConfig.builder().triggerMessages(10).triggerTokens(10).build());

        ReasoningInput input = new ReasoningInput(List.of(), List.of(), null);
        AtomicReference<ReasoningInput> forwarded = new AtomicReference<>();
        middleware
                .onReasoning(
                        agent,
                        runtimeContext("compaction-session"),
                        input,
                        in -> {
                            forwarded.set(in);
                            return Flux.<AgentEvent>empty();
                        })
                .blockLast();

        assertSame(input, forwarded.get());
    }

    @Test
    void memoryFlushMiddlewareCompletesWhenAgentHasRuntimeContext() {
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getRuntimeContext()).thenReturn(runtimeContext("flush-session"));
        when(agent.getAgentState())
                .thenReturn(AgentState.builder().sessionId("flush-session").build());
        MemoryFlushMiddleware middleware =
                new MemoryFlushMiddleware(mock(WorkspaceManager.class), mock(Model.class));

        middleware
                .onAgent(
                        agent,
                        runtimeContext("flush-session"),
                        new AgentInput(List.of()),
                        in -> Flux.<AgentEvent>empty())
                .blockLast();

        assertNotNull(agent);
    }

    @Test
    void memoryMaintenanceMiddlewareCompletesWhenAgentHasRuntimeContext() {
        Agent agent = mock(Agent.class);
        when(agent.getRuntimeContext()).thenReturn(runtimeContext("maint-session"));
        WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
        when(workspaceManager.getFilesystem()).thenReturn(null);
        MemoryMaintenanceMiddleware middleware =
                new MemoryMaintenanceMiddleware(workspaceManager, null, 1, 1, Duration.ZERO);

        middleware
                .onAgent(
                        agent,
                        runtimeContext("maint-session"),
                        new AgentInput(List.of()),
                        in -> Flux.<AgentEvent>empty())
                .blockLast();

        assertNotNull(agent);
    }

    @Test
    void subagentsMiddlewarePassesRuntimeContextToTaskRepository() {
        RuntimeContext runtimeContext = runtimeContext("subagents-session");
        Agent agent = mock(Agent.class);
        when(agent.getRuntimeContext()).thenReturn(runtimeContext);
        TaskRepository taskRepository = mock(TaskRepository.class);
        when(taskRepository.findPendingDeliveries(same(runtimeContext), eq("subagents-session")))
                .thenReturn(List.of());
        when(taskRepository.listTasks(
                        same(runtimeContext), eq("subagents-session"), eq((TaskStatus) null)))
                .thenReturn(List.of());
        SubagentsMiddleware middleware =
                new SubagentsMiddleware(
                        List.of(new SubagentEntry("worker", "Worker", mock(SubagentFactory.class))),
                        taskRepository,
                        (WorkspaceManager) null);
        AtomicReference<ReasoningInput> forwarded = new AtomicReference<>();

        middleware
                .onReasoning(
                        agent,
                        runtimeContext,
                        new ReasoningInput(List.of(), List.of(), null),
                        in -> {
                            forwarded.set(in);
                            return Flux.<AgentEvent>empty();
                        })
                .blockLast();

        assertNotNull(forwarded.get());
        assertTrue(!forwarded.get().messages().isEmpty());
        verify(taskRepository).findPendingDeliveries(same(runtimeContext), eq("subagents-session"));
        verify(taskRepository)
                .listTasks(same(runtimeContext), eq("subagents-session"), eq((TaskStatus) null));
    }

    @Test
    void toolResultEvictionMiddlewareCompletesWhenAgentHasRuntimeContext() {
        Agent agent = mock(Agent.class);
        when(agent.getRuntimeContext()).thenReturn(runtimeContext("eviction-session"));
        when(agent.getAgentState()).thenReturn(null);
        ToolResultEvictionMiddleware middleware =
                new ToolResultEvictionMiddleware(
                        mock(AbstractFilesystem.class), ToolResultEvictionConfig.defaults());

        middleware
                .onActing(
                        agent,
                        runtimeContext("eviction-session"),
                        new ActingInput(List.of()),
                        in -> Flux.<AgentEvent>empty())
                .blockLast();

        assertNotNull(agent);
    }

    private static RuntimeContext runtimeContext(String sessionId) {
        return RuntimeContext.builder().sessionId(sessionId).build();
    }
}
