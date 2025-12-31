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
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
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
                emitEvent(EventType.REASONING, e.getReasoningMessage(), true);
            }
            return Mono.just(event);
        } else if (event instanceof ReasoningChunkEvent) {
            ReasoningChunkEvent e = (ReasoningChunkEvent) event;
            // This is an intermediate chunk
            if (options.shouldStream(EventType.REASONING)
                    && options.shouldIncludeReasoningEmission(true)) {
                // Use incremental or accumulated based on StreamOptions
                Msg msgToEmit =
                        options.isIncremental() ? e.getIncrementalChunk() : e.getAccumulated();
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
            if (options.shouldStream(EventType.TOOL_RESULT)) {
                Msg toolMsg = createToolMessage(e.getChunk());
                emitEvent(EventType.TOOL_RESULT, toolMsg, false);
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
