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

import io.agentscope.core.formatter.dashscope.dto.DashScopeContentPart;
import io.agentscope.core.formatter.dashscope.dto.DashScopeMessage;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.VideoBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts AgentScope Msg objects to DashScope DTO message types.
 *
 * <p>This class provides unified conversion methods for both simple text messages
 * and multimodal messages containing images, videos, and audio.
 */
public class DashScopeMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(DashScopeMessageConverter.class);

    private final DashScopeMediaConverter mediaConverter;
    private final DashScopeToolsHelper toolsHelper;
    private final Function<List<ContentBlock>, String> toolResultConverter;

    public DashScopeMessageConverter(Function<List<ContentBlock>, String> toolResultConverter) {
        this.mediaConverter = new DashScopeMediaConverter();
        this.toolsHelper = new DashScopeToolsHelper();
        this.toolResultConverter = toolResultConverter;
    }

    /**
     * Convert single Msg to DashScopeMessage.
     *
     * <p>This is the main entry point for message conversion. It automatically selects
     * the appropriate format based on the message content and the hasMediaContent flag.
     *
     * @param msg The message to convert
     * @param useMultimodalFormat Whether to use multimodal content format (List of content parts)
     * @return The converted DashScopeMessage
     */
    public DashScopeMessage convertToMessage(Msg msg, boolean useMultimodalFormat) {
        if (useMultimodalFormat) {
            return convertToMultimodalContent(msg);
        } else {
            return convertToSimpleContent(msg);
        }
    }

    /**
     * Convert message to multimodal format with List of content parts.
     *
     * <p>This unified method handles all message roles including TOOL, ASSISTANT, USER, and SYSTEM.
     * It properly handles all content block types: TextBlock, ImageBlock, VideoBlock, AudioBlock,
     * ThinkingBlock (skipped), and ToolResultBlock.
     *
     * @param msg The message to convert
     * @return DashScopeMessage with multimodal content format
     */
    private DashScopeMessage convertToMultimodalContent(Msg msg) {
        // Special handling for TOOL role messages
        if (msg.getRole() == MsgRole.TOOL) {
            return convertToolRoleMessage(msg);
        }

        List<DashScopeContentPart> contents = new ArrayList<>();

        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock tb) {
                contents.add(DashScopeContentPart.text(tb.getText()));
            } else if (block instanceof ImageBlock imageBlock) {
                try {
                    contents.add(mediaConverter.convertImageBlockToContentPart(imageBlock));
                } catch (Exception e) {
                    log.warn("Failed to process ImageBlock: {}", e.getMessage());
                    contents.add(
                            DashScopeContentPart.text(
                                    "[Image - processing failed: " + e.getMessage() + "]"));
                }
            } else if (block instanceof VideoBlock videoBlock) {
                try {
                    contents.add(mediaConverter.convertVideoBlockToContentPart(videoBlock));
                } catch (Exception e) {
                    log.warn("Failed to process VideoBlock: {}", e.getMessage());
                    contents.add(
                            DashScopeContentPart.text(
                                    "[Video - processing failed: " + e.getMessage() + "]"));
                }
            } else if (block instanceof AudioBlock audioBlock) {
                try {
                    contents.add(mediaConverter.convertAudioBlockToContentPart(audioBlock));
                } catch (Exception e) {
                    log.warn("Failed to process AudioBlock: {}", e.getMessage());
                    contents.add(
                            DashScopeContentPart.text(
                                    "[Audio - processing failed: " + e.getMessage() + "]"));
                }
            } else if (block instanceof ThinkingBlock) {
                log.debug("Skipping ThinkingBlock when formatting for DashScope");
            } else if (block instanceof ToolResultBlock toolResult) {
                String toolResultText = toolResultConverter.apply(toolResult.getOutput());
                if (!toolResultText.isEmpty()) {
                    contents.add(DashScopeContentPart.text(toolResultText));
                }
            }
        }

        // Ensure non-empty content (required by some VL APIs)
        if (contents.isEmpty()) {
            contents.add(DashScopeContentPart.text(""));
        }

        DashScopeMessage.Builder builder =
                DashScopeMessage.builder()
                        .role(msg.getRole().name().toLowerCase())
                        .content(contents);

        // Handle ASSISTANT tool calls
        if (msg.getRole() == MsgRole.ASSISTANT) {
            List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
            if (!toolBlocks.isEmpty()) {
                builder.toolCalls(toolsHelper.convertToolCalls(toolBlocks));
            }
        }

        return builder.build();
    }

    /**
     * Convert TOOL role message to DashScopeMessage.
     *
     * <p>TOOL role messages require special handling with toolCallId and name fields.
     *
     * @param msg The TOOL role message
     * @return DashScopeMessage with tool response format
     */
    private DashScopeMessage convertToolRoleMessage(Msg msg) {
        ToolResultBlock toolResult = msg.getFirstContentBlock(ToolResultBlock.class);
        if (toolResult != null) {
            String toolResultText = toolResultConverter.apply(toolResult.getOutput());
            List<DashScopeContentPart> content = new ArrayList<>();
            content.add(DashScopeContentPart.text(toolResultText));

            return DashScopeMessage.builder()
                    .role("tool")
                    .toolCallId(toolResult.getId())
                    .name(toolResult.getName())
                    .content(content)
                    .build();
        }

        // Fallback: no ToolResultBlock found, use text content
        List<DashScopeContentPart> content = new ArrayList<>();
        content.add(DashScopeContentPart.text(extractTextContent(msg)));
        return DashScopeMessage.builder().role("tool").content(content).build();
    }

    /**
     * Convert message to simple text format.
     *
     * <p>This method is used when multimodal content is not needed. It handles
     * TOOL role messages and ASSISTANT tool calls appropriately.
     *
     * @param msg The message to convert
     * @return DashScopeMessage with simple text content
     */
    private DashScopeMessage convertToSimpleContent(Msg msg) {
        // Check if message contains tool result - if so, treat as TOOL role
        ToolResultBlock toolResult = msg.getFirstContentBlock(ToolResultBlock.class);
        if (toolResult != null
                && (msg.getRole() == MsgRole.TOOL || msg.getRole() == MsgRole.SYSTEM)) {
            return DashScopeMessage.builder()
                    .role("tool")
                    .toolCallId(toolResult.getId())
                    .name(toolResult.getName())
                    .content(toolResultConverter.apply(toolResult.getOutput()))
                    .build();
        }

        DashScopeMessage.Builder builder =
                DashScopeMessage.builder().role(msg.getRole().name().toLowerCase());

        if (msg.getRole() == MsgRole.ASSISTANT) {
            List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
            if (!toolBlocks.isEmpty()) {
                // Assistant with tool calls
                builder.toolCalls(toolsHelper.convertToolCalls(toolBlocks));
                String textContent = extractTextContent(msg);
                if (textContent.isEmpty()) {
                    builder.content((String) null);
                } else {
                    builder.content(textContent);
                }
            } else {
                builder.content(extractTextContent(msg));
            }
        } else {
            builder.content(extractTextContent(msg));
        }

        return builder.build();
    }

    /**
     * Extract text content from message by concatenating all TextBlocks.
     *
     * @param msg The message to extract text from
     * @return Concatenated text content
     */
    private String extractTextContent(Msg msg) {
        return msg.getContent().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
    }
}
