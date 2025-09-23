/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Test tool conversion logic in DashScope model implementation.
 */
class DashScopeToolConversionTest {

    @Test
    void testAssistantMessageWithToolCallsConversion() {
        // Create a FormattedMessage representing an assistant message with tool calls
        FormattedMessage assistantMessage =
                FormattedMessage.builder()
                        .role("assistant")
                        .content(null) // Tool call messages often have null content
                        .toolCalls(
                                List.of(
                                        Map.of(
                                                "id", "call_123",
                                                "type", "function",
                                                "function",
                                                        Map.of(
                                                                "name",
                                                                "get_weather",
                                                                "arguments",
                                                                "{\"location\":\"New"
                                                                        + " York\"}"))))
                        .build();

        FormattedMessageList messageList = FormattedMessageList.of(assistantMessage);

        // Verify the structure is correct
        assertEquals(1, messageList.size());
        assertEquals("assistant", messageList.get(0).getRole());
        assertTrue(messageList.get(0).hasToolCalls());

        List<Map<String, Object>> toolCalls = messageList.get(0).getToolCalls();
        assertEquals(1, toolCalls.size());

        Map<String, Object> toolCall = toolCalls.get(0);
        assertEquals("call_123", toolCall.get("id"));
        assertEquals("function", toolCall.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
        assertEquals("get_weather", function.get("name"));
        assertEquals("{\"location\":\"New York\"}", function.get("arguments"));
    }

    @Test
    void testToolResponseMessageConversion() {
        // Create a FormattedMessage representing a tool response
        FormattedMessage toolResponseMessage =
                FormattedMessage.builder()
                        .role("tool")
                        .content("The weather in New York is 22°C and sunny.")
                        .toolCallId("call_123")
                        .build();

        FormattedMessageList messageList = FormattedMessageList.of(toolResponseMessage);

        // Verify the structure is correct
        assertEquals(1, messageList.size());
        assertEquals("tool", messageList.get(0).getRole());
        assertEquals(
                "The weather in New York is 22°C and sunny.",
                messageList.get(0).getContentAsString());
        assertEquals("call_123", messageList.get(0).getToolCallId());
    }

    @Test
    void testCompleteToolCallFlow() {
        // Create a complete tool call conversation flow
        FormattedMessage userMessage =
                FormattedMessage.builder()
                        .role("user")
                        .content("What's the weather like in New York?")
                        .build();

        FormattedMessage assistantToolCall =
                FormattedMessage.builder()
                        .role("assistant")
                        .content(null)
                        .toolCalls(
                                List.of(
                                        Map.of(
                                                "id", "call_abc123",
                                                "type", "function",
                                                "function",
                                                        Map.of(
                                                                "name",
                                                                "get_weather",
                                                                "arguments",
                                                                "{\"location\":\"New"
                                                                        + " York\"}"))))
                        .build();

        FormattedMessage toolResponse =
                FormattedMessage.builder()
                        .role("tool")
                        .content("The current weather in New York is 22°C and sunny.")
                        .toolCallId("call_abc123")
                        .build();

        FormattedMessage assistantFinal =
                FormattedMessage.builder()
                        .role("assistant")
                        .content(
                                "Based on the weather data, it's currently 22°C and sunny in New"
                                        + " York. Great weather for outdoor activities!")
                        .build();

        FormattedMessageList conversation =
                FormattedMessageList.of(
                        userMessage, assistantToolCall, toolResponse, assistantFinal);

        // Verify the conversation structure
        assertEquals(4, conversation.size());

        // Verify user message
        assertEquals("user", conversation.get(0).getRole());
        assertEquals(
                "What's the weather like in New York?", conversation.get(0).getContentAsString());

        // Verify assistant tool call
        assertEquals("assistant", conversation.get(1).getRole());
        assertTrue(conversation.get(1).hasToolCalls());

        // Verify tool response
        assertEquals("tool", conversation.get(2).getRole());
        assertEquals("call_abc123", conversation.get(2).getToolCallId());

        // Verify final assistant response
        assertEquals("assistant", conversation.get(3).getRole());
        assertFalse(conversation.get(3).hasToolCalls());
        assertNotNull(conversation.get(3).getContentAsString());
    }
}
