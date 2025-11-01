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
package io.agentscope.core.agent;

import io.agentscope.core.hook.ChunkMode;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
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
 * <p>Intercepts hook callbacks and emits {@link Event} instances to a FluxSink.
 * Handles event filtering and chunk mode processing.
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

    // ========== Reasoning Hooks ==========

    /**
     * Returns the chunk mode specified in the stream options.
     *
     * @return The chunk mode from StreamOptions
     */
    @Override
    public ChunkMode reasoningChunkMode() {
        return options.getChunkMode();
    }

    /**
     * Handles reasoning completion and emits the final reasoning event.
     *
     * <p>This method is called after streaming completes, representing the last/complete message.
     *
     * @param agent The agent performing reasoning
     * @param reasoningMsg The complete reasoning message
     * @return Mono containing the reasoning message
     */
    @Override
    public Mono<Msg> postReasoning(Agent agent, Msg reasoningMsg) {
        if (options.shouldStream(EventType.REASONING)) {
            // postReasoning is called after streaming completes
            // This is the last/complete message
            emitEvent(EventType.REASONING, reasoningMsg, true); // isLast=true
        }
        return Mono.just(reasoningMsg);
    }

    /**
     * Handles intermediate reasoning chunks during streaming.
     *
     * <p>This method is called for each intermediate chunk, allowing real-time observation
     * of the reasoning process.
     *
     * @param agent The agent performing reasoning
     * @param chunkMsg The intermediate chunk message
     * @return Empty Mono
     */
    @Override
    public Mono<Void> onReasoningChunk(Agent agent, Msg chunkMsg) {
        if (options.shouldStream(EventType.REASONING)) {
            // This is an intermediate chunk
            emitEvent(EventType.REASONING, chunkMsg, false); // isLast=false
        }
        return Mono.empty();
    }

    // ========== Acting/Tool Hooks ==========

    /**
     * Handles tool execution completion and emits the final tool result event.
     *
     * <p>This method is called after a tool completes execution, representing the complete
     * tool result.
     *
     * @param agent The agent executing the tool
     * @param toolUse The tool use block
     * @param toolResult The complete tool result
     * @return Mono containing the tool result
     */
    @Override
    public Mono<ToolResultBlock> postActing(
            Agent agent, ToolUseBlock toolUse, ToolResultBlock toolResult) {

        if (options.shouldStream(EventType.TOOL_RESULT)) {
            // Wrap tool result in a Msg and emit as final event
            Msg toolMsg = createToolMessage(toolResult);
            emitEvent(EventType.TOOL_RESULT, toolMsg, true); // isLast=true
        }

        return Mono.just(toolResult);
    }

    /**
     * Handles intermediate tool execution chunks during streaming.
     *
     * <p>This method is called for each intermediate chunk during tool execution,
     * allowing real-time observation of tool progress.
     *
     * @param agent The agent executing the tool
     * @param toolUse The tool use block
     * @param chunk The intermediate tool result chunk
     * @return Empty Mono
     */
    @Override
    public Mono<Void> onActingChunk(Agent agent, ToolUseBlock toolUse, ToolResultBlock chunk) {
        if (options.shouldStream(EventType.TOOL_RESULT)) {
            // Wrap chunk in a Msg and emit as intermediate event
            Msg toolMsg = createToolMessage(chunk);
            emitEvent(EventType.TOOL_RESULT, toolMsg, false); // isLast=false
        }
        return Mono.empty();
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
     * @param isLast Whether this is the last/complete message (aligned with Python)
     */
    private void emitEvent(EventType type, Msg msg, boolean isLast) {
        Msg processedMsg = msg;

        // For incremental mode, calculate the diff
        if (options.getChunkMode() == ChunkMode.INCREMENTAL && !isLast) {
            processedMsg = calculateIncremental(msg);
        }

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

    /**
     * Calculate incremental content by comparing current message with previous state.
     *
     * <p>This method extracts only the new content blocks that have been added
     * since the last chunk, enabling INCREMENTAL chunk mode where only deltas
     * are emitted rather than the full cumulative content.
     *
     * @param currentMsg The current message with all accumulated content
     * @return A new message containing only the new content blocks
     */
    private Msg calculateIncremental(Msg currentMsg) {
        String msgId = currentMsg.getId();
        List<ContentBlock> prevBlocks = previousContent.getOrDefault(msgId, List.of());
        List<ContentBlock> currBlocks = currentMsg.getContent();

        // Extract new blocks
        List<ContentBlock> newBlocks = new ArrayList<>();
        for (int i = prevBlocks.size(); i < currBlocks.size(); i++) {
            newBlocks.add(currBlocks.get(i));
        }

        // If no new blocks, return original (empty content is fine)
        if (newBlocks.isEmpty()) {
            return currentMsg;
        }

        // Build message with only new content
        return Msg.builder()
                .id(currentMsg.getId())
                .name(currentMsg.getName())
                .role(currentMsg.getRole())
                .content(newBlocks)
                .metadata(currentMsg.getMetadata())
                .build();
    }
}
