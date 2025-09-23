/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.formatter;

import java.util.List;
import java.util.Map;

/**
 * Simple token counter implementation using basic word/character-based estimation.
 *
 * This is a basic implementation that provides approximate token counts.
 * For production use, consider integrating with proper tokenization libraries
 * like tiktoken for OpenAI models or provider-specific tokenizers.
 */
public class SimpleTokenCounter implements TokenCounter {

    private final String name;
    private final double averageTokenLength;

    /**
     * Constructor with custom average token length.
     *
     * @param name Name of this token counter
     * @param averageTokenLength Average characters per token (e.g., 4.0 for English)
     */
    public SimpleTokenCounter(String name, double averageTokenLength) {
        this.name = name;
        this.averageTokenLength = averageTokenLength;
    }

    /**
     * Constructor with default settings for English text.
     *
     * @param name Name of this token counter
     */
    public SimpleTokenCounter(String name) {
        this(name, 4.0); // Approximate average for English text
    }

    @Override
    public int countTokens(List<Map<String, Object>> formattedMessages) {
        int totalTokens = 0;

        for (Map<String, Object> message : formattedMessages) {
            // Count tokens in message content
            Object content = message.get("content");
            if (content instanceof String textContent) {
                totalTokens += countTokens(textContent);
            } else if (content instanceof List<?> contentArray) {
                // Handle content arrays (for multimodal messages)
                for (Object contentItem : contentArray) {
                    if (contentItem instanceof Map<?, ?> contentMap) {
                        Object text = contentMap.get("text");
                        if (text instanceof String textValue) {
                            totalTokens += countTokens(textValue);
                        }
                    }
                }
            }

            // Count tokens in tool calls
            Object toolCalls = message.get("tool_calls");
            if (toolCalls instanceof List<?> toolCallList) {
                for (Object toolCall : toolCallList) {
                    if (toolCall instanceof Map<?, ?> toolCallMap) {
                        Object function = toolCallMap.get("function");
                        if (function instanceof Map<?, ?> functionMap) {
                            Object arguments = functionMap.get("arguments");
                            if (arguments instanceof String argsText) {
                                totalTokens += countTokens(argsText);
                            }
                        }
                    }
                }
            }

            // Add overhead for message structure (role, metadata, etc.)
            totalTokens += 10; // Approximate overhead per message
        }

        return totalTokens;
    }

    @Override
    public int countTokens(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }

        // Simple approximation: divide character count by average token length
        int charCount = text.length();
        int approximateTokens = (int) Math.ceil(charCount / averageTokenLength);

        // Add some tokens for whitespace and punctuation
        int whitespaceTokens = text.split("\\s+").length;

        return Math.max(approximateTokens, whitespaceTokens);
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * Create a token counter optimized for OpenAI models.
     *
     * @return OpenAI-optimized token counter
     */
    public static SimpleTokenCounter forOpenAI() {
        return new SimpleTokenCounter("OpenAI-Simple", 4.0);
    }

    /**
     * Create a token counter optimized for Anthropic models.
     *
     * @return Anthropic-optimized token counter
     */
    public static SimpleTokenCounter forAnthropic() {
        return new SimpleTokenCounter("Anthropic-Simple", 3.8);
    }

    /**
     * Get the average token length used by this counter.
     *
     * @return Average characters per token
     */
    public double getAverageTokenLength() {
        return averageTokenLength;
    }
}
