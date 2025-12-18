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
package io.agentscope.core.formatter.openai;

import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAITool;
import io.agentscope.core.formatter.openai.dto.OpenAIToolFunction;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles tool registration and options application for OpenAI HTTP API.
 *
 * <p>This class provides utility methods for:
 * <ul>
 *   <li>Applying generation options to OpenAI request DTOs
 *   <li>Converting AgentScope tool schemas to OpenAI tool definitions
 * </ul>
 */
public class OpenAIToolsHelper {

    private static final Logger log = LoggerFactory.getLogger(OpenAIToolsHelper.class);

    /**
     * Apply GenerateOptions to OpenAI request DTO.
     *
     * @param request OpenAI request DTO
     * @param options Generation options to apply
     * @param defaultOptions Default options to use if options parameter is null
     */
    public void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {

        // Apply temperature
        Double temperature =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getTemperature);
        if (temperature != null) {
            request.setTemperature(temperature);
        }

        // Apply max tokens
        Integer maxTokens =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getMaxTokens);
        if (maxTokens != null) {
            request.setMaxCompletionTokens(maxTokens);
            // Some providers still expect the legacy max_tokens field
            request.setMaxTokens(maxTokens);
        }

        // Apply top_p
        Double topP = getOptionOrDefault(options, defaultOptions, GenerateOptions::getTopP);
        if (topP != null) {
            request.setTopP(topP);
        }

        // Apply frequency penalty
        Double frequencyPenalty =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getFrequencyPenalty);
        if (frequencyPenalty != null) {
            request.setFrequencyPenalty(frequencyPenalty);
        }

        // Apply presence penalty
        Double presencePenalty =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getPresencePenalty);
        if (presencePenalty != null) {
            request.setPresencePenalty(presencePenalty);
        }

        // Apply seed
        Long seed = getOptionOrDefault(options, defaultOptions, GenerateOptions::getSeed);
        if (seed != null) {
            if (seed < Integer.MIN_VALUE || seed > Integer.MAX_VALUE) {
                log.warn("Seed value {} is out of Integer range, will be truncated", seed);
            }
            request.setSeed(seed.intValue());
        }

        // Apply additional body params (must be last to allow overriding)
        applyAdditionalBodyParams(request, defaultOptions);
        applyAdditionalBodyParams(request, options);
    }

    /**
     * Apply additional body parameters from GenerateOptions to OpenAI request.
     * This handles parameters like reasoning_effort that are set via additionalBodyParam().
     */
    private void applyAdditionalBodyParams(OpenAIRequest request, GenerateOptions opts) {
        if (opts == null) return;
        Map<String, Object> params = opts.getAdditionalBodyParams();
        if (params != null && !params.isEmpty()) {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                // Map common parameter names to OpenAIRequest setters
                switch (key) {
                    case "reasoning_effort":
                        if (value instanceof String) {
                            request.setReasoningEffort((String) value);
                        }
                        break;
                    case "stop":
                        if (value instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<String> stopList = (List<String>) value;
                            request.setStop(stopList);
                        }
                        break;
                    case "response_format":
                        if (value instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> formatMap = (Map<String, Object>) value;
                            request.setResponseFormat(formatMap);
                        }
                        break;
                    default:
                        log.info(
                                "Additional body parameter '{}' is not mapped to any OpenAIRequest"
                                        + " field, will be ignored",
                                key);
                        break;
                }
            }
            log.debug("Applied {} additional body params to OpenAI request", params.size());
        }
    }

    /**
     * Get option value with fallback to default.
     */
    private <T> T getOptionOrDefault(
            GenerateOptions options,
            GenerateOptions defaultOptions,
            java.util.function.Function<GenerateOptions, T> getter) {
        T value = options != null ? getter.apply(options) : null;
        if (value == null && defaultOptions != null) {
            value = getter.apply(defaultOptions);
        }
        return value;
    }

    /**
     * Convert tool schemas to OpenAI tool list.
     *
     * @param tools List of tool schemas to convert (may be null or empty)
     * @return List of OpenAI tool DTOs
     */
    public List<OpenAITool> convertTools(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }

        List<OpenAITool> openAITools = new ArrayList<>();

        try {
            for (ToolSchema toolSchema : tools) {
                OpenAIToolFunction.Builder functionBuilder =
                        OpenAIToolFunction.builder()
                                .name(toolSchema.getName())
                                .description(toolSchema.getDescription())
                                .parameters(toolSchema.getParameters());

                // Pass strict field if present
                if (toolSchema.getStrict() != null) {
                    functionBuilder.strict(toolSchema.getStrict());
                }

                OpenAIToolFunction function = functionBuilder.build();
                openAITools.add(OpenAITool.function(function));
                log.debug(
                        "Converted tool to OpenAI format: {} (strict: {})",
                        toolSchema.getName(),
                        toolSchema.getStrict());
            }
        } catch (Exception e) {
            log.error("Failed to convert tools to OpenAI format: {}", e.getMessage(), e);
        }

        return openAITools;
    }

    /**
     * Apply tool schemas to OpenAI request DTO.
     *
     * @param request OpenAI request DTO
     * @param tools List of tool schemas to apply (may be null or empty)
     */
    public void applyTools(OpenAIRequest request, List<ToolSchema> tools) {
        List<OpenAITool> openAITools = convertTools(tools);
        if (openAITools != null && !openAITools.isEmpty()) {
            request.setTools(openAITools);
        }
    }

    /**
     * Apply tool choice configuration to OpenAI request DTO.
     *
     * @param request OpenAI request DTO
     * @param toolChoice Tool choice configuration (null means auto)
     */
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        if (toolChoice == null || toolChoice instanceof ToolChoice.Auto) {
            request.setToolChoice("auto");
        } else if (toolChoice instanceof ToolChoice.None) {
            request.setToolChoice("none");
        } else if (toolChoice instanceof ToolChoice.Required) {
            request.setToolChoice("required");
        } else if (toolChoice instanceof ToolChoice.Specific specific) {
            // Force specific tool call
            Map<String, Object> namedToolChoice = new HashMap<>();
            namedToolChoice.put("type", "function");
            Map<String, Object> function = new HashMap<>();
            function.put("name", specific.toolName());
            namedToolChoice.put("function", function);
            request.setToolChoice(namedToolChoice);
        } else {
            // Fallback to auto for unknown types
            request.setToolChoice("auto");
        }

        log.debug(
                "Applied tool choice: {}",
                toolChoice != null ? toolChoice.getClass().getSimpleName() : "Auto");
    }

    /**
     * Apply reasoning effort configuration to OpenAI request DTO.
     * This is used for o1 and other reasoning models.
     *
     * @param request OpenAI request DTO
     * @param reasoningEffort Reasoning effort level ("low", "medium", "high")
     */
    public void applyReasoningEffort(OpenAIRequest request, String reasoningEffort) {
        if (reasoningEffort != null && !reasoningEffort.isEmpty()) {
            request.setReasoningEffort(reasoningEffort);
            log.debug("Applied reasoning effort: {}", reasoningEffort);
        }
    }
}
