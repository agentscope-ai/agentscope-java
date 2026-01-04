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
package io.agentscope.core.agui.encoder;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.agentscope.core.agui.AguiException;
import io.agentscope.core.agui.AguiObjectMapper;
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
 * It uses a shared ObjectMapper instance for efficient serialization.
 */
public class AguiEventEncoder {

    /**
     * Creates a new AguiEventEncoder.
     */
    public AguiEventEncoder() {
        // Uses shared ObjectMapper from AguiObjectMapper
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
     * @throws AguiException.EncodingException if the event cannot be serialized
     */
    public String encode(AguiEvent event) {
        try {
            String json = AguiObjectMapper.get().writeValueAsString(event);
            return "data: " + json + "\n\n";
        } catch (JsonProcessingException e) {
            throw new AguiException.EncodingException("Failed to encode AG-UI event", e);
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
     * @throws AguiException.EncodingException if the event cannot be serialized
     */
    public String encodeToJson(AguiEvent event) {
        try {
            // Add leading space for SSE compatibility: "data:" + " {...}" = "data: {...}"
            return " " + AguiObjectMapper.get().writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new AguiException.EncodingException("Failed to encode AG-UI event to JSON", e);
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
}
