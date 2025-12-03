/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.core.formatter.dashscope.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for DashScopeDtoConverter.
 */
class DashScopeDtoConverterTest {

    private DashScopeDtoConverter converter;

    @BeforeEach
    void setUp() {
        converter =
                new DashScopeDtoConverter(
                        blocks -> {
                            StringBuilder sb = new StringBuilder();
                            for (ContentBlock block : blocks) {
                                if (block instanceof TextBlock tb) {
                                    sb.append(tb.getText());
                                }
                            }
                            return sb.toString();
                        });
    }

    @Test
    void testConvertUserMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Hello, how are you?").build())
                        .build();

        DashScopeMessage dsMsg = converter.convertMessage(msg, false);

        assertEquals("user", dsMsg.getRole());
        assertEquals("Hello, how are you?", dsMsg.getContentAsString());
        assertFalse(dsMsg.isMultimodal());
    }

    @Test
    void testConvertAssistantMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("I'm doing well, thanks!").build())
                        .build();

        DashScopeMessage dsMsg = converter.convertMessage(msg, false);

        assertEquals("assistant", dsMsg.getRole());
        assertEquals("I'm doing well, thanks!", dsMsg.getContentAsString());
    }

    @Test
    void testConvertSystemMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text("You are a helpful assistant.").build())
                        .build();

        DashScopeMessage dsMsg = converter.convertMessage(msg, false);

        assertEquals("system", dsMsg.getRole());
        assertEquals("You are a helpful assistant.", dsMsg.getContentAsString());
    }

    @Test
    void testConvertToolResultMessage() {
        ToolResultBlock toolResult =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("get_weather")
                        .output(List.of(TextBlock.builder().text("Sunny, 25°C").build()))
                        .build();

        Msg msg = Msg.builder().role(MsgRole.TOOL).content(List.of(toolResult)).build();

        DashScopeMessage dsMsg = converter.convertMessage(msg, false);

        assertEquals("tool", dsMsg.getRole());
        assertEquals("call_123", dsMsg.getToolCallId());
        assertEquals("get_weather", dsMsg.getName());
        assertEquals("Sunny, 25°C", dsMsg.getContentAsString());
    }

    @Test
    void testConvertAssistantWithToolCalls() {
        Map<String, Object> args = new HashMap<>();
        args.put("location", "Beijing");

        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call_abc").name("get_weather").input(args).build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("Let me check the weather.")
                                                .build(),
                                        toolUse))
                        .build();

        DashScopeMessage dsMsg = converter.convertMessage(msg, false);

        assertEquals("assistant", dsMsg.getRole());
        assertNotNull(dsMsg.getToolCalls());
        assertEquals(1, dsMsg.getToolCalls().size());

        DashScopeToolCall toolCall = dsMsg.getToolCalls().get(0);
        assertEquals("call_abc", toolCall.getId());
        assertEquals("get_weather", toolCall.getFunction().getName());
        assertTrue(toolCall.getFunction().getArguments().contains("Beijing"));
    }

    @Test
    void testConvertMultipleMessages() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello").build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text("Hi there!").build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("How are you?").build())
                                .build());

        List<DashScopeMessage> dsMessages = converter.convertMessages(messages, false);

        assertEquals(3, dsMessages.size());
        assertEquals("user", dsMessages.get(0).getRole());
        assertEquals("assistant", dsMessages.get(1).getRole());
        assertEquals("user", dsMessages.get(2).getRole());
    }

    @Test
    void testBuildParametersWithOptions() {
        GenerateOptions options =
                GenerateOptions.builder().temperature(0.7).topP(0.9).maxTokens(1000).build();

        DashScopeParameters params = converter.buildParameters(options, null, null, null, false);

        assertEquals("message", params.getResultFormat());
        assertEquals(0.7, params.getTemperature());
        assertEquals(0.9, params.getTopP());
        assertEquals(1000, params.getMaxTokens());
        assertNull(params.getIncrementalOutput());
    }

    @Test
    void testBuildParametersForStreaming() {
        GenerateOptions options = GenerateOptions.builder().temperature(0.5).build();

        DashScopeParameters params = converter.buildParameters(options, null, null, null, true);

        assertTrue(params.getIncrementalOutput());
    }

    @Test
    void testBuildParametersWithDefaultFallback() {
        GenerateOptions options = GenerateOptions.builder().build();
        GenerateOptions defaults =
                GenerateOptions.builder().temperature(0.8).maxTokens(2000).build();

        DashScopeParameters params =
                converter.buildParameters(options, defaults, null, null, false);

        assertEquals(0.8, params.getTemperature());
        assertEquals(2000, params.getMaxTokens());
    }

    @Test
    void testBuildParametersWithTools() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");

        ToolSchema tool =
                ToolSchema.builder()
                        .name("get_weather")
                        .description("Get weather info")
                        .parameters(parameters)
                        .build();

        DashScopeParameters params =
                converter.buildParameters(null, null, List.of(tool), null, false);

        assertNotNull(params.getTools());
        assertEquals(1, params.getTools().size());
        assertEquals("get_weather", params.getTools().get(0).getFunction().getName());
    }

    @Test
    void testBuildParametersWithToolChoiceAuto() {
        DashScopeParameters params =
                converter.buildParameters(null, null, null, new ToolChoice.Auto(), false);

        assertEquals("auto", params.getToolChoice());
    }

    @Test
    void testBuildParametersWithToolChoiceNone() {
        DashScopeParameters params =
                converter.buildParameters(null, null, null, new ToolChoice.None(), false);

        assertEquals("none", params.getToolChoice());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testBuildParametersWithToolChoiceSpecific() {
        DashScopeParameters params =
                converter.buildParameters(
                        null, null, null, new ToolChoice.Specific("my_tool"), false);

        assertNotNull(params.getToolChoice());
        assertTrue(params.getToolChoice() instanceof Map);
        Map<String, Object> choice = (Map<String, Object>) params.getToolChoice();
        assertEquals("function", choice.get("type"));
    }

    @Test
    void testBuildCompleteRequest() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .content(TextBlock.builder().text("You are helpful").build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hi").build())
                                .build());

        GenerateOptions options = GenerateOptions.builder().temperature(0.5).build();

        DashScopeRequest request =
                converter.buildRequest(
                        "qwen-plus", messages, false, options, null, null, null, false);

        assertEquals("qwen-plus", request.getModel());
        assertNotNull(request.getInput());
        assertEquals(2, request.getInput().getMessages().size());
        assertNotNull(request.getParameters());
        assertEquals(0.5, request.getParameters().getTemperature());
    }

    @Test
    void testBuildRequestWithThinkingBudget() {
        GenerateOptions options = GenerateOptions.builder().thinkingBudget(500).build();

        DashScopeParameters params = converter.buildParameters(options, null, null, null, false);

        assertTrue(params.getEnableThinking());
        assertEquals(500, params.getThinkingBudget());
    }
}
