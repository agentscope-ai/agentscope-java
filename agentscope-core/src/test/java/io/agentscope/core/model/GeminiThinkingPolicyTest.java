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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.StructuredOutputCapableAgent;
import io.agentscope.core.formatter.gemini.dto.GeminiGenerationConfig;
import io.agentscope.core.formatter.gemini.dto.GeminiRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@Tag("unit")
@DisplayName("GeminiThinkingPolicy Unit Tests")
class GeminiThinkingPolicyTest {

    private final GeminiThinkingPolicy policy =
            new GeminiThinkingPolicy(LoggerFactory.getLogger(GeminiThinkingPolicyTest.class));

    @Test
    @DisplayName("Should disable thinking config for Gemini 3 Flash structured output")
    void testDisableThinkingForGemini3FlashStructuredOutput() {
        GeminiRequest request = new GeminiRequest();
        GeminiGenerationConfig config = new GeminiGenerationConfig();
        config.setThinkingConfig(new GeminiGenerationConfig.GeminiThinkingConfig());
        request.setGenerationConfig(config);

        boolean isStructuredOutput =
                policy.disableThinkingForGemini3FlashStructuredOutput(
                        "gemini-3-flash-preview", request, List.of(structuredOutputTool()));

        assertTrue(isStructuredOutput);
        assertNotNull(request.getGenerationConfig());
        assertNull(request.getGenerationConfig().getThinkingConfig());
    }

    @Test
    @DisplayName("Should not disable thinking config for non Gemini 3 Flash model")
    void testDisableThinkingForNonFlashModel() {
        GeminiRequest request = new GeminiRequest();
        GeminiGenerationConfig.GeminiThinkingConfig thinkingConfig =
                new GeminiGenerationConfig.GeminiThinkingConfig();
        GeminiGenerationConfig config = new GeminiGenerationConfig();
        config.setThinkingConfig(thinkingConfig);
        request.setGenerationConfig(config);

        boolean isStructuredOutput =
                policy.disableThinkingForGemini3FlashStructuredOutput(
                        "gemini-2.5-flash", request, List.of(structuredOutputTool()));

        assertFalse(isStructuredOutput);
        assertSame(thinkingConfig, request.getGenerationConfig().getThinkingConfig());
    }

    @Test
    @DisplayName(
            "Should create generation config when disabling thinking for flash structured output")
    void testDisableThinkingCreatesGenerationConfigWhenMissing() {
        GeminiRequest request = new GeminiRequest();

        boolean isStructuredOutput =
                policy.disableThinkingForGemini3FlashStructuredOutput(
                        "gemini-3-flash-preview", request, List.of(structuredOutputTool()));

        assertTrue(isStructuredOutput);
        assertNotNull(request.getGenerationConfig());
        assertNull(request.getGenerationConfig().getThinkingConfig());
    }

    @Test
    @DisplayName("Should disable thoughts when Gemini 3 has non structured output tools")
    void testApplyGemini3CompatibilityPolicyWithNonStructuredTool() {
        GeminiRequest request = new GeminiRequest();

        policy.applyGemini3CompatibilityPolicy(
                "gemini-3-pro", request, List.of(simpleTool("search_web")), false);

        assertNotNull(request.getGenerationConfig());
        assertNotNull(request.getGenerationConfig().getThinkingConfig());
        assertFalse(request.getGenerationConfig().getThinkingConfig().getIncludeThoughts());
    }

    @Test
    @DisplayName("Should remove thinking budget and enable thoughts for structured output tool")
    void testApplyGemini3CompatibilityPolicyWithStructuredOutputTool() {
        GeminiGenerationConfig.GeminiThinkingConfig thinkingConfig =
                new GeminiGenerationConfig.GeminiThinkingConfig();
        thinkingConfig.setIncludeThoughts(false);
        thinkingConfig.setThinkingBudget(512);
        GeminiGenerationConfig generationConfig = new GeminiGenerationConfig();
        generationConfig.setThinkingConfig(thinkingConfig);
        GeminiRequest request = new GeminiRequest();
        request.setGenerationConfig(generationConfig);

        policy.applyGemini3CompatibilityPolicy(
                "gemini-3-pro", request, List.of(structuredOutputTool()), false);

        assertEquals(true, request.getGenerationConfig().getThinkingConfig().getIncludeThoughts());
        assertNull(request.getGenerationConfig().getThinkingConfig().getThinkingBudget());
    }

    @Test
    @DisplayName(
            "Should disable thoughts for Gemini 3 Flash structured output in compatibility policy")
    void testApplyGemini3CompatibilityPolicyWithFlashStructuredOutputTool() {
        GeminiGenerationConfig.GeminiThinkingConfig thinkingConfig =
                new GeminiGenerationConfig.GeminiThinkingConfig();
        thinkingConfig.setIncludeThoughts(true);
        thinkingConfig.setThinkingBudget(2048);
        GeminiGenerationConfig generationConfig = new GeminiGenerationConfig();
        generationConfig.setThinkingConfig(thinkingConfig);
        GeminiRequest request = new GeminiRequest();
        request.setGenerationConfig(generationConfig);

        policy.applyGemini3CompatibilityPolicy(
                "gemini-3-flash-preview", request, List.of(structuredOutputTool()), false);

        assertEquals(false, request.getGenerationConfig().getThinkingConfig().getIncludeThoughts());
        assertNull(request.getGenerationConfig().getThinkingConfig().getThinkingBudget());
    }

    @Test
    @DisplayName("Should keep request unchanged when model is not Gemini 3")
    void testApplyGemini3CompatibilityPolicyWithNonGemini3Model() {
        GeminiRequest request = new GeminiRequest();
        GeminiGenerationConfig generationConfig = new GeminiGenerationConfig();
        request.setGenerationConfig(generationConfig);

        policy.applyGemini3CompatibilityPolicy(
                "gemini-2.5-flash", request, List.of(simpleTool("search")), false);

        assertSame(generationConfig, request.getGenerationConfig());
    }

    @Test
    @DisplayName("Should skip compatibility policy when flash structured output already handled")
    void testApplyGemini3CompatibilityPolicySkippedByFlag() {
        GeminiRequest request = new GeminiRequest();
        GeminiGenerationConfig generationConfig = new GeminiGenerationConfig();
        request.setGenerationConfig(generationConfig);

        policy.applyGemini3CompatibilityPolicy(
                "gemini-3-flash-preview", request, List.of(simpleTool("search")), true);

        assertSame(generationConfig, request.getGenerationConfig());
        assertNull(request.getGenerationConfig().getThinkingConfig());
    }

    @Test
    @DisplayName("Should keep null generation config when Gemini 3 has no tools")
    void testApplyGemini3CompatibilityPolicyWithNoTools() {
        GeminiRequest request = new GeminiRequest();

        policy.applyGemini3CompatibilityPolicy("gemini-3-pro", request, null, false);

        assertNull(request.getGenerationConfig());
    }

    @Test
    @DisplayName("Should keep null thinking config when generation config exists")
    void testApplyGemini3CompatibilityPolicyWithNullThinkingConfig() {
        GeminiRequest request = new GeminiRequest();
        request.setGenerationConfig(new GeminiGenerationConfig());

        policy.applyGemini3CompatibilityPolicy(
                "gemini-3-pro", request, List.of(structuredOutputTool()), false);

        assertNotNull(request.getGenerationConfig());
        assertNull(request.getGenerationConfig().getThinkingConfig());
    }

    @Test
    @DisplayName("Should force unary structured output and disable thoughts for Gemini 3 Flash")
    void testApplyForceUnaryForStructuredOutput() {
        GeminiRequest request = new GeminiRequest();
        boolean forceUnary =
                policy.applyForceUnaryForStructuredOutput(
                        "gemini-3-flash-preview", request, List.of(structuredOutputTool()));

        assertTrue(forceUnary);
        assertNotNull(request.getGenerationConfig());
        assertNotNull(request.getGenerationConfig().getThinkingConfig());
        assertEquals(false, request.getGenerationConfig().getThinkingConfig().getIncludeThoughts());
    }

    @Test
    @DisplayName("Should force unary without changing thinking config for non flash model")
    void testApplyForceUnaryForStructuredOutputWithNonFlashModel() {
        GeminiRequest request = new GeminiRequest();
        GeminiGenerationConfig generationConfig = new GeminiGenerationConfig();
        request.setGenerationConfig(generationConfig);

        boolean forceUnary =
                policy.applyForceUnaryForStructuredOutput(
                        "gemini-3-pro", request, List.of(structuredOutputTool()));

        assertTrue(forceUnary);
        assertSame(generationConfig, request.getGenerationConfig());
        assertNull(request.getGenerationConfig().getThinkingConfig());
    }

    @Test
    @DisplayName("Should not force unary when structured output tool is absent")
    void testApplyForceUnaryForStructuredOutputWithoutStructuredTool() {
        GeminiRequest request = new GeminiRequest();

        boolean forceUnary =
                policy.applyForceUnaryForStructuredOutput(
                        "gemini-3-pro", request, List.of(simpleTool("search")));

        assertFalse(forceUnary);
        assertNull(request.getGenerationConfig());
    }

    @Test
    @DisplayName("Should not force unary when tool list is null")
    void testApplyForceUnaryForStructuredOutputWithNullTools() {
        GeminiRequest request = new GeminiRequest();

        boolean forceUnary =
                policy.applyForceUnaryForStructuredOutput("gemini-3-pro", request, null);

        assertFalse(forceUnary);
        assertNull(request.getGenerationConfig());
    }

    private static ToolSchema structuredOutputTool() {
        return ToolSchema.builder()
                .name(StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME)
                .description("Structured output tool")
                .parameters(Map.of("type", "object", "properties", Map.of()))
                .build();
    }

    private static ToolSchema simpleTool(String name) {
        return ToolSchema.builder()
                .name(name)
                .description("Simple tool")
                .parameters(Map.of("type", "object", "properties", Map.of()))
                .build();
    }
}
