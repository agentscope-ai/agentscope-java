/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.spring.boot.responses.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.responses.model.ResponsesRequest;
import io.agentscope.core.responses.model.ResponsesStreamEvent;
import io.agentscope.core.responses.streaming.ResponsesStreamingAdapter;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * Spring-specific service for streaming Responses API events.
 *
 * <p>This service is a thin adapter layer that:
 *
 * <ul>
 *   <li>Delegates framework-agnostic streaming logic to {@link ResponsesStreamingAdapter}
 *   <li>Converts {@link ResponsesStreamEvent} objects to Spring {@link ServerSentEvent} objects
 *   <li>Handles JSON serialization for the SSE data field
 * </ul>
 *
 * <p><b>Architecture:</b>
 *
 * <pre>
 * ResponsesStreamingAdapter (framework-agnostic, in extension-core)
 *           -> Flux&lt;ResponsesStreamEvent&gt;
 * ResponsesStreamingService (Spring-specific, in starter)
 *           -> Flux&lt;ServerSentEvent&lt;String&gt;&gt;
 * HTTP Response (Responses-style SSE stream)
 * </pre>
 */
public class ResponsesStreamingService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ResponsesStreamingAdapter streamingAdapter;

    /**
     * Constructs a new {@code ResponsesStreamingService}.
     *
     * @param streamingAdapter The framework-agnostic Responses streaming adapter
     */
    public ResponsesStreamingService(ResponsesStreamingAdapter streamingAdapter) {
        this.streamingAdapter = streamingAdapter;
    }

    /**
     * Stream agent events as Responses API Server-Sent Events.
     *
     * <p>Each SSE data field contains one JSON-serialized {@link ResponsesStreamEvent}. Responses
     * streams do not append a Chat Completions-style {@code [DONE]} sentinel; completion is carried
     * by the {@code response.completed} event.
     *
     * @param agent The agent to stream from
     * @param messages The messages to send to the agent
     * @param request The original Responses API request
     * @param responseId The response ID used across the stream
     * @return A {@link Flux} of Spring SSE events
     */
    public Flux<ServerSentEvent<String>> streamAsSse(
            ReActAgent agent, List<Msg> messages, ResponsesRequest request, String responseId) {
        return toSseStream(
                streamingAdapter.stream(agent, messages, request, responseId), request, responseId);
    }

    /**
     * Stream agent events as Responses API Server-Sent Events with JSON Schema structured output.
     *
     * <p>The core agent uses its structured-output path, and the streaming adapter converts the
     * final structured payload into standard Responses text delta/done/completed events.
     *
     * @param agent The agent to stream from
     * @param messages The messages to send to the agent
     * @param structuredOutputSchema The JSON Schema requested by {@code text.format.type=json_schema}
     * @param request The original Responses API request
     * @param responseId The response ID used across the stream
     * @return A {@link Flux} of Spring SSE events
     */
    public Flux<ServerSentEvent<String>> streamAsSse(
            ReActAgent agent,
            List<Msg> messages,
            JsonNode structuredOutputSchema,
            ResponsesRequest request,
            String responseId) {
        return toSseStream(
                streamingAdapter.stream(
                        agent, messages, structuredOutputSchema, request, responseId),
                request,
                responseId);
    }

    /**
     * Stream agent events and observe each raw Responses event before Spring SSE serialization.
     *
     * <p>The controller uses this variant to persist the final {@code response.completed} or {@code
     * response.failed} payload after the stream finishes.
     *
     * @param agent The agent to stream from
     * @param messages The messages to send to the agent
     * @param structuredOutputSchema Optional JSON Schema for structured output
     * @param request The original Responses API request
     * @param responseId The response ID used across the stream
     * @param eventConsumer Observer invoked for each Responses stream event
     * @return A {@link Flux} of Spring SSE events
     */
    public Flux<ServerSentEvent<String>> streamAsSse(
            ReActAgent agent,
            List<Msg> messages,
            JsonNode structuredOutputSchema,
            ResponsesRequest request,
            String responseId,
            Consumer<ResponsesStreamEvent> eventConsumer) {
        return toSseStream(
                streamingAdapter.stream(
                                agent, messages, structuredOutputSchema, request, responseId)
                        .doOnNext(eventConsumer),
                request,
                responseId);
    }

    /**
     * Create an SSE event for a runtime failure after the request has entered streaming mode.
     *
     * @param error The error that occurred
     * @param request The original Responses API request
     * @param responseId The response ID used across the stream
     * @return A {@link ServerSentEvent} wrapping a {@code response.failed} event
     */
    public ServerSentEvent<String> createErrorSseEvent(
            Throwable error, ResponsesRequest request, String responseId) {
        return failedSse(streamingAdapter.createFailedEvent(error, request, responseId));
    }

    /**
     * Convert a Responses stream event to a Spring SSE event.
     *
     * @param events The Responses stream event to serialize
     * @return SSE event with the Responses event name and JSON data
     */
    private Flux<ServerSentEvent<String>> toSseStream(
            Flux<ResponsesStreamEvent> events, ResponsesRequest request, String responseId) {
        return events.map(this::toSse)
                .onErrorResume(
                        StreamSerializationException.class,
                        error ->
                                Flux.just(
                                        failedSse(
                                                streamingAdapter.createFailedEvent(
                                                        error.getCause(), request, responseId))));
    }

    private ServerSentEvent<String> toSse(ResponsesStreamEvent event) {
        try {
            return ServerSentEvent.<String>builder()
                    .event(event.getType())
                    .data(OBJECT_MAPPER.writeValueAsString(event))
                    .build();
        } catch (JsonProcessingException e) {
            throw new StreamSerializationException(e);
        }
    }

    private ServerSentEvent<String> failedSse(ResponsesStreamEvent event) {
        try {
            return ServerSentEvent.<String>builder()
                    .event(event.getType())
                    .data(OBJECT_MAPPER.writeValueAsString(event))
                    .build();
        } catch (JsonProcessingException e) {
            return ServerSentEvent.<String>builder()
                    .event("response.failed")
                    .data(
                            "{\"type\":\"response.failed\","
                                    + "\"error\":{\"type\":\"invalid_request_error\","
                                    + "\"code\":\"runtime_error\","
                                    + "\"message\":\"Serialization error\"}}")
                    .build();
        }
    }

    private static class StreamSerializationException extends RuntimeException {

        StreamSerializationException(JsonProcessingException cause) {
            super(cause);
        }
    }
}
