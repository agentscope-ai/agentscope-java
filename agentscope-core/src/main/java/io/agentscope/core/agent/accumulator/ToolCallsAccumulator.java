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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
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
 * @hidden
 */
public class ToolCallsAccumulator implements ContentAccumulator<ToolUseBlock> {

    // Map to support multiple parallel tool calls
    // Key: tool identifier (ID, name, or index)
    private final Map<String, ToolCallBuilder> builders = new LinkedHashMap<>();
    private int nextIndex = 0;

    // Track the last tool call key for streaming chunks without ID
    // This is needed when models return fragments with placeholder names and empty IDs
    private String lastToolCallKey = null;

    /** Builder for a single tool call. */
    private static class ToolCallBuilder {
        String toolId;
        String name;
        Map<String, Object> args = new HashMap<>();
        StringBuilder rawContent = new StringBuilder();
        Map<String, Object> metadata = new HashMap<>();

        void merge(ToolUseBlock block) {
            // Update ID if present
            if (this.toolId == null && block.getId() != null && !block.getId().isEmpty()) {
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

            // Merge metadata (e.g., thoughtSignature for Gemini 3 Pro)
            if (block.getMetadata() != null && !block.getMetadata().isEmpty()) {
                this.metadata.putAll(block.getMetadata());
            }
        }

        ToolUseBlock build() {
            Map<String, Object> finalArgs = new HashMap<>(args);
            String rawContentStr = this.rawContent.toString();

            // If no parsed arguments but has raw JSON content, try to parse
            if (finalArgs.isEmpty() && rawContentStr.length() > 0) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed =
                            JsonUtils.getJsonCodec().fromJson(rawContentStr, Map.class);
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
                    .content(rawContentStr.isEmpty() ? "{}" : rawContentStr)
                    .metadata(metadata.isEmpty() ? null : metadata)
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

    /**
     * @hidden
     */
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
     *   <li>Use tool ID if available (non-empty)
     *   <li>Use tool name if available (non-placeholder)
     *   <li>If this is a fragment (placeholder name), reuse the last tool call key
     *   <li>Otherwise, use index for chunks without any identifier
     * </ol>
     */
    private String determineKey(ToolUseBlock block) {
        // 1. Prefer tool ID if non-empty
        if (block.getId() != null && !block.getId().isEmpty()) {
            String key = block.getId();
            // Remember this key if it's not a placeholder
            if (block.getName() != null && !isPlaceholder(block.getName())) {
                lastToolCallKey = key;
            }
            return key;
        }

        // 2. Use tool name (non-placeholder)
        if (block.getName() != null && !isPlaceholder(block.getName())) {
            String key = "name:" + block.getName();
            lastToolCallKey = key;
            return key;
        }

        // 3. If this is a fragment (placeholder name) and we have a last key, reuse it
        if (isPlaceholder(block.getName()) && lastToolCallKey != null) {
            return lastToolCallKey;
        }

        // 4. Use index (for chunks without any identifier)
        String key = "index:" + nextIndex++;
        lastToolCallKey = key;
        return key;
    }

    private boolean isPlaceholder(String name) {
        return name != null && name.startsWith("__");
    }

    /**
     * @hidden
     */
    @Override
    public boolean hasContent() {
        return !builders.isEmpty();
    }

    /**
     * @hidden
     */
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
     * @hidden
     * @return List of tool calls
     */
    public List<ToolUseBlock> buildAllToolCalls() {
        return builders.values().stream().map(ToolCallBuilder::build).collect(Collectors.toList());
    }

    /**
     * Get accumulated tool call by ID.
     *
     * <p>If the ID is null or empty, or if no builder is found for the given ID,
     * this method falls back to using the lastToolCallKey.
     *
     * @param id The tool call ID to look up
     * @return The accumulated ToolUseBlock, or null if not found
     */
    public ToolUseBlock getAccumulatedToolCall(String id) {
        if (id != null && !id.isEmpty()) {
            // First try to find by ID directly
            ToolCallBuilder builder = builders.get(id);
            if (builder != null) {
                return builder.build();
            }
        }

        // Fallback to lastToolCallKey if ID is empty or not found
        if (lastToolCallKey != null) {
            ToolCallBuilder builder = builders.get(lastToolCallKey);
            if (builder != null) {
                return builder.build();
            }
        }

        return null;
    }

    /**
     * Get all accumulated tool calls.
     *
     * <p>This is an alias for {@link #buildAllToolCalls()} for API consistency.
     *
     * @return List of all accumulated ToolUseBlocks
     */
    public List<ToolUseBlock> getAllAccumulatedToolCalls() {
        return buildAllToolCalls();
    }

    /**
     * Get the ID of the current (last) tool call being accumulated.
     *
     * <p>This is useful for enriching fragment chunks with the correct tool call ID,
     * allowing users to properly concatenate streaming chunks.
     *
     * @return The current tool call ID, or null if no tool call is being accumulated
     */
    public String getCurrentToolCallId() {
        if (lastToolCallKey == null) {
            return null;
        }

        ToolCallBuilder builder = builders.get(lastToolCallKey);
        if (builder == null) {
            return null;
        }

        return builder.toolId;
    }

    /**
     * @hidden
     */
    @Override
    public void reset() {
        builders.clear();
        nextIndex = 0;
        lastToolCallKey = null;
    }
}
