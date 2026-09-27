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
package io.agentscope.extensions.model.gemini.formatter;

import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolConfig;
import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formatter for Gemini Content Generation API.
 *
 * <p>Converts between AgentScope Msg objects and Gemini SDK types:
 * <ul>
 *   <li>Msg → Content (request format)</li>
 *   <li>GenerateContentResponse → ChatResponse (response parsing)</li>
 *   <li>ToolSchema → Tool (tool definitions)</li>
 * </ul>
 *
 * <p><b>Important Gemini API Behaviors:</b>
 * <ul>
 *   <li>System messages are converted to "user" role (Gemini doesn't support system role in contents)</li>
 *   <li>Tool results are independent "user" role Content objects</li>
 *   <li>Thinking content uses the "thought" flag on Part objects</li>
 * </ul>
 */
public class GeminiChatFormatter
        extends AbstractBaseFormatter<
                Content, GenerateContentResponse, GenerateContentConfig.Builder> {

    private static final Logger log = LoggerFactory.getLogger(GeminiChatFormatter.class);

    /**
     * ThinkingLevel values accepted by the Gemini API (matching the SDK's ThinkingLevel enum).
     */
    private static final List<String> SUPPORTED_THINKING_LEVELS =
            List.of("minimal", "low", "medium", "high");

    private final GeminiMessageConverter messageConverter;
    private final GeminiResponseParser responseParser;
    private final GeminiToolsHelper toolsHelper;

    /**
     * Creates a new GeminiChatFormatter with default converters and parsers.
     */
    public GeminiChatFormatter() {
        this.messageConverter = new GeminiMessageConverter();
        this.responseParser = new GeminiResponseParser();
        this.toolsHelper = new GeminiToolsHelper();
    }

    @Override
    protected List<Content> doFormat(List<Msg> msgs) {
        return messageConverter.convertMessages(msgs);
    }

    @Override
    public ChatResponse parseResponse(GenerateContentResponse response, Instant startTime) {
        return responseParser.parseResponse(response, startTime);
    }

    @Override
    public void applyOptions(
            GenerateContentConfig.Builder configBuilder,
            GenerateOptions options,
            GenerateOptions defaultOptions) {

        // Apply each option with fallback to defaultOptions
        applyFloatOption(
                GenerateOptions::getTemperature,
                options,
                defaultOptions,
                configBuilder::temperature);

        applyFloatOption(GenerateOptions::getTopP, options, defaultOptions, configBuilder::topP);

        // Apply topK (Gemini uses Float for topK)
        applyIntegerAsFloatOption(
                GenerateOptions::getTopK, options, defaultOptions, configBuilder::topK);

        // Apply seed
        applyLongAsIntOption(
                GenerateOptions::getSeed, options, defaultOptions, configBuilder::seed);

        applyIntegerOption(
                GenerateOptions::getMaxTokens,
                options,
                defaultOptions,
                configBuilder::maxOutputTokens);

        applyFloatOption(
                GenerateOptions::getFrequencyPenalty,
                options,
                defaultOptions,
                configBuilder::frequencyPenalty);

        applyFloatOption(
                GenerateOptions::getPresencePenalty,
                options,
                defaultOptions,
                configBuilder::presencePenalty);

        // Apply ThinkingConfig when any thinking option is configured.
        Integer thinkingBudget =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getThinkingBudget);
        Boolean includeThoughts =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getIncludeThoughts);
        String thinkingLevel =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getThinkingLevel);

        String normalizedThinkingLevel =
                thinkingLevel == null ? null : normalizeThinkingLevel(thinkingLevel);

        if (thinkingBudget != null || includeThoughts != null || normalizedThinkingLevel != null) {
            ThinkingConfig.Builder thinkingConfigBuilder = ThinkingConfig.builder();

            if (includeThoughts != null) {
                thinkingConfigBuilder.includeThoughts(includeThoughts);
            } else if (thinkingBudget != null) {
                // Preserve the existing behavior for callers that only set thinkingBudget.
                thinkingConfigBuilder.includeThoughts(true);
            }
            if (thinkingBudget != null) {
                thinkingConfigBuilder.thinkingBudget(thinkingBudget);
            }
            if (normalizedThinkingLevel != null) {
                thinkingConfigBuilder.thinkingLevel(normalizedThinkingLevel);
            }

            configBuilder.thinkingConfig(thinkingConfigBuilder.build());
        }
    }

    /**
     * Normalizes a configured thinking level before it is forwarded to the Gemini API.
     *
     * <p>Trims surrounding whitespace and aligns the case of the values Gemini accepts
     * ({@code "minimal"}, {@code "low"}, {@code "medium"}, {@code "high"}), so inputs such as
     * {@code " Minimal "} or {@code "HIGH"} are sent in their accepted form instead of surfacing
     * as an opaque remote error. Values outside the known set are forwarded unchanged after a
     * warning, keeping the provider authoritative for newly added levels.
     *
     * @param thinkingLevel the configured thinking level (non-null)
     * @return the normalized level, or null if the value is blank and should be dropped
     */
    private String normalizeThinkingLevel(String thinkingLevel) {
        String trimmed = thinkingLevel.trim();
        if (trimmed.isEmpty()) {
            log.warn("Blank thinkingLevel configured, ignoring it");
            return null;
        }
        String lowercased = trimmed.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_THINKING_LEVELS.contains(lowercased)) {
            log.warn(
                    "Unknown thinkingLevel '{}'; Gemini accepts {}. Forwarding it as-is and the"
                            + " API may reject it",
                    trimmed,
                    SUPPORTED_THINKING_LEVELS);
            return trimmed;
        }
        return lowercased;
    }

    /**
     * Apply Float option with fallback logic.
     */
    private void applyFloatOption(
            Function<GenerateOptions, Double> accessor,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Consumer<Float> setter) {

        Double value = getOptionOrDefault(options, defaultOptions, accessor);
        if (value != null) {
            setter.accept(value.floatValue());
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

    /**
     * Apply Integer option as Float with fallback logic (for Gemini topK which requires Float).
     */
    private void applyIntegerAsFloatOption(
            Function<GenerateOptions, Integer> accessor,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Consumer<Float> setter) {

        Integer value = getOptionOrDefault(options, defaultOptions, accessor);
        if (value != null) {
            setter.accept(value.floatValue());
        }
    }

    /**
     * Apply Long option as Integer with fallback logic (for Gemini seed which requires Integer).
     */
    private void applyLongAsIntOption(
            Function<GenerateOptions, Long> accessor,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Consumer<Integer> setter) {

        Long value = getOptionOrDefault(options, defaultOptions, accessor);
        if (value != null) {
            setter.accept(value.intValue());
        }
    }

    @Override
    public void applyTools(GenerateContentConfig.Builder configBuilder, List<ToolSchema> tools) {
        Tool tool = toolsHelper.convertToGeminiTool(tools);
        if (tool != null) {
            configBuilder.tools(List.of(tool));
        }
    }

    @Override
    public void applyToolChoice(
            GenerateContentConfig.Builder configBuilder, ToolChoice toolChoice) {
        ToolConfig toolConfig = toolsHelper.convertToolChoice(toolChoice);
        if (toolConfig != null) {
            configBuilder.toolConfig(toolConfig);
        }
    }
}
