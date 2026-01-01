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
import io.agentscope.spring.boot.chat.service.ChatCompletionsAgentService;
import io.agentscope.spring.boot.chat.service.ChatCompletionsStreamingService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * HTTP controller exposing a Chat Completions style API on top of a ReActAgent.
 *
 * <p>This controller delegates business logic to service classes:
 *
 * <ul>
 *   <li>{@link ChatCompletionsAgentService} - Manages agent lifecycle and state persistence
 *   <li>{@link ChatMessageConverter} - Converts HTTP DTOs to framework messages
 *   <li>{@link ChatCompletionsResponseBuilder} - Builds response objects
 *   <li>{@link ChatCompletionsStreamingService} - Handles streaming responses
 * </ul>
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Non-streaming JSON response compatible with OpenAI-style chat completions
 *   <li>SSE streaming when client accepts {@code text/event-stream}
 *   <li>Automatic state persistence after each request via Session
 * </ul>
 */
@RestController
@RequestMapping
public class ChatCompletionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);

    private final ChatCompletionsAgentService agentService;
    private final ChatMessageConverter messageConverter;
    private final ChatCompletionsResponseBuilder responseBuilder;
    private final ChatCompletionsStreamingService streamingService;

    /**
     * Constructs a new ChatCompletionsController.
     *
     * @param agentService Service for managing agent lifecycle and state persistence
     * @param messageConverter Converter for HTTP DTOs to framework messages
     * @param responseBuilder Builder for response objects
     * @param streamingService Service for streaming responses
     */
    public ChatCompletionsController(
            ChatCompletionsAgentService agentService,
            ChatMessageConverter messageConverter,
            ChatCompletionsResponseBuilder responseBuilder,
            ChatCompletionsStreamingService streamingService) {
        this.agentService = agentService;
        this.messageConverter = messageConverter;
        this.responseBuilder = responseBuilder;
        this.streamingService = streamingService;
    }

    /**
     * Non-streaming chat completion endpoint.
     *
     * @param request The chat completion request
     * @return A {@link Mono} containing the {@link ChatCompletionsResponse} with the agent's reply
     */
    @PostMapping(
            value = "${agentscope.chat-completions.base-path:/v1/chat/completions}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ChatCompletionsResponse> createCompletion(
            @Valid @RequestBody ChatCompletionsRequest request) {
        String requestId = UUID.randomUUID().toString();
        String sessionId = agentService.resolveSessionId(request.getSessionId());

        log.debug(
                "Processing chat completion request: requestId={}, sessionId={}, messageCount={},"
                        + " stream={}",
                requestId,
                sessionId,
                request.getMessages() != null ? request.getMessages().size() : 0,
                request.getStream());

        // Reject streaming requests on non-streaming endpoint
        if (Boolean.TRUE.equals(request.getStream())) {
            log.warn(
                    "Streaming request received on non-streaming endpoint: requestId={}",
                    requestId);
            return Mono.error(
                    new IllegalArgumentException(
                            "Streaming requests should use the streaming endpoint with Accept:"
                                    + " text/event-stream"));
        }

        try {
            ReActAgent agent = agentService.getAgent(sessionId);

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
                                        "Request completed: requestId={}, duration={}ms,"
                                                + " sessionId={}",
                                        requestId,
                                        duration,
                                        sessionId);
                                return responseBuilder.buildResponse(request, reply, requestId);
                            })
                    .doFinally(
                            signal -> {
                                // Save agent state after request completes (success or error)
                                agentService.saveAgentState(sessionId, agent);
                            })
                    .onErrorResume(
                            error -> {
                                log.error(
                                        "Error processing chat completion request: requestId={},"
                                                + " sessionId={}",
                                        requestId,
                                        sessionId,
                                        error);
                                return Mono.just(
                                        responseBuilder.buildErrorResponse(
                                                request, error, requestId));
                            });
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Error processing request: requestId={}", requestId, e);
            return Mono.error(e);
        } catch (Exception e) {
            log.error("Error creating agent or processing request: requestId={}", requestId, e);
            return Mono.error(new RuntimeException("Failed to process request", e));
        }
    }

    /**
     * Streaming chat completion endpoint.
     *
     * @param request The chat completion request
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
        String sessionId = agentService.resolveSessionId(request.getSessionId());

        log.debug(
                "Processing streaming chat completion request: requestId={}, sessionId={},"
                        + " messageCount={}",
                requestId,
                sessionId,
                request.getMessages() != null ? request.getMessages().size() : 0);

        try {
            ReActAgent agent = agentService.getAgent(sessionId);

            List<Msg> messages = messageConverter.convertMessages(request.getMessages());
            if (messages.isEmpty()) {
                log.warn("Empty messages list in streaming request: requestId={}", requestId);
                return Flux.error(new IllegalArgumentException("At least one message is required"));
            }

            return streamingService
                    .streamAsSse(agent, messages, requestId)
                    .doFinally(
                            signal -> {
                                // Save agent state after streaming completes
                                agentService.saveAgentState(sessionId, agent);
                            })
                    .onErrorResume(
                            error -> {
                                log.error(
                                        "Error in streaming response: requestId={}, sessionId={}",
                                        requestId,
                                        sessionId,
                                        error);
                                return Flux.just(
                                        streamingService.createErrorSseEvent(error, requestId));
                            });
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Error processing streaming request: requestId={}", requestId, e);
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
