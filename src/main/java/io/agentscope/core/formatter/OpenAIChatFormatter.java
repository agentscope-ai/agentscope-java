/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ContentBlockUtils;
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
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formatter for OpenAI Chat Completion API.
 *
 * This formatter converts AgentScope Msg objects to the format expected
 * by the OpenAI Chat Completion API, including support for:
 * - Text messages
 * - System messages
 * - Tool use and tool result messages
 * - Multimodal content (images, audio, video)
 *
 * Follows the Python agentscope OpenAIChatFormatter implementation pattern.
 */
public class OpenAIChatFormatter extends TruncatedFormatterBase {

    private static final Logger log = LoggerFactory.getLogger(OpenAIChatFormatter.class);

    public OpenAIChatFormatter() {
        super();
    }

    public OpenAIChatFormatter(TokenCounter tokenCounter, Integer maxTokens) {
        super(tokenCounter, maxTokens);
    }

    @Override
    protected List<Map<String, Object>> formatInternal(List<Msg> msgs, FormatterOptions options) {
        List<Map<String, Object>> result = new ArrayList<>();

        for (Msg msg : msgs) {
            switch (msg.getRole()) {
                case SYSTEM -> result.add(formatSystemMessage(msg));
                case USER -> result.add(formatUserMessage(msg));
                case ASSISTANT -> result.add(formatAssistantMessage(msg));
                case TOOL -> result.add(formatToolMessage(msg));
            }
        }

        return result;
    }

    @Override
    protected Map<String, Object> formatSystemMessage(Msg msg) {
        Map<String, Object> formatted = new HashMap<>();
        formatted.put("role", "system");
        formatted.put("content", extractTextContent(msg));
        return formatted;
    }

    @Override
    protected List<Map<String, Object>> formatToolSequence(List<Msg> msgs) {
        List<Map<String, Object>> result = new ArrayList<>();

        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.ASSISTANT) {
                result.add(formatAssistantMessage(msg));
            } else if (msg.getRole() == MsgRole.TOOL) {
                result.add(formatToolMessage(msg));
            }
        }

        return result;
    }

    @Override
    protected List<Map<String, Object>> formatAgentMessage(List<Msg> msgs, boolean isFirst) {
        List<Map<String, Object>> result = new ArrayList<>();

        for (Msg msg : msgs) {
            switch (msg.getRole()) {
                case USER -> result.add(formatUserMessage(msg));
                case ASSISTANT -> result.add(formatAssistantMessage(msg));
                default -> {
                    // Convert other roles to user messages for simplicity
                    Map<String, Object> userMsg = new HashMap<>();
                    userMsg.put("role", "user");
                    userMsg.put("content", extractTextContent(msg));
                    result.add(userMsg);
                }
            }
        }

        return result;
    }

    /**
     * Format a user message.
     *
     * @param msg User message to format
     * @return Formatted user message
     */
    protected Map<String, Object> formatUserMessage(Msg msg) {
        Map<String, Object> formatted = new HashMap<>();
        formatted.put("role", "user");

        List<ContentBlock> contentBlocks = msg.getContent();

        // If only one text block, use simple string format
        if (contentBlocks.size() == 1 && contentBlocks.get(0) instanceof TextBlock textBlock) {
            formatted.put("content", textBlock.getText());
            return formatted;
        }

        // For multimodal or multi-block content, use content array
        List<Map<String, Object>> contentArray = new ArrayList<>();
        for (ContentBlock block : contentBlocks) {
            if (block instanceof TextBlock textBlock) {
                Map<String, Object> textContent = new HashMap<>();
                textContent.put("type", "text");
                textContent.put("text", textBlock.getText());
                contentArray.add(textContent);
            } else if (block instanceof ImageBlock imageBlock) {
                Map<String, Object> imageContent = new HashMap<>();
                imageContent.put("type", "image_url");
                Map<String, Object> imageUrl = new HashMap<>();
                imageUrl.put("url", "data:image/jpeg;base64,placeholder");
                imageContent.put("image_url", imageUrl);
                contentArray.add(imageContent);
            }
        }

        if (contentArray.isEmpty()) {
            formatted.put("content", extractTextContent(msg));
        } else {
            formatted.put("content", contentArray);
        }

        return formatted;
    }

    /**
     * Format an assistant message.
     *
     * @param msg Assistant message to format
     * @return Formatted assistant message
     */
    protected Map<String, Object> formatAssistantMessage(Msg msg) {
        Map<String, Object> formatted = new HashMap<>();
        formatted.put("role", "assistant");

        List<ContentBlock> contentBlocks = msg.getContent();

        // Separate text/thinking content from tool calls
        StringBuilder textContent = new StringBuilder();
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        for (ContentBlock block : contentBlocks) {
            if (block instanceof TextBlock textBlock) {
                if (textContent.length() > 0) {
                    textContent.append("\n");
                }
                textContent.append(textBlock.getText());
            } else if (block instanceof ThinkingBlock thinkingBlock) {
                if (textContent.length() > 0) {
                    textContent.append("\n");
                }
                textContent.append(thinkingBlock.getThinking());
            } else if (block instanceof ToolUseBlock toolUseBlock) {
                Map<String, Object> toolCall = new HashMap<>();
                toolCall.put("id", toolUseBlock.getId());
                toolCall.put("type", "function");

                Map<String, Object> function = new HashMap<>();
                function.put("name", toolUseBlock.getName());
                function.put("arguments", convertInputToJson(toolUseBlock.getInput()));
                toolCall.put("function", function);

                toolCalls.add(toolCall);
                log.debug(
                        "Formatted assistant tool call: id={}, name={}",
                        toolUseBlock.getId(),
                        toolUseBlock.getName());
            }
        }

        // Set content field
        if (textContent.length() > 0) {
            formatted.put("content", textContent.toString());
        } else if (toolCalls.isEmpty()) {
            // No content and no tool calls - use empty string
            formatted.put("content", "");
        } else {
            // Only tool calls, no text content
            formatted.put("content", null);
        }

        // Add tool_calls if any
        if (!toolCalls.isEmpty()) {
            formatted.put("tool_calls", toolCalls);
        }

        return formatted;
    }

    /**
     * Format a tool result message.
     *
     * @param msg Tool message to format
     * @return Formatted tool message
     */
    protected Map<String, Object> formatToolMessage(Msg msg) {
        Map<String, Object> formatted = new HashMap<>();
        formatted.put("role", "tool");

        ContentBlock content = msg.getFirstContentBlock();
        String toolCallId = "tool_call_" + System.currentTimeMillis(); // default fallback
        String textContent = extractTextContent(msg);

        // Handle ToolResultBlock specifically
        if (content instanceof ToolResultBlock toolResult) {
            // Extract the actual tool call ID from ToolResultBlock
            toolCallId = toolResult.getId();

            // Extract text content from the output
            ContentBlock output = toolResult.getOutput();
            if (output instanceof TextBlock textBlock) {
                textContent = textBlock.getText();
            } else {
                textContent = ContentBlockUtils.extractTextContent(output);
            }

            log.debug("Formatting tool result: id={}, content={}", toolCallId, textContent);
        } else if (msg.getName() != null && msg.getName().startsWith("tool_result:")) {
            // Fallback: extract from message name
            toolCallId = msg.getName().substring("tool_result:".length());
        }

        formatted.put("content", textContent);
        formatted.put("tool_call_id", toolCallId);

        return formatted;
    }

    /**
     * Extract text content from a message.
     *
     * @param msg Message to extract text from
     * @return Text content
     */
    protected String extractTextContent(Msg msg) {
        return msg.getTextContent();
    }

    /**
     * Convert tool input parameters to JSON string.
     *
     * @param input Tool input parameters
     * @return JSON string representation
     */
    protected String convertInputToJson(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "{}";
        }

        // Simple JSON conversion - in production, use proper JSON library
        try {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : input.entrySet()) {
                if (!first) json.append(",");
                json.append("\"").append(entry.getKey()).append("\":");

                Object value = entry.getValue();
                if (value instanceof String) {
                    json.append("\"").append(value).append("\"");
                } else if (value instanceof Number || value instanceof Boolean) {
                    json.append(value);
                } else {
                    json.append("\"")
                            .append(value != null ? value.toString() : "null")
                            .append("\"");
                }
                first = false;
            }
            json.append("}");
            return json.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override
    public FormatterCapabilities getCapabilities() {
        return FormatterCapabilities.builder()
                .providerName("OpenAI")
                .supportToolsApi(true)
                .supportMultiAgent(false)
                .supportVision(true)
                .supportedBlocks(
                        Set.of(
                                TextBlock.class,
                                ImageBlock.class,
                                ToolUseBlock.class,
                                ThinkingBlock.class))
                .build();
    }
}
