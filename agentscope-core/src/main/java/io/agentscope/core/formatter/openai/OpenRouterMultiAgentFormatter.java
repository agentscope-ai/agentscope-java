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

/**
 * Multi-agent formatter for OpenRouter API.
 *
 * <p>This formatter extends {@link OpenAIMultiAgentFormatter} and inherits all behavior.
 * OpenRouter follows the OpenAI API format, so no provider-specific handling is needed.
 *
 * <p>Features:
 * <ul>
 *   <li>Multi-agent conversation handling (grouping, history merging)</li>
 *   <li>Supports all sampling parameters (temperature, top_p, penalties)</li>
 *   <li>Supports strict parameter in tool definitions</li>
 *   <li>Supports all tool_choice options (auto, none, required, specific)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new OpenRouterMultiAgentFormatter())
 *     .modelName("openai/gpt-4")  // Provider/model format
 *     .baseUrl("https://openrouter.ai/api/v1")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 *
 * <p>Note: OpenRouter routes requests to different providers, so specific model behavior
 * depends on the underlying model being used. If you need provider-specific formatting
 * for the underlying model, use the appropriate multi-agent formatter instead.
 */
public class OpenRouterMultiAgentFormatter extends OpenAIMultiAgentFormatter {

    /**
     * Create an OpenRouterMultiAgentFormatter with default conversation history prompt.
     */
    public OpenRouterMultiAgentFormatter() {
        super();
    }

    /**
     * Create an OpenRouterMultiAgentFormatter with custom conversation history prompt.
     *
     * @param conversationHistoryPrompt The prompt to prepend before conversation history
     */
    public OpenRouterMultiAgentFormatter(String conversationHistoryPrompt) {
        super(conversationHistoryPrompt);
    }

    // Inherits all behavior from OpenAIMultiAgentFormatter
    // OpenRouter follows OpenAI API format
}
