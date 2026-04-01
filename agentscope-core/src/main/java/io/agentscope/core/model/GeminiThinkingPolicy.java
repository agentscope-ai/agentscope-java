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

import io.agentscope.core.agent.StructuredOutputCapableAgent;
import io.agentscope.core.formatter.gemini.dto.GeminiGenerationConfig;
import io.agentscope.core.formatter.gemini.dto.GeminiGenerationConfig.GeminiThinkingConfig;
import io.agentscope.core.formatter.gemini.dto.GeminiRequest;
import java.util.List;

/**
 * Applies Gemini model-specific thinking configuration policies.
 *
 * <p>This class extracts compatibility and structured-output thinking behavior
 * out of {@link GeminiChatModel} while preserving existing logic.
 */
final class GeminiThinkingPolicy {

    GeminiThinkingPolicy() {}

    /**
     * For Gemini 3 Flash with structured output, explicitly disable thinking
     * after options application.
     *
     */
    void disableThinkingForGemini3FlashStructuredOutput(
            String modelName, GeminiRequest requestDto, List<ToolSchema> tools) {
        if (modelName.toLowerCase().contains("gemini-3-flash") && tools != null) {
            for (ToolSchema tool : tools) {
                if (StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME.equals(
                        tool.getName())) {
                    GeminiGenerationConfig genConfig = requestDto.getGenerationConfig();
                    if (genConfig == null) {
                        genConfig = new GeminiGenerationConfig();
                        requestDto.setGenerationConfig(genConfig);
                    }
                    GeminiThinkingConfig thinkingConfig = new GeminiThinkingConfig();
                    thinkingConfig.setThinkingBudget(0);
                    genConfig.setThinkingConfig(thinkingConfig);
                    break;
                }
            }
        }
    }

    /**
     * Determine whether structured output should force unary endpoint and apply
     * additional Gemini 3 Flash thinking mitigation.
     */
    boolean applyForceUnaryForStructuredOutput(List<ToolSchema> tools) {
        boolean forceUnaryForStructuredOutput = false;
        if (tools != null) {
            for (ToolSchema tool : tools) {
                if (StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME.equals(
                        tool.getName())) {
                    forceUnaryForStructuredOutput = true;
                    break;
                }
            }
        }
        return forceUnaryForStructuredOutput;
    }
}
