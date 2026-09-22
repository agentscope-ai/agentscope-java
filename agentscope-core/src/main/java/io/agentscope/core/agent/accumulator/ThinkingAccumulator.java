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
import io.agentscope.core.message.ContentBlockMetadataKeys;
import io.agentscope.core.message.ThinkingBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thinking content accumulator for accumulating streaming thinking chunks.
 *
 * <p>This accumulator concatenates all thinking chunks in order to build the complete thinking
 * content.
 *
 * <p>List-valued metadata (e.g. reasoning details) is accumulated in stream order: entries from
 * each chunk are appended rather than replaced. Scalar metadata uses last-write-wins. List values
 * are always copied to prevent caller mutation from affecting accumulator state.
 * @hidden
 */
public class ThinkingAccumulator implements ContentAccumulator<ThinkingBlock> {

    private final StringBuilder accumulated = new StringBuilder();
    private final Map<String, Object> metadata = new HashMap<>();
    private final List<ThinkingBlock> completedBlocks = new ArrayList<>();
    private final StringBuilder currentBlock = new StringBuilder();
    private final Map<String, Object> currentMetadata = new HashMap<>();

    /**
     * @hidden
     */
    @Override
    public void add(ThinkingBlock block) {
        if (block == null) {
            return;
        }
        if (block.getThinking() != null) {
            accumulated.append(block.getThinking());
            currentBlock.append(block.getThinking());
        }

        Map<String, Object> blockMetadata = block.getMetadata();
        if (blockMetadata != null && !blockMetadata.isEmpty()) {
            mergeMetadata(metadata, blockMetadata);
            mergeMetadata(currentMetadata, blockMetadata);
        }

        // A thought signature terminates its provider Part. Keep that boundary so distinct
        // signature-bearing Parts can be replayed without merging their metadata.
        if (blockMetadata != null
                && blockMetadata.get(ContentBlockMetadataKeys.THOUGHT_SIGNATURE) != null) {
            completeCurrentBlock();
        }
    }

    /**
     * @hidden
     */
    @Override
    public boolean hasContent() {
        return accumulated.length() > 0 || !metadata.isEmpty();
    }

    /**
     * @hidden
     */
    @Override
    public ContentBlock buildAggregated() {
        if (!hasContent()) {
            return null;
        }
        ThinkingBlock.Builder builder = ThinkingBlock.builder().thinking(accumulated.toString());
        if (!metadata.isEmpty()) {
            builder.metadata(new HashMap<>(metadata));
        }
        return builder.build();
    }

    /**
     * Build accumulated thinking blocks while preserving thought-signature Part boundaries.
     *
     * @hidden
     * @return accumulated thinking blocks in their original order
     */
    public List<ThinkingBlock> buildAllThinkingBlocks() {
        if (!hasContent()) {
            return List.of();
        }

        List<ThinkingBlock> blocks = new ArrayList<>(completedBlocks);
        if (currentBlock.length() > 0 || !currentMetadata.isEmpty()) {
            blocks.add(buildCurrentBlock());
        }
        return List.copyOf(blocks);
    }

    /**
     * @hidden
     */
    @Override
    public void reset() {
        accumulated.setLength(0);
        metadata.clear();
        completedBlocks.clear();
        currentBlock.setLength(0);
        currentMetadata.clear();
    }

    /**
     * Merges incoming metadata into the given accumulator metadata map.
     *
     * <p>List values are accumulated in stream order (entries appended, not replaced). Scalar
     * values use last-write-wins. List values are always copied to prevent caller mutation.
     */
    private void mergeMetadata(Map<String, Object> target, Map<String, Object> incoming) {
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            String key = entry.getKey();
            Object newValue = entry.getValue();
            Object existing = target.get(key);
            if (existing instanceof List<?> existingList && newValue instanceof List<?> newList) {
                // Both are lists: concatenate in stream order
                List<Object> combined = new ArrayList<>(existingList);
                combined.addAll(newList);
                target.put(key, combined);
            } else if (newValue instanceof List<?> newList) {
                // First list for this key: copy to prevent caller mutation
                target.put(key, new ArrayList<>(newList));
            } else {
                // Scalar value: last-write-wins
                target.put(key, newValue);
            }
        }
    }

    /**
     * Get the accumulated thinking content.
     *
     * @hidden
     * @return accumulated thinking as string
     */
    public String getAccumulated() {
        return accumulated.toString();
    }

    private void completeCurrentBlock() {
        completedBlocks.add(buildCurrentBlock());
        currentBlock.setLength(0);
        currentMetadata.clear();
    }

    private ThinkingBlock buildCurrentBlock() {
        return ThinkingBlock.builder()
                .thinking(currentBlock.toString())
                .metadata(currentMetadata.isEmpty() ? null : currentMetadata)
                .build();
    }
}
