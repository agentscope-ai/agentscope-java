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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Utility class for computing hash values of state lists.
 *
 * <p>This class provides hash computation for change detection in Session implementations. The hash
 * is used to detect if a list has been modified (not just appended) since the last save operation.
 *
 * <p>The hash computation covers all elements to guarantee that any modification
 * (including edits to middle elements) is reliably detected:
 * <ul>
 *   <li>Uses SHA-256 over each element's {@code hashCode()} to avoid 32-bit collision risk
 *   <li>Includes list size in the hash to detect shrink operations
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

    private ListHashUtil() {
        // Utility class, prevent instantiation
    }

    /**
     * Compute a SHA-256 hash value for a list of state objects.
     *
     * <p>遍历所有元素计算 hash，确保任意位置的元素变更（包括中间元素）都能被检测到。
     * 使用 SHA-256 代替 {@code String.hashCode()} 以避免 32 位整数碰撞风险。
     *
     * @param values the list of state objects to hash
     * @return a hex string hash representing the list content
     */
    public static String computeHash(List<? extends State> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY_HASH;
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int size = values.size();
            // 将 size 纳入 hash，防止仅缩短列表时碰撞
            digest.update(intToBytes(size));
            for (State item : values) {
                digest.update(intToBytes(item != null ? item.hashCode() : 0));
            }
            byte[] hashBytes = digest.digest();
            // 取前8字节（64位）转十六进制，已足够唯一
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hashBytes[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JVM 标准算法，不会出现此异常
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * 将 int 转为大端序 4 字节数组。
     */
    private static byte[] intToBytes(int value) {
        return new byte[] {
            (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
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
