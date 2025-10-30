/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.agent.accumulator;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Flux;

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
 */
public class ReasoningContext {

    private final String agentName;
    private String messageId;

    private final TextAccumulator textAcc = new TextAccumulator();
    private final ThinkingAccumulator thinkingAcc = new ThinkingAccumulator();
    private final ToolCallsAccumulator toolCallsAcc = new ToolCallsAccumulator();

    private final List<Msg> allStreamedChunks = new ArrayList<>();

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
     *   <li>ToolUseBlock: Accumulate first, emit after complete
     * </ul>
     *
     * @param chunk Response chunk from the model
     * @return List of messages that can be sent immediately
     */
    public List<Msg> processChunk(ChatResponse chunk) {
        this.messageId = chunk.getId();

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
                // Accumulate tool calls, don't emit immediately
                toolCallsAcc.add(tub);
            }
        }

        return streamingMsgs;
    }

    /**
     * Emit all finalized tool calls.
     * Uses defer() to ensure tool calls are built lazily when the Flux is subscribed to,
     * not when this method is called.
     *
     * @return Flux of tool call messages
     */
    public Flux<Msg> emitFinalizedToolCalls() {
        return Flux.defer(
                () -> {
                    List<ToolUseBlock> toolCalls = toolCallsAcc.buildAllToolCalls();
                    return Flux.fromIterable(toolCalls)
                            .map(
                                    tub -> {
                                        Msg msg =
                                                Msg.builder()
                                                        .id(messageId)
                                                        .name(agentName)
                                                        .role(MsgRole.ASSISTANT)
                                                        .content(tub)
                                                        .build();
                                        // Track emitted tool call messages
                                        allStreamedChunks.add(msg);
                                        return msg;
                                    });
                });
    }

    /**
     * Build the final reasoning message with all content blocks.
     * This includes text, thinking, AND tool calls in ONE message.
     *
     * <p>This method aligns with Python's behavior where a single reasoning round
     * produces one message that may contain multiple content blocks.
     *
     * <p>Strategy:
     *
     * <ol>
     *   <li>Add text content if present
     *   <li>Add thinking content if present
     *   <li>Add all tool calls
     * </ol>
     *
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

        return Msg.builder()
                .id(messageId)
                .name(agentName)
                .role(MsgRole.ASSISTANT)
                .content(blocks)
                .build();
    }

    /**
     * Build a chunk message from a content block.
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
     * Get the accumulated text content.
     *
     * @return accumulated text as string
     */
    public String getAccumulatedText() {
        return textAcc.getAccumulated();
    }

    /**
     * Get the accumulated thinking content.
     *
     * @return accumulated thinking as string
     */
    public String getAccumulatedThinking() {
        return thinkingAcc.getAccumulated();
    }
}
