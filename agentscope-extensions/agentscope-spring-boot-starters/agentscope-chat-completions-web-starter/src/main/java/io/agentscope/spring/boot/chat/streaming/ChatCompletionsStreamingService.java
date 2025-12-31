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
package io.agentscope.spring.boot.chat.streaming;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.streaming.ChatCompletionsStreamingAdapter;
import io.agentscope.core.message.Msg;
import java.util.List;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Service for handling streaming chat completion responses.
 *
 * <p>This service converts agent events to Server-Sent Events (SSE) format for streaming
 * responses.
 *
 * <p>This component is automatically discovered by Spring Boot's component scanning.
 */
@Component
public class ChatCompletionsStreamingService {

    private final ChatCompletionsResponseBuilder responseBuilder;
    private final ChatCompletionsStreamingAdapter<ServerSentEvent<String>> adapter;

    /**
     * Constructs a new {@code ChatCompletionsStreamingService} and initializes the underlying
     * streaming adapter using the provided {@link ChatCompletionsResponseBuilder}.
     *
     * @param responseBuilder The builder used to extract text content from agent messages
     */
    public ChatCompletionsStreamingService(ChatCompletionsResponseBuilder responseBuilder) {
        this.responseBuilder = responseBuilder;
        this.adapter =
                new ChatCompletionsStreamingAdapter<>(
                        responseBuilder,
                        this::eventToSse,
                        this::createDoneSseEvent,
                        this::createErrorSseEvent);
    }

    /**
     * Stream agent events as SSE text deltas.
     *
     * <p>Each SSE "data" field contains a text delta from a REASONING event. Clients can
     * accumulate these deltas to reconstruct the full assistant message, similar to OpenAI
     * streaming.
     *
     * @param agent The agent to stream from
     * @param messages The messages to send to the agent
     * @param requestId The request ID for tracking
     * @return A {@link Flux} of {@link ServerSentEvent} objects containing text deltas,
     *     followed by a "done" event when the stream completes
     */
    public Flux<ServerSentEvent<String>> streamAsSse(
            ReActAgent agent, List<Msg> messages, String requestId) {
        return adapter.stream(agent, messages, requestId);
    }

    /**
     * Convert an agent event to a Server-Sent Event.
     *
     * @param event The agent event
     * @return The SSE event, or null if no text content
     */
    private ServerSentEvent<String> eventToSse(Event event) {
        String text = responseBuilder.extractTextContent(event.getMessage());

        if (text == null || text.isEmpty()) {
            return null;
        }

        String requestId = event.getMessageId() != null ? event.getMessageId() : "unknown";
        ServerSentEvent.Builder<String> builder =
                ServerSentEvent.<String>builder().data(text).event("delta").id(requestId);

        if (event.isLast()) {
            builder.comment("end");
        }

        return builder.build();
    }

    /**
     * Create a done event to signal stream completion.
     *
     * @param requestId The request ID
     * @return The done SSE event
     */
    private ServerSentEvent<String> createDoneSseEvent(String requestId) {
        return ServerSentEvent.<String>builder().data("[DONE]").event("done").id(requestId).build();
    }

    /**
     * Create an error SSE event.
     *
     * @param error The error that occurred
     * @param requestId The request ID for tracking
     * @return A {@link ServerSentEvent} with event type "error" containing the error message
     */
    public ServerSentEvent<String> createErrorSseEvent(Throwable error, String requestId) {
        String errorMessage = error != null ? error.getMessage() : "Unknown error occurred";
        return ServerSentEvent.<String>builder()
                .data("Error: " + errorMessage)
                .event("error")
                .id(requestId)
                .build();
    }
}
