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

import io.agentscope.core.formatter.gemini.dto.GeminiContent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;

/**
 * Message converter for multi-agent Gemini formatting.
 *
 * <p>This converter merges agent conversation messages into history-tagged content
 * while keeping tool sequences as separate Gemini contents.
 */
public class GeminiMultiAgentMessageConverter {

    private final GeminiMessageConverter baseConverter;
    private final GeminiConversationMerger conversationMerger;
    private final String conversationHistoryPrompt;

    /**
     * Create a multi-agent message converter with a conversation history prompt.
     *
     * @param conversationHistoryPrompt Prompt text to prepend to the first history group
     */
    public GeminiMultiAgentMessageConverter(String conversationHistoryPrompt) {
        this.baseConverter = new GeminiMessageConverter();
        this.conversationMerger = new GeminiConversationMerger(conversationHistoryPrompt);
        this.conversationHistoryPrompt = conversationHistoryPrompt;
    }

    /**
     * Convert messages into Gemini contents with multi-agent history merging.
     *
     * @param msgs Messages to convert
     * @return Gemini contents for API requests
     */
    public List<GeminiContent> convertMessages(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return List.of();
        }

        List<MessageGroup> groups = groupMessagesSequentially(msgs);
        List<GeminiContent> result = new ArrayList<>();
        boolean historyPromptApplied = false;

        for (int i = 0; i < groups.size(); i++) {
            MessageGroup group = groups.get(i);
            if (group.type == GroupType.TOOL_SEQUENCE) {
                result.addAll(baseConverter.convertMessages(group.messages));
                continue;
            }

            if (group.messages.isEmpty()) {
                continue;
            }

            String historyPrompt = historyPromptApplied ? "" : conversationHistoryPrompt;
            GeminiContent mergedContent =
                    conversationMerger.mergeToContent(
                            group.messages,
                            this::resolveHistoryName,
                            baseConverter::convertToolResultToString,
                            historyPrompt);
            result.add(mergedContent);
            historyPromptApplied = true;
        }

        return result;
    }

    private String resolveHistoryName(Msg msg) {
        String name = msg.getName();
        if (name != null && !name.isBlank()) {
            return name;
        }

        MsgRole role = msg.getRole();
        if (role == null) {
            return "unknown";
        }

        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            case TOOL -> "tool";
        };
    }

    /**
     * Group messages sequentially into agent_message and tool_sequence groups.
     *
     * @param msgs Messages to group
     * @return List of message groups in order
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
                currentType = msgType;
                currentGroup.add(msg);
            } else if (currentType == msgType) {
                currentGroup.add(msg);
            } else {
                result.add(new MessageGroup(currentType, new ArrayList<>(currentGroup)));
                currentGroup.clear();
                currentGroup.add(msg);
                currentType = msgType;
            }
        }

        if (!currentGroup.isEmpty()) {
            result.add(new MessageGroup(currentType, currentGroup));
        }

        return result;
    }

    private enum GroupType {
        AGENT_MESSAGE,
        TOOL_SEQUENCE
    }

    private static class MessageGroup {
        final GroupType type;
        final List<Msg> messages;

        MessageGroup(GroupType type, List<Msg> messages) {
            this.type = type;
            this.messages = messages;
        }
    }
}
