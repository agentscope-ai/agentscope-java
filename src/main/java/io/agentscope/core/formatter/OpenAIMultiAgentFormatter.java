/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.formatter;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Multi-agent formatter for OpenAI Chat Completion API.
 *
 * This formatter handles conversations between multiple agents by:
 * - Grouping multi-agent messages into conversation history
 * - Using special markup (e.g., <history></history> tags) to structure conversations
 * - Consolidating multi-agent conversations into single user messages
 * - Supporting conversation history prompts
 *
 * Follows the Python agentscope OpenAIMultiAgentFormatter implementation pattern.
 */
public class OpenAIMultiAgentFormatter extends OpenAIChatFormatter {

    private static final String HISTORY_START_TAG = "<history>";
    private static final String HISTORY_END_TAG = "</history>";
    private static final String AGENT_MESSAGE_PREFIX = "Agent";

    public OpenAIMultiAgentFormatter() {
        super();
    }

    public OpenAIMultiAgentFormatter(TokenCounter tokenCounter, Integer maxTokens) {
        super(tokenCounter, maxTokens);
    }

    @Override
    protected List<Map<String, Object>> formatInternal(List<Msg> msgs, FormatterOptions options) {
        List<Map<String, Object>> result = new ArrayList<>();

        // Group messages into sequences
        List<MessageGroup> groups = groupMessages(msgs);

        for (MessageGroup group : groups) {
            switch (group.getType()) {
                case SYSTEM -> result.add(formatSystemMessage(group.getMessages().get(0)));
                case TOOL_SEQUENCE -> result.addAll(formatToolSequence(group.getMessages()));
                case AGENT_CONVERSATION ->
                        result.addAll(formatAgentConversation(group.getMessages()));
            }
        }

        return result;
    }

    /**
     * Group messages into different types (system, tool sequences, agent conversations).
     *
     * @param msgs List of messages to group
     * @return List of message groups
     */
    protected List<MessageGroup> groupMessages(List<Msg> msgs) {
        List<MessageGroup> groups = new ArrayList<>();
        List<Msg> currentGroup = new ArrayList<>();
        MessageGroupType currentType = null;

        for (Msg msg : msgs) {
            MessageGroupType msgType = determineGroupType(msg);

            if (currentType == null
                    || currentType != msgType
                    || (msgType == MessageGroupType.SYSTEM)) {
                // Start new group
                if (!currentGroup.isEmpty()) {
                    groups.add(new MessageGroup(currentType, currentGroup));
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
     *
     * @param msg Message to categorize
     * @return Group type
     */
    protected MessageGroupType determineGroupType(Msg msg) {
        switch (msg.getRole()) {
            case SYSTEM:
                return MessageGroupType.SYSTEM;
            case TOOL:
                return MessageGroupType.TOOL_SEQUENCE;
            case USER:
            case ASSISTANT:
                // Check if this is part of a tool sequence
                ContentBlock content = msg.getContent();
                if (content instanceof ToolUseBlock) {
                    return MessageGroupType.TOOL_SEQUENCE;
                }
                return MessageGroupType.AGENT_CONVERSATION;
            default:
                return MessageGroupType.AGENT_CONVERSATION;
        }
    }

    /**
     * Format a multi-agent conversation into OpenAI format.
     *
     * @param msgs List of messages in the conversation
     * @return List of formatted messages
     */
    protected List<Map<String, Object>> formatAgentConversation(List<Msg> msgs) {
        if (msgs.size() == 1) {
            // Single message - format normally
            return formatAgentMessage(msgs, true);
        }

        // Multi-agent conversation - consolidate into history format
        StringBuilder conversationHistory = new StringBuilder();
        conversationHistory.append(HISTORY_START_TAG).append("\n");

        for (Msg msg : msgs) {
            String agentName = msg.getName() != null ? msg.getName() : "Unknown";
            String roleLabel = formatRoleLabel(msg.getRole());
            String content = extractTextContent(msg);

            conversationHistory.append(String.format("%s %s: %s\n", roleLabel, agentName, content));
        }

        conversationHistory.append(HISTORY_END_TAG);

        // Create a single user message containing the conversation history
        Map<String, Object> historyMessage = new HashMap<>();
        historyMessage.put("role", "user");
        historyMessage.put("content", conversationHistory.toString());

        return List.of(historyMessage);
    }

    /**
     * Format role label for conversation history.
     *
     * @param role Message role
     * @return Formatted role label
     */
    protected String formatRoleLabel(MsgRole role) {
        switch (role) {
            case USER:
                return "User";
            case ASSISTANT:
                return "Assistant";
            case SYSTEM:
                return "System";
            case TOOL:
                return "Tool";
            default:
                return "Unknown";
        }
    }

    @Override
    protected List<Map<String, Object>> formatToolSequence(List<Msg> msgs) {
        // Group tool-related messages together
        List<Map<String, Object>> result = new ArrayList<>();

        // Process tool use and tool result messages in sequence
        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.ASSISTANT) {
                result.add(formatAssistantMessage(msg));
            } else if (msg.getRole() == MsgRole.TOOL) {
                result.add(formatToolMessage(msg));
            }
        }

        return result;
    }

    @Override
    public FormatterCapabilities getCapabilities() {
        return FormatterCapabilities.builder()
                .providerName("OpenAI")
                .supportToolsApi(true)
                .supportMultiAgent(true) // This is the key difference
                .supportVision(true)
                .supportedBlocks(
                        Set.of(
                                TextBlock.class,
                                ImageBlock.class,
                                ToolUseBlock.class,
                                ThinkingBlock.class))
                .build();
    }

    /**
     * Represents a group of related messages.
     */
    protected static class MessageGroup {
        private final MessageGroupType type;
        private final List<Msg> messages;

        public MessageGroup(MessageGroupType type, List<Msg> messages) {
            this.type = type;
            this.messages = new ArrayList<>(messages);
        }

        public MessageGroupType getType() {
            return type;
        }

        public List<Msg> getMessages() {
            return messages;
        }
    }

    /**
     * Types of message groups in multi-agent conversations.
     */
    protected enum MessageGroupType {
        SYSTEM, // System messages
        TOOL_SEQUENCE, // Tool use and tool result messages
        AGENT_CONVERSATION // Regular agent conversation messages
    }
}
