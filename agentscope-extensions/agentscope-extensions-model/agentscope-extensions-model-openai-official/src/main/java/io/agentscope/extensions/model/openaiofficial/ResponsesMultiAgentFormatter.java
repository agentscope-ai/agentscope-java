/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.openaiofficial;

import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Multi-agent formatter for the OpenAI Responses API.
 *
 * <p>Converts AgentScope Msg objects to Responses API ResponseInputItem objects
 * with multi-agent support.
 *
 * <p>The formatter groups messages into:
 * <ul>
 *   <li><b>SYSTEM</b> - extracted individually as {@code EasyInputMessage} with SYSTEM role
 *   <li><b>TOOL_SEQUENCE</b> - tool calls and results, passed through to
 *       {@link ResponsesRequestMapper} unchanged (including reasoning replay)
 *   <li><b>AGENT_CONVERSATION</b> - merged into a single {@code EasyInputMessage} with
 *       USER role, wrapped in {@code <history>} tags
 *   <li><b>BYPASS</b> - messages with {@code BYPASS_MULTIAGENT_HISTORY_MERGE} metadata,
 *       passed through individually as user messages
 * </ul>
 */
public class ResponsesMultiAgentFormatter {

    private static final Logger log = LoggerFactory.getLogger(ResponsesMultiAgentFormatter.class);

    private static final String DEFAULT_CONVERSATION_HISTORY_PROMPT =
            "# Conversation History\n"
                    + "The content between <history></history> tags contains your conversation"
                    + " history\n";

    private static final String HISTORY_START_TAG = "<history>";
    private static final String HISTORY_END_TAG = "</history>";

    private final String conversationHistoryPrompt;

    /** Create a formatter with the default conversation history prompt. */
    public ResponsesMultiAgentFormatter() {
        this(DEFAULT_CONVERSATION_HISTORY_PROMPT);
    }

    /**
     * Create a formatter with a custom conversation history prompt.
     *
     * @param conversationHistoryPrompt the prompt to prepend before the first history block
     */
    public ResponsesMultiAgentFormatter(String conversationHistoryPrompt) {
        this.conversationHistoryPrompt = conversationHistoryPrompt;
    }

    /**
     * Formats AgentScope messages into Responses API input items with multi-agent merging.
     *
     * <p>System messages and tool sequences are passed through to {@link ResponsesRequestMapper}
     * unchanged. Agent conversation messages are merged into single user messages with
     * {@code <history>} tags.
     *
     * @param messages the conversation history
     * @return Responses API input items
     */
    public List<ResponseInputItem> formatHistory(List<Msg> messages) {
        List<ResponseInputItem> result = new ArrayList<>();
        List<MessageGroup> groups = groupMessages(messages);
        boolean isFirstAgentGroup = true;

        for (MessageGroup group : groups) {
            switch (group.type) {
                case SYSTEM ->
                        ResponsesRequestMapper.mapSystemMessage(group.messages.get(0), result);
                case TOOL_SEQUENCE -> {
                    for (Msg msg : group.messages) {
                        if (msg.getRole() == MsgRole.ASSISTANT || msg.getRole() == MsgRole.TOOL) {
                            ResponsesRequestMapper.mapMessage(msg, result);
                        }
                    }
                }
                case AGENT_CONVERSATION -> {
                    result.addAll(mergeAgentConversation(group.messages, isFirstAgentGroup));
                    isFirstAgentGroup = false;
                }
                case BYPASS -> ResponsesRequestMapper.mapMessage(group.messages.get(0), result);
            }
        }

        return result;
    }

    // -- Grouping --------------------------------------------------

    /**
     * Types of message groups in multi-agent conversations.
     */
    private enum GroupType {
        SYSTEM, // System messages
        TOOL_SEQUENCE, // Tool use and tool result messages
        AGENT_CONVERSATION, // Regular agent conversation messages
        BYPASS // Messages that bypass history merging
    }

    private record MessageGroup(GroupType type, List<Msg> messages) {}

    /**
     * Groups messages into contiguous runs of the same type. SYSTEM and BYPASS messages
     * always start a new group (they are never grouped with adjacent messages).
     */
    private List<MessageGroup> groupMessages(List<Msg> msgs) {
        List<MessageGroup> groups = new ArrayList<>();
        List<Msg> currentGroup = new ArrayList<>();
        GroupType currentType = null;

        for (Msg msg : msgs) {
            GroupType msgType = determineGroupType(msg);

            if (currentType == null
                    || currentType != msgType
                    || msgType == GroupType.SYSTEM
                    || msgType == GroupType.BYPASS) {
                if (!currentGroup.isEmpty()) {
                    groups.add(new MessageGroup(currentType, new ArrayList<>(currentGroup)));
                }
                currentGroup = new ArrayList<>();
                currentType = msgType;
            }
            currentGroup.add(msg);
        }

        if (!currentGroup.isEmpty()) {
            groups.add(new MessageGroup(currentType, currentGroup));
        }

        return groups;
    }

    private GroupType determineGroupType(Msg msg) {
        if (shouldBypassHistory(msg)) {
            return GroupType.BYPASS;
        }

        return switch (msg.getRole()) {
            case SYSTEM -> GroupType.SYSTEM;
            case TOOL -> GroupType.TOOL_SEQUENCE;
            case USER, ASSISTANT -> {
                if (msg.hasContentBlocks(ToolUseBlock.class)
                        || msg.hasContentBlocks(ToolResultBlock.class)) {
                    yield GroupType.TOOL_SEQUENCE;
                }
                // Assistant messages with encrypted reasoning content must be
                // passed through to mapAssistantMessage.
                if (msg.getRole() == MsgRole.ASSISTANT && hasEncryptedReasoning(msg)) {
                    yield GroupType.TOOL_SEQUENCE;
                }
                yield GroupType.AGENT_CONVERSATION;
            }
        };
    }

    private static boolean shouldBypassHistory(Msg msg) {
        if (msg.getMetadata() == null) {
            return false;
        }
        Object bypassFlag =
                msg.getMetadata().get(MessageMetadataKeys.BYPASS_MULTIAGENT_HISTORY_MERGE);
        return Boolean.TRUE.equals(bypassFlag);
    }

    /**
     * Checks whether a message carries encrypted reasoning content that must be
     * preserved as a Responses reasoning replay item (not merged into history text).
     */
    private static boolean hasEncryptedReasoning(Msg msg) {
        ThinkingBlock thinkingBlock = msg.getFirstContentBlock(ThinkingBlock.class);
        if (thinkingBlock == null || thinkingBlock.getMetadata() == null) {
            return false;
        }
        Object ec =
                thinkingBlock
                        .getMetadata()
                        .get(OpenAIOfficialConstants.MD_REASONING_ENCRYPTED_CONTENT);
        return ec instanceof String s && !s.isEmpty();
    }

    // -- Conversation merging --------------------------------------

    /**
     * Merges a group of agent conversation messages into a single user message
     * with {@code <history>} tags.
     *
     * <p>Text and thinking blocks are accumulated in a text buffer. Image and data blocks
     * flush the buffer and are added as separate {@link ResponseInputContent} parts to
     * preserve their multimodal nature.
     *
     * @param messages     the conversation messages to merge
     * @param isFirstGroup whether this is the first agent conversation group (controls
     *                      whether the history prompt is included)
     * @return a single-element list containing the merged user message, or empty list
     */
    private List<ResponseInputItem> mergeAgentConversation(
            List<Msg> messages, boolean isFirstGroup) {

        List<ResponseInputContent> parts = new ArrayList<>();
        StringBuilder textBuffer = new StringBuilder();

        String prompt = isFirstGroup ? conversationHistoryPrompt : "";
        textBuffer.append(prompt).append(HISTORY_START_TAG).append("\n");

        // Include agent name prefix only in multi-turn context
        boolean includePrefix = messages.size() > 1;

        for (Msg msg : messages) {
            processMessage(msg, textBuffer, parts, includePrefix);
        }

        textBuffer.append(HISTORY_END_TAG).append("\n");

        // Flush remaining text (always non-empty: at least the history tags are present)
        parts.add(
                ResponseInputContent.ofInputText(
                        ResponseInputText.builder().text(textBuffer.toString()).build()));

        return List.of(
                ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage.builder()
                                .role(EasyInputMessage.Role.USER)
                                .contentOfResponseInputMessageContentList(parts)
                                .build()));
    }

    /**
     * Processes a single message, routing each content block to the text buffer
     * or the parts list as appropriate.
     */
    private void processMessage(
            Msg msg,
            StringBuilder textBuffer,
            List<ResponseInputContent> parts,
            boolean includePrefix) {

        String agentName = msg.getName();
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null) {
            return;
        }

        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                if (includePrefix) {
                    appendNamePrefix(textBuffer, agentName);
                }
                textBuffer.append(tb.getText()).append("\n");
            } else if (block instanceof HintBlock hb) {
                if (includePrefix) {
                    appendNamePrefix(textBuffer, agentName);
                }
                textBuffer.append(hb.getHint()).append("\n");
            } else if (block instanceof ImageBlock ib) {
                flushText(textBuffer, parts);
                try {
                    String imageUrl = ResponsesRequestMapper.resolveImageUrl(ib.getSource());
                    parts.add(
                            ResponseInputContent.ofInputImage(
                                    ResponseInputImage.builder()
                                            .imageUrl(imageUrl)
                                            .detail(ResponseInputImage.Detail.of("auto"))
                                            .build()));
                } catch (Exception e) {
                    log.warn(
                            "Failed to process ImageBlock in multi-agent conversation: {}",
                            e.getMessage());
                    if (includePrefix) {
                        appendNamePrefix(textBuffer, agentName);
                    }
                    textBuffer.append("[Image - processing failed]\n");
                }
            } else if (block instanceof DataBlock db) {
                flushText(textBuffer, parts);
                try {
                    String imageUrl = ResponsesRequestMapper.resolveDataBlockImageUrl(db);
                    parts.add(
                            ResponseInputContent.ofInputImage(
                                    ResponseInputImage.builder()
                                            .imageUrl(imageUrl)
                                            .detail(ResponseInputImage.Detail.of("auto"))
                                            .build()));
                } catch (Exception e) {
                    log.warn(
                            "Failed to process DataBlock in multi-agent conversation: {}",
                            e.getMessage());
                    if (includePrefix) {
                        appendNamePrefix(textBuffer, agentName);
                    }
                    textBuffer.append("[Data - processing failed]\n");
                }
            } else if (block instanceof ThinkingBlock tb) {
                if (includePrefix) {
                    appendNamePrefix(textBuffer, agentName);
                }
                String thinking = tb.getThinking();
                if (thinking != null && !thinking.isEmpty()) {
                    textBuffer.append("[Thinking]: ").append(thinking).append("\n");
                }
            }
            // Other block types (AudioBlock, VideoBlock, ToolUseBlock,
            // ToolResultBlock) are silently skipped. ToolUseBlock and
            // ToolResultBlock can never reach here
        }
    }

    private void flushText(StringBuilder textBuffer, List<ResponseInputContent> parts) {
        if (textBuffer.length() > 0) {
            parts.add(
                    ResponseInputContent.ofInputText(
                            ResponseInputText.builder().text(textBuffer.toString()).build()));
            textBuffer.setLength(0);
        }
    }

    private void appendNamePrefix(StringBuilder buffer, String agentName) {
        if (agentName != null && !agentName.isEmpty()) {
            buffer.append(agentName).append(": ");
        }
    }
}
