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

import io.agentscope.core.message.ContentBlockMetadataKeys;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared metadata merge rules for streaming content accumulators.
 *
 * <p>List values are accumulated in stream order: entries from each chunk are appended rather than
 * replaced. Scalar values use last-write-wins. List values are always copied to prevent caller
 * mutation from affecting accumulator state.
 *
 * @hidden
 */
final class BlockMetadataMerger {

    private BlockMetadataMerger() {}

    /**
     * Merges incoming metadata into the target map using the accumulator merge rules.
     *
     * @param target accumulator-owned map to merge into
     * @param incoming metadata from the incoming chunk (never modified)
     */
    static void merge(Map<String, Object> target, Map<String, Object> incoming) {
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
     * Returns a copy of the metadata without the scalar thought signature.
     *
     * <p>A thought signature only applies to the single provider Part it was attached to. When an
     * aggregate view merges the text of multiple Parts, keeping one Part's signature on the merged
     * block would mis-attribute it, so callers building aggregate views drop it.
     *
     * @param metadata source metadata (never modified)
     * @return a new map without the {@link ContentBlockMetadataKeys#THOUGHT_SIGNATURE} entry
     */
    static Map<String, Object> withoutThoughtSignature(Map<String, Object> metadata) {
        Map<String, Object> copy = new HashMap<>(metadata);
        copy.remove(ContentBlockMetadataKeys.THOUGHT_SIGNATURE);
        return copy;
    }
}
