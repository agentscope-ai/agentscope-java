/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.chat.completions.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.chat.completions.model.ChatCompletionsChunk;
import io.agentscope.core.chat.completions.model.ToolCall;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link ChatCompletionsStreamingAdapter}.
 *
 * <p>These tests verify the adapter's behavior for converting agent events to streaming chunks.
 */
@DisplayName("ChatCompletionsStreamingAdapter Tests")
class ChatCompletionsStreamingAdapterTest {

    private ChatCompletionsStreamingAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ChatCompletionsStreamingAdapter();
    }

    @Nested
    @DisplayName("Convert Event To Chunks Tests")
    class ConvertEventToChunksTests {

        @Test
        @DisplayName("Should convert text event to text chunk")
        void shouldConvertTextEventToTextChunk() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Hello world").build())
                            .build();

            Event event = new Event(EventType.REASONING, msg, false);

            Flux<ChatCompletionsChunk> result =
                    adapter.convertEventToChunks(event, "req-123", "gpt-4");

            StepVerifier.create(result)
                    .assertNext(
                            chunk -> {
                                assertEquals("req-123", chunk.getId());
                                assertEquals("gpt-4", chunk.getModel());
                                assertEquals("chat.completion.chunk", chunk.getObject());
                                assertNotNull(chunk.getChoices());
                                assertEquals(1, chunk.getChoices().size());
                                assertEquals(
                                        "Hello world",
                                        chunk.getChoices().get(0).getDelta().getContent());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should convert tool call event to tool call chunk")
        void shouldConvertToolCallEventToToolCallChunk() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder()
                            .id("call-123")
                            .name("get_weather")
                            .input(Map.of("city", "Beijing"))
                            .build();

            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(toolUseBlock).build();

            Event event = new Event(EventType.REASONING, msg, false);

            Flux<ChatCompletionsChunk> result =
                    adapter.convertEventToChunks(event, "req-123", "gpt-4");

            StepVerifier.create(result)
                    .assertNext(
                            chunk -> {
                                assertNotNull(chunk.getChoices());
                                assertNotNull(chunk.getChoices().get(0).getDelta().getToolCalls());
                                assertEquals(
                                        1,
                                        chunk.getChoices().get(0).getDelta().getToolCalls().size());
                                assertEquals(
                                        "call-123",
                                        chunk.getChoices()
                                                .get(0)
                                                .getDelta()
                                                .getToolCalls()
                                                .get(0)
                                                .getId());
                                assertEquals(
                                        "get_weather",
                                        chunk.getChoices()
                                                .get(0)
                                                .getDelta()
                                                .getToolCalls()
                                                .get(0)
                                                .getFunction()
                                                .getName());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should convert event with text and tool calls to multiple chunks")
        void shouldConvertEventWithTextAndToolCallsToMultipleChunks() {
            TextBlock textBlock = TextBlock.builder().text("Let me check").build();
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder()
                            .id("call-456")
                            .name("calculate")
                            .input(Map.of("a", 1, "b", 2))
                            .build();

            Msg msg =
                    Msg.builder().role(MsgRole.ASSISTANT).content(textBlock, toolUseBlock).build();

            Event event = new Event(EventType.REASONING, msg, false);

            Flux<ChatCompletionsChunk> result =
                    adapter.convertEventToChunks(event, "req-123", "gpt-4");

            StepVerifier.create(result)
                    .assertNext(
                            chunk -> {
                                // First chunk is text
                                assertEquals(
                                        "Let me check",
                                        chunk.getChoices().get(0).getDelta().getContent());
                            })
                    .assertNext(
                            chunk -> {
                                // Second chunk is tool call
                                assertNotNull(chunk.getChoices().get(0).getDelta().getToolCalls());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should add finish chunk for last event with stop reason")
        void shouldAddFinishChunkForLastEventWithStopReason() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Done").build())
                            .build();

            Event event = new Event(EventType.REASONING, msg, true); // isLast = true

            Flux<ChatCompletionsChunk> result =
                    adapter.convertEventToChunks(event, "req-123", "gpt-4");

            StepVerifier.create(result)
                    .assertNext(
                            chunk -> {
                                // Text chunk
                                assertEquals(
                                        "Done", chunk.getChoices().get(0).getDelta().getContent());
                            })
                    .assertNext(
                            chunk -> {
                                // Finish chunk
                                assertEquals("stop", chunk.getChoices().get(0).getFinishReason());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should add finish chunk with tool_calls reason when has tool calls")
        void shouldAddFinishChunkWithToolCallsReasonWhenHasToolCalls() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder()
                            .id("call-789")
                            .name("search")
                            .input(Map.of("query", "test"))
                            .build();

            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(toolUseBlock).build();

            Event event = new Event(EventType.REASONING, msg, true); // isLast = true

            Flux<ChatCompletionsChunk> result =
                    adapter.convertEventToChunks(event, "req-123", "gpt-4");

            StepVerifier.create(result)
                    .assertNext(
                            chunk -> {
                                // Tool call chunk
                                assertNotNull(chunk.getChoices().get(0).getDelta().getToolCalls());
                            })
                    .assertNext(
                            chunk -> {
                                // Finish chunk with tool_calls reason
                                assertEquals(
                                        "tool_calls", chunk.getChoices().get(0).getFinishReason());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty flux for null message")
        void shouldReturnEmptyFluxForNullMessage() {
            Event event = new Event(EventType.REASONING, null, false);

            Flux<ChatCompletionsChunk> result =
                    adapter.convertEventToChunks(event, "req-123", "gpt-4");

            StepVerifier.create(result).verifyComplete();
        }

        @Test
        @DisplayName("Should return empty flux for empty content")
        void shouldReturnEmptyFluxForEmptyContent() {
            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).build();

            Event event = new Event(EventType.REASONING, msg, false);

            Flux<ChatCompletionsChunk> result =
                    adapter.convertEventToChunks(event, "req-123", "gpt-4");

            StepVerifier.create(result).verifyComplete();
        }

        @Test
        @DisplayName("Should not extract tool calls from TOOL_RESULT events")
        void shouldNotExtractToolCallsFromToolResultEvents() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder().id("call-123").name("test").input(Map.of()).build();

            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(toolUseBlock).build();

            // TOOL_RESULT event type - should not extract tool calls (ToolUseBlock)
            Event event = new Event(EventType.TOOL_RESULT, msg, false);

            Flux<ChatCompletionsChunk> result =
                    adapter.convertEventToChunks(event, "req-123", "gpt-4");

            // Should return empty because no text content and tool calls are not extracted for
            // TOOL_RESULT (only ToolResultBlock is processed for TOOL_RESULT events)
            StepVerifier.create(result).verifyComplete();
        }

        @Test
        @DisplayName("Should extract tool result from TOOL_RESULT events")
        void shouldExtractToolResultFromToolResultEvents() {
            ToolResultBlock toolResultBlock =
                    ToolResultBlock.of(
                            "call-123",
                            "get_weather",
                            TextBlock.builder().text("Sunny, 25°C").build());

            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(toolResultBlock).build();

            Event event = new Event(EventType.TOOL_RESULT, msg, false);

            Flux<ChatCompletionsChunk> result =
                    adapter.convertEventToChunks(event, "req-123", "gpt-4");

            StepVerifier.create(result)
                    .assertNext(
                            chunk -> {
                                assertNotNull(chunk.getChoices());
                                assertEquals(1, chunk.getChoices().size());
                                // Tool results are now formatted as assistant content for streaming
                                // compatibility
                                assertEquals(
                                        "assistant",
                                        chunk.getChoices().get(0).getDelta().getRole());
                                assertEquals(
                                        "[Tool: get_weather] Sunny, 25°C",
                                        chunk.getChoices().get(0).getDelta().getContent());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle multiple tool results in single event")
        void shouldHandleMultipleToolResultsInSingleEvent() {
            ToolResultBlock result1 =
                    ToolResultBlock.of(
                            "call-1", "tool_a", TextBlock.builder().text("Result A").build());
            ToolResultBlock result2 =
                    ToolResultBlock.of(
                            "call-2", "tool_b", TextBlock.builder().text("Result B").build());

            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(result1, result2).build();

            Event event = new Event(EventType.TOOL_RESULT, msg, false);

            Flux<ChatCompletionsChunk> result =
                    adapter.convertEventToChunks(event, "req-123", "gpt-4");

            StepVerifier.create(result)
                    .assertNext(
                            chunk -> {
                                // Tool results are now formatted as assistant content with tool
                                // name
                                // prefix
                                assertEquals(
                                        "assistant",
                                        chunk.getChoices().get(0).getDelta().getRole());
                                assertEquals(
                                        "[Tool: tool_a] Result A",
                                        chunk.getChoices().get(0).getDelta().getContent());
                            })
                    .assertNext(
                            chunk -> {
                                assertEquals(
                                        "assistant",
                                        chunk.getChoices().get(0).getDelta().getRole());
                                assertEquals(
                                        "[Tool: tool_b] Result B",
                                        chunk.getChoices().get(0).getDelta().getContent());
                            })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Create Error Chunk Tests")
    class CreateErrorChunkTests {

        @Test
        @DisplayName("Should create error chunk with message")
        void shouldCreateErrorChunkWithMessage() {
            RuntimeException error = new RuntimeException("Test error");

            ChatCompletionsChunk chunk = adapter.createErrorChunk(error, "req-123", "gpt-4");

            assertNotNull(chunk);
            assertEquals("req-123", chunk.getId());
            assertEquals("gpt-4", chunk.getModel());
            assertTrue(chunk.getChoices().get(0).getDelta().getContent().contains("Test error"));
        }

        @Test
        @DisplayName("Should handle null error")
        void shouldHandleNullError() {
            ChatCompletionsChunk chunk = adapter.createErrorChunk(null, "req-123", "gpt-4");

            assertNotNull(chunk);
            assertTrue(
                    chunk.getChoices()
                            .get(0)
                            .getDelta()
                            .getContent()
                            .contains("Unknown error occurred"));
        }
    }

    @Nested
    @DisplayName("Stream Integration Tests")
    class StreamIntegrationTests {

        @Test
        @DisplayName("Should stream complete conversation")
        void shouldStreamCompleteConversation() {
            // Create a mock model that returns a simple response
            Model mockModel =
                    new Model() {
                        @Override
                        public Flux<ChatResponse> stream(
                                List<Msg> messages,
                                List<ToolSchema> tools,
                                GenerateOptions options) {
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("Hello!")
                                                                    .build()))
                                            .build());
                        }

                        @Override
                        public String getModelName() {
                            return "test-model";
                        }
                    };

            ReActAgent agent =
                    ReActAgent.builder().name("test").sysPrompt("Test").model(mockModel).build();

            Msg userMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Hi").build())
                            .build();

            Flux<ChatCompletionsChunk> result =
                    adapter.stream(agent, List.of(userMsg), "req-123", "test-model");

            // Verify stream produces chunks and completes
            StepVerifier.create(result).thenConsumeWhile(chunk -> true).verifyComplete();
        }
    }

    @Nested
    @DisplayName("Tool Call Serialization Tests")
    class ToolCallSerializationTests {

        @Test
        @DisplayName("Should serialize tool call arguments to JSON")
        void shouldSerializeToolCallArgumentsToJson() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder()
                            .id("call-123")
                            .name("get_weather")
                            .input(Map.of("city", "Beijing", "unit", "celsius"))
                            .build();

            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(toolUseBlock).build();

            Event event = new Event(EventType.REASONING, msg, false);

            Flux<ChatCompletionsChunk> result =
                    adapter.convertEventToChunks(event, "req-123", "gpt-4");

            StepVerifier.create(result)
                    .assertNext(
                            chunk -> {
                                String args =
                                        chunk.getChoices()
                                                .get(0)
                                                .getDelta()
                                                .getToolCalls()
                                                .get(0)
                                                .getFunction()
                                                .getArguments();
                                // Should be valid JSON
                                assertTrue(args.contains("city"));
                                assertTrue(args.contains("Beijing"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle empty tool input")
        void shouldHandleEmptyToolInput() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder().id("call-123").name("no_args").input(Map.of()).build();

            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(toolUseBlock).build();

            Event event = new Event(EventType.REASONING, msg, false);

            Flux<ChatCompletionsChunk> result =
                    adapter.convertEventToChunks(event, "req-123", "gpt-4");

            StepVerifier.create(result)
                    .assertNext(
                            chunk -> {
                                String args =
                                        chunk.getChoices()
                                                .get(0)
                                                .getDelta()
                                                .getToolCalls()
                                                .get(0)
                                                .getFunction()
                                                .getArguments();
                                // For streaming, empty arguments should be empty string, not "{}"
                                // This allows clients to accumulate subsequent chunks correctly
                                assertEquals("", args);
                            })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Streaming Tool Call Deduplication Tests")
    class StreamingToolCallDeduplicationTests {

        /**
         * Regression test for: stream=true mode sends partial tool call argument JSON fragments to
         * external clients, causing OpenAI-compatible clients to accumulate invalid JSON (e.g.
         * {@code {"url": "htt} concatenated with {@code {"url":"https://complete"}} = invalid).
         *
         * <p>The fix: when the agent streams LLM output, intermediate REASONING events
         * (isLast=false) must NOT produce tool call chunks. Tool calls are emitted exactly once,
         * with complete arguments, via the final REASONING event (isLast=true, from
         * PostReasoningEvent).
         *
         * <p>We simulate this by giving {@link ChatCompletionsStreamingAdapter#stream} a mock model
         * that:
         *
         * <ul>
         *   <li>On the first call: returns two incremental chunks whose argument strings together
         *       form a valid JSON object.
         *   <li>On the second call: returns a plain text "Done" response so the agent stops
         *       looping.
         * </ul>
         *
         * <p>The streaming output must contain the tool call exactly once (from the first
         * iteration's final REASONING event) with the fully assembled arguments.
         */
        @Test
        @DisplayName(
                "stream() must emit tool call exactly once with complete JSON arguments,"
                        + " not duplicated or fragmented")
        void streamShouldEmitToolCallOnceWithCompleteArguments() {
            io.agentscope.core.model.ChatUsage usage =
                    io.agentscope.core.model.ChatUsage.builder()
                            .inputTokens(5)
                            .outputTokens(10)
                            .build();

            // chunk-1: first LLM streaming delta – partial tool call arguments (not valid JSON
            // alone)
            ChatResponse chunk1 =
                    ChatResponse.builder()
                            .content(
                                    List.of(
                                            ToolUseBlock.builder()
                                                    .id("call-stream-1")
                                                    .name("get_feishu_doc")
                                                    .content("{\"url\": \"https://example.com")
                                                    .input(Map.of())
                                                    .build()))
                            .build();

            // chunk-2: second LLM streaming delta – completes the argument JSON via a fragment
            ChatResponse chunk2 =
                    ChatResponse.builder()
                            .content(
                                    List.of(
                                            ToolUseBlock.builder()
                                                    .id("")
                                                    .name("__fragment__")
                                                    .content("/doc\"}")
                                                    .input(Map.of())
                                                    .build()))
                            .usage(usage)
                            .build();

            // Second LLM call (after tool result): plain text, so agent stops
            ChatResponse textResponse =
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("Done.").build()))
                            .usage(usage)
                            .build();

            // Stateful mock: first invocation returns tool call chunks; subsequent calls return
            // text
            java.util.concurrent.atomic.AtomicInteger callCount =
                    new java.util.concurrent.atomic.AtomicInteger(0);
            Model mockModel =
                    new Model() {
                        @Override
                        public Flux<ChatResponse> stream(
                                List<Msg> messages,
                                List<io.agentscope.core.model.ToolSchema> tools,
                                io.agentscope.core.model.GenerateOptions options) {
                            if (callCount.getAndIncrement() == 0) {
                                return Flux.just(chunk1, chunk2);
                            }
                            return Flux.just(textResponse);
                        }

                        @Override
                        public String getModelName() {
                            return "test-model";
                        }
                    };

            ReActAgent agent =
                    ReActAgent.builder().name("test").sysPrompt("Test").model(mockModel).build();

            Msg userMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("read the doc").build())
                            .build();

            List<ChatCompletionsChunk> allChunks =
                    adapter.stream(agent, List.of(userMsg), "req-dup", "test-model")
                            .collectList()
                            .block(java.time.Duration.ofSeconds(5));

            assertNotNull(allChunks);

            // Collect all chunks that carry tool_calls
            List<ChatCompletionsChunk> toolCallChunks =
                    allChunks.stream()
                            .filter(
                                    c ->
                                            c.getChoices() != null
                                                    && !c.getChoices().isEmpty()
                                                    && c.getChoices().get(0).getDelta() != null
                                                    && c.getChoices()
                                                                    .get(0)
                                                                    .getDelta()
                                                                    .getToolCalls()
                                                            != null
                                                    && !c.getChoices()
                                                            .get(0)
                                                            .getDelta()
                                                            .getToolCalls()
                                                            .isEmpty())
                            .toList();

            // Tool call must appear exactly ONCE (not duplicated from intermediate events)
            assertEquals(
                    1,
                    toolCallChunks.size(),
                    "Tool call should be emitted exactly once, not duplicated from intermediate"
                            + " events. Got "
                            + toolCallChunks.size()
                            + " tool call chunks.");

            // The single tool call chunk must carry complete, valid JSON arguments
            ToolCall tc =
                    toolCallChunks.get(0).getChoices().get(0).getDelta().getToolCalls().get(0);
            assertEquals("call-stream-1", tc.getId());
            assertEquals("get_feishu_doc", tc.getFunction().getName());
            String args = tc.getFunction().getArguments();
            assertNotNull(args);
            // The accumulated JSON must be complete and include the full URL
            assertTrue(
                    args.contains("https://example.com/doc"),
                    "Arguments should contain the complete URL, got: " + args);
        }

        @Test
        @DisplayName("convertEventToChunks() on non-last event still emits tool calls (public API)")
        void convertEventToChunksShouldEmitToolCallsRegardlessOfIsLast() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder()
                            .id("call-abc")
                            .name("search")
                            .content("{\"query\":\"test\"}")
                            .input(Map.of("query", "test"))
                            .build();

            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(toolUseBlock).build();
            // Public convertEventToChunks() called directly (not via stream()) should still emit
            // tool calls even for isLast=false events.
            Event nonLastEvent = new Event(EventType.REASONING, msg, false);

            Flux<ChatCompletionsChunk> result =
                    adapter.convertEventToChunks(nonLastEvent, "req-2", "gpt-4");

            StepVerifier.create(result)
                    .assertNext(
                            chunk -> {
                                assertNotNull(chunk.getChoices().get(0).getDelta().getToolCalls());
                                assertEquals(
                                        "search",
                                        chunk.getChoices()
                                                .get(0)
                                                .getDelta()
                                                .getToolCalls()
                                                .get(0)
                                                .getFunction()
                                                .getName());
                            })
                    .verifyComplete();
        }
    }
}
