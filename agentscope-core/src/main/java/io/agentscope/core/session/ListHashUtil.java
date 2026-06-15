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
        // hash 中包含 size，所以列表缩短(size变小)一定能被检测到
        sb.append("size:").append(size).append(";");

        // 获取采样索引：≤5 全量采样，>5 按 [0, 1/4, 1/2, 3/4, tail] 采样 5 个位置
        int[] sampleIndices = getSampleIndices(size);

        // 拼接 "索引:hash,索引:hash,..." 形成指纹字符串
        // 示例: "size:100;0:12345,25:67890,50:11111,75:22222,99:33333"
        for (int idx : sampleIndices) {
            State item = values.get(idx);
            int itemHash = item != null ? item.hashCode() : 0;
            sb.append(idx).append(":").append(itemHash).append(",");
        }

        // 最终转为 hex 字符串存储到 Redis
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
            // 小列表（≤5个元素）：对所有元素采样，因为遍历成本很低
            int[] indices = new int[size];
            for (int i = 0; i < size; i++) {
                indices[i] = i;
            }
            return indices;
        }

        // 大列表（>5个元素）：用 5 个固定比例位置采样，O(1) 代价覆盖列表的头、中、尾
        // 示例: size=100 → [0, 25, 50, 75, 99] 分别对应 0%、25%、50%、75%、末尾
        // 这样只要这 5 个位置 + size 的 hash 没变，就可以推断列表仅有尾部追加
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
     * Determine if a full rewrite is needed based on list content and existing count.
     *
     * <p>三种结果:
     * <ul>
     *   <li>true  → 全量重写: DEL + 逐条 RPUSH 重建整个 List</li>
     *   <li>false → 增量追加: 只 RPUSH 新增的尾部元素（最热路径）</li>
     *   <li>false → 无变化跳过: size 没变则零 Redis 开销</li>
     * </ul>
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

        // 情况1: 列表缩短了（有元素被删除，如 memory.deleteMessage 或 clear）
        // Redis List 不支持删除中间元素，必须全量重建
        if (currentSize < existingCount) {
            return true;
        }

        // 情况2: Redis 中已有数据但哈希丢失（版本升级/数据损坏）
        // 无法验证已有部分是否被修改，安全起见全量重写
        if (storedHash == null && existingCount > 0) {
            return true;
        }

        // 情况3: 取已有部分（prefix = 前 existingCount 个元素），
        // 用采样 hash 比对，判断已有部分是否被修改
        // - hash 变了 → 内部有修改，需要全量重写
        // - hash 没变 → 可以增量追加（仅尾部新增）
        List<? extends State> prefix = currentValues.subList(0, existingCount);
        String prefixHash = computeHash(prefix);
        return hasChanged(prefixHash, storedHash);
    }
}
