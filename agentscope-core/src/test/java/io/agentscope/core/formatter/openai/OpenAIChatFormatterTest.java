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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.formatter.openai.dto.OpenAITool;
import io.agentscope.core.formatter.openai.dto.OpenAIToolFunction;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OpenAIChatFormatter.
 *
 * <p>These tests verify the formatter's ability to:
 * <ul>
 *   <li>Format messages correctly</li>
 *   <li>Apply generation options</li>
 *   <li>Apply tools and tool choices</li>
 *   <li>Build complete requests</li>
 *   <li>Parse responses</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("OpenAIChatFormatter Unit Tests")
class OpenAIChatFormatterTest {

    private OpenAIChatFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new OpenAIChatFormatter();
    }

    @Test
    @DisplayName("Should format simple text messages")
    void testFormatSimpleTextMessages() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(List.of(TextBlock.builder().text("Hi there").build()))
                                .build());

        List<OpenAIMessage> result = formatter.format(messages);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("user", result.get(0).getRole());
        assertEquals("Hello", result.get(0).getContentAsString());
        assertEquals("assistant", result.get(1).getRole());
        assertEquals("Hi there", result.get(1).getContentAsString());
    }

    @Test
    @DisplayName("Should format system message")
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
    @DisplayName("Should apply generation options to request")
    void testApplyOptions() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();

        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.7)
                        .maxTokens(1000)
                        .topP(0.9)
                        .frequencyPenalty(0.5)
                        .presencePenalty(0.3)
                        .seed(42L)
                        .build();

        GenerateOptions defaultOptions = GenerateOptions.builder().build();

        formatter.applyOptions(request, options, defaultOptions);

        assertEquals(0.7, request.getTemperature());
        assertEquals(1000, request.getMaxTokens());
        assertEquals(0.9, request.getTopP());
        assertEquals(0.5, request.getFrequencyPenalty());
        assertEquals(0.3, request.getPresencePenalty());
        assertEquals(42, request.getSeed());
    }

    @Test
    @DisplayName("Should use default options when options is null")
    void testApplyOptionsWithNull() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();

        GenerateOptions defaultOptions =
                GenerateOptions.builder().temperature(0.8).maxTokens(500).build();

        formatter.applyOptions(request, null, defaultOptions);

        assertEquals(0.8, request.getTemperature());
        assertEquals(500, request.getMaxTokens());
    }

    @Test
    @DisplayName("Should apply tools to request")
    void testApplyTools() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();

        ToolSchema tool1 =
                ToolSchema.builder()
                        .name("get_weather")
                        .description("Get weather information")
                        .build();

        ToolSchema tool2 =
                ToolSchema.builder().name("calculate").description("Perform calculations").build();

        formatter.applyTools(request, List.of(tool1, tool2));

        assertNotNull(request.getTools());
        assertEquals(2, request.getTools().size());
        assertEquals("get_weather", request.getTools().get(0).getFunction().getName());
        assertEquals("calculate", request.getTools().get(1).getFunction().getName());
    }

    @Test
    @DisplayName("Should apply tool choice to request")
    void testApplyToolChoice() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();
        request.setTools(createDummyTools());

        ToolChoice toolChoice = new ToolChoice.Specific("get_weather");

        formatter.applyToolChoice(request, toolChoice);

        assertNotNull(request.getToolChoice());
        assertTrue(request.getToolChoice().toString().contains("get_weather"));
    }

    @Test
    @DisplayName("Should apply tool choice auto")
    void testApplyToolChoiceAuto() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();
        request.setTools(createDummyTools());

        ToolChoice toolChoice = new ToolChoice.Auto();

        formatter.applyToolChoice(request, toolChoice);

        assertNotNull(request.getToolChoice());
    }

    @Test
    @DisplayName("Should apply tool choice none")
    void testApplyToolChoiceNone() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();
        request.setTools(createDummyTools());

        ToolChoice toolChoice = new ToolChoice.None();

        formatter.applyToolChoice(request, toolChoice);

        assertNotNull(request.getToolChoice());
    }

    private List<OpenAITool> createDummyTools() {
        OpenAIToolFunction function = new OpenAIToolFunction();
        function.setName("get_weather");
        OpenAITool tool = new OpenAITool();
        tool.setFunction(function);
        tool.setType("function");
        return List.of(tool);
    }

    @Test
    @DisplayName("Should build complete request with all options")
    void testBuildCompleteRequest() {
        List<OpenAIMessage> messages =
                List.of(OpenAIMessage.builder().role("user").content("Hello").build());

        GenerateOptions options =
                GenerateOptions.builder().temperature(0.7).maxTokens(1000).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().build();

        ToolSchema tool =
                ToolSchema.builder().name("get_weather").description("Get weather").build();

        ToolChoice toolChoice = new ToolChoice.Auto();

        OpenAIRequest request =
                formatter.buildRequest(
                        "gpt-4",
                        messages,
                        false,
                        options,
                        defaultOptions,
                        List.of(tool),
                        toolChoice);

        assertNotNull(request);
        assertEquals("gpt-4", request.getModel());
        assertEquals(messages, request.getMessages());
        assertEquals(0.7, request.getTemperature());
        assertEquals(1000, request.getMaxTokens());
        assertNotNull(request.getTools());
        assertEquals(1, request.getTools().size());
        assertNotNull(request.getToolChoice());
    }

    @Test
    @DisplayName("Should parse response correctly")
    void testParseResponse() {
        // Create response using setters (OpenAIResponse doesn't have builder)
        OpenAIResponse response = new OpenAIResponse();
        response.setId("chatcmpl-123");
        response.setObject("chat.completion");
        response.setCreated(1677652280L);
        response.setModel("gpt-4");

        io.agentscope.core.formatter.openai.dto.OpenAIChoice choice =
                new io.agentscope.core.formatter.openai.dto.OpenAIChoice();
        choice.setIndex(0);
        OpenAIMessage message =
                OpenAIMessage.builder().role("assistant").content("Hello! How can I help?").build();
        choice.setMessage(message);
        choice.setFinishReason("stop");
        response.setChoices(List.of(choice));

        io.agentscope.core.formatter.openai.dto.OpenAIUsage usage =
                new io.agentscope.core.formatter.openai.dto.OpenAIUsage();
        usage.setPromptTokens(10);
        usage.setCompletionTokens(20);
        usage.setTotalTokens(30);
        response.setUsage(usage);

        Instant startTime = Instant.now();
        ChatResponse chatResponse = formatter.parseResponse(response, startTime);

        assertNotNull(chatResponse);
        assertNotNull(chatResponse.getContent());
        assertEquals(
                "Hello! How can I help?",
                ((io.agentscope.core.message.TextBlock) chatResponse.getContent().get(0))
                        .getText());
        assertNotNull(chatResponse.getUsage());
        assertEquals(10, chatResponse.getUsage().getInputTokens());
        assertEquals(20, chatResponse.getUsage().getOutputTokens());
    }

    @Test
    @DisplayName("Should handle null tool choice gracefully")
    void testBuildRequestWithNullToolChoice() {
        List<OpenAIMessage> messages =
                List.of(OpenAIMessage.builder().role("user").content("Hello").build());

        GenerateOptions options = GenerateOptions.builder().temperature(0.7).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().build();

        OpenAIRequest request =
                formatter.buildRequest(
                        "gpt-4", messages, false, options, defaultOptions, List.of(), null);

        assertNotNull(request);
        assertNull(request.getToolChoice());
    }
}
