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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

/**
 * Regression tests: a tool call whose streamed arguments never formed a valid JSON object
 * (typically truncated by the output token limit) must not be executed with empty arguments.
 * It gets an ERROR result carrying the original call ID, so the tool_use ↔ tool_result pairing
 * stays complete and the next model request can reissue the call.
 */
class TruncatedToolCallIntegrityTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "{\"file_path\": \"src/Main.ja",
                "{\"file_path\":\"prefix\"}{\"file_path\":"
            })
    void truncatedArgumentsAreNotExecutedAndProduceErrorResultWithOriginalId(String rawArguments) {
        AtomicInteger executions = new AtomicInteger();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(
                tool(
                        "write_text_file",
                        p -> {
                            executions.incrementAndGet();
                            return Mono.just(ToolResultBlock.text("written"));
                        }));

        List<String> modelInputs = new CopyOnWriteArrayList<>();
        AtomicInteger round = new AtomicInteger();
        MockModel model =
                new MockModel(
                        messages -> {
                            modelInputs.add(
                                    messages.stream()
                                            .map(
                                                    m -> {
                                                        StringBuilder sb =
                                                                new StringBuilder(
                                                                        m.getTextContent());
                                                        for (ToolResultBlock b :
                                                                m.getContentBlocks(
                                                                        ToolResultBlock.class)) {
                                                            sb.append(resultText(b));
                                                        }
                                                        return sb.toString();
                                                    })
                                            .collect(Collectors.joining("\n")));
                            if (round.getAndIncrement() == 0) {
                                // No parameters yet, an unclosed object, or an invalid suffix.
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("trunc-1")
                                                                        .name("write_text_file")
                                                                        .content(rawArguments)
                                                                        .build()))
                                                .finishReason("length")
                                                .build());
                            }
                            return List.of(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("recovered")
                                                                    .build()))
                                            .build());
                        });

        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (ReActAgent agent =
                ReActAgent.builder().name("test").model(model).toolkit(toolkit).build()) {
            agent.call(List.of())
                    .contextWrite(
                            ctx ->
                                    ctx.put(
                                            AgentEventEmitter.FORWARDING_CONTEXT_KEY,
                                            (AgentEventEmitter) events::add))
                    .block(Duration.ofSeconds(5));

            assertEquals(0, executions.get(), "truncated tool call must not reach the handler");

            ToolResultBlock result = findToolResult(agent.getAgentState().getContext(), "trunc-1");
            assertNotNull(result, "an ERROR tool_result must pair the truncated tool_use");
            assertEquals(ToolResultState.ERROR, result.getState());
            String resultMessage = resultText(result);
            assertTrue(resultMessage.contains("output token limit"));
            assertTrue(resultMessage.contains("Do not repeat the same large arguments"));
            assertTrue(resultMessage.contains("Split the work into smaller tool calls"));

            // The loop continued to a second model request (no auto-retry, but also no stall),
            // and that request carried the error result so the model can reissue the call.
            assertEquals(2, model.getCallCount());
            assertTrue(
                    modelInputs.get(1).contains("not executed"),
                    "second model request must see the error result");

            ToolResultEndEvent end =
                    events.stream()
                            .filter(ToolResultEndEvent.class::isInstance)
                            .map(ToolResultEndEvent.class::cast)
                            .filter(e -> "trunc-1".equals(e.getToolCallId()))
                            .findFirst()
                            .orElse(null);
            assertNotNull(end, "lifecycle events must be published for the truncated call");
            assertEquals(ToolResultState.ERROR, end.getState());
        }
    }

    @Test
    void lengthStopDoesNotRejectCompletedSiblings() {
        AtomicInteger executions = new AtomicInteger();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(
                tool(
                        "record",
                        p -> {
                            executions.incrementAndGet();
                            return Mono.just(ToolResultBlock.text("recorded"));
                        }));
        AtomicInteger round = new AtomicInteger();
        MockModel model =
                new MockModel(
                        messages -> {
                            if (round.getAndIncrement() == 0) {
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("incomplete")
                                                                        .name("record")
                                                                        .content("")
                                                                        .build(),
                                                                ToolUseBlock.builder()
                                                                        .id("complete")
                                                                        .name("record")
                                                                        .content("{}")
                                                                        .build(),
                                                                ToolUseBlock.builder()
                                                                        .id("input-only")
                                                                        .name("record")
                                                                        .input(
                                                                                Map.of(
                                                                                        "message",
                                                                                        "ready"))
                                                                        .build()))
                                                .build(),
                                        ChatResponse.builder()
                                                .content(List.of())
                                                .finishReason("length")
                                                .build());
                            }
                            return List.of(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("done")
                                                                    .build()))
                                            .build());
                        });
        try (ReActAgent agent =
                ReActAgent.builder().name("test").model(model).toolkit(toolkit).build()) {
            agent.call(List.of()).block(Duration.ofSeconds(5));
            List<Msg> history = agent.getAgentState().getContext();
            assertEquals(2, executions.get());
            assertEquals(2, model.getCallCount());
            assertEquals(ToolResultState.ERROR, findToolResult(history, "incomplete").getState());
            assertEquals(ToolResultState.SUCCESS, findToolResult(history, "complete").getState());
            assertEquals(ToolResultState.SUCCESS, findToolResult(history, "input-only").getState());
            assertEquals(
                    Boolean.TRUE,
                    history.get(0).getMetadata().get(MessageMetadataKeys.OUTPUT_LIMIT_REACHED));
        }
    }

    @Test
    void malformedArgumentsWithoutOutputLimitRequestJsonCorrection() {
        AtomicInteger executions = new AtomicInteger();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(
                tool(
                        "write_text_file",
                        p -> {
                            executions.incrementAndGet();
                            return Mono.just(ToolResultBlock.text("written"));
                        }));

        AtomicInteger round = new AtomicInteger();
        MockModel model =
                new MockModel(
                        messages -> {
                            if (round.getAndIncrement() == 0) {
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("invalid-1")
                                                                        .name("write_text_file")
                                                                        .content(
                                                                                "{\"file_path\":"
                                                                                    + "\"prefix\"}{\"file_path\":")
                                                                        .build()))
                                                .build());
                            }
                            return List.of(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("recovered")
                                                                    .build()))
                                            .build());
                        });

        try (ReActAgent agent =
                ReActAgent.builder().name("test").model(model).toolkit(toolkit).build()) {
            agent.call(List.of()).block(Duration.ofSeconds(5));

            assertEquals(0, executions.get(), "malformed tool call must not reach the handler");
            ToolResultBlock result =
                    findToolResult(agent.getAgentState().getContext(), "invalid-1");
            assertNotNull(result);
            assertEquals(ToolResultState.ERROR, result.getState());
            String resultMessage = resultText(result);
            assertTrue(resultMessage.contains("not a valid JSON object"));
            assertFalse(resultMessage.contains("output token limit"));
            assertFalse(
                    agent.getAgentState()
                            .getContext()
                            .get(0)
                            .getMetadata()
                            .containsKey(MessageMetadataKeys.OUTPUT_LIMIT_REACHED));
        }
    }

    @Test
    void completeArgumentsStillExecute() {
        AtomicInteger executions = new AtomicInteger();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(
                tool(
                        "write_text_file",
                        p -> {
                            executions.incrementAndGet();
                            return Mono.just(ToolResultBlock.text("written"));
                        }));

        AtomicInteger round = new AtomicInteger();
        MockModel model =
                new MockModel(
                        messages -> {
                            if (round.getAndIncrement() == 0) {
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("ok-1")
                                                                        .name("write_text_file")
                                                                        .content(
                                                                                "{\"file_path\":"
                                                                                    + " \"a.md\"}")
                                                                        .build()))
                                                .build());
                            }
                            return List.of(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("done")
                                                                    .build()))
                                            .build());
                        });

        try (ReActAgent agent =
                ReActAgent.builder().name("test").model(model).toolkit(toolkit).build()) {
            agent.call(List.of()).block(Duration.ofSeconds(5));

            assertEquals(1, executions.get(), "complete tool call must execute exactly once");
            ToolResultBlock result = findToolResult(agent.getAgentState().getContext(), "ok-1");
            assertNotNull(result);
            assertEquals("written", resultText(result));
        }
    }

    private static ToolResultBlock findToolResult(List<Msg> context, String toolCallId) {
        for (Msg msg : context) {
            for (ToolResultBlock block : msg.getContentBlocks(ToolResultBlock.class)) {
                if (toolCallId.equals(block.getId())) {
                    return block;
                }
            }
        }
        return null;
    }

    private static String resultText(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(b -> ((TextBlock) b).getText())
                .collect(Collectors.joining());
    }

    static AgentTool tool(String name, Function<ToolCallParam, Mono<ToolResultBlock>> body) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name;
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam p) {
                return body.apply(p);
            }
        };
    }
}
