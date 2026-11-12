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
package io.agentscope.harness.agent.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.session.SessionLogException;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StoreSessionStorageTest {
    private static final List<String> SESSION_NAMESPACE =
            List.of("tenant", "__agentscope_session_log_v1__");

    @Test
    void readAllMatchesIndividualReadsForPresentMissingAndDuplicatePaths() {
        var store = new InMemoryStore();
        var storage = new StoreSessionStorage(store, List.of("tenant"));
        put(store, "commits/first.json", new byte[] {1, 2, 3});
        put(store, "commits/second.json", new byte[] {4, 5});
        put(store, "other/ignored.json", new byte[] {9});
        assertTrue(
                store.putIfVersion(
                        SESSION_NAMESPACE,
                        "commits/first.json",
                        Map.of("bytes", encode(new byte[] {6, 7})),
                        1));

        var paths =
                List.of(
                        "commits/first.json",
                        "commits/missing.json",
                        "commits/second.json",
                        "commits/first.json");

        var batch = storage.readAll(paths);

        assertEquals(Set.of("commits/first.json", "commits/second.json"), batch.keySet());
        for (String path : batch.keySet()) {
            var single = storage.read(path);
            assertEquals(single.version(), batch.get(path).version(), path);
            assertArrayEquals(single.bytes(), batch.get(path).bytes(), path);
        }
        assertEquals(2, batch.get("commits/first.json").version());
        assertFalse(batch.containsKey("commits/missing.json"));
        assertFalse(batch.containsKey("other/ignored.json"));
    }

    @Test
    void readAllUsesOneBulkReadForLargeBatches() {
        var store = new CountingStore();
        var storage = new StoreSessionStorage(store, List.of("tenant"));
        var paths = new ArrayList<String>();
        for (int i = 0; i < 300; i++) {
            String path = "commits/segment-" + i + ".json";
            put(store, path, new byte[] {(byte) i});
            paths.add(path);
        }

        var batch = storage.readAll(paths);

        assertEquals(300, batch.size());
        assertEquals(1, store.getAllCalls);
        assertEquals(300, store.requestedKeys);
        assertEquals(0, store.gets);
        assertArrayEquals(new byte[] {(byte) 299}, batch.get("commits/segment-299.json").bytes());
    }

    @Test
    void readAllUsesOneGetForASinglePath() {
        var store = new SearchFallbackStore();
        var storage = new StoreSessionStorage(store, List.of("tenant"));
        put(store, "commits/first.json", new byte[] {1});

        var batch = storage.readAll(List.of("commits/first.json"));

        assertArrayEquals(new byte[] {1}, batch.get("commits/first.json").bytes());
        assertEquals(1, store.gets);
        assertEquals(0, store.searches);
    }

    @Test
    void readAllUsesPagedSearchWhenBulkReadIsUnsupported() {
        var store = new SearchFallbackStore();
        var storage = new StoreSessionStorage(store, List.of("tenant"));
        put(store, "commits/first.json", new byte[] {1});
        put(store, "commits/second.json", new byte[] {2});

        var batch =
                storage.readAll(
                        List.of(
                                "commits/first.json",
                                "commits/second.json",
                                "commits/absent.json"));

        assertEquals(Set.of("commits/first.json", "commits/second.json"), batch.keySet());
        assertArrayEquals(new byte[] {1}, batch.get("commits/first.json").bytes());
        assertArrayEquals(new byte[] {2}, batch.get("commits/second.json").bytes());
        assertEquals(2, store.searches);
        assertEquals(1, store.gets);
    }

    @Test
    void readAllFallsBackToPerKeyGetsWhenSearchIsUnsupported() {
        var store = new DelegatingStore();
        var storage = new StoreSessionStorage(store, List.of("tenant"));
        put(store, "commits/first.json", new byte[] {1});
        put(store, "commits/second.json", new byte[] {2});

        var batch =
                storage.readAll(
                        List.of(
                                "commits/first.json",
                                "commits/second.json",
                                "commits/absent.json",
                                "commits/first.json"));

        assertEquals(Set.of("commits/first.json", "commits/second.json"), batch.keySet());
        assertArrayEquals(new byte[] {1}, batch.get("commits/first.json").bytes());
        assertArrayEquals(new byte[] {2}, batch.get("commits/second.json").bytes());
        // The backend has no bulk read and no search, so every path is read individually.
        assertEquals(3, store.gets);
    }

    @Test
    void readAllRejectsUnversionedObjectsLikeRead() {
        var store = new UnversionedStore();
        var storage = new StoreSessionStorage(store, List.of("tenant"));
        put(store, "commits/first.json", new byte[] {1});
        put(store, "commits/second.json", new byte[] {2});

        assertThrows(
                SessionLogException.class,
                () -> storage.readAll(List.of("commits/first.json", "commits/second.json")));
    }

    private static void put(BaseStore store, String path, byte[] bytes) {
        store.put(SESSION_NAMESPACE, path, Map.of("bytes", encode(bytes)));
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static final class CountingStore extends InMemoryStore {
        int getAllCalls;
        int requestedKeys;
        int gets;

        @Override
        public Map<String, StoreItem> getAll(List<String> namespace, Collection<String> keys) {
            getAllCalls++;
            requestedKeys += keys.size();
            return super.getAll(namespace, keys);
        }

        @Override
        public StoreItem get(List<String> namespace, String key) {
            gets++;
            return super.get(namespace, key);
        }
    }

    /** A store with efficient search but no bulk read, such as the control-plane store. */
    private static final class SearchFallbackStore implements BaseStore {
        private final InMemoryStore delegate = new InMemoryStore();
        int searches;
        int gets;

        @Override
        public StoreItem get(List<String> namespace, String key) {
            gets++;
            return delegate.get(namespace, key);
        }

        @Override
        public void put(List<String> namespace, String key, Map<String, Object> value) {
            delegate.put(namespace, key, value);
        }

        @Override
        public boolean supportsAtomicSessionStorage() {
            return true;
        }

        @Override
        public boolean putIfVersion(
                List<String> namespace, String key, Map<String, Object> value, long expected) {
            return delegate.putIfVersion(namespace, key, value, expected);
        }

        @Override
        public List<StoreItem> search(List<String> namespace, int limit, int offset) {
            searches++;
            return delegate.search(namespace, limit, offset);
        }

        @Override
        public void delete(List<String> namespace, String key) {
            delegate.delete(namespace, key);
        }
    }

    /** A store that only implements the required methods and has no search support. */
    private static final class DelegatingStore implements BaseStore {
        private final InMemoryStore delegate = new InMemoryStore();
        int gets;

        @Override
        public StoreItem get(List<String> namespace, String key) {
            gets++;
            return delegate.get(namespace, key);
        }

        @Override
        public void put(List<String> namespace, String key, Map<String, Object> value) {
            delegate.put(namespace, key, value);
        }

        @Override
        public boolean supportsAtomicSessionStorage() {
            return true;
        }

        @Override
        public boolean putIfVersion(
                List<String> namespace, String key, Map<String, Object> value, long expected) {
            return delegate.putIfVersion(namespace, key, value, expected);
        }

        @Override
        public List<StoreItem> search(List<String> namespace, int limit, int offset) {
            throw new UnsupportedOperationException("search");
        }

        @Override
        public void delete(List<String> namespace, String key) {
            delegate.delete(namespace, key);
        }
    }

    private static final class UnversionedStore extends InMemoryStore {
        @Override
        public StoreItem get(List<String> namespace, String key) {
            return unversioned(super.get(namespace, key));
        }

        @Override
        public Map<String, StoreItem> getAll(List<String> namespace, Collection<String> keys) {
            var items = new HashMap<String, StoreItem>();
            for (var entry : super.getAll(namespace, keys).entrySet())
                items.put(entry.getKey(), unversioned(entry.getValue()));
            return items;
        }

        private static StoreItem unversioned(StoreItem item) {
            return item == null ? null : new StoreItem(item.key(), item.value(), 0);
        }
    }
}
