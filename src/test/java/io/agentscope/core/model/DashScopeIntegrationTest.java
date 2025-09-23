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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Integration test for DashScope tool conversion logic.
 */
class DashScopeIntegrationTest {

    @Test
    void testToolCallConversionToDashScope() throws Exception {
        // Create a DashScopeChatModel for testing conversion logic
        DashScopeChatModel model =
                DashScopeChatModel.builder().apiKey("test-key").modelName("qwen-plus").build();

        // Create FormattedMessageList with tool calls
        Map<String, Object> toolCallData = new HashMap<>();
        toolCallData.put("id", "call_123");
        toolCallData.put("type", "function");

        Map<String, Object> functionData = new HashMap<>();
        functionData.put("name", "get_weather");
        functionData.put("arguments", "{\"location\":\"Shanghai\"}");
        toolCallData.put("function", functionData);

        Map<String, Object> assistantMessageData = new HashMap<>();
        assistantMessageData.put("role", "assistant");
        assistantMessageData.put("content", null);
        assistantMessageData.put("tool_calls", List.of(toolCallData));

        Map<String, Object> toolResponseData = new HashMap<>();
        toolResponseData.put("role", "tool");
        toolResponseData.put("content", "Shanghai: 25°C, sunny");
        toolResponseData.put("tool_call_id", "call_123");

        List<Map<String, Object>> rawMessages = List.of(assistantMessageData, toolResponseData);

        // Use reflection to access the private method for testing
        Method convertMethod =
                DashScopeChatModel.class.getDeclaredMethod(
                        "convertToDashScopeMessages", List.class);
        convertMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Message> dashScopeMessages = (List<Message>) convertMethod.invoke(model, rawMessages);

        // Verify conversion
        assertEquals(2, dashScopeMessages.size());

        // Check assistant message with tool calls
        Message assistantMsg = dashScopeMessages.get(0);
        assertEquals("assistant", assistantMsg.getRole());
        // Content should be empty or null for tool call messages
        assertTrue(assistantMsg.getContent() == null || assistantMsg.getContent().isEmpty());

        List<ToolCallBase> toolCalls = assistantMsg.getToolCalls();
        assertNotNull(toolCalls);
        assertEquals(1, toolCalls.size());

        ToolCallBase toolCall = toolCalls.get(0);
        assertTrue(toolCall instanceof ToolCallFunction);
        assertEquals("call_123", toolCall.getId());

        ToolCallFunction tcf = (ToolCallFunction) toolCall;
        ToolCallFunction.CallFunction function = tcf.getFunction();
        assertNotNull(function);
        assertEquals("get_weather", function.getName());
        assertEquals("{\"location\":\"Shanghai\"}", function.getArguments());

        // Check tool response message
        Message toolMsg = dashScopeMessages.get(1);
        assertEquals("tool", toolMsg.getRole());
        assertEquals("Shanghai: 25°C, sunny", toolMsg.getContent());
        assertEquals("call_123", toolMsg.getToolCallId());
    }

    @Test
    void testConvertToDashScopeToolCallMethod() throws Exception {
        // Test the specific conversion method
        DashScopeChatModel model =
                DashScopeChatModel.builder().apiKey("test-key").modelName("qwen-plus").build();

        Map<String, Object> toolCallData = new HashMap<>();
        toolCallData.put("id", "call_456");
        toolCallData.put("type", "function");

        Map<String, Object> functionData = new HashMap<>();
        functionData.put("name", "calculate");
        functionData.put("arguments", "{\"a\":10,\"b\":5,\"op\":\"multiply\"}");
        toolCallData.put("function", functionData);

        // Use reflection to test the private conversion method
        Method convertToolCallMethod =
                DashScopeChatModel.class.getDeclaredMethod("convertToDashScopeToolCall", Map.class);
        convertToolCallMethod.setAccessible(true);

        ToolCallBase result = (ToolCallBase) convertToolCallMethod.invoke(model, toolCallData);

        assertNotNull(result);
        assertTrue(result instanceof ToolCallFunction);
        assertEquals("call_456", result.getId());

        ToolCallFunction tcf = (ToolCallFunction) result;
        ToolCallFunction.CallFunction function = tcf.getFunction();
        assertNotNull(function);
        assertEquals("calculate", function.getName());
        assertEquals("{\"a\":10,\"b\":5,\"op\":\"multiply\"}", function.getArguments());
    }

    @Test
    void testFormattedMessageListDirectUsage() {
        // Test that Model can use FormattedMessageList directly
        Map<String, Object> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", "What's 2 + 2?");

        Map<String, Object> assistantToolCall = new HashMap<>();
        assistantToolCall.put("role", "assistant");
        assistantToolCall.put("content", null);

        Map<String, Object> toolCall = new HashMap<>();
        toolCall.put("id", "call_calc");
        toolCall.put("type", "function");

        Map<String, Object> function = new HashMap<>();
        function.put("name", "calculator");
        function.put("arguments", "{\"operation\":\"add\",\"a\":2,\"b\":2}");
        toolCall.put("function", function);

        assistantToolCall.put("tool_calls", List.of(toolCall));

        Map<String, Object> toolResponse = new HashMap<>();
        toolResponse.put("role", "tool");
        toolResponse.put("content", "4");
        toolResponse.put("tool_call_id", "call_calc");

        List<Map<String, Object>> conversationData =
                List.of(userMessage, assistantToolCall, toolResponse);
        FormattedMessageList conversation = new FormattedMessageList(conversationData);

        // Verify the conversation structure
        assertEquals(3, conversation.size());

        // User message
        FormattedMessage user = conversation.get(0);
        assertEquals("user", user.getRole());
        assertEquals("What's 2 + 2?", user.getContentAsString());

        // Assistant tool call
        FormattedMessage assistantMsg = conversation.get(1);
        assertEquals("assistant", assistantMsg.getRole());
        assertTrue(assistantMsg.hasToolCalls());

        // Tool response
        FormattedMessage toolMsg = conversation.get(2);
        assertEquals("tool", toolMsg.getRole());
        assertEquals("4", toolMsg.getContentAsString());
        assertEquals("call_calc", toolMsg.getToolCallId());

        // Model can get raw maps for DashScope SDK
        List<Map<String, Object>> rawMaps = conversation.asMaps();
        assertEquals(3, rawMaps.size());

        // Verify tool call structure in raw maps
        Map<String, Object> assistantRaw = rawMaps.get(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls =
                (List<Map<String, Object>>) assistantRaw.get("tool_calls");
        assertNotNull(toolCalls);
        assertEquals(1, toolCalls.size());
    }
}
