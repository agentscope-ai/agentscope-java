/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.chat.completions.streaming;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Adapter for handling streaming chat completion responses.
 *
 * <p>This adapter converts agent events to streaming format. The actual streaming format
 * (e.g., SSE) is determined by the implementation.
 *
 * @param <T> The type of streaming event (e.g., ServerSentEvent for SSE)
 */
public class ChatCompletionsStreamingAdapter<T> {

    private final ChatCompletionsResponseBuilder responseBuilder;
    private final Function<Event, T> eventConverter;
    private final Function<String, T> doneEventFactory;
    private final BiFunction<Throwable, String, T> errorEventFactory;

    /**
     * Constructs a new ChatCompletionsStreamingAdapter.
     *
     * @param responseBuilder Builder for extracting text content from messages
     * @param eventConverter Function to convert agent events to streaming events
     * @param doneEventFactory Function to create done event
     * @param errorEventFactory Function to create error event (takes Throwable and requestId)
     */
    public ChatCompletionsStreamingAdapter(
            ChatCompletionsResponseBuilder responseBuilder,
            Function<Event, T> eventConverter,
            Function<String, T> doneEventFactory,
            BiFunction<Throwable, String, T> errorEventFactory) {
        this.responseBuilder = responseBuilder;
        this.eventConverter = eventConverter;
        this.doneEventFactory = doneEventFactory;
        this.errorEventFactory = errorEventFactory;
    }

    /**
     * Stream agent events as streaming events.
     *
     * <p>Each event contains a text delta from a REASONING event. Clients can
     * accumulate these deltas to reconstruct the full assistant message, similar to OpenAI
     * streaming.
     *
     * @param agent The agent to stream from
     * @param messages The messages to send to the agent
     * @param requestId The request ID for tracking
     * @return Flux of streaming events
     */
    public Flux<T> stream(ReActAgent agent, List<Msg> messages, String requestId) {
        StreamOptions options =
                StreamOptions.builder().eventTypes(EventType.REASONING).incremental(true).build();

        return agent.stream(messages, options)
                .filter(event -> event.getMessage() != null)
                .flatMap(
                        event -> {
                            T streamingEvent = convertEvent(event, requestId);
                            if (streamingEvent == null) {
                                return Flux.empty();
                            }
                            return Flux.just(streamingEvent);
                        })
                .concatWith(Flux.just(doneEventFactory.apply(requestId)));
    }

    /**
     * Convert an agent event to a streaming event.
     *
     * @param event The agent event
     * @param requestId The request ID for tracking
     * @return The streaming event, or null if no text content
     */
    private T convertEvent(Event event, String requestId) {
        String text = responseBuilder.extractTextContent(event.getMessage());

        if (text == null || text.isEmpty()) {
            return null;
        }

        return eventConverter.apply(event);
    }

    /**
     * Create an error streaming event.
     *
     * @param error The error that occurred
     * @param requestId The request ID
     * @return The error streaming event
     */
    public T createErrorEvent(Throwable error, String requestId) {
        return errorEventFactory.apply(error, requestId);
    }
}
