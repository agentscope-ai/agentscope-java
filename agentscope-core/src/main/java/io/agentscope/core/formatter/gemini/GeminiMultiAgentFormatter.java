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
package io.agentscope.core.formatter.gemini;

import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.formatter.gemini.dto.GeminiContent;
import io.agentscope.core.formatter.gemini.dto.GeminiRequest;
import io.agentscope.core.formatter.gemini.dto.GeminiResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.List;

/**
 * Gemini formatter for multi-agent conversations.
 *
 * <p>Converts AgentScope Msg objects to Gemini Content objects with multi-agent support.
 * Collapses multi-agent conversation into a single user message with history tags.
 *
 * <p><b>Format Strategy:</b>
 * <ul>
 *   <li>System messages: Converted to user role (Gemini doesn't support system in contents)</li>
 *   <li>Agent messages: Merged into single Content with {@code <history>} tags</li>
 *   <li>Tool sequences: Converted directly (assistant with tool calls + user with tool results)</li>
 * </ul>
 */
public class GeminiMultiAgentFormatter
        extends AbstractBaseFormatter<GeminiContent, GeminiResponse, GeminiRequest> {

    private static final String DEFAULT_CONVERSATION_HISTORY_PROMPT =
            "# Conversation History\n"
                    + "The content between <history></history> tags contains your conversation"
                    + " history\n";

    private final GeminiMessageConverter systemMessageConverter;
    private final GeminiMultiAgentMessageConverter multiAgentMessageConverter;
    private final GeminiResponseParser responseParser;
    private final GeminiToolsHelper toolsHelper;
    private final GeminiChatFormatter chatFormatter;

    /**
     * Create a GeminiMultiAgentFormatter with default conversation history prompt.
     */
    public GeminiMultiAgentFormatter() {
        this(DEFAULT_CONVERSATION_HISTORY_PROMPT);
    }

    /**
     * Create a GeminiMultiAgentFormatter with custom conversation history prompt.
     *
     * @param conversationHistoryPrompt The prompt to prepend before conversation history
     */
    public GeminiMultiAgentFormatter(String conversationHistoryPrompt) {
        this.systemMessageConverter = new GeminiMessageConverter();
        this.multiAgentMessageConverter =
                new GeminiMultiAgentMessageConverter(conversationHistoryPrompt);
        this.responseParser = new GeminiResponseParser();
        this.toolsHelper = new GeminiToolsHelper();
        this.chatFormatter = new GeminiChatFormatter();
    }

    @Override
    protected List<GeminiContent> doFormat(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return List.of();
        }

        int startIndex = computeStartIndex(msgs);
        return multiAgentMessageConverter.convertMessages(msgs.subList(startIndex, msgs.size()));
    }

    @Override
    public ChatResponse parseResponse(GeminiResponse response, Instant startTime) {
        return responseParser.parseResponse(response, startTime);
    }

    @Override
    public void applyOptions(
            GeminiRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        // Delegate to chat formatter
        chatFormatter.applyOptions(request, options, defaultOptions);
    }

    @Override
    public void applyTools(GeminiRequest request, List<ToolSchema> tools) {
        chatFormatter.applyTools(request, tools);
    }

    @Override
    public void applyToolChoice(GeminiRequest request, ToolChoice toolChoice) {
        chatFormatter.applyToolChoice(request, toolChoice);
    }

    /**
     * Apply system instruction to the request if present.
     *
     * @param request The Gemini request to configure
     * @param originalMessages The original message list (used to extract system prompt)
     */
    public void applySystemInstruction(GeminiRequest request, List<Msg> originalMessages) {
        GeminiContent systemInstruction = buildSystemInstruction(originalMessages);
        if (systemInstruction != null) {
            request.setSystemInstruction(systemInstruction);
        } else {
            request.setSystemInstruction(null);
        }
    }

    // ========== Private Helper Methods ==========

    private int computeStartIndex(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return 0;
        }
        return msgs.get(0).getRole() == MsgRole.SYSTEM ? 1 : 0;
    }

    private GeminiContent buildSystemInstruction(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return null;
        }

        Msg first = msgs.get(0);
        if (first.getRole() != MsgRole.SYSTEM) {
            return null;
        }

        List<GeminiContent> converted = systemMessageConverter.convertMessages(List.of(first));
        return converted.isEmpty() ? null : converted.get(0);
    }
}
