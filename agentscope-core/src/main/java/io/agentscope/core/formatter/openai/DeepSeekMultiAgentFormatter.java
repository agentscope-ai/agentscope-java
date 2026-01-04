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
import io.agentscope.core.message.Msg;
import java.util.List;

/**
 * Multi-agent formatter for DeepSeek Chat models.
 *
 * <p>This formatter extends {@link OpenAIMultiAgentFormatter} with DeepSeek-specific handling:
 * <ul>
 *   <li>No name field in messages (returns HTTP 400 if present)</li>
 *   <li>System messages converted to user messages</li>
 *   <li>Does NOT support strict parameter in tool definitions</li>
 *   <li>reasoning_content handling for thinking mode</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new DeepSeekMultiAgentFormatter())
 *     .modelName("deepseek-chat")
 *     .baseUrl("https://api.deepseek.com/v1")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 */
public class DeepSeekMultiAgentFormatter extends OpenAIMultiAgentFormatter {

    private final boolean appendEmptyUserIfEndsWithAssistant;

    public DeepSeekMultiAgentFormatter() {
        super();
        this.appendEmptyUserIfEndsWithAssistant = false;
    }

    public DeepSeekMultiAgentFormatter(String conversationHistoryPrompt) {
        super(conversationHistoryPrompt);
        this.appendEmptyUserIfEndsWithAssistant = false;
    }

    /**
     * Create a DeepSeek multi-agent formatter with optional empty user message appending.
     *
     * @param appendEmptyUserIfEndsWithAssistant if true, append an empty user message when the
     *     conversation ends with an assistant message to avoid API errors
     */
    public DeepSeekMultiAgentFormatter(boolean appendEmptyUserIfEndsWithAssistant) {
        super();
        this.appendEmptyUserIfEndsWithAssistant = appendEmptyUserIfEndsWithAssistant;
    }

    /**
     * Create a DeepSeek multi-agent formatter with custom conversation history prompt and optional
     * empty user message appending.
     *
     * @param conversationHistoryPrompt custom prompt for conversation history
     * @param appendEmptyUserIfEndsWithAssistant if true, append an empty user message when the
     *     conversation ends with an assistant message to avoid API errors
     */
    public DeepSeekMultiAgentFormatter(
            String conversationHistoryPrompt, boolean appendEmptyUserIfEndsWithAssistant) {
        super(conversationHistoryPrompt);
        this.appendEmptyUserIfEndsWithAssistant = appendEmptyUserIfEndsWithAssistant;
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        List<OpenAIMessage> messages = super.doFormat(msgs);
        messages = DeepSeekFormatter.applyDeepSeekFixes(messages);
        if (appendEmptyUserIfEndsWithAssistant) {
            messages = DeepSeekFormatter.appendEmptyUserIfNeeded(messages);
        }
        return messages;
    }

    @Override
    protected boolean supportsStrict() {
        return false;
    }
}
