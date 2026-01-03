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
import io.agentscope.core.formatter.openai.dto.OpenAITool;
import io.agentscope.core.formatter.openai.dto.OpenAIToolFunction;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
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
 *   <li>Supports all sampling parameters (temperature, top_p, etc.)</li>
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
        // First, use parent's formatting
        List<OpenAIMessage> messages = super.doFormat(msgs);

        // Then ensure at least one user message exists
        return ensureUserMessage(messages);
    }

    /**
     * Ensure at least one user message exists in the conversation.
     *
     * <p>GLM API requires at least one user message in the conversation.
     * If no user message exists, a placeholder is added at the end.
     */
    private List<OpenAIMessage> ensureUserMessage(List<OpenAIMessage> messages) {
        boolean hasUserMessage = false;
        for (OpenAIMessage msg : messages) {
            if ("user".equals(msg.getRole())) {
                hasUserMessage = true;
                break;
            }
        }

        if (hasUserMessage) {
            return messages;
        }

        // GLM API returns error 1214 if there's no user message
        // Add a placeholder user message at the end
        log.debug("GLM: adding placeholder user message to satisfy API requirement");
        List<OpenAIMessage> adjustedMessages = new ArrayList<>(messages);
        adjustedMessages.add(
                OpenAIMessage.builder().role("user").content("Please proceed.").build());
        return adjustedMessages;
    }

    @Override
    public void applyTools(OpenAIRequest request, List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }

        List<OpenAITool> openAITools = new ArrayList<>();

        try {
            for (ToolSchema toolSchema : tools) {
                // GLM does NOT support strict parameter
                OpenAIToolFunction function =
                        OpenAIToolFunction.builder()
                                .name(toolSchema.getName())
                                .description(toolSchema.getDescription())
                                .parameters(toolSchema.getParameters())
                                .build();

                openAITools.add(OpenAITool.function(function));
                log.debug("Converted tool to GLM format: {}", toolSchema.getName());
            }
        } catch (Exception e) {
            log.error("Failed to convert tools to GLM format: {}", e.getMessage(), e);
        }

        if (!openAITools.isEmpty()) {
            request.setTools(openAITools);
        }
    }

    @Override
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        // Only apply tool_choice if tools are present
        if (request.getTools() == null || request.getTools().isEmpty()) {
            return;
        }

        // GLM only supports "auto" for tool_choice
        // All other options are degraded to "auto"
        if (toolChoice != null && !(toolChoice instanceof ToolChoice.Auto)) {
            log.info(
                    "GLM does not support tool_choice='{}', degrading to 'auto'. "
                            + "For reliable behavior with GLM, avoid using forced tool choice.",
                    toolChoice.getClass().getSimpleName());
        }

        request.setToolChoice("auto");
        log.debug("Applied tool choice: auto (GLM only supports auto)");
    }
}
