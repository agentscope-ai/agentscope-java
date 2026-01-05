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
package io.agentscope.core.formatter.gemini;

import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.formatter.gemini.dto.GeminiContent;
import io.agentscope.core.formatter.gemini.dto.GeminiGenerationConfig;
import io.agentscope.core.formatter.gemini.dto.GeminiGenerationConfig.GeminiThinkingConfig;
import io.agentscope.core.formatter.gemini.dto.GeminiRequest;
import io.agentscope.core.formatter.gemini.dto.GeminiResponse;
import io.agentscope.core.formatter.gemini.dto.GeminiTool;
import io.agentscope.core.formatter.gemini.dto.GeminiToolConfig;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Formatter for Gemini Content Generation API.
 *
 * <p>
 * Converts between AgentScope Msg objects and Gemini API DTOs:
 * <ul>
 * <li>Msg → GeminiContent (request format)</li>
 * <li>GeminiResponse → ChatResponse (response parsing)</li>
 * <li>ToolSchema → GeminiTool (tool definitions)</li>
 * </ul>
 */
public class GeminiChatFormatter
        extends AbstractBaseFormatter<GeminiContent, GeminiResponse, GeminiRequest> {

    private final GeminiMessageConverter messageConverter;
    private final GeminiResponseParser responseParser;
    private final GeminiToolsHelper toolsHelper;
    private GeminiContent systemInstruction;

    /**
     * Creates a new GeminiChatFormatter with default converters and parsers.
     */
    public GeminiChatFormatter() {
        this.messageConverter = new GeminiMessageConverter();
        this.responseParser = new GeminiResponseParser();
        this.toolsHelper = new GeminiToolsHelper();
    }

    @Override
    protected List<GeminiContent> doFormat(List<Msg> msgs) {
        // Extract and store SYSTEM message separately
        systemInstruction = null;
        int startIndex = 0;

        if (!msgs.isEmpty() && msgs.get(0).getRole() == io.agentscope.core.message.MsgRole.SYSTEM) {
            systemInstruction = messageConverter.convertMessages(List.of(msgs.get(0))).get(0);
            startIndex = 1;
        }

        // Gemini API requires contents to start with "user" role
        // If first remaining message is ASSISTANT (from another agent), convert it to USER
        if (startIndex < msgs.size()
                && msgs.get(startIndex).getRole() == io.agentscope.core.message.MsgRole.ASSISTANT) {
            List<GeminiContent> result = new ArrayList<>();

            // Convert first ASSISTANT message to USER role for multi-agent compatibility
            GeminiContent userContent = new GeminiContent();
            userContent.setRole("user");
            userContent.setParts(
                    messageConverter
                            .convertMessages(List.of(msgs.get(startIndex)))
                            .get(0)
                            .getParts());
            result.add(userContent);

            // Add remaining messages
            if (startIndex + 1 < msgs.size()) {
                result.addAll(
                        messageConverter.convertMessages(
                                msgs.subList(startIndex + 1, msgs.size())));
            }

            return result;
        }

        // Return remaining messages (excluding SYSTEM)
        if (startIndex > 0 && startIndex < msgs.size()) {
            return messageConverter.convertMessages(msgs.subList(startIndex, msgs.size()));
        } else if (startIndex == 0) {
            return messageConverter.convertMessages(msgs);
        }

        return new ArrayList<>();
    }

    /**
     * Apply system instruction to the request if present.
     *
     * @param request The Gemini request to configure
     */
    public void applySystemInstruction(GeminiRequest request) {
        if (systemInstruction != null) {
            request.setSystemInstruction(systemInstruction);
        }
    }

    @Override
    public ChatResponse parseResponse(GeminiResponse response, Instant startTime) {
        return responseParser.parseResponse(response, startTime);
    }

    @Override
    public void applyOptions(
            GeminiRequest request, GenerateOptions options, GenerateOptions defaultOptions) {

        // Ensure generation config exists
        if (request.getGenerationConfig() == null) {
            request.setGenerationConfig(new GeminiGenerationConfig());
        }
        GeminiGenerationConfig config = request.getGenerationConfig();

        // Apply each option with fallback to defaultOptions
        applyDoubleOption(
                GenerateOptions::getTemperature, options, defaultOptions, config::setTemperature);

        applyDoubleOption(GenerateOptions::getTopP, options, defaultOptions, config::setTopP);

        // topK: Integer in GenerateOptions -> Double in GeminiGenerationConfig
        applyIntegerAsDoubleOption(
                GenerateOptions::getTopK, options, defaultOptions, config::setTopK);

        // seed: Long in GenerateOptions -> Integer in GeminiGenerationConfig
        applyLongAsIntegerOption(
                GenerateOptions::getSeed, options, defaultOptions, config::setSeed);

        applyIntegerOption(
                GenerateOptions::getMaxTokens, options, defaultOptions, config::setMaxOutputTokens);

        applyDoubleOption(
                GenerateOptions::getFrequencyPenalty,
                options,
                defaultOptions,
                config::setFrequencyPenalty);

        applyDoubleOption(
                GenerateOptions::getPresencePenalty,
                options,
                defaultOptions,
                config::setPresencePenalty);

        // Apply ThinkingConfig if either includeThoughts or thinkingBudget is set
        Integer thinkingBudget =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getThinkingBudget);

        if (thinkingBudget != null) {
            GeminiThinkingConfig thinkingConfig = new GeminiThinkingConfig();
            thinkingConfig.setIncludeThoughts(true);
            thinkingConfig.setThinkingBudget(thinkingBudget);
            config.setThinkingConfig(thinkingConfig);
        }
    }

    /**
     * Apply Double option with fallback logic.
     */
    private void applyDoubleOption(
            Function<GenerateOptions, Double> accessor,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Consumer<Double> setter) {

        Double value = getOptionOrDefault(options, defaultOptions, accessor);
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * Apply Integer option as Double with fallback logic.
     */
    private void applyIntegerAsDoubleOption(
            Function<GenerateOptions, Integer> accessor,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Consumer<Double> setter) {

        Integer value = getOptionOrDefault(options, defaultOptions, accessor);
        if (value != null) {
            setter.accept(value.doubleValue());
        }
    }

    /**
     * Apply Long option as Integer with fallback logic.
     */
    private void applyLongAsIntegerOption(
            Function<GenerateOptions, Long> accessor,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Consumer<Integer> setter) {

        Long value = getOptionOrDefault(options, defaultOptions, accessor);
        if (value != null) {
            setter.accept(value.intValue());
        }
    }

    /**
     * Apply Integer option with fallback logic.
     */
    private void applyIntegerOption(
            Function<GenerateOptions, Integer> accessor,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Consumer<Integer> setter) {

        Integer value = getOptionOrDefault(options, defaultOptions, accessor);
        if (value != null) {
            setter.accept(value);
        }
    }

    @Override
    public void applyTools(GeminiRequest request, List<ToolSchema> tools) {
        GeminiTool tool = toolsHelper.convertToGeminiTool(tools);
        if (tool != null) {
            // Gemini API expects a list of tools, typically one tool object containing
            // function declarations
            request.setTools(List.of(tool));
        }
    }

    @Override
    public void applyToolChoice(GeminiRequest request, ToolChoice toolChoice) {
        GeminiToolConfig toolConfig = toolsHelper.convertToolChoice(toolChoice);
        if (toolConfig != null) {
            request.setToolConfig(toolConfig);
        }
    }
}
