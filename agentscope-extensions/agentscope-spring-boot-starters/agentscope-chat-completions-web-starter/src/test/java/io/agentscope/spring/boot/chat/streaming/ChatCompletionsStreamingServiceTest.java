/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.spring.boot.chat.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.spring.boot.chat.builder.ChatCompletionsResponseBuilder;
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
 * <p>These tests verify the streaming service's behavior for converting agent events to SSE.
 */
@DisplayName("ChatCompletionsStreamingService Tests")
class ChatCompletionsStreamingServiceTest {

    private ChatCompletionsStreamingService service;
    private ChatCompletionsResponseBuilder responseBuilder;

    @BeforeEach
    void setUp() {
        responseBuilder = mock(ChatCompletionsResponseBuilder.class);
        service = new ChatCompletionsStreamingService(responseBuilder);
    }

    @Nested
    @DisplayName("Stream As SSE Tests")
    class StreamAsSseTests {

        @Test
        @DisplayName("Should convert agent events to SSE correctly")
        void shouldConvertAgentEventsToSseCorrectly() {
            ReActAgent agent = mock(ReActAgent.class);
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Hello").build())
                            .build();

            Event event = mock(Event.class);
            when(event.getMessage()).thenReturn(msg);
            when(event.getMessageId()).thenReturn("msg-1");
            when(event.isLast()).thenReturn(false);

            when(agent.stream(anyList(), any())).thenReturn(Flux.just(event));
            when(responseBuilder.extractTextContent(msg)).thenReturn("Hello");

            Flux<ServerSentEvent<String>> result =
                    service.streamAsSse(agent, List.of(msg), "request-id");

            StepVerifier.create(result)
                    .assertNext(
                            sse -> {
                                assertThat(sse.data()).isEqualTo("Hello");
                                assertThat(sse.event()).isEqualTo("delta");
                                assertThat(sse.id()).isEqualTo("msg-1");
                            })
                    .thenConsumeWhile(
                            sse -> {
                                // Consume all events except the last [DONE] event
                                return !"[DONE]".equals(sse.data());
                            })
                    .expectNextMatches(sse -> "[DONE]".equals(sse.data()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should filter out null messages")
        void shouldFilterOutNullMessages() {
            ReActAgent agent = mock(ReActAgent.class);
            Event event = mock(Event.class);
            when(event.getMessage()).thenReturn(null);

            when(agent.stream(anyList(), any())).thenReturn(Flux.just(event));

            Flux<ServerSentEvent<String>> result =
                    service.streamAsSse(agent, List.of(mock(Msg.class)), "request-id");

            StepVerifier.create(result)
                    .expectNextMatches(sse -> "[DONE]".equals(sse.data()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should filter out empty text content")
        void shouldFilterOutEmptyTextContent() {
            ReActAgent agent = mock(ReActAgent.class);
            Msg msg = mock(Msg.class);
            Event event = mock(Event.class);
            when(event.getMessage()).thenReturn(msg);
            when(responseBuilder.extractTextContent(msg)).thenReturn("");

            when(agent.stream(anyList(), any())).thenReturn(Flux.just(event));

            Flux<ServerSentEvent<String>> result =
                    service.streamAsSse(agent, List.of(mock(Msg.class)), "request-id");

            StepVerifier.create(result)
                    .expectNextMatches(sse -> "[DONE]".equals(sse.data()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should append done event at the end")
        void shouldAppendDoneEventAtTheEnd() {
            ReActAgent agent = mock(ReActAgent.class);
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Final").build())
                            .build();

            Event event = mock(Event.class);
            when(event.getMessage()).thenReturn(msg);
            when(event.isLast()).thenReturn(true);

            when(agent.stream(anyList(), any())).thenReturn(Flux.just(event));
            when(responseBuilder.extractTextContent(msg)).thenReturn("Final");

            Flux<ServerSentEvent<String>> result =
                    service.streamAsSse(agent, List.of(msg), "request-id");

            StepVerifier.create(result)
                    .expectNextMatches(sse -> "Final".equals(sse.data()))
                    .expectNextMatches(sse -> "[DONE]".equals(sse.data()) && "done".equals(sse.event()))
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

            ServerSentEvent<String> result = service.createErrorSseEvent(error, "request-id");

            assertThat(result).isNotNull();
            assertThat(result.data()).contains("Error:").contains("Test error");
            assertThat(result.event()).isEqualTo("error");
            assertThat(result.id()).isEqualTo("request-id");
        }

        @Test
        @DisplayName("Should handle null error")
        void shouldHandleNullError() {
            ServerSentEvent<String> result = service.createErrorSseEvent(null, "request-id");

            assertThat(result).isNotNull();
            assertThat(result.data()).contains("Unknown error occurred");
            assertThat(result.event()).isEqualTo("error");
        }
    }
}
