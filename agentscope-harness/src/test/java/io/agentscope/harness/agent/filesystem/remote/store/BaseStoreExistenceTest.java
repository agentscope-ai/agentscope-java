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
package io.agentscope.harness.agent.filesystem.remote.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BaseStoreExistenceTest {

    private static final List<String> NS = List.of("workspace");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactKeysAndRecursivePrefixesRemainDistinct(boolean fallback) {
        BaseStore store = store(fallback);
        store.put(NS, "/file", Map.of());
        store.put(List.of("workspace", "child"), "/nested/file", Map.of());
        store.put(List.of("workspace-other"), "/sibling/file", Map.of());

        assertTrue(store.containsKey(NS, "/file"));
        assertFalse(store.containsKey(NS, "/nested/file"));
        assertFalse(store.containsKey(List.of("work"), "/file"));
        assertTrue(store.hasKeyPrefix(NS, "/nested/"));
        assertFalse(store.hasKeyPrefix(NS, "/sibling/"));
        assertTrue(store.hasKeyPrefix(NS, ""));
        assertFalse(store.hasKeyPrefix(List.of("missing"), ""));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keyPrefixesAreLiteralAndCaseSensitive(boolean fallback) {
        BaseStore store = store(fallback);
        store.put(NS, "/a%_!/file", Map.of());
        assertTrue(store.hasKeyPrefix(NS, "/a%_!/"));
        assertFalse(store.hasKeyPrefix(NS, "/A%_!/"));
        assertFalse(store.hasKeyPrefix(NS, "/aXX/"));
        assertThrows(NullPointerException.class, () -> store.hasKeyPrefix(NS, null));
    }

    @Test
    void defaultStopsAfterTheFirstMatchingPage() {
        LegacyStore store = populatedStore(250);
        assertTrue(store.hasKeyPrefix(NS, "/file000"));
        assertEquals(List.of(0), store.offsets);
    }

    @Test
    void defaultCanMatchALaterPage() {
        LegacyStore store = populatedStore(250);
        assertTrue(store.hasKeyPrefix(NS, "/file200"));
        assertEquals(List.of(0, 100, 200), store.offsets);
    }

    @Test
    void defaultVisitsAllPagesForAMiss() {
        LegacyStore store = populatedStore(250);
        assertFalse(store.hasKeyPrefix(NS, "/missing"));
        assertEquals(List.of(0, 100, 200), store.offsets);
    }

    @Test
    void defaultStopsAfterAnEmptyFinalPage() {
        LegacyStore store = populatedStore(200);
        assertFalse(store.hasKeyPrefix(NS, "/missing"));
        assertEquals(List.of(0, 100, 200), store.offsets);
    }

    @Test
    void defaultPropagatesBackendFailures() {
        IllegalStateException failure = new IllegalStateException("store unavailable");
        LegacyStore store =
                new LegacyStore() {
                    @Override
                    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
                        throw failure;
                    }
                };
        assertSame(
                failure,
                assertThrows(IllegalStateException.class, () -> store.hasKeyPrefix(NS, "/file")));
    }

    private static BaseStore store(boolean fallback) {
        return fallback ? new LegacyStore() : new InMemoryStore();
    }

    private static LegacyStore populatedStore(int count) {
        LegacyStore store = new LegacyStore();
        for (int i = 0; i < count; i++) {
            store.put(NS, String.format("/file%03d", i), Map.of());
        }
        return store;
    }

    /** Implements only the original interface, exercising both default methods. */
    private static class LegacyStore implements BaseStore {
        private final InMemoryStore delegate = new InMemoryStore();
        private final List<Integer> offsets = new ArrayList<>();

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
            offsets.add(offset);
            assertEquals(100, limit);
            return delegate.search(namespace, limit, offset);
        }

        @Override
        public void delete(List<String> namespace, String key) {
            delegate.delete(namespace, key);
        }
    }
}
