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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class StripedLocksTest {

    @Test
    void lockCountStaysBoundedAcrossManyPaths() {
        StripedLocks locks = new StripedLocks(64);
        Set<ReentrantLock> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < 100_000; i++) {
            retained.add(locks.get("workspace/" + i + ".md"));
        }
        assertEquals(64, retained.size());
        assertSame(locks.get(new String("same-path")), locks.get(new String("same-path")));
    }

    @Test
    void stripeCountRoundsUpWithoutCollapsingCapacity() {
        for (int[] sample : new int[][] {{1, 1}, {33, 64}, {64, 64}, {65, 128}}) {
            StripedLocks locks = new StripedLocks(sample[0]);
            Set<ReentrantLock> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int key = 0; key < sample[1]; key++) {
                distinct.add(locks.get(key));
            }
            assertEquals(sample[1], distinct.size());
        }
    }

    @Test
    void rejectsStripeCountsThatCannotBeRepresented() {
        assertThrows(IllegalArgumentException.class, () -> new StripedLocks(0));
        assertThrows(IllegalArgumentException.class, () -> new StripedLocks(-1));
        assertThrows(IllegalArgumentException.class, () -> new StripedLocks((1 << 30) + 1));
        assertThrows(IllegalArgumentException.class, () -> new StripedLocks(Integer.MAX_VALUE));
    }

    @Test
    void heldKeyExcludesAnotherThreadUntilRelease() throws Exception {
        StripedLocks locks = new StripedLocks(64);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ReentrantLock owner = locks.get("shared/file.txt");
        try {
            owner.lock();
            try {
                assertFalse(
                        pool.submit(() -> tryAcquire(locks.get("shared/file.txt")))
                                .get(5, TimeUnit.SECONDS));
            } finally {
                owner.unlock();
            }
            assertTrue(
                    pool.submit(() -> tryAcquire(locks.get("shared/file.txt")))
                            .get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void differentStripesCanProceedWhileAnotherIsHeld() throws Exception {
        StripedLocks locks = new StripedLocks(64);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ReentrantLock owner = locks.get(0);
        try {
            owner.lock();
            try {
                assertTrue(pool.submit(() -> tryAcquire(locks.get(1))).get(5, TimeUnit.SECONDS));
            } finally {
                owner.unlock();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void collidingKeysRemainReentrant() throws Exception {
        StripedLocks locks = new StripedLocks(64);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> operation =
                    pool.submit(
                            () -> {
                                // 两个不同字符串具有相同哈希值，验证碰撞时的嵌套加锁不会自锁。
                                ReentrantLock outer = locks.get("Aa");
                                ReentrantLock inner = locks.get("BB");
                                assertSame(outer, inner);
                                outer.lock();
                                try {
                                    assertTrue(inner.tryLock());
                                    try {
                                        assertEquals(2, outer.getHoldCount());
                                    } finally {
                                        inner.unlock();
                                    }
                                } finally {
                                    outer.unlock();
                                }
                            });
            operation.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean tryAcquire(ReentrantLock lock) {
        if (!lock.tryLock()) {
            return false;
        }
        try {
            return true;
        } finally {
            lock.unlock();
        }
    }
}
