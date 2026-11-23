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
package io.agentscope.core.state;

import java.util.List;

/**
 * Utility class for computing hash values of state lists.
 *
 * <p>This class provides hash computation for change detection in AgentStateStore implementations.
 * The hash is used to detect if a list has been modified (not just appended) since the last save
 * operation.
 *
 * <p>The hash covers every element so that replacing any element changes it. Full iteration is
 * both necessary and cheap: a sample of positions would miss edits outside the sampled indices,
 * and a save already serializes the whole list to JSON and commits it to storage, which costs
 * orders of magnitude more than reading one {@code hashCode()} per element.
 *
 * <p>Stability of the hash depends on the elements comparing by content, which {@link State}
 * requires of implementations stored in lists.
 *
 * <p>Usage in AgentStateStore implementations:
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

    private ListHashUtil() {
        // Utility class, prevent instantiation
    }

    /**
     * Compute a hash value for a list of state objects.
     *
     * <p>The hash includes the list size and the hash code of every element, so replacing any
     * element changes it. Its stability depends on the elements comparing by content, which
     * {@link State} requires of implementations stored in lists.
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

        for (int idx = 0; idx < size; idx++) {
            State item = values.get(idx);
            int itemHash = item != null ? item.hashCode() : 0;
            sb.append(idx).append(":").append(itemHash).append(",");
        }

        return Integer.toHexString(sb.toString().hashCode());
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
     * Determine if a full rewrite is needed based on list content and existing count.
     *
     * @param currentValues the current complete list of state objects
     * @param storedHash the previously stored hash (may be null)
     * @param existingCount the count of items already stored
     * @return true if full rewrite is needed, false if incremental append is sufficient
     */
    public static boolean needsFullRewrite(
            List<? extends State> currentValues, String storedHash, int existingCount) {
        if (currentValues == null) {
            return existingCount > 0;
        }

        int currentSize = currentValues.size();

        // Case 1: List shrunk (items were deleted)
        if (currentSize < existingCount) {
            return true;
        }

        // Case 2: Missing hash but existing data found (e.g., version upgrade or corrupted hash)
        // Must rewrite because we cannot verify unmodified state.
        if (storedHash == null && existingCount > 0) {
            return true;
        }

        // Case 3: Check if the previously existing elements were modified.
        List<? extends State> prefix = currentValues.subList(0, existingCount);
        String prefixHash = computeHash(prefix);
        return hasChanged(prefixHash, storedHash);
    }
}
