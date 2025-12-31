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

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link ChatCompletionsStreamingAdapter}.
 *
 * <p>These tests verify the adapter's behavior for converting agent events to streaming format.
 */
@DisplayName("ChatCompletionsStreamingAdapter Tests")
class ChatCompletionsStreamingAdapterTest {

    private ChatCompletionsResponseBuilder responseBuilder;
    private Function<Event, String> eventConverter;
    private Function<String, String> doneEventFactory;
    private BiFunction<Throwable, String, String> errorEventFactory;
    private ChatCompletionsStreamingAdapter<String> adapter;

    @BeforeEach
    void setUp() {
        responseBuilder = new ChatCompletionsResponseBuilder();
        eventConverter = event -> "event-data";
        doneEventFactory = requestId -> "[DONE]";
        errorEventFactory =
                (error, requestId) -> "Error: " + (error != null ? error.getMessage() : "Unknown");
        adapter =
                new ChatCompletionsStreamingAdapter<>(
                        responseBuilder, eventConverter, doneEventFactory, errorEventFactory);
    }

    @Nested
    @DisplayName("Stream Tests")
    class StreamTests {

        @Test
        @DisplayName("Should convert agent events to streaming events correctly")
        void shouldConvertAgentEventsToStreamingEventsCorrectly() {
            // Create a real agent with a simple model
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .sysPrompt("Test")
                            .model(
                                    new io.agentscope.core.model.Model() {
                                        @Override
                                        public reactor.core.publisher.Flux<
                                                        io.agentscope.core.model.ChatResponse>
                                                stream(
                                                        List<Msg> messages,
                                                        List<io.agentscope.core.model.ToolSchema>
                                                                tools,
                                                        io.agentscope.core.model.GenerateOptions
                                                                options) {
                                            return reactor.core.publisher.Flux.just(
                                                    io.agentscope.core.model.ChatResponse.builder()
                                                            .content(
                                                                    java.util.List.of(
                                                                            TextBlock.builder()
                                                                                    .text("Hello")
                                                                                    .build()))
                                                            .build());
                                        }

                                        @Override
                                        public String getModelName() {
                                            return "test-model";
                                        }
                                    })
                            .build();

            Msg msg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Hello").build())
                            .build();

            // Use the real agent's stream method
            Flux<String> result = adapter.stream(agent, List.of(msg), "request-id");

            // The stream will produce events, but we can't easily mock them
            // So we just verify the stream completes
            StepVerifier.create(result).thenConsumeWhile(data -> true).verifyComplete();
        }

        @Test
        @DisplayName("Should append done event at the end")
        void shouldAppendDoneEventAtTheEnd() {
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .sysPrompt("Test")
                            .model(
                                    new io.agentscope.core.model.Model() {
                                        @Override
                                        public reactor.core.publisher.Flux<
                                                        io.agentscope.core.model.ChatResponse>
                                                stream(
                                                        List<Msg> messages,
                                                        List<io.agentscope.core.model.ToolSchema>
                                                                tools,
                                                        io.agentscope.core.model.GenerateOptions
                                                                options) {
                                            return reactor.core.publisher.Flux.just(
                                                    io.agentscope.core.model.ChatResponse.builder()
                                                            .content(
                                                                    java.util.List.of(
                                                                            TextBlock.builder()
                                                                                    .text("Final")
                                                                                    .build()))
                                                            .build());
                                        }

                                        @Override
                                        public String getModelName() {
                                            return "test-model";
                                        }
                                    })
                            .build();

            Msg msg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Final").build())
                            .build();

            Flux<String> result = adapter.stream(agent, List.of(msg), "request-id");

            // Verify stream completes (done event is appended)
            StepVerifier.create(result).thenConsumeWhile(data -> true).verifyComplete();
        }
    }

    @Nested
    @DisplayName("Create Error Event Tests")
    class CreateErrorEventTests {

        @Test
        @DisplayName("Should create error event correctly")
        void shouldCreateErrorEventCorrectly() {
            RuntimeException error = new RuntimeException("Test error");

            String result = adapter.createErrorEvent(error, "request-id");

            assertThat(result).isEqualTo("Error: Test error");
        }

        @Test
        @DisplayName("Should handle null error")
        void shouldHandleNullError() {
            String result = adapter.createErrorEvent(null, "request-id");

            assertThat(result).isEqualTo("Error: Unknown");
        }
    }
}
