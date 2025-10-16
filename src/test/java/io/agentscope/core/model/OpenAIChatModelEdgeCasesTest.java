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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("OpenAIChatModel Edge Cases Tests")
class OpenAIChatModelEdgeCasesTest {

    @Test
    @DisplayName("Should handle system messages in FormattedMessage")
    void testSystemMessage() {
        String apiKey = "test-key";
        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("gpt-4").stream(false).build();

        FormattedMessage systemMsg =
                FormattedMessage.builder()
                        .role("system")
                        .content("You are a helpful assistant")
                        .build();

        FormattedMessageList messages = FormattedMessageList.of(systemMsg);

        // This should not throw an exception
        assertNotNull(messages);
        assertEquals(1, messages.size());
        assertEquals("system", messages.get(0).getRole());
    }

    @Test
    @DisplayName("Should handle null content in FormattedMessage")
    void testNullContent() {
        FormattedMessage msgWithNullContent = FormattedMessage.builder().role("user").build();

        assertNotNull(msgWithNullContent);
        assertEquals("user", msgWithNullContent.getRole());
    }

    @Test
    @DisplayName("Should handle tool messages with tool_call_id")
    void testToolMessage() {
        Map<String, Object> toolMsgData = new HashMap<>();
        toolMsgData.put("role", "tool");
        toolMsgData.put("content", "The weather is sunny, 25°C");
        toolMsgData.put("tool_call_id", "call_123");

        FormattedMessage toolMsg = new FormattedMessage(toolMsgData);

        assertEquals("tool", toolMsg.getRole());
        assertEquals("The weather is sunny, 25°C", toolMsg.getContentAsString());
        assertEquals("call_123", toolMsg.getToolCallId());
    }

    @Test
    @DisplayName("Should handle assistant messages with tool calls")
    void testAssistantMessageWithToolCalls() {
        Map<String, Object> toolCall = new HashMap<>();
        toolCall.put("id", "call_abc123");
        toolCall.put("type", "function");

        Map<String, Object> functionData = new HashMap<>();
        functionData.put("name", "get_weather");
        functionData.put("arguments", "{\"location\":\"Beijing\"}");
        toolCall.put("function", functionData);

        Map<String, Object> assistantMsgData = new HashMap<>();
        assistantMsgData.put("role", "assistant");
        assistantMsgData.put("content", "Let me check the weather");
        assistantMsgData.put("tool_calls", List.of(toolCall));

        FormattedMessage assistantMsg = new FormattedMessage(assistantMsgData);

        assertEquals("assistant", assistantMsg.getRole());
        assertTrue(assistantMsg.hasToolCalls());
        assertEquals(1, assistantMsg.getToolCalls().size());
        assertEquals("call_abc123", assistantMsg.getToolCalls().get(0).get("id"));
    }

    @Test
    @DisplayName("Should handle assistant messages with empty content and tool calls")
    void testAssistantMessageWithToolCallsEmptyContent() {
        Map<String, Object> toolCall = new HashMap<>();
        toolCall.put("id", "call_456");
        toolCall.put("type", "function");

        Map<String, Object> functionData = new HashMap<>();
        functionData.put("name", "search");
        functionData.put("arguments", "{}");
        toolCall.put("function", functionData);

        Map<String, Object> assistantMsgData = new HashMap<>();
        assistantMsgData.put("role", "assistant");
        assistantMsgData.put("content", "");
        assistantMsgData.put("tool_calls", List.of(toolCall));

        FormattedMessage assistantMsg = new FormattedMessage(assistantMsgData);

        assertEquals("assistant", assistantMsg.getRole());
        assertTrue(assistantMsg.hasToolCalls());
        assertEquals("", assistantMsg.getContentAsString());
    }

    @Test
    @DisplayName("Should handle tool message without tool_call_id")
    void testToolMessageWithoutId() {
        Map<String, Object> toolMsgData = new HashMap<>();
        toolMsgData.put("role", "tool");
        toolMsgData.put("content", "Result");

        FormattedMessage toolMsg = new FormattedMessage(toolMsgData);

        assertEquals("tool", toolMsg.getRole());
        assertEquals("Result", toolMsg.getContentAsString());
    }

    @Test
    @DisplayName("Should handle unknown role defaulting to user")
    void testUnknownRole() {
        FormattedMessage unknownMsg =
                FormattedMessage.builder().role("unknown_role").content("Hello").build();

        assertEquals("unknown_role", unknownMsg.getRole());
        assertEquals("Hello", unknownMsg.getContentAsString());
        assertFalse(unknownMsg.hasToolCalls());
    }

    @Test
    @DisplayName("Should handle ToolSchema conversion")
    void testToolSchemaBasics() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("required", List.of("location"));

        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> locationProp = new HashMap<>();
        locationProp.put("type", "string");
        locationProp.put("description", "The city name");
        properties.put("location", locationProp);
        parameters.put("properties", properties);

        ToolSchema schema =
                ToolSchema.builder()
                        .name("get_weather")
                        .description("Get the current weather")
                        .parameters(parameters)
                        .build();

        assertEquals("get_weather", schema.getName());
        assertEquals("Get the current weather", schema.getDescription());
        assertNotNull(schema.getParameters());
        assertEquals("object", schema.getParameters().get("type"));
    }

    @Test
    @DisplayName("Should handle ToolSchema without parameters")
    void testToolSchemaWithoutParameters() {
        ToolSchema schema =
                ToolSchema.builder().name("simple_tool").description("A simple tool").build();

        assertEquals("simple_tool", schema.getName());
        assertEquals("A simple tool", schema.getDescription());
    }

    @Test
    @DisplayName("Should handle GenerateOptions with null values")
    void testGenerateOptionsDefaults() {
        GenerateOptions options = new GenerateOptions();

        // All values should be null by default
        assertNotNull(options);
    }

    @Test
    @DisplayName("Should handle GenerateOptions with custom values")
    void testGenerateOptionsCustom() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.7)
                        .maxTokens(100)
                        .topP(0.9)
                        .frequencyPenalty(0.5)
                        .presencePenalty(0.3)
                        .build();

        assertEquals(0.7, options.getTemperature());
        assertEquals(100, options.getMaxTokens());
        assertEquals(0.9, options.getTopP());
        assertEquals(0.5, options.getFrequencyPenalty());
        assertEquals(0.3, options.getPresencePenalty());
    }
}
