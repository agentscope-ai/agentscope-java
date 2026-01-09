/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.core.formatter.ollama;

import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.formatter.ollama.dto.OllamaMessage;
import io.agentscope.core.formatter.ollama.dto.OllamaRequest;
import io.agentscope.core.formatter.ollama.dto.OllamaResponse;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.ollama.OllamaOptions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ollama formatter for multi-agent conversations.
 * Converts AgentScope Msg objects to Ollama DTO Message objects with multi-agent support.
 * Collapses multi-agent conversation into a single user message with history tags.
 *
 */
public class OllamaMultiAgentFormatter
        extends AbstractBaseFormatter<OllamaMessage, OllamaResponse, OllamaRequest> {
    private static final Logger log = LoggerFactory.getLogger(OllamaMultiAgentFormatter.class);

    private static final String DEFAULT_CONVERSATION_HISTORY_PROMPT =
            "# Conversation History\n"
                    + "The content between <history></history> tags contains your conversation"
                    + " history\n";

    private final OllamaMessageConverter messageConverter;
    private final OllamaResponseParser responseParser;
    private final OllamaToolsHelper toolsHelper;
    private final OllamaConversationMerger conversationMerger;
    private final String conversationHistoryPrompt;
    private final boolean promoteToolResultImages;

    /**
     * Create a OllamaMultiAgentFormatter with default conversation history prompt.
     */
    public OllamaMultiAgentFormatter() {
        this(DEFAULT_CONVERSATION_HISTORY_PROMPT);
    }

    /**
     * Create a OllamaMultiAgentFormatter with custom conversation history prompt.
     *
     * @param conversationHistoryPrompt The prompt to prepend before conversation history
     */
    public OllamaMultiAgentFormatter(String conversationHistoryPrompt) {
        this(conversationHistoryPrompt, false);
    }

    public OllamaMultiAgentFormatter(boolean promoteToolResultImages) {
        this(DEFAULT_CONVERSATION_HISTORY_PROMPT, promoteToolResultImages);
    }

    public OllamaMultiAgentFormatter(
            String conversationHistoryPrompt, boolean promoteToolResultImages) {
        this.messageConverter = new OllamaMessageConverter();
        this.responseParser = new OllamaResponseParser();
        this.toolsHelper = new OllamaToolsHelper();
        this.conversationMerger = new OllamaConversationMerger(conversationHistoryPrompt);
        this.conversationHistoryPrompt = conversationHistoryPrompt;
        this.promoteToolResultImages = promoteToolResultImages;
    }

    @Override
    protected List<OllamaMessage> doFormat(List<Msg> msgs) {
        List<OllamaMessage> result = new ArrayList<>();
        int startIndex = 0;

        // Process system message first (if any) - output separately
        if (!msgs.isEmpty() && msgs.get(0).getRole() == MsgRole.SYSTEM) {
            OllamaMessage systemMsg = new OllamaMessage();
            systemMsg.setRole("system");
            systemMsg.setContent(extractTextContent(msgs.get(0)));
            result.add(systemMsg);
            startIndex = 1;
        }

        // If there's only one message after system and it's not a tool message, convert directly
        // to avoid wrapping in conversation history
        List<Msg> remainingMsgs = msgs.subList(startIndex, msgs.size());
        if (remainingMsgs.size() == 1 && !isToolMessage(remainingMsgs.get(0))) {
            result.add(messageConverter.convertMessage(remainingMsgs.get(0)));
            return result;
        }

        // Group remaining messages and process each group
        List<MessageGroup> groups = groupMessagesSequentially(remainingMsgs);
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
                // Format tool sequence directly using the same approach as OllamaChatFormatter
                List<OllamaMessage> toolMessages = formatToolSeq(group.messages);

                // If promoteToolResultImages is enabled, check for images in tool results
                if (promoteToolResultImages) {
                    for (int i = 0; i < toolMessages.size(); i++) {
                        OllamaMessage toolMsg = toolMessages.get(i);
                        result.add(toolMsg);

                        // If this is a tool result with images, add a separate user message
                        if (isToolResultWithImages(toolMsg)) {
                            OllamaMessage imageMsg =
                                    createImagePromotionMessage(group.messages.get(i), toolMsg);
                            if (imageMsg != null) {
                                result.add(imageMsg);
                            }
                        }
                    }
                } else {
                    result.addAll(toolMessages);
                }
            }
        }

        return result;
    }

    /**
     * Checks if the given OllamaMessage contains images in its content.
     *
     * @param msg The OllamaMessage to check
     * @return true if the message contains images and the required text patterns, false otherwise
     */
    private boolean isToolResultWithImages(OllamaMessage msg) {
        return "tool".equals(msg.getRole())
                && msg.getContent() != null
                && msg.getContent().contains("image")
                && msg.getContent().contains("can be found at:");
    }

    /**
     * Creates an image promotion message when images are found in tool results.
     *
     * @param originalMsg The original message containing the image
     * @param convertedMsg The converted OllamaMessage
     * @return The OllamaMessage with image promotion information, or null if creation fails
     */
    private OllamaMessage createImagePromotionMessage(Msg originalMsg, OllamaMessage convertedMsg) {
        // Extract image paths from the tool result content
        String content = convertedMsg.getContent();

        // Find image paths in the format "image can be found at: ./path"
        Pattern pattern = Pattern.compile("can be found at: ([^\s\n]+)");
        java.util.regex.Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            String imagePath = matcher.group(1);

            // Try to convert the image to base64
            try {
                ImageBlock imageBlock = extractImageBlockFromMsg(originalMsg);
                if (imageBlock != null) {
                    String base64Image =
                            new OllamaMediaConverter().convertImageBlockToBase64(imageBlock);

                    OllamaMessage imageMsg = new OllamaMessage();
                    imageMsg.setRole("user");
                    imageMsg.setContent(
                            "<system-info>The following are "
                                    + "the image contents from the tool "
                                    + "result of '"
                                    + convertedMsg.getName()
                                    + "':\n\n"
                                    + "- The image from '"
                                    + imagePath
                                    + "': \n</system-info>");
                    imageMsg.setImages(java.util.Collections.singletonList(base64Image));
                    return imageMsg;
                }
            } catch (Exception e) {
                // Log error but don't fail the whole request
                log.warn("Failed to promote image from tool result", e);
            }
        }

        return null;
    }

    /**
     * Extracts an ImageBlock from the given message's content.
     *
     * @param msg The message to extract the image block from
     * @return The ImageBlock if found, null otherwise
     */
    private ImageBlock extractImageBlockFromMsg(Msg msg) {
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ImageBlock) {
                return (ImageBlock) block;
            } else if (block instanceof ToolResultBlock) {
                ToolResultBlock toolResult = (ToolResultBlock) block;
                for (ContentBlock outputBlock : toolResult.getOutput()) {
                    if (outputBlock instanceof ImageBlock) {
                        return (ImageBlock) outputBlock;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public ChatResponse parseResponse(OllamaResponse result, Instant startTime) {
        return responseParser.parseResponse(result);
    }

    @Override
    public void applyOptions(
            OllamaRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        toolsHelper.applyOptions(request, options, defaultOptions);
    }

    /**
     * Apply OllamaOptions to the request.
     *
     * @param request The request to apply options to
     * @param options The runtime options
     * @param defaultOptions The default options
     */
    public void applyOptions(
            OllamaRequest request, OllamaOptions options, OllamaOptions defaultOptions) {
        toolsHelper.applyOptions(request, options, defaultOptions);
    }

    @Override
    public void applyTools(OllamaRequest request, List<ToolSchema> tools) {
        toolsHelper.applyTools(request, tools);
    }

    @Override
    public void applyToolChoice(OllamaRequest request, ToolChoice toolChoice) {
        toolsHelper.applyToolChoice(request, toolChoice);
    }

    /**
     * Build a complete OllamaRequest for the API call.
     *
     * @param model Model name
     * @param messages Formatted Ollama messages
     * @param stream Whether to enable streaming
     * @param options Generation options
     * @param defaultOptions Default generation options
     * @param tools Tool schemas
     * @param toolChoice Tool choice configuration
     * @return Complete OllamaRequest ready for API call
     */
    public OllamaRequest buildRequest(
            String model,
            List<OllamaMessage> messages,
            boolean stream,
            OllamaOptions options,
            OllamaOptions defaultOptions,
            List<ToolSchema> tools,
            ToolChoice toolChoice) {

        OllamaRequest request = new OllamaRequest();
        request.setModel(model);
        request.setMessages(messages);
        request.setStream(stream);

        // Apply options
        applyOptions(request, options, defaultOptions);

        // Apply tools if present
        if (tools != null && !tools.isEmpty()) {
            applyTools(request, tools);
            if (toolChoice != null) {
                applyToolChoice(request, toolChoice);
            }
        }

        return request;
    }

    // ========== Private Helper Methods ==========

    /**
     * Check if a message is a tool-related message.
     *
     * @param msg The message to check
     * @return true if the message is a tool message, false otherwise
     */
    private boolean isToolMessage(Msg msg) {
        return msg.getRole() == MsgRole.TOOL
                || msg.hasContentBlocks(ToolUseBlock.class)
                || msg.hasContentBlocks(ToolResultBlock.class);
    }

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

    /**
     * Format tool sequence messages to OllamaMessage format using the same logic as OllamaChatFormatter
     *
     * @param msgs The list of messages to format
     * @return List of formatted OllamaMessage objects
     */
    private List<OllamaMessage> formatToolSeq(List<Msg> msgs) {
        List<OllamaMessage> result = new ArrayList<>();
        for (Msg msg : msgs) {
            // Convert the message based on its role and content
            OllamaMessage ollamaMsg = messageConverter.convertMessage(msg);

            // Set the role based on the message role
            if (msg.getRole() == MsgRole.TOOL) {
                ollamaMsg.setRole("tool");
            } else {
                ollamaMsg.setRole(msg.getRole().name().toLowerCase());
            }

            // For tool messages, we need to extract the content properly
            if (msg.getRole() == MsgRole.TOOL) {
                // Handle tool result messages specially
                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof ToolResultBlock) {
                        ToolResultBlock toolResultBlock = (ToolResultBlock) block;
                        ollamaMsg.setToolCallId(toolResultBlock.getId());
                        ollamaMsg.setName(toolResultBlock.getName());

                        // Extract textual output from tool result
                        StringBuilder textualOutput = new StringBuilder();
                        List<ContentBlock> multimodalOutputs = new ArrayList<>();

                        for (ContentBlock outputBlock : toolResultBlock.getOutput()) {
                            if (outputBlock instanceof TextBlock) {
                                if (textualOutput.length() > 0) {
                                    textualOutput.append("\n");
                                }
                                textualOutput.append(((TextBlock) outputBlock).getText());
                            } else if (outputBlock instanceof ImageBlock) {
                                multimodalOutputs.add(outputBlock);
                            }
                        }

                        // If there are multimodal outputs (like images), we might need to handle
                        // them specially
                        // For now, just include the text content
                        ollamaMsg.setContent(textualOutput.toString());

                        // Check if there are images that need to be promoted
                        if (!multimodalOutputs.isEmpty() && promoteToolResultImages) {
                            // Add special text to content that can be detected by
                            // isToolResultWithImages
                            for (ContentBlock multimodalBlock : multimodalOutputs) {
                                if (multimodalBlock instanceof ImageBlock) {
                                    ImageBlock imageBlock = (ImageBlock) multimodalBlock;
                                    String imageUrl = imageBlock.getSource().toString();
                                    if (imageBlock.getSource() instanceof URLSource) {
                                        imageUrl = ((URLSource) imageBlock.getSource()).getUrl();
                                    }
                                    ollamaMsg.setContent(
                                            ollamaMsg.getContent()
                                                    + "\nimage can be found at: "
                                                    + imageUrl);
                                }
                            }
                        }
                        break; // Process first tool result block
                    }
                }
            }

            result.add(ollamaMsg);
        }
        return result;
    }

    /**
     * Convert list of content blocks (usually from tool result) to string.
     *
     * @param blocks The list of content blocks to convert
     * @return The string representation of the content blocks
     */
    public String convertToolResultToString(List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock) {
                sb.append(((TextBlock) block).getText());
            } else if (block instanceof ToolResultBlock) {
                // Extract text from the output blocks of ToolResultBlock
                List<ContentBlock> outputBlocks = ((ToolResultBlock) block).getOutput();
                for (ContentBlock outputBlock : outputBlocks) {
                    if (outputBlock instanceof TextBlock) {
                        sb.append(((TextBlock) outputBlock).getText());
                    }
                }
            }
        }
        return sb.toString();
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

    /**
     * Helper class to hold separated content blocks.
     */
    private static class MessageContent {
        List<ContentBlock> textBlocks = new ArrayList<>();
        List<ContentBlock> toolUseBlocks = new ArrayList<>();
        List<ContentBlock> toolResultBlocks = new ArrayList<>();
        List<String> images = new ArrayList<>();
    }
}
