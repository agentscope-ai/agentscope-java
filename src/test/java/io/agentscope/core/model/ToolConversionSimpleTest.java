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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Simple test for tool conversion logic validation.
 */
class ToolConversionSimpleTest {

    @Test
    void testFormattedMessageToolCallStructure() {
        // Test that FormattedMessage correctly handles tool_calls
        Map<String, Object> toolCallData = new HashMap<>();
        toolCallData.put("id", "call_123");
        toolCallData.put("type", "function");

        Map<String, Object> functionData = new HashMap<>();
        functionData.put("name", "get_weather");
        functionData.put("arguments", "{\"location\":\"New York\"}");
        toolCallData.put("function", functionData);

        Map<String, Object> messageData = new HashMap<>();
        messageData.put("role", "assistant");
        messageData.put("content", null);
        messageData.put("tool_calls", List.of(toolCallData));

        FormattedMessage message = new FormattedMessage(messageData);

        // Verify the message structure
        assertEquals("assistant", message.getRole());
        assertTrue(message.hasToolCalls());

        List<Map<String, Object>> toolCalls = message.getToolCalls();
        assertEquals(1, toolCalls.size());

        Map<String, Object> firstCall = toolCalls.get(0);
        assertEquals("call_123", firstCall.get("id"));
        assertEquals("function", firstCall.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) firstCall.get("function");
        assertEquals("get_weather", function.get("name"));
        assertEquals("{\"location\":\"New York\"}", function.get("arguments"));
    }

    @Test
    void testToolResponseMessage() {
        // Test that tool response messages are handled correctly
        Map<String, Object> messageData = new HashMap<>();
        messageData.put("role", "tool");
        messageData.put("content", "Weather: 22°C, sunny");
        messageData.put("tool_call_id", "call_123");

        FormattedMessage message = new FormattedMessage(messageData);

        assertEquals("tool", message.getRole());
        assertEquals("Weather: 22°C, sunny", message.getContentAsString());
        assertEquals("call_123", message.getToolCallId());
        assertFalse(message.hasToolCalls()); // tool response shouldn't have tool_calls
    }

    @Test
    void testDirectConversionFromFormatterOutput() {
        // Simulate what a formatter would output for a tool conversation
        Map<String, Object> assistantMessage = new HashMap<>();
        assistantMessage.put("role", "assistant");
        assistantMessage.put("content", null);

        Map<String, Object> toolCall = new HashMap<>();
        toolCall.put("id", "call_abc123");
        toolCall.put("type", "function");

        Map<String, Object> function = new HashMap<>();
        function.put("name", "calculate");
        function.put("arguments", "{\"operation\":\"add\",\"a\":5,\"b\":3}");
        toolCall.put("function", function);

        assistantMessage.put("tool_calls", List.of(toolCall));

        // This is exactly the format that would come from a formatter
        List<Map<String, Object>> formatterOutput = List.of(assistantMessage);
        FormattedMessageList messageList = new FormattedMessageList(formatterOutput);

        // Verify that Model can use this directly
        assertEquals(1, messageList.size());
        FormattedMessage msg = messageList.get(0);

        assertEquals("assistant", msg.getRole());
        assertTrue(msg.hasToolCalls());

        // Model can access raw maps for DashScope SDK
        Map<String, Object> rawMap = msg.asMap();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCallsFromMap =
                (List<Map<String, Object>>) rawMap.get("tool_calls");
        assertNotNull(toolCallsFromMap);
        assertEquals(1, toolCallsFromMap.size());
    }
}
