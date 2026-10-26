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
package io.agentscope.core.util;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 按键的哈希值选择固定数量的可重入锁，不保留键，避免锁表随路径数量增长。
 *
 * <p>相同键始终使用同一把锁；不同键可能共享锁槽，因此会产生额外竞争。
 * 调用方应限制嵌套加锁，不能依赖不同键一定对应不同锁。
 */
public final class StripedLocks {

    private static final int MAX_STRIPE_COUNT = 1 << 30;

    private final ReentrantLock[] stripes;

    /**
     * 创建固定大小的锁池，锁槽数量向上取整为二的幂。
     *
     * @param stripeCount 请求的锁槽数量，必须在 1 到 2 的 30 次方之间
     * @throws IllegalArgumentException 锁槽数量非正数，或取整后无法表示为正整数
     */
    public StripedLocks(int stripeCount) {
        if (stripeCount <= 0 || stripeCount > MAX_STRIPE_COUNT) {
            throw new IllegalArgumentException("stripeCount out of range: " + stripeCount);
        }
        int highest = Integer.highestOneBit(stripeCount);
        int size = highest == stripeCount ? highest : highest << 1;
        this.stripes = new ReentrantLock[size];
        for (int i = 0; i < size; i++) {
            this.stripes[i] = new ReentrantLock();
        }
    }

    /**
     * 返回指定键对应的锁；键的哈希值必须在使用期间保持稳定。
     *
     * @param key 已由调用方保证非空的键
     * @return 该键对应的可重入锁，由调用方负责加锁和释放
     */
    public ReentrantLock get(Object key) {
        int hash = key.hashCode();
        // 将高位混入低位，避免仅高位不同的哈希值集中到同一个锁槽。
        hash ^= hash >>> 16;
        return stripes[hash & (stripes.length - 1)];
    }
}
