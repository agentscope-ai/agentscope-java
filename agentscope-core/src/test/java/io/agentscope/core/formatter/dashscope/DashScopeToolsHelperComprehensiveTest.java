/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.formatter.dashscope;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.dashscope.dto.DashScopeParameters;
import io.agentscope.core.formatter.dashscope.dto.DashScopeTool;
import io.agentscope.core.formatter.dashscope.dto.DashScopeToolCall;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Comprehensive tests for DashScopeToolsHelper to achieve full code coverage. */
class DashScopeToolsHelperComprehensiveTest {

    private DashScopeToolsHelper helper;

    @BeforeEach
    void setUp() {
        helper = new DashScopeToolsHelper();
    }

    // ==================== ToolChoice Tests ====================

    @Test
    void testApplyToolChoiceWithNull() {
        DashScopeParameters params = DashScopeParameters.builder().build();

        // Should not throw exception with null tool choice
        assertDoesNotThrow(() -> helper.applyToolChoice(params, null));
        assertNull(params.getToolChoice());
    }

    @Test
    void testApplyToolChoiceWithAuto() {
        DashScopeParameters params = DashScopeParameters.builder().build();

        helper.applyToolChoice(params, new ToolChoice.Auto());

        // Verify toolChoice is set to "auto"
        assertEquals("auto", params.getToolChoice());
    }

    @Test
    void testApplyToolChoiceWithNone() {
        DashScopeParameters params = DashScopeParameters.builder().build();

        helper.applyToolChoice(params, new ToolChoice.None());

        // Verify toolChoice is set to "none"
        assertEquals("none", params.getToolChoice());
    }

    @Test
    void testApplyToolChoiceWithRequired() {
        DashScopeParameters params = DashScopeParameters.builder().build();

        helper.applyToolChoice(params, new ToolChoice.Required());

        // Required falls back to "auto" (not directly supported by DashScope)
        assertEquals("auto", params.getToolChoice());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testApplyToolChoiceWithSpecific() {
        DashScopeParameters params = DashScopeParameters.builder().build();

        helper.applyToolChoice(params, new ToolChoice.Specific("my_function"));

        // Verify toolChoice is set to the specific function object
        assertNotNull(params.getToolChoice());
        Map<String, Object> choice = (Map<String, Object>) params.getToolChoice();
        assertEquals("function", choice.get("type"));
        Map<String, String> function = (Map<String, String>) choice.get("function");
        assertEquals("my_function", function.get("name"));
    }

    // ==================== applyOptions Tests ====================

    @Test
    void testApplyOptionsWithAllOptions() {
        DashScopeParameters params = DashScopeParameters.builder().build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .topP(0.9)
                        .maxTokens(1024)
                        .thinkingBudget(500)
                        .build();

        helper.applyOptions(params, options, null);

        assertEquals(0.8, params.getTemperature());
        assertEquals(0.9, params.getTopP());
        assertEquals(1024, params.getMaxTokens());
        assertEquals(500, params.getThinkingBudget());
        assertTrue(params.getEnableThinking());
    }

    @Test
    void testApplyOptionsWithDefaultOptions() {
        DashScopeParameters params = DashScopeParameters.builder().build();
        GenerateOptions defaultOptions =
                GenerateOptions.builder().temperature(0.7).maxTokens(512).build();

        helper.applyOptions(params, null, defaultOptions);

        assertEquals(0.7, params.getTemperature());
        assertEquals(512, params.getMaxTokens());
    }

    @Test
    void testApplyOptionsOptionsOverrideDefault() {
        DashScopeParameters params = DashScopeParameters.builder().build();
        GenerateOptions options = GenerateOptions.builder().temperature(0.9).build();
        GenerateOptions defaultOptions =
                GenerateOptions.builder().temperature(0.5).maxTokens(256).build();

        helper.applyOptions(params, options, defaultOptions);

        // options.temperature should override defaultOptions.temperature
        assertEquals(0.9, params.getTemperature());
        // maxTokens from defaultOptions should be used
        assertEquals(256, params.getMaxTokens());
    }

    @Test
    void testApplyOptionsWithNullValues() {
        DashScopeParameters params = DashScopeParameters.builder().build();
        GenerateOptions options = GenerateOptions.builder().build();

        // Should not throw with all null values
        assertDoesNotThrow(() -> helper.applyOptions(params, options, null));
    }

    @Test
    void testApplyOptionsBothNull() {
        DashScopeParameters params = DashScopeParameters.builder().build();

        // Should not throw when both options are null
        assertDoesNotThrow(() -> helper.applyOptions(params, null, null));
    }

    // ==================== convertTools Tests ====================

    @Test
    void testConvertToolsWithValidTools() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        params.put("properties", Map.of("query", Map.of("type", "string")));

        ToolSchema tool1 =
                ToolSchema.builder()
                        .name("search")
                        .description("Search the web")
                        .parameters(params)
                        .build();

        ToolSchema tool2 =
                ToolSchema.builder()
                        .name("calculator")
                        .description("Calculate math")
                        .parameters(params)
                        .build();

        List<DashScopeTool> result = helper.convertTools(List.of(tool1, tool2));

        assertEquals(2, result.size());
        assertEquals("function", result.get(0).getType());
        assertEquals("search", result.get(0).getFunction().getName());
        assertEquals("Search the web", result.get(0).getFunction().getDescription());
        assertEquals("calculator", result.get(1).getFunction().getName());
    }

    @Test
    void testConvertToolsWithEmptyList() {
        List<DashScopeTool> result = helper.convertTools(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void testConvertToolsWithNullList() {
        List<DashScopeTool> result = helper.convertTools(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void testConvertToolsWithMinimalTool() {
        ToolSchema minimalTool =
                ToolSchema.builder().name("simple").description("Simple tool").build();

        List<DashScopeTool> result = helper.convertTools(List.of(minimalTool));

        assertEquals(1, result.size());
        assertEquals("simple", result.get(0).getFunction().getName());
        assertEquals("Simple tool", result.get(0).getFunction().getDescription());
    }

    // ==================== applyTools Tests ====================

    @Test
    void testApplyToolsWithValidTools() {
        DashScopeParameters params = DashScopeParameters.builder().build();
        ToolSchema tool = ToolSchema.builder().name("test_tool").description("A test tool").build();

        helper.applyTools(params, List.of(tool));

        assertNotNull(params.getTools());
        assertEquals(1, params.getTools().size());
    }

    @Test
    void testApplyToolsWithEmptyList() {
        DashScopeParameters params = DashScopeParameters.builder().build();

        helper.applyTools(params, List.of());

        assertNull(params.getTools());
    }

    @Test
    void testApplyToolsWithNull() {
        DashScopeParameters params = DashScopeParameters.builder().build();

        helper.applyTools(params, null);

        assertNull(params.getTools());
    }

    // ==================== convertToolCalls Tests ====================

    @Test
    void testConvertToolCallsWithValidBlocks() {
        Map<String, Object> args1 = new HashMap<>();
        args1.put("query", "test");

        Map<String, Object> args2 = new HashMap<>();
        args2.put("a", 1);
        args2.put("b", 2);

        List<ToolUseBlock> blocks =
                List.of(
                        ToolUseBlock.builder().id("call_1").name("search").input(args1).build(),
                        ToolUseBlock.builder().id("call_2").name("add").input(args2).build());

        List<DashScopeToolCall> result = helper.convertToolCalls(blocks);

        assertEquals(2, result.size());
        assertEquals("call_1", result.get(0).getId());
        assertEquals("search", result.get(0).getFunction().getName());
        assertEquals("call_2", result.get(1).getId());
        assertEquals("add", result.get(1).getFunction().getName());
    }

    @Test
    void testConvertToolCallsWithEmptyList() {
        List<DashScopeToolCall> result = helper.convertToolCalls(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void testConvertToolCallsWithNullList() {
        List<DashScopeToolCall> result = helper.convertToolCalls(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void testConvertToolCallsSkipsNullBlocks() {
        List<ToolUseBlock> blocks = new ArrayList<>();
        blocks.add(ToolUseBlock.builder().id("call_1").name("tool1").input(Map.of()).build());
        blocks.add(null);
        blocks.add(ToolUseBlock.builder().id("call_2").name("tool2").input(Map.of()).build());

        List<DashScopeToolCall> result = helper.convertToolCalls(blocks);

        assertEquals(2, result.size());
        assertEquals("call_1", result.get(0).getId());
        assertEquals("call_2", result.get(1).getId());
    }

    @Test
    void testConvertToolCallsWithEmptyInput() {
        ToolUseBlock block =
                ToolUseBlock.builder().id("call_123").name("no_args").input(Map.of()).build();

        List<DashScopeToolCall> result = helper.convertToolCalls(List.of(block));

        assertEquals(1, result.size());
        assertEquals("no_args", result.get(0).getFunction().getName());
        assertEquals("{}", result.get(0).getFunction().getArguments());
    }

    @Test
    void testConvertToolCallsWithComplexArgs() {
        Map<String, Object> complexArgs = new HashMap<>();
        complexArgs.put("string", "value");
        complexArgs.put("number", 42);
        complexArgs.put("boolean", true);
        complexArgs.put("nested", Map.of("key", "nested_value"));

        ToolUseBlock block =
                ToolUseBlock.builder()
                        .id("call_complex")
                        .name("complex")
                        .input(complexArgs)
                        .build();

        List<DashScopeToolCall> result = helper.convertToolCalls(List.of(block));

        assertEquals(1, result.size());
        String argsJson = result.get(0).getFunction().getArguments();
        assertNotNull(argsJson);
        assertTrue(argsJson.contains("string"));
        assertTrue(argsJson.contains("value"));
    }

    // ==================== convertToolChoice Tests ====================

    @Test
    void testConvertToolChoiceNull() {
        Object result = helper.convertToolChoice(null);
        assertNull(result);
    }

    @Test
    void testConvertToolChoiceAuto() {
        Object result = helper.convertToolChoice(new ToolChoice.Auto());
        assertEquals("auto", result);
    }

    @Test
    void testConvertToolChoiceNone() {
        Object result = helper.convertToolChoice(new ToolChoice.None());
        assertEquals("none", result);
    }

    @Test
    void testConvertToolChoiceRequired() {
        Object result = helper.convertToolChoice(new ToolChoice.Required());
        // Required falls back to auto
        assertEquals("auto", result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testConvertToolChoiceSpecific() {
        Object result = helper.convertToolChoice(new ToolChoice.Specific("my_tool"));

        assertNotNull(result);
        Map<String, Object> choice = (Map<String, Object>) result;
        assertEquals("function", choice.get("type"));
        Map<String, String> function = (Map<String, String>) choice.get("function");
        assertEquals("my_tool", function.get("name"));
    }

    // ==================== New Parameters Tests ====================

    @Test
    void testApplyOptionsWithTopK() {
        DashScopeParameters params = DashScopeParameters.builder().build();
        GenerateOptions options = GenerateOptions.builder().topK(40).build();

        helper.applyOptions(params, options, null);

        assertEquals(40, params.getTopK());
    }

    @Test
    void testApplyOptionsWithSeed() {
        DashScopeParameters params = DashScopeParameters.builder().build();
        GenerateOptions options = GenerateOptions.builder().seed(12345L).build();

        helper.applyOptions(params, options, null);

        assertEquals(12345, params.getSeed());
    }

    @Test
    void testApplyOptionsWithFrequencyPenalty() {
        DashScopeParameters params = DashScopeParameters.builder().build();
        GenerateOptions options = GenerateOptions.builder().frequencyPenalty(0.5).build();

        helper.applyOptions(params, options, null);

        assertEquals(0.5, params.getFrequencyPenalty());
    }

    @Test
    void testApplyOptionsWithPresencePenalty() {
        DashScopeParameters params = DashScopeParameters.builder().build();
        GenerateOptions options = GenerateOptions.builder().presencePenalty(0.3).build();

        helper.applyOptions(params, options, null);

        assertEquals(0.3, params.getPresencePenalty());
    }

    @Test
    void testApplyOptionsWithAllNewParameters() {
        DashScopeParameters params = DashScopeParameters.builder().build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .topK(50)
                        .seed(42L)
                        .frequencyPenalty(0.5)
                        .presencePenalty(0.3)
                        .build();

        helper.applyOptions(params, options, null);

        assertEquals(0.8, params.getTemperature());
        assertEquals(50, params.getTopK());
        assertEquals(42, params.getSeed());
        assertEquals(0.5, params.getFrequencyPenalty());
        assertEquals(0.3, params.getPresencePenalty());
    }

    @Test
    void testApplyOptionsTopKFromDefaultOptions() {
        DashScopeParameters params = DashScopeParameters.builder().build();
        GenerateOptions options = GenerateOptions.builder().temperature(0.5).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().topK(30).seed(999L).build();

        helper.applyOptions(params, options, defaultOptions);

        assertEquals(0.5, params.getTemperature());
        assertEquals(30, params.getTopK());
        assertEquals(999, params.getSeed());
    }

    // ==================== Merge Methods Tests ====================

    @Test
    void testMergeAdditionalHeadersWithBothOptions() {
        GenerateOptions defaultOptions =
                GenerateOptions.builder()
                        .additionalHeader("X-Default", "default-value")
                        .additionalHeader("X-Shared", "default-shared")
                        .build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalHeader("X-Custom", "custom-value")
                        .additionalHeader("X-Shared", "custom-shared")
                        .build();

        Map<String, String> result = helper.mergeAdditionalHeaders(options, defaultOptions);

        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals("default-value", result.get("X-Default"));
        assertEquals("custom-value", result.get("X-Custom"));
        assertEquals("custom-shared", result.get("X-Shared")); // options overrides default
    }

    @Test
    void testMergeAdditionalHeadersWithNullOptions() {
        GenerateOptions defaultOptions =
                GenerateOptions.builder().additionalHeader("X-Default", "value").build();

        Map<String, String> result = helper.mergeAdditionalHeaders(null, defaultOptions);

        assertNotNull(result);
        assertEquals("value", result.get("X-Default"));
    }

    @Test
    void testMergeAdditionalHeadersWithBothEmpty() {
        GenerateOptions options = GenerateOptions.builder().build();
        GenerateOptions defaultOptions = GenerateOptions.builder().build();

        Map<String, String> result = helper.mergeAdditionalHeaders(options, defaultOptions);

        assertNull(result);
    }

    @Test
    void testMergeAdditionalBodyParamsWithBothOptions() {
        GenerateOptions defaultOptions =
                GenerateOptions.builder()
                        .additionalBodyParam("default_key", "default_value")
                        .additionalBodyParam("shared_key", "default_shared")
                        .build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalBodyParam("custom_key", 123)
                        .additionalBodyParam("shared_key", "custom_shared")
                        .build();

        Map<String, Object> result = helper.mergeAdditionalBodyParams(options, defaultOptions);

        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals("default_value", result.get("default_key"));
        assertEquals(123, result.get("custom_key"));
        assertEquals("custom_shared", result.get("shared_key")); // options overrides default
    }

    @Test
    void testMergeAdditionalQueryParamsWithBothOptions() {
        GenerateOptions defaultOptions =
                GenerateOptions.builder()
                        .additionalQueryParam("default_param", "default_value")
                        .additionalQueryParam("shared_param", "default_shared")
                        .build();
        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalQueryParam("custom_param", "custom_value")
                        .additionalQueryParam("shared_param", "custom_shared")
                        .build();

        Map<String, String> result = helper.mergeAdditionalQueryParams(options, defaultOptions);

        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals("default_value", result.get("default_param"));
        assertEquals("custom_value", result.get("custom_param"));
        assertEquals("custom_shared", result.get("shared_param")); // options overrides default
    }
}
