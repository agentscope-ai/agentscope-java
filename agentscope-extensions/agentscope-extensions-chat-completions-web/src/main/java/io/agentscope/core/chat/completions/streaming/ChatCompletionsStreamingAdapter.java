/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.chat.completions.streaming;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.chat.completions.model.ChatCompletionsChunk;
import io.agentscope.core.chat.completions.model.ToolCall;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * Framework-agnostic adapter for handling streaming chat completion responses.
 *
 * <p>This adapter converts agent events to OpenAI-compatible {@link ChatCompletionsChunk} objects.
 * It is designed to be used by framework-specific adapters (e.g., Spring SSE, Quarkus Multi) that
 * handle the final serialization and transport.
 *
 * <p><b>Design Philosophy:</b>
 *
 * <ul>
 *   <li>Core streaming logic is framework-agnostic
 *   <li>Returns {@link Flux} of {@link ChatCompletionsChunk} (not framework-specific types)
 *   <li>Framework adapters (in starter modules) handle SSE/transport specifics
 * </ul>
 *
 * <p><b>Supported Event Types:</b>
 *
 * <ul>
 *   <li>{@link EventType#REASONING} - Text content and tool call decisions
 *   <li>{@link EventType#TOOL_RESULT} - Tool execution results
 * </ul>
 *
 * <p><b>Output Chunk Types:</b>
 *
 * <ul>
 *   <li>Text chunks - Incremental text content
 *   <li>Tool call chunks - When agent decides to call tools
 *   <li>Finish chunks - Stream completion with finish_reason
 * </ul>
 */
public class ChatCompletionsStreamingAdapter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Constructs a new {@code ChatCompletionsStreamingAdapter}. */
    public ChatCompletionsStreamingAdapter() {}

    /**
     * Stream agent events as OpenAI-compatible chunks.
     *
     * <p>Subscribes to agent's event stream and converts each event to one or more {@link
     * ChatCompletionsChunk} objects following OpenAI's streaming format.
     *
     * <p><b>Text Deduplication:</b> In incremental mode, agents send text deltas (isLast=false)
     * followed by a final accumulated event (isLast=true). To avoid duplication, we filter text
     * from the last REASONING event if incremental chunks were seen, while preserving tool calls
     * and finish reasons.
     *
     * @param agent The agent to stream from
     * @param messages The messages to send to the agent
     * @param requestId The request ID for tracking (used as chunk ID)
     * @param model The model name to include in chunks
     * @return A {@link Flux} of {@link ChatCompletionsChunk} objects
     */
    public Flux<ChatCompletionsChunk> stream(
            ReActAgent agent, List<Msg> messages, String requestId, String model) {
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
                        .incremental(true)
                        .build();

        // Track if we've seen non-last REASONING events (indicates incremental mode)
        java.util.concurrent.atomic.AtomicBoolean hasSeenIncrementalReasoning =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        return agent.stream(messages, options)
                .filter(event -> event.getMessage() != null)
                .doOnNext(
                        event -> {
                            // Track if we see a non-last REASONING event (incremental chunk)
                            if (event.getType() == EventType.REASONING && !event.isLast()) {
                                hasSeenIncrementalReasoning.set(true);
                            }
                        })
                .flatMap(
                        event -> {
                            // Filter out text from last REASONING events if we've seen incremental
                            // ones
                            if (event.getType() == EventType.REASONING
                                    && event.isLast()
                                    && hasSeenIncrementalReasoning.get()) {
                                // This is the final accumulated text event, filter out its text
                                // but keep tool calls and finish reason
                                return convertEventToChunksWithoutText(event, requestId, model);
                            }
                            return convertEventToChunks(event, requestId, model);
                        });
    }

    /**
     * Convert an agent event to one or more streaming chunks.
     *
     * <p>A single event may produce multiple chunks if it contains both text and tool calls.
     *
     * @param event The agent event
     * @param requestId The request ID for tracking
     * @param model The model name
     * @return Flux of ChatCompletionsChunk objects
     */
    public Flux<ChatCompletionsChunk> convertEventToChunks(
            Event event, String requestId, String model) {
        return convertEventToChunksInternal(event, requestId, model, true);
    }

    /**
     * Convert an agent event to chunks, excluding text content.
     *
     * <p>This is used for final accumulated events in incremental mode where we want to keep tool
     * calls and finish reason but filter out duplicate text.
     *
     * @param event The agent event
     * @param requestId The request ID
     * @param model The model name
     * @return Flux of ChatCompletionsChunk objects (without text chunks)
     */
    private Flux<ChatCompletionsChunk> convertEventToChunksWithoutText(
            Event event, String requestId, String model) {
        return convertEventToChunksInternal(event, requestId, model, false);
    }

    /**
     * Internal method to convert an agent event to chunks with optional text filtering.
     *
     * @param event The agent event
     * @param requestId The request ID
     * @param model The model name
     * @param includeText Whether to include text content
     * @return Flux of ChatCompletionsChunk objects
     */
    private Flux<ChatCompletionsChunk> convertEventToChunksInternal(
            Event event, String requestId, String model, boolean includeText) {
        Msg msg = event.getMessage();
        if (msg == null) {
            return Flux.empty();
        }

        List<ContentBlock> contentBlocks = msg.getContent();
        if (contentBlocks == null || contentBlocks.isEmpty()) {
            return Flux.empty();
        }

        List<ChatCompletionsChunk> chunks = new ArrayList<>();

        // Extract text content
        StringBuilder textBuilder = new StringBuilder();
        for (ContentBlock block : contentBlocks) {
            if (block instanceof TextBlock) {
                String text = ((TextBlock) block).getText();
                if (text != null && !text.isEmpty()) {
                    textBuilder.append(text);
                }
            }
        }

        String textContent = textBuilder.toString();
        if (!textContent.isEmpty() && includeText) {
            chunks.add(ChatCompletionsChunk.textChunk(requestId, model, textContent));
        }

        // Extract tool calls (only for REASONING events from assistant)
        if (event.getType() == EventType.REASONING) {
            List<ToolCall> toolCalls = new ArrayList<>();
            for (ContentBlock block : contentBlocks) {
                if (block instanceof ToolUseBlock) {
                    ToolUseBlock toolUseBlock = (ToolUseBlock) block;
                    String argumentsJson = serializeMapToJson(toolUseBlock.getInput());
                    toolCalls.add(
                            new ToolCall(
                                    toolUseBlock.getId(), toolUseBlock.getName(), argumentsJson));
                }
            }

            if (!toolCalls.isEmpty()) {
                chunks.add(ChatCompletionsChunk.toolCallChunk(requestId, model, toolCalls));
            }
        }

        // Extract tool results (only for TOOL_RESULT events)
        if (event.getType() == EventType.TOOL_RESULT) {
            for (ContentBlock block : contentBlocks) {
                if (block instanceof ToolResultBlock) {
                    ToolResultBlock resultBlock = (ToolResultBlock) block;
                    String resultContent = extractToolResultContent(resultBlock);
                    chunks.add(
                            ChatCompletionsChunk.toolResultChunk(
                                    requestId,
                                    model,
                                    resultBlock.getId(),
                                    resultBlock.getName(),
                                    resultContent));
                }
            }
        }

        // Add finish chunk if this is the last event
        if (event.isLast()) {
            boolean hasToolCalls =
                    contentBlocks.stream().anyMatch(block -> block instanceof ToolUseBlock);
            String finishReason = hasToolCalls ? "tool_calls" : "stop";
            chunks.add(ChatCompletionsChunk.finishChunk(requestId, model, finishReason));
        }

        return Flux.fromIterable(chunks);
    }

    /**
     * Create an error chunk.
     *
     * <p>This can be used by framework adapters to handle errors in a consistent way.
     *
     * @param error The error that occurred
     * @param requestId The request ID for tracking
     * @param model The model name
     * @return A ChatCompletionsChunk representing the error
     */
    public ChatCompletionsChunk createErrorChunk(Throwable error, String requestId, String model) {
        String errorMessage = error != null ? error.getMessage() : "Unknown error occurred";
        return ChatCompletionsChunk.textChunk(requestId, model, "Error: " + errorMessage);
    }

    /**
     * Extract content from a ToolResultBlock.
     *
     * <p>Concatenates all TextBlock content from the tool result's output.
     *
     * @param resultBlock The tool result block
     * @return The extracted content as a string
     */
    private String extractToolResultContent(ToolResultBlock resultBlock) {
        if (resultBlock.getOutput() == null || resultBlock.getOutput().isEmpty()) {
            return "";
        }

        StringBuilder contentBuilder = new StringBuilder();
        for (ContentBlock outputBlock : resultBlock.getOutput()) {
            if (outputBlock instanceof TextBlock) {
                String text = ((TextBlock) outputBlock).getText();
                if (text != null && !text.isEmpty()) {
                    contentBuilder.append(text);
                }
            }
        }
        return contentBuilder.toString();
    }

    /**
     * Serialize a Map to a JSON string.
     *
     * @param map The map to serialize
     * @return JSON string representation, or "{}" if serialization fails
     */
    private String serializeMapToJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
