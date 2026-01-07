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
import io.agentscope.core.formatter.ollama.dto.OllamaFunction;
import io.agentscope.core.formatter.ollama.dto.OllamaMessage;
import io.agentscope.core.formatter.ollama.dto.OllamaRequest;
import io.agentscope.core.formatter.ollama.dto.OllamaResponse;
import io.agentscope.core.formatter.ollama.dto.OllamaToolCall;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.ollama.OllamaOptions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formatter for Ollama Chat API.
 * Converts between AgentScope Msg objects and Ollama DTO types.
 *
 */
public class OllamaChatFormatter
        extends AbstractBaseFormatter<OllamaMessage, OllamaResponse, OllamaRequest> {
    private static final Logger log = LoggerFactory.getLogger(OllamaChatFormatter.class);

    private final OllamaMessageConverter messageConverter;
    private final OllamaResponseParser responseParser;
    private final OllamaToolsHelper toolsHelper;
    private final boolean promoteToolResultImages;

    public OllamaChatFormatter() {
        this(false);
    }

    public OllamaChatFormatter(boolean promoteToolResultImages) {
        this.messageConverter = new OllamaMessageConverter();
        this.responseParser = new OllamaResponseParser();
        this.toolsHelper = new OllamaToolsHelper();
        this.promoteToolResultImages = promoteToolResultImages;
    }

    @Override
    protected List<OllamaMessage> doFormat(List<Msg> msgs) {
        List<OllamaMessage> result = new ArrayList<>();

        for (Msg msg : msgs) {
            // Process each message and add to result
            processMessage(msg, result);
        }

        return result;
    }

    /**
     * Process a single message and add the corresponding OllamaMessage(s) to the result list.
     *
     * @param msg the message to process
     * @param result the list to add formatted messages to
     */
    private void processMessage(Msg msg, List<OllamaMessage> result) {
        // Separate content blocks by type
        MessageContent messageContent = separateContentBlocks(msg.getContent());

        // Handle tool result blocks first (they are added directly to the result)
        if (messageContent.toolResultBlocks != null) {
            for (ContentBlock toolResultBlock : messageContent.toolResultBlocks) {
                processToolResultBlock((ToolResultBlock) toolResultBlock, result);
            }
        }

        // Create and add the main message if it has content, images, or tool calls
        if (!messageContent.textBlocks.isEmpty()
                || !messageContent.images.isEmpty()
                || !messageContent.toolUseBlocks.isEmpty()) {

            OllamaMessage mainMessage =
                    createMainOllamaMessage(
                            msg,
                            messageContent.textBlocks,
                            messageContent.toolUseBlocks,
                            messageContent.images);

            if (mainMessage.getContent() != null
                    || mainMessage.getImages() != null
                    || mainMessage.getToolCalls() != null) {
                result.add(mainMessage);
            }
        }
    }

    /**
     * Separate content blocks into different types for easier processing.
     *
     * @param contentBlocks the list of content blocks to separate
     * @return a MessageContent object containing separated blocks
     */
    private MessageContent separateContentBlocks(List<ContentBlock> contentBlocks) {
        MessageContent messageContent = new MessageContent();

        for (ContentBlock block : contentBlocks) {
            if (block instanceof TextBlock) {
                messageContent.textBlocks.add(block);
            } else if (block instanceof ToolUseBlock) {
                messageContent.toolUseBlocks.add(block);
            } else if (block instanceof ToolResultBlock) {
                messageContent.toolResultBlocks.add(block);
            } else if (block instanceof ImageBlock) {
                processImageBlock((ImageBlock) block, messageContent.images);
            } else {
                log.warn(
                        "Unsupported block type {} in the message, skipped.",
                        block.getClass().getSimpleName());
            }
        }

        return messageContent;
    }

    /**
     * Process a tool use block and convert it to an OllamaToolCall.
     *
     * @param block the tool use block to process
     * @param toolCalls the list to add the converted tool call to
     */
    private void processToolUseBlock(ContentBlock block, List<OllamaToolCall> toolCalls) {
        ToolUseBlock toolUseBlock = (ToolUseBlock) block;
        OllamaFunction function =
                new OllamaFunction(toolUseBlock.getName(), toolUseBlock.getInput());
        OllamaToolCall toolCall = new OllamaToolCall(function);
        toolCalls.add(toolCall);
    }

    /**
     * Process a tool result block and add the corresponding OllamaMessage to the result.
     *
     * @param toolResultBlock the tool result block to process
     * @param result the list to add the formatted message to
     */
    private void processToolResultBlock(
            ToolResultBlock toolResultBlock, List<OllamaMessage> result) {
        // Create tool result message
        OllamaMessage toolResultMsg = new OllamaMessage();
        toolResultMsg.setRole("tool");
        toolResultMsg.setToolCallId(toolResultBlock.getId());
        toolResultMsg.setName(toolResultBlock.getName());

        // Extract textual output from tool result output
        StringBuilder textualOutput = new StringBuilder();
        List<ContentBlock> multimodalData = new ArrayList<>();

        for (ContentBlock outputBlock : toolResultBlock.getOutput()) {
            if (outputBlock instanceof TextBlock) {
                if (textualOutput.length() > 0) {
                    textualOutput.append("\n");
                }
                textualOutput.append(((TextBlock) outputBlock).getText());
            } else if (outputBlock instanceof ImageBlock) {
                multimodalData.add(outputBlock);
            }
        }

        toolResultMsg.setContent(textualOutput.toString());
        result.add(toolResultMsg);

        // Handle multimodal data promotion if needed
        if (promoteToolResultImages && !multimodalData.isEmpty()) {
            handleImagePromotion(toolResultBlock, multimodalData, result);
        }
    }

    /**
     * Handle image promotion from tool results.
     *
     * @param toolResultBlock the original tool result block
     * @param multimodalData the multimodal data to promote
     * @param result the list to add the promoted image message to
     */
    private void handleImagePromotion(
            ToolResultBlock toolResultBlock,
            List<ContentBlock> multimodalData,
            List<OllamaMessage> result) {
        List<ContentBlock> promotedBlocks = new ArrayList<>();
        for (ContentBlock multimodalBlock : multimodalData) {
            if (multimodalBlock instanceof ImageBlock) {
                ImageBlock imageBlock = (ImageBlock) multimodalBlock;
                // Add text block with image information
                String imageUrl = imageBlock.getSource().toString();
                if (imageBlock.getSource() instanceof io.agentscope.core.message.URLSource) {
                    imageUrl =
                            ((io.agentscope.core.message.URLSource) imageBlock.getSource())
                                    .getUrl();
                }
                promotedBlocks.add(
                        new TextBlock.Builder()
                                .text("\n- The image from '" + imageUrl + "': ")
                                .build());
                promotedBlocks.add(imageBlock);
            }
        }

        if (!promotedBlocks.isEmpty()) {
            // Create a new user message with system info and promoted blocks
            List<ContentBlock> allPromotedBlocks = new ArrayList<>();
            allPromotedBlocks.add(
                    new TextBlock.Builder()
                            .text(
                                    "<system-info>The following are "
                                            + "the image contents from the tool "
                                            + "result of '"
                                            + toolResultBlock.getName()
                                            + "':")
                            .build());
            allPromotedBlocks.addAll(promotedBlocks);
            allPromotedBlocks.add(new TextBlock.Builder().text("</system-info>").build());

            // Create a temporary message and convert it to OllamaMessage format using the builder
            Msg tempMsg =
                    new Msg.Builder()
                            .name("user")
                            .role(MsgRole.USER)
                            .content(allPromotedBlocks)
                            .build();
            OllamaMessage imagePromotionMsg = messageConverter.convertMessage(tempMsg);
            result.add(imagePromotionMsg);
        }
    }

    /**
     * Process an image block and convert it to base64 string.
     *
     * @param block the image block to process
     * @param images the list to add the converted base64 image to
     */
    private void processImageBlock(ImageBlock block, List<String> images) {
        try {
            String base64Image = new OllamaMediaConverter().convertImageBlockToBase64(block);
            images.add(base64Image);
        } catch (Exception e) {
            log.warn("Failed to convert image block to Ollama format", e);
        }
    }

    /**
     * Create the main OllamaMessage from text content, tool calls, and images.
     *
     * @param msg the original message
     * @param textBlocks the text content blocks
     * @param toolUseBlocks the tool use blocks
     * @param images the base64 encoded images
     * @return the formatted OllamaMessage
     */
    private OllamaMessage createMainOllamaMessage(
            Msg msg,
            List<ContentBlock> textBlocks,
            List<ContentBlock> toolUseBlocks,
            List<String> images) {
        // Create tool calls from tool use blocks
        List<OllamaToolCall> toolCalls = new ArrayList<>();
        for (ContentBlock block : toolUseBlocks) {
            processToolUseBlock(block, toolCalls);
        }

        // Combine text content
        StringBuilder contentMsg = new StringBuilder();
        for (int j = 0; j < textBlocks.size(); j++) {
            if (j > 0) contentMsg.append("\n");
            contentMsg.append(((TextBlock) textBlocks.get(j)).getText());
        }

        OllamaMessage msgOllama = new OllamaMessage();
        msgOllama.setRole(msg.getRole().name().toLowerCase());
        msgOllama.setContent(contentMsg.length() > 0 ? contentMsg.toString() : null);

        if (!images.isEmpty()) {
            msgOllama.setImages(images);
        }

        if (!toolCalls.isEmpty()) {
            msgOllama.setToolCalls(toolCalls);
        }

        return msgOllama;
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
        // Look for image paths in the format "image can be found at: ./path"
        String content = convertedMsg.getContent();

        // Find image paths in the content
        Pattern pattern = Pattern.compile("can be found at: ([^\s\n]+)");
        Matcher matcher = pattern.matcher(content);

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
                    imageMsg.setImages(Collections.singletonList(base64Image));
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

        OllamaRequest.Builder builder =
                OllamaRequest.builder().model(model).messages(messages).stream(stream);

        OllamaRequest request = builder.build();

        applyOptions(request, options, defaultOptions);
        if (tools != null && !tools.isEmpty()) {
            applyTools(request, tools);
        }
        applyToolChoice(request, toolChoice);

        return request;
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
