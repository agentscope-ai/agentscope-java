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
package io.agentscope.core.formatter.dashscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.formatter.dashscope.dto.DashScopeFunction;
import io.agentscope.core.formatter.dashscope.dto.DashScopeParameters;
import io.agentscope.core.formatter.dashscope.dto.DashScopeTool;
import io.agentscope.core.formatter.dashscope.dto.DashScopeToolCall;
import io.agentscope.core.formatter.dashscope.dto.DashScopeToolFunction;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles tool registration and options application for DashScope API.
 *
 * <p>This class converts AgentScope tool schemas and options to DashScope DTO format.
 */
public class DashScopeToolsHelper {

    private static final Logger log = LoggerFactory.getLogger(DashScopeToolsHelper.class);

    private final ObjectMapper objectMapper;

    public DashScopeToolsHelper() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Apply GenerateOptions to DashScopeParameters.
     *
     * @param params DashScope parameters to modify
     * @param options Generation options to apply
     * @param defaultOptions Default options to use if options parameter is null
     */
    public void applyOptions(
            DashScopeParameters params, GenerateOptions options, GenerateOptions defaultOptions) {
        Double temperature = getOption(options, defaultOptions, GenerateOptions::getTemperature);
        if (temperature != null) {
            params.setTemperature(temperature);
        }

        Double topP = getOption(options, defaultOptions, GenerateOptions::getTopP);
        if (topP != null) {
            params.setTopP(topP);
        }

        Integer maxTokens = getOption(options, defaultOptions, GenerateOptions::getMaxTokens);
        if (maxTokens != null) {
            params.setMaxTokens(maxTokens);
        }

        Integer thinkingBudget =
                getOption(options, defaultOptions, GenerateOptions::getThinkingBudget);
        if (thinkingBudget != null) {
            params.setThinkingBudget(thinkingBudget);
            params.setEnableThinking(true);
        }

        Integer topK = getOption(options, defaultOptions, GenerateOptions::getTopK);
        if (topK != null) {
            params.setTopK(topK);
        }

        Long seed = getOption(options, defaultOptions, GenerateOptions::getSeed);
        if (seed != null) {
            params.setSeed(seed.intValue());
        }

        Double frequencyPenalty =
                getOption(options, defaultOptions, GenerateOptions::getFrequencyPenalty);
        if (frequencyPenalty != null) {
            params.setFrequencyPenalty(frequencyPenalty);
        }

        Double presencePenalty =
                getOption(options, defaultOptions, GenerateOptions::getPresencePenalty);
        if (presencePenalty != null) {
            params.setPresencePenalty(presencePenalty);
        }
    }

    /**
     * Helper method to get option value with fallback to default.
     */
    private <T> T getOption(
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Function<GenerateOptions, T> getter) {
        if (options != null) {
            T value = getter.apply(options);
            if (value != null) {
                return value;
            }
        }
        if (defaultOptions != null) {
            return getter.apply(defaultOptions);
        }
        return null;
    }

    /**
     * Convert tool schemas to DashScope tool list.
     *
     * @param tools List of tool schemas to convert (may be null or empty)
     * @return List of DashScopeTool objects
     */
    public List<DashScopeTool> convertTools(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }

        List<DashScopeTool> result = new ArrayList<>();
        for (ToolSchema t : tools) {
            Map<String, Object> parameters = new HashMap<>();
            if (t.getParameters() != null) {
                parameters.putAll(t.getParameters());
            }

            DashScopeToolFunction function =
                    DashScopeToolFunction.builder()
                            .name(t.getName())
                            .description(t.getDescription())
                            .parameters(parameters)
                            .build();

            result.add(DashScopeTool.function(function));
        }

        log.debug("Converted {} tools to DashScope format", result.size());
        return result;
    }

    /**
     * Apply tools to DashScopeParameters.
     *
     * @param params DashScope parameters to modify
     * @param tools List of tool schemas to apply (may be null or empty)
     */
    public void applyTools(DashScopeParameters params, List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        params.setTools(convertTools(tools));
    }

    /**
     * Convert tool choice to DashScope format.
     *
     * <p>DashScope API supports:
     * <ul>
     *   <li>String "auto": model decides whether to call tools (default)</li>
     *   <li>String "none": disable tool calling</li>
     *   <li>Object {"type": "function", "function": {"name": "tool_name"}}: force specific tool</li>
     * </ul>
     *
     * @param toolChoice The tool choice configuration (null means auto/default)
     * @return The converted tool choice object
     */
    public Object convertToolChoice(ToolChoice toolChoice) {
        if (toolChoice == null) {
            return null;
        }

        if (toolChoice instanceof ToolChoice.Auto) {
            log.debug("ToolChoice.Auto: returning 'auto'");
            return "auto";
        } else if (toolChoice instanceof ToolChoice.None) {
            log.debug("ToolChoice.None: returning 'none'");
            return "none";
        } else if (toolChoice instanceof ToolChoice.Required) {
            log.warn(
                    "ToolChoice.Required is not directly supported by DashScope API. Using 'auto'"
                            + " instead.");
            return "auto";
        } else if (toolChoice instanceof ToolChoice.Specific specific) {
            log.debug("ToolChoice.Specific: forcing tool '{}'", specific.toolName());
            Map<String, Object> choice = new HashMap<>();
            choice.put("type", "function");
            Map<String, String> function = new HashMap<>();
            function.put("name", specific.toolName());
            choice.put("function", function);
            return choice;
        }

        return null;
    }

    /**
     * Apply tool choice configuration to DashScopeParameters.
     *
     * @param params DashScope parameters to modify
     * @param toolChoice The tool choice configuration (null means auto/default)
     */
    public void applyToolChoice(DashScopeParameters params, ToolChoice toolChoice) {
        Object choice = convertToolChoice(toolChoice);
        if (choice != null) {
            params.setToolChoice(choice);
        }
    }

    /**
     * Convert ToolUseBlock list to DashScope ToolCall format.
     *
     * @param toolBlocks The tool use blocks to convert
     * @return List of DashScopeToolCall objects (empty list if input is null/empty)
     */
    public List<DashScopeToolCall> convertToolCalls(List<ToolUseBlock> toolBlocks) {
        if (toolBlocks == null || toolBlocks.isEmpty()) {
            return List.of();
        }

        List<DashScopeToolCall> result = new ArrayList<>();

        for (ToolUseBlock toolUse : toolBlocks) {
            if (toolUse == null) {
                log.warn("Skipping null ToolUseBlock in convertToolCalls");
                continue;
            }

            String argsJson;
            try {
                argsJson = objectMapper.writeValueAsString(toolUse.getInput());
            } catch (Exception e) {
                log.warn("Failed to serialize tool call arguments: {}", e.getMessage());
                argsJson = "{}";
            }

            DashScopeFunction function = DashScopeFunction.of(toolUse.getName(), argsJson);
            DashScopeToolCall toolCall =
                    DashScopeToolCall.builder()
                            .id(toolUse.getId())
                            .type("function")
                            .function(function)
                            .build();

            result.add(toolCall);
        }

        return result;
    }

    /**
     * Merge additional headers from options and default options.
     *
     * <p>Default options are applied first, then options override.
     *
     * @param options the primary options (higher priority)
     * @param defaultOptions the fallback options (lower priority)
     * @return merged headers map, or null if both are empty
     */
    public Map<String, String> mergeAdditionalHeaders(
            GenerateOptions options, GenerateOptions defaultOptions) {
        Map<String, String> result = new HashMap<>();

        if (defaultOptions != null && !defaultOptions.getAdditionalHeaders().isEmpty()) {
            result.putAll(defaultOptions.getAdditionalHeaders());
        }
        if (options != null && !options.getAdditionalHeaders().isEmpty()) {
            result.putAll(options.getAdditionalHeaders());
        }

        return result.isEmpty() ? null : result;
    }

    /**
     * Merge additional body parameters from options and default options.
     *
     * <p>Default options are applied first, then options override.
     *
     * @param options the primary options (higher priority)
     * @param defaultOptions the fallback options (lower priority)
     * @return merged body params map, or null if both are empty
     */
    public Map<String, Object> mergeAdditionalBodyParams(
            GenerateOptions options, GenerateOptions defaultOptions) {
        Map<String, Object> result = new HashMap<>();

        if (defaultOptions != null && !defaultOptions.getAdditionalBodyParams().isEmpty()) {
            result.putAll(defaultOptions.getAdditionalBodyParams());
        }
        if (options != null && !options.getAdditionalBodyParams().isEmpty()) {
            result.putAll(options.getAdditionalBodyParams());
        }

        return result.isEmpty() ? null : result;
    }

    /**
     * Merge additional query parameters from options and default options.
     *
     * <p>Default options are applied first, then options override.
     *
     * @param options the primary options (higher priority)
     * @param defaultOptions the fallback options (lower priority)
     * @return merged query params map, or null if both are empty
     */
    public Map<String, String> mergeAdditionalQueryParams(
            GenerateOptions options, GenerateOptions defaultOptions) {
        Map<String, String> result = new HashMap<>();

        if (defaultOptions != null && !defaultOptions.getAdditionalQueryParams().isEmpty()) {
            result.putAll(defaultOptions.getAdditionalQueryParams());
        }
        if (options != null && !options.getAdditionalQueryParams().isEmpty()) {
            result.putAll(options.getAdditionalQueryParams());
        }

        return result.isEmpty() ? null : result;
    }
}
