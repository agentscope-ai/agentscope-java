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
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-agent formatter for OpenAI Chat Completion HTTP API.
 * Converts AgentScope Msg objects to OpenAI DTO types with multi-agent support.
 *
 * <p>This formatter handles conversations between multiple agents by:
 * - Grouping multi-agent messages into conversation history
 * - Using special markup (e.g., history tags) to structure conversations
 * - Consolidating multi-agent conversations into single user messages
 */
public class OpenAIMultiAgentFormatter extends OpenAIBaseFormatter {

    private static final String DEFAULT_CONVERSATION_HISTORY_PROMPT =
            "# Conversation History\n"
                    + "The content between <history></history> tags contains your conversation"
                    + " history\n";

    private final OpenAIConversationMerger conversationMerger;

    /**
     * Create an OpenAIMultiAgentFormatter with default conversation history prompt.
     */
    public OpenAIMultiAgentFormatter() {
        this(DEFAULT_CONVERSATION_HISTORY_PROMPT);
    }

    /**
     * Create an OpenAIMultiAgentFormatter with custom conversation history prompt.
     *
     * @param conversationHistoryPrompt The prompt to prepend before conversation history
     */
    public OpenAIMultiAgentFormatter(String conversationHistoryPrompt) {
        super();
        this.conversationMerger = new OpenAIConversationMerger(conversationHistoryPrompt);
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        List<OpenAIMessage> result = new ArrayList<>();

        // Group messages into sequences
        List<MessageGroup> groups = groupMessages(msgs);

        for (MessageGroup group : groups) {
            switch (group.type) {
                case SYSTEM -> {
                    Msg systemMsg = group.messages.get(0);
                    result.add(messageConverter.convertToMessage(systemMsg, false));
                }
                case TOOL_SEQUENCE -> result.addAll(formatToolSequence(group.messages));
                case AGENT_CONVERSATION -> {
                    result.add(
                            conversationMerger.mergeToUserMessage(
                                    group.messages,
                                    msg -> formatRoleLabel(msg.getRole()),
                                    this::convertToolResultToString));
                }
                case BYPASS -> {
                    Msg bypassMsg = group.messages.get(0);
                    result.add(
                            messageConverter.convertToMessage(
                                    bypassMsg, hasMediaContent(bypassMsg)));
                }
            }
        }

        return result;
    }

    // ========== Private Helper Methods ==========

    /**
     * Group messages into different types (system, tool sequences, agent conversations, bypass).
     */
    private List<MessageGroup> groupMessages(List<Msg> msgs) {
        List<MessageGroup> groups = new ArrayList<>();
        List<Msg> currentGroup = new ArrayList<>();
        MessageGroupType currentType = null;

        for (Msg msg : msgs) {
            MessageGroupType msgType = determineGroupType(msg);

            if (currentType == null
                    || currentType != msgType
                    || (msgType == MessageGroupType.SYSTEM)
                    || (msgType == MessageGroupType.BYPASS)) {
                // Start new group
                if (!currentGroup.isEmpty()) {
                    groups.add(new MessageGroup(currentType, new ArrayList<>(currentGroup)));
                }
                currentGroup = new ArrayList<>();
                currentType = msgType;
            }

            currentGroup.add(msg);
        }

        // Add final group
        if (!currentGroup.isEmpty()) {
            groups.add(new MessageGroup(currentType, currentGroup));
        }

        return groups;
    }

    /**
     * Determine the group type for a message.
     */
    private MessageGroupType determineGroupType(Msg msg) {
        // Check for bypass flag first
        if (shouldBypassHistory(msg)) {
            return MessageGroupType.BYPASS;
        }

        return switch (msg.getRole()) {
            case SYSTEM -> MessageGroupType.SYSTEM;
            case TOOL -> MessageGroupType.TOOL_SEQUENCE;
            case USER, ASSISTANT -> {
                if (msg.hasContentBlocks(ToolUseBlock.class)) {
                    yield MessageGroupType.TOOL_SEQUENCE;
                }
                yield MessageGroupType.AGENT_CONVERSATION;
            }
        };
    }

    /**
     * Format tool sequence messages.
     */
    private List<OpenAIMessage> formatToolSequence(List<Msg> msgs) {
        List<OpenAIMessage> result = new ArrayList<>();

        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.ASSISTANT || msg.getRole() == MsgRole.TOOL) {
                result.add(messageConverter.convertToMessage(msg, hasMediaContent(msg)));
            }
        }

        return result;
    }

    /**
     * Represents a group of related messages.
     */
    private static class MessageGroup {
        private final MessageGroupType type;
        private final List<Msg> messages;

        public MessageGroup(MessageGroupType type, List<Msg> messages) {
            this.type = type;
            this.messages = messages;
        }
    }

    /**
     * Types of message groups in multi-agent conversations.
     */
    private enum MessageGroupType {
        SYSTEM, // System messages
        TOOL_SEQUENCE, // Tool use and tool result messages
        AGENT_CONVERSATION, // Regular agent conversation messages
        BYPASS // Messages that bypass history merging
    }
}
