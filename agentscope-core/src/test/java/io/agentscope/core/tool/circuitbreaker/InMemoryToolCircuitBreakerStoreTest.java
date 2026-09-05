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
package io.agentscope.core.tool.circuitbreaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Contract of {@link InMemoryToolCircuitBreakerStore}, including its atomicity guarantees. */
class InMemoryToolCircuitBreakerStoreTest {

    private static final String TOOL = "query_weather";
    private static final String OTHER_TOOL = "query_news";

    private final InMemoryToolCircuitBreakerStore store = new InMemoryToolCircuitBreakerStore();

    @Test
    void unknownToolReadsAsClosedWithNoFailures() {
        assertEquals(0L, store.failureCount(TOOL));
        assertEquals(ToolCircuitSnapshot.CLOSED, store.snapshot(TOOL));
        assertFalse(store.snapshot(TOOL).isOpen());
    }

    @Test
    void failureCounterStartsAtOneAndIncrements() {
        assertEquals(1L, store.recordFailure(TOOL));
        assertEquals(2L, store.recordFailure(TOOL));
        assertEquals(2L, store.failureCount(TOOL));
    }

    @Test
    void resetClearsOnlyTheNamedToolsCounter() {
        store.recordFailure(TOOL);
        store.recordFailure(OTHER_TOOL);

        store.resetFailures(TOOL);

        assertEquals(0L, store.failureCount(TOOL));
        assertEquals(1L, store.failureCount(OTHER_TOOL));
    }

    @Test
    void openStampsTimestampAndAdvancesGeneration() {
        assertEquals(1L, store.open(TOOL, 1_000L));

        ToolCircuitSnapshot first = store.snapshot(TOOL);
        assertTrue(first.isOpen());
        assertEquals(1L, first.generation());
        assertEquals(1_000L, first.openedAtEpochMilli());

        assertEquals(2L, store.open(TOOL, 5_000L));

        ToolCircuitSnapshot second = store.snapshot(TOOL);
        assertEquals(2L, second.generation());
        assertEquals(5_000L, second.openedAtEpochMilli());
    }

    @Test
    void closeDiscardsGenerationSoBackoffRestarts() {
        store.open(TOOL, 1_000L);
        store.open(TOOL, 2_000L);

        store.close(TOOL);

        assertFalse(store.snapshot(TOOL).isOpen());
        assertEquals(0L, store.snapshot(TOOL).generation());
        assertEquals(1L, store.open(TOOL, 3_000L));
    }

    @Test
    void toolsDoNotShareState() {
        store.open(TOOL, 1_000L);

        assertTrue(store.snapshot(TOOL).isOpen());
        assertFalse(store.snapshot(OTHER_TOOL).isOpen());
    }

    @Test
    void concurrentFailureCountsAreNotLost() throws Exception {
        int threads = 8;
        int perThread = 500;

        runConcurrently(threads, perThread, () -> store.recordFailure(TOOL));

        assertEquals((long) threads * perThread, store.failureCount(TOOL));
    }

    @Test
    void concurrentOpensYieldContiguousGenerations() throws Exception {
        int threads = 8;
        int perThread = 200;
        AtomicLong maxGeneration = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(
                        () -> {
                            for (int i = 0; i < perThread; i++) {
                                long generation = store.open(TOOL, 1_000L + i);
                                maxGeneration.accumulateAndGet(generation, Math::max);
                            }
                        });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        // Every open must observe a distinct, gap-free generation, so the highest value seen equals
        // the number of opens performed.
        assertEquals((long) threads * perThread, maxGeneration.get());
        assertEquals((long) threads * perThread, store.snapshot(TOOL).generation());
    }

    private void runConcurrently(int threads, int perThread, Runnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(
                        () -> {
                            for (int i = 0; i < perThread; i++) {
                                task.run();
                            }
                        });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
