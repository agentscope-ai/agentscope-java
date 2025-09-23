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
 * Interface for counting tokens in formatted messages.
 *
 * Different LLM providers use different tokenization schemes,
 * so this interface allows for provider-specific token counting
 * implementations.
 */
public interface TokenCounter {

    /**
     * Count the tokens in a list of formatted messages.
     *
     * @param formattedMessages List of messages in API format
     * @return Number of tokens
     */
    int countTokens(List<Map<String, Object>> formattedMessages);

    /**
     * Count tokens in a single text string.
     *
     * @param text Text to count tokens for
     * @return Number of tokens
     */
    int countTokens(String text);

    /**
     * Get the name of this token counter (e.g., "OpenAI-GPT4", "Anthropic-Claude").
     *
     * @return Token counter name
     */
    String getName();
}
