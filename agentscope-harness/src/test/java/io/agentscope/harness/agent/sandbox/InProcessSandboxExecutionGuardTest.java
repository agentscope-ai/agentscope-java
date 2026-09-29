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
package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Unit tests for {@link InProcessSandboxExecutionGuard}. */
class InProcessSandboxExecutionGuardTest {

    private static SandboxIsolationKey key(String sessionId) {
        Optional<SandboxIsolationKey> k =
                SandboxIsolationKey.resolve(
                        IsolationScope.SESSION,
                        RuntimeContext.builder().sessionId(sessionId).build(),
                        "agent");
        return k.orElseThrow();
    }

    @Test
    void defaultWaitIsBoundedAndUnboundedWaitRequiresExplicitNull() {
        assertEquals(
                InProcessSandboxExecutionGuard.DEFAULT_WAIT_TIMEOUT,
                new InProcessSandboxExecutionGuard().waitTimeout());
        assertNull(new InProcessSandboxExecutionGuard(null).waitTimeout());
        assertSame(
                SandboxExecutionGuard.defaultInProcess(), SandboxExecutionGuard.defaultInProcess());
    }

    @Test
    void distributedStoreKeepsItsNonNullGuardContract() {
        DistributedStore store =
                DistributedStore.builder()
                        .agentStateStore(new InMemoryAgentStateStore())
                        .baseStore(mock(BaseStore.class))
                        .build();
        assertSame(SandboxExecutionGuard.defaultInProcess(), store.sandboxExecutionGuard());
    }

    @Test
    @Timeout(5)
    void differentKeysDoNotBlockEachOther() throws Exception {
        InProcessSandboxExecutionGuard guard = new InProcessSandboxExecutionGuard();

        // Two distinct keys can both be held at once — no serialisation across keys.
        SandboxLease a = guard.tryEnter(key("s1"));
        SandboxLease b = guard.tryEnter(key("s2"));

        assertEquals(2, guard.trackedSlots(), "both keys are tracked while held");
        a.close();
        b.close();
        assertEquals(0, guard.trackedSlots(), "slots are recycled once all leases close");
    }

    @Test
    @Timeout(5)
    void sameKeySerialisesSecondEntryUntilFirstReleases() throws Exception {
        InProcessSandboxExecutionGuard guard = new InProcessSandboxExecutionGuard();

        SandboxLease first = guard.tryEnter(key("s1"));

        AtomicBoolean secondEntered = new AtomicBoolean(false);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);

        Thread t =
                new Thread(
                        () -> {
                            secondStarted.countDown();
                            try {
                                SandboxLease second = guard.tryEnter(key("s1"));
                                secondEntered.set(true);
                                second.close();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                secondDone.countDown();
                            }
                        });
        t.start();

        // The second entry must be blocked while the first lease is held.
        assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
        assertFalse(
                secondDone.await(300, TimeUnit.MILLISECONDS),
                "second entry must block while the first lease is held");
        assertFalse(secondEntered.get(), "second entry must not proceed before first releases");

        // Releasing the first lease lets the blocked entry proceed.
        first.close();
        assertTrue(
                secondDone.await(2, TimeUnit.SECONDS),
                "second entry must proceed once the first lease is released");
        assertTrue(secondEntered.get());

        t.join(1000);
        assertEquals(0, guard.trackedSlots(), "slot recycled after both entries complete");
    }

    @Test
    @Timeout(5)
    void leaseCloseIsIdempotent() throws Exception {
        InProcessSandboxExecutionGuard guard = new InProcessSandboxExecutionGuard();

        SandboxLease lease = guard.tryEnter(key("s1"));
        lease.close();
        // A double close must not over-release the permit nor double-decrement the ref count.
        lease.close();

        assertEquals(0, guard.trackedSlots());
        // The permit was released exactly once, so the key is immediately re-acquirable.
        SandboxLease again = guard.tryEnter(key("s1"));
        again.close();
        assertEquals(0, guard.trackedSlots());
    }

    @Test
    @Timeout(10)
    void hotKeyHasBoundedWaitersAndDoesNotLeakItsSlot() throws Exception {
        InProcessSandboxExecutionGuard guard = new InProcessSandboxExecutionGuard();
        SandboxIsolationKey key = key("bounded-queue");
        SandboxLease held = guard.tryEnter(key);
        List<Thread> waiters = new ArrayList<>();
        try {
            for (int i = 0; i < InProcessSandboxExecutionGuard.MAX_WAITERS_PER_KEY; i++) {
                Thread waiter =
                        new Thread(
                                () -> {
                                    try (SandboxLease ignored = guard.tryEnter(key)) {
                                        // Each queued caller releases its lease after entry.
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                });
                waiter.start();
                waiters.add(waiter);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (guard.waitingCalls(key) < InProcessSandboxExecutionGuard.MAX_WAITERS_PER_KEY
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(
                    InProcessSandboxExecutionGuard.MAX_WAITERS_PER_KEY, guard.waitingCalls(key));
            SandboxExecutionQueueFullException ex =
                    assertThrows(
                            SandboxExecutionQueueFullException.class, () -> guard.tryEnter(key));
            assertEquals(key, ex.getKey());
            assertEquals(InProcessSandboxExecutionGuard.MAX_WAITERS_PER_KEY, ex.getMaxWaiters());
            try (SandboxLease otherKey = guard.tryEnter(key("unrelated"))) {
                assertEquals(2, guard.trackedSlots());
            }
        } finally {
            held.close();
            for (Thread waiter : waiters) {
                waiter.join(3000);
            }
        }
        assertEquals(0, guard.trackedSlots());
    }

    @Test
    @Timeout(5)
    void timedOutWaiterDoesNotRecycleSlotWhileNewCallerRegisters() throws Exception {
        InProcessSandboxExecutionGuard guard =
                new InProcessSandboxExecutionGuard(Duration.ofMillis(150));
        SandboxIsolationKey key = key("recycle-race");
        SandboxLease held = guard.tryEnter(key);
        assertThrows(SandboxExecutionTimeoutException.class, () -> guard.tryEnter(key));

        AtomicBoolean entered = new AtomicBoolean();
        Thread newcomer =
                new Thread(
                        () -> {
                            try (SandboxLease ignored = guard.tryEnter(key)) {
                                entered.set(true);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
        newcomer.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (guard.waitingCalls(key) == 0 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(1, guard.waitingCalls(key));
            assertFalse(entered.get(), "new caller must still wait for the original holder");
        } finally {
            held.close();
            newcomer.join(1000);
        }
        assertTrue(entered.get());
        assertEquals(0, guard.trackedSlots());
    }

    @Test
    @Timeout(5)
    void waitTimeoutThrowsWhenSlotStaysBusyAndRecyclesTheSlot() throws Exception {
        InProcessSandboxExecutionGuard guard =
                new InProcessSandboxExecutionGuard(Duration.ofMillis(100));

        // Hold the slot so the next entry has to wait past its timeout.
        SandboxLease held = guard.tryEnter(key("s1"));

        SandboxExecutionTimeoutException ex =
                assertThrows(
                        SandboxExecutionTimeoutException.class, () -> guard.tryEnter(key("s1")));
        assertEquals(key("s1"), ex.getKey());
        assertEquals(Duration.ofMillis(100), ex.getWaited());
        assertTrue(ex.getMessage().contains("other waiting calls:"));

        // The timed-out entry must have dropped its reference — only the holder keeps the slot.
        assertEquals(1, guard.trackedSlots(), "timed-out waiter must not leak a slot reference");

        held.close();
        assertEquals(0, guard.trackedSlots(), "slot recycled once the holder releases");
    }

    @Test
    @Timeout(5)
    void rejectsNonPositiveWaitTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new InProcessSandboxExecutionGuard(Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InProcessSandboxExecutionGuard(Duration.ofMillis(-1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InProcessSandboxExecutionGuard(Duration.ofSeconds(1), 0));
    }

    @Test
    @Timeout(5)
    void leaseAcquiredOnOneThreadCanBeClosedOnAnother() throws Exception {
        // The harness acquires in a reactive resource supplier and releases in the cleanup, which
        // may run on a different thread — a Semaphore (not a thread-owned lock) makes this legal.
        InProcessSandboxExecutionGuard guard = new InProcessSandboxExecutionGuard();
        SandboxLease lease = guard.tryEnter(key("s1"));

        Thread closer = new Thread(lease::close);
        closer.start();
        closer.join(1000);

        assertEquals(0, guard.trackedSlots(), "cross-thread close must release the slot");
        // Slot is free again for a fresh entry.
        SandboxLease again = guard.tryEnter(key("s1"));
        again.close();
    }
}
