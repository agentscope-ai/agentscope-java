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
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(GLMMultiAgentFormatter.class);

    public GLMMultiAgentFormatter() {
        super();
    }

    public GLMMultiAgentFormatter(String conversationHistoryPrompt) {
        super(conversationHistoryPrompt);
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        // Use parent's multi-agent formatting
        List<OpenAIMessage> messages = super.doFormat(msgs);
        // Apply GLM-specific fixes
        return applyGLMFixes(messages);
    }

    /**
     * Apply GLM-specific message format fixes.
     */
    private List<OpenAIMessage> applyGLMFixes(List<OpenAIMessage> messages) {
        // GLM API requires at least one user message in the conversation
        boolean hasUserMessage = false;
        for (OpenAIMessage msg : messages) {
            if ("user".equals(msg.getRole())) {
                hasUserMessage = true;
                break;
            }
        }

        if (!hasUserMessage) {
            // GLM API returns error 1214 if there's no user message
            log.debug("GLM MultiAgent: adding placeholder user message to satisfy API requirement");
            OpenAIMessage placeholderUserMessage =
                    OpenAIMessage.builder().role("user").content("Please proceed.").build();
            List<OpenAIMessage> adjustedMessages = new ArrayList<>(messages);
            adjustedMessages.add(placeholderUserMessage);
            return adjustedMessages;
        }

        return messages;
    }

    @Override
    public void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        // GLM supports most sampling parameters
        Double temperature =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getTemperature);
        if (temperature != null) {
            request.setTemperature(temperature);
        }

        Double topP = getOptionOrDefault(options, defaultOptions, GenerateOptions::getTopP);
        if (topP != null) {
            request.setTopP(topP);
        }

        // GLM does not support frequency_penalty and presence_penalty

        Integer maxTokens =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getMaxTokens);
        if (maxTokens != null) {
            request.setMaxCompletionTokens(maxTokens);
            request.setMaxTokens(maxTokens);
        }

        Long seed = getOptionOrDefault(options, defaultOptions, GenerateOptions::getSeed);
        if (seed != null) {
            if (seed < Integer.MIN_VALUE || seed > Integer.MAX_VALUE) {
                log.warn("Seed value {} is out of Integer range, will be truncated", seed);
            }
            request.setSeed(seed.intValue());
        }

        applyAdditionalBodyParams(request, defaultOptions);
        applyAdditionalBodyParams(request, options);
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

        // GLM only supports "auto" tool_choice
        if (toolChoice != null && !(toolChoice instanceof ToolChoice.Auto)) {
            log.warn(
                    "GLM provider only supports tool_choice='auto', degrading from '{}'",
                    toolChoice.getClass().getSimpleName());
        }
        request.setToolChoice("auto");
        log.debug("Applied tool choice: auto (GLM only supports auto)");
    }
}
