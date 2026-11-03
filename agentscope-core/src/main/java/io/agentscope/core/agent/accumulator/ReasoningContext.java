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

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.FinishReasons;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

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
    private final ServerToolResultAccumulator serverToolResults = new ServerToolResultAccumulator();

    // Providers that expose block indices retain native boundaries and order for history replay.
    private final Map<Integer, List<ContentBlock>> indexedChunks = new TreeMap<>();
    private final Set<Integer> completedBlocks = new HashSet<>();
    private int lastObservedBlockIndex = -1;
    private String finishReason;
    private boolean textReplaced;

    // ChatUsage
    private int inputTokens = 0;
    private int outputTokens = 0;
    private int cachedTokens = 0;
    private int cacheCreationTokens = 0;
    private int reasoningTokens = 0;
    private int toolUsePromptTokens = 0;
    private double time = 0;

    // Provider-specific response metadata to propagate to the final message
    private final Map<String, Object> responseMetadata = new HashMap<>();

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
        if (chunk.getFinishReason() != null) {
            finishReason = chunk.getFinishReason();
        }

        // Accumulate ChatUsage
        ChatUsage usage = chunk.getUsage();
        if (usage != null) {
            inputTokens = usage.getInputTokens();
            outputTokens = usage.getOutputTokens();
            cachedTokens = usage.getCachedTokens();
            cacheCreationTokens = usage.getCacheCreationTokens();
            reasoningTokens = usage.getReasoningTokens();
            toolUsePromptTokens = usage.getToolUsePromptTokens();
            time = usage.getTime();
        }

        // Propagate provider-specific metadata
        if (chunk.getMetadata() != null && !chunk.getMetadata().isEmpty()) {
            responseMetadata.putAll(chunk.getMetadata());
            responseMetadata.remove(ChatResponse.METADATA_CONTENT_BLOCK_INDEX);
            responseMetadata.remove(ChatResponse.METADATA_CONTENT_BLOCK_COMPLETE);
            Object index = chunk.getMetadata().get(ChatResponse.METADATA_CONTENT_BLOCK_INDEX);
            if (index instanceof Number number) {
                int blockIndex = number.intValue();
                // Include metadata-only events and every block in a non-streaming response.
                lastObservedBlockIndex =
                        Math.max(
                                lastObservedBlockIndex,
                                blockIndex + Math.max(1, chunk.getContent().size()) - 1);
                boolean complete =
                        Boolean.TRUE.equals(
                                chunk.getMetadata()
                                        .get(ChatResponse.METADATA_CONTENT_BLOCK_COMPLETE));
                if (complete) {
                    completedBlocks.add(blockIndex);
                }
                for (ContentBlock block : chunk.getContent()) {
                    indexedChunks.computeIfAbsent(blockIndex, k -> new ArrayList<>()).add(block);
                    if (complete) {
                        completedBlocks.add(blockIndex);
                    }
                    blockIndex++;
                }
            }
        }

        List<Msg> streamingMsgs = new ArrayList<>();

        for (ContentBlock block : chunk.getContent()) {
            if (block instanceof TextBlock tb) {
                textAcc.add(tb);

                // Emit text block immediately
                Msg msg = buildChunkMsg(tb);
                streamingMsgs.add(msg);

            } else if (block instanceof ThinkingBlock tb) {
                thinkingAcc.add(tb);

                // Emit thinking block immediately
                Msg msg = buildChunkMsg(tb);
                streamingMsgs.add(msg);

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

            } else if (block instanceof ToolResultBlock trb && trb.isServerTool()) {
                // Server tool results arrive as part of the assistant response; keep them so
                // they end up in the final message and can be echoed back on later turns.
                serverToolResults.add(trb);

                Msg msg = buildChunkMsg(trb);
                streamingMsgs.add(msg);
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
     * <p>Native block indices preserve provider boundaries and order. Providers without indices
     * keep the existing thinking, text, and tool-call aggregation order.
     *
     * @hidden
     * @return The complete reasoning message with all blocks, or null if no content
     */
    public Msg buildFinalMessage() {
        List<ContentBlock> blocks = new ArrayList<>();
        boolean outputLimitReached = isTruncated();

        if (!indexedChunks.isEmpty()) {
            blocks.addAll(buildIndexedContent());
        } else {
            // Add thinking content if present
            if (thinkingAcc.hasContent()) {
                blocks.add(thinkingAcc.buildAggregated());
            }

            // Add text content if present
            if (textAcc.hasContent()) {
                blocks.add(textAcc.buildAggregated());
            }

            // Add all tool calls, placing server tool results right after their calls
            List<ToolUseBlock> toolCalls = toolCallsAcc.buildAllToolCalls(outputLimitReached);
            blocks.addAll(serverToolResults.placeAfterToolCalls(toolCalls));
        }

        // If no content at all, return null
        if (blocks.isEmpty()) {
            return null;
        }

        // Build metadata: start with propagated response metadata, then add ChatUsage
        Map<String, Object> metadata = new HashMap<>(responseMetadata);
        if (outputLimitReached) {
            metadata.put(MessageMetadataKeys.OUTPUT_LIMIT_REACHED, true);
        }
        ChatUsage chatUsage = null;
        if (inputTokens > 0 || outputTokens > 0 || time > 0) {
            chatUsage =
                    ChatUsage.builder()
                            .inputTokens(inputTokens)
                            .outputTokens(outputTokens)
                            .cachedTokens(cachedTokens)
                            .cacheCreationTokens(cacheCreationTokens)
                            .reasoningTokens(reasoningTokens)
                            .toolUsePromptTokens(toolUsePromptTokens)
                            .time(time)
                            .build();
            metadata.put(MessageMetadataKeys.CHAT_USAGE, chatUsage);
        }

        return AssistantMessage.builder()
                .id(messageId)
                .name(agentName)
                .content(blocks)
                .metadata(metadata)
                .usage(chatUsage)
                .build();
    }

    private boolean isTruncated() {
        return FinishReasons.indicatesOutputLimit(finishReason, responseMetadata);
    }

    private List<ContentBlock> buildIndexedContent() {
        List<ContentBlock> blocks = new ArrayList<>();
        boolean replacementAdded = false;
        for (Map.Entry<Integer, List<ContentBlock>> entry : indexedChunks.entrySet()) {
            List<ContentBlock> chunks = entry.getValue();
            if (chunks.get(0) instanceof TextBlock && textReplaced) {
                // Middleware replaced all native text: emit the replacement once, anchored at
                // the position of the first native text block.
                if (!replacementAdded && textAcc.hasContent()) {
                    blocks.add(textAcc.buildAggregated());
                }
                replacementAdded = true;
            } else {
                blocks.addAll(aggregateGroup(entry.getKey(), chunks));
            }
        }
        if (textReplaced && !replacementAdded && textAcc.hasContent()) {
            // Replaced/added text with no native text block to anchor it (e.g. middleware
            // adding text to a thinking-only response).
            blocks.add(textAcc.buildAggregated());
        }
        return blocks;
    }

    /** Re-aggregates the fragments of one native block into final content blocks. */
    private List<? extends ContentBlock> aggregateGroup(Integer index, List<ContentBlock> chunks) {
        ContentBlock first = chunks.get(0);
        if (first instanceof TextBlock) {
            TextAccumulator accumulator = new TextAccumulator();
            chunks.forEach(b -> accumulator.add((TextBlock) b));
            return accumulator.hasContent() ? List.of(accumulator.buildAggregated()) : List.of();
        } else if (first instanceof ThinkingBlock) {
            ThinkingAccumulator accumulator = new ThinkingAccumulator();
            chunks.forEach(b -> accumulator.add((ThinkingBlock) b));
            return accumulator.hasContent() ? List.of(accumulator.buildAggregated()) : List.of();
        } else if (first instanceof ToolUseBlock) {
            ToolCallsAccumulator accumulator = new ToolCallsAccumulator();
            chunks.forEach(b -> accumulator.add((ToolUseBlock) b));
            // A stop event alone does not prove that the last native block completed before
            // the output limit. Earlier completed blocks remain executable.
            boolean blockTruncated =
                    isTruncated()
                            && (index == lastObservedBlockIndex
                                    || !completedBlocks.contains(index));
            return accumulator.buildAllToolCalls(blockTruncated);
        } else if (first instanceof ToolResultBlock) {
            ServerToolResultAccumulator accumulator = new ServerToolResultAccumulator();
            chunks.forEach(b -> accumulator.add((ToolResultBlock) b));
            return accumulator.buildAllToolResults();
        }
        return List.of();
    }

    /**
     * Build a chunk message from a content block.
     * @hidden
     */
    private Msg buildChunkMsg(ContentBlock block) {
        return AssistantMessage.builder().id(messageId).name(agentName).content(block).build();
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
                .state(block.getState())
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
     * Replace accumulated text after {@code onModelCall} middleware transforms text delta events.
     *
     * @hidden
     * @param text text reconstructed from the transformed event stream
     */
    public void replaceAccumulatedText(String text) {
        textReplaced |= !Objects.equals(textAcc.getAccumulated(), text);
        textAcc.replace(text);
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

    /**
     * Get the accumulated ChatUsage.
     *
     * @return ChatUsage with accumulated tokens, or null if no usage data
     */
    public ChatUsage getChatUsage() {
        if (inputTokens > 0 || outputTokens > 0 || time > 0) {
            return ChatUsage.builder()
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .cachedTokens(cachedTokens)
                    .cacheCreationTokens(cacheCreationTokens)
                    .reasoningTokens(reasoningTokens)
                    .toolUsePromptTokens(toolUsePromptTokens)
                    .time(time)
                    .build();
        }
        return null;
    }
}
