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
package io.agentscope.core.formatter.dashscope;

import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.formatter.dashscope.dto.DashScopeContentPart;
import io.agentscope.core.formatter.dashscope.dto.DashScopeInput;
import io.agentscope.core.formatter.dashscope.dto.DashScopeMessage;
import io.agentscope.core.formatter.dashscope.dto.DashScopeParameters;
import io.agentscope.core.formatter.dashscope.dto.DashScopeRequest;
import io.agentscope.core.formatter.dashscope.dto.DashScopeResponse;
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
 * DashScope formatter for multi-agent conversations.
 * Converts AgentScope Msg objects to DashScope DTO Message objects with multi-agent support.
 * Collapses multi-agent conversation into a single user message with history tags.
 *
 * <p><b>ThinkingBlock Handling:</b> ThinkingBlock content is filtered out and NOT sent to
 * DashScope API. It is stored in memory but excluded from all formatted messages.
 */
public class DashScopeMultiAgentFormatter
        extends AbstractBaseFormatter<DashScopeMessage, DashScopeResponse, DashScopeRequest> {

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
    protected List<DashScopeMessage> doFormat(List<Msg> msgs) {
        List<DashScopeMessage> result = new ArrayList<>();
        int startIndex = 0;

        // Process system message first (if any) - output separately
        if (!msgs.isEmpty() && msgs.get(0).getRole() == MsgRole.SYSTEM) {
            result.add(
                    DashScopeMessage.builder()
                            .role("system")
                            .content(extractTextContent(msgs.get(0)))
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
                String historyPrompt = isFirstAgentMessage ? conversationHistoryPrompt : "";
                result.add(
                        conversationMerger.mergeToMessage(
                                group.messages,
                                msg -> msg.getName() != null ? msg.getName() : "Unknown",
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
    public ChatResponse parseResponse(DashScopeResponse result, Instant startTime) {
        return responseParser.parseResponse(result, startTime);
    }

    @Override
    public void applyOptions(
            DashScopeRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        DashScopeParameters params = request.getParameters();
        if (params == null) {
            params = DashScopeParameters.builder().build();
            request.setParameters(params);
        }
        toolsHelper.applyOptions(params, options, defaultOptions);
    }

    @Override
    public void applyTools(DashScopeRequest request, List<ToolSchema> tools) {
        DashScopeParameters params = request.getParameters();
        if (params == null) {
            params = DashScopeParameters.builder().build();
            request.setParameters(params);
        }
        params.setTools(toolsHelper.convertTools(tools));
    }

    @Override
    public void applyToolChoice(DashScopeRequest request, ToolChoice toolChoice) {
        DashScopeParameters params = request.getParameters();
        if (params == null) {
            params = DashScopeParameters.builder().build();
            request.setParameters(params);
        }
        toolsHelper.applyToolChoice(params, toolChoice);
    }

    /**
     * Format AgentScope Msg objects to DashScope MultiModal message format.
     * This method is used for vision models that require the MultiModalConversation API.
     *
     * <p>This method follows Python's logic:
     * 1. Process system message (if any)
     * 2. Group remaining messages into "agent_message" and "tool_sequence"
     * 3. Process each group in order, with first agent_message having history prompt
     *
     * @param msgs The AgentScope messages to convert
     * @return List of DashScopeMessage objects with multimodal content
     */
    public List<DashScopeMessage> formatMultiModal(List<Msg> msgs) {
        List<DashScopeMessage> result = new ArrayList<>();
        int startIndex = 0;

        // Process system message first (if any)
        if (!msgs.isEmpty() && msgs.get(0).getRole() == MsgRole.SYSTEM) {
            result.add(
                    DashScopeMessage.builder()
                            .role("system")
                            .content(
                                    List.of(
                                            DashScopeContentPart.text(
                                                    extractTextContent(msgs.get(0)))))
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
                                msg -> msg.getName() != null ? msg.getName() : "Unknown",
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

    /**
     * Build a complete DashScopeRequest for the API call.
     *
     * @param model Model name
     * @param messages Formatted DashScope messages
     * @param stream Whether to enable streaming
     * @return Complete DashScopeRequest ready for API call
     */
    public DashScopeRequest buildRequest(
            String model, List<DashScopeMessage> messages, boolean stream) {
        DashScopeParameters params =
                DashScopeParameters.builder().incrementalOutput(stream).build();

        return DashScopeRequest.builder()
                .model(model)
                .input(DashScopeInput.builder().messages(messages).build())
                .parameters(params)
                .build();
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

    /**
     * Format tool sequence messages to DashScopeMessage format.
     */
    private List<DashScopeMessage> formatToolSeq(List<Msg> msgs) {
        List<DashScopeMessage> result = new ArrayList<>();
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
    private DashScopeMessage formatAssistantToolCall(Msg msg) {
        DashScopeMessage.Builder builder = DashScopeMessage.builder().role("assistant");

        List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
        if (!toolBlocks.isEmpty()) {
            builder.toolCalls(toolsHelper.convertToolCalls(toolBlocks));
            // Set content to null if empty when tool calls exist (Python behavior)
            String textContent = extractTextContent(msg);
            builder.content(textContent.isEmpty() ? null : textContent);
        } else {
            builder.content(extractTextContent(msg));
        }

        return builder.build();
    }

    /**
     * Format tool result message.
     */
    private DashScopeMessage formatToolResult(Msg msg) {
        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        if (result != null) {
            return DashScopeMessage.builder()
                    .role("tool")
                    .toolCallId(result.getId())
                    .name(result.getName())
                    .content(convertToolResultToString(result.getOutput()))
                    .build();
        } else {
            return DashScopeMessage.builder()
                    .role("tool")
                    .toolCallId("tool_call_" + System.currentTimeMillis())
                    .content(extractTextContent(msg))
                    .build();
        }
    }

    /**
     * Format tool sequence messages to MultiModal format.
     */
    private List<DashScopeMessage> formatMultiModalToolSeq(List<Msg> msgs) {
        List<DashScopeMessage> result = new ArrayList<>();
        for (Msg msg : msgs) {
            result.add(messageConverter.convertToMessage(msg, true));
        }
        return result;
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
