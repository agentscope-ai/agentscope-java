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
package io.agentscope.core.agui.encoder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.agui.event.AguiEvent;

/**
 * Encoder for AG-UI events to Server-Sent Events (SSE) format.
 *
 * <p>This encoder serializes AG-UI events to the SSE wire format:
 * <pre>
 * data: {"type":"EVENT_TYPE",...}\n\n
 * </pre>
 *
 * <p>The encoder is thread-safe and can be shared across multiple requests.
 */
public class AguiEventEncoder {

    private final ObjectMapper objectMapper;

    /**
     * Creates a new AguiEventEncoder with default ObjectMapper settings.
     */
    public AguiEventEncoder() {
        this.objectMapper = new ObjectMapper();
        // Disable pretty printing for compact wire format
        this.objectMapper.disable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Creates a new AguiEventEncoder with a custom ObjectMapper.
     *
     * @param objectMapper The ObjectMapper to use for serialization
     */
    public AguiEventEncoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Encode an AG-UI event to SSE format.
     *
     * <p>The output format is:
     * <pre>
     * data: {"type":"EVENT_TYPE",...}\n\n
     * </pre>
     *
     * @param event The event to encode
     * @return The SSE-formatted string
     * @throws AguiEncodingException if the event cannot be serialized
     */
    public String encode(AguiEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            return "data: " + json + "\n\n";
        } catch (JsonProcessingException e) {
            throw new AguiEncodingException("Failed to encode AG-UI event", e);
        }
    }

    /**
     * Encode an AG-UI event to JSON string only (without SSE wrapper).
     *
     * <p>Note: The returned string has a leading space to ensure compatibility
     * with AG-UI client libraries that expect SSE format "data: {...}" (with space).
     * Spring WebFlux generates "data:" prefix, so the leading space produces
     * the expected "data: {...}" format.
     *
     * @param event The event to encode
     * @return The JSON string with leading space for SSE compatibility
     * @throws AguiEncodingException if the event cannot be serialized
     */
    public String encodeToJson(AguiEvent event) {
        try {
            // Add leading space for SSE compatibility: "data:" + " {...}" = "data: {...}"
            return " " + objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new AguiEncodingException("Failed to encode AG-UI event to JSON", e);
        }
    }

    /**
     * Encode a comment in SSE format.
     *
     * <p>Comments can be used as keep-alive signals:
     * <pre>
     * : comment text\n\n
     * </pre>
     *
     * @param comment The comment text
     * @return The SSE-formatted comment
     */
    public String encodeComment(String comment) {
        return ": " + comment + "\n\n";
    }

    /**
     * Generate a keep-alive comment.
     *
     * @return An SSE keep-alive comment
     */
    public String keepAlive() {
        return ": keep-alive\n\n";
    }

    /**
     * Exception thrown when event encoding fails.
     */
    public static class AguiEncodingException extends RuntimeException {

        /**
         * Creates a new encoding exception.
         *
         * @param message The error message
         * @param cause The underlying cause
         */
        public AguiEncodingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
