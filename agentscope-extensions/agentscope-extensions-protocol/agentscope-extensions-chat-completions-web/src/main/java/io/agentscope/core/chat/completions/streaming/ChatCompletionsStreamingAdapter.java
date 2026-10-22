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
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.model.ChatCompletionsChunk;
import io.agentscope.core.chat.completions.model.ChatCompletionsResponse;
import io.agentscope.core.chat.completions.model.ToolCall;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.HashMap;
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
 * <p>{@link #stream} consumes {@link ReActAgent#streamEvents(List)}; see the five-argument
 * overload for how each event maps to chunks.
 *
 * <p><b>Output Chunk Types:</b>
 *
 * <ul>
 *   <li>Text chunks - Incremental text content
 *   <li>Tool call chunks - When agent decides to call tools
 *   <li>Tool result chunks - Tool output, sent as assistant text
 *   <li>Finish chunk - Once, at the end of the stream, with finish_reason
 *   <li>Usage chunk - Only with {@code stream_options.include_usage}
 * </ul>
 */
public class ChatCompletionsStreamingAdapter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Constructs a new {@code ChatCompletionsStreamingAdapter}. */
    public ChatCompletionsStreamingAdapter() {}

    /**
     * Stream agent events as OpenAI-compatible chunks.
     *
     * <p>Same as {@link #stream(ReActAgent, List, String, String, boolean)} without the usage
     * chunk.
     *
     * @param agent The agent to stream from
     * @param messages The messages to send to the agent
     * @param requestId The request ID for tracking (used as chunk ID)
     * @param model The model name to include in chunks
     * @return A {@link Flux} of {@link ChatCompletionsChunk} objects
     */
    public Flux<ChatCompletionsChunk> stream(
            ReActAgent agent, List<Msg> messages, String requestId, String model) {
        return stream(agent, messages, requestId, model, false);
    }

    /**
     * Stream agent events as OpenAI-compatible chunks, optionally ending with a usage chunk.
     *
     * <p>Built on {@link ReActAgent#streamEvents(List)}:
     *
     * <ul>
     *   <li>{@link TextBlockDeltaEvent} becomes a text chunk.
     *   <li>{@link ToolCallStartEvent} becomes a tool call chunk with the call's id and name, and
     *       each {@link ToolCallDeltaEvent} a chunk with the next piece of its arguments, as in
     *       OpenAI's streaming format.
     *   <li>A tool's {@link ToolResultTextDeltaEvent}s are collected and sent as one tool result
     *       chunk when its {@link ToolResultEndEvent} arrives.
     *   <li>{@link AgentResultEvent} ends the stream with a single finish chunk, and, when {@code
     *       includeUsage} is true, a chunk with empty {@code choices} carrying the usage of the
     *       whole request ({@link Msg#getInvocationUsage()}). No usage chunk is sent when no model
     *       call reported usage.
     * </ul>
     *
     * <p>Events forwarded from subagents (those with a {@code source}) and internal tools (names
     * starting with {@code "__"}) are not sent to the client.
     *
     * @param agent The agent to stream from
     * @param messages The messages to send to the agent
     * @param requestId The request ID for tracking (used as chunk ID)
     * @param model The model name to include in chunks
     * @param includeUsage Whether to end with a usage chunk ({@code
     *     stream_options.include_usage})
     * @return A {@link Flux} of {@link ChatCompletionsChunk} objects
     */
    public Flux<ChatCompletionsChunk> stream(
            ReActAgent agent,
            List<Msg> messages,
            String requestId,
            String model,
            boolean includeUsage) {
        return Flux.defer(
                () -> {
                    StreamState state = new StreamState();
                    return agent.streamEvents(messages)
                            .filter(event -> event.getSource() == null)
                            .concatMapIterable(
                                    event ->
                                            toChunks(event, state, requestId, model, includeUsage));
                });
    }

    /** Per-stream bookkeeping for {@link #toChunks}. */
    private static final class StreamState {

        /** OpenAI tool call index of each visible tool call, per assistant reply. */
        final Map<String, Integer> toolCallIndex = new HashMap<>();

        String toolCallReplyId;

        /** Text of each tool result, collected until its end event. */
        final Map<String, StringBuilder> toolResultText = new HashMap<>();
    }

    private List<ChatCompletionsChunk> toChunks(
            AgentEvent event,
            StreamState state,
            String requestId,
            String model,
            boolean includeUsage) {
        if (event instanceof TextBlockDeltaEvent text) {
            String delta = text.getDelta();
            return delta == null || delta.isEmpty()
                    ? List.of()
                    : List.of(ChatCompletionsChunk.textChunk(requestId, model, delta));
        }
        if (event instanceof ToolCallStartEvent start) {
            if (isInternalTool(start.getToolCallName())) {
                return List.of();
            }
            if (!start.getReplyId().equals(state.toolCallReplyId)) {
                // Tool call indexes restart for each assistant message, as in OpenAI's format.
                state.toolCallReplyId = start.getReplyId();
                state.toolCallIndex.clear();
            }
            int index = state.toolCallIndex.size();
            state.toolCallIndex.put(start.getToolCallId(), index);
            ToolCall call = new ToolCall(index, start.getToolCallId(), start.getToolCallName(), "");
            return List.of(ChatCompletionsChunk.toolCallChunk(requestId, model, List.of(call)));
        }
        if (event instanceof ToolCallDeltaEvent delta) {
            Integer index = state.toolCallIndex.get(delta.getToolCallId());
            if (index == null || delta.getDelta() == null || delta.getDelta().isEmpty()) {
                return List.of();
            }
            // Continuation deltas carry only the index and the next piece of the arguments.
            ToolCall call = new ToolCall(index, null, null, delta.getDelta());
            call.setType(null);
            return List.of(ChatCompletionsChunk.toolCallChunk(requestId, model, List.of(call)));
        }
        if (event instanceof ToolResultTextDeltaEvent resultText) {
            if (!isInternalTool(resultText.getToolCallName()) && resultText.getDelta() != null) {
                state.toolResultText
                        .computeIfAbsent(resultText.getToolCallId(), id -> new StringBuilder())
                        .append(resultText.getDelta());
            }
            return List.of();
        }
        if (event instanceof ToolResultEndEvent resultEnd) {
            if (isInternalTool(resultEnd.getToolCallName())) {
                return List.of();
            }
            StringBuilder text = state.toolResultText.remove(resultEnd.getToolCallId());
            return List.of(
                    ChatCompletionsChunk.toolResultChunk(
                            requestId,
                            model,
                            resultEnd.getToolCallId(),
                            resultEnd.getToolCallName(),
                            text != null ? text.toString() : ""));
        }
        if (event instanceof AgentResultEvent result) {
            Msg reply = result.getResult();
            List<ChatCompletionsChunk> chunks = new ArrayList<>(2);
            chunks.add(
                    ChatCompletionsChunk.finishChunk(
                            requestId,
                            model,
                            ChatCompletionsResponseBuilder.finishReasonOf(reply)));
            ChatCompletionsResponse.Usage usage =
                    includeUsage ? ChatCompletionsResponseBuilder.usageOf(reply) : null;
            if (usage != null) {
                chunks.add(ChatCompletionsChunk.usageChunk(requestId, model, usage));
            }
            return chunks;
        }
        return List.of();
    }

    private static boolean isInternalTool(String toolName) {
        return toolName != null && toolName.startsWith("__");
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
     * @deprecated since 2.1.0. Converts the deprecated {@link Event} type, which {@link #stream}
     *     no longer uses; it now consumes {@link ReActAgent#streamEvents(List)}. Kept for
     *     callers that drive {@code ReActAgent#stream(List, StreamOptions)} themselves.
     */
    @Deprecated(since = "2.1.0")
    public Flux<ChatCompletionsChunk> convertEventToChunks(
            Event event, String requestId, String model) {
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
        if (!textContent.isEmpty()) {
            chunks.add(ChatCompletionsChunk.textChunk(requestId, model, textContent));
        }

        // Extract tool calls (only for REASONING events from assistant)
        if (event.getType() == EventType.REASONING) {
            List<ToolCall> toolCalls = new ArrayList<>();
            int toolCallIndex = 0;
            for (ContentBlock block : contentBlocks) {
                if (block instanceof ToolUseBlock) {
                    ToolUseBlock toolUseBlock = (ToolUseBlock) block;
                    // Prioritize content field (raw JSON string) over input map
                    // DashScope and some providers store arguments in content field
                    String argumentsJson;
                    String content = toolUseBlock.getContent();
                    Map<String, Object> input = toolUseBlock.getInput();

                    if (content != null && !content.isEmpty()) {
                        argumentsJson = content;
                    } else if (input != null && !input.isEmpty()) {
                        // Only serialize input if it's not empty
                        argumentsJson = serializeMapToJson(input);
                    } else {
                        // Both content and input are empty - use empty string for streaming
                        // This allows clients to accumulate subsequent chunks correctly
                        argumentsJson = "";
                    }
                    toolCalls.add(
                            new ToolCall(
                                    toolCallIndex,
                                    toolUseBlock.getId(),
                                    toolUseBlock.getName(),
                                    argumentsJson));
                    toolCallIndex++;
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
            // Determine finish reason based on GenerateReason or tool calls
            // This mirrors the logic in ChatCompletionsResponseBuilder
            String finishReason;
            GenerateReason generateReason =
                    event.getMessage() != null ? event.getMessage().getGenerateReason() : null;

            if (generateReason == GenerateReason.TOOL_SUSPENDED) {
                finishReason = "tool_calls";
            } else if (generateReason == GenerateReason.MAX_ITERATIONS) {
                finishReason = "length";
            } else {
                // Fall back to checking for tool calls in content
                boolean hasToolCalls =
                        contentBlocks.stream().anyMatch(block -> block instanceof ToolUseBlock);
                finishReason = hasToolCalls ? "tool_calls" : "stop";
            }

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
