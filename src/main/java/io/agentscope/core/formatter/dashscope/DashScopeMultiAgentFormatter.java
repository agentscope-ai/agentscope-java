/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.core.formatter.dashscope;

import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.MultiModalMessage;
import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.formatter.FormatterCapabilities;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DashScope formatter for multi-agent conversations.
 * Converts AgentScope Msg objects to DashScope SDK Message objects with multi-agent support.
 * Collapses multi-agent conversation into a single user message with history tags.
 *
 * <p><b>ThinkingBlock Handling:</b> ThinkingBlock content is filtered out and NOT sent to
 * DashScope API. It is stored in memory but excluded from all formatted messages.
 */
public class DashScopeMultiAgentFormatter
        extends AbstractBaseFormatter<Message, GenerationResult, GenerationParam> {

    private static final String DEFAULT_CONVERSATION_HISTORY_PROMPT =
            "# Conversation History\n"
                    + "The content between <history></history> tags contains your conversation"
                    + " history\n";

    private final DashScopeMessageConverter messageConverter;
    private final DashScopeResponseParser responseParser;
    private final DashScopeToolsHelper toolsHelper;
    private final DashScopeConversationMerger conversationMerger;
    private final String conversationHistoryPrompt;

    /**
     * Create a DashScopeMultiAgentFormatter with default conversation history prompt.
     */
    public DashScopeMultiAgentFormatter() {
        this(DEFAULT_CONVERSATION_HISTORY_PROMPT);
    }

    /**
     * Create a DashScopeMultiAgentFormatter with custom conversation history prompt.
     *
     * @param conversationHistoryPrompt The prompt to prepend before conversation history
     */
    public DashScopeMultiAgentFormatter(String conversationHistoryPrompt) {
        this.messageConverter = new DashScopeMessageConverter(this::convertToolResultToString);
        this.responseParser = new DashScopeResponseParser();
        this.toolsHelper = new DashScopeToolsHelper();
        this.conversationMerger = new DashScopeConversationMerger(conversationHistoryPrompt);
        this.conversationHistoryPrompt = conversationHistoryPrompt;
    }

    @Override
    public List<Message> format(List<Msg> msgs) {
        List<Message> result = new ArrayList<>();
        int startIndex = 0;

        // Process system message first (if any) - output separately
        if (!msgs.isEmpty() && msgs.get(0).getRole() == MsgRole.SYSTEM) {
            Message systemMessage = new Message();
            systemMessage.setRole("system");
            systemMessage.setContent(extractTextContent(msgs.get(0)));
            result.add(systemMessage);
            startIndex = 1;
        }

        // Group remaining messages and process each group
        List<MessageGroup> groups =
                groupMessagesSequentially(msgs.subList(startIndex, msgs.size()));
        boolean isFirstAgentMessage = true;

        for (MessageGroup group : groups) {
            if (group.type == GroupType.AGENT_MESSAGE) {
                // Format agent messages with conversation history
                String historyPrompt = isFirstAgentMessage ? conversationHistoryPrompt : "";
                result.add(
                        conversationMerger.mergeToMessage(
                                group.messages,
                                msg -> msg.getName(),
                                this::convertToolResultToString,
                                historyPrompt));
                isFirstAgentMessage = false;
            } else if (group.type == GroupType.TOOL_SEQUENCE) {
                // Format tool sequence directly
                result.addAll(formatToolSeq(group.messages));
            }
        }

        return result;
    }

    @Override
    public ChatResponse parseResponse(GenerationResult result, Instant startTime) {
        return responseParser.parseResponse(result, startTime);
    }

    @Override
    public void applyOptions(
            GenerationParam param, GenerateOptions options, GenerateOptions defaultOptions) {
        toolsHelper.applyOptions(
                param,
                options,
                defaultOptions,
                opt -> getOptionOrDefault(options, defaultOptions, opt));
    }

    @Override
    public void applyTools(GenerationParam param, List<ToolSchema> tools) {
        toolsHelper.applyTools(param, tools);
    }

    /**
     * Format AgentScope Msg objects to DashScope MultiModalMessage format.
     * This method is used for vision models that require the MultiModalConversation API.
     *
     * <p>This method follows Python's logic:
     * 1. Process system message (if any)
     * 2. Group remaining messages into "agent_message" and "tool_sequence"
     * 3. Process each group in order, with first agent_message having history prompt
     *
     * @param msgs The AgentScope messages to convert
     * @return List of MultiModalMessage objects ready for DashScope MultiModalConversation API
     */
    public List<MultiModalMessage> formatMultiModal(List<Msg> msgs) {
        List<MultiModalMessage> result = new ArrayList<>();
        int startIndex = 0;

        // Process system message first (if any)
        if (!msgs.isEmpty() && msgs.get(0).getRole() == MsgRole.SYSTEM) {
            Map<String, Object> systemContent = new HashMap<>();
            systemContent.put("text", extractTextContent(msgs.get(0)));
            result.add(
                    MultiModalMessage.builder()
                            .role("system")
                            .content(List.of(systemContent))
                            .build());
            startIndex = 1;
        }

        // Group remaining messages and process each group
        List<MessageGroup> groups =
                groupMessagesSequentially(msgs.subList(startIndex, msgs.size()));
        boolean isFirstAgentMessage = true;

        for (MessageGroup group : groups) {
            if (group.type == GroupType.AGENT_MESSAGE) {
                // Format agent messages with conversation history
                result.add(
                        conversationMerger.mergeToMultiModalMessage(
                                group.messages,
                                msg -> msg.getName(),
                                this::convertToolResultToString,
                                isFirstAgentMessage));
                isFirstAgentMessage = false;
            } else if (group.type == GroupType.TOOL_SEQUENCE) {
                // Format tool sequence directly
                result.addAll(formatMultiModalToolSeq(group.messages));
            }
        }

        return result;
    }

    // ========== Private Helper Methods ==========

    /**
     * Group messages sequentially into agent_message and tool_sequence groups.
     * This follows Python's _group_messages logic.
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
                    msg.hasContentBlocks(ToolUseBlock.class)
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

    /**
     * Group messages into conversation, tool sequence, and bypass categories.
     * (Old method kept for format() method compatibility)
     */
    private MessageGroups groupMessages(List<Msg> msgs) {
        MessageGroups groups = new MessageGroups();

        for (Msg msg : msgs) {
            if (shouldBypassHistory(msg)) {
                groups.bypass.add(msg);
            } else if (msg.getRole() == MsgRole.TOOL
                    || (msg.getRole() == MsgRole.ASSISTANT
                            && msg.hasContentBlocks(ToolUseBlock.class))) {
                groups.toolSeq.add(msg);
            } else {
                groups.conversation.add(msg);
            }
        }

        return groups;
    }

    /**
     * Format tool sequence messages to DashScope Message format.
     */
    private List<Message> formatToolSeq(List<Msg> msgs) {
        List<Message> result = new ArrayList<>();
        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.ASSISTANT) {
                result.add(formatAssistantToolCall(msg));
            } else if (msg.getRole() == MsgRole.TOOL
                    || (msg.getRole() == MsgRole.SYSTEM
                            && msg.hasContentBlocks(ToolResultBlock.class))) {
                result.add(formatToolResult(msg));
            }
        }
        return result;
    }

    /**
     * Format assistant message with tool calls.
     */
    private Message formatAssistantToolCall(Msg msg) {
        Message message = new Message();
        message.setRole("assistant");

        List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
        if (!toolBlocks.isEmpty()) {
            message.setToolCalls(toolsHelper.convertToolCalls(toolBlocks));
            // Set content to null if empty when tool calls exist (Python behavior)
            String textContent = extractTextContent(msg);
            message.setContent(textContent.isEmpty() ? null : textContent);
        } else {
            message.setContent(extractTextContent(msg));
        }

        return message;
    }

    /**
     * Format tool result message.
     */
    private Message formatToolResult(Msg msg) {
        Message message = new Message();
        message.setRole("tool");

        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        if (result != null) {
            message.setToolCallId(result.getId());
            message.setName(result.getName());
            message.setContent(convertToolResultToString(result.getOutput()));
        } else {
            message.setToolCallId("tool_call_" + System.currentTimeMillis());
            message.setContent(extractTextContent(msg));
        }

        return message;
    }

    /**
     * Format tool sequence messages to MultiModalMessage format.
     */
    private List<MultiModalMessage> formatMultiModalToolSeq(List<Msg> msgs) {
        List<MultiModalMessage> result = new ArrayList<>();
        for (Msg msg : msgs) {
            result.add(messageConverter.convertToMultiModalMessage(msg));
        }
        return result;
    }

    @Override
    public FormatterCapabilities getCapabilities() {
        return FormatterCapabilities.builder()
                .providerName("DashScope")
                .supportToolsApi(true)
                .supportMultiAgent(true)
                .supportVision(true)
                .supportedBlocks(
                        Set.of(
                                TextBlock.class,
                                ToolUseBlock.class,
                                ToolResultBlock.class,
                                ThinkingBlock.class,
                                ImageBlock.class,
                                AudioBlock.class,
                                VideoBlock.class))
                .build();
    }

    /**
     * Helper class to group messages by category.
     */
    private static class MessageGroups {
        List<Msg> conversation = new ArrayList<>();
        List<Msg> toolSeq = new ArrayList<>();
        List<Msg> bypass = new ArrayList<>();
    }

    /**
     * Group type enum for sequential message grouping.
     */
    private enum GroupType {
        AGENT_MESSAGE,
        TOOL_SEQUENCE
    }

    /**
     * Helper class to hold a group of messages with their type.
     */
    private static class MessageGroup {
        final GroupType type;
        final List<Msg> messages;

        MessageGroup(GroupType type, List<Msg> messages) {
            this.type = type;
            this.messages = messages;
        }
    }
}
