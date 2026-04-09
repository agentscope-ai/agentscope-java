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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.MockToolkit;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.hook.SummaryChunkEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** Regression tests for summary-stream thinking hook events. */
@DisplayName("ReActAgent Summary Thinking Hook Tests")
class ReActAgentSummaryThinkingHookTest {

    @Test
    @DisplayName("Should emit ReasoningChunkEvent for summary thinking chunks")
    void testSummaryThinkingChunksAlsoEmitReasoningEvents() {
        CopyOnWriteArrayList<String> reasoningChunks = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> summaryThinkingChunks = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> summaryTextChunks = new CopyOnWriteArrayList<>();

        Hook captureHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof ReasoningChunkEvent reasoningEvent) {
                            ThinkingBlock block =
                                    reasoningEvent
                                            .getIncrementalChunk()
                                            .getFirstContentBlock(ThinkingBlock.class);
                            if (block != null) {
                                reasoningChunks.add(block.getThinking());
                            }
                        }
                        if (event instanceof SummaryChunkEvent summaryEvent) {
                            ThinkingBlock thinkingBlock =
                                    summaryEvent
                                            .getIncrementalChunk()
                                            .getFirstContentBlock(ThinkingBlock.class);
                            if (thinkingBlock != null) {
                                summaryThinkingChunks.add(thinkingBlock.getThinking());
                            }
                            TextBlock textBlock =
                                    summaryEvent
                                            .getIncrementalChunk()
                                            .getFirstContentBlock(TextBlock.class);
                            if (textBlock != null && !textBlock.getText().isEmpty()) {
                                summaryTextChunks.add(textBlock.getText());
                            }
                        }
                        return Mono.just(event);
                    }
                };

        ReActAgent agent = createAgentForSummaryThinkingHooks(captureHook);
        Msg response =
                agent.call(TestUtils.createUserMessage("User", "Need a concise answer"))
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertEquals(GenerateReason.MAX_ITERATIONS, response.getGenerateReason());
        assertEquals(List.of("I should ", "summarize carefully."), reasoningChunks);
        assertEquals(List.of("I should ", "summarize carefully."), summaryThinkingChunks);
        assertEquals(List.of("Final concise answer."), summaryTextChunks);
    }

    @Test
    @DisplayName("Should accumulate summary thinking content in mirrored reasoning events")
    void testSummaryThinkingReasoningEventsUseAccumulatedThinking() {
        CopyOnWriteArrayList<String> accumulatedThinking = new CopyOnWriteArrayList<>();

        Hook captureHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof ReasoningChunkEvent reasoningEvent) {
                            ThinkingBlock block =
                                    reasoningEvent
                                            .getAccumulated()
                                            .getFirstContentBlock(ThinkingBlock.class);
                            if (block != null) {
                                accumulatedThinking.add(block.getThinking());
                            }
                        }
                        return Mono.just(event);
                    }
                };

        ReActAgent agent = createAgentForSummaryThinkingHooks(captureHook);
        agent.call(TestUtils.createUserMessage("User", "Need a concise answer"))
                .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertEquals(List.of("I should ", "I should summarize carefully."), accumulatedThinking);
    }

    @Test
    @DisplayName("Should emit mirrored reasoning event before summary event for summary thinking")
    void testSummaryThinkingReasoningEventOrder() {
        CopyOnWriteArrayList<String> eventOrder = new CopyOnWriteArrayList<>();

        Hook captureHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof ReasoningChunkEvent reasoningEvent) {
                            ThinkingBlock block =
                                    reasoningEvent
                                            .getIncrementalChunk()
                                            .getFirstContentBlock(ThinkingBlock.class);
                            if (block != null) {
                                eventOrder.add("reasoning:" + block.getThinking());
                            }
                        }
                        if (event instanceof SummaryChunkEvent summaryEvent) {
                            ThinkingBlock thinkingBlock =
                                    summaryEvent
                                            .getIncrementalChunk()
                                            .getFirstContentBlock(ThinkingBlock.class);
                            if (thinkingBlock != null) {
                                eventOrder.add("summary-thinking:" + thinkingBlock.getThinking());
                            }
                            TextBlock textBlock =
                                    summaryEvent
                                            .getIncrementalChunk()
                                            .getFirstContentBlock(TextBlock.class);
                            if (textBlock != null && !textBlock.getText().isEmpty()) {
                                eventOrder.add("summary-text:" + textBlock.getText());
                            }
                        }
                        return Mono.just(event);
                    }
                };

        ReActAgent agent = createAgentForSummaryThinkingHooks(captureHook);
        agent.call(TestUtils.createUserMessage("User", "Need a concise answer"))
                .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertFalse(eventOrder.isEmpty());
        assertEquals(
                List.of(
                        "reasoning:I should ",
                        "summary-thinking:I should ",
                        "reasoning:summarize carefully.",
                        "summary-thinking:summarize carefully.",
                        "summary-text:Final concise answer."),
                eventOrder);
        assertTrue(
                eventOrder.stream()
                        .noneMatch(item -> item.startsWith("reasoning:Final concise answer.")));
    }

    private ReActAgent createAgentForSummaryThinkingHooks(Hook captureHook) {
        MockModel model =
                new MockModel(
                        messages -> {
                            if (messages.stream()
                                    .anyMatch(
                                            msg ->
                                                    msg.getTextContent()
                                                            .contains("maximum iterations"))) {
                                return List.of(
                                        createThinkingResponse("I should "),
                                        createThinkingResponse("summarize carefully."),
                                        createTextResponse("Final concise answer."));
                            }
                            return List.of(
                                    createToolCallResponse(
                                            TestConstants.TEST_TOOL_NAME,
                                            "summary_tool_call",
                                            TestUtils.createToolArguments()));
                        });

        return ReActAgent.builder()
                .name(TestConstants.TEST_REACT_AGENT_NAME)
                .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                .model(model)
                .toolkit(new MockToolkit())
                .memory(new InMemoryMemory())
                .hook(captureHook)
                .maxIters(1)
                .build();
    }

    private ChatResponse createThinkingResponse(String thinking) {
        return ChatResponse.builder()
                .id("msg_" + UUID.randomUUID())
                .content(List.of(ThinkingBlock.builder().thinking(thinking).build()))
                .build();
    }

    private ChatResponse createTextResponse(String text) {
        return ChatResponse.builder()
                .id("msg_" + UUID.randomUUID())
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    private ChatResponse createToolCallResponse(
            String toolName, String toolCallId, Map<String, Object> arguments) {
        return ChatResponse.builder()
                .id("msg_" + UUID.randomUUID())
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .name(toolName)
                                        .id(toolCallId)
                                        .input(arguments)
                                        .content(JsonUtils.getJsonCodec().toJson(arguments))
                                        .build()))
                .build();
    }
}
