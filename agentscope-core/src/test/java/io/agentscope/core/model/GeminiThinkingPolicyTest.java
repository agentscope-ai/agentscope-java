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

        List<ToolSchema> tools = List.of(structuredOutputTool());

        boolean isStructuredOutput =
                policy.disableThinkingForGemini3FlashStructuredOutput(
                        "gemini-3-flash-preview", request, tools);

        assertTrue(isStructuredOutput);
        assertNotNull(request.getGenerationConfig());
        assertNull(request.getGenerationConfig().getThinkingConfig());
    }

    @Test
    @DisplayName("Should disable thoughts when Gemini 3 has non structured output tools")
    void testApplyGemini3CompatibilityPolicyWithNonStructuredTool() {
        GeminiRequest request = new GeminiRequest();
        List<ToolSchema> tools = List.of(simpleTool("search_web"));

        policy.applyGemini3CompatibilityPolicy("gemini-3-pro", request, tools, false);

        assertNotNull(request.getGenerationConfig());
        assertNotNull(request.getGenerationConfig().getThinkingConfig());
        assertFalse(request.getGenerationConfig().getThinkingConfig().getIncludeThoughts());
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
