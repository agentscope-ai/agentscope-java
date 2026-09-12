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

import io.agentscope.core.util.JsonUtils;
import java.util.List;

/**
 * Utility class for computing hash values of state lists.
 *
 * <p>This class provides hash computation for change detection in AgentStateStore implementations. The hash
 * is used to detect if a list has been modified (not just appended) since the last save operation.
 *
 * <p>The hash is a full content hash computed over every element (serialized to JSON), so any
 * modification to any element is detected. It must NOT use sampling: a sampled hash can silently
 * miss edits to non-sampled positions and cause stale data to be kept on save.
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
     * <p>The hash includes:
     *
     * <ul>
     *   <li>List size
     *   <li>The serialized (JSON) form of every element, at every position
     * </ul>
     *
     * <p>Every element is included so that a modification at any position is detected. Detection
     * is based on the serialized form rather than {@link Object#hashCode()}, so it does not depend
     * on whether {@code State} implementations override {@code hashCode()} content-wise.
     *
     * @param values the list of state objects to hash
     * @return a hex string hash representing the list content
     */
    public static String computeHash(List<? extends State> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY_HASH;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("size:").append(values.size()).append(";");

        for (int idx = 0; idx < values.size(); idx++) {
            State item = values.get(idx);
            String json = item != null ? JsonUtils.getJsonCodec().toJson(item) : "null";
            sb.append(idx).append(":").append(json.hashCode()).append(",");
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
