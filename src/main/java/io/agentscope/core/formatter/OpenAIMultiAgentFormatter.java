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
package io.agentscope.core.formatter;

import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Multi-agent formatter for OpenAI Chat Completion API.
 * Converts AgentScope Msg objects to OpenAI SDK ChatCompletionMessageParam objects with multi-agent support.
 *
 * This formatter handles conversations between multiple agents by:
 * - Grouping multi-agent messages into conversation history
 * - Using special markup (e.g., history tags) to structure conversations
 * - Consolidating multi-agent conversations into single user messages
 */
public class OpenAIMultiAgentFormatter extends AbstractOpenAIFormatter {

    private static final Logger log = LoggerFactory.getLogger(OpenAIMultiAgentFormatter.class);
    private static final String HISTORY_START_TAG = "<history>";
    private static final String HISTORY_END_TAG = "</history>";
    private static final String DEFAULT_CONVERSATION_HISTORY_PROMPT =
            "# Conversation History\n"
                    + "The content between <history></history> tags contains your conversation"
                    + " history\n";
    private final String conversationHistoryPrompt;

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
        this.conversationHistoryPrompt =
                conversationHistoryPrompt != null
                        ? conversationHistoryPrompt
                        : DEFAULT_CONVERSATION_HISTORY_PROMPT;
    }

    /**
     * Formats a list of multi-agent messages to OpenAI Chat Completion format.
     *
     * <p>This method groups messages into logical sequences (system, tool, conversation) and
     * formats each group appropriately:
     * <ul>
     *   <li>System messages are passed through as-is</li>
     *   <li>Tool sequences (assistant tool calls + tool results) preserve native OpenAI format</li>
     *   <li>Agent conversations are consolidated into single user messages with history tags</li>
     * </ul>
     *
     * @param msgs the list of messages to format
     * @return the list of formatted OpenAI message parameters
     */
    @Override
    public List<ChatCompletionMessageParam> format(List<Msg> msgs) {
        List<ChatCompletionMessageParam> result = new ArrayList<>();

        // Group messages into sequences
        List<MessageGroup> groups = groupMessages(msgs);

        for (MessageGroup group : groups) {
            switch (group.getType()) {
                case SYSTEM ->
                        result.add(
                                ChatCompletionMessageParam.ofSystem(
                                        formatSystemMsg(group.getMessages().get(0))));
                case TOOL_SEQUENCE -> result.addAll(formatToolSequence(group.getMessages()));
                case AGENT_CONVERSATION ->
                        result.add(
                                ChatCompletionMessageParam.ofUser(
                                        formatAgentConversation(group.getMessages())));
            }
        }

        return result;
    }

    /**
     * Group messages into different types (system, tool sequences, agent conversations).
     */
    private List<MessageGroup> groupMessages(List<Msg> msgs) {
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
     */
    private MessageGroupType determineGroupType(Msg msg) {
        return switch (msg.getRole()) {
            case SYSTEM -> MessageGroupType.SYSTEM;
            case TOOL -> MessageGroupType.TOOL_SEQUENCE;
            case USER, ASSISTANT -> {
                // Check if this is part of a tool sequence
                if (msg.hasContentBlocks(ToolUseBlock.class)) {
                    yield MessageGroupType.TOOL_SEQUENCE;
                }
                yield MessageGroupType.AGENT_CONVERSATION;
            }
        };
    }

    /**
     * Format a multi-agent conversation into OpenAI format.
     * Consolidates multiple agent messages into a single user message with history tags.
     * Preserves images and audio as ContentParts.
     */
    private ChatCompletionUserMessageParam formatAgentConversation(List<Msg> msgs) {
        // Build conversation text history with agent names
        StringBuilder conversationHistory = new StringBuilder();
        conversationHistory.append(conversationHistoryPrompt);
        conversationHistory.append(HISTORY_START_TAG).append("\n");

        // Collect multimodal content (images/audio) separately
        List<ChatCompletionContentPart> multimodalParts = new ArrayList<>();

        for (Msg msg : msgs) {
            String agentName = msg.getName() != null ? msg.getName() : "Unknown";
            String roleLabel = formatRoleLabel(msg.getRole());

            // Process all blocks: text goes to history, images/audio go to ContentParts
            // Note: ThinkingBlock is intentionally NOT included in conversation history
            List<ContentBlock> blocks = msg.getContent();
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock tb) {
                    conversationHistory
                            .append(roleLabel)
                            .append(" ")
                            .append(agentName)
                            .append(": ")
                            .append(tb.getText())
                            .append("\n");
                } else if (block instanceof ImageBlock imageBlock) {
                    // Preserve images as ContentParts
                    // Note: Do NOT add "[Image]" marker to conversation history text
                    // (images are represented only as ContentParts)
                    try {
                        multimodalParts.add(convertImageBlockToContentPart(imageBlock));
                    } catch (Exception e) {
                        log.warn("Failed to process ImageBlock: {}", e.getMessage());
                        conversationHistory
                                .append(roleLabel)
                                .append(" ")
                                .append(agentName)
                                .append(": [Image - processing failed]\n");
                    }
                } else if (block instanceof AudioBlock audioBlock) {
                    // Preserve audio as ContentParts
                    // Note: Do NOT add "[Audio]" marker to conversation history text
                    // (audio is represented only as ContentParts)
                    try {
                        multimodalParts.add(convertAudioBlockToContentPart(audioBlock));
                    } catch (Exception e) {
                        log.warn("Failed to process AudioBlock: {}", e.getMessage());
                        conversationHistory
                                .append(roleLabel)
                                .append(" ")
                                .append(agentName)
                                .append(": [Audio - processing failed]\n");
                    }
                } else if (block instanceof ThinkingBlock) {
                    // IMPORTANT: ThinkingBlock is NOT included in conversation history
                    // for multi-agent formatters
                    log.debug("Skipping ThinkingBlock in multi-agent conversation for OpenAI API");
                } else if (block instanceof ToolResultBlock toolResult) {
                    // Use convertToolResultToString to handle multimodal content
                    String resultText = convertToolResultToString(toolResult.getOutput());
                    String finalResultText =
                            !resultText.isEmpty() ? resultText : "[Empty tool result]";
                    conversationHistory
                            .append(roleLabel)
                            .append(" ")
                            .append(agentName)
                            .append(" (")
                            .append(toolResult.getName())
                            .append("): ")
                            .append(finalResultText)
                            .append("\n");
                }
            }
        }

        conversationHistory.append(HISTORY_END_TAG);

        // Build the user message with multimodal content if needed
        ChatCompletionUserMessageParam.Builder builder = ChatCompletionUserMessageParam.builder();

        if (multimodalParts.isEmpty()) {
            // No multimodal content - use simple text content
            builder.content(conversationHistory.toString());
        } else {
            // Has multimodal content - build ContentPart list
            // First add the text conversation history
            List<ChatCompletionContentPart> allParts = new ArrayList<>();
            allParts.add(
                    ChatCompletionContentPart.ofText(
                            ChatCompletionContentPartText.builder()
                                    .text(conversationHistory.toString())
                                    .build()));

            // Then add all image/audio ContentParts
            allParts.addAll(multimodalParts);

            builder.contentOfArrayOfContentParts(allParts);
        }

        return builder.build();
    }

    private ChatCompletionSystemMessageParam formatSystemMsg(Msg msg) {
        return ChatCompletionSystemMessageParam.builder().content(extractTextContent(msg)).build();
    }

    private List<ChatCompletionMessageParam> formatToolSequence(List<Msg> msgs) {
        List<ChatCompletionMessageParam> result = new ArrayList<>();

        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.ASSISTANT) {
                result.add(ChatCompletionMessageParam.ofAssistant(formatAssistantToolCall(msg)));
            } else if (msg.getRole() == MsgRole.TOOL) {
                result.add(ChatCompletionMessageParam.ofTool(formatToolResult(msg)));
            }
        }

        return result;
    }

    private ChatCompletionAssistantMessageParam formatAssistantToolCall(Msg msg) {
        ChatCompletionAssistantMessageParam.Builder builder =
                ChatCompletionAssistantMessageParam.builder();

        String textContent = extractTextContent(msg);
        if (!textContent.isEmpty()) {
            builder.content(textContent);
        }

        // Handle tool calls
        List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
        if (!toolBlocks.isEmpty()) {
            for (ToolUseBlock toolUse : toolBlocks) {
                String argsJson;
                try {
                    argsJson = objectMapper.writeValueAsString(toolUse.getInput());
                } catch (Exception e) {
                    log.warn("Failed to serialize tool call arguments: {}", e.getMessage());
                    argsJson = "{}";
                }

                var toolCallParam =
                        ChatCompletionMessageFunctionToolCall.builder()
                                .id(toolUse.getId())
                                .function(
                                        ChatCompletionMessageFunctionToolCall.Function.builder()
                                                .name(toolUse.getName())
                                                .arguments(argsJson)
                                                .build())
                                .build();

                builder.addToolCall(toolCallParam);
                log.debug(
                        "Formatted multi-agent tool call: id={}, name={}",
                        toolUse.getId(),
                        toolUse.getName());
            }
        }

        return builder.build();
    }

    private ChatCompletionToolMessageParam formatToolResult(Msg msg) {
        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        String toolCallId =
                result != null ? result.getId() : "tool_call_" + System.currentTimeMillis();

        // Use convertToolResultToString to handle multimodal content
        String content =
                result != null
                        ? convertToolResultToString(result.getOutput())
                        : extractTextContent(msg);

        return ChatCompletionToolMessageParam.builder()
                .content(content)
                .toolCallId(toolCallId)
                .build();
    }

    /**
     * Returns the capabilities of this OpenAI multi-agent formatter.
     *
     * <p>Supported features:
     * <ul>
     *   <li>Multi-agent conversation formatting with history tags</li>
     *   <li>Tool calling API</li>
     *   <li>Vision (images and audio)</li>
     *   <li>Text, thinking, tool use/result blocks</li>
     * </ul>
     *
     * @return the formatter capabilities
     */
    @Override
    public FormatterCapabilities getCapabilities() {
        return FormatterCapabilities.builder()
                .providerName("OpenAI")
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
                                AudioBlock.class))
                .build();
    }

    /**
     * Represents a group of related messages.
     */
    private static class MessageGroup {
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
    private enum MessageGroupType {
        SYSTEM, // System messages
        TOOL_SEQUENCE, // Tool use and tool result messages
        AGENT_CONVERSATION // Regular agent conversation messages
    }
}
