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
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import io.agentscope.core.model.GenerateOptions;
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
        Double temperature =
                (Double)
                        optionGetter.apply(
                                opts ->
                                        opts != null
                                                ? opts.getTemperature()
                                                : (defaultOptions != null
                                                        ? defaultOptions.getTemperature()
                                                        : null));
        if (temperature != null) paramsBuilder.temperature(temperature);

        Integer maxTokens =
                (Integer)
                        optionGetter.apply(
                                opts ->
                                        opts != null
                                                ? opts.getMaxTokens()
                                                : (defaultOptions != null
                                                        ? defaultOptions.getMaxTokens()
                                                        : null));
        if (maxTokens != null) paramsBuilder.maxCompletionTokens(maxTokens.longValue());

        Double topP =
                (Double)
                        optionGetter.apply(
                                opts ->
                                        opts != null
                                                ? opts.getTopP()
                                                : (defaultOptions != null
                                                        ? defaultOptions.getTopP()
                                                        : null));
        if (topP != null) paramsBuilder.topP(topP);

        Double frequencyPenalty =
                (Double)
                        optionGetter.apply(
                                opts ->
                                        opts != null
                                                ? opts.getFrequencyPenalty()
                                                : (defaultOptions != null
                                                        ? defaultOptions.getFrequencyPenalty()
                                                        : null));
        if (frequencyPenalty != null) paramsBuilder.frequencyPenalty(frequencyPenalty);

        Double presencePenalty =
                (Double)
                        optionGetter.apply(
                                opts ->
                                        opts != null
                                                ? opts.getPresencePenalty()
                                                : (defaultOptions != null
                                                        ? defaultOptions.getPresencePenalty()
                                                        : null));
        if (presencePenalty != null) paramsBuilder.presencePenalty(presencePenalty);
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

            // Set tool choice to auto to allow the model to decide when to use tools
            paramsBuilder.toolChoice(
                    ChatCompletionToolChoiceOption.ofAuto(
                            ChatCompletionToolChoiceOption.Auto.AUTO));

        } catch (Exception e) {
            log.error("Failed to add tools to OpenAI request: {}", e.getMessage(), e);
        }
    }
}
