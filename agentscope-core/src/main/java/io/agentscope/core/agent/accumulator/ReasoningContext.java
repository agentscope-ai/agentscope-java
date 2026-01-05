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
package io.agentscope.core.agent.accumulator;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reasoning context that manages all state and content accumulation for a single reasoning round.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Accumulate various content types (text, thinking, tool calls) from streaming responses
 *   <li>Generate real-time streaming messages (for Hook notifications)
 *   <li>Build final aggregated message (for saving to memory)
 * </ul>
 * @hidden
 */
public class ReasoningContext {

    private final String agentName;
    private String messageId;

    private final TextAccumulator textAcc = new TextAccumulator();
    private final ThinkingAccumulator thinkingAcc = new ThinkingAccumulator();
    private final ToolCallsAccumulator toolCallsAcc = new ToolCallsAccumulator();

    private final List<Msg> allStreamedChunks = new ArrayList<>();

    // ChatUsage
    private int inputTokens = 0;
    private int outputTokens = 0;
    private double time = 0;

    public ReasoningContext(String agentName) {
        this.agentName = agentName;
    }

    /**
     * Process a response chunk and return messages that can be sent immediately.
     *
     * <p>Strategy:
     *
     * <ul>
     *   <li>TextBlock/ThinkingBlock: Emit immediately for real-time display
     *   <li>ToolUseBlock: Accumulate and emit immediately for real-time streaming
     * </ul>
     *
     * @hidden
     * @param chunk Response chunk from the model
     * @return List of messages that can be sent immediately
     */
    public List<Msg> processChunk(ChatResponse chunk) {
        this.messageId = chunk.getId();

        // Accumulate ChatUsage
        ChatUsage usage = chunk.getUsage();
        if (usage != null) {
            inputTokens = usage.getInputTokens();
            outputTokens = usage.getOutputTokens();
            time = usage.getTime();
        }

        List<Msg> streamingMsgs = new ArrayList<>();

        for (ContentBlock block : chunk.getContent()) {
            if (block instanceof TextBlock tb) {
                textAcc.add(tb);

                // Emit text block immediately
                Msg msg = buildChunkMsg(tb);
                streamingMsgs.add(msg);
                allStreamedChunks.add(msg);

            } else if (block instanceof ThinkingBlock tb) {
                thinkingAcc.add(tb);

                // Emit thinking block immediately
                Msg msg = buildChunkMsg(tb);
                streamingMsgs.add(msg);
                allStreamedChunks.add(msg);

            } else if (block instanceof ToolUseBlock tub) {
                // Accumulate tool calls and emit immediately for real-time streaming
                toolCallsAcc.add(tub);

                // Emit ToolUseBlock chunk immediately for real-time display
                // Each tool call chunk is emitted separately, supporting multiple parallel tool
                // calls
                // For fragments (placeholder names like "__fragment__"), we need to include
                // the correct tool call ID so users can properly concatenate the chunks
                ToolUseBlock outputBlock = enrichToolUseBlockWithId(tub);
                Msg msg = buildChunkMsg(outputBlock);
                streamingMsgs.add(msg);
                allStreamedChunks.add(msg);
            }
        }

        return streamingMsgs;
    }

    /**
     * Build the final reasoning message with all content blocks.
     * This includes text, thinking, AND tool calls in ONE message.
     *
     * <p>This method ensures that a single reasoning round produces one message
     * that may contain multiple content blocks.
     *
     * <p>Strategy:
     *
     * <ol>
     *   <li>Add text content if present
     *   <li>Add thinking content if present
     *   <li>Add all tool calls
     * </ol>
     *
     * @hidden
     * @return The complete reasoning message with all blocks, or null if no content
     */
    public Msg buildFinalMessage() {
        List<ContentBlock> blocks = new ArrayList<>();

        // Add thinking content if present
        if (thinkingAcc.hasContent()) {
            blocks.add(thinkingAcc.buildAggregated());
        }

        // Add text content if present
        if (textAcc.hasContent()) {
            blocks.add(textAcc.buildAggregated());
        }

        // Add all tool calls
        List<ToolUseBlock> toolCalls = toolCallsAcc.buildAllToolCalls();
        blocks.addAll(toolCalls);

        // If no content at all, return null
        if (blocks.isEmpty()) {
            return null;
        }

        // Build metadata with accumulated ChatUsage
        Map<String, Object> metadata = new HashMap<>();
        if (inputTokens > 0 || outputTokens > 0 || time > 0) {
            ChatUsage chatUsage =
                    ChatUsage.builder()
                            .inputTokens(inputTokens)
                            .outputTokens(outputTokens)
                            .time(time)
                            .build();
            metadata.put(MessageMetadataKeys.CHAT_USAGE, chatUsage);
        }

        return Msg.builder()
                .id(messageId)
                .name(agentName)
                .role(MsgRole.ASSISTANT)
                .content(blocks)
                .metadata(metadata)
                .build();
    }

    /**
     * Build a chunk message from a content block.
     * @hidden
     */
    private Msg buildChunkMsg(ContentBlock block) {
        return Msg.builder()
                .id(messageId)
                .name(agentName)
                .role(MsgRole.ASSISTANT)
                .content(block)
                .build();
    }

    /**
     * Enrich a ToolUseBlock with the correct tool call ID.
     *
     * <p>For fragments (placeholder names like "__fragment__"), the original block may not have
     * the correct ID. This method retrieves the ID from the accumulator and creates a new block
     * with the correct ID, allowing users to properly concatenate chunks.
     *
     * @param block The original ToolUseBlock
     * @return A ToolUseBlock with the correct ID
     */
    private ToolUseBlock enrichToolUseBlockWithId(ToolUseBlock block) {
        // If the block already has an ID, return it as-is
        if (block.getId() != null && !block.getId().isEmpty()) {
            return block;
        }

        // Get the current tool call ID from the accumulator
        String currentId = toolCallsAcc.getCurrentToolCallId();
        if (currentId == null || currentId.isEmpty()) {
            return block;
        }

        // Create a new block with the correct ID
        return ToolUseBlock.builder()
                .id(currentId)
                .name(block.getName())
                .input(block.getInput())
                .content(block.getContent())
                .metadata(block.getMetadata())
                .build();
    }

    /**
     * Get the accumulated text content.
     *
     * @hidden
     * @return accumulated text as string
     */
    public String getAccumulatedText() {
        return textAcc.getAccumulated();
    }

    /**
     * Get the accumulated thinking content.
     *
     * @hidden
     * @return accumulated thinking as string
     */
    public String getAccumulatedThinking() {
        return thinkingAcc.getAccumulated();
    }

    /**
     * Get accumulated tool call by ID.
     *
     * <p>If the ID is null or empty, or if no builder is found for the given ID,
     * this method falls back to using the last tool call.
     *
     * @param id The tool call ID to look up
     * @return The accumulated ToolUseBlock, or null if not found
     */
    public ToolUseBlock getAccumulatedToolCall(String id) {
        return toolCallsAcc.getAccumulatedToolCall(id);
    }

    /**
     * Get all accumulated tool calls.
     *
     * @return List of all accumulated ToolUseBlocks
     */
    public List<ToolUseBlock> getAllAccumulatedToolCalls() {
        return toolCallsAcc.getAllAccumulatedToolCalls();
    }
}
