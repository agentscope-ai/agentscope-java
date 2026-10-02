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
package io.agentscope.extensions.model.openai.compat.deepseek;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Formatter for DeepSeek Chat models (deepseek-v4-flash, deepseek-v4-pro).
 *
 * <p>DeepSeek API has the following specific requirements:
 * <ul>
 *   <li>System/user/assistant {@code name} fields are allowed</li>
 *   <li>Omits strict parameter in tool definitions</li>
 *   <li>reasoning_content is preserved for all assistant messages and backfilled when
 *       missing</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new DeepSeekFormatter())
 *     .modelName("deepseek-v4-flash")
 *     .baseUrl("https://api.deepseek.com")
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
     * @see <a href="https://api-docs.deepseek.com/guides/thinking_mode#tool-calls">DeepSeek
     *     Thinking Mode / Tool Calls</a>
     */
    static List<OpenAIMessage> applyDeepSeekFixes(List<OpenAIMessage> messages) {
        for (OpenAIMessage msg : messages) {
            // Backfill missing reasoning_content
            if ("assistant".equals(msg.getRole()) && msg.getReasoningContent() == null) {
                msg.setReasoningContent("");
            }
        }
        return messages;
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
}
