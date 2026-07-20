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
package io.agentscope.extensions.model.openai.compat.glm;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.formatter.OpenAIMultiAgentFormatter;
import java.util.List;

/**
 * Multi-agent formatter for Zhipu AI (Z.ai) GLM models.
 *
 * <p>This formatter extends {@link OpenAIMultiAgentFormatter} with the same GLM-specific
 * handling as {@link GLMFormatter}:
 * <ul>
 *   <li>At least one user message is required (error 1214 otherwise)</li>
 *   <li>{@code tool_choice} only supports {@code "auto"}; other values are degraded</li>
 *   <li>Does NOT support the {@code strict} parameter in tool definitions</li>
 *   <li>Unsupported sampling parameters are stripped, {@code max_completion_tokens} is mapped
 *       to {@code max_tokens}, and {@code temperature} / {@code top_p} are clamped to the GLM
 *       ranges (see {@link GLMFormatter} for details)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new GLMMultiAgentFormatter())
 *     .modelName("glm-5.2")
 *     .baseUrl("https://open.bigmodel.cn/api/paas/v4")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 *
 * @see GLMFormatter
 */
public class GLMMultiAgentFormatter extends OpenAIMultiAgentFormatter {

    public GLMMultiAgentFormatter() {
        super();
    }

    public GLMMultiAgentFormatter(String conversationHistoryPrompt) {
        super(conversationHistoryPrompt);
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        List<OpenAIMessage> messages = super.doFormat(msgs);
        return GLMFormatter.ensureUserMessage(messages);
    }

    @Override
    protected boolean supportsStrict() {
        return false;
    }

    @Override
    public void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        super.applyOptions(request, options, defaultOptions);
        GLMFormatter.stripUnsupportedSamplingParams(request);
    }

    @Override
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        GLMFormatter.applyGLMToolChoice(request, toolChoice);
    }
}
