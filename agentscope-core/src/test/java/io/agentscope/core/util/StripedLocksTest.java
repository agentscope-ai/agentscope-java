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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class StripedLocksTest {

    @Test
    void sameKeyAlwaysMapsToSameLock() {
        StripedLocks locks = new StripedLocks(64);
        assertSame(locks.get("workspace/a.txt"), locks.get("workspace/a.txt"));
        assertSame(locks.get(""), locks.get(""));
    }

    @Test
    void memoryFootprintStaysBoundedAcrossManyDistinctKeys() {
        StripedLocks locks = new StripedLocks(64);
        Set<ReentrantLock> distinct = new HashSet<>();
        // Simulate an agent runtime producing an unbounded stream of model-chosen paths:
        // regardless of how many distinct keys are locked, only `size()` lock objects exist.
        for (int i = 0; i < 100_000; i++) {
            distinct.add(locks.get("workspace/dir-" + i + "/file-" + i + ".md"));
        }
        assertEquals(64, locks.size());
        assertTrue(distinct.size() <= locks.size());
    }

    @Test
    void stripeCountIsRoundedUpToPowerOfTwo() {
        assertEquals(1, new StripedLocks(1).size());
        assertEquals(64, new StripedLocks(33).size());
        assertEquals(64, new StripedLocks(64).size());
        assertEquals(128, new StripedLocks(65).size());
    }

    @Test
    void rejectsNonPositiveStripeCount() {
        assertThrows(IllegalArgumentException.class, () -> new StripedLocks(0));
        assertThrows(IllegalArgumentException.class, () -> new StripedLocks(-8));
    }

    @Test
    void mutualExclusionPerKeyIsPreservedUnderConcurrency() throws Exception {
        StripedLocks locks = new StripedLocks(16);
        int threads = 8;
        int incrementsPerThread = 5_000;
        AtomicInteger unsafeCounter = new AtomicInteger();
        int[] plainCounter = new int[1];

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(
                    () -> {
                        try {
                            start.await();
                            for (int i = 0; i < incrementsPerThread; i++) {
                                ReentrantLock lock = locks.get("shared/file.txt");
                                lock.lock();
                                try {
                                    plainCounter[0]++;
                                } finally {
                                    lock.unlock();
                                }
                                unsafeCounter.incrementAndGet();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish in time");
        pool.shutdownNow();

        assertEquals(threads * incrementsPerThread, unsafeCounter.get());
        // If two threads ever held the "same key" lock simultaneously, the unsynchronized
        // increment would lose updates and this assertion would fail.
        assertEquals(threads * incrementsPerThread, plainCounter[0]);
    }
}
