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

import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ToolChoice;
import java.util.List;

/**
 * Multi-agent formatter for Zhipu GLM models.
 *
 * <p>This formatter extends {@link OpenAIMultiAgentFormatter} with GLM-specific handling:
 * <ul>
 *   <li>At least one user message is required (error 1214 otherwise)</li>
 *   <li>Only supports "auto" tool_choice</li>
 *   <li>Does NOT support strict parameter in tool definitions</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new GLMMultiAgentFormatter())
 *     .modelName("glm-4")
 *     .baseUrl("https://open.bigmodel.cn/api/paas/v4")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
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
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        GLMFormatter.applyGLMToolChoice(request, toolChoice);
    }
}
