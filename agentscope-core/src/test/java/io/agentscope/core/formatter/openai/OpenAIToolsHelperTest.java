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

import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAITool;
import io.agentscope.core.formatter.openai.dto.OpenAIToolFunction;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OpenAIToolsHelper.
 *
 * <p>These tests verify the helper's ability to:
 * <ul>
 *   <li>Apply generation options to requests</li>
 *   <li>Convert tool schemas to OpenAI tools</li>
 *   <li>Apply tools to requests</li>
 *   <li>Apply tool choice configurations</li>
 *   <li>Handle additional body parameters</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("OpenAIToolsHelper Unit Tests")
class OpenAIToolsHelperTest {

    private OpenAIToolsHelper helper;

    @BeforeEach
    void setUp() {
        helper = new OpenAIToolsHelper();
    }

    @Test
    @DisplayName("Should apply temperature option")
    void testApplyTemperature() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();

        GenerateOptions options = GenerateOptions.builder().temperature(0.7).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().build();

        helper.applyOptions(request, options, defaultOptions);

        assertEquals(0.7, request.getTemperature());
    }

    @Test
    @DisplayName("Should apply max tokens option")
    void testApplyMaxTokens() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();

        GenerateOptions options = GenerateOptions.builder().maxTokens(1000).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().build();

        helper.applyOptions(request, options, defaultOptions);

        assertEquals(1000, request.getMaxTokens());
        assertEquals(1000, request.getMaxCompletionTokens());
    }

    @Test
    @DisplayName("Should apply top_p option")
    void testApplyTopP() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();

        GenerateOptions options = GenerateOptions.builder().topP(0.9).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().build();

        helper.applyOptions(request, options, defaultOptions);

        assertEquals(0.9, request.getTopP());
    }

    @Test
    @DisplayName("Should apply frequency and presence penalty")
    void testApplyPenalties() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();

        GenerateOptions options =
                GenerateOptions.builder().frequencyPenalty(0.5).presencePenalty(0.3).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().build();

        helper.applyOptions(request, options, defaultOptions);

        assertEquals(0.5, request.getFrequencyPenalty());
        assertEquals(0.3, request.getPresencePenalty());
    }

    @Test
    @DisplayName("Should apply seed option")
    void testApplySeed() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();

        GenerateOptions options = GenerateOptions.builder().seed(42L).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().build();

        helper.applyOptions(request, options, defaultOptions);

        assertEquals(42, request.getSeed());
    }

    @Test
    @DisplayName("Should use default options when options is null")
    void testApplyOptionsWithNull() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();

        GenerateOptions defaultOptions =
                GenerateOptions.builder().temperature(0.8).maxTokens(500).build();

        helper.applyOptions(request, null, defaultOptions);

        assertEquals(0.8, request.getTemperature());
        assertEquals(500, request.getMaxTokens());
    }

    @Test
    @DisplayName("Should convert tool schemas to OpenAI tools")
    void testConvertTools() {
        ToolSchema tool1 =
                ToolSchema.builder()
                        .name("get_weather")
                        .description("Get weather information")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "location",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "City name"))))
                        .build();

        ToolSchema tool2 =
                ToolSchema.builder().name("calculate").description("Perform calculations").build();

        List<OpenAITool> result = helper.convertTools(List.of(tool1, tool2));

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("get_weather", result.get(0).getFunction().getName());
        assertEquals("calculate", result.get(1).getFunction().getName());
    }

    @Test
    @DisplayName("Should return null for empty tool list")
    void testConvertEmptyTools() {
        List<OpenAITool> result = helper.convertTools(List.of());
        assertNull(result);
    }

    @Test
    @DisplayName("Should return null for null tool list")
    void testConvertNullTools() {
        List<OpenAITool> result = helper.convertTools(null);
        assertNull(result);
    }

    @Test
    @DisplayName("Should apply tools to request")
    void testApplyTools() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();

        ToolSchema tool =
                ToolSchema.builder().name("get_weather").description("Get weather").build();

        helper.applyTools(request, List.of(tool));

        assertNotNull(request.getTools());
        assertEquals(1, request.getTools().size());
        assertEquals("get_weather", request.getTools().get(0).getFunction().getName());
    }

    @Test
    @DisplayName("Should not apply tools when list is empty")
    void testApplyEmptyTools() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();

        helper.applyTools(request, List.of());

        assertNull(request.getTools());
    }

    @Test
    @DisplayName("Should apply tool choice auto")
    void testApplyToolChoiceAuto() {
        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(List.of())
                        .tools(createDummyTools())
                        .build();

        ToolChoice toolChoice = new ToolChoice.Auto();

        helper.applyToolChoice(request, toolChoice);

        assertEquals("auto", request.getToolChoice());
    }

    @Test
    @DisplayName("Should apply tool choice none")
    void testApplyToolChoiceNone() {
        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(List.of())
                        .tools(createDummyTools())
                        .build();

        ToolChoice toolChoice = new ToolChoice.None();

        helper.applyToolChoice(request, toolChoice);

        assertEquals("none", request.getToolChoice());
    }

    @Test
    @DisplayName("Should apply tool choice required")
    void testApplyToolChoiceRequired() {
        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(List.of())
                        .tools(createDummyTools())
                        .build();

        ToolChoice toolChoice = new ToolChoice.Required();

        helper.applyToolChoice(request, toolChoice);

        assertEquals("required", request.getToolChoice());
    }

    @Test
    @DisplayName("Should apply tool choice specific")
    void testApplyToolChoiceSpecific() {
        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(List.of())
                        .tools(createDummyTools())
                        .build();

        ToolChoice toolChoice = new ToolChoice.Specific("get_weather");

        helper.applyToolChoice(request, toolChoice);

        assertNotNull(request.getToolChoice());
        assertTrue(request.getToolChoice() instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> toolChoiceMap = (Map<String, Object>) request.getToolChoice();
        assertEquals("function", toolChoiceMap.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) toolChoiceMap.get("function");
        assertEquals("get_weather", function.get("name"));
    }

    @Test
    @DisplayName("Should apply tool choice null as auto")
    void testApplyToolChoiceNull() {
        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(List.of())
                        .tools(createDummyTools())
                        .build();

        helper.applyToolChoice(request, null);

        assertEquals("auto", request.getToolChoice());
    }

    @Test
    @DisplayName("Should ignore tool choice when no tools provided")
    void testApplyToolChoiceWithNoTools() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();

        // request.getTools() is null/empty by default

        helper.applyToolChoice(request, new ToolChoice.Auto());

        // Should be null because of early return
        assertNull(request.getToolChoice());
    }

    private List<OpenAITool> createDummyTools() {
        OpenAIToolFunction function = new OpenAIToolFunction();
        function.setName("test_tool");
        OpenAITool tool = new OpenAITool();
        tool.setFunction(function);
        tool.setType("function");
        return List.of(tool);
    }

    @Test
    @DisplayName("Should apply reasoning effort")
    void testApplyReasoningEffort() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();

        helper.applyReasoningEffort(request, "high");

        assertEquals("high", request.getReasoningEffort());
    }

    @Test
    @DisplayName("Should apply additional body parameters")
    void testApplyAdditionalBodyParams() {
        OpenAIRequest request = OpenAIRequest.builder().model("gpt-4").messages(List.of()).build();

        Map<String, Object> additionalParams = new HashMap<>();
        additionalParams.put("reasoning_effort", "high");
        additionalParams.put("stop", List.of("STOP", "END"));

        GenerateOptions options =
                GenerateOptions.builder().additionalBodyParams(additionalParams).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().build();

        helper.applyOptions(request, options, defaultOptions);

        assertEquals("high", request.getReasoningEffort());
        assertNotNull(request.getStop());
        assertTrue(request.getStop() instanceof List);
    }

    @Test
    @DisplayName("Should handle tool with strict field")
    void testConvertToolWithStrict() {
        ToolSchema tool =
                ToolSchema.builder()
                        .name("get_weather")
                        .description("Get weather")
                        .strict(true)
                        .build();

        List<OpenAITool> result = helper.convertTools(List.of(tool));

        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.get(0).getFunction().getStrict());
    }
}
