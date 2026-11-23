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

import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.tool.TaskTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class DynamicSubagentsMiddlewareIsolationTest {
    @Test
    void terminalSummaryRequiresActualOutput() {
        var repository = mock(TaskRepository.class);
        when(repository.listTasks(any(), any(), any()))
                .thenReturn(
                        List.of(
                                new BackgroundTask(
                                        "done",
                                        "worker",
                                        CompletableFuture.completedFuture("result"))));
        var middleware =
                new DynamicSubagentsMiddleware(
                        List.of(),
                        null,
                        null,
                        null,
                        new DefaultAgentManager(List.of(), mock(WorkspaceManager.class)),
                        null,
                        repository);
        var toolkit = new Toolkit();
        toolkit.registerTool(new TaskTool(repository));
        Agent agent = mock(Agent.class);
        when(agent.getToolkit()).thenReturn(toolkit);
        var rc =
                RuntimeContext.builder()
                        .sessionId("test")
                        .agentState(AgentState.builder().build())
                        .build();
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();
        middleware
                .onReasoning(
                        agent,
                        rc,
                        new ReasoningInput(List.of(), toolkit.getToolSchemas(), null),
                        input -> {
                            seen.set(input);
                            return Flux.empty();
                        })
                .blockLast();
        String summary = tail(seen.get());
        assertTrue(summary.contains("status: completed"));
        assertTrue(summary.contains("Status only, not task results"));
        assertTrue(summary.contains("use task_output"));
        assertFalse(summary.contains("no need to call task_output"));
    }

    @Test
    void interleavedReasoningAndHostRefreshKeepEachDeclarationSnapshot() {
        var filesystem = mock(AbstractFilesystem.class);
        when(filesystem.glob(any(RuntimeContext.class), eq("*.md"), eq("subagents")))
                .thenAnswer(
                        invocation -> {
                            RuntimeContext rc = invocation.getArgument(0);
                            return GlobResult.success(
                                    List.of(
                                            FileInfo.ofFile(
                                                    "subagents/" + rc.getUserId() + ".md", 0, "")));
                        });
        when(filesystem.read(any(RuntimeContext.class), any(String.class), eq(0), eq(0)))
                .thenAnswer(
                        invocation -> {
                            RuntimeContext rc = invocation.getArgument(0);
                            return ReadResult.success(
                                    FileData.create(
                                            "---\ndescription: "
                                                    + rc.getSessionId()
                                                    + "\n---\nworker"));
                        });
        var workspaceManager = mock(WorkspaceManager.class);
        var baseline = new DefaultAgentManager(List.of(), workspaceManager);
        var created = new ArrayList<String>();
        var middleware =
                new DynamicSubagentsMiddleware(
                        List.of(),
                        filesystem,
                        null,
                        declaration ->
                                rc -> {
                                    created.add(
                                            declaration.getName()
                                                    + ":"
                                                    + declaration.getDescription());
                                    return mock(Agent.class);
                                },
                        baseline,
                        null,
                        mock(TaskRepository.class));
        var a = RuntimeContext.builder().userId("alice").sessionId("parent-a").build();
        var b = RuntimeContext.builder().userId("bob").sessionId("parent-b").build();
        ReasoningInput promptA = reason(middleware, a);
        var snapshotA = a.get(AgentSpawnTool.CTX_AGENT_MANAGER, DefaultAgentManager.class);
        ReasoningInput promptB = reason(middleware, b);
        var snapshotB = b.get(AgentSpawnTool.CTX_AGENT_MANAGER, DefaultAgentManager.class);
        assertNotSame(snapshotA, snapshotB);
        assertSame(workspaceManager, snapshotA.getWorkspaceManager());
        assertSame(baseline, middleware.getAgentManager());
        assertTrue(baseline.getAgentFactories().isEmpty());
        assertTrue(tail(promptA).contains("- `alice`: parent-a"));
        assertFalse(tail(promptA).contains("- `bob`:"));
        assertTrue(tail(promptB).contains("- `bob`: parent-b"));
        assertFalse(tail(promptB).contains("- `alice`:"));

        var tool = (AgentSpawnTool) middleware.getTools().get(0);
        var exposures = new ArrayList<String>();
        middleware.setGatewayBridge(
                (agentId, sessionId, agent, replyTo) -> {
                    exposures.add(agentId);
                    return new io.agentscope.harness.agent.gateway.SubagentGatewayBridge
                            .ExposeResult("thread");
                });
        assertSame(
                tool,
                middleware.getTools().get(0),
                "gateway wiring must retain the registered tool");
        assertText(
                tool.agentSpawn(a, null, "alice", null, null, 1, true).block(),
                ToolResultState.SUCCESS);
        assertText(
                tool.agentSpawn(b, null, "bob", null, null, 1, null).block(),
                ToolResultState.SUCCESS);
        assertEquals(List.of("alice:parent-a", "bob:parent-b"), created);
        assertEquals(List.of("alice"), exposures);

        var refreshedB = middleware.refreshAgentManager(b);
        assertNotSame(snapshotB, refreshedB);
        assertSame(refreshedB, b.get(AgentSpawnTool.CTX_AGENT_MANAGER));
        assertSame(snapshotA, a.get(AgentSpawnTool.CTX_AGENT_MANAGER));
        assertSame(workspaceManager, refreshedB.getWorkspaceManager());
        assertTrue(refreshedB.hasAgent("bob"));
        assertFalse(refreshedB.hasAgent("alice"));
        assertTrue(baseline.getAgentFactories().isEmpty());
        assertText(
                tool.agentSpawn(a, null, "alice", null, null, 1, null).block(),
                ToolResultState.SUCCESS);
        assertEquals("alice:parent-a", created.get(2));
    }

    private static ReasoningInput reason(DynamicSubagentsMiddleware middleware, RuntimeContext rc) {
        var input = new ReasoningInput(List.<Msg>of(), List.of(), null);
        var captured = new AtomicReference<ReasoningInput>();
        middleware
                .onReasoning(
                        null,
                        rc,
                        input,
                        next -> {
                            captured.set(next);
                            return Flux.empty();
                        })
                .blockLast();
        return captured.get();
    }

    private static String tail(ReasoningInput input) {
        return input.messages().get(input.messages().size() - 1).getTextContent();
    }
}
