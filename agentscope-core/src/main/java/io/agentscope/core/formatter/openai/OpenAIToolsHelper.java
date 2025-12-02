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

import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionNamedToolChoice;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles tool registration and options application for OpenAI API.
 *
 * <p>This class provides utility methods for:
 * <ul>
 *   <li>Applying generation options to OpenAI request parameters
 *   <li>Converting AgentScope tool schemas to OpenAI tool definitions
 * </ul>
 */
public class OpenAIToolsHelper {

    private static final Logger log = LoggerFactory.getLogger(OpenAIToolsHelper.class);

    /**
     * Apply GenerateOptions to OpenAI ChatCompletionCreateParams.Builder.
     *
     * @param paramsBuilder OpenAI request parameters builder
     * @param options Generation options to apply
     * @param defaultOptions Default options to use if options parameter is null
     * @param optionGetter Function to get option value with fallback
     */
    public void applyOptions(
            ChatCompletionCreateParams.Builder paramsBuilder,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Function<Function<GenerateOptions, ?>, ?> optionGetter) {

        // Apply each option individually, falling back to defaultOptions if the specific field is
        // null
        applyDoubleOption(
                optionGetter,
                GenerateOptions::getTemperature,
                defaultOptions,
                paramsBuilder::temperature);

        applyIntegerOption(
                optionGetter,
                GenerateOptions::getMaxTokens,
                defaultOptions,
                value -> paramsBuilder.maxCompletionTokens(value.longValue()));

        applyDoubleOption(
                optionGetter, GenerateOptions::getTopP, defaultOptions, paramsBuilder::topP);

        applyDoubleOption(
                optionGetter,
                GenerateOptions::getFrequencyPenalty,
                defaultOptions,
                paramsBuilder::frequencyPenalty);

        applyDoubleOption(
                optionGetter,
                GenerateOptions::getPresencePenalty,
                defaultOptions,
                paramsBuilder::presencePenalty);

        // Apply seed parameter
        applyLongOption(
                optionGetter, GenerateOptions::getSeed, defaultOptions, paramsBuilder::seed);

        // Apply additional parameters
        GenerateOptions effectiveOptions = options != null ? options : defaultOptions;
        if (effectiveOptions != null) {
            // Apply additional headers
            Map<String, String> additionalHeaders = effectiveOptions.getAdditionalHeaders();
            if (additionalHeaders != null && !additionalHeaders.isEmpty()) {
                for (Map.Entry<String, String> entry : additionalHeaders.entrySet()) {
                    paramsBuilder.putAdditionalHeader(entry.getKey(), entry.getValue());
                }
                log.debug(
                        "Applied {} additional headers to OpenAI request",
                        additionalHeaders.size());
            }

            // Apply additional body params
            Map<String, Object> additionalBodyParams = effectiveOptions.getAdditionalBodyParams();
            if (additionalBodyParams != null && !additionalBodyParams.isEmpty()) {
                for (Map.Entry<String, Object> entry : additionalBodyParams.entrySet()) {
                    paramsBuilder.putAdditionalBodyProperty(
                            entry.getKey(), JsonValue.from(entry.getValue()));
                }
                log.debug(
                        "Applied {} additional body params to OpenAI request",
                        additionalBodyParams.size());
            }

            // Apply additional query params
            Map<String, String> additionalQueryParams = effectiveOptions.getAdditionalQueryParams();
            if (additionalQueryParams != null && !additionalQueryParams.isEmpty()) {
                for (Map.Entry<String, String> entry : additionalQueryParams.entrySet()) {
                    paramsBuilder.putAdditionalQueryParam(entry.getKey(), entry.getValue());
                }
                log.debug(
                        "Applied {} additional query params to OpenAI request",
                        additionalQueryParams.size());
            }
        }
    }

    /**
     * Helper method to apply Double option with fallback logic.
     */
    private void applyDoubleOption(
            Function<Function<GenerateOptions, ?>, ?> optionGetter,
            Function<GenerateOptions, Double> accessor,
            GenerateOptions defaultOptions,
            java.util.function.Consumer<Double> setter) {
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
            java.util.function.Consumer<Integer> setter) {
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
     * Helper method to apply Long option with fallback logic.
     */
    private void applyLongOption(
            Function<Function<GenerateOptions, ?>, ?> optionGetter,
            Function<GenerateOptions, Long> accessor,
            GenerateOptions defaultOptions,
            java.util.function.Consumer<Long> setter) {
        Long value =
                (Long)
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
     * Apply tool schemas to OpenAI ChatCompletionCreateParams.Builder.
     *
     * @param paramsBuilder OpenAI request parameters builder
     * @param tools List of tool schemas to apply (may be null or empty)
     */
    public void applyTools(
            ChatCompletionCreateParams.Builder paramsBuilder, List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }

        try {
            for (ToolSchema toolSchema : tools) {
                // Convert ToolSchema to OpenAI ChatCompletionTool
                // Create function definition first
                FunctionDefinition.Builder functionBuilder =
                        FunctionDefinition.builder().name(toolSchema.getName());

                if (toolSchema.getDescription() != null) {
                    functionBuilder.description(toolSchema.getDescription());
                }

                // Convert parameters map to proper format for OpenAI
                if (toolSchema.getParameters() != null) {
                    // Convert Map<String, Object> to FunctionParameters
                    FunctionParameters.Builder funcParamsBuilder = FunctionParameters.builder();
                    for (Map.Entry<String, Object> entry : toolSchema.getParameters().entrySet()) {
                        funcParamsBuilder.putAdditionalProperty(
                                entry.getKey(), JsonValue.from(entry.getValue()));
                    }
                    functionBuilder.parameters(funcParamsBuilder.build());
                }

                // Create ChatCompletionFunctionTool
                ChatCompletionFunctionTool functionTool =
                        ChatCompletionFunctionTool.builder()
                                .function(functionBuilder.build())
                                .build();

                // Create ChatCompletionTool
                ChatCompletionTool tool = ChatCompletionTool.ofFunction(functionTool);
                paramsBuilder.addTool(tool);

                log.debug("Added tool to OpenAI request: {}", toolSchema.getName());
            }

        } catch (Exception e) {
            log.error("Failed to add tools to OpenAI request: {}", e.getMessage(), e);
        }
    }

    /**
     * Apply tool choice configuration to OpenAI request parameters.
     *
     * @param paramsBuilder OpenAI request parameters builder
     * @param toolChoice Tool choice configuration (null means auto)
     */
    public void applyToolChoice(
            ChatCompletionCreateParams.Builder paramsBuilder, ToolChoice toolChoice) {
        if (toolChoice == null || toolChoice instanceof ToolChoice.Auto) {
            // Default to auto
            paramsBuilder.toolChoice(
                    ChatCompletionToolChoiceOption.ofAuto(
                            ChatCompletionToolChoiceOption.Auto.AUTO));
        } else if (toolChoice instanceof ToolChoice.None) {
            paramsBuilder.toolChoice(
                    ChatCompletionToolChoiceOption.ofAuto(
                            ChatCompletionToolChoiceOption.Auto.NONE));
        } else if (toolChoice instanceof ToolChoice.Required) {
            paramsBuilder.toolChoice(
                    ChatCompletionToolChoiceOption.ofAuto(
                            ChatCompletionToolChoiceOption.Auto.REQUIRED));
        } else if (toolChoice instanceof ToolChoice.Specific specific) {
            // Force specific tool call using ChatCompletionNamedToolChoice
            ChatCompletionNamedToolChoice namedToolChoice =
                    ChatCompletionNamedToolChoice.builder()
                            .function(
                                    ChatCompletionNamedToolChoice.Function.builder()
                                            .name(specific.toolName())
                                            .build())
                            .build();
            paramsBuilder.toolChoice(
                    ChatCompletionToolChoiceOption.ofNamedToolChoice(namedToolChoice));
        } else {
            // Fallback to auto for unknown types
            paramsBuilder.toolChoice(
                    ChatCompletionToolChoiceOption.ofAuto(
                            ChatCompletionToolChoiceOption.Auto.AUTO));
        }

        log.debug(
                "Applied tool choice: {}",
                toolChoice != null ? toolChoice.getClass().getSimpleName() : "Auto");
    }
}
