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

import io.agentscope.core.formatter.anthropic.dto.AnthropicContent;
import io.agentscope.core.formatter.anthropic.dto.AnthropicMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts AgentScope Msg objects to Anthropic DTO types.
 *
 * <p>
 * This class handles all message role conversions including system, user,
 * assistant, and tool
 * messages. It supports multimodal content (text, images) and tool calling
 * functionality.
 *
 * <p>
 * Important: In Anthropic API, only the first message can be a system message.
 * Non-first system
 * messages are converted to user messages.
 */
public class AnthropicMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicMessageConverter.class);

    private final AnthropicMediaConverter mediaConverter;
    private final Function<List<ContentBlock>, String> toolResultConverter;

    /**
     * Create an AnthropicMessageConverter with required dependency functions.
     *
     * @param toolResultConverter Function to convert tool result blocks to strings
     */
    public AnthropicMessageConverter(Function<List<ContentBlock>, String> toolResultConverter) {
        this(toolResultConverter, new AnthropicMediaConverter());
    }

    /**
     * Create an AnthropicMessageConverter with custom media converter and default
     * tool result converter.
     *
     * @param mediaConverter Custom AnthropicMediaConverter
     */
    public AnthropicMessageConverter(AnthropicMediaConverter mediaConverter) {
        this(
                blocks -> {
                    StringBuilder sb = new StringBuilder();
                    if (blocks != null) {
                        for (ContentBlock block : blocks) {
                            if (block instanceof TextBlock tb) {
                                sb.append(tb.getText());
                            }
                        }
                    }
                    return sb.toString();
                },
                mediaConverter);
    }

    public AnthropicMessageConverter(
            Function<List<ContentBlock>, String> toolResultConverter,
            AnthropicMediaConverter mediaConverter) {
        this.toolResultConverter = toolResultConverter;
        this.mediaConverter = mediaConverter;
    }

    /**
     * Convert list of Msg to list of Anthropic messages. Handles the special case
     * where tool
     * results need to be in separate user messages.
     *
     * @param messages The messages to convert
     * @return List of AnthropicMessage for Anthropic API
     */
    public List<AnthropicMessage> convert(List<Msg> messages) {
        List<AnthropicMessage> result = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            boolean isFirstMessage = (i == 0);

            // Special handling for tool results - they create separate user messages
            if (msg.hasContentBlocks(ToolResultBlock.class)) {
                // Add non-tool-result content first (if any)
                List<ContentBlock> nonToolBlocks = new ArrayList<>();
                List<ToolResultBlock> toolResults = new ArrayList<>();

                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof ToolResultBlock tr) {
                        toolResults.add(tr);
                    } else {
                        nonToolBlocks.add(block);
                    }
                }

                // Add regular content if present
                if (!nonToolBlocks.isEmpty()) {
                    AnthropicMessage regularMsg = convertMessageContent(msg, nonToolBlocks, i == 0);
                    if (regularMsg != null) {
                        result.add(regularMsg);
                    }
                }

                // Add tool results as separate user messages
                for (ToolResultBlock toolResult : toolResults) {
                    result.add(convertToolResult(toolResult));
                }
            } else {
                AnthropicMessage anthropicMsg =
                        convertMessageContent(msg, msg.getContent(), isFirstMessage);
                if (anthropicMsg != null) {
                    result.add(anthropicMsg);
                }
            }
        }

        return result;
    }

    /**
     * Convert message content to AnthropicMessage.
     */
    private AnthropicMessage convertMessageContent(
            Msg msg, List<ContentBlock> blocks, boolean isFirstMessage) {
        String role = convertRole(msg.getRole(), isFirstMessage);
        List<AnthropicContent> contentBlocks = new ArrayList<>();

        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                contentBlocks.add(AnthropicContent.text(tb.getText()));
            } else if (block instanceof ThinkingBlock thinkingBlock) {
                // Anthropic supports thinking blocks natively
                contentBlocks.add(AnthropicContent.thinking(thinkingBlock.getThinking()));
            } else if (block instanceof ImageBlock ib) {
                try {
                    AnthropicContent.ImageSource imageSource = mediaConverter.convertImageBlock(ib);
                    contentBlocks.add(
                            AnthropicContent.image(
                                    imageSource.getMediaType(), imageSource.getData()));
                } catch (Exception e) {
                    log.warn("Failed to process ImageBlock: {}", e.getMessage());
                    contentBlocks.add(
                            AnthropicContent.text(
                                    "[Image - processing failed: " + e.getMessage() + "]"));
                }
            } else if (block instanceof ToolUseBlock tub) {
                Map<String, Object> input =
                        tub.getInput() != null ? tub.getInput() : new HashMap<>();
                contentBlocks.add(AnthropicContent.toolUse(tub.getId(), tub.getName(), input));
            }
            // ToolResultBlock is handled separately in convert() method
        }

        if (contentBlocks.isEmpty()) {
            return null;
        }

        return new AnthropicMessage(role, contentBlocks);
    }

    /**
     * Convert tool result to separate user message.
     */
    private AnthropicMessage convertToolResult(ToolResultBlock toolResult) {
        // Convert output to content string or blocks
        Object output = toolResult.getOutput();
        Object contentValue;

        if (output == null) {
            contentValue = "";
        } else if (output instanceof List) {
            // Multi-block output - convert to list of content blocks
            List<?> outputList = (List<?>) output;
            List<AnthropicContent> blocks = new ArrayList<>();

            for (Object item : outputList) {
                if (item instanceof ContentBlock cb) {
                    if (cb instanceof TextBlock tb) {
                        blocks.add(AnthropicContent.text(tb.getText()));
                    } else if (cb instanceof ImageBlock ib) {
                        try {
                            AnthropicContent.ImageSource imageSource =
                                    mediaConverter.convertImageBlock(ib);
                            blocks.add(
                                    AnthropicContent.image(
                                            imageSource.getMediaType(), imageSource.getData()));
                        } catch (Exception e) {
                            log.warn("Failed to process ImageBlock in tool result: {}", e);
                        }
                    }
                }
            }
            contentValue = blocks.isEmpty() ? "" : blocks;
        } else {
            // String output
            String outputStr =
                    output instanceof String
                            ? (String) output
                            : (output instanceof ContentBlock
                                    ? toolResultConverter.apply(List.of((ContentBlock) output))
                                    : output.toString());
            contentValue = outputStr;
        }

        // Create tool result content
        AnthropicContent toolResultContent =
                AnthropicContent.toolResult(toolResult.getId(), contentValue, null);

        // Wrap in user message
        AnthropicMessage message = new AnthropicMessage("user");
        message.addContent(toolResultContent);
        return message;
    }

    /**
     * Convert AgentScope MsgRole to Anthropic role string. Important: Anthropic
     * only allows the
     * first message to be system. Non-first system messages are converted to user.
     */
    private String convertRole(MsgRole msgRole, boolean isFirstMessage) {
        return switch (msgRole) {
            case SYSTEM -> "user"; // Anthropic uses user for system messages
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "user"; // Tool results are always user messages
        };
    }

    /**
     * Extract system message content if present in the first message.
     */
    public String extractSystemMessage(List<Msg> messages) {
        if (messages.isEmpty()) {
            return null;
        }

        Msg first = messages.get(0);
        if (first.getRole() == MsgRole.SYSTEM) {
            StringBuilder sb = new StringBuilder();
            for (ContentBlock block : first.getContent()) {
                if (block instanceof TextBlock tb) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(tb.getText());
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }

        return null;
    }
}
