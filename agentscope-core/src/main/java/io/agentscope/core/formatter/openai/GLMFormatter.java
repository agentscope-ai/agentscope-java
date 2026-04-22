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
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formatter for Zhipu GLM models (glm-4, glm-3-turbo, etc.).
 *
 * <p>Zhipu GLM API has the following specific requirements:
 * <ul>
 *   <li>At least one user message is required (error 1214 otherwise)</li>
 *   <li>Only supports "auto" for tool_choice</li>
 *   <li>Does NOT support strict parameter in tool definitions</li>
 *   <li>Supports temperature, top_p, max_tokens, seed (but not frequency/presence penalty)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new GLMFormatter())
 *     .modelName("glm-4")
 *     .baseUrl("https://open.bigmodel.cn/api/paas/v4/")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 */
public class GLMFormatter extends OpenAIChatFormatter {

    private static final Logger log = LoggerFactory.getLogger(GLMFormatter.class);

    public GLMFormatter() {
        super();
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        List<OpenAIMessage> messages = super.doFormat(msgs);
        return ensureUserMessage(messages);
    }

    @Override
    protected boolean supportsStrict() {
        return false;
    }

    @Override
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        applyGLMToolChoice(request, toolChoice);
    }

    /**
     * Ensure at least one user message exists in the conversation.
     *
     * <p>GLM API requires at least one user message. If no user message exists, a placeholder is
     * added at the end.
     *
     * <p>This method is static to allow sharing with {@link GLMMultiAgentFormatter}.
     *
     * @param messages the messages to check
     * @return messages with a user message ensured
     */
    static List<OpenAIMessage> ensureUserMessage(List<OpenAIMessage> messages) {
        boolean hasUserMessage = messages.stream().anyMatch(msg -> "user".equals(msg.getRole()));

        if (hasUserMessage) {
            return messages;
        }

        // GLM API returns error 1214 if there's no user message
        log.debug("GLM: adding placeholder user message to satisfy API requirement");
        List<OpenAIMessage> result = new ArrayList<>(messages);
        result.add(OpenAIMessage.builder().role("user").content("").build());
        return result;
    }

    /**
     * Apply GLM-specific tool choice handling.
     *
     * <p>GLM only supports "auto" for tool_choice. All other options are degraded to "auto".
     *
     * <p>This method is static to allow sharing with {@link GLMMultiAgentFormatter}.
     *
     * @param request the request to apply tool choice to
     * @param toolChoice the requested tool choice
     */
    static void applyGLMToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        if (request.getTools() == null || request.getTools().isEmpty()) {
            return;
        }

        if (toolChoice != null && !(toolChoice instanceof ToolChoice.Auto)) {
            log.info(
                    "GLM only supports tool_choice='auto', degrading from '{}'",
                    toolChoice.getClass().getSimpleName());
        }

        request.setToolChoice("auto");
    }
}
