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

import org.junit.jupiter.api.Test;

/** Tests for {@link BotLoopGuard}, including idle-peer eviction. */
class BotLoopGuardTest {

    @Test
    void eventsWithinBudgetAreAllowed() {
        BotLoopGuard guard = new BotLoopGuard(3, 60_000L, 60_000L);
        assertTrue(guard.allow("peer-1"));
        assertTrue(guard.allow("peer-1"));
        assertTrue(guard.allow("peer-1"));
        assertEquals(1, guard.trackedPeers());
    }

    @Test
    void exceedingTheWindowTripsCooldown() {
        BotLoopGuard guard = new BotLoopGuard(2, 60_000L, 60_000L);
        assertTrue(guard.allow("peer-1"));
        assertTrue(guard.allow("peer-1"));
        assertFalse(guard.allow("peer-1"));
        assertTrue(guard.isCoolingDown("peer-1"));
        assertEquals(1, guard.trackedPeers());
    }

    @Test
    void nullAndBlankPeerKeysAreAllowed() {
        BotLoopGuard guard = new BotLoopGuard(1, 60_000L, 60_000L);
        assertTrue(guard.allow(null));
        assertTrue(guard.allow("  "));
        assertEquals(0, guard.trackedPeers());
    }

    @Test
    void cooldownStateIsNotEvictedWhileActive() {
        BotLoopGuard guard = new BotLoopGuard(1, 60_000L, 60_000L);
        assertTrue(guard.allow("peer-1"));
        assertFalse(guard.allow("peer-1")); // trips the cooldown
        assertEquals(0, guard.evictIdlePeers()); // idle threshold is 120s away
        assertTrue(guard.isCoolingDown("peer-1"));
    }

    @Test
    void idlePeersAreEvictedAfterWindowPlusCooldown() throws InterruptedException {
        BotLoopGuard guard = new BotLoopGuard(5, 80L, 80L); // idle threshold: 160ms
        assertTrue(guard.allow("peer-1"));
        Thread.sleep(60);
        assertEquals(0, guard.evictIdlePeers()); // not idle yet
        Thread.sleep(180);
        assertEquals(1, guard.evictIdlePeers());
        assertEquals(0, guard.trackedPeers());
        // After eviction the peer starts from a fresh state.
        assertTrue(guard.allow("peer-1"));
    }

    @Test
    void activePeersSurviveEvictionOfIdleOnes() throws InterruptedException {
        BotLoopGuard guard = new BotLoopGuard(5, 500L, 500L); // idle threshold: 1000ms
        assertTrue(guard.allow("idle-peer"));
        Thread.sleep(300);
        assertTrue(guard.allow("active-peer"));
        Thread.sleep(800); // idle-peer idle ~1100ms; active-peer idle ~800ms
        assertEquals(1, guard.evictIdlePeers());
        assertTrue(guard.allow("active-peer"));
        assertEquals(1, guard.trackedPeers());
    }

    @Test
    void sweepRunsOnTheFirstCallAfterTheSweepInterval() throws InterruptedException {
        BotLoopGuard guard =
                new BotLoopGuard(5, 80L, 80L); // sweep interval == idle threshold: 160ms
        assertTrue(guard.allow("stale-peer")); // first call stamps the sweep clock
        Thread.sleep(200);
        assertTrue(guard.allow("busy-peer")); // first call past the interval sweeps the idle peer
        assertEquals(1, guard.trackedPeers());
        assertFalse(guard.isCoolingDown("stale-peer"));
    }
}
