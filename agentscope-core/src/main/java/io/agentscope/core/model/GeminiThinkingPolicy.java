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
import org.slf4j.Logger;

/**
 * Applies Gemini model-specific thinking configuration policies.
 *
 * <p>This class extracts compatibility and structured-output thinking behavior
 * out of {@link GeminiChatModel} while preserving existing logic.
 */
final class GeminiThinkingPolicy {

    private final Logger log;

    GeminiThinkingPolicy(Logger log) {
        this.log = log;
    }

    /**
     * For Gemini 3 Flash with structured output, remove thinkingConfig immediately
     * after options application.
     *
     * @return true if this is a Gemini 3 Flash structured-output request.
     */
    boolean disableThinkingForGemini3FlashStructuredOutput(
            String modelName, GeminiRequest requestDto, List<ToolSchema> tools) {
        boolean isGemini3FlashStructuredOutput = false;
        if (modelName.toLowerCase().contains("gemini-3-flash") && tools != null) {
            for (ToolSchema tool : tools) {
                if (StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME.equals(
                        tool.getName())) {
                    isGemini3FlashStructuredOutput = true;
                    GeminiGenerationConfig genConfig = requestDto.getGenerationConfig();
                    if (genConfig == null) {
                        genConfig = new GeminiGenerationConfig();
                        requestDto.setGenerationConfig(genConfig);
                    }
                    // CRITICAL: Set thinkingConfig to null to completely remove it.
                    // Setting includeThoughts=false doesn't work - we must remove the entire
                    // config.
                    genConfig.setThinkingConfig(null);
                    break;
                }
            }
        }
        return isGemini3FlashStructuredOutput;
    }

    /**
     * Apply Gemini 3 compatibility fix for thinking/tool-calling behavior.
     */
    void applyGemini3CompatibilityPolicy(
            String modelName,
            GeminiRequest requestDto,
            List<ToolSchema> tools,
            boolean isGemini3FlashStructuredOutput) {
        // Compatibility fix for Gemini 3 models.
        // Disable thinking mode when tools are present to avoid MALFORMED_FUNCTION_CALL.
        if (modelName.toLowerCase().contains("gemini-3") && !isGemini3FlashStructuredOutput) {

            // Check if there are non-structured-output tools.
            boolean hasNonStructuredOutputTools = false;
            if (tools != null && !tools.isEmpty()) {
                log.info("Tools present for Gemini 3, count: {}", tools.size());
                for (ToolSchema tool : tools) {
                    log.info("Tool name: {}", tool.getName());
                    if (!StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME.equals(
                            tool.getName())) {
                        hasNonStructuredOutputTools = true;
                        log.info("Found non-structured-output tool: {}", tool.getName());
                        break;
                    }
                }
            } else {
                log.info("No tools present for Gemini 3");
            }

            if (hasNonStructuredOutputTools) {
                // When non-structured-output tools are present, ensure genConfig exists and
                // completely disable thinking mode.
                GeminiGenerationConfig genConfig = requestDto.getGenerationConfig();
                if (genConfig == null) {
                    genConfig = new GeminiGenerationConfig();
                    requestDto.setGenerationConfig(genConfig);
                }
                // The combination of extended reasoning and tool calls causes
                // MALFORMED_FUNCTION_CALL API errors in Gemini 3.
                log.info(
                        "Disabling thinking mode for Gemini 3 model when"
                                + " non-structured-output tools are present");
                GeminiThinkingConfig thinkingConfig = new GeminiThinkingConfig();
                thinkingConfig.setIncludeThoughts(false);
                genConfig.setThinkingConfig(thinkingConfig);
            } else {
                // For structured output or non-tool requests, adjust thinking config.
                // BUT: Don't enable thinking for Gemini 3 Flash with structured output
                // as it causes tool hallucination.
                boolean isGemini3Flash = modelName.toLowerCase().contains("gemini-3-flash");
                boolean hasStructuredOutputTool = false;
                if (tools != null) {
                    for (ToolSchema tool : tools) {
                        if (StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME.equals(
                                tool.getName())) {
                            hasStructuredOutputTool = true;
                            break;
                        }
                    }
                }

                if (isGemini3Flash && hasStructuredOutputTool) {
                    log.info(
                            "Disabling thinking config for Gemini 3 Flash"
                                    + " structured output to avoid tool hallucination");
                    // CRITICAL: Actively disable thinking for Gemini 3 Flash structured output.
                    GeminiGenerationConfig genConfig = requestDto.getGenerationConfig();
                    if (genConfig == null) {
                        genConfig = new GeminiGenerationConfig();
                        requestDto.setGenerationConfig(genConfig);
                    }
                    GeminiThinkingConfig thinkingConfig = new GeminiThinkingConfig();
                    thinkingConfig.setIncludeThoughts(false);
                    thinkingConfig.setThinkingBudget(null);
                    genConfig.setThinkingConfig(thinkingConfig);
                } else {
                    log.info(
                            "Adjusting thinking config for Gemini 3 (structured output or no"
                                    + " tools)");
                    GeminiGenerationConfig genConfig = requestDto.getGenerationConfig();
                    log.info("Current genConfig: {}", genConfig);
                    if (genConfig != null) {
                        GeminiThinkingConfig thinkingConfig = genConfig.getThinkingConfig();
                        log.info("Current thinkingConfig: {}", thinkingConfig);
                        if (thinkingConfig != null) {
                            if (thinkingConfig.getThinkingBudget() != null) {
                                log.info(
                                        "Removing thinkingBudget for Gemini"
                                                + " 3 model compatibility");
                                thinkingConfig.setThinkingBudget(null);
                            }
                            thinkingConfig.setIncludeThoughts(true);
                            log.info("Set includeThoughts=true for Gemini 3");
                        } else {
                            log.warn("thinkingConfig is null, cannot enable thinking mode");
                        }
                    } else {
                        log.warn("genConfig is null, cannot adjust thinking config");
                    }
                }
            }
        }
    }

    /**
     * Determine whether structured output should force unary endpoint and apply
     * additional Gemini 3 Flash thinking mitigation.
     */
    boolean applyForceUnaryForStructuredOutput(
            String modelName, GeminiRequest requestDto, List<ToolSchema> tools) {
        boolean forceUnaryForStructuredOutput = false;
        if (tools != null) {
            for (ToolSchema tool : tools) {
                if (StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME.equals(
                        tool.getName())) {
                    forceUnaryForStructuredOutput = true;

                    // CRITICAL FIX: Gemini 3 Flash has a known issue where thinking mode
                    // causes it to hallucinate tool names instead of using generate_response.
                    if (modelName.toLowerCase().contains("gemini-3-flash")) {
                        GeminiGenerationConfig genConfig = requestDto.getGenerationConfig();
                        if (genConfig == null) {
                            genConfig = new GeminiGenerationConfig();
                            requestDto.setGenerationConfig(genConfig);
                        }
                        GeminiThinkingConfig thinkingConfig = new GeminiThinkingConfig();
                        thinkingConfig.setIncludeThoughts(false);
                        genConfig.setThinkingConfig(thinkingConfig);
                    }
                    break;
                }
            }
        }
        return forceUnaryForStructuredOutput;
    }
}
