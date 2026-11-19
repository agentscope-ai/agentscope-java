/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.agentprotocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Lease, fencing and cancellation semantics of {@link BaseStoreProtocolTaskRunRegistry}. */
class BaseStoreProtocolTaskRunRegistryTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final BaseStoreProtocolTaskRunRegistry registry =
            new BaseStoreProtocolTaskRunRegistry(new InMemoryStore(), "node-a", clock);

    @Test
    void leaseIsExclusiveUntilReleased() {
        ProtocolTaskLease lease = registry.tryAcquire("t1", TTL).orElseThrow();

        assertTrue(registry.find("t1").isPresent());
        assertTrue(registry.tryAcquire("t1", TTL).isEmpty());

        registry.release(lease);

        assertTrue(registry.find("t1").isEmpty());
        assertTrue(registry.tryAcquire("t1", TTL).isPresent());
    }

    @Test
    void expiredLeaseCanBeReclaimedAndStaleReleaseIsFenced() {
        ProtocolTaskLease stale = registry.tryAcquire("t1", TTL).orElseThrow();

        clock.advance(TTL.plusSeconds(1));

        assertTrue(registry.find("t1").isEmpty());
        ProtocolTaskLease fresh = registry.tryAcquire("t1", TTL).orElseThrow();

        // The previous owner unwinding late must not drop the new owner's lease.
        registry.release(stale);

        assertEquals(fresh.leaseId(), registry.find("t1").orElseThrow().leaseId());
    }

    @Test
    void renewExtendsLeaseForTheCurrentHolderOnly() {
        ProtocolTaskLease lease = registry.tryAcquire("t1", TTL).orElseThrow();

        clock.advance(Duration.ofSeconds(20));
        assertTrue(registry.renew(lease, TTL));
        clock.advance(Duration.ofSeconds(20));

        // 40s after acquisition, but renewed at 20s for another 30s.
        assertTrue(registry.find("t1").isPresent());
        assertFalse(
                registry.renew(new ProtocolTaskLease("t1", "node-a", "other", Instant.now()), TTL));
    }

    @Test
    void instanceIdentityIsDerivedPerRegistry() {
        BaseStoreProtocolTaskRunRegistry first = BaseStoreProtocolTaskRunRegistry.inMemory();
        BaseStoreProtocolTaskRunRegistry second = BaseStoreProtocolTaskRunRegistry.inMemory();

        assertFalse(first.ownerId().isBlank());
        assertTrue(
                first.ownerId().contains(":"),
                "holder ids must be host:pid:random so a stored lease names its instance, got "
                        + first.ownerId());
        assertNotEquals(
                first.ownerId(),
                second.ownerId(),
                "two registries must not report the same holder id");
    }

    @Test
    void sharingOneStoreMakesOwnershipVisibleToEveryInstance() {
        InMemoryStore shared = new InMemoryStore();
        BaseStoreProtocolTaskRunRegistry nodeA =
                new BaseStoreProtocolTaskRunRegistry(shared, "node-a", clock);
        BaseStoreProtocolTaskRunRegistry nodeB =
                new BaseStoreProtocolTaskRunRegistry(shared, "node-b", clock);

        ProtocolTaskLease lease = nodeA.tryAcquire("t1", TTL).orElseThrow();

        assertTrue(
                nodeB.tryAcquire("t1", TTL).isEmpty(),
                "a lease held through the shared store must exclude the other instance");
        assertTrue(nodeB.find("t1").isPresent(), "the other instance must see the live lease");

        nodeA.release(lease);

        assertTrue(
                nodeB.tryAcquire("t1", TTL).isPresent(),
                "a release must free the lease for every instance");
    }

    @Test
    void losingTheCreateRaceDoesNotOverwriteTheWinner() {
        // The first read misses, and a rival lease appears before the write: a store with lazy
        // consistency would let the slower writer clobber it.
        StaleReadStore store = new StaleReadStore();
        BaseStoreProtocolTaskRunRegistry registry =
                new BaseStoreProtocolTaskRunRegistry(store, "node-a", clock);

        assertTrue(
                registry.tryAcquire("t1", TTL).isEmpty(),
                "an acquisition that lost the create race must not report success");
        assertEquals("node-b", registry.find("t1").orElseThrow().ownerId());
    }

    @Test
    void withoutCompareAndSetTheStoredValueDecides() {
        // A store without compare-and-set cannot fence writers, so the acquisition is decided by
        // reading back what actually landed.
        OverwritingStore store = new OverwritingStore();
        BaseStoreProtocolTaskRunRegistry registry =
                new BaseStoreProtocolTaskRunRegistry(store, "node-a", clock);

        assertTrue(
                registry.tryAcquire("t1", TTL).isEmpty(),
                "a lost write must be detected by the read-back, not assumed successful");
        assertEquals("node-b", registry.find("t1").orElseThrow().ownerId());
    }

    @Test
    void requestCancelSignalsTheBoundHandler() {
        AtomicInteger fired = new AtomicInteger();
        registry.tryAcquire("t1", TTL).orElseThrow();
        registry.bindCancelHandler("t1", fired::incrementAndGet);

        assertTrue(registry.requestCancel("t1"));

        assertEquals(1, fired.get());
        assertTrue(registry.isCancelled("t1"));
    }

    @Test
    void requestCancelWithNothingRunningIsIgnored() {
        assertFalse(registry.requestCancel("missing"));
        assertFalse(registry.isCancelled("missing"));
    }

    @Test
    void newAcquisitionClearsCancelIntentFromAPreviousHolder() {
        registry.bindCancelHandler("t1", () -> {});
        registry.tryAcquire("t1", TTL).orElseThrow();
        registry.requestCancel("t1");
        assertTrue(registry.isCancelled("t1"));

        clock.advance(TTL.plusSeconds(1));
        registry.tryAcquire("t1", TTL).orElseThrow();

        assertFalse(registry.isCancelled("t1"));
    }

    /** A live lease owned by another instance, as it would arrive from a concurrent acquisition. */
    private static void writeRivalLease(List<String> namespace, String key, BaseStore store) {
        Map<String, Object> rival = new LinkedHashMap<>();
        rival.put("owner_id", "node-b");
        rival.put("lease_id", "rival");
        rival.put("expires_at", Instant.parse("2030-01-01T00:00:00Z").toEpochMilli());
        store.put(namespace, key, rival);
    }

    /** Reports the first read as a miss after another instance has already created the entry. */
    private static final class StaleReadStore implements BaseStore {

        private final BaseStore delegate = new InMemoryStore();
        private boolean raced;

        @Override
        public boolean supportsAtomicJournalStorage() {
            return true;
        }

        @Override
        public StoreItem get(List<String> namespace, String key) {
            if (!raced) {
                raced = true;
                writeRivalLease(namespace, key, delegate);
                return null;
            }
            return delegate.get(namespace, key);
        }

        @Override
        public void put(List<String> namespace, String key, Map<String, Object> value) {
            delegate.put(namespace, key, value);
        }

        @Override
        public boolean putIfVersion(
                List<String> namespace,
                String key,
                Map<String, Object> value,
                long expectedVersion) {
            return delegate.putIfVersion(namespace, key, value, expectedVersion);
        }

        @Override
        public List<StoreItem> search(List<String> namespace, int limit, int offset) {
            return delegate.search(namespace, limit, offset);
        }

        @Override
        public void delete(List<String> namespace, String key) {
            delegate.delete(namespace, key);
        }
    }

    /** Drops the writer's value once, as a store without compare-and-set effectively can. */
    private static final class OverwritingStore implements BaseStore {

        private final BaseStore delegate = new InMemoryStore();
        private boolean hijacked;

        @Override
        public boolean supportsAtomicJournalStorage() {
            return false;
        }

        @Override
        public StoreItem get(List<String> namespace, String key) {
            return delegate.get(namespace, key);
        }

        @Override
        public void put(List<String> namespace, String key, Map<String, Object> value) {
            if (!hijacked) {
                hijacked = true;
                writeRivalLease(namespace, key, delegate);
                return;
            }
            delegate.put(namespace, key, value);
        }

        @Override
        public boolean putIfVersion(
                List<String> namespace,
                String key,
                Map<String, Object> value,
                long expectedVersion) {
            return delegate.putIfVersion(namespace, key, value, expectedVersion);
        }

        @Override
        public List<StoreItem> search(List<String> namespace, int limit, int offset) {
            return delegate.search(namespace, limit, offset);
        }

        @Override
        public void delete(List<String> namespace, String key) {
            delegate.delete(namespace, key);
        }
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

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
            return now;
        }
    }
}
