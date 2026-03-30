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

@Tag("unit")
@DisplayName("GeminiThinkingPolicy Unit Tests")
class GeminiThinkingPolicyTest {

    private final GeminiThinkingPolicy policy = new GeminiThinkingPolicy();

    @Test
    @DisplayName("Should disable thinking config for Gemini 3 Flash structured output")
    void testDisableThinkingForGemini3FlashStructuredOutput() {
        GeminiRequest request = new GeminiRequest();
        GeminiGenerationConfig config = new GeminiGenerationConfig();
        config.setThinkingConfig(new GeminiGenerationConfig.GeminiThinkingConfig());
        request.setGenerationConfig(config);

        policy.disableThinkingForGemini3FlashStructuredOutput(
                "gemini-3-flash-preview", request, List.of(structuredOutputTool()));

        assertNotNull(request.getGenerationConfig());
        assertNotNull(request.getGenerationConfig().getThinkingConfig());
        assertEquals(0, request.getGenerationConfig().getThinkingConfig().getThinkingBudget());
        assertNull(request.getGenerationConfig().getThinkingConfig().getIncludeThoughts());
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

        policy.disableThinkingForGemini3FlashStructuredOutput(
                "gemini-2.5-flash", request, List.of(structuredOutputTool()));

        assertSame(thinkingConfig, request.getGenerationConfig().getThinkingConfig());
    }

    @Test
    @DisplayName(
            "Should create generation config when disabling thinking for flash structured output")
    void testDisableThinkingCreatesGenerationConfigWhenMissing() {
        GeminiRequest request = new GeminiRequest();

        policy.disableThinkingForGemini3FlashStructuredOutput(
                "gemini-3-flash-preview", request, List.of(structuredOutputTool()));

        assertNotNull(request.getGenerationConfig());
        assertNotNull(request.getGenerationConfig().getThinkingConfig());
        assertEquals(0, request.getGenerationConfig().getThinkingConfig().getThinkingBudget());
        assertNull(request.getGenerationConfig().getThinkingConfig().getIncludeThoughts());
    }

    @Test
    @DisplayName(
            "Should force unary for structured output tool (thinking already handled by"
                    + " disableThinkingForGemini3FlashStructuredOutput)")
    void testApplyForceUnaryForStructuredOutput() {
        boolean forceUnary =
                policy.applyForceUnaryForStructuredOutput(List.of(structuredOutputTool()));

        assertTrue(forceUnary);
    }

    @Test
    @DisplayName("Should force unary without changing thinking config for non flash model")
    void testApplyForceUnaryForStructuredOutputWithNonFlashModel() {
        GeminiRequest request = new GeminiRequest();
        GeminiGenerationConfig generationConfig = new GeminiGenerationConfig();
        request.setGenerationConfig(generationConfig);

        boolean forceUnary =
                policy.applyForceUnaryForStructuredOutput(List.of(structuredOutputTool()));

        assertTrue(forceUnary);
        assertSame(generationConfig, request.getGenerationConfig());
        assertNull(request.getGenerationConfig().getThinkingConfig());
    }

    @Test
    @DisplayName("Should not force unary when structured output tool is absent")
    void testApplyForceUnaryForStructuredOutputWithoutStructuredTool() {
        GeminiRequest request = new GeminiRequest();

        boolean forceUnary =
                policy.applyForceUnaryForStructuredOutput(List.of(simpleTool("search")));

        assertFalse(forceUnary);
        assertNull(request.getGenerationConfig());
    }

    @Test
    @DisplayName("Should not force unary when tool list is null")
    void testApplyForceUnaryForStructuredOutputWithNullTools() {
        GeminiRequest request = new GeminiRequest();

        boolean forceUnary = policy.applyForceUnaryForStructuredOutput(null);

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
