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
package io.agentscope.core.formatter.dashscope;

import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.tools.ToolFunction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles tool registration and options application for DashScope API.
 */
public class DashScopeToolsHelper {

    private static final Logger log = LoggerFactory.getLogger(DashScopeToolsHelper.class);

    private final Gson gson = new Gson(); // DashScope SDK requires Gson
    private final ObjectMapper objectMapper;

    public DashScopeToolsHelper() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Apply GenerateOptions to DashScope GenerationParam.
     *
     * @param param DashScope generation parameters
     * @param options Generation options to apply
     * @param defaultOptions Default options to use if options parameter is null
     * @param optionGetter Function to get option value with fallback
     */
    public void applyOptions(
            GenerationParam param,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Function<Function<GenerateOptions, ?>, ?> optionGetter) {
        // Apply each option individually, falling back to defaultOptions if the specific field is
        // null
        applyDoubleOption(
                optionGetter,
                GenerateOptions::getTemperature,
                defaultOptions,
                value -> param.setTemperature(value.floatValue()));

        applyDoubleOption(optionGetter, GenerateOptions::getTopP, defaultOptions, param::setTopP);

        applyIntegerOption(
                optionGetter, GenerateOptions::getMaxTokens, defaultOptions, param::setMaxTokens);

        applyIntegerOption(
                optionGetter,
                GenerateOptions::getThinkingBudget,
                defaultOptions,
                param::setThinkingBudget);
    }

    /**
     * Helper method to apply Double option with fallback logic.
     */
    private void applyDoubleOption(
            Function<Function<GenerateOptions, ?>, ?> optionGetter,
            Function<GenerateOptions, Double> accessor,
            GenerateOptions defaultOptions,
            Consumer<Double> setter) {
        Double value =
                (Double)
                        optionGetter.apply(
                                opts ->
                                        opts != null
                                                ? accessor.apply(opts)
                                                : (defaultOptions != null
                                                        ? accessor.apply(defaultOptions)
                                                        : null));
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * Helper method to apply Integer option with fallback logic.
     */
    private void applyIntegerOption(
            Function<Function<GenerateOptions, ?>, ?> optionGetter,
            Function<GenerateOptions, Integer> accessor,
            GenerateOptions defaultOptions,
            Consumer<Integer> setter) {
        Integer value =
                (Integer)
                        optionGetter.apply(
                                opts ->
                                        opts != null
                                                ? accessor.apply(opts)
                                                : (defaultOptions != null
                                                        ? accessor.apply(defaultOptions)
                                                        : null));
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * Apply tool schemas to DashScope GenerationParam.
     *
     * @param param DashScope generation parameters
     * @param tools List of tool schemas to apply (may be null or empty)
     */
    public void applyTools(GenerationParam param, List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }

        List<ToolBase> toolList = new ArrayList<>();
        for (ToolSchema t : tools) {
            FunctionDefinition.FunctionDefinitionBuilder<?, ?> fdb = FunctionDefinition.builder();
            if (t.getName() != null) fdb.name(t.getName());
            if (t.getDescription() != null) fdb.description(t.getDescription());
            if (t.getParameters() != null) {
                // Must use Gson here because DashScope SDK's FunctionDefinition.parameters()
                // specifically requires com.google.gson.JsonObject type
                JsonElement el = gson.toJsonTree(t.getParameters());
                if (el != null && el.isJsonObject()) {
                    fdb.parameters(el.getAsJsonObject());
                } else {
                    fdb.parameters(new JsonObject());
                }
            }
            FunctionDefinition fd = fdb.build();
            ToolFunction toolFn = ToolFunction.builder().type("function").function(fd).build();
            toolList.add(toolFn);
        }
        param.setTools(toolList);
        log.debug("DashScope tools registered: {}", toolList.size());
    }

    /**
     * Apply tool choice configuration to DashScope GenerationParam.
     *
     * <p>DashScope API supports: - String "auto": model decides whether to call tools (default) -
     * String "none": disable tool calling - Object {"type": "function", "function": {"name":
     * "tool_name"}}: force specific tool
     *
     * @param param DashScope generation parameters
     * @param toolChoice The tool choice configuration (null means auto/default)
     */
    public void applyToolChoice(GenerationParam param, ToolChoice toolChoice) {
        if (toolChoice == null) {
            // Null means use default (auto)
            return;
        }

        try {
            if (toolChoice instanceof ToolChoice.Auto) {
                // Set explicit "auto" string
                param.setToolChoice("auto");
                log.debug("ToolChoice.Auto: set tool_choice to 'auto'");
            } else if (toolChoice instanceof ToolChoice.None) {
                // Set "none" string to disable tools
                param.setToolChoice("none");
                log.debug("ToolChoice.None: set tool_choice to 'none'");
            } else if (toolChoice instanceof ToolChoice.Required) {
                // DashScope doesn't have explicit "required" mode
                // Log warning as this is not directly supported
                log.warn(
                        "ToolChoice.Required is not directly supported by DashScope API. Using"
                                + " 'auto' instead. The model may still choose not to call"
                                + " tools.");
                param.setToolChoice("auto");
            } else if (toolChoice instanceof ToolChoice.Specific specific) {
                // Force specific tool call using ToolFunction object
                // Format: {"type": "function", "function": {"name": "tool_name"}}
                ToolFunction toolFunction =
                        ToolFunction.builder()
                                .function(
                                        FunctionDefinition.builder()
                                                .name(specific.toolName())
                                                .build())
                                .build();
                param.setToolChoice(toolFunction);
                log.debug(
                        "ToolChoice.Specific: set tool_choice to force tool '{}'",
                        specific.toolName());
            }
        } catch (Exception e) {
            log.error(
                    "Failed to apply tool choice configuration to DashScope: {}",
                    e.getMessage(),
                    e);
        }
    }

    /**
     * Convert ToolUseBlock list to DashScope ToolCallBase format.
     *
     * @param toolBlocks The tool use blocks to convert
     * @return List of ToolCallBase objects for DashScope API (empty list if input is null/empty)
     */
    public List<ToolCallBase> convertToolCalls(List<ToolUseBlock> toolBlocks) {
        if (toolBlocks == null || toolBlocks.isEmpty()) {
            return List.of();
        }

        List<ToolCallBase> result = new ArrayList<>();

        for (ToolUseBlock toolUse : toolBlocks) {
            if (toolUse == null) {
                log.warn("Skipping null ToolUseBlock in convertToolCalls");
                continue;
            }
            ToolCallFunction tcf = new ToolCallFunction();
            tcf.setId(toolUse.getId());

            // Create CallFunction as inner class instance
            ToolCallFunction.CallFunction cf = tcf.new CallFunction();
            cf.setName(toolUse.getName());

            // Convert arguments map to JSON string
            // Use Python-compatible format with space after colon
            try {
                String argsJson = objectMapper.writeValueAsString(toolUse.getInput());
                // Add space after colon to match Python's json.dumps default format
                argsJson = argsJson.replaceAll("\":\"", "\": \"").replaceAll("\":(\\d)", "\": $1");
                cf.setArguments(argsJson);
            } catch (Exception e) {
                log.warn("Failed to serialize tool call arguments: {}", e.getMessage());
                cf.setArguments("{}");
            }

            tcf.setFunction(cf);
            result.add(tcf);
        }

        return result;
    }

    /**
     * Apply tool choice configuration to MultiModalConversationParam.
     *
     * <p>MultiModalConversation API supports the same tool_choice options as Generation API: -
     * String "auto": model decides whether to call tools (default) - String "none": disable tool
     * calling - Object {"type": "function", "function": {"name": "tool_name"}}: force specific tool
     *
     * @param param MultiModalConversation parameters
     * @param toolChoice The tool choice configuration (null means auto/default)
     */
    public void applyToolChoice(MultiModalConversationParam param, ToolChoice toolChoice) {
        if (toolChoice == null) {
            // Null means use default (auto)
            return;
        }

        try {
            if (toolChoice instanceof ToolChoice.Auto) {
                // Set explicit "auto" string
                param.setToolChoice("auto");
                log.debug("ToolChoice.Auto: set tool_choice to 'auto' for MultiModal");
            } else if (toolChoice instanceof ToolChoice.None) {
                // Set "none" string to disable tools
                param.setToolChoice("none");
                log.debug("ToolChoice.None: set tool_choice to 'none' for MultiModal");
            } else if (toolChoice instanceof ToolChoice.Required) {
                // DashScope doesn't have explicit "required" mode
                // Log warning as this is not directly supported
                log.warn(
                        "ToolChoice.Required is not directly supported by DashScope MultiModal"
                                + " API. Using 'auto' instead. The model may still choose not to"
                                + " call tools.");
                param.setToolChoice("auto");
            } else if (toolChoice instanceof ToolChoice.Specific specific) {
                // Force specific tool call using ToolFunction object
                // Format: {"type": "function", "function": {"name": "tool_name"}}
                ToolFunction toolFunction =
                        ToolFunction.builder()
                                .function(
                                        FunctionDefinition.builder()
                                                .name(specific.toolName())
                                                .build())
                                .build();
                param.setToolChoice(toolFunction);
                log.debug(
                        "ToolChoice.Specific: set tool_choice to force tool '{}' for MultiModal",
                        specific.toolName());
            }
        } catch (Exception e) {
            log.error(
                    "Failed to apply tool choice configuration to DashScope MultiModal: {}",
                    e.getMessage(),
                    e);
        }
    }
}
