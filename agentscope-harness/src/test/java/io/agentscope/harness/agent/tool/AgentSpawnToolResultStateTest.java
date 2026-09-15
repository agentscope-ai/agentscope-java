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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.RemoteSubagentTransport;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

class AgentSpawnToolResultStateTest {
    private final Agent agent = mock(Agent.class);
    private final TaskRepository repository = mock(TaskRepository.class);
    private final RuntimeContext context = RuntimeContext.builder().sessionId("parent").build();

    private AgentSpawnTool tool(SubagentDeclaration declaration) {
        return new AgentSpawnTool(
                new DefaultAgentManager(
                        List.of(new SubagentEntry("worker", "worker", rc -> agent, declaration)),
                        null),
                repository,
                0);
    }

    private AgentSpawnTool localTool(boolean persist) {
        return tool(
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("worker")
                        .inlineAgentsBody("worker")
                        .persistSession(persist)
                        .build());
    }

    private static String key(ToolResultBlock result) {
        return assertText(result, ToolResultState.SUCCESS)
                .lines()
                .filter(line -> line.startsWith("agent_key: "))
                .findFirst()
                .orElseThrow()
                .substring("agent_key: ".length());
    }

    @Test
    void spawnAndSendFailuresCarryErrorState() {
        AgentSpawnTool tool = localTool(false);
        assertText(
                tool.agentSpawn(context, null, "missing", null, null, 1, null).block(),
                ToolResultState.ERROR);
        assertText(
                tool.agentSend(context, null, null, null, "hello", 1).block(),
                ToolResultState.ERROR);
        assertText(
                tool.agentSend(context, null, "key", "label", "hello", 1).block(),
                ToolResultState.ERROR);
        assertText(
                tool.agentSend(context, null, "missing", null, "hello", 1).block(),
                ToolResultState.ERROR);
        assertText(
                tool.agentSend(context, null, null, "missing", "hello", 1).block(),
                ToolResultState.ERROR);
        assertText(
                tool.agentSend(context, null, "key", null, " ", 1).block(), ToolResultState.ERROR);
        assertText(
                tool.agentSpawn(context, null, "worker", null, "label", 1, null).block(),
                ToolResultState.SUCCESS);
        assertText(
                tool.agentSpawn(context, null, "worker", null, "label", 1, null).block(),
                ToolResultState.ERROR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Error: connection refused", "[ERROR] log content"})
    void syncReplyIsSuccessfulRegardlessOfText(String reply) {
        when(agent.call(anyList()))
                .thenReturn(
                        Mono.just(
                                Msg.builder().role(MsgRole.ASSISTANT).textContent(reply).build()));
        AgentSpawnTool tool = localTool(true);
        ToolResultBlock spawned =
                tool.agentSpawn(context, null, "worker", "go", "worker", 1, null).block();
        String agentKey = key(spawned);
        assertTrue(assertText(spawned, ToolResultState.SUCCESS).endsWith(reply));
        assertTrue(
                assertText(
                                tool.agentSend(context, null, agentKey, null, "next", 1).block(),
                                ToolResultState.SUCCESS)
                        .endsWith(reply));
        assertTrue(
                assertText(
                                tool.agentSpawn(context, null, "worker", "again", "worker", 1, null)
                                        .block(),
                                ToolResultState.SUCCESS)
                        .endsWith(reply));
        assertText(tool.agentList(), ToolResultState.SUCCESS);
    }

    @Test
    void executionExceptionIsNotASuccessfulReply() {
        when(agent.call(anyList()))
                .thenReturn(Mono.error(new IllegalStateException("child failed")));
        AgentSpawnTool tool = localTool(true);
        String agentKey =
                key(tool.agentSpawn(context, null, "worker", null, "worker", 1, null).block());
        assertTrue(
                assertText(
                                tool.agentSend(context, null, agentKey, null, "go", 1).block(),
                                ToolResultState.ERROR)
                        .contains("child failed"));
        assertText(
                tool.agentSpawn(context, null, "worker", "go", "worker", 1, null).block(),
                ToolResultState.ERROR);
    }

    @Test
    void asyncSubmissionAndReuseAreSuccessful() {
        AgentSpawnTool tool = localTool(true);
        assertText(tool.agentList(), ToolResultState.SUCCESS);
        String agentKey =
                key(tool.agentSpawn(context, null, "worker", "go", "worker", 0, null).block());
        assertText(
                tool.agentSpawn(context, null, "worker", null, "worker", 0, null).block(),
                ToolResultState.SUCCESS);
        assertText(
                tool.agentSpawn(context, null, "worker", "again", "worker", 0, null).block(),
                ToolResultState.SUCCESS);
        assertText(
                tool.agentSend(context, null, agentKey, null, "again", 0).block(),
                ToolResultState.SUCCESS);
    }

    @Test
    void toolkitPreservesReactiveResultStateAndUnquotedOutput() {
        AgentSpawnTool tool = localTool(false);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        ToolUseBlock call =
                ToolUseBlock.builder()
                        .id("call")
                        .name("agent_send")
                        .input(Map.of("agent_key", "missing", "message", "hello"))
                        .content("{\"agent_key\":\"missing\",\"message\":\"hello\"}")
                        .build();
        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(call).build())
                        .block(Duration.ofSeconds(5));
        assertEquals(
                "Error: Unknown agent_key: missing", assertText(result, ToolResultState.ERROR));
    }

    @Test
    void remoteSuccessFailureAndCancellationUseTaskState() {
        AgentSpawnTool tool =
                tool(
                        SubagentDeclaration.builder()
                                .name("worker")
                                .description("worker")
                                .url("http://unused.invalid")
                                .build());
        tool.setRemoteTransport(mock(RemoteSubagentTransport.class));
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("remote failed"));
        CompletableFuture<String> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        when(repository.putTask(
                        eq(context),
                        anyString(),
                        eq("worker"),
                        eq("parent"),
                        any(TaskRunSpec.class)))
                .thenReturn(
                        new BackgroundTask(
                                "ok",
                                "worker",
                                CompletableFuture.completedFuture("[ERROR] just data")))
                .thenReturn(new BackgroundTask("failed", "worker", failed))
                .thenReturn(new BackgroundTask("cancelled", "worker", cancelled));
        assertTrue(
                assertText(
                                tool.agentSpawn(context, null, "worker", "go", null, 1, null)
                                        .block(),
                                ToolResultState.SUCCESS)
                        .endsWith("[ERROR] just data"));
        assertTrue(
                assertText(
                                tool.agentSpawn(context, null, "worker", "go", null, 1, null)
                                        .block(),
                                ToolResultState.ERROR)
                        .contains("remote failed"));
        assertText(
                tool.agentSpawn(context, null, "worker", "go", null, 1, null).block(),
                ToolResultState.INTERRUPTED);
    }
}
