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
package io.agentscope.core.memory.autocontext;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;

/**
 * Utility class for estimating token count in messages.
 *
 * <p>This class provides methods to estimate the number of input tokens that would be
 * consumed when sending messages to an LLM. The estimation uses a character-based
 * approximation that works reasonably well for both English and Chinese text.
 *
 * <p>Token estimation strategy:
 * <ul>
 *   <li>Text content: ~1 token per 2-4 characters (varies by language)
 *   <li>Tool calls: Includes tool name, parameters, and structure overhead
 *   <li>Tool results: Includes output content and structure overhead
 *   <li>Message structure: Role, name, and formatting overhead
 * </ul>
 */
public class TokenCounterUtil {

    // Token estimation ratios
    // For English: ~1 token per 4 characters
    // For Chinese: ~1 token per 1-2 characters
    // Using a conservative ratio that works for mixed content
    private static final double CHARS_PER_TOKEN = 2.5;

    // Overhead tokens for message structure (role, name, formatting)
    private static final int MESSAGE_OVERHEAD = 5;

    // Overhead tokens for tool call structure
    private static final int TOOL_CALL_OVERHEAD = 10;

    // Overhead tokens for tool result structure
    private static final int TOOL_RESULT_OVERHEAD = 8;

    /**
     * Calculates the estimated total input tokens for a list of messages.
     *
     * <p>This method estimates tokens by:
     * <ul>
     *   <li>Extracting all text content from messages
     *   <li>Counting characters in tool calls and results
     *   <li>Adding structure overhead for each message and content block
     * </ul>
     *
     * @param messages the list of messages to estimate tokens for
     * @return estimated number of input tokens
     */
    public static int calculateToken(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        int totalTokens = 0;

        for (Msg msg : messages) {
            totalTokens += estimateMessageTokens(msg);
        }

        return totalTokens;
    }

    /**
     * Estimates tokens for a single message.
     *
     * @param msg the message to estimate
     * @return estimated number of tokens for this message
     */
    private static int estimateMessageTokens(Msg msg) {
        if (msg == null) {
            return 0;
        }

        int tokens = MESSAGE_OVERHEAD;

        // Add overhead for role and name
        if (msg.getRole() != null) {
            tokens += estimateTextTokens(msg.getRole().name());
        }
        if (msg.getName() != null) {
            tokens += estimateTextTokens(msg.getName());
        }

        // Estimate tokens for content blocks
        List<ContentBlock> content = msg.getContent();
        if (content != null) {
            for (ContentBlock block : content) {
                tokens += estimateContentBlockTokens(block);
            }
        }

        return tokens;
    }

    /**
     * Estimates tokens for a content block.
     *
     * @param block the content block to estimate
     * @return estimated number of tokens for this block
     */
    private static int estimateContentBlockTokens(ContentBlock block) {
        if (block == null) {
            return 0;
        }

        if (block instanceof TextBlock textBlock) {
            return estimateTextTokens(textBlock.getText());
        } else if (block instanceof ToolUseBlock toolUseBlock) {
            return estimateToolUseBlockTokens(toolUseBlock);
        } else if (block instanceof ToolResultBlock toolResultBlock) {
            return estimateToolResultBlockTokens(toolResultBlock);
        }

        // For other block types (ImageBlock, AudioBlock, etc.), estimate minimal overhead
        return 5;
    }

    /**
     * Estimates tokens for a ToolUseBlock.
     *
     * @param toolUseBlock the tool use block to estimate
     * @return estimated number of tokens
     */
    private static int estimateToolUseBlockTokens(ToolUseBlock toolUseBlock) {
        int tokens = TOOL_CALL_OVERHEAD;

        // Tool name
        if (toolUseBlock.getName() != null) {
            tokens += estimateTextTokens(toolUseBlock.getName());
        }

        // Tool ID
        if (toolUseBlock.getId() != null) {
            tokens += estimateTextTokens(toolUseBlock.getId());
        }

        // Tool input parameters
        Map<String, Object> input = toolUseBlock.getInput();
        if (input != null && !input.isEmpty()) {
            // Estimate tokens for JSON representation of parameters
            String inputJson = estimateMapAsJson(input);
            tokens += estimateTextTokens(inputJson);
        }

        // Raw content (if present)
        if (toolUseBlock.getContent() != null) {
            tokens += estimateTextTokens(toolUseBlock.getContent());
        }

        return tokens;
    }

    /**
     * Estimates tokens for a ToolResultBlock.
     *
     * @param toolResultBlock the tool result block to estimate
     * @return estimated number of tokens
     */
    private static int estimateToolResultBlockTokens(ToolResultBlock toolResultBlock) {
        int tokens = TOOL_RESULT_OVERHEAD;

        // Tool name
        if (toolResultBlock.getName() != null) {
            tokens += estimateTextTokens(toolResultBlock.getName());
        }

        // Tool ID
        if (toolResultBlock.getId() != null) {
            tokens += estimateTextTokens(toolResultBlock.getId());
        }

        // Output content blocks
        List<ContentBlock> output = toolResultBlock.getOutput();
        if (output != null) {
            for (ContentBlock outputBlock : output) {
                tokens += estimateContentBlockTokens(outputBlock);
            }
        }

        return tokens;
    }

    /**
     * Estimates tokens for text content.
     *
     * <p>Uses a character-based approximation that works reasonably well
     * for both English and Chinese text.
     *
     * @param text the text to estimate
     * @return estimated number of tokens
     */
    private static int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        // Count characters and apply ratio
        int charCount = text.length();
        return (int) Math.ceil(charCount / CHARS_PER_TOKEN);
    }

    /**
     * Estimates the JSON string representation of a map for token counting.
     *
     * <p>This is a simplified estimation that counts keys and string values.
     *
     * @param map the map to estimate
     * @return estimated JSON string length
     */
    private static String estimateMapAsJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof String) {
                sb.append("\"").append(value).append("\"");
            } else {
                sb.append(value != null ? value.toString() : "null");
            }
        }
        sb.append("}");
        return sb.toString();
    }
}
