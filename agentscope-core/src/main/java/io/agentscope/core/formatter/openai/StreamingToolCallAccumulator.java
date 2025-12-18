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
package io.agentscope.core.formatter.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.formatter.openai.dto.OpenAIToolCall;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accumulator for streaming tool call responses.
 *
 * <p>In streaming mode, OpenAI returns tool calls incrementally across multiple chunks.
 * This class accumulates the partial data to build complete tool calls.
 *
 * <p>Example usage:
 * <pre>{@code
 * StreamingToolCallAccumulator accumulator = new StreamingToolCallAccumulator();
 *
 * // Process streaming chunks
 * flux.subscribe(response -> {
 *     List<OpenAIToolCall> toolCalls = response.getChoices().get(0)
 *         .getDelta().getToolCalls();
 *     if (toolCalls != null) {
 *         accumulator.accumulate(toolCalls);
 *     }
 * });
 *
 * // Get complete tool calls
 * List<ToolUseBlock> completedCalls = accumulator.getCompletedToolCalls();
 * }</pre>
 */
public class StreamingToolCallAccumulator {

    private static final Logger log = LoggerFactory.getLogger(StreamingToolCallAccumulator.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Store accumulated data indexed by tool call index
    private final Map<Integer, StringBuilder> argumentsBuffer = new HashMap<>();
    private final Map<Integer, String> toolCallIds = new HashMap<>();
    private final Map<Integer, String> toolNames = new HashMap<>();

    /**
     * Accumulate a list of streaming tool call chunks.
     *
     * @param toolCalls List of tool call chunks from a streaming response
     */
    public void accumulate(List<OpenAIToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }

        for (OpenAIToolCall toolCall : toolCalls) {
            accumulate(toolCall);
        }
    }

    /**
     * Accumulate a single streaming tool call chunk.
     *
     * @param toolCall Tool call chunk from a streaming response
     */
    public void accumulate(OpenAIToolCall toolCall) {
        if (toolCall == null || toolCall.getFunction() == null) {
            return;
        }

        int index = toolCall.getIndex() != null ? toolCall.getIndex() : 0;

        // Store tool call ID (usually comes in first chunk)
        if (toolCall.getId() != null && !toolCall.getId().isEmpty()) {
            toolCallIds.put(index, toolCall.getId());
            log.debug("Stored tool call ID for index {}: {}", index, toolCall.getId());
        }

        // Store tool name (usually comes in first chunk)
        if (toolCall.getFunction().getName() != null
                && !toolCall.getFunction().getName().isEmpty()) {
            toolNames.put(index, toolCall.getFunction().getName());
            log.debug("Stored tool name for index {}: {}", index, toolCall.getFunction().getName());
        }

        // Accumulate arguments (comes incrementally across chunks)
        String arguments = toolCall.getFunction().getArguments();
        if (arguments != null && !arguments.isEmpty()) {
            argumentsBuffer.computeIfAbsent(index, k -> new StringBuilder()).append(arguments);
            log.trace(
                    "Accumulated {} characters of arguments for tool call index {}",
                    arguments.length(),
                    index);
        }
    }

    /**
     * Get all completed tool calls.
     *
     * <p>This method parses the accumulated JSON arguments and returns
     * complete ToolUseBlock objects.
     *
     * @return List of completed tool calls
     */
    public List<ToolUseBlock> getCompletedToolCalls() {
        List<ToolUseBlock> result = new ArrayList<>();

        // Get all indices and sort them
        Set<Integer> allIndices = new HashSet<>();
        allIndices.addAll(toolCallIds.keySet());
        allIndices.addAll(toolNames.keySet());
        allIndices.addAll(argumentsBuffer.keySet());

        List<Integer> sortedIndices = new ArrayList<>(allIndices);
        Collections.sort(sortedIndices);

        for (Integer index : sortedIndices) {
            String toolCallId = toolCallIds.get(index);
            String toolName = toolNames.get(index);
            String argumentsJson =
                    argumentsBuffer.get(index) != null ? argumentsBuffer.get(index).toString() : "";

            // Skip if we don't have essential data
            if (toolCallId == null || toolName == null || argumentsJson.isEmpty()) {
                log.warn(
                        "Incomplete tool call at index {}: id={}, name={}, args.length={}",
                        index,
                        toolCallId,
                        toolName,
                        argumentsJson.length());
                continue;
            }

            // Parse arguments JSON
            Map<String, Object> argsMap = new HashMap<>();
            try {
                if (argumentsJson.trim().startsWith("{") && argumentsJson.trim().endsWith("}")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = objectMapper.readValue(argumentsJson, Map.class);
                    if (parsed != null) {
                        argsMap.putAll(parsed);
                    }
                }
            } catch (Exception e) {
                log.warn(
                        "Failed to parse tool call arguments for {}: {}. Raw: {}",
                        toolName,
                        e.getMessage(),
                        argumentsJson.substring(0, Math.min(100, argumentsJson.length())));
            }

            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder()
                            .id(toolCallId)
                            .name(toolName)
                            .input(argsMap)
                            .content(argumentsJson)
                            .build();

            result.add(toolUseBlock);
            log.debug(
                    "Completed tool call: id={}, name={}, args.size={}",
                    toolCallId,
                    toolName,
                    argsMap.size());
        }

        return result;
    }

    /**
     * Get the accumulated arguments JSON for a specific tool call index.
     *
     * @param index Tool call index
     * @return Accumulated arguments JSON, or empty string if not available
     */
    public String getAccumulatedArguments(int index) {
        StringBuilder buffer = argumentsBuffer.get(index);
        return buffer != null ? buffer.toString() : "";
    }

    /**
     * Get the tool call ID for a specific index.
     *
     * @param index Tool call index
     * @return Tool call ID, or null if not available
     */
    public String getToolCallId(int index) {
        return toolCallIds.get(index);
    }

    /**
     * Get the tool name for a specific index.
     *
     * @param index Tool call index
     * @return Tool name, or null if not available
     */
    public String getToolName(int index) {
        return toolNames.get(index);
    }

    /**
     * Check if a tool call at the given index is complete.
     *
     * <p>A tool call is considered complete if it has ID, name, and valid JSON arguments.
     *
     * @param index Tool call index
     * @return true if the tool call is complete
     */
    public boolean isComplete(int index) {
        String toolCallId = toolCallIds.get(index);
        String toolName = toolNames.get(index);
        String arguments = getAccumulatedArguments(index);

        if (toolCallId == null || toolName == null || arguments.isEmpty()) {
            return false;
        }

        // Check if arguments form valid JSON
        String trimmed = arguments.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }

    /**
     * Get the number of tool calls being accumulated.
     *
     * @return Number of distinct tool call indices
     */
    public int size() {
        Set<Integer> allIndices = new HashSet<>();
        allIndices.addAll(toolCallIds.keySet());
        allIndices.addAll(toolNames.keySet());
        allIndices.addAll(argumentsBuffer.keySet());
        return allIndices.size();
    }

    /**
     * Clear all accumulated data.
     */
    public void clear() {
        argumentsBuffer.clear();
        toolCallIds.clear();
        toolNames.clear();
        log.debug("Cleared tool call accumulator");
    }

    /**
     * Check if any tool call data has been accumulated.
     *
     * @return true if no data has been accumulated
     */
    public boolean isEmpty() {
        return argumentsBuffer.isEmpty() && toolCallIds.isEmpty() && toolNames.isEmpty();
    }
}
