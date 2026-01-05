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

import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAITool;
import io.agentscope.core.formatter.openai.dto.OpenAIToolFunction;
import io.agentscope.core.message.Msg;
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
 * Formatter for OpenAI Chat Completion HTTP API.
 * Converts between AgentScope Msg objects and OpenAI DTO types.
 *
 * <p>This formatter is used with the HTTP-based OpenAI client for standard OpenAI GPT models.
 * It provides full support for:
 * <ul>
 *   <li>All sampling parameters (temperature, top_p, penalties)</li>
 *   <li>Tool calling with strict mode support</li>
 *   <li>Full tool_choice options (auto, none, required, specific)</li>
 * </ul>
 */
public class OpenAIChatFormatter extends OpenAIBaseFormatter {

    private static final Logger log = LoggerFactory.getLogger(OpenAIChatFormatter.class);

    public OpenAIChatFormatter() {
        super();
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        List<OpenAIMessage> result = new ArrayList<>();
        for (Msg msg : msgs) {
            boolean hasMedia = hasMediaContent(msg);
            OpenAIMessage openAIMsg = messageConverter.convertToMessage(msg, hasMedia);
            if (openAIMsg != null) {
                result.add(openAIMsg);
            }
        }
        return result;
    }

    @Override
    public void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {

        // Apply temperature
        Double temperature =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getTemperature);
        if (temperature != null) {
            request.setTemperature(temperature);
        }
        // Apply reasoning effort
        String reasoningEffort =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getReasoningEffort);
        if (reasoningEffort != null) {
            request.setReasoningEffort(reasoningEffort);
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

        // Apply max tokens
        Integer maxTokens =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getMaxTokens);
        if (maxTokens != null) {
            request.setMaxCompletionTokens(maxTokens);
            request.setMaxTokens(maxTokens);
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

    @Override
    public void applyTools(OpenAIRequest request, List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }

        List<OpenAITool> openAITools = new ArrayList<>();

        try {
            for (ToolSchema toolSchema : tools) {
                OpenAIToolFunction.Builder functionBuilder =
                        OpenAIToolFunction.builder()
                                .name(toolSchema.getName())
                                .description(toolSchema.getDescription())
                                .parameters(toolSchema.getParameters());

                // Only apply strict parameter if the provider supports it
                if (supportsStrict() && toolSchema.getStrict() != null) {
                    functionBuilder.strict(toolSchema.getStrict());
                }

                OpenAIToolFunction function = functionBuilder.build();
                openAITools.add(OpenAITool.function(function));
                log.debug(
                        "Converted tool to OpenAI format: {} (strict: {})",
                        toolSchema.getName(),
                        supportsStrict() ? toolSchema.getStrict() : "not supported");
            }
        } catch (Exception e) {
            log.error("Failed to convert tools to OpenAI format: {}", e.getMessage(), e);
        }

        if (!openAITools.isEmpty()) {
            request.setTools(openAITools);
        }
    }

    /**
     * Returns whether this formatter's target API supports the strict parameter in tool
     * definitions.
     *
     * <p>Subclasses can override this method to disable strict parameter for providers that don't
     * support it (e.g., DeepSeek).
     *
     * @return true if strict parameter is supported, false otherwise
     */
    protected boolean supportsStrict() {
        return true;
    }

    @Override
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        // Only apply tool_choice if tools are present
        if (request.getTools() == null || request.getTools().isEmpty()) {
            return;
        }

        if (toolChoice == null || toolChoice instanceof ToolChoice.Auto) {
            request.setToolChoice("auto");
        } else if (toolChoice instanceof ToolChoice.None) {
            request.setToolChoice("none");
        } else if (toolChoice instanceof ToolChoice.Required) {
            request.setToolChoice("required");
        } else if (toolChoice instanceof ToolChoice.Specific specific) {
            // OpenAI format: {"type": "function", "function": {"name": "..."}}
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
     * Apply additional body parameters from GenerateOptions to OpenAI request.
     * This handles parameters like reasoning_effort that are set via additionalBodyParam().
     */
    protected void applyAdditionalBodyParams(OpenAIRequest request, GenerateOptions opts) {
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
}
