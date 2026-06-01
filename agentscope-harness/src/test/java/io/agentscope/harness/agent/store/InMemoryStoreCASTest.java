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
package io.agentscope.harness.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * CAS semantics for {@link InMemoryStore#putIfVersion}: create-if-absent, version bump on success,
 * mismatch on stale version, and atomicity under concurrent writers.
 */
class InMemoryStoreCASTest {

    private static final List<String> NS = List.of("test");

    @Test
    void putIfVersionZero_succeedsWhenAbsent_failsWhenPresent() {
        InMemoryStore store = new InMemoryStore();

        assertTrue(store.putIfVersion(NS, "k", Map.of("v", 1), 0L));
        StoreItem first = store.get(NS, "k");
        assertNotNull(first);
        assertEquals(1L, first.version());

        // Second create attempt must fail — key exists.
        assertFalse(store.putIfVersion(NS, "k", Map.of("v", 2), 0L));
        assertEquals(1, store.get(NS, "k").value().get("v"));
    }

    @Test
    void putIfVersion_bumpsVersionOnSuccess() {
        InMemoryStore store = new InMemoryStore();
        store.put(NS, "k", Map.of("v", 1));
        StoreItem v1 = store.get(NS, "k");
        assertEquals(1L, v1.version());

        assertTrue(store.putIfVersion(NS, "k", Map.of("v", 2), v1.version()));
        assertEquals(2L, store.get(NS, "k").version());

        // Re-using the stale version must fail.
        assertFalse(store.putIfVersion(NS, "k", Map.of("v", 3), v1.version()));
        assertEquals(2, store.get(NS, "k").value().get("v"));
    }

    @Test
    void putIfVersion_isAtomicUnderConcurrentWriters() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.put(NS, "counter", Map.of("n", 0));

        int writers = 16;
        int incrementsPerWriter = 50;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger retries = new AtomicInteger();

        for (int i = 0; i < writers; i++) {
            pool.submit(
                    () -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        for (int j = 0; j < incrementsPerWriter; j++) {
                            while (true) {
                                StoreItem cur = store.get(NS, "counter");
                                int n = (int) cur.value().get("n");
                                if (store.putIfVersion(
                                        NS, "counter", Map.of("n", n + 1), cur.version())) {
                                    break;
                                }
                                retries.incrementAndGet();
                            }
                        }
                    });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        StoreItem finalItem = store.get(NS, "counter");
        // No lost updates — every successful CAS contributed exactly +1.
        assertEquals(writers * incrementsPerWriter, finalItem.value().get("n"));
        // Version increments once per successful CAS plus the initial put.
        assertEquals(writers * incrementsPerWriter + 1L, finalItem.version());
    }

    @Test
    void putIfVersionZero_onMissingKeyAfterDelete_succeeds() {
        InMemoryStore store = new InMemoryStore();
        store.put(NS, "k", Map.of("v", 1));
        store.delete(NS, "k");
        assertNull(store.get(NS, "k"));

        assertTrue(store.putIfVersion(NS, "k", Map.of("v", 99), 0L));
        assertEquals(1L, store.get(NS, "k").version());
    }
}
