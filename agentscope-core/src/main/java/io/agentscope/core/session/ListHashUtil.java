/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.core.session;

import io.agentscope.core.state.State;
import java.util.List;

/**
 * Utility class for computing hash values of state lists.
 *
 * <p>This class provides hash computation for change detection in Session implementations. The hash
 * is used to detect if a list has been modified (not just appended) since the last save operation.
 *
 * <p>The hash computation uses a sampling strategy to avoid iterating over large lists:
 *
 * <ul>
 *   <li>For small lists (≤5 elements): all elements are included
 *   <li>For large lists: samples at positions 0, 1/4, 1/2, 3/4, and last
 * </ul>
 *
 * <p>Usage in Session implementations:
 *
 * <pre>{@code
 * String currentHash = ListHashUtil.computeHash(values);
 * String storedHash = readStoredHash();
 *
 * if (storedHash != null && !storedHash.equals(currentHash)) {
 *     // List was modified, need full rewrite
 *     rewriteEntireList(values);
 * } else if (values.size() > existingCount) {
 *     // List grew, can append incrementally
 *     appendNewItems(values);
 * }
 * }</pre>
 */
public final class ListHashUtil {

    /** Empty list hash constant. */
    private static final String EMPTY_HASH = "empty:0";

    /** Threshold for using sampling strategy. */
    private static final int SAMPLING_THRESHOLD = 5;

    private ListHashUtil() {
        // Utility class, prevent instantiation
    }

    /**
     * Compute a hash value for a list of state objects.
     *
     * <p>The hash includes:
     *
     * <ul>
     *   <li>List size
     *   <li>Hash codes of sampled elements
     * </ul>
     *
     * <p>This method is designed to be lightweight and fast, using sampling for large lists to
     * avoid O(n) iteration.
     *
     * @param values the list of state objects to hash
     * @return a hex string hash representing the list content
     */
    public static String computeHash(List<? extends State> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY_HASH;
        }

        int size = values.size();
        StringBuilder sb = new StringBuilder();
        sb.append("size:").append(size).append(";");

        // Get sample indices based on list size
        int[] sampleIndices = getSampleIndices(size);

        for (int idx : sampleIndices) {
            State item = values.get(idx);
            int itemHash = item != null ? item.hashCode() : 0;
            sb.append(idx).append(":").append(itemHash).append(",");
        }

        return Integer.toHexString(sb.toString().hashCode());
    }

    /**
     * Get the indices to sample from a list of given size.
     *
     * <p>Sampling strategy:
     *
     * <ul>
     *   <li>For size ≤ 5: returns all indices [0, 1, 2, ..., size-1]
     *   <li>For size > 5: returns [0, size/4, size/2, size*3/4, size-1]
     * </ul>
     *
     * @param size the size of the list
     * @return array of indices to sample
     */
    private static int[] getSampleIndices(int size) {
        if (size <= SAMPLING_THRESHOLD) {
            // Small list: sample all elements
            int[] indices = new int[size];
            for (int i = 0; i < size; i++) {
                indices[i] = i;
            }
            return indices;
        }

        // Large list: sample at key positions
        return new int[] {0, size / 4, size / 2, size * 3 / 4, size - 1};
    }

    /**
     * Check if the list has changed based on hash comparison.
     *
     * @param currentHash the hash of the current list
     * @param storedHash the previously stored hash (may be null)
     * @return true if the list has changed, false otherwise
     */
    public static boolean hasChanged(String currentHash, String storedHash) {
        if (storedHash == null) {
            // No previous hash, consider as new list
            return false;
        }
        return !storedHash.equals(currentHash);
    }

    /**
     * Determine if a full rewrite is needed based on hash and size comparison.
     *
     * @param currentHash the hash of the current list
     * @param storedHash the previously stored hash (may be null)
     * @param currentSize the current list size
     * @param existingCount the count of items already stored
     * @return true if full rewrite is needed, false if incremental append is sufficient
     */
    public static boolean needsFullRewrite(
            String currentHash, String storedHash, int currentSize, int existingCount) {
        // Case 1: Hash changed (list was modified, not just appended)
        if (hasChanged(currentHash, storedHash)) {
            return true;
        }

        // Case 2: List shrunk (items were deleted)
        if (currentSize < existingCount) {
            return true;
        }

        return false;
    }
}
