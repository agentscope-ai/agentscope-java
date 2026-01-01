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
package io.agentscope.core.formatter.anthropic;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Multi-agent formatter for Anthropic Messages API. Converts AgentScope Msg objects to Anthropic
 * SDK MessageParam objects with multi-agent support.
 *
 * <p>This formatter handles conversations between multiple agents by:
 *
 * <ul>
 *   <li>Grouping multi-agent messages into conversation history
 *   <li>Using special markup (history tags) to structure conversations
 *   <li>Consolidating multi-agent conversations into single user messages
 * </ul>
 */
public class AnthropicMultiAgentFormatter extends AnthropicBaseFormatter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicMultiAgentFormatter.class);

    private static final String DEFAULT_CONVERSATION_HISTORY_PROMPT =
            "# Conversation History\n"
                    + "The content between <history></history> tags contains your conversation"
                    + " history\n";

    private final AnthropicMediaConverter mediaConverter;
    private final String conversationHistoryPrompt;
    private boolean isFirstAgentMessageGroup = true;

    /** Create an AnthropicMultiAgentFormatter with default conversation history prompt. */
    public AnthropicMultiAgentFormatter() {
        this(DEFAULT_CONVERSATION_HISTORY_PROMPT);
    }

    /**
     * Create an AnthropicMultiAgentFormatter with custom conversation history prompt.
     *
     * @param conversationHistoryPrompt The prompt to prepend before conversation history
     */
    public AnthropicMultiAgentFormatter(String conversationHistoryPrompt) {
        this.mediaConverter = new AnthropicMediaConverter();
        this.conversationHistoryPrompt = conversationHistoryPrompt;
    }

    @Override
    public List<MessageParam> doFormat(List<Msg> msgs) {
        List<MessageParam> result = new ArrayList<>();
        this.isFirstAgentMessageGroup = true;

        // Group messages
        List<MessageGroup> groups = groupMessages(msgs);

        for (MessageGroup group : groups) {
            switch (group.type) {
                case SYSTEM -> {
                    Msg systemMsg = group.messages.get(0);
                    result.addAll(messageConverter.convert(List.of(systemMsg)));
                }
                case TOOL_SEQUENCE -> result.addAll(formatToolSequence(group.messages));
                case AGENT_CONVERSATION -> result.addAll(formatAgentConversation(group.messages));
            }
        }

        return result;
    }

    @Override
    public ChatResponse parseResponse(Object response, Instant startTime) {
        if (response instanceof Message message) {
            return AnthropicResponseParser.parseMessage(message, startTime);
        } else {
            throw new IllegalArgumentException("Unsupported response type: " + response.getClass());
        }
    }

    // ========== Private Helper Methods ==========

    private enum GroupType {
        SYSTEM,
        TOOL_SEQUENCE,
        AGENT_CONVERSATION
    }

    private static class MessageGroup {
        GroupType type;
        List<Msg> messages;

        MessageGroup(GroupType type, List<Msg> messages) {
            this.type = type;
            this.messages = messages;
        }
    }

    /** Group messages into system, tool sequences, and agent conversations. */
    private List<MessageGroup> groupMessages(List<Msg> msgs) {
        List<MessageGroup> groups = new ArrayList<>();
        List<Msg> currentGroup = new ArrayList<>();
        GroupType currentType = null;

        for (Msg msg : msgs) {
            GroupType msgType = determineMessageType(msg);

            if (msgType == GroupType.SYSTEM) {
                if (!currentGroup.isEmpty()) {
                    groups.add(new MessageGroup(currentType, new ArrayList<>(currentGroup)));
                    currentGroup.clear();
                }
                groups.add(new MessageGroup(GroupType.SYSTEM, List.of(msg)));
                currentType = null;
            } else if (msgType != currentType && !currentGroup.isEmpty()) {
                groups.add(new MessageGroup(currentType, new ArrayList<>(currentGroup)));
                currentGroup.clear();
                currentType = msgType;
                currentGroup.add(msg);
            } else {
                currentType = msgType;
                currentGroup.add(msg);
            }
        }

        if (!currentGroup.isEmpty()) {
            groups.add(new MessageGroup(currentType, currentGroup));
        }

        return groups;
    }

    /** Determine message type for grouping. */
    private GroupType determineMessageType(Msg msg) {
        if (msg.getRole() == MsgRole.SYSTEM
                && !msg.getContent().isEmpty()
                && !(msg.getContent().get(0) instanceof ToolResultBlock)) {
            return GroupType.SYSTEM;
        }

        boolean hasToolUse = msg.hasContentBlocks(ToolUseBlock.class);
        boolean hasToolResult = msg.hasContentBlocks(ToolResultBlock.class);

        if (hasToolUse || hasToolResult) {
            return GroupType.TOOL_SEQUENCE;
        }

        return GroupType.AGENT_CONVERSATION;
    }

    /** Format tool sequence (tool calls and results). */
    private List<MessageParam> formatToolSequence(List<Msg> messages) {
        return messageConverter.convert(messages);
    }

    /** Format agent conversation messages with history tags. */
    private List<MessageParam> formatAgentConversation(List<Msg> messages) {
        boolean isFirst = isFirstAgentMessageGroup;
        isFirstAgentMessageGroup = false;

        String prompt = isFirst ? conversationHistoryPrompt : "";

        // Merge conversation with history tags
        List<Object> conversationBlocks =
                AnthropicConversationMerger.mergeConversation(messages, prompt);

        // Convert to ContentBlockParam list
        List<ContentBlockParam> contentBlocks = new ArrayList<>();

        for (Object block : conversationBlocks) {
            if (block instanceof String text) {
                contentBlocks.add(
                        ContentBlockParam.ofText(TextBlockParam.builder().text(text).build()));
            } else if (block instanceof ImageBlock ib) {
                try {
                    ImageBlockParam imageParam = mediaConverter.convertImageBlock(ib);
                    contentBlocks.add(ContentBlockParam.ofImage(imageParam));
                } catch (Exception e) {
                    log.warn("Failed to process ImageBlock in multi-agent conversation: {}", e);
                }
            }
        }

        if (contentBlocks.isEmpty()) {
            return List.of();
        }

        return List.of(
                MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(MessageParam.Content.ofBlockParams(contentBlocks))
                        .build());
    }
}
