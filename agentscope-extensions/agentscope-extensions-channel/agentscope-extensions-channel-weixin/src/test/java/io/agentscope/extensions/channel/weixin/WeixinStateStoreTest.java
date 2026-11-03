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
package io.agentscope.extensions.channel.weixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WeixinStateStoreTest {
    @Test
    void expiredClaimsAndLeasesCannotCompleteNewerWork() {
        AtomicLong time = new AtomicLong(1000);
        WeixinStateStore store =
                new InMemoryWeixinStateStore(
                        new Clock() {
                            public ZoneId getZone() {
                                return ZoneOffset.UTC;
                            }

                            public Clock withZone(ZoneId zone) {
                                return this;
                            }

                            public Instant instant() {
                                return Instant.ofEpochMilli(time.get());
                            }
                        });
        var lease = store.acquireLease("account", "holder", 1000).orElseThrow();
        store.acceptBatch(
                "account",
                lease,
                "cursor",
                List.of(
                        new WeixinInboxMessage("b", "payload"),
                        new WeixinInboxMessage("a", "payload")));
        var old = store.claimMessages("account", lease, 1, 50).get(0);
        assertEquals("b", old.messageId());
        assertTrue(store.claimMessages("account", lease, 1, 50).isEmpty());
        time.addAndGet(50);
        var next = store.claimMessages("account", lease, 1, 50).get(0);
        assertNotEquals(old.claimId(), next.claimId());
        assertFalse(store.completeMessage("account", lease, old));
        assertTrue(store.completeMessage("account", lease, next));
        assertEquals("a", store.claimMessages("account", lease, 1, 50).get(0).messageId());
        time.addAndGet(1000);
        assertFalse(store.renewLease("account", lease, 1000));
        var newLease = store.acquireLease("account", "holder", 1000).orElseThrow();
        assertTrue(newLease.generation() > lease.generation());
        assertFalse(store.acceptBatch("account", lease, "stale", List.of()));
        assertEquals("cursor", store.loadCursor("account"));
    }

    @Test
    void messageDiagnosticsDoNotExposePayloadOrContextToken() {
        assertFalse(
                new WeixinInboxMessage("id", "private-context")
                        .toString()
                        .contains("private-context"));
        assertFalse(
                new WeixinInboxClaim("id", "private-context", "claim")
                        .toString()
                        .contains("private-context"));
    }

    @Test
    void readOnlyLookupsDoNotRetainAccounts() {
        InMemoryWeixinStateStore store = new InMemoryWeixinStateStore();

        assertEquals("", store.loadCursor("ghost"));
        assertNull(store.loadContextToken("ghost", "peer"));
        assertFalse(store.isLeaseCurrent("ghost", null));
        store.releaseLease("ghost", null);

        assertEquals(0, store.retainedAccounts(), "validation must not materialise accounts");
    }

    @Test
    void keepsTheCursorWhenAnAccountGoesIdle() {
        AtomicLong time = new AtomicLong(1000);
        InMemoryWeixinStateStore store = new InMemoryWeixinStateStore(clockAt(time));
        WeixinLease active = store.acquireLease("active", "holder", 60_000).orElseThrow();
        WeixinLease idle = store.acquireLease("idle", "holder", 1000).orElseThrow();
        store.acceptBatch("idle", idle, "cursor-idle", List.of());

        time.addAndGet(2000);
        store.acceptBatch("active", active, "cursor-active", List.of());

        assertEquals(
                "cursor-idle",
                store.loadCursor("idle"),
                "the cursor is the consumer position and must survive idleness");
        assertEquals("cursor-active", store.loadCursor("active"));
    }

    @Test
    void forgetsAccountsThatNeverHeldState() {
        AtomicLong time = new AtomicLong(1000);
        InMemoryWeixinStateStore store = new InMemoryWeixinStateStore(clockAt(time));
        WeixinLease active = store.acquireLease("active", "holder", 60_000).orElseThrow();
        WeixinLease seen = store.acquireLease("seen-once", "holder", 1000).orElseThrow();
        store.releaseLease("seen-once", seen);
        assertEquals(2, store.retainedAccounts());

        time.addAndGet(2000);
        store.acceptBatch("active", active, "cursor-active", List.of());

        assertEquals(1, store.retainedAccounts(), "a stateless account must not be retained");
        assertEquals("", store.loadCursor("seen-once"));
    }

    @Test
    void abandonedMessagesAreTombstonedAndNeverClaimedAgain() {
        var store = WeixinStateStore.inMemory();
        var lease = store.acquireLease("account", "holder", 60_000).orElseThrow();
        store.acceptBatch(
                "account", lease, "cursor", List.of(new WeixinInboxMessage("gone", "payload")));
        var claim = store.claimMessages("account", lease, 1, 60_000).get(0);

        assertTrue(store.abandonMessage("account", lease, claim));
        // Not claimable again, and a provider re-delivery of the same batch cannot resurrect it.
        assertTrue(store.claimMessages("account", lease, 1, 60_000).isEmpty());
        store.acceptBatch(
                "account", lease, "cursor-2", List.of(new WeixinInboxMessage("gone", "payload")));
        assertTrue(store.claimMessages("account", lease, 1, 60_000).isEmpty());
    }

    @Test
    void removeAccountDropsTheCursor() {
        InMemoryWeixinStateStore store = new InMemoryWeixinStateStore();
        WeixinLease lease = store.acquireLease("retired", "holder", 60_000).orElseThrow();
        store.acceptBatch("retired", lease, "cursor", List.of());
        assertEquals("cursor", store.loadCursor("retired"));

        store.removeAccount("retired");

        assertEquals(0, store.retainedAccounts());
        assertEquals("", store.loadCursor("retired"));
    }

    @Test
    void keepsAccountsThatStillHoldPendingMessages() {
        AtomicLong time = new AtomicLong(1000);
        InMemoryWeixinStateStore store = new InMemoryWeixinStateStore(clockAt(time));
        WeixinLease active = store.acquireLease("active", "holder", 60_000).orElseThrow();
        WeixinLease pending = store.acquireLease("pending", "holder", 1000).orElseThrow();
        store.acceptBatch(
                "pending", pending, "cursor-pending", List.of(new WeixinInboxMessage("m1", "p")));

        time.addAndGet(2000);
        store.acceptBatch("active", active, "cursor-active", List.of());

        assertEquals("cursor-pending", store.loadCursor("pending"), "pending work must be kept");
        assertEquals(2, store.retainedAccounts());
    }

    @Test
    void releasedLeaseCannotBecomeValidAgainForTheSameHolder() {
        WeixinStateStore store = WeixinStateStore.inMemory();
        WeixinLease old = store.acquireLease("account", "holder", 60_000).orElseThrow();
        store.releaseLease("account", old);
        WeixinLease current = store.acquireLease("account", "holder", 60_000).orElseThrow();

        assertTrue(current.generation() > old.generation());
        assertFalse(store.renewLease("account", old, 60_000));
        assertFalse(store.acceptBatch("account", old, "lost", List.of()));
        assertEquals("", store.loadCursor("account"));
    }

    private static Clock clockAt(AtomicLong millis) {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(millis.get());
            }
        };
    }
}
