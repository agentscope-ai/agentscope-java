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
package io.agentscope.spring.boot.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.model.ChatCompletionsChunk;
import io.agentscope.core.chat.completions.streaming.ChatCompletionsStreamingAdapter;
import io.agentscope.core.message.Msg;
import java.util.List;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * Spring-specific service for streaming chat completion responses.
 *
 * <p>This service is a thin adapter layer that:
 *
 * <ul>
 *   <li>Delegates core streaming logic to {@link ChatCompletionsStreamingAdapter}
 *   <li>Converts {@link ChatCompletionsChunk} to Spring's {@link ServerSentEvent}
 *   <li>Handles JSON serialization for SSE data field
 * </ul>
 *
 * <p><b>Architecture:</b>
 *
 * <pre>
 * ChatCompletionsStreamingAdapter (framework-agnostic, in extension-core)
 *           ↓ Flux&lt;ChatCompletionsChunk&gt;
 * ChatCompletionsStreamingService (Spring-specific, in starter)
 *           ↓ Flux&lt;ServerSentEvent&lt;String&gt;&gt;
 * HTTP Response (SSE stream)
 * </pre>
 *
 * <p>This design allows the core streaming logic to be reused across different frameworks (Spring,
 * Quarkus, etc.) while keeping framework-specific concerns in the starter modules.
 */
public class ChatCompletionsStreamingService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChatCompletionsStreamingAdapter streamingAdapter;

    /**
     * Constructs a new {@code ChatCompletionsStreamingService}.
     *
     * @param streamingAdapter The framework-agnostic streaming adapter
     */
    public ChatCompletionsStreamingService(ChatCompletionsStreamingAdapter streamingAdapter) {
        this.streamingAdapter = streamingAdapter;
    }

    /**
     * Stream agent events as Server-Sent Events (SSE).
     *
     * <p>Each SSE "data" field contains a JSON-serialized {@link ChatCompletionsChunk} following
     * OpenAI's streaming format. The stream ends with a "data: [DONE]" event.
     *
     * @param agent The agent to stream from
     * @param messages The messages to send to the agent
     * @param requestId The request ID for tracking
     * @param model The model name for the response
     * @return A {@link Flux} of {@link ServerSentEvent} objects
     */
    public Flux<ServerSentEvent<String>> streamAsSse(
            ReActAgent agent, List<Msg> messages, String requestId, String model) {
        return streamingAdapter.stream(agent, messages, requestId, model)
                .map(this::chunkToSseEvent)
                .concatWith(Flux.just(createDoneSseEvent()));
    }

    /**
     * Convert a chunk to an SSE event.
     *
     * @param chunk The chunk to convert
     * @return SSE event with JSON data
     */
    private ServerSentEvent<String> chunkToSseEvent(ChatCompletionsChunk chunk) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(chunk);
            return ServerSentEvent.<String>builder().data(json).build();
        } catch (JsonProcessingException e) {
            return ServerSentEvent.<String>builder()
                    .data("{\"error\":\"Serialization error\"}")
                    .build();
        }
    }

    /**
     * Create a done event to signal stream completion.
     *
     * @return The done SSE event with "[DONE]" data
     */
    private ServerSentEvent<String> createDoneSseEvent() {
        return ServerSentEvent.<String>builder().data("[DONE]").build();
    }

    /**
     * Create an error SSE event.
     *
     * @param error The error that occurred
     * @param requestId The request ID for tracking
     * @param model The model name
     * @return A {@link ServerSentEvent} with error information
     */
    public ServerSentEvent<String> createErrorSseEvent(
            Throwable error, String requestId, String model) {
        ChatCompletionsChunk errorChunk =
                streamingAdapter.createErrorChunk(error, requestId, model);
        return chunkToSseEvent(errorChunk);
    }
}
