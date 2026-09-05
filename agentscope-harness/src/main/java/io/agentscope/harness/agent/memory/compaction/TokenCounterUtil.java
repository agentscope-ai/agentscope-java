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
package io.agentscope.harness.agent.memory.compaction;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;

/**
 * Utility class for estimating token count in messages.
 *
 * <p>This class provides methods to estimate the number of input tokens that would be
 * consumed when sending messages to an LLM. The estimation uses a character-based
 * approximation, not a model-specific tokenizer. Non-ASCII text is counted more
 * conservatively to avoid delaying compaction for languages such as Chinese.
 *
 * <p>Token estimation strategy:
 * <ul>
 *   <li>Text content: ~1 token per 2.5 ASCII characters; at least 1 per non-ASCII code point
 *   <li>Tool calls: Includes tool name, parameters, and structure overhead
 *   <li>Tool results: Includes output content and structure overhead
 *   <li>Message structure: Role, name, and formatting overhead
 * </ul>
 */
public class TokenCounterUtil {

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

        long totalTokens = 0;

        for (Msg msg : messages) {
            totalTokens += estimateMessageTokens(msg);
        }

        return (int) Math.min(Integer.MAX_VALUE, totalTokens);
    }

    /**
     * Estimates a request including system messages, tool definitions and the response schema.
     *
     * <p>Only prompt-bearing options are counted; connection settings and credentials are not
     * serialized. Provider chat templates, model defaults and multimodal token costs can differ,
     * so callers must still reserve headroom for the model's actual context window.
     *
     * @param messages all request messages, including system messages
     * @param tools tool definitions, or null
     * @param options request options, or null
     * @return estimated input tokens, saturated at {@link Integer#MAX_VALUE}
     */
    public static int calculateToken(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        long tokens = calculateToken(messages);
        if (tools != null && !tools.isEmpty()) {
            tokens += estimateTextTokens(JsonUtils.getJsonCodec().toJson(tools));
        }
        if (options != null && options.getResponseFormat() != null) {
            tokens +=
                    estimateTextTokens(
                            JsonUtils.getJsonCodec().toJson(options.getResponseFormat()));
        }
        return (int) Math.min(Integer.MAX_VALUE, tokens);
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

        long tokens = MESSAGE_OVERHEAD;

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

        return (int) Math.min(Integer.MAX_VALUE, tokens);
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
        } else if (block instanceof HintBlock hintBlock) {
            return estimateTextTokens(hintBlock.getHint());
        } else if (block instanceof ThinkingBlock thinkingBlock) {
            // OpenAI-compatible reasoning models can send this text back with assistant history.
            return estimateTextTokens(thinkingBlock.getThinking());
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
        long tokens = TOOL_CALL_OVERHEAD;

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
            String inputJson = JsonUtils.getJsonCodec().toJson(input);
            tokens += estimateTextTokens(inputJson);
        }

        // Raw content (if present)
        if (toolUseBlock.getContent() != null) {
            tokens += estimateTextTokens(toolUseBlock.getContent());
        }

        return (int) Math.min(Integer.MAX_VALUE, tokens);
    }

    /**
     * Estimates tokens for a ToolResultBlock.
     *
     * @param toolResultBlock the tool result block to estimate
     * @return estimated number of tokens
     */
    private static int estimateToolResultBlockTokens(ToolResultBlock toolResultBlock) {
        long tokens = TOOL_RESULT_OVERHEAD;

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

        return (int) Math.min(Integer.MAX_VALUE, tokens);
    }

    /**
     * Estimates tokens for text content.
     *
     * <p>Keeps the existing ASCII estimate. Non-ASCII BMP characters count as one token;
     * supplementary code points count as two. These are conservative heuristics rather than
     * a guarantee of an upper bound for every tokenizer.
     *
     * @param text the text to estimate
     * @return estimated number of tokens
     */
    private static int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        // Accumulate fifths of a token to avoid rounding each ASCII character up.
        long fifths = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            int width = Character.charCount(codePoint);
            fifths += codePoint < 128 ? 2 : 5L * width;
            offset += width;
        }
        return (int) Math.min(Integer.MAX_VALUE, (fifths + 4) / 5);
    }
}
