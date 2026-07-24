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
package io.agentscope.extensions.model.openai.compat.kimi;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.formatter.OpenAIMultiAgentFormatter;

/**
 * Multi-agent formatter for Kimi (Moonshot AI) models.
 *
 * <p>This formatter extends {@link OpenAIMultiAgentFormatter} with the same Kimi-specific
 * handling as {@link KimiFormatter}:
 * <ul>
 *   <li>Fixed sampling parameters ({@code temperature} / {@code top_p} / penalties) are
 *       stripped on {@code kimi-*} models</li>
 *   <li>{@code reasoning_effort} is only kept for {@code kimi-k3};
 *       {@code thinking_budget} is always stripped</li>
 *   <li>{@code max_completion_tokens} is mapped to {@code max_tokens}</li>
 *   <li>{@code tool_choice} is degraded when the target model does not support the requested
 *       mode (see {@link KimiFormatter} for details)</li>
 *   <li>The {@code strict} parameter in tool definitions is not sent</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new KimiMultiAgentFormatter())
 *     .modelName("kimi-k3")
 *     .baseUrl("https://api.moonshot.cn/v1")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 *
 * @see KimiFormatter
 */
public class KimiMultiAgentFormatter extends OpenAIMultiAgentFormatter {

    public KimiMultiAgentFormatter() {
        super();
    }

    public KimiMultiAgentFormatter(String conversationHistoryPrompt) {
        super(conversationHistoryPrompt);
    }

    @Override
    protected boolean supportsStrict() {
        return false;
    }

    @Override
    public void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        super.applyOptions(request, options, defaultOptions);
        KimiFormatter.sanitizeKimiRequest(request);
    }

    @Override
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        KimiFormatter.applyKimiToolChoice(request, toolChoice);
    }
}
