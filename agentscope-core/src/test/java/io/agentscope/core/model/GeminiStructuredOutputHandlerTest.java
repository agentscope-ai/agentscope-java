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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.StructuredOutputCapableAgent;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("GeminiStructuredOutputHandler Unit Tests")
class GeminiStructuredOutputHandlerTest {

    private final GeminiStructuredOutputHandler handler = new GeminiStructuredOutputHandler();

    @Test
    @DisplayName("Should wrap tool input and set structured output metadata")
    void testEnsureStructuredOutputMetadata() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name(StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME)
                        .input(Map.of("location", "San Francisco", "temperature", "20"))
                        .build();
        ChatResponse response = ChatResponse.builder().content(List.of(toolUse)).build();

        ChatResponse result = handler.ensureStructuredOutputMetadata(response);

        assertNotNull(result.getMetadata());
        assertTrue(result.getMetadata().containsKey(MessageMetadataKeys.STRUCTURED_OUTPUT));
        @SuppressWarnings("unchecked")
        Map<String, Object> structured =
                (Map<String, Object>)
                        result.getMetadata().get(MessageMetadataKeys.STRUCTURED_OUTPUT);
        assertEquals("San Francisco", structured.get("location"));

        ToolUseBlock fixedToolUse = (ToolUseBlock) result.getContent().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> wrapped = (Map<String, Object>) fixedToolUse.getInput().get("response");
        assertNotNull(wrapped);
        assertEquals("20", wrapped.get("temperature"));
    }

    @Test
    @DisplayName("Should convert JSON text response into generate_response tool call")
    void testFixStructuredOutputResponseFromJsonText() {
        ChatResponse response =
                ChatResponse.builder()
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "{\"location\":\"San Francisco\","
                                                                + "\"temperature\":\"20\","
                                                                + "\"condition\":\"Cloudy\"}")
                                                .build()))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new ToolChoice.Specific(
                                        StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME))
                        .build();

        ChatResponse fixed =
                handler.fixStructuredOutputResponse(response, options, List.of(tool()));

        assertEquals(1, fixed.getContent().size());
        ToolUseBlock toolUse = (ToolUseBlock) fixed.getContent().get(0);
        assertEquals(StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME, toolUse.getName());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) toolUse.getInput().get("response");
        assertNotNull(payload);
        assertEquals("San Francisco", payload.get("location"));
        assertEquals("Cloudy", payload.get("condition"));
    }

    private static ToolSchema tool() {
        Map<String, Object> responseSchema =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "location", Map.of("type", "string"),
                                "temperature", Map.of("type", "string"),
                                "condition", Map.of("type", "string")));

        Map<String, Object> parameters =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("response", responseSchema),
                        "required",
                        List.of("response"));

        return ToolSchema.builder()
                .name(StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME)
                .description("Structured output tool")
                .parameters(parameters)
                .build();
    }
}
