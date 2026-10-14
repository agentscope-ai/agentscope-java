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
import io.agentscope.core.model.GenerateOptions;
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
 *   <li>Does NOT support strict parameter in tool definitions</li>
 *   <li>reasoning_content is preserved for all assistant messages and backfilled when
 *       missing</li>
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
 * @deprecated use {@link io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter}.
 * @see <a href="https://api-docs.deepseek.com/guides/thinking_mode#tool-calls">DeepSeek Thinking Mode</a>
 */
@Deprecated
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
        return doFormat(msgs, null);
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs, GenerateOptions options) {
        List<OpenAIMessage> messages = super.doFormat(msgs, options);
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
     * <p>DeepSeek API requires (thinking mode, requests carrying tools): reasoning_content
     * must be fully passed back for <b>all</b> assistant turns — even turns without tool
     * calls; otherwise the API returns HTTP 400 ("The reasoning_content in the thinking mode
     * must be passed back to the API").
     *
     * <p>This deprecated formatter additionally removes the legacy {@code name} field, which
     * the old DeepSeek endpoint rejects with HTTP 400.
     *
     * <p>Fixes mutate the freshly converted message list in place; the input {@link Msg}
     * objects and persisted history are not touched.
     *
     * <p>This method is static to allow sharing with {@link DeepSeekMultiAgentFormatter}.
     *
     * @param messages the original OpenAI messages
     * @return the fixed messages for DeepSeek API
     */
    static List<OpenAIMessage> applyDeepSeekFixes(List<OpenAIMessage> messages) {
        List<OpenAIMessage> result = new ArrayList<>(messages.size());
        for (OpenAIMessage msg : messages) {
            OpenAIMessage fixed = fixMessage(msg);
            // Backfill missing reasoning_content
            if ("assistant".equals(fixed.getRole()) && fixed.getReasoningContent() == null) {
                fixed.setReasoningContent("");
            }
            result.add(fixed);
        }
        return result;
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

    @SuppressWarnings("unchecked")
    private static OpenAIMessage fixMessage(OpenAIMessage msg) {
        if (msg.getName() == null) {
            return msg;
        }

        // Build new message without the name field
        OpenAIMessage.Builder builder = OpenAIMessage.builder().role(msg.getRole());

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
        if (msg.getReasoningContent() != null) {
            builder.reasoningContent(msg.getReasoningContent());
        }

        return builder.build();
    }
}
