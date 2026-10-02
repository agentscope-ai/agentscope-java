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

import io.agentscope.core.message.Msg;
import io.agentscope.extensions.model.openai.dto.OpenAIContentPart;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * Formatter for DeepSeek Chat models (deepseek-chat, deepseek-coder).
 *
 * <p>DeepSeek API has the following specific requirements:
 * <ul>
 *   <li>No name field in messages (returns HTTP 400 if present)</li>
 *   <li>System messages should be converted to user messages</li>
 *   <li>Does NOT support strict parameter in tool definitions</li>
 *   <li>In thinking mode, reasoning_content is preserved for segments with tool calls</li>
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
 *
 * @see <a href="https://api-docs.deepseek.com/guides/thinking_mode#tool-calls">DeepSeek Thinking Mode</a>
 */
public class DeepSeekFormatter extends OpenAIChatFormatter {

    private final boolean appendEmptyUserIfEndsWithAssistant;

    public DeepSeekFormatter() {
        this(false);
    }

    /**
     * Create a DeepSeek formatter with optional empty user message appending.
     *
     * @param appendEmptyUserIfEndsWithAssistant if true, append an empty user message when the
     *     conversation ends with an assistant message to avoid API errors
     */
    public DeepSeekFormatter(boolean appendEmptyUserIfEndsWithAssistant) {
        super();
        this.appendEmptyUserIfEndsWithAssistant = appendEmptyUserIfEndsWithAssistant;
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        List<OpenAIMessage> messages = super.doFormat(msgs);
        messages = applyDeepSeekFixes(messages);
        if (appendEmptyUserIfEndsWithAssistant) {
            messages = appendEmptyUserIfNeeded(messages);
        }
        return messages;
    }

    @Override
    protected boolean supportsStrict() {
        return false;
    }

    /**
     * Apply DeepSeek-specific message format fixes.
     *
     * <p>DeepSeek API requires:
     * <ul>
     *   <li>No name field in messages</li>
     *   <li>System messages converted to user</li>
     *   <li>In thinking mode, reasoning_content preserved for segments with tool calls</li>
     *   <li>reasoning_content removed for segments without tool calls in thinking mode</li>
     * </ul>
     *
     * <p>This method is static to allow sharing with {@link DeepSeekMultiAgentFormatter}.
     *
     * @param messages the original OpenAI messages
     * @return the fixed messages for DeepSeek API
     */
    static List<OpenAIMessage> applyDeepSeekFixes(List<OpenAIMessage> messages) {
        int lastUserIndex = findLastUserIndex(messages);
        boolean thinkingMode = messages.stream().anyMatch(m -> m.getReasoningContent() != null);
        boolean[] segHasTool = thinkingMode ? computeSegmentToolFlags(messages) : null;

        List<OpenAIMessage> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            boolean isCurrentTurn = i >= lastUserIndex;
            boolean needReasoning =
                    thinkingMode
                            ? (isCurrentTurn || (segHasTool != null && segHasTool[i]))
                            : isCurrentTurn;
            result.add(fixMessage(messages.get(i), needReasoning));
        }
        return result;
    }

    /**
     * Scans messages in a single pass to identify segments (between consecutive
     * user messages) that contain tool calls. Messages within such segments
     * are flagged to preserve their reasoning_content.
     */
    private static boolean[] computeSegmentToolFlags(List<OpenAIMessage> messages) {
        boolean[] flags = new boolean[messages.size()];
        int prevUser = -1;
        for (int i = 0; i <= messages.size(); i++) {
            if (i == messages.size() || "user".equals(messages.get(i).getRole())) {
                if (prevUser >= 0) {
                    // Check if segment (prevUser, i) has any tool call
                    boolean hasTool = false;
                    for (int j = prevUser + 1; j < i && !hasTool; j++) {
                        OpenAIMessage m = messages.get(j);
                        hasTool = m.getToolCalls() != null && !m.getToolCalls().isEmpty();
                    }
                    if (hasTool) {
                        for (int j = prevUser + 1; j < i; j++) {
                            flags[j] = true;
                        }
                    }
                }
                prevUser = i;
            }
        }
        return flags;
    }

    /**
     * Append an empty user message if the conversation ends with an assistant message.
     *
     * <p>Some DeepSeek API scenarios require the conversation to not end with an assistant message.
     *
     * @param messages the messages to check
     * @return messages with an empty user message appended if needed
     */
    static List<OpenAIMessage> appendEmptyUserIfNeeded(List<OpenAIMessage> messages) {
        if (messages.isEmpty()
                || !"assistant".equals(messages.get(messages.size() - 1).getRole())) {
            return messages;
        }
        List<OpenAIMessage> result = new ArrayList<>(messages);
        result.add(OpenAIMessage.builder().role("user").content("").build());
        return result;
    }

    private static int findLastUserIndex(List<OpenAIMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                return i;
            }
        }
        return 0; // No user message found, treat all as current turn
    }

    @SuppressWarnings("unchecked")
    private static OpenAIMessage fixMessage(OpenAIMessage msg, boolean needReasoning) {
        boolean isSystem = "system".equals(msg.getRole());
        boolean hasName = msg.getName() != null;
        boolean hasReasoning = msg.getReasoningContent() != null;
        // needReasoning is determined by applyDeepSeekFixes:
        // true = current turn, or segment had tool calls in thinking mode
        boolean shouldRemoveReasoning = hasReasoning && !needReasoning;

        if (!isSystem && !hasName && !shouldRemoveReasoning) {
            return msg;
        }

        // Build new message: convert system to user, remove name field
        OpenAIMessage.Builder builder =
                OpenAIMessage.builder().role(isSystem ? "user" : msg.getRole());

        Object content = msg.getContent();
        if (content instanceof String s) {
            builder.content(s);
        } else if (content instanceof List<?> list) {
            builder.content((List<OpenAIContentPart>) list);
        }

        if (msg.getToolCalls() != null) {
            builder.toolCalls(msg.getToolCalls());
        }
        if (msg.getToolCallId() != null) {
            builder.toolCallId(msg.getToolCallId());
        }

        if (needReasoning && hasReasoning) {
            builder.reasoningContent(msg.getReasoningContent());
        }

        return builder.build();
    }
}
