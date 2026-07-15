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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(ToolCallsAccumulator.class);

    // Map to support multiple parallel tool calls
    // Key: tool identifier (ID, name, or index)
    private final Map<String, ToolCallBuilder> builders = new LinkedHashMap<>();
    private int nextIndex = 0;

    // Track the last tool call key for streaming chunks without ID
    // This is needed when models return fragments with placeholder names and empty IDs
    private String lastToolCallKey = null;

    // Maps the protocol-level tool call index (OpenAI tool_calls[].index) to a builder key.
    // The index is the only identity guaranteed to be present on every chunk of the same
    // tool call, so it takes priority over id/name heuristics when available.
    private final Map<Integer, String> streamIndexToKey = new HashMap<>();

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

        boolean hasName() {
            return name != null;
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

            // Always validate rawContent is a legal JSON object before using it
            // as content. This prevents persisting malformed JSON fragments
            // (e.g. when streaming was interrupted mid-arguments).
            String contentStr;
            if (rawContentStr.isEmpty()) {
                contentStr = "{}";
            } else if (JsonUtils.isValidJsonObject(rawContentStr)) {
                contentStr = rawContentStr;
            } else {
                contentStr = "{}";
            }

            // The stream index is internal routing information; do not leak it
            // into the final block (it would otherwise be persisted in memory).
            Map<String, Object> finalMetadata = new HashMap<>(metadata);
            finalMetadata.remove(ToolUseBlock.METADATA_STREAM_INDEX);

            return ToolUseBlock.builder()
                    .id(toolId != null ? toolId : generateId())
                    .name(name)
                    .input(finalArgs)
                    .content(contentStr)
                    .metadata(finalMetadata.isEmpty() ? null : finalMetadata)
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
     *   <li>Use the protocol-level stream index if present (every chunk of the same tool call
     *       carries the same index, regardless of whether it has an id or name)
     *   <li>Use tool ID if available (non-empty)
     *   <li>Use tool name if available (non-placeholder)
     *   <li>If this is a fragment (placeholder name), reuse the last tool call key
     *   <li>Otherwise, use index for chunks without any identifier
     * </ol>
     */
    private String determineKey(ToolUseBlock block) {
        // 0. Prefer the protocol-level stream index when the parser provided one
        Integer streamIndex = getStreamIndex(block);
        if (streamIndex != null) {
            String key = streamIndexToKey.get(streamIndex);
            if (key == null) {
                // First chunk for this index: prefer the real tool ID as key,
                // otherwise derive a stable key from the index itself
                if (block.getId() != null && !block.getId().isEmpty()) {
                    key = block.getId();
                } else {
                    key = "stream_index:" + streamIndex;
                }
                streamIndexToKey.put(streamIndex, key);
            }
            lastToolCallKey = key;
            return key;
        }

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

    private Integer getStreamIndex(ToolUseBlock block) {
        if (block.getMetadata() == null) {
            return null;
        }
        Object index = block.getMetadata().get(ToolUseBlock.METADATA_STREAM_INDEX);
        return index instanceof Integer ? (Integer) index : null;
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
     * <p>Builders whose tool name never arrived (e.g. a gateway stripped {@code function.name}
     * from every streaming chunk) are dropped with a warning instead of producing a block with
     * a null name. Such a block could not be executed, and once persisted in memory it would be
     * skipped on every subsequent model request, stalling the agent.
     *
     * @hidden
     * @return List of tool calls
     */
    public List<ToolUseBlock> buildAllToolCalls() {
        return builders.values().stream()
                .filter(
                        builder -> {
                            if (builder.hasName()) {
                                return true;
                            }
                            log.warn(
                                    "Dropping accumulated tool call without a name (id={},"
                                            + " arguments={}). The model or gateway did not send"
                                            + " function.name in any streaming chunk.",
                                    builder.toolId,
                                    builder.rawContent);
                            return false;
                        })
                .map(ToolCallBuilder::build)
                .collect(Collectors.toList());
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
        streamIndexToKey.clear();
    }
}
