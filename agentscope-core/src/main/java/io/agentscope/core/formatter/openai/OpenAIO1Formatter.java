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
import io.agentscope.core.model.GenerateOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formatter for OpenAI o1 reasoning models (o1, o1-mini, o1-preview).
 *
 * <p>OpenAI o1 models are reasoning models with fixed sampling parameters.
 * This formatter:
 * <ul>
 *   <li>Does NOT apply temperature, top_p, frequency_penalty, presence_penalty</li>
 *   <li>Only applies max_tokens and seed</li>
 *   <li>Supports reasoning_effort parameter ("low", "medium", "high")</li>
 *   <li>Supports strict parameter in tool definitions</li>
 *   <li>Supports all tool_choice options</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new OpenAIO1Formatter())
 *     .modelName("o1-mini")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 */
public class OpenAIO1Formatter extends OpenAIChatFormatter {

    private static final Logger log = LoggerFactory.getLogger(OpenAIO1Formatter.class);

    public OpenAIO1Formatter() {
        super();
    }

    @Override
    public void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {

        // o1 models do NOT support temperature, top_p, frequency_penalty, presence_penalty
        // Only apply max_tokens, seed, and reasoning_effort

        // Apply max tokens
        Integer maxTokens =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getMaxTokens);
        if (maxTokens != null) {
            // o1 uses max_completion_tokens, not max_tokens
            request.setMaxCompletionTokens(maxTokens);
        }

        // Apply seed
        Long seed = getOptionOrDefault(options, defaultOptions, GenerateOptions::getSeed);
        if (seed != null) {
            if (seed < Integer.MIN_VALUE || seed > Integer.MAX_VALUE) {
                log.warn("Seed value {} is out of Integer range, will be truncated", seed);
            }
            request.setSeed(seed.intValue());
        }

        // Apply reasoning effort
        String reasoningEffort =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getReasoningEffort);
        if (reasoningEffort != null) {
            request.setReasoningEffort(reasoningEffort);
        }

        // Apply additional body params (must be last to allow overriding)
        applyAdditionalBodyParams(request, defaultOptions);
        applyAdditionalBodyParams(request, options);
    }
}
