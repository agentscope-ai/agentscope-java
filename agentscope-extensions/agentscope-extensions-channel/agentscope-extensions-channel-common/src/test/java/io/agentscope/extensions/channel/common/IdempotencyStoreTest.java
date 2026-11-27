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
package io.agentscope.extensions.channel.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for the default in-process {@link IdempotencyStore} implementation. */
class IdempotencyStoreTest {

    @Test
    void firstObservationIsAllowedAndRedeliveryIsDropped() {
        IdempotencyStore store = new IdempotencyStore();
        assertTrue(store.firstSeen("ch|1"));
        assertFalse(store.firstSeen("ch|1"));
        assertTrue(store.firstSeen("ch|2"));
    }

    @Test
    void nullKeyIsAlwaysFirstSeen() {
        IdempotencyStore store = new IdempotencyStore();
        assertTrue(store.firstSeen(null));
        assertTrue(store.firstSeen(null));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void entriesExpireAfterTtl(int capacity) throws InterruptedException {
        IdempotencyStore store = new IdempotencyStore(80, capacity);
        assertTrue(store.firstSeen("ch|1"));
        assertFalse(store.firstSeen("ch|1"));
        Thread.sleep(160);
        assertTrue(store.firstSeen("ch|1"));
        // The accepted observation restarts the retention window: an immediate redelivery drops.
        assertFalse(store.firstSeen("ch|1"));
    }

    @Test
    void sizeIsBoundedByMaxEntries() {
        IdempotencyStore store = new IdempotencyStore(60_000, 3);
        assertTrue(store.firstSeen("a"));
        assertTrue(store.firstSeen("b"));
        assertTrue(store.firstSeen("c"));
        assertEquals(3, store.size());
        assertTrue(store.firstSeen("d"));
        assertEquals(3, store.size());
    }

    @Test
    void duplicateAtCapacityDoesNotEvictItsOwnEntry() {
        IdempotencyStore store = new IdempotencyStore(60_000, 1);
        assertTrue(store.firstSeen("a"));
        for (int i = 0; i < 10; i++) {
            assertFalse(store.firstSeen("a"));
        }
        assertEquals(1, store.size());
        assertTrue(store.firstSeen("b"));
        assertFalse(store.firstSeen("b"));
        assertEquals(1, store.size());
    }

    @Test
    void duplicateAtCapacityDoesNotEvictOtherRetainedEntries() {
        IdempotencyStore store = new IdempotencyStore(60_000, 3);
        assertTrue(store.firstSeen("a"));
        assertTrue(store.firstSeen("b"));
        assertTrue(store.firstSeen("c"));
        for (String key : List.of("c", "b", "a")) {
            assertFalse(store.firstSeen(key));
            assertEquals(3, store.size());
        }
    }

    @Test
    void concurrentFirstDeliveriesAtCapacityAdmitOneCaller() throws Exception {
        IdempotencyStore store = new IdempotencyStore(60_000, 1);
        var executor = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 20; round++) {
                String key = "event-" + round;
                CountDownLatch ready = new CountDownLatch(8);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    results.add(
                            executor.submit(
                                    () -> {
                                        ready.countDown();
                                        assertTrue(start.await(5, TimeUnit.SECONDS));
                                        return store.firstSeen(key);
                                    }));
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                int accepted = 0;
                for (Future<Boolean> result : results) {
                    if (result.get(5, TimeUnit.SECONDS)) {
                        accepted++;
                    }
                }
                assertEquals(1, accepted);
                assertEquals(1, store.size());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
