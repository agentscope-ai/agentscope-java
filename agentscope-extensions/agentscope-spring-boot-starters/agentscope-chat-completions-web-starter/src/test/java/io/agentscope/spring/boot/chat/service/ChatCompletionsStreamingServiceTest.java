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
package io.agentscope.spring.boot.chat.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.streaming.ChatCompletionsStreamingAdapter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link ChatCompletionsStreamingService}.
 *
 * <p>These tests verify the Spring-specific streaming service's behavior for converting chunks to
 * SSE.
 */
@DisplayName("ChatCompletionsStreamingService Tests")
class ChatCompletionsStreamingServiceTest {

    private ChatCompletionsStreamingService service;
    private ChatCompletionsStreamingAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ChatCompletionsStreamingAdapter();
        service = new ChatCompletionsStreamingService(adapter);
    }

    /** Create a mock model that returns the specified text. */
    private Model createMockModel(String responseText) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text(responseText).build()))
                                .build());
            }

            @Override
            public String getModelName() {
                return "test-model";
            }
        };
    }

    @Nested
    @DisplayName("Stream As SSE Tests")
    class StreamAsSseTests {

        @Test
        @DisplayName("Should convert agent events to OpenAI-compatible SSE chunks")
        void shouldConvertAgentEventsToOpenAiChunks() {
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .sysPrompt("Test")
                            .model(createMockModel("Hello"))
                            .build();

            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Hi").build())
                            .build();

            Flux<ServerSentEvent<String>> result =
                    service.streamAsSse(agent, List.of(msg), "request-id", "qwen-plus");

            // Verify stream produces events and ends with DONE
            StepVerifier.create(result)
                    .thenConsumeWhile(
                            sse -> {
                                if ("[DONE]".equals(sse.data())) {
                                    return true;
                                }
                                assertTrue(sse.data().contains("chat.completion.chunk"));
                                return true;
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should append DONE event at the end")
        void shouldAppendDoneEventAtTheEnd() {
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .sysPrompt("Test")
                            .model(createMockModel("Hello"))
                            .build();

            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Hi").build())
                            .build();

            Flux<ServerSentEvent<String>> result =
                    service.streamAsSse(agent, List.of(msg), "request-id", "qwen-plus");

            // Collect all events and verify last one is DONE
            StepVerifier.create(result.collectList())
                    .assertNext(
                            events -> {
                                assertTrue(!events.isEmpty());
                                ServerSentEvent<String> lastEvent = events.get(events.size() - 1);
                                assertTrue("[DONE]".equals(lastEvent.data()));
                            })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Create Error SSE Event Tests")
    class CreateErrorSseEventTests {

        @Test
        @DisplayName("Should create error SSE event correctly")
        void shouldCreateErrorSseEventCorrectly() {
            RuntimeException error = new RuntimeException("Test error");

            ServerSentEvent<String> result =
                    service.createErrorSseEvent(error, "request-id", "qwen-plus");

            assertNotNull(result);
            assertTrue(result.data().contains("Test error"));
        }

        @Test
        @DisplayName("Should handle null error")
        void shouldHandleNullError() {
            ServerSentEvent<String> result =
                    service.createErrorSseEvent(null, "request-id", "qwen-plus");

            assertNotNull(result);
            assertTrue(result.data().contains("Unknown error occurred"));
        }
    }

    @Nested
    @DisplayName("SSE Serialization Tests")
    class SseSerializationTests {

        @Test
        @DisplayName("Should serialize chunks to JSON in SSE data")
        void shouldSerializeChunksToJsonInSseData() {
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .sysPrompt("Test")
                            .model(createMockModel("World"))
                            .build();

            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Hello").build())
                            .build();

            Flux<ServerSentEvent<String>> result =
                    service.streamAsSse(agent, List.of(msg), "request-id", "qwen-plus");

            // Verify JSON format in SSE data
            StepVerifier.create(result)
                    .thenConsumeWhile(
                            sse -> {
                                if ("[DONE]".equals(sse.data())) {
                                    return true;
                                }
                                // Should be valid JSON
                                assertTrue(
                                        sse.data().startsWith("{"),
                                        "SSE data should be JSON object");
                                return true;
                            })
                    .verifyComplete();
        }
    }
}
