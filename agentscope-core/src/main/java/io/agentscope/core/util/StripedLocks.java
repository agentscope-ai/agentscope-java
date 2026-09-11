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
 * A fixed-size array of striped {@link ReentrantLock}s selected by key hash.
 *
 * <p>Use this instead of a {@code Map<String, ReentrantLock>} populated via
 * {@code computeIfAbsent} when the key space is unbounded (for example, file paths produced by
 * model output in an agent runtime). A per-key lock map never evicts its entries and therefore
 * grows without bound over the lifetime of the process, whereas the memory footprint of striped
 * locks is constant regardless of how many distinct keys are ever locked.
 *
 * <p>Two distinct keys may hash to the same stripe and thus contend on the same lock. This false
 * sharing only affects throughput, never correctness: mutual exclusion per key is always
 * preserved. For coarse-grained workloads such as file I/O the cost is negligible.
 */
public final class StripedLocks {

    private final ReentrantLock[] stripes;

    /**
     * Creates a lock striping with the given number of stripes.
     *
     * @param stripeCount the desired number of stripes; rounded up to the next power of two,
     *     must be positive
     */
    public StripedLocks(int stripeCount) {
        if (stripeCount <= 0) {
            throw new IllegalArgumentException("stripeCount must be positive: " + stripeCount);
        }
        int size = ceilingPowerOfTwo(stripeCount);
        this.stripes = new ReentrantLock[size];
        for (int i = 0; i < size; i++) {
            this.stripes[i] = new ReentrantLock();
        }
    }

    /**
     * Returns the lock associated with the given key. The same key always maps to the same lock.
     *
     * @param key the key to select a stripe for, must not be null
     * @return the {@link ReentrantLock} guarding the stripe this key belongs to
     */
    public ReentrantLock get(Object key) {
        return stripes[indexFor(key)];
    }

    /** Returns the actual number of stripes (a power of two). */
    public int size() {
        return stripes.length;
    }

    private int indexFor(Object key) {
        int h = key.hashCode();
        // Spread higher bits downwards (same idea as ConcurrentHashMap) so that keys whose
        // hash codes differ only in the upper bits still land on different stripes.
        h ^= (h >>> 16);
        return h & (stripes.length - 1);
    }

    private static int ceilingPowerOfTwo(int value) {
        int highest = Integer.highestOneBit(value);
        return highest == value ? value : highest << 1;
    }
}
