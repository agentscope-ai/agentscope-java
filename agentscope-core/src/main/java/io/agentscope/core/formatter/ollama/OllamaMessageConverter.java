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

import io.agentscope.core.formatter.ollama.dto.OllamaFunction;
import io.agentscope.core.formatter.ollama.dto.OllamaMessage;
import io.agentscope.core.formatter.ollama.dto.OllamaToolCall;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converter for transforming AgentScope Msg objects to OllamaMessage objects.
 *
 */
public class OllamaMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(OllamaMessageConverter.class);
    private final OllamaMediaConverter mediaConverter = new OllamaMediaConverter();

    /**
     * Converts an AgentScope Msg object to an OllamaMessage object.
     *
     * @param msg The AgentScope message to convert.
     * @return The converted OllamaMessage object.
     */
    public OllamaMessage convertMessage(Msg msg) {
        OllamaMessage ollamaMsg = new OllamaMessage();

        // 1. Map Role and Handle Tool Result
        if (msg.getRole() == MsgRole.TOOL) {
            ollamaMsg.setRole("tool");
            // Handle Tool Result
            ToolResultBlock toolResult = msg.getFirstContentBlock(ToolResultBlock.class);
            if (toolResult != null) {
                // Extract text content and image paths from output blocks
                StringBuilder contentBuilder = new StringBuilder();

                // Add text content
                String textContent = extractTextContentFromBlocks(toolResult.getOutput());
                if (textContent != null && !textContent.isEmpty()) {
                    contentBuilder.append(textContent);
                }

                // Add image information in the format expected by the promotion logic
                List<String> imagePaths = extractImagePathsFromBlocks(toolResult.getOutput());
                if (!imagePaths.isEmpty()) {
                    if (contentBuilder.length() > 0) {
                        contentBuilder.append("\n");
                    }
                    for (String imagePath : imagePaths) {
                        contentBuilder
                                .append("- The returned image can be found at: ")
                                .append(imagePath)
                                .append("\n");
                    }
                }

                ollamaMsg.setContent(contentBuilder.toString().trim());

                // Set tool_call_id and name if available
                if (toolResult.getId() != null) {
                    ollamaMsg.setToolCallId(toolResult.getId());
                }
                if (toolResult.getName() != null) {
                    ollamaMsg.setName(toolResult.getName());
                }
            } else {
                // Fallback if no ToolResultBlock
                ollamaMsg.setContent(extractTextContent(msg));
            }
            return ollamaMsg;
        }

        // Standard role mapping
        ollamaMsg.setRole(msg.getRole().name().toLowerCase());

        // 2. Map Content and Tool Calls (for Assistant)
        StringBuilder textContent = new StringBuilder();
        List<String> images = new ArrayList<>();
        List<OllamaToolCall> toolCalls = new ArrayList<>();

        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock) {
                textContent.append(((TextBlock) block).getText());
            } else if (block instanceof ImageBlock) {
                ImageBlock imageBlock = (ImageBlock) block;
                try {
                    String base64Image = mediaConverter.convertImageBlockToBase64(imageBlock);
                    images.add(base64Image);
                } catch (Exception e) {
                    log.error("Failed to convert image block to Ollama format", e);
                    // Decide whether to fail hard or skip.
                    // For now, logging error and skipping to avoid crashing the whole request,
                    // but usually image is important context.
                }
            } else if (block instanceof ToolUseBlock) {
                ToolUseBlock toolUse = (ToolUseBlock) block;
                // Convert ToolUseBlock to OllamaToolCall
                Map<String, Object> args = toolUse.getInput();

                OllamaFunction function = new OllamaFunction(toolUse.getName(), args);
                OllamaToolCall toolCall = new OllamaToolCall(function);
                // Set ID if available
                if (toolUse.getId() != null) {
                    // Note: OllamaToolCall may not have direct ID field, this depends on the DTO
                    // structure
                    // We might need to handle this differently based on Ollama API requirements
                }
                toolCalls.add(toolCall);
            }
        }

        if (textContent.length() > 0) {
            ollamaMsg.setContent(textContent.toString());
        }

        if (!images.isEmpty()) {
            ollamaMsg.setImages(images);
        }

        if (!toolCalls.isEmpty()) {
            ollamaMsg.setToolCalls(toolCalls);
        }

        return ollamaMsg;
    }

    /**
     * Extracts text content from a message by combining all text blocks and tool result outputs.
     *
     * @param msg The message to extract text content from
     * @return The combined text content as a string
     */
    private String extractTextContent(Msg msg) {
        return msg.getContent().stream()
                .flatMap(
                        block -> {
                            if (block instanceof TextBlock tb) {
                                return Stream.of(tb.getText());
                            } else if (block instanceof ToolResultBlock toolResult) {
                                // Extract text from tool result output
                                return toolResult.getOutput().stream()
                                        .filter(output -> output instanceof TextBlock)
                                        .map(output -> ((TextBlock) output).getText());
                            }
                            return Stream.empty();
                        })
                .collect(Collectors.joining("\n"));
    }

    /**
     * Extracts text content from a list of content blocks, including text from tool result outputs.
     *
     * @param blocks The list of content blocks to extract text from
     * @return The combined text content as a string
     */
    private String extractTextContentFromBlocks(List<ContentBlock> blocks) {
        return blocks.stream()
                .flatMap(
                        block -> {
                            if (block instanceof TextBlock tb) {
                                return Stream.of(tb.getText());
                            } else if (block instanceof ToolResultBlock toolResult) {
                                // Extract text from tool result output
                                return toolResult.getOutput().stream()
                                        .filter(output -> output instanceof TextBlock)
                                        .map(output -> ((TextBlock) output).getText());
                            }
                            return Stream.empty();
                        })
                .collect(Collectors.joining("\n"));
    }

    /**
     * Extracts image paths from a list of content blocks.
     *
     * @param blocks The list of content blocks to extract image paths from
     * @return A list of image paths as strings
     */
    private List<String> extractImagePathsFromBlocks(List<ContentBlock> blocks) {
        List<String> imagePaths = new ArrayList<>();

        for (ContentBlock block : blocks) {
            if (block instanceof ImageBlock) {
                ImageBlock imageBlock = (ImageBlock) block;
                if (imageBlock.getSource() != null) {
                    // Extract the URL path from the source
                    String imagePath = imageBlock.getSource().toString();
                    if (imageBlock.getSource() instanceof URLSource) {
                        imagePath = ((URLSource) imageBlock.getSource()).getUrl();
                    }
                    imagePaths.add(imagePath);
                }
            }
        }

        return imagePaths;
    }
}
