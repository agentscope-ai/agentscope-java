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
 * Formatter for OpenRouter API.
 *
 * <p>OpenRouter is a unified API that provides access to many AI models.
 * It follows the OpenAI API format, so this formatter extends {@link OpenAIChatFormatter}
 * and uses the same parameter handling.
 *
 * <p>Features:
 * <ul>
 *   <li>Supports all sampling parameters (temperature, top_p, penalties)</li>
 *   <li>Supports strict parameter in tool definitions</li>
 *   <li>Supports all tool_choice options (auto, none, required, specific)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new OpenRouterFormatter())
 *     .modelName("openai/gpt-4")  // Provider/model format
 *     .baseUrl("https://openrouter.ai/api/v1")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 *
 * <p>Note: OpenRouter routes requests to different providers, so specific model behavior
 * depends on the underlying model being used. If you need provider-specific formatting
 * for the underlying model, use the appropriate formatter instead.
 */
public class OpenRouterFormatter extends OpenAIChatFormatter {

    public OpenRouterFormatter() {
        super();
    }

    // Inherits all behavior from OpenAIChatFormatter
    // OpenRouter follows OpenAI API format
}
