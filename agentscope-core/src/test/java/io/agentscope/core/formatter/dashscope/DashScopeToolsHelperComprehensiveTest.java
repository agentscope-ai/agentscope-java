/*
 * Copyright 2024-2025 the original author or authors.
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

import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.tools.ToolCallBase;
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

    // ==================== MultiModalConversationParam ToolChoice Tests ====================

    @Test
    void testApplyToolChoiceMultiModalWithNull() {
        MultiModalConversationParam param =
                MultiModalConversationParam.builder().model("qwen-vl-max").build();

        // Should not throw exception with null tool choice
        assertDoesNotThrow(() -> helper.applyToolChoice(param, null));
    }

    @Test
    void testApplyToolChoiceMultiModalWithAuto() {
        MultiModalConversationParam param =
                MultiModalConversationParam.builder().model("qwen-vl-max").build();

        helper.applyToolChoice(param, new ToolChoice.Auto());

        // Verify toolChoice is set to "auto"
        assertEquals("auto", param.getToolChoice());
    }

    @Test
    void testApplyToolChoiceMultiModalWithNone() {
        MultiModalConversationParam param =
                MultiModalConversationParam.builder().model("qwen-vl-max").build();

        helper.applyToolChoice(param, new ToolChoice.None());

        // Verify toolChoice is set to "none"
        assertEquals("none", param.getToolChoice());
    }

    @Test
    void testApplyToolChoiceMultiModalWithRequired() {
        MultiModalConversationParam param =
                MultiModalConversationParam.builder().model("qwen-vl-max").build();

        // Should log warning and set to "auto"
        assertDoesNotThrow(() -> helper.applyToolChoice(param, new ToolChoice.Required()));
        assertEquals("auto", param.getToolChoice());
    }

    @Test
    void testApplyToolChoiceMultiModalWithSpecific() {
        MultiModalConversationParam param =
                MultiModalConversationParam.builder().model("qwen-vl-max").build();

        helper.applyToolChoice(param, new ToolChoice.Specific("my_function"));

        // Verify toolChoice is set to ToolFunction object
        assertNotNull(param.getToolChoice());
    }

    // ==================== GenerationParam ToolChoice Tests ====================

    @Test
    void testApplyToolChoiceGenerationWithAuto() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        helper.applyToolChoice(param, new ToolChoice.Auto());

        // Verify toolChoice is set to "auto"
        assertEquals("auto", param.getToolChoice());
    }

    @Test
    void testApplyToolChoiceGenerationWithNone() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        helper.applyToolChoice(param, new ToolChoice.None());

        // Verify toolChoice is set to "none"
        assertEquals("none", param.getToolChoice());
    }

    @Test
    void testApplyToolChoiceGenerationWithRequired() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        // Should log warning and set to "auto"
        assertDoesNotThrow(() -> helper.applyToolChoice(param, new ToolChoice.Required()));
        assertEquals("auto", param.getToolChoice());
    }

    @Test
    void testApplyToolChoiceGenerationWithSpecific() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        helper.applyToolChoice(param, new ToolChoice.Specific("generate_response"));

        // Verify toolChoice is set to ToolFunction object
        assertNotNull(param.getToolChoice());
    }

    // ==================== applyTools Tests ====================

    @Test
    void testApplyToolsWithNull() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        // Should handle null gracefully
        assertDoesNotThrow(() -> helper.applyTools(param, null));
        assertNull(param.getTools());
    }

    @Test
    void testApplyToolsWithEmptyList() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        // Should handle empty list gracefully
        assertDoesNotThrow(() -> helper.applyTools(param, List.of()));
        assertNull(param.getTools());
    }

    @Test
    void testApplyToolsWithValidTools() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", Map.of("arg1", Map.of("type", "string")));

        ToolSchema tool1 =
                ToolSchema.builder()
                        .name("tool1")
                        .description("Test tool 1")
                        .parameters(parameters)
                        .build();

        ToolSchema tool2 =
                ToolSchema.builder()
                        .name("tool2")
                        .description("Test tool 2")
                        .parameters(parameters)
                        .build();

        helper.applyTools(param, List.of(tool1, tool2));

        // Verify tools are set
        assertNotNull(param.getTools());
        assertEquals(2, param.getTools().size());
    }

    @Test
    void testApplyToolsWithEmptyParameters() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        // Use a mutable empty HashMap to avoid Collections$EmptyMap access issues
        Map<String, Object> emptyParams = new HashMap<>();

        ToolSchema tool =
                ToolSchema.builder()
                        .name("tool")
                        .description("Test")
                        .parameters(emptyParams) // Empty parameters should be handled
                        .build();

        // Should handle empty parameters gracefully
        assertDoesNotThrow(() -> helper.applyTools(param, List.of(tool)));
        assertNotNull(param.getTools());
    }

    @Test
    void testApplyToolsWithComplexValidParameters() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        // Complex but valid parameters
        Map<String, Object> complexParams = new HashMap<>();
        complexParams.put("type", "object");
        complexParams.put(
                "properties",
                Map.of(
                        "name", Map.of("type", "string"),
                        "age", Map.of("type", "integer")));
        complexParams.put("required", List.of("name"));

        ToolSchema tool =
                ToolSchema.builder()
                        .name("complex_tool")
                        .description("Complex tool")
                        .parameters(complexParams)
                        .build();

        assertDoesNotThrow(() -> helper.applyTools(param, List.of(tool)));
        assertNotNull(param.getTools());
    }

    // ==================== convertToolCalls Tests ====================

    @Test
    void testConvertToolCallsWithNull() {
        List<ToolCallBase> result = helper.convertToolCalls(null);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testConvertToolCallsWithEmptyList() {
        List<ToolCallBase> result = helper.convertToolCalls(List.of());

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testConvertToolCallsWithValidToolUse() {
        Map<String, Object> input = Map.of("param1", "value1", "param2", 123);

        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call_123").name("my_function").input(input).build();

        List<ToolCallBase> result = helper.convertToolCalls(List.of(toolUse));

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void testConvertToolCallsWithMultipleTools() {
        ToolUseBlock tool1 =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("func1")
                        .input(Map.of("arg", "val"))
                        .build();

        ToolUseBlock tool2 =
                ToolUseBlock.builder().id("call_2").name("func2").input(Map.of("num", 42)).build();

        List<ToolCallBase> result = helper.convertToolCalls(List.of(tool1, tool2));

        assertNotNull(result);
        assertEquals(2, result.size());
    }

    @Test
    void testConvertToolCallsWithNullToolUseBlock() {
        List<ToolUseBlock> toolBlocks = new ArrayList<>();
        toolBlocks.add(null);
        toolBlocks.add(ToolUseBlock.builder().id("call_1").name("func").input(Map.of()).build());

        List<ToolCallBase> result = helper.convertToolCalls(toolBlocks);

        // Should skip null and process valid ones
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void testConvertToolCallsWithComplexArguments() {
        Map<String, Object> complexInput = new HashMap<>();
        complexInput.put("string", "value");
        complexInput.put("number", 42);
        complexInput.put("boolean", true);
        complexInput.put("nested", Map.of("key", "value"));
        complexInput.put("array", List.of(1, 2, 3));

        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call_complex")
                        .name("complex_func")
                        .input(complexInput)
                        .build();

        List<ToolCallBase> result = helper.convertToolCalls(List.of(toolUse));

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void testConvertToolCallsWithEmptyInput() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call_empty").name("func").input(Map.of()).build();

        List<ToolCallBase> result = helper.convertToolCalls(List.of(toolUse));

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    // ==================== applyOptions Tests ====================

    @Test
    void testApplyOptionsWithAllOptions() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .topP(0.9)
                        .maxTokens(1000)
                        .thinkingBudget(500)
                        .build();

        helper.applyOptions(
                param,
                options,
                null,
                getter -> {
                    Object value = getter.apply(options);
                    return value;
                });

        // Verify options are applied
        assertEquals(0.8f, param.getTemperature());
        assertEquals(0.9, param.getTopP());
        assertEquals(1000, param.getMaxTokens());
        assertEquals(500, param.getThinkingBudget());
    }

    @Test
    void testApplyOptionsWithNullOptions() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        GenerateOptions defaultOptions =
                GenerateOptions.builder().temperature(0.7).topP(0.95).build();

        helper.applyOptions(
                param,
                null,
                defaultOptions,
                getter -> {
                    Object value = getter.apply(null);
                    if (value == null && defaultOptions != null) {
                        return getter.apply(defaultOptions);
                    }
                    return value;
                });

        // Should use default options
        assertEquals(0.7f, param.getTemperature());
        assertEquals(0.95, param.getTopP());
    }

    @Test
    void testApplyOptionsWithPartialOptions() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        GenerateOptions options = GenerateOptions.builder().temperature(0.5).build();

        GenerateOptions defaultOptions =
                GenerateOptions.builder().topP(0.85).maxTokens(2000).build();

        helper.applyOptions(
                param,
                options,
                defaultOptions,
                getter -> {
                    Object value = getter.apply(options);
                    if (value == null && defaultOptions != null) {
                        return getter.apply(defaultOptions);
                    }
                    return value;
                });

        // Should use options for temperature and defaults for others
        assertEquals(0.5f, param.getTemperature());
        assertEquals(0.85, param.getTopP());
        assertEquals(2000, param.getMaxTokens());
    }

    @Test
    void testApplyOptionsWithNullDefaultOptions() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        GenerateOptions options = GenerateOptions.builder().temperature(0.6).build();

        helper.applyOptions(
                param,
                options,
                null,
                getter -> {
                    Object value = getter.apply(options);
                    return value;
                });

        // Should only set specified options
        assertEquals(0.6f, param.getTemperature());
        assertNull(param.getTopP());
    }

    @Test
    void testApplyOptionsWithZeroValues() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        GenerateOptions options =
                GenerateOptions.builder().temperature(0.0).topP(0.0).maxTokens(0).build();

        helper.applyOptions(
                param,
                options,
                null,
                getter -> {
                    Object value = getter.apply(options);
                    return value;
                });

        // Zero values should still be applied
        assertEquals(0.0f, param.getTemperature());
        assertEquals(0.0, param.getTopP());
        assertEquals(0, param.getMaxTokens());
    }

    // ==================== New Parameters Tests ====================

    @Test
    void testApplyOptionsWithTopK() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        GenerateOptions options = GenerateOptions.builder().topK(40).build();

        helper.applyOptions(param, options, null, getter -> getter.apply(options));

        assertEquals(40, param.getTopK());
    }

    @Test
    void testApplyOptionsWithSeed() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        GenerateOptions options = GenerateOptions.builder().seed(12345L).build();

        helper.applyOptions(param, options, null, getter -> getter.apply(options));

        assertEquals(12345, param.getSeed());
    }

    @Test
    void testApplyOptionsWithAdditionalHeaders() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalHeader("X-Custom-Header", "custom-value")
                        .additionalHeader("X-Request-Id", "req-123")
                        .build();

        helper.applyOptions(param, options, null, getter -> getter.apply(options));

        Map<String, String> headers = param.getHeaders();
        assertNotNull(headers);
        assertEquals("custom-value", headers.get("X-Custom-Header"));
        assertEquals("req-123", headers.get("X-Request-Id"));
    }

    @Test
    void testApplyOptionsWithAllNewParameters() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .topK(50)
                        .seed(42L)
                        .additionalHeader("X-Api-Key", "secret")
                        .build();

        helper.applyOptions(param, options, null, getter -> getter.apply(options));

        assertEquals(0.8f, param.getTemperature());
        assertEquals(50, param.getTopK());
        assertEquals(42, param.getSeed());
        assertEquals("secret", param.getHeaders().get("X-Api-Key"));
    }

    @Test
    void testApplyOptionsTopKFromDefaultOptions() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        GenerateOptions options = GenerateOptions.builder().temperature(0.5).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().topK(30).seed(999L).build();

        helper.applyOptions(
                param,
                options,
                defaultOptions,
                getter -> {
                    Object value = getter.apply(options);
                    if (value == null && defaultOptions != null) {
                        return getter.apply(defaultOptions);
                    }
                    return value;
                });

        assertEquals(0.5f, param.getTemperature());
        assertEquals(30, param.getTopK());
        assertEquals(999, param.getSeed());
    }
}
