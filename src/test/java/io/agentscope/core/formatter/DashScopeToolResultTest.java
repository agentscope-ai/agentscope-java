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

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Test DashScope formatter's handling of ToolResultBlock.
 */
class DashScopeToolResultTest {

    @Test
    void testToolResultBlockFormatting() {
        // Create a tool result message
        Msg toolResultMsg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("call_123")
                                        .name("get_weather")
                                        .output(
                                                TextBlock.builder()
                                                        .text("The weather is sunny, 25°C")
                                                        .build())
                                        .build())
                        .build();

        // Format with DashScope formatter
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();
        List<Map<String, Object>> formatted =
                formatter
                        .format(List.of(toolResultMsg), FormatterOptions.builder().build())
                        .block()
                        .asMaps();

        // Verify output
        assertEquals(1, formatted.size());
        Map<String, Object> formattedMsg = formatted.get(0);

        assertEquals("tool", formattedMsg.get("role"));
        assertEquals("The weather is sunny, 25°C", formattedMsg.get("content"));
        assertEquals("call_123", formattedMsg.get("tool_call_id"));

        System.out.println("Formatted tool result message:");
        System.out.println(formattedMsg);
    }

    @Test
    void testCompleteToolConversationFormatting() {
        // Create user message
        Msg userMsg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What's the weather like?").build())
                        .build();

        // Create assistant message with tool call (this would come from model response)
        Map<String, Object> toolCall =
                Map.of(
                        "id", "call_123",
                        "type", "function",
                        "function",
                                Map.of(
                                        "name", "get_weather",
                                        "arguments", "{\"location\":\"Shanghai\"}"));

        Msg assistantMsg =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("").build())
                        .build();

        // Create tool result message
        Msg toolResultMsg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("call_123")
                                        .name("get_weather")
                                        .output(
                                                TextBlock.builder()
                                                        .text("Shanghai: 22°C, sunny")
                                                        .build())
                                        .build())
                        .build();

        // Format the conversation
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();
        List<Map<String, Object>> formatted =
                formatter
                        .format(
                                List.of(userMsg, assistantMsg, toolResultMsg),
                                FormatterOptions.builder().build())
                        .block()
                        .asMaps();

        // Verify the formatted conversation
        assertEquals(3, formatted.size());

        // Check user message
        Map<String, Object> userFormatted = formatted.get(0);
        assertEquals("user", userFormatted.get("role"));

        // Check assistant message
        Map<String, Object> assistantFormatted = formatted.get(1);
        assertEquals("assistant", assistantFormatted.get("role"));

        // Check tool result message
        Map<String, Object> toolFormatted = formatted.get(2);
        assertEquals("tool", toolFormatted.get("role"));
        assertEquals("Shanghai: 22°C, sunny", toolFormatted.get("content"));
        assertEquals("call_123", toolFormatted.get("tool_call_id"));

        System.out.println("Formatted conversation:");
        for (int i = 0; i < formatted.size(); i++) {
            System.out.println(i + ": " + formatted.get(i));
        }
    }
}
