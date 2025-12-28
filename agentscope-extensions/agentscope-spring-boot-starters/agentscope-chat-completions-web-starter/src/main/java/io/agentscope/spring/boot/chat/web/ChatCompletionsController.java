package io.agentscope.spring.boot.chat.web;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.spring.boot.chat.api.ChatCompletionsRequest;
import io.agentscope.spring.boot.chat.api.ChatCompletionsResponse;
import io.agentscope.spring.boot.chat.builder.ChatCompletionsResponseBuilder;
import io.agentscope.spring.boot.chat.converter.ChatMessageConverter;
import io.agentscope.spring.boot.chat.session.ChatCompletionsSessionManager;
import io.agentscope.spring.boot.chat.streaming.ChatCompletionsStreamingService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 *   <li>{@link ChatMessageConverter} - Converts HTTP DTOs to framework messages</li>
 *   <li>{@link ChatCompletionsResponseBuilder} - Builds response objects</li>
 *   <li>{@link ChatCompletionsStreamingService} - Handles streaming responses</li>
 * </ul>
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Non-streaming JSON response compatible with OpenAI-style chat completions</li>
 *   <li>SSE streaming when client accepts {@code text/event-stream}</li>
 * </ul>
 */
@RestController
@RequestMapping
public class ChatCompletionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);

    private final ObjectProvider<ReActAgent> agentProvider;
    private final ChatCompletionsSessionManager sessionManager;
    private final ChatMessageConverter messageConverter;
    private final ChatCompletionsResponseBuilder responseBuilder;
    private final ChatCompletionsStreamingService streamingService;

    /**
     * Constructs a new ChatCompletionsController.
     *
     * @param agentProvider Provider for creating ReActAgent instances
     * @param sessionManager Manager for session-scoped agents
     * @param messageConverter Converter for HTTP DTOs to framework messages
     * @param responseBuilder Builder for response objects
     * @param streamingService Service for streaming responses
     */
    public ChatCompletionsController(
            ObjectProvider<ReActAgent> agentProvider,
            ChatCompletionsSessionManager sessionManager,
            ChatMessageConverter messageConverter,
            ChatCompletionsResponseBuilder responseBuilder,
            ChatCompletionsStreamingService streamingService) {
        this.agentProvider = agentProvider;
        this.sessionManager = sessionManager;
        this.messageConverter = messageConverter;
        this.responseBuilder = responseBuilder;
        this.streamingService = streamingService;
    }

    /**
     * Non-streaming chat completion endpoint.
     *
     * @param request The chat completion request
     * @return Mono containing the chat completion response
     */
    @PostMapping(
            value = "${agentscope.chat-completions.base-path:/v1/chat/completions}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ChatCompletionsResponse> createCompletion(
            @Valid @RequestBody ChatCompletionsRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.debug(
                "Processing chat completion request: requestId={}, sessionId={}, messageCount={},"
                        + " stream={}",
                requestId,
                request.getSessionId(),
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
            ReActAgent agent =
                    sessionManager.getOrCreateAgent(request.getSessionId(), agentProvider);

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
                                        request.getSessionId());
                                return responseBuilder.buildResponse(request, reply, requestId);
                            })
                    .onErrorResume(
                            error -> {
                                log.error(
                                        "Error processing chat completion request: requestId={},"
                                                + " sessionId={}",
                                        requestId,
                                        request.getSessionId(),
                                        error);
                                return Mono.just(
                                        responseBuilder.buildErrorResponse(
                                                request, error, requestId));
                            })
                    .doOnError(
                            error ->
                                    log.error(
                                            "Unexpected error in chat completion: requestId={}",
                                            requestId,
                                            error));
        } catch (Exception e) {
            log.error("Error creating agent or processing request: requestId={}", requestId, e);
            return Mono.error(new RuntimeException("Failed to process request", e));
        }
    }

    /**
     * Streaming chat completion endpoint.
     *
     * @param request The chat completion request
     * @return Flux of Server-Sent Events
     */
    @PostMapping(
            value = "${agentscope.chat-completions.base-path:/v1/chat/completions}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> createCompletionStream(
            @Valid @RequestBody ChatCompletionsRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.debug(
                "Processing streaming chat completion request: requestId={}, sessionId={},"
                        + " messageCount={}",
                requestId,
                request.getSessionId(),
                request.getMessages() != null ? request.getMessages().size() : 0);

        try {
            ReActAgent agent =
                    sessionManager.getOrCreateAgent(request.getSessionId(), agentProvider);

            List<Msg> messages = messageConverter.convertMessages(request.getMessages());
            if (messages.isEmpty()) {
                log.warn("Empty messages list in streaming request: requestId={}", requestId);
                return Flux.error(new IllegalArgumentException("At least one message is required"));
            }

            return streamingService
                    .streamAsSse(agent, messages, requestId)
                    .onErrorResume(
                            error -> {
                                log.error(
                                        "Error in streaming response: requestId={}, sessionId={}",
                                        requestId,
                                        request.getSessionId(),
                                        error);
                                return Flux.just(
                                        streamingService.createErrorSseEvent(error, requestId));
                            })
                    .doOnError(
                            error ->
                                    log.error(
                                            "Unexpected error in streaming: requestId={}",
                                            requestId,
                                            error));
        } catch (Exception e) {
            log.error(
                    "Error creating agent or processing streaming request: requestId={}",
                    requestId,
                    e);
            return Flux.error(new RuntimeException("Failed to process streaming request", e));
        }
    }
}
