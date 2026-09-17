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
}
