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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tool calls accumulator for accumulating streaming tool call chunks.
 *
 * <p>This accumulator supports multiple parallel tool calls and handles:
 *
 * <ul>
 *   <li>Tool name and ID accumulation
 *   <li>Incremental parameter merging
 *   <li>Raw JSON content accumulation and parsing
 *   <li>Placeholder name handling (e.g., "__fragment__")
 * </ul>
 */
public class ToolCallsAccumulator implements ContentAccumulator<ToolUseBlock> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Map to support multiple parallel tool calls
    // Key: tool identifier (ID, name, or index)
    private final Map<String, ToolCallBuilder> builders = new LinkedHashMap<>();
    private int nextIndex = 0;

    /** Builder for a single tool call. */
    private static class ToolCallBuilder {
        String toolId;
        String name;
        Map<String, Object> args = new HashMap<>();
        StringBuilder rawContent = new StringBuilder();

        void merge(ToolUseBlock block) {
            // Update ID if present
            if (block.getId() != null && !block.getId().isEmpty()) {
                this.toolId = block.getId();
            }

            // Update name (ignore placeholders)
            if (block.getName() != null && !isPlaceholder(block.getName())) {
                this.name = block.getName();
            }

            // Merge parameters
            if (block.getInput() != null) {
                this.args.putAll(block.getInput());
            }

            // Accumulate raw content (for parsing complete JSON)
            if (block.getContent() != null) {
                this.rawContent.append(block.getContent());
            }
        }

        ToolUseBlock build() {
            Map<String, Object> finalArgs = new HashMap<>(args);

            // If no parsed arguments but has raw JSON content, try to parse
            if (finalArgs.isEmpty() && rawContent.length() > 0) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = MAPPER.readValue(rawContent.toString(), Map.class);
                    if (parsed != null) {
                        finalArgs.putAll(parsed);
                    }
                } catch (Exception ignored) {
                    // Parsing failed, keep empty args
                }
            }

            return ToolUseBlock.builder()
                    .id(toolId != null ? toolId : generateId())
                    .name(name)
                    .input(finalArgs)
                    .build();
        }

        private boolean isPlaceholder(String name) {
            // Common placeholder names
            return "__fragment__".equals(name)
                    || "__pending__".equals(name)
                    || (name != null && name.startsWith("__"));
        }

        private String generateId() {
            return "tool_call_" + System.currentTimeMillis();
        }
    }

    @Override
    public void add(ToolUseBlock block) {
        if (block == null) {
            return;
        }

        // Determine which tool call this block belongs to
        String key = determineKey(block);

        // Get or create the corresponding builder
        ToolCallBuilder builder = builders.computeIfAbsent(key, k -> new ToolCallBuilder());

        // Merge the block
        builder.merge(block);
    }

    /**
     * Determine the key for a tool call (to distinguish multiple parallel calls).
     *
     * <p>Priority:
     *
     * <ol>
     *   <li>Use tool ID if available
     *   <li>Use tool name if available (non-placeholder)
     *   <li>Use index for chunks without any identifier
     * </ol>
     */
    private String determineKey(ToolUseBlock block) {
        // 1. Prefer tool ID
        if (block.getId() != null && !block.getId().isEmpty()) {
            return block.getId();
        }

        // 2. Use tool name (non-placeholder)
        if (block.getName() != null && !isPlaceholder(block.getName())) {
            return "name:" + block.getName();
        }

        // 3. Use index (for chunks without any identifier)
        return "index:" + nextIndex++;
    }

    private boolean isPlaceholder(String name) {
        return name != null && name.startsWith("__");
    }

    @Override
    public boolean hasContent() {
        return !builders.isEmpty();
    }

    @Override
    public ContentBlock buildAggregated() {
        List<ToolUseBlock> toolCalls = buildAllToolCalls();

        // If only one tool call, return it
        // If multiple, return the last one (or could return a special multi-call block)
        if (toolCalls.isEmpty()) {
            return null;
        }

        return toolCalls.get(toolCalls.size() - 1);
    }

    /**
     * Build all accumulated tool calls.
     *
     * @return List of tool calls
     */
    public List<ToolUseBlock> buildAllToolCalls() {
        return builders.values().stream().map(ToolCallBuilder::build).collect(Collectors.toList());
    }

    /**
     * Get all tool call messages as separate Msg objects.
     *
     * @param messageId Message ID to use
     * @param agentName Agent name to use
     * @param role Message role to use
     * @return List of tool call messages
     */
    public List<io.agentscope.core.message.Msg> buildAllToolCallMessages(
            String messageId, String agentName, io.agentscope.core.message.MsgRole role) {
        List<io.agentscope.core.message.Msg> messages = new ArrayList<>();

        for (ToolUseBlock toolCall : buildAllToolCalls()) {
            messages.add(
                    io.agentscope.core.message.Msg.builder()
                            .id(messageId)
                            .name(agentName)
                            .role(role)
                            .content(toolCall)
                            .build());
        }

        return messages;
    }

    @Override
    public void reset() {
        builders.clear();
        nextIndex = 0;
    }
}
