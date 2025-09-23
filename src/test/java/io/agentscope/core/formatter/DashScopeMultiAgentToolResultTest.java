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
package io.agentscope.core.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Test DashScopeMultiAgentFormatter's handling of ToolResultBlock.
 */
class DashScopeMultiAgentToolResultTest {

    @Test
    void testToolResultBlockFormatting() {
        // Create a tool result message
        Msg toolResultMsg =
                Msg.builder()
                        .name("WeatherAgent")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("call_weather_123")
                                        .name("get_weather")
                                        .output(
                                                TextBlock.builder()
                                                        .text("Beijing: 15°C, cloudy")
                                                        .build())
                                        .build())
                        .build();

        // Format with DashScopeMultiAgent formatter
        DashScopeMultiAgentFormatter formatter = new DashScopeMultiAgentFormatter();
        List<Map<String, Object>> formatted =
                formatter
                        .format(List.of(toolResultMsg), FormatterOptions.builder().build())
                        .block()
                        .asMaps();

        // Verify output - tool messages should be formatted as tool role
        assertEquals(1, formatted.size());
        Map<String, Object> formattedMsg = formatted.get(0);

        assertEquals("tool", formattedMsg.get("role"));
        assertEquals("Beijing: 15°C, cloudy", formattedMsg.get("content"));
        assertEquals("call_weather_123", formattedMsg.get("tool_call_id"));

        System.out.println("Formatted tool result message:");
        System.out.println(formattedMsg);
    }

    @Test
    void testMultiAgentConversationWithToolResult() {
        // Create user message
        Msg userMsg =
                Msg.builder()
                        .name("Alice")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("What's the weather like in Beijing?")
                                        .build())
                        .build();

        // Create assistant message with tool call
        Msg assistantToolCallMsg =
                Msg.builder()
                        .name("WeatherBot")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("call_weather_456")
                                        .name("get_weather")
                                        .input(Map.of("location", "Beijing"))
                                        .build())
                        .build();

        // Create tool result message
        Msg toolResultMsg =
                Msg.builder()
                        .name("WeatherAgent")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("call_weather_456")
                                        .name("get_weather")
                                        .output(
                                                TextBlock.builder()
                                                        .text(
                                                                "Beijing: 15°C, cloudy with light"
                                                                        + " rain")
                                                        .build())
                                        .build())
                        .build();

        // Create final assistant response
        Msg finalResponseMsg =
                Msg.builder()
                        .name("WeatherBot")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "The current weather in Beijing is 15°C and cloudy"
                                                        + " with light rain.")
                                        .build())
                        .build();

        // Format the conversation
        DashScopeMultiAgentFormatter formatter = new DashScopeMultiAgentFormatter();
        List<Map<String, Object>> formatted =
                formatter
                        .format(
                                List.of(
                                        userMsg,
                                        assistantToolCallMsg,
                                        toolResultMsg,
                                        finalResponseMsg),
                                FormatterOptions.builder().build())
                        .block()
                        .asMaps();

        // Should have:
        // 1. History message (user + final assistant response)
        // 2. Assistant tool call message
        // 3. Tool result message
        assertEquals(3, formatted.size());

        // First message should be the collapsed history
        Map<String, Object> historyMsg = formatted.get(0);
        assertEquals("user", historyMsg.get("role"));
        Object content = historyMsg.get("content");
        assertTrue(content instanceof List);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) content;
        String historyText = (String) contentBlocks.get(0).get("text");
        assertTrue(historyText.contains("<history>"));
        assertTrue(historyText.contains("Alice: What's the weather like in Beijing?"));
        assertTrue(
                historyText.contains(
                        "WeatherBot: The current weather in Beijing is 15°C and cloudy with light"
                                + " rain."));
        assertTrue(historyText.contains("</history>"));

        // Second message should be the assistant tool call
        Map<String, Object> toolCallMsg = formatted.get(1);
        assertEquals("assistant", toolCallMsg.get("role"));
        assertTrue(toolCallMsg.containsKey("tool_calls"));

        // Third message should be the tool result
        Map<String, Object> toolResult = formatted.get(2);
        assertEquals("tool", toolResult.get("role"));
        assertEquals("Beijing: 15°C, cloudy with light rain", toolResult.get("content"));
        assertEquals("call_weather_456", toolResult.get("tool_call_id"));

        System.out.println("Formatted multi-agent conversation with tools:");
        for (int i = 0; i < formatted.size(); i++) {
            System.out.println(i + ": " + formatted.get(i));
        }
    }
}
