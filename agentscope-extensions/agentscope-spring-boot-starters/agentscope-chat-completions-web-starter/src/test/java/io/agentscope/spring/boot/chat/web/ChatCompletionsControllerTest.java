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
package io.agentscope.spring.boot.chat.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.converter.ChatMessageConverter;
import io.agentscope.core.chat.completions.model.ChatCompletionsRequest;
import io.agentscope.core.chat.completions.model.ChatCompletionsResponse;
import io.agentscope.core.chat.completions.model.ChatMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.spring.boot.chat.session.ChatCompletionsSessionManager;
import io.agentscope.spring.boot.chat.streaming.ChatCompletionsStreamingService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link ChatCompletionsController}.
 *
 * <p>These tests verify the controller's behavior without using Spring Test, relying purely on
 * JUnit 5 and Mockito.
 */
@DisplayName("ChatCompletionsController Tests")
class ChatCompletionsControllerTest {

    private ChatCompletionsController controller;
    private ObjectProvider<ReActAgent> agentProvider;
    private ChatCompletionsSessionManager sessionManager;
    private ChatMessageConverter messageConverter;
    private ChatCompletionsResponseBuilder responseBuilder;
    private ChatCompletionsStreamingService streamingService;
    private ReActAgent mockAgent;

    @BeforeEach
    void setUp() {
        agentProvider = mock(ObjectProvider.class);
        sessionManager = mock(ChatCompletionsSessionManager.class);
        messageConverter = mock(ChatMessageConverter.class);
        responseBuilder = mock(ChatCompletionsResponseBuilder.class);
        streamingService = mock(ChatCompletionsStreamingService.class);
        mockAgent = mock(ReActAgent.class);

        controller =
                new ChatCompletionsController(
                        agentProvider,
                        sessionManager,
                        messageConverter,
                        responseBuilder,
                        streamingService);
    }

    @Nested
    @DisplayName("Create Completion Tests")
    class CreateCompletionTests {

        @Test
        @DisplayName("Should process non-streaming request successfully")
        void shouldProcessNonStreamingRequestSuccessfully() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");
            request.setSessionId("test-session");
            request.setMessages(List.of(new ChatMessage("user", "Hello")));
            request.setStream(false);

            Msg replyMsg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Hi!").build())
                            .build();

            List<Msg> convertedMessages = List.of(replyMsg);
            ChatCompletionsResponse expectedResponse = new ChatCompletionsResponse();
            expectedResponse.setId("response-id");

            when(sessionManager.getOrCreateAgent(anyString(), any(ObjectProvider.class)))
                    .thenReturn(mockAgent);
            when(messageConverter.convertMessages(anyList())).thenReturn(convertedMessages);
            when(mockAgent.call(anyList())).thenReturn(Mono.just(replyMsg));
            when(responseBuilder.buildResponse(any(), any(), anyString()))
                    .thenReturn(expectedResponse);

            Mono<ChatCompletionsResponse> result = controller.createCompletion(request);

            StepVerifier.create(result).expectNext(expectedResponse).verifyComplete();

            verify(sessionManager).getOrCreateAgent(eq("test-session"), eq(agentProvider));
            verify(messageConverter).convertMessages(eq(request.getMessages()));
            verify(mockAgent).call(eq(convertedMessages));
            verify(responseBuilder).buildResponse(eq(request), eq(replyMsg), anyString());
        }

        @Test
        @DisplayName("Should reject streaming request on non-streaming endpoint")
        void shouldRejectStreamingRequestOnNonStreamingEndpoint() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setStream(true);
            request.setMessages(List.of(new ChatMessage("user", "Hello")));

            Mono<ChatCompletionsResponse> result = controller.createCompletion(request);

            StepVerifier.create(result)
                    .expectErrorMatches(
                            error ->
                                    error instanceof IllegalArgumentException
                                            && error.getMessage()
                                                    .contains("Streaming requests should use"))
                    .verify();
        }

        @Test
        @DisplayName("Should return error for empty messages")
        void shouldReturnErrorForEmptyMessages() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setMessages(List.of());

            when(sessionManager.getOrCreateAgent(anyString(), any(ObjectProvider.class)))
                    .thenReturn(mockAgent);
            when(messageConverter.convertMessages(anyList())).thenReturn(List.of());

            Mono<ChatCompletionsResponse> result = controller.createCompletion(request);

            StepVerifier.create(result)
                    .expectErrorMatches(
                            error ->
                                    error instanceof IllegalArgumentException
                                            && error.getMessage().contains("At least one message"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("Create Completion Stream Tests")
    class CreateCompletionStreamTests {

        @Test
        @DisplayName("Should process streaming request successfully")
        void shouldProcessStreamingRequestSuccessfully() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");
            request.setSessionId("test-session");
            request.setMessages(List.of(new ChatMessage("user", "Hello")));

            Msg replyMsg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Hi!").build())
                            .build();

            List<Msg> convertedMessages = List.of(replyMsg);
            ServerSentEvent<String> sseEvent =
                    ServerSentEvent.<String>builder().data("Hi!").build();

            when(sessionManager.getOrCreateAgent(anyString(), any(ObjectProvider.class)))
                    .thenReturn(mockAgent);
            when(messageConverter.convertMessages(anyList())).thenReturn(convertedMessages);
            when(streamingService.streamAsSse(any(), anyList(), anyString()))
                    .thenReturn(Flux.just(sseEvent));

            Flux<ServerSentEvent<String>> result = controller.createCompletionStream(request);

            StepVerifier.create(result).expectNext(sseEvent).verifyComplete();

            verify(sessionManager).getOrCreateAgent(eq("test-session"), eq(agentProvider));
            verify(messageConverter).convertMessages(eq(request.getMessages()));
            verify(streamingService).streamAsSse(eq(mockAgent), eq(convertedMessages), anyString());
        }

        @Test
        @DisplayName("Should return error for empty messages in streaming")
        void shouldReturnErrorForEmptyMessagesInStreaming() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setMessages(List.of());

            when(sessionManager.getOrCreateAgent(anyString(), any(ObjectProvider.class)))
                    .thenReturn(mockAgent);
            when(messageConverter.convertMessages(anyList())).thenReturn(List.of());

            Flux<ServerSentEvent<String>> result = controller.createCompletionStream(request);

            StepVerifier.create(result)
                    .expectErrorMatches(
                            error ->
                                    error instanceof IllegalArgumentException
                                            && error.getMessage().contains("At least one message"))
                    .verify();
        }

        @Test
        @DisplayName("Should handle streaming error gracefully")
        void shouldHandleStreamingErrorGracefully() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setMessages(List.of(new ChatMessage("user", "Hello")));

            RuntimeException error = new RuntimeException("Streaming error");
            ServerSentEvent<String> errorEvent =
                    ServerSentEvent.<String>builder().data("Error").build();

            when(sessionManager.getOrCreateAgent(anyString(), any(ObjectProvider.class)))
                    .thenReturn(mockAgent);
            when(messageConverter.convertMessages(anyList())).thenReturn(List.of(mock(Msg.class)));
            when(streamingService.streamAsSse(any(), anyList(), anyString()))
                    .thenReturn(Flux.error(error));
            when(streamingService.createErrorSseEvent(any(), anyString())).thenReturn(errorEvent);

            Flux<ServerSentEvent<String>> result = controller.createCompletionStream(request);

            StepVerifier.create(result).expectNext(errorEvent).verifyComplete();

            verify(streamingService).createErrorSseEvent(eq(error), anyString());
        }
    }
}
