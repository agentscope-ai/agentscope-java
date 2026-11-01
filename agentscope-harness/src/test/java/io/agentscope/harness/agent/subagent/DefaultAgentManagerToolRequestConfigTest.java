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
package io.agentscope.harness.agent.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.ToolMergeMode;
import io.agentscope.core.tool.ToolRequestConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

class DefaultAgentManagerToolRequestConfigTest {

    @TempDir Path workspace;

    static Stream<Arguments> invocationModes() {
        return Stream.of(ToolMergeMode.MERGE_EXTERNAL_PRIORITY, ToolMergeMode.EXTERNAL_ONLY)
                .flatMap(
                        mode ->
                                Stream.of(false, true)
                                        .flatMap(
                                                streaming ->
                                                        Stream.of(false, true)
                                                                .map(
                                                                        harness ->
                                                                                Arguments.of(
                                                                                        mode,
                                                                                        streaming,
                                                                                        harness))));
    }

    @ParameterizedTest
    @MethodSource("invocationModes")
    void childUsesItsOwnTools(ToolMergeMode mode, boolean streaming, boolean harness) {
        Map<String, Object> parameters = Map.of("type", "object", "properties", Map.of());
        ToolRequestConfig config =
                new ToolRequestConfig(
                        Map.of(
                                "choose_x",
                                new SchemaOnlyTool("choose_x", "Client choice", parameters),
                                "server_tool",
                                new SchemaOnlyTool("server_tool", "Client override", parameters)),
                        mode);
        RuntimeContext parent =
                RuntimeContext.builder()
                        .sessionId("parent")
                        .userId("user")
                        .runId("parent-run")
                        .toolRequestConfig(config)
                        .build();
        Toolkit toolkit = new Toolkit();
        AgentTool serverTool = mock(AgentTool.class);
        when(serverTool.getName()).thenReturn("server_tool");
        when(serverTool.getDescription()).thenReturn("Server tool");
        when(serverTool.getParameters()).thenReturn(parameters);
        when(serverTool.callAsync(any()))
                .thenReturn(
                        Mono.just(
                                ToolResultBlock.of(
                                        TextBlock.builder().text("server result").build())));
        toolkit.registerTool(serverTool);
        AtomicInteger turns = new AtomicInteger();
        MockModel model =
                new MockModel(
                        messages -> {
                            int turn = turns.getAndIncrement();
                            if (turn < 2) {
                                String name = turn == 0 ? "server_tool" : "choose_x";
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id(name)
                                                                        .name(name)
                                                                        .input(Map.of())
                                                                        .content("{}")
                                                                        .build()))
                                                .build());
                            }
                            return List.of(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("child done")
                                                                    .build()))
                                            .build());
                        });
        AtomicReference<RuntimeContext> observed = new AtomicReference<>();
        MiddlewareBase recorder =
                new MiddlewareBase() {
                    @Override
                    public Mono<String> onSystemPrompt(
                            Agent agent, RuntimeContext ctx, String prompt) {
                        observed.set(ctx);
                        return Mono.just(prompt);
                    }
                };
        Agent child =
                harness
                        ? HarnessAgent.builder()
                                .name("child")
                                .model(model)
                                .toolkit(toolkit)
                                .workspace(workspace)
                                .stateStore(new InMemoryAgentStateStore())
                                .memory(
                                        MemoryConfig.builder()
                                                .flushTrigger(MemoryConfig.FlushTrigger.never())
                                                .build())
                                .middlewares(List.of(recorder))
                                .build()
                        : ReActAgent.builder()
                                .name("child")
                                .model(model)
                                .toolkit(toolkit)
                                .middlewares(List.of(recorder))
                                .build();
        DefaultAgentManager manager = new DefaultAgentManager(List.of(), null);
        try {
            Msg reply =
                    streaming
                            ? manager.invokeAgentStream(
                                            child,
                                            "child-session",
                                            "user",
                                            "go",
                                            null,
                                            StreamOptions.defaults(),
                                            parent)
                                    .filter(
                                            event ->
                                                    event.isLast()
                                                            && event.getType()
                                                                    == EventType.AGENT_RESULT)
                                    .map(event -> event.getMessage())
                                    .last()
                                    .block(Duration.ofSeconds(10))
                            : manager.invokeAgent(child, "child-session", "user", "go", parent)
                                    .block(Duration.ofSeconds(10));
            assertNotNull(reply);
            assertEquals("child done", reply.getTextContent());
            assertEquals(3, model.getCallCount());
            List<String> names = model.getLastTools().stream().map(ToolSchema::getName).toList();
            assertTrue(names.contains("server_tool"));
            assertFalse(names.contains("choose_x"));
            verify(serverTool).callAsync(any());
            assertTrue(
                    model.getLastMessages().stream()
                            .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                            .anyMatch(
                                    result ->
                                            "choose_x".equals(result.getId())
                                                    && result.getState() == ToolResultState.ERROR));
            assertNotNull(observed.get());
            assertNull(observed.get().getToolRequestConfig());
            assertEquals("child-session", observed.get().getSessionId());
            assertEquals("user", observed.get().getUserId());
            assertEquals("parent-run", observed.get().getRunId());
            assertSame(config, parent.getToolRequestConfig());
            assertEquals("parent", parent.getSessionId());
        } finally {
            if (child instanceof HarnessAgent harnessAgent) {
                harnessAgent.close();
            }
        }
    }
}
