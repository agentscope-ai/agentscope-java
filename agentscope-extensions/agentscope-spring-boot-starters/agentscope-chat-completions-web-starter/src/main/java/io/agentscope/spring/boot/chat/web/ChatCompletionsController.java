/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.chat.web;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.converter.ChatMessageConverter;
import io.agentscope.core.chat.completions.model.ChatCompletionsRequest;
import io.agentscope.core.chat.completions.model.ChatCompletionsResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.spring.boot.chat.service.ChatCompletionsStreamingService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * HTTP controller exposing a Chat Completions API compatible with OpenAI's standard.
 *
 * <p>This controller implements a <b>100% stateless API</b>, fully compatible with OpenAI's Chat
 * Completions API. Each request is independent, and the client is responsible for managing
 * conversation history.
 *
 * <p><b>How It Works:</b>
 *
 * <ol>
 *   <li>Client sends complete conversation history in {@code messages}
 *   <li>Server creates a fresh agent instance, loads the messages
 *   <li>Agent processes and returns a response
 *   <li>Client appends the assistant message to their history for next request
 * </ol>
 *
 * <p><b>Tool Calls:</b>
 *
 * <p>When the assistant uses tools, the response includes {@code tool_calls} in the assistant
 * message. The client should:
 *
 * <ol>
 *   <li>Append the assistant message (with tool_calls) to history
 *   <li>Execute the tools and create tool result messages
 *   <li>Send the updated history in the next request
 * </ol>
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Non-streaming JSON response (default)
 *   <li>SSE streaming when Accept: text/event-stream
 *   <li>Full OpenAI compatibility
 * </ul>
 */
@RestController
@RequestMapping
public class ChatCompletionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);

    private final ObjectProvider<ReActAgent> agentProvider;
    private final ChatMessageConverter messageConverter;
    private final ChatCompletionsResponseBuilder responseBuilder;
    private final ChatCompletionsStreamingService streamingService;

    /**
     * Constructs a new ChatCompletionsController.
     *
     * @param agentProvider Provider for creating prototype-scoped agent instances
     * @param messageConverter Converter for HTTP DTOs to framework messages
     * @param responseBuilder Builder for response objects
     * @param streamingService Service for streaming responses
     */
    public ChatCompletionsController(
            ObjectProvider<ReActAgent> agentProvider,
            ChatMessageConverter messageConverter,
            ChatCompletionsResponseBuilder responseBuilder,
            ChatCompletionsStreamingService streamingService) {
        this.agentProvider = agentProvider;
        this.messageConverter = messageConverter;
        this.responseBuilder = responseBuilder;
        this.streamingService = streamingService;
    }

    /**
     * Non-streaming chat completion endpoint.
     *
     * <p>Processes the complete message history and returns an assistant response.
     *
     * <p>If the request has {@code stream=true}, automatically switches to streaming mode even
     * without {@code Accept: text/event-stream} header for better client compatibility.
     *
     * @param request The chat completion request containing the full message history
     * @return A {@link Mono} containing the {@link ChatCompletionsResponse} with the agent's reply,
     *     or a {@link Flux} of {@link ServerSentEvent} if streaming is requested
     */
    @PostMapping(
            value = "${agentscope.chat-completions.base-path:/v1/chat/completions}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object createCompletion(@Valid @RequestBody ChatCompletionsRequest request) {
        String requestId = UUID.randomUUID().toString();

        log.debug(
                "Processing chat completion request: requestId={}, messageCount={}, stream={}",
                requestId,
                request.getMessages() != null ? request.getMessages().size() : 0,
                request.getStream());

        // If stream=true, automatically switch to streaming mode for client compatibility
        // This handles clients that set stream=true but don't set Accept: text/event-stream
        if (Boolean.TRUE.equals(request.getStream())) {
            log.debug("Auto-switching to streaming mode: requestId={}", requestId);
            // Return Flux with explicit content type - Spring MVC needs this to handle SSE
            Flux<ServerSentEvent<String>> stream = createCompletionStream(request);
            return ResponseEntity.ok()
                    .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(stream);
        }

        try {
            // Create a fresh agent instance for this request (stateless)
            ReActAgent agent = agentProvider.getObject();
            if (agent == null) {
                return Mono.error(
                        new IllegalStateException(
                                "Failed to create ReActAgent: agentProvider returned null"));
            }

            // Convert all messages from the request
            List<Msg> messages = messageConverter.convertMessages(request.getMessages());
            if (messages.isEmpty()) {
                log.warn("Empty messages list in request: requestId={}", requestId);
                return Mono.error(new IllegalArgumentException("At least one message is required"));
            }

            long startTime = System.currentTimeMillis();
            return agent.call(messages)
                    .map(
                            reply -> {
                                long duration = System.currentTimeMillis() - startTime;
                                log.debug(
                                        "Request completed: requestId={}, duration={}ms",
                                        requestId,
                                        duration);
                                return responseBuilder.buildResponse(request, reply, requestId);
                            })
                    .onErrorResume(
                            error -> {
                                log.error(
                                        "Error processing chat completion request: requestId={}",
                                        requestId,
                                        error);
                                return Mono.just(
                                        responseBuilder.buildErrorResponse(
                                                request, error, requestId));
                            });
        } catch (IllegalArgumentException e) {
            log.error("Invalid request: requestId={}", requestId, e);
            return Mono.error(e);
        } catch (Exception e) {
            log.error("Error creating agent or processing request: requestId={}", requestId, e);
            return Mono.error(new RuntimeException("Failed to process request", e));
        }
    }

    /**
     * Streaming chat completion endpoint.
     *
     * <p>Processes the complete message history and streams the response as Server-Sent Events.
     *
     * @param request The chat completion request containing the full message history
     * @return A {@link Flux} of {@link ServerSentEvent} containing text deltas and completion
     *     events
     */
    @PostMapping(
            value = "${agentscope.chat-completions.base-path:/v1/chat/completions}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> createCompletionStream(
            @Valid @RequestBody ChatCompletionsRequest request) {
        String requestId = UUID.randomUUID().toString();

        log.debug(
                "Processing streaming chat completion request: requestId={}, messageCount={},"
                        + " stream={}",
                requestId,
                request.getMessages() != null ? request.getMessages().size() : 0,
                request.getStream());

        // Reject non-streaming requests on streaming endpoint
        // If stream is explicitly false, return error for consistency
        if (Boolean.FALSE.equals(request.getStream())) {
            log.warn(
                    "Non-streaming request received on streaming endpoint: requestId={},"
                            + " stream=false",
                    requestId);
            return Flux.error(
                    new IllegalArgumentException(
                            "Non-streaming requests (stream=false) should use the non-streaming"
                                    + " endpoint without Accept: text/event-stream header"));
        }

        try {
            // Create a fresh agent instance for this request (stateless)
            ReActAgent agent = agentProvider.getObject();
            if (agent == null) {
                return Flux.error(
                        new IllegalStateException(
                                "Failed to create ReActAgent: agentProvider returned null"));
            }

            // Convert all messages from the request
            List<Msg> messages = messageConverter.convertMessages(request.getMessages());
            if (messages.isEmpty()) {
                log.warn("Empty messages list in streaming request: requestId={}", requestId);
                return Flux.error(new IllegalArgumentException("At least one message is required"));
            }

            String model = request.getModel();
            return streamingService
                    .streamAsSse(agent, messages, requestId, model)
                    .onErrorResume(
                            error -> {
                                log.error(
                                        "Error in streaming response: requestId={}",
                                        requestId,
                                        error);
                                return Flux.just(
                                        streamingService.createErrorSseEvent(
                                                error, requestId, model));
                            });
        } catch (IllegalArgumentException e) {
            log.error("Invalid streaming request: requestId={}", requestId, e);
            return Flux.error(e);
        } catch (Exception e) {
            log.error(
                    "Error creating agent or processing streaming request: requestId={}",
                    requestId,
                    e);
            return Flux.error(new RuntimeException("Failed to process streaming request", e));
        }
    }
}
