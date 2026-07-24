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
package io.agentscope.extensions.model.openai.formatter;

import io.agentscope.core.model.ToolChoice;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import java.util.List;

/**
 * Formatter for Zhipu GLM models.
 *
 * @deprecated use {@link io.agentscope.extensions.model.openai.compat.glm.GLMFormatter} instead.
 *     All behavior is inherited from the new implementation; this class only remains for
 *     backwards compatibility and will be removed in a future release.
 */
@Deprecated
public class GLMFormatter extends io.agentscope.extensions.model.openai.compat.glm.GLMFormatter {

    public GLMFormatter() {
        super();
    }

    /**
     * Ensure at least one user message exists in the conversation.
     *
     * @deprecated use {@link io.agentscope.extensions.model.openai.compat.glm.GLMFormatter}
     *     instead.
     * @param messages the messages to check
     * @return messages with a user message ensured
     */
    @Deprecated
    protected static List<OpenAIMessage> ensureUserMessage(List<OpenAIMessage> messages) {
        return io.agentscope.extensions.model.openai.compat.glm.GLMFormatter.ensureUserMessage(
                messages);
    }

    /**
     * Apply GLM-specific tool choice handling.
     *
     * @deprecated use {@link io.agentscope.extensions.model.openai.compat.glm.GLMFormatter}
     *     instead.
     * @param request the request to apply tool choice to
     * @param toolChoice the requested tool choice
     */
    @Deprecated
    protected static void applyGLMToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        io.agentscope.extensions.model.openai.compat.glm.GLMFormatter.applyGLMToolChoice(
                request, toolChoice);
    }
}
