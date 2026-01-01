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

import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.ArrayList;
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
        extends AbstractBaseFormatter<
                Content, GenerateContentResponse, GenerateContentConfig.Builder> {

    private static final String DEFAULT_CONVERSATION_HISTORY_PROMPT =
            "# Conversation History\n"
                    + "The content between <history></history> tags contains your conversation"
                    + " history\n";

    private final GeminiMessageConverter messageConverter;
    private final GeminiResponseParser responseParser;
    private final GeminiToolsHelper toolsHelper;
    private final GeminiConversationMerger conversationMerger;
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
        this.messageConverter = new GeminiMessageConverter();
        this.responseParser = new GeminiResponseParser();
        this.toolsHelper = new GeminiToolsHelper();
        this.conversationMerger = new GeminiConversationMerger(conversationHistoryPrompt);
        this.chatFormatter = new GeminiChatFormatter();
    }

    @Override
    protected List<Content> doFormat(List<Msg> msgs) {
        List<Content> result = new ArrayList<>();
        int startIndex = 0;

        // Process system message first (if any) - convert to user role
        if (!msgs.isEmpty() && msgs.get(0).getRole() == MsgRole.SYSTEM) {
            Msg systemMsg = msgs.get(0);
            // Gemini doesn't support system role in contents, convert to user
            Content systemContent =
                    Content.builder()
                            .role("user")
                            .parts(
                                    List.of(
                                            Part.builder()
                                                    .text(extractTextContent(systemMsg))
                                                    .build()))
                            .build();
            result.add(systemContent);
            startIndex = 1;
        }

        // Group remaining messages and process each group
        List<MessageGroup> groups =
                groupMessagesSequentially(msgs.subList(startIndex, msgs.size()));
        boolean isFirstAgentMessage = true;

        for (MessageGroup group : groups) {
            if (group.type == GroupType.AGENT_MESSAGE) {
                // Format agent messages with conversation history
                String historyPrompt =
                        isFirstAgentMessage ? DEFAULT_CONVERSATION_HISTORY_PROMPT : "";
                result.add(
                        conversationMerger.mergeToContent(
                                group.messages,
                                msg -> msg.getName() != null ? msg.getName() : "Unknown",
                                this::convertToolResultToString,
                                historyPrompt));
                isFirstAgentMessage = false;

            } else if (group.type == GroupType.TOOL_SEQUENCE) {
                // Format tool sequence directly using message converter
                result.addAll(messageConverter.convertMessages(group.messages));
            }
        }

        return result;
    }

    @Override
    public ChatResponse parseResponse(GenerateContentResponse response, Instant startTime) {
        return responseParser.parseResponse(response, startTime);
    }

    @Override
    public void applyOptions(
            GenerateContentConfig.Builder configBuilder,
            GenerateOptions options,
            GenerateOptions defaultOptions) {
        // Delegate to chat formatter
        chatFormatter.applyOptions(configBuilder, options, defaultOptions);
    }

    @Override
    public void applyTools(GenerateContentConfig.Builder configBuilder, List<ToolSchema> tools) {
        chatFormatter.applyTools(configBuilder, tools);
    }

    @Override
    public void applyToolChoice(
            GenerateContentConfig.Builder configBuilder, ToolChoice toolChoice) {
        chatFormatter.applyToolChoice(configBuilder, toolChoice);
    }

    // ========== Private Helper Methods ==========

    /**
     * Group messages sequentially into agent_message and tool_sequence groups.
     *
     * @param msgs Messages to group (excluding system message)
     * @return List of MessageGroup objects in order
     */
    private List<MessageGroup> groupMessagesSequentially(List<Msg> msgs) {
        List<MessageGroup> result = new ArrayList<>();
        if (msgs.isEmpty()) {
            return result;
        }

        GroupType currentType = null;
        List<Msg> currentGroup = new ArrayList<>();

        for (Msg msg : msgs) {
            boolean isToolRelated =
                    msg.getRole() == MsgRole.TOOL
                            || msg.hasContentBlocks(ToolUseBlock.class)
                            || msg.hasContentBlocks(ToolResultBlock.class);

            GroupType msgType = isToolRelated ? GroupType.TOOL_SEQUENCE : GroupType.AGENT_MESSAGE;

            if (currentType == null) {
                // First message
                currentType = msgType;
                currentGroup.add(msg);
            } else if (currentType == msgType) {
                // Same type, add to current group
                currentGroup.add(msg);
            } else {
                // Different type, yield current group and start new one
                result.add(new MessageGroup(currentType, new ArrayList<>(currentGroup)));
                currentGroup.clear();
                currentGroup.add(msg);
                currentType = msgType;
            }
        }

        // Add the last group
        if (!currentGroup.isEmpty()) {
            result.add(new MessageGroup(currentType, currentGroup));
        }

        return result;
    }

    // ========== Inner Classes ==========

    /** Type of message group. */
    private enum GroupType {
        /** Regular agent conversation messages */
        AGENT_MESSAGE,
        /** Tool call and tool result sequence */
        TOOL_SEQUENCE
    }

    /** Container for a group of messages. */
    private static class MessageGroup {
        final GroupType type;
        final List<Msg> messages;

        MessageGroup(GroupType type, List<Msg> messages) {
            this.type = type;
            this.messages = messages;
        }
    }
}
