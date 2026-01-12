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
package io.agentscope.spring.boot.chat.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import io.agentscope.spring.boot.chat.service.ChatCompletionsStreamingService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link ChatCompletionsController}.
 *
 * <p>These tests verify the stateless controller's behavior without using Spring Test, relying
 * purely on JUnit 5 and Mockito.
 */
@DisplayName("ChatCompletionsController Tests")
class ChatCompletionsControllerTest {

    private ChatCompletionsController controller;
    private ObjectProvider<ReActAgent> agentProvider;
    private ChatMessageConverter messageConverter;
    private ChatCompletionsResponseBuilder responseBuilder;
    private ChatCompletionsStreamingService streamingService;
    private ReActAgent mockAgent;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        agentProvider = mock(ObjectProvider.class);
        messageConverter = mock(ChatMessageConverter.class);
        responseBuilder = mock(ChatCompletionsResponseBuilder.class);
        streamingService = mock(ChatCompletionsStreamingService.class);
        mockAgent = mock(ReActAgent.class);

        // Default: agentProvider returns mockAgent
        when(agentProvider.getObject()).thenReturn(mockAgent);

        controller =
                new ChatCompletionsController(
                        agentProvider, messageConverter, responseBuilder, streamingService);
    }

    @Nested
    @DisplayName("Create Completion Tests")
    class CreateCompletionTests {

        @Test
        @DisplayName("Should process non-streaming request successfully")
        void shouldProcessNonStreamingRequestSuccessfully() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");
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

            when(messageConverter.convertMessages(anyList())).thenReturn(convertedMessages);
            when(mockAgent.call(anyList())).thenReturn(Mono.just(replyMsg));
            when(responseBuilder.buildResponse(any(), any(), anyString()))
                    .thenReturn(expectedResponse);

            Object result = controller.createCompletion(request);

            // Result should be Mono<ChatCompletionsResponse> for non-streaming requests
            assert result instanceof Mono;
            @SuppressWarnings("unchecked")
            Mono<ChatCompletionsResponse> responseMono = (Mono<ChatCompletionsResponse>) result;
            StepVerifier.create(responseMono).expectNext(expectedResponse).verifyComplete();

            verify(agentProvider).getObject();
            verify(messageConverter).convertMessages(eq(request.getMessages()));
            verify(mockAgent).call(eq(convertedMessages));
            verify(responseBuilder).buildResponse(eq(request), eq(replyMsg), anyString());
        }

        @Test
        @DisplayName("Should auto-switch to streaming mode when stream=true")
        void shouldAutoSwitchToStreamingModeWhenStreamTrue() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");
            request.setStream(true);
            request.setMessages(List.of(new ChatMessage("user", "Hello")));

            List<Msg> convertedMessages = List.of(mock(Msg.class));
            ServerSentEvent<String> sseEvent =
                    ServerSentEvent.<String>builder().data("test").build();

            when(messageConverter.convertMessages(anyList())).thenReturn(convertedMessages);
            when(streamingService.streamAsSse(any(), anyList(), anyString(), anyString()))
                    .thenReturn(Flux.just(sseEvent));

            Object result = controller.createCompletion(request);

            // Result should be ResponseEntity with Flux body for streaming requests
            assert result instanceof ResponseEntity;
            @SuppressWarnings("unchecked")
            ResponseEntity<Flux<ServerSentEvent<String>>> responseEntity =
                    (ResponseEntity<Flux<ServerSentEvent<String>>>) result;

            // Verify response headers
            HttpHeaders headers = responseEntity.getHeaders();
            assert headers.getFirst("Content-Type").equals("text/event-stream");

            // Verify the stream content
            StepVerifier.create(responseEntity.getBody()).expectNext(sseEvent).verifyComplete();

            verify(agentProvider).getObject();
            verify(messageConverter).convertMessages(eq(request.getMessages()));
            verify(streamingService)
                    .streamAsSse(
                            eq(mockAgent), eq(convertedMessages), anyString(), eq("test-model"));
        }

        @Test
        @DisplayName("Should return error for empty messages")
        void shouldReturnErrorForEmptyMessages() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setMessages(List.of());
            request.setStream(false);

            when(messageConverter.convertMessages(anyList())).thenReturn(List.of());

            Object result = controller.createCompletion(request);

            // Result should be Mono<ChatCompletionsResponse> with error
            assert result instanceof Mono;
            @SuppressWarnings("unchecked")
            Mono<ChatCompletionsResponse> responseMono = (Mono<ChatCompletionsResponse>) result;
            StepVerifier.create(responseMono)
                    .expectErrorMatches(
                            error ->
                                    error instanceof IllegalArgumentException
                                            && error.getMessage().contains("At least one message"))
                    .verify();
        }

        @Test
        @DisplayName("Should handle agent returning null gracefully")
        void shouldHandleAgentReturningNullGracefully() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setMessages(List.of(new ChatMessage("user", "Hello")));
            request.setStream(false);

            when(agentProvider.getObject()).thenReturn(null);

            Object result = controller.createCompletion(request);

            // Result should be Mono<ChatCompletionsResponse> with error
            assert result instanceof Mono;
            @SuppressWarnings("unchecked")
            Mono<ChatCompletionsResponse> responseMono = (Mono<ChatCompletionsResponse>) result;
            StepVerifier.create(responseMono)
                    .expectErrorMatches(
                            error ->
                                    error instanceof IllegalStateException
                                            && error.getMessage()
                                                    .contains("agentProvider returned null"))
                    .verify();
        }

        @Test
        @DisplayName("Should process request with stream=null as non-streaming")
        void shouldProcessRequestWithStreamNullAsNonStreaming() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");
            request.setMessages(List.of(new ChatMessage("user", "Hello")));
            request.setStream(null); // Explicitly set to null

            Msg replyMsg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Hi!").build())
                            .build();

            List<Msg> convertedMessages = List.of(replyMsg);
            ChatCompletionsResponse expectedResponse = new ChatCompletionsResponse();
            expectedResponse.setId("response-id");

            when(messageConverter.convertMessages(anyList())).thenReturn(convertedMessages);
            when(mockAgent.call(anyList())).thenReturn(Mono.just(replyMsg));
            when(responseBuilder.buildResponse(any(), any(), anyString()))
                    .thenReturn(expectedResponse);

            Object result = controller.createCompletion(request);

            // Result should be Mono<ChatCompletionsResponse> for non-streaming requests
            assert result instanceof Mono;
            @SuppressWarnings("unchecked")
            Mono<ChatCompletionsResponse> responseMono = (Mono<ChatCompletionsResponse>) result;
            StepVerifier.create(responseMono).expectNext(expectedResponse).verifyComplete();

            verify(agentProvider).getObject();
            verify(messageConverter).convertMessages(eq(request.getMessages()));
            verify(mockAgent).call(eq(convertedMessages));
            verify(responseBuilder).buildResponse(eq(request), eq(replyMsg), anyString());
            // Should NOT call streaming service
            verify(streamingService, never()).streamAsSse(any(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("Should handle errors in non-streaming request gracefully")
        void shouldHandleErrorsInNonStreamingRequestGracefully() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");
            request.setMessages(List.of(new ChatMessage("user", "Hello")));
            request.setStream(false);

            Msg replyMsg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Hi!").build())
                            .build();

            List<Msg> convertedMessages = List.of(replyMsg);
            RuntimeException agentError = new RuntimeException("Agent error");
            ChatCompletionsResponse errorResponse = new ChatCompletionsResponse();
            errorResponse.setId("error-id");

            when(messageConverter.convertMessages(anyList())).thenReturn(convertedMessages);
            when(mockAgent.call(anyList())).thenReturn(Mono.error(agentError));
            when(responseBuilder.buildErrorResponse(any(), eq(agentError), anyString()))
                    .thenReturn(errorResponse);

            Object result = controller.createCompletion(request);

            // Result should be Mono<ChatCompletionsResponse> with error response
            assert result instanceof Mono;
            @SuppressWarnings("unchecked")
            Mono<ChatCompletionsResponse> responseMono = (Mono<ChatCompletionsResponse>) result;
            StepVerifier.create(responseMono).expectNext(errorResponse).verifyComplete();

            verify(responseBuilder).buildErrorResponse(eq(request), eq(agentError), anyString());
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
            request.setMessages(List.of(new ChatMessage("user", "Hello")));
            // stream can be null or true for streaming endpoint

            Msg replyMsg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Hi!").build())
                            .build();

            List<Msg> convertedMessages = List.of(replyMsg);
            ServerSentEvent<String> sseEvent =
                    ServerSentEvent.<String>builder().data("Hi!").build();

            when(messageConverter.convertMessages(anyList())).thenReturn(convertedMessages);
            when(streamingService.streamAsSse(any(), anyList(), anyString(), anyString()))
                    .thenReturn(Flux.just(sseEvent));

            Flux<ServerSentEvent<String>> result = controller.createCompletionStream(request);

            StepVerifier.create(result).expectNext(sseEvent).verifyComplete();

            verify(agentProvider).getObject();
            verify(messageConverter).convertMessages(eq(request.getMessages()));
            verify(streamingService)
                    .streamAsSse(
                            eq(mockAgent), eq(convertedMessages), anyString(), eq("test-model"));
        }

        @Test
        @DisplayName("Should reject non-streaming request on streaming endpoint")
        void shouldRejectNonStreamingRequestOnStreamingEndpoint() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");
            request.setMessages(List.of(new ChatMessage("user", "Hello")));
            request.setStream(false); // Explicitly set to false

            Flux<ServerSentEvent<String>> result = controller.createCompletionStream(request);

            StepVerifier.create(result)
                    .expectErrorMatches(
                            error ->
                                    error instanceof IllegalArgumentException
                                            && error.getMessage()
                                                    .contains("Non-streaming requests"))
                    .verify();

            // Should NOT call streaming service
            verify(streamingService, never()).streamAsSse(any(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("Should return error for empty messages in streaming")
        void shouldReturnErrorForEmptyMessagesInStreaming() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setMessages(List.of());

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
            request.setModel("test-model");
            request.setMessages(List.of(new ChatMessage("user", "Hello")));

            RuntimeException error = new RuntimeException("Streaming error");
            ServerSentEvent<String> errorEvent =
                    ServerSentEvent.<String>builder().data("Error").build();

            when(messageConverter.convertMessages(anyList())).thenReturn(List.of(mock(Msg.class)));
            when(streamingService.streamAsSse(any(), anyList(), anyString(), anyString()))
                    .thenReturn(Flux.error(error));
            when(streamingService.createErrorSseEvent(any(), anyString(), anyString()))
                    .thenReturn(errorEvent);

            Flux<ServerSentEvent<String>> result = controller.createCompletionStream(request);

            StepVerifier.create(result).expectNext(errorEvent).verifyComplete();

            verify(streamingService).createErrorSseEvent(eq(error), anyString(), eq("test-model"));
        }

        @Test
        @DisplayName("Should handle auto-switch streaming error gracefully")
        void shouldHandleAutoSwitchStreamingErrorGracefully() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");
            request.setStream(true);
            request.setMessages(List.of(new ChatMessage("user", "Hello")));

            RuntimeException error = new RuntimeException("Streaming error");
            ServerSentEvent<String> errorEvent =
                    ServerSentEvent.<String>builder().data("Error").build();

            when(messageConverter.convertMessages(anyList())).thenReturn(List.of(mock(Msg.class)));
            when(streamingService.streamAsSse(any(), anyList(), anyString(), anyString()))
                    .thenReturn(Flux.error(error));
            when(streamingService.createErrorSseEvent(any(), anyString(), anyString()))
                    .thenReturn(errorEvent);

            Object result = controller.createCompletion(request);

            // Result should be ResponseEntity with Flux body containing error
            assert result instanceof ResponseEntity;
            @SuppressWarnings("unchecked")
            ResponseEntity<Flux<ServerSentEvent<String>>> responseEntity =
                    (ResponseEntity<Flux<ServerSentEvent<String>>>) result;

            // Verify response headers
            HttpHeaders headers = responseEntity.getHeaders();
            assert headers.getFirst("Content-Type").equals("text/event-stream");

            // Verify the error event is returned
            StepVerifier.create(responseEntity.getBody()).expectNext(errorEvent).verifyComplete();

            verify(streamingService).createErrorSseEvent(eq(error), anyString(), eq("test-model"));
        }

        @Test
        @DisplayName("Should handle empty messages in auto-switch streaming mode")
        void shouldHandleEmptyMessagesInAutoSwitchStreamingMode() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");
            request.setStream(true);
            request.setMessages(List.of());

            when(messageConverter.convertMessages(anyList())).thenReturn(List.of());

            Object result = controller.createCompletion(request);

            // Result should be ResponseEntity with Flux body containing error
            assert result instanceof ResponseEntity;
            @SuppressWarnings("unchecked")
            ResponseEntity<Flux<ServerSentEvent<String>>> responseEntity =
                    (ResponseEntity<Flux<ServerSentEvent<String>>>) result;

            // Verify response headers
            HttpHeaders headers = responseEntity.getHeaders();
            assert headers.getFirst("Content-Type").equals("text/event-stream");

            // Verify error is returned
            StepVerifier.create(responseEntity.getBody())
                    .expectErrorMatches(
                            error ->
                                    error instanceof IllegalArgumentException
                                            && error.getMessage().contains("At least one message"))
                    .verify();
        }
    }
}
