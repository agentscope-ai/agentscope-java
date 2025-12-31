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
package io.agentscope.core.formatter.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAITool;
import io.agentscope.core.formatter.openai.dto.OpenAIToolFunction;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OpenAIMultiAgentFormatter.
 *
 * <p>These tests verify the multi-agent formatter's ability to:
 * <ul>
 *   <li>Group messages correctly (system, tool sequences, agent conversations)</li>
 *   <li>Merge multi-agent conversations into history</li>
 *   <li>Format tool sequences separately</li>
 *   <li>Apply options and tools</li>
 *   <li>Build complete requests</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("OpenAIMultiAgentFormatter Unit Tests")
class OpenAIMultiAgentFormatterTest {

    private OpenAIMultiAgentFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new OpenAIMultiAgentFormatter();
    }

    @Test
    @DisplayName("Should format system message separately")
    void testFormatSystemMessage() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("You are a helpful assistant")
                                                        .build()))
                                .build());

        List<OpenAIMessage> result = formatter.format(messages);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("system", result.get(0).getRole());
        assertEquals("You are a helpful assistant", result.get(0).getContentAsString());
    }

    @Test
    @DisplayName("Should merge agent conversation messages into history")
    void testMergeAgentConversation() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("Alice")
                                .content(List.of(TextBlock.builder().text("Hello, Bob").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("Bob")
                                .content(List.of(TextBlock.builder().text("Hi Alice!").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("Alice")
                                .content(List.of(TextBlock.builder().text("How are you?").build()))
                                .build());

        List<OpenAIMessage> result = formatter.format(messages);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("user", result.get(0).getRole());
        String content = result.get(0).getContentAsString();
        assertTrue(content.contains("Hello, Bob"));
        assertTrue(content.contains("Hi Alice!"));
        assertTrue(content.contains("How are you?"));
        assertTrue(content.contains("<history>"));
        assertTrue(content.contains("</history>"));
    }

    @Test
    @DisplayName("Should format tool sequence separately")
    void testFormatToolSequence() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("call_123")
                                                        .name("get_weather")
                                                        .input(Map.of("location", "Beijing"))
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .name("get_weather")
                                .content(
                                        List.of(
                                                new ToolResultBlock(
                                                        "call_123",
                                                        "get_weather",
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Sunny, 25°C")
                                                                        .build()),
                                                        null)))
                                .build());

        List<OpenAIMessage> result = formatter.format(messages);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("assistant", result.get(0).getRole());
        assertEquals("tool", result.get(1).getRole());
    }

    @Test
    @DisplayName("Should handle mixed system, conversation, and tool messages")
    void testFormatMixedMessages() {
        List<Msg> messages = new ArrayList<>();
        // System message
        messages.add(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("You are a helpful assistant")
                                                .build()))
                        .build());
        // Agent conversation
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(List.of(TextBlock.builder().text("What's the weather?").build()))
                        .build());
        // Tool sequence
        messages.add(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("call_1")
                                                .name("get_weather")
                                                .input(Map.of())
                                                .build()))
                        .build());
        messages.add(
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .name("get_weather")
                        .content(
                                List.of(
                                        new ToolResultBlock(
                                                "call_1",
                                                "get_weather",
                                                List.of(TextBlock.builder().text("Sunny").build()),
                                                null)))
                        .build());

        List<OpenAIMessage> result = formatter.format(messages);

        assertNotNull(result);
        // Should have: system + merged conversation + assistant tool + tool result
        assertTrue(result.size() >= 3);
        assertEquals("system", result.get(0).getRole());
    }

    @Test
    @DisplayName("Should build request with model and messages")
    void testBuildRequest() {
        List<OpenAIMessage> messages =
                List.of(OpenAIMessage.builder().role("user").content("Hello").build());

        OpenAIRequest request = formatter.buildRequest("gpt-4", messages, false);

        assertNotNull(request);
        assertEquals("gpt-4", request.getModel());
        assertEquals(messages, request.getMessages());
        assertEquals(false, request.getStream());
    }

    @Test
    @DisplayName("Should apply generation options")
    void testApplyOptions() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();

        GenerateOptions options =
                GenerateOptions.builder().temperature(0.7).maxTokens(1000).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().build();

        formatter.applyOptions(request, options, defaultOptions);

        assertEquals(0.7, request.getTemperature());
        assertEquals(1000, request.getMaxTokens());
    }

    @Test
    @DisplayName("Should apply tools")
    void testApplyTools() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();

        ToolSchema tool =
                ToolSchema.builder().name("get_weather").description("Get weather").build();

        formatter.applyTools(request, List.of(tool));

        assertNotNull(request.getTools());
        assertEquals(1, request.getTools().size());
    }

    @Test
    @DisplayName("Should apply tool choice")
    void testApplyToolChoice() {
        // Add a tool first (tool_choice only applies when tools are present)
        OpenAIToolFunction function = new OpenAIToolFunction();
        function.setName("test_tool");
        OpenAITool tool = new OpenAITool();
        tool.setFunction(function);
        tool.setType("function");

        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(List.of())
                        .tools(List.of(tool))
                        .build();

        ToolChoice toolChoice = new ToolChoice.Auto();

        formatter.applyToolChoice(request, toolChoice);

        assertNotNull(request.getToolChoice());
    }

    @Test
    @DisplayName("Should use custom conversation history prompt")
    void testCustomConversationHistoryPrompt() {
        String customPrompt = "Custom history prompt\n";
        OpenAIMultiAgentFormatter customFormatter = new OpenAIMultiAgentFormatter(customPrompt);

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("Alice")
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build());

        List<OpenAIMessage> result = customFormatter.format(messages);

        assertNotNull(result);
        assertEquals(1, result.size());
        String content = result.get(0).getContentAsString();
        assertTrue(content.contains("Custom history prompt"));
    }
}
