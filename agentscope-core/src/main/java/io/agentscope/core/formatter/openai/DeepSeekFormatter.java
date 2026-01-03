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

import io.agentscope.core.formatter.openai.dto.OpenAIContentPart;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAITool;
import io.agentscope.core.formatter.openai.dto.OpenAIToolFunction;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formatter for DeepSeek Chat models (deepseek-chat, deepseek-coder).
 *
 * <p>DeepSeek API has the following specific requirements:
 * <ul>
 *   <li>No name field in messages (returns HTTP 400 if present)</li>
 *   <li>System messages should be converted to user messages</li>
 *   <li>Messages should not end with assistant role</li>
 *   <li>Does NOT support strict parameter in tool definitions</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new DeepSeekFormatter())
 *     .modelName("deepseek-chat")
 *     .baseUrl("https://api.deepseek.com/v1")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 */
public class DeepSeekFormatter extends OpenAIChatFormatter {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekFormatter.class);

    public DeepSeekFormatter() {
        super();
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        // First, use parent's formatting
        List<OpenAIMessage> messages = super.doFormat(msgs);

        // Then apply DeepSeek-specific fixes
        return applyDeepSeekFixes(messages);
    }

    /**
     * Apply DeepSeek-specific message format fixes.
     *
     * <p>DeepSeek API requires:
     * <ul>
     *   <li>No name field in messages</li>
     *   <li>System messages converted to user</li>
     *   <li>Messages should not end with assistant role</li>
     * </ul>
     */
    private List<OpenAIMessage> applyDeepSeekFixes(List<OpenAIMessage> messages) {
        boolean needsFix = false;

        // Check if any fixes are needed
        for (OpenAIMessage msg : messages) {
            if ("system".equals(msg.getRole()) || msg.getName() != null) {
                needsFix = true;
                break;
            }
        }

        // Check if last message is assistant (need to add user message to continue)
        if (!needsFix
                && !messages.isEmpty()
                && "assistant".equals(messages.get(messages.size() - 1).getRole())) {
            needsFix = true;
        }

        if (!needsFix) {
            return messages;
        }

        log.debug("DeepSeek: applying message format fixes");
        List<OpenAIMessage> adjustedMessages = new ArrayList<>();

        for (OpenAIMessage msg : messages) {
            // Convert system message to user
            String role = msg.getRole();
            if ("system".equals(role)) {
                role = "user";
            }

            // Build new message without name field
            OpenAIMessage.Builder builder = OpenAIMessage.builder().role(role);

            // Handle content (could be String or List)
            Object content = msg.getContent();
            if (content instanceof String) {
                builder.content((String) content);
            } else if (content instanceof List) {
                @SuppressWarnings("unchecked")
                List<OpenAIContentPart> contentParts = (List<OpenAIContentPart>) content;
                builder.content(contentParts);
            }

            // Note: Don't include name field for DeepSeek
            if (msg.getToolCalls() != null) {
                builder.toolCalls(msg.getToolCalls());
            }
            if (msg.getToolCallId() != null) {
                builder.toolCallId(msg.getToolCallId());
            }

            adjustedMessages.add(builder.build());
        }

        // If last message is assistant, add a user message to continue
        if (!adjustedMessages.isEmpty()
                && "assistant"
                        .equals(adjustedMessages.get(adjustedMessages.size() - 1).getRole())) {
            adjustedMessages.add(
                    OpenAIMessage.builder().role("user").content("Please continue.").build());
        }

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
                // DeepSeek does NOT support strict parameter
                OpenAIToolFunction function =
                        OpenAIToolFunction.builder()
                                .name(toolSchema.getName())
                                .description(toolSchema.getDescription())
                                .parameters(toolSchema.getParameters())
                                .build();

                openAITools.add(OpenAITool.function(function));
                log.debug("Converted tool to DeepSeek format: {}", toolSchema.getName());
            }
        } catch (Exception e) {
            log.error("Failed to convert tools to DeepSeek format: {}", e.getMessage(), e);
        }

        if (!openAITools.isEmpty()) {
            request.setTools(openAITools);
        }
    }
}
