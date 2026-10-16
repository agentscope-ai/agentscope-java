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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Compare-and-set contract of {@link InMemoryToolCircuitBreakerStore}. */
class InMemoryToolCircuitBreakerStoreTest {

    private static final String TOOL = "query_weather";
    private static final String OTHER_TOOL = "query_news";

    private final InMemoryToolCircuitBreakerStore store = new InMemoryToolCircuitBreakerStore();

    @Test
    void unknownToolReadsAsClosed() {
        assertEquals(ToolCircuitSnapshot.CLOSED, store.snapshot(TOOL));
        assertFalse(store.snapshot(TOOL).isOpen());
    }

    @Test
    void compareAndSetCommitsWhenTheObservedValueStillHolds() {
        ToolCircuitSnapshot update = new ToolCircuitSnapshot(2L, 0L, 0L, null, 0L);

        assertTrue(store.compareAndSet(TOOL, ToolCircuitSnapshot.CLOSED, update));

        assertEquals(update, store.snapshot(TOOL));
    }

    @Test
    void compareAndSetRejectsAStaleExpectation() {
        ToolCircuitSnapshot first = new ToolCircuitSnapshot(1L, 0L, 0L, null, 0L);
        store.compareAndSet(TOOL, ToolCircuitSnapshot.CLOSED, first);

        // A caller that still believes the tool is untouched must not be able to commit.
        boolean committed =
                store.compareAndSet(
                        TOOL,
                        ToolCircuitSnapshot.CLOSED,
                        new ToolCircuitSnapshot(9L, 0L, 0L, null, 0L));

        assertFalse(committed);
        assertEquals(first, store.snapshot(TOOL));
    }

    @Test
    void updatingToClosedRemovesTheEntry() {
        store.compareAndSet(TOOL, ToolCircuitSnapshot.CLOSED, new ToolCircuitSnapshot(3L, 1_000L));

        assertTrue(store.compareAndSet(TOOL, store.snapshot(TOOL), ToolCircuitSnapshot.CLOSED));

        assertEquals(ToolCircuitSnapshot.CLOSED, store.snapshot(TOOL));
        // An absent entry must still be a valid expectation, proving it was removed rather than
        // stored as an explicit zero value.
        assertTrue(
                store.compareAndSet(
                        TOOL,
                        ToolCircuitSnapshot.CLOSED,
                        new ToolCircuitSnapshot(1L, 0L, 0L, null, 0L)));
    }

    @Test
    void probeClaimIsPartOfTheComparedValue() {
        ToolCircuitSnapshot open = new ToolCircuitSnapshot(4L, 1_000L);
        store.compareAndSet(TOOL, ToolCircuitSnapshot.CLOSED, open);
        ToolCircuitSnapshot claimed = new ToolCircuitSnapshot(0L, 4L, 1_000L, "token-a", 9_000L);
        assertTrue(store.compareAndSet(TOOL, open, claimed));

        // Another caller holding the pre-claim value must lose, which is what makes the single
        // recovery probe exclusive.
        assertFalse(
                store.compareAndSet(
                        TOOL, open, new ToolCircuitSnapshot(0L, 4L, 1_000L, "token-b", 9_000L)));
        assertEquals("token-a", store.snapshot(TOOL).probeToken());
    }

    @Test
    void resetDiscardsStateUnconditionally() {
        store.compareAndSet(TOOL, ToolCircuitSnapshot.CLOSED, new ToolCircuitSnapshot(2L, 1_000L));

        store.reset(TOOL);

        assertEquals(ToolCircuitSnapshot.CLOSED, store.snapshot(TOOL));
    }

    @Test
    void toolsDoNotShareState() {
        store.compareAndSet(TOOL, ToolCircuitSnapshot.CLOSED, new ToolCircuitSnapshot(1L, 1_000L));

        assertTrue(store.snapshot(TOOL).isOpen());
        assertFalse(store.snapshot(OTHER_TOOL).isOpen());
    }

    @Test
    void exactlyOneOfManyConcurrentCompareAndSetsWins() throws Exception {
        int threads = 16;
        ToolCircuitSnapshot expected = ToolCircuitSnapshot.CLOSED;
        AtomicInteger winners = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                long generation = i + 1L;
                pool.submit(
                        () -> {
                            if (store.compareAndSet(
                                    TOOL, expected, new ToolCircuitSnapshot(generation, 1_000L))) {
                                winners.incrementAndGet();
                            }
                        });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, winners.get());
    }
}
