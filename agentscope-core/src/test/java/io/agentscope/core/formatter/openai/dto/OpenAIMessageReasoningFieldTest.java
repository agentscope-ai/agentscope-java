/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.formatter.openai.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for OpenAIMessage reasoning field compatibility.
 *
 * <p>Verifies that both {@code reasoning_content} (commercial APIs) and {@code reasoning}
 * (vLLM) are correctly deserialized into {@code reasoningContent} via {@code @JsonAlias}.
 */
@Tag("unit")
@DisplayName("OpenAIMessage Reasoning Field Compatibility")
class OpenAIMessageReasoningFieldTest {

    private JsonCodec jsonCodec;

    @BeforeEach
    void setUp() {
        jsonCodec = JsonUtils.getJsonCodec();
    }

    @Test
    @DisplayName("Should deserialize reasoning_content from commercial APIs")
    void testDeserializeReasoningContent() {
        String json =
                """
                {
                  "role": "assistant",
                  "reasoning_content": "Let me analyze this step by step...",
                  "content": "The answer is 42."
                }
                """;

        OpenAIMessage message = jsonCodec.fromJson(json, OpenAIMessage.class);

        assertEquals("Let me analyze this step by step...", message.getReasoningContent());
        assertEquals("The answer is 42.", message.getContentAsString());
    }

    @Test
    @DisplayName("Should deserialize reasoning from vLLM deployments via @JsonAlias")
    void testDeserializeReasoning() {
        String json =
                """
                {
                  "role": "assistant",
                  "reasoning": "First, I need to consider the problem...",
                  "content": "The result is 7."
                }
                """;

        OpenAIMessage message = jsonCodec.fromJson(json, OpenAIMessage.class);

        assertEquals("First, I need to consider the problem...", message.getReasoningContent());
        assertEquals("The result is 7.", message.getContentAsString());
    }

    @Test
    @DisplayName("Should handle null reasoning fields gracefully")
    void testDeserializeWithoutReasoning() {
        String json =
                """
                {
                  "role": "assistant",
                  "content": "Simple response without thinking."
                }
                """;

        OpenAIMessage message = jsonCodec.fromJson(json, OpenAIMessage.class);

        assertNull(message.getReasoningContent());
    }

    @Test
    @DisplayName("Should deserialize reasoning from vLLM streaming delta")
    void testDeserializeVllmStreamingDelta() {
        String json =
                """
                {
                  "role": "assistant",
                  "reasoning": "Thinking chunk...",
                  "content": null
                }
                """;

        OpenAIMessage delta = jsonCodec.fromJson(json, OpenAIMessage.class);

        assertEquals("Thinking chunk...", delta.getReasoningContent());
        assertNull(delta.getContentAsString());
    }

    @Test
    @DisplayName("Should deserialize full vLLM-style response")
    void testDeserializeFullVllmResponse() {
        String json =
                """
                {
                  "id": "chatcmpl-vllm-123",
                  "object": "chat.completion",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "reasoning": "Comparing decimals...",
                        "content": "9.8 is greater as a decimal number."
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
                """;

        OpenAIResponse response = jsonCodec.fromJson(json, OpenAIResponse.class);
        OpenAIMessage message = response.getFirstChoice().getMessage();

        assertEquals("Comparing decimals...", message.getReasoningContent());
        assertTrue(message.getContentAsString().contains("9.8 is greater"));
    }

    @Test
    @DisplayName("Should deserialize full vLLM streaming chunk")
    void testDeserializeVllmStreamingChunk() {
        String json =
                """
                {
                  "id": "chatcmpl-vllm-456",
                  "object": "chat.completion.chunk",
                  "choices": [
                    {
                      "index": 0,
                      "delta": {
                        "role": "assistant",
                        "reasoning": "Step 1: parse the input..."
                      },
                      "finish_reason": null
                    }
                  ]
                }
                """;

        OpenAIResponse response = jsonCodec.fromJson(json, OpenAIResponse.class);
        OpenAIMessage delta = response.getFirstChoice().getDelta();

        assertEquals("Step 1: parse the input...", delta.getReasoningContent());
    }
}
