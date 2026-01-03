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
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Multi-agent formatter for DeepSeek R1 reasoning models.
 *
 * <p>This formatter extends {@link OpenAIMultiAgentFormatter} with DeepSeek R1-specific handling:
 * <ul>
 *   <li>Reasoning models do NOT accept temperature, top_p, or penalties</li>
 *   <li>No name field in messages (returns HTTP 400 if present)</li>
 *   <li>System messages converted to user messages</li>
 *   <li>Messages should not end with assistant role</li>
 *   <li>Does NOT support strict parameter in tool definitions</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new DeepSeekReasonerMultiAgentFormatter())
 *     .modelName("deepseek-reasoner")
 *     .baseUrl("https://api.deepseek.com/v1")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 */
public class DeepSeekReasonerMultiAgentFormatter extends OpenAIMultiAgentFormatter {

    private static final Logger log =
            LoggerFactory.getLogger(DeepSeekReasonerMultiAgentFormatter.class);

    public DeepSeekReasonerMultiAgentFormatter() {
        super();
    }

    public DeepSeekReasonerMultiAgentFormatter(String conversationHistoryPrompt) {
        super(conversationHistoryPrompt);
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        // Use parent's multi-agent formatting
        List<OpenAIMessage> messages = super.doFormat(msgs);
        // Apply DeepSeek-specific fixes
        return applyDeepSeekFixes(messages);
    }

    /**
     * Apply DeepSeek-specific message format fixes.
     */
    private List<OpenAIMessage> applyDeepSeekFixes(List<OpenAIMessage> messages) {
        boolean needsFix = false;

        for (OpenAIMessage msg : messages) {
            if ("system".equals(msg.getRole()) || msg.getName() != null) {
                needsFix = true;
                break;
            }
        }

        if (!needsFix
                && !messages.isEmpty()
                && "assistant".equals(messages.get(messages.size() - 1).getRole())) {
            needsFix = true;
        }

        if (!needsFix) {
            return messages;
        }

        log.debug("DeepSeek R1 MultiAgent: applying message format fixes");
        List<OpenAIMessage> adjustedMessages = new ArrayList<>();

        for (OpenAIMessage msg : messages) {
            String role = msg.getRole();
            if ("system".equals(role)) {
                role = "user";
            }

            OpenAIMessage.Builder builder = OpenAIMessage.builder().role(role);

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

        if (!adjustedMessages.isEmpty()
                && "assistant"
                        .equals(adjustedMessages.get(adjustedMessages.size() - 1).getRole())) {
            adjustedMessages.add(
                    OpenAIMessage.builder().role("user").content("Please continue.").build());
        }

        return adjustedMessages;
    }

    @Override
    public void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        // Reasoning models do NOT accept temperature, top_p, frequency_penalty, presence_penalty
        log.debug("DeepSeek R1 reasoning model: disabling sampling parameters");

        Integer maxTokens =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getMaxTokens);
        if (maxTokens != null) {
            request.setMaxTokens(maxTokens);
        } else {
            // DeepSeek R1 has a limit, use safe default
            request.setMaxTokens(4000);
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
                // DeepSeek does NOT support strict parameter
                OpenAIToolFunction function =
                        OpenAIToolFunction.builder()
                                .name(toolSchema.getName())
                                .description(toolSchema.getDescription())
                                .parameters(toolSchema.getParameters())
                                .build();

                openAITools.add(OpenAITool.function(function));
                log.debug("Converted tool to DeepSeek R1 format: {}", toolSchema.getName());
            }
        } catch (Exception e) {
            log.error("Failed to convert tools to DeepSeek R1 format: {}", e.getMessage(), e);
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

        // DeepSeek supports all tool_choice options
        if (toolChoice == null || toolChoice instanceof ToolChoice.Auto) {
            request.setToolChoice("auto");
        } else if (toolChoice instanceof ToolChoice.None) {
            request.setToolChoice("none");
        } else if (toolChoice instanceof ToolChoice.Required) {
            request.setToolChoice("required");
        } else if (toolChoice instanceof ToolChoice.Specific specific) {
            Map<String, Object> namedToolChoice = new HashMap<>();
            namedToolChoice.put("type", "function");
            Map<String, Object> function = new HashMap<>();
            function.put("name", specific.toolName());
            namedToolChoice.put("function", function);
            request.setToolChoice(namedToolChoice);
        } else {
            request.setToolChoice("auto");
        }

        log.debug(
                "Applied tool choice: {}",
                toolChoice != null ? toolChoice.getClass().getSimpleName() : "Auto");
    }
}
