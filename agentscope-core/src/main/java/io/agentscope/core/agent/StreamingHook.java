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
package io.agentscope.core.agent;

import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.hook.SummaryChunkEvent;
import io.agentscope.core.message.ContentBlock;
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
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

/**
 * Internal hook implementation for streaming events.
 *
 * <p>Intercepts hook callbacks and emits {@link Event} instances to a FluxSink. Handles event
 * filtering and chunk mode processing.
 */
class StreamingHook implements Hook {

    private final FluxSink<Event> sink;
    private final StreamOptions options;

    // Track previous content for incremental mode
    private final Map<String, List<ContentBlock>> previousContent = new HashMap<>();

    // Track cumulative reasoning content across ReAct reasoning/acting boundaries.
    private final List<ContentBlock> cumulativeReasoningContent = new ArrayList<>();
    private final Map<String, Integer> cumulativeReasoningPositions = new HashMap<>();

    /**
     * Creates a new streaming hook.
     *
     * @param sink The FluxSink to emit events to
     * @param options Configuration options for streaming
     */
    StreamingHook(FluxSink<Event> sink, StreamOptions options) {
        this.sink = sink;
        this.options = options;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent) {
            PostReasoningEvent e = (PostReasoningEvent) event;
            // postReasoning is called after streaming completes
            // This is the last/complete message
            if (options.shouldStream(EventType.REASONING)
                    && options.shouldIncludeReasoningEmission(false)) {
                Msg msgToEmit =
                        options.isIncremental()
                                ? e.getReasoningMessage()
                                : accumulateReasoning(e.getReasoningMessage());
                emitEvent(EventType.REASONING, msgToEmit, true);
            }
            return Mono.just(event);
        } else if (event instanceof ReasoningChunkEvent) {
            ReasoningChunkEvent e = (ReasoningChunkEvent) event;
            // This is an intermediate chunk
            if (options.shouldStream(EventType.REASONING)
                    && options.shouldIncludeReasoningEmission(true)) {
                // Use incremental or accumulated based on StreamOptions
                Msg msgToEmit =
                        options.isIncremental()
                                ? e.getIncrementalChunk()
                                : accumulateReasoning(e.getAccumulated());
                emitEvent(EventType.REASONING, msgToEmit, false);
            }
            return Mono.just(event);
        } else if (event instanceof PostActingEvent) {
            PostActingEvent e = (PostActingEvent) event;
            // Tool execution completed
            if (options.shouldStream(EventType.TOOL_RESULT)) {
                Msg toolMsg = createToolMessage(e.getToolResult());
                emitEvent(EventType.TOOL_RESULT, toolMsg, true);
            }
            return Mono.just(event);
        } else if (event instanceof ActingChunkEvent) {
            ActingChunkEvent e = (ActingChunkEvent) event;
            // Intermediate tool chunk
            if (options.shouldStream(EventType.TOOL_RESULT) && options.isIncludeActingChunk()) {
                Msg toolMsg = createToolMessage(e.getChunk());
                emitEvent(EventType.TOOL_RESULT, toolMsg, false);
            }
            return Mono.just(event);
        } else if (event instanceof PostSummaryEvent) {
            PostSummaryEvent e = (PostSummaryEvent) event;
            // Summary generation completed
            if (options.shouldStream(EventType.SUMMARY)
                    && options.shouldIncludeSummaryEmission(false)) {
                emitEvent(EventType.SUMMARY, e.getSummaryMessage(), true);
            }
            return Mono.just(event);
        } else if (event instanceof SummaryChunkEvent) {
            SummaryChunkEvent e = (SummaryChunkEvent) event;
            // Intermediate summary chunk
            if (options.shouldStream(EventType.SUMMARY)
                    && options.shouldIncludeSummaryEmission(true)) {
                // Use incremental or accumulated based on StreamOptions
                Msg msgToEmit =
                        options.isIncremental() ? e.getIncrementalChunk() : e.getAccumulated();
                emitEvent(EventType.SUMMARY, msgToEmit, false);
            }
            return Mono.just(event);
        }
        return Mono.just(event);
    }

    // ========== Helper Methods ==========

    /**
     * Creates a tool message from a tool result block.
     *
     * @param toolResultBlock The tool result or chunk
     * @return A message with TOOL role containing the result
     */
    private Msg createToolMessage(ToolResultBlock toolResultBlock) {
        return Msg.builder()
                .name("system")
                .role(MsgRole.TOOL)
                .content(List.of(toolResultBlock))
                .build();
    }

    private Msg accumulateReasoning(Msg reasoningMsg) {
        for (int index = 0; index < reasoningMsg.getContent().size(); index++) {
            ContentBlock block = reasoningMsg.getContent().get(index);
            String key = reasoningContentKey(reasoningMsg.getId(), block, index);
            Integer position = cumulativeReasoningPositions.get(key);

            if (position == null) {
                cumulativeReasoningPositions.put(key, cumulativeReasoningContent.size());
                cumulativeReasoningContent.add(block);
            } else {
                cumulativeReasoningContent.set(position, block);
            }
        }

        return Msg.builder()
                .id(reasoningMsg.getId())
                .name(reasoningMsg.getName())
                .role(reasoningMsg.getRole())
                .content(new ArrayList<>(cumulativeReasoningContent))
                .metadata(new HashMap<>(reasoningMsg.getMetadata()))
                .timestamp(reasoningMsg.getTimestamp())
                .build();
    }

    private String reasoningContentKey(String messageId, ContentBlock block, int index) {
        if (block instanceof ThinkingBlock) {
            return messageId + ":thinking";
        }
        if (block instanceof TextBlock) {
            return messageId + ":text";
        }
        if (block instanceof ToolUseBlock toolUseBlock) {
            String toolCallId = toolUseBlock.getId();
            if (toolCallId == null || toolCallId.isBlank()) {
                toolCallId = toolUseBlock.getName() + ":" + index;
            }
            return messageId + ":tool:" + toolCallId;
        }
        return messageId + ":" + block.getClass().getName() + ":" + index;
    }

    /**
     * Emit an event to the sink.
     *
     * @param type The event type
     * @param msg The message
     * @param isLast Whether this is the last/complete message in the stream
     */
    private void emitEvent(EventType type, Msg msg, boolean isLast) {
        Msg processedMsg = msg;

        // For incremental mode, calculate the diff (if needed in the future)
        // Currently we directly use the incremental chunk from ReasoningChunkEvent

        // Create and emit the event
        Event event = new Event(type, processedMsg, isLast);
        sink.next(event);

        // Update tracking
        if (!isLast) {
            previousContent.put(msg.getId(), new ArrayList<>(msg.getContent()));
        } else {
            previousContent.remove(msg.getId());
        }
    }
}