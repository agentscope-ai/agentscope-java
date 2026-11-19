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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Wiring semantics of {@link ProtocolStores}: one store, three collaborators, two instances. */
class ProtocolStoresTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    private final InMemoryStore store = new InMemoryStore();

    @Test
    void aStoreWithoutCompareAndSetIsRejectedInsteadOfWiringUnsafeCollaborators() {
        BaseStore nonAtomic = new NonAtomicStore();

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> ProtocolStores.on(nonAtomic));
        assertTrue(
                thrown.getMessage().contains("compare-and-set"),
                "the failure must name the missing capability: " + thrown.getMessage());
        assertTrue(
                thrown.getMessage().contains("single-instance"),
                "the failure must point at the single-instance alternative: "
                        + thrown.getMessage());
    }

    /** An object-storage-like store: writes succeed, but nothing fences two writers. */
    private static final class NonAtomicStore implements BaseStore {

        private final BaseStore delegate = new InMemoryStore();

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
            delegate.put(namespace, key, value);
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

    @Test
    void collaboratorsAreStablePerWiring() {
        ProtocolStores stores = ProtocolStores.on(store);

        assertSame(stores.runRegistry(), stores.runRegistry());
        assertSame(stores.contextStore(), stores.contextStore());
        assertSame(stores.sessionRegistry(), stores.sessionRegistry());
    }

    @Test
    void everyCollaboratorSharesTheStoreAcrossInstances() {
        ProtocolStores nodeA = ProtocolStores.on(store);
        ProtocolStores nodeB = ProtocolStores.on(store);

        ProtocolTaskLease lease = nodeA.runRegistry().tryAcquire("t1", TTL).orElseThrow();
        assertTrue(
                nodeB.runRegistry().tryAcquire("t1", TTL).isEmpty(),
                "a second instance must not acquire a lease held through the same store");
        String owner = nodeB.runRegistry().find("t1").orElseThrow().ownerId();
        assertTrue(
                owner != null && owner.contains(":"),
                "the lease must name the instance holding it, got " + owner);
        nodeA.runRegistry().release(lease);
        assertTrue(nodeB.runRegistry().tryAcquire("t1", TTL).isPresent());

        nodeA.contextStore()
                .save(
                        "t1",
                        ProtocolTaskContext.from(
                                Map.of("user_id", "u1", "session_id", "conv-1", "detail", "full")));
        ProtocolTaskContext seen = nodeB.contextStore().find("t1").orElseThrow();
        assertEquals("u1", seen.userId());
        assertEquals("full", seen.detail());
        assertEquals("conv-1", seen.sessionId());
        nodeB.contextStore().clear("t1");
        assertTrue(nodeA.contextStore().find("t1").isEmpty());

        nodeA.sessionRegistry().registerOrVerify("conv-1", "u1", "worker", "planner");
        assertThrows(
                IllegalArgumentException.class,
                () -> nodeB.sessionRegistry().registerOrVerify("conv-1", "u2", "worker", "planner"),
                "ownership registered through the store must be enforced on every instance");
    }
}
