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
 *   <li>Provider-specific tool_choice compatibility handling
 * </ul>
 *
 * <p>Provider Compatibility:
 * <ul>
 *   <li>OpenAI: Full support (auto, none, required, specific)</li>
 *   <li>Anthropic: Full support with different format</li>
 *   <li>Gemini: Partial support (no specific)</li>
 *   <li>GLM: Limited support (auto only)</li>
 * </ul>
 *
 * @see ProviderCapability
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

        // Check if this is a reasoning model (has fixed sampling parameters)
        // Reasoning models like DeepSeek R1, OpenAI o1 don't accept temperature, top_p, penalties
        String model = request.getModel();
        String baseUrl = options != null ? options.getBaseUrl() : null;
        if (baseUrl == null && defaultOptions != null) {
            baseUrl = defaultOptions.getBaseUrl();
        }
        boolean isReasoningModel = ProviderCapability.isReasoningModel(model, baseUrl);

        if (!isReasoningModel) {
            // Apply temperature
            Double temperature =
                    getOptionOrDefault(options, defaultOptions, GenerateOptions::getTemperature);
            if (temperature != null) {
                request.setTemperature(temperature);
            }

            // Apply top_p
            Double topP = getOptionOrDefault(options, defaultOptions, GenerateOptions::getTopP);
            if (topP != null) {
                request.setTopP(topP);
            }

            // Apply frequency penalty
            Double frequencyPenalty =
                    getOptionOrDefault(
                            options, defaultOptions, GenerateOptions::getFrequencyPenalty);
            if (frequencyPenalty != null) {
                request.setFrequencyPenalty(frequencyPenalty);
            }

            // Apply presence penalty
            Double presencePenalty =
                    getOptionOrDefault(
                            options, defaultOptions, GenerateOptions::getPresencePenalty);
            if (presencePenalty != null) {
                request.setPresencePenalty(presencePenalty);
            }
        }

        // Apply max tokens (applies to all models including reasoning models)
        Integer maxTokens =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getMaxTokens);
        if (maxTokens != null) {
            // For reasoning models, only use max_tokens, not max_completion_tokens
            if (!isReasoningModel) {
                request.setMaxCompletionTokens(maxTokens);
            }
            // Some providers still expect the legacy max_tokens field
            request.setMaxTokens(maxTokens);
        } else if (isReasoningModel) {
            // Reasoning models require max_tokens to be set
            // DeepSeek R1 has a limit of 4092, use safe default
            request.setMaxTokens(4000);
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
                    case "include_reasoning":
                        if (value instanceof Boolean) {
                            request.setIncludeReasoning((Boolean) value);
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
                        // Add unknown parameters to extraParams
                        request.addExtraParam(key, value);
                        log.debug("Additional body parameter '{}' added to extraParams", key);
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
        return convertTools(tools, null);
    }

    /**
     * Convert tool schemas to OpenAI tool list with provider compatibility handling.
     *
     * @param tools List of tool schemas to convert (may be null or empty)
     * @param capability Provider capability for compatibility adjustments (null for default)
     * @return List of OpenAI tool DTOs
     */
    public List<OpenAITool> convertTools(List<ToolSchema> tools, ProviderCapability capability) {
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

                // Pass strict field only if provider supports it
                // GLM does not support the 'strict' parameter
                if (toolSchema.getStrict() != null
                        && (capability == null || capability.supportsStrictParameter())) {
                    functionBuilder.strict(toolSchema.getStrict());
                }

                OpenAIToolFunction function = functionBuilder.build();
                openAITools.add(OpenAITool.function(function));
                log.debug(
                        "Converted tool to OpenAI format: {} (strict: {}, capability: {})",
                        toolSchema.getName(),
                        toolSchema.getStrict(),
                        capability);
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
     * Apply tool schemas to OpenAI request DTO with provider capability handling.
     *
     * @param request OpenAI request DTO
     * @param tools List of tool schemas to apply (may be null or empty)
     * @param capability Provider capability for compatibility adjustments
     */
    public void applyTools(
            OpenAIRequest request, List<ToolSchema> tools, ProviderCapability capability) {
        List<OpenAITool> openAITools = convertTools(tools, capability);
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
        applyToolChoice(request, toolChoice, null, null);
    }

    /**
     * Apply tool choice configuration to OpenAI request DTO with provider compatibility handling.
     *
     * <p>This method detects the provider from baseUrl and adjusts tool_choice format
     * or falls back to "auto" when the provider doesn't support certain options.
     *
     * <p>Provider behavior:
     * <ul>
     *   <li>OpenAI: Full support (auto, none, required, specific)</li>
     *   <li>Anthropic: Full support with {"type": "tool", "name": "..."} format</li>
     *   <li>Gemini: auto, none, required (specific degraded to required)</li>
     *   <li>GLM: auto only (none/required/specific degraded to auto)</li>
     * </ul>
     *
     * @param request OpenAI request DTO
     * @param toolChoice Tool choice configuration (null means auto)
     * @param baseUrl API base URL for provider detection (null for default)
     * @param modelName Model name for provider detection fallback (null)
     */
    public void applyToolChoice(
            OpenAIRequest request, ToolChoice toolChoice, String baseUrl, String modelName) {

        // Only apply tool_choice if tools are present
        if (request.getTools() == null || request.getTools().isEmpty()) {
            return;
        }

        // Detect provider capability
        ProviderCapability capability = detectProvider(baseUrl, modelName);

        if (toolChoice == null || toolChoice instanceof ToolChoice.Auto) {
            request.setToolChoice("auto");
        } else if (toolChoice instanceof ToolChoice.None) {
            if (capability.supportsNone()) {
                request.setToolChoice("none");
            } else {
                logProviderFallback("none", capability);
                request.setToolChoice("auto");
            }
        } else if (toolChoice instanceof ToolChoice.Required) {
            if (capability.supportsRequired()) {
                request.setToolChoice("required");
            } else {
                logProviderFallback("required", capability);
                request.setToolChoice("auto");
            }
        } else if (toolChoice instanceof ToolChoice.Specific specific) {
            if (capability.supportsSpecific()) {
                applySpecificToolChoice(request, specific, capability);
            } else {
                logProviderFallback("specific tool choice", capability);
                // For providers like Gemini, degrade to "required" if supported
                if (capability.supportsRequired()) {
                    request.setToolChoice("required");
                    log.debug("Degraded specific tool_choice to 'required' for {}", capability);
                } else {
                    request.setToolChoice("auto");
                }
            }
        } else {
            // Fallback to auto for unknown types
            request.setToolChoice("auto");
        }

        log.debug(
                "Applied tool choice: {} (provider: {})",
                toolChoice != null ? toolChoice.getClass().getSimpleName() : "Auto",
                capability);
    }

    /**
     * Detect provider capability from baseUrl and modelName.
     *
     * @param baseUrl the base URL
     * @param modelName the model name
     * @return the detected provider capability
     */
    private ProviderCapability detectProvider(String baseUrl, String modelName) {
        ProviderCapability capability = ProviderCapability.fromUrl(baseUrl);
        if (capability == ProviderCapability.UNKNOWN && modelName != null) {
            capability = ProviderCapability.fromModelName(modelName);
        }
        return capability;
    }

    /**
     * Apply specific tool choice with provider-specific formatting.
     *
     * @param request the OpenAI request
     * @param specific the specific tool choice
     * @param capability the provider capability
     */
    private void applySpecificToolChoice(
            OpenAIRequest request, ToolChoice.Specific specific, ProviderCapability capability) {

        Map<String, Object> namedToolChoice = new HashMap<>();

        switch (capability) {
            case ANTHROPIC:
                // Anthropic uses {"type": "tool", "name": "..."}
                namedToolChoice.put("type", "tool");
                namedToolChoice.put("name", specific.toolName());
                break;
            case OPENAI:
            case DEEPSEEK:
            case DASHSCOPE:
            case UNKNOWN:
            default:
                // OpenAI format: {"type": "function", "function": {"name": "..."}}
                namedToolChoice.put("type", "function");
                Map<String, Object> function = new HashMap<>();
                function.put("name", specific.toolName());
                namedToolChoice.put("function", function);
                break;
        }

        request.setToolChoice(namedToolChoice);
    }

    /**
     * Log a warning when tool_choice is degraded due to provider limitations.
     *
     * @param requestedType the requested tool_choice type
     * @param capability the provider capability
     */
    private void logProviderFallback(String requestedType, ProviderCapability capability) {
        log.warn(
                "Provider {} does not support tool_choice='{}', degrading to 'auto'. For reliable"
                        + " behavior with this provider, avoid using forced tool choice.",
                capability,
                requestedType);
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
