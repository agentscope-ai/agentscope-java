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
     *
     * @return Flux of tool call messages
     */
    public Flux<Msg> emitFinalizedToolCalls() {
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
    }

    /**
     * Build the final aggregated message for saving to memory.
     *
     * <p>Strategy:
     *
     * <ol>
     *   <li>If has tool calls, return the last tool call (ReAct loop needs this)
     *   <li>Else if has text, return aggregated text
     *   <li>Else if has thinking, return aggregated thinking
     *   <li>Else return the last streamed message
     * </ol>
     *
     * @return Aggregated message
     */
    public Msg buildMemoryMessage() {
        // Priority 1: Tool calls (needed for ReAct loop control)
        if (toolCallsAcc.hasContent()) {
            ContentBlock toolCall = toolCallsAcc.buildAggregated();
            if (toolCall != null) {
                return Msg.builder()
                        .id(messageId)
                        .name(agentName)
                        .role(MsgRole.ASSISTANT)
                        .content(toolCall)
                        .build();
            }
        }

        // Priority 2: Text content
        if (textAcc.hasContent()) {
            return Msg.builder()
                    .id(messageId)
                    .name(agentName)
                    .role(MsgRole.ASSISTANT)
                    .content(textAcc.buildAggregated())
                    .build();
        }

        // Priority 3: Thinking content
        if (thinkingAcc.hasContent()) {
            return Msg.builder()
                    .id(messageId)
                    .name(agentName)
                    .role(MsgRole.ASSISTANT)
                    .content(thinkingAcc.buildAggregated())
                    .build();
        }

        // Fallback: Return last streamed message
        if (!allStreamedChunks.isEmpty()) {
            return allStreamedChunks.get(allStreamedChunks.size() - 1);
        }

        return null;
    }

    /**
     * Get the current accumulated text (for streaming Hook's accumulated parameter).
     *
     * @return Current accumulated text
     */
    public String getAccumulatedText() {
        return textAcc.getCurrentText();
    }

    /**
     * Build an accumulated text message for Hook notifications.
     *
     * @return Accumulated message with all text content so far
     */
    public Msg buildAccumulatedTextMsg() {
        if (!textAcc.hasContent()) {
            return null;
        }
        return Msg.builder()
                .id(messageId)
                .name(agentName)
                .role(MsgRole.ASSISTANT)
                .content(textAcc.buildAggregated())
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
}
