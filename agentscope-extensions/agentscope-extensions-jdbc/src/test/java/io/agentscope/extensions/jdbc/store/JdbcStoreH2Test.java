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
package io.agentscope.extensions.jdbc.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.jdbc.H2TestSupport;
import io.agentscope.extensions.jdbc.dialect.H2Dialect;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H2 in-memory integration tests for {@link JdbcStore}.
 *
 * @author shanhongyu
 */
@DisplayName("JdbcStore H2 integration tests")
class JdbcStoreH2Test {

    private JdbcStore store;

    @BeforeEach
    void setUp() {
        DataSource ds = H2TestSupport.createDataSource("jdbc_store_test");
        store = JdbcStore.builder(ds).dialect(new H2Dialect()).initializeSchema(true).build();
    }

    @Test
    @DisplayName("put and get round-trips a value")
    void putAndGetRoundTrip() {
        List<String> ns = List.of("agents", "my-agent");
        Map<String, Object> value = Map.of("key", "value", "count", 42);

        store.put(ns, "item1", value);
        StoreItem result = store.get(ns, "item1");

        assertNotNull(result);
        assertEquals("item1", result.key());
        assertEquals("value", result.value().get("key"));
        assertEquals(42, result.value().get("count"));
        assertEquals(1L, result.version());
    }

    @Test
    @DisplayName("get returns null for missing key")
    void getReturnsNullForMissingKey() {
        StoreItem result = store.get(List.of("ns"), "nonexistent");
        assertNull(result);
    }

    @Test
    @DisplayName("put updates version on overwrite")
    void putUpdatesVersion() {
        List<String> ns = List.of("ns");
        store.put(ns, "k", Map.of("v", 1));
        store.put(ns, "k", Map.of("v", 2));

        StoreItem result = store.get(ns, "k");
        assertEquals(2L, result.version());
        assertEquals(2, result.value().get("v"));
    }

    @Test
    @DisplayName("putIfVersion with expectedVersion=0 creates new item")
    void putIfVersionCreateNew() {
        boolean success = store.putIfVersion(List.of("ns"), "k", Map.of("v", 1), 0L);
        assertTrue(success);
        assertEquals(1L, store.get(List.of("ns"), "k").version());
    }

    @Test
    @DisplayName("putIfVersion with expectedVersion=0 fails on existing key")
    void putIfVersionCreateFailsOnExisting() {
        store.putIfVersion(List.of("ns"), "k", Map.of("v", 1), 0L);
        boolean success = store.putIfVersion(List.of("ns"), "k", Map.of("v", 2), 0L);
        assertFalse(success);
    }

    @Test
    @DisplayName("putIfVersion with matching version succeeds")
    void putIfVersionMatchingSucceeds() {
        store.put(List.of("ns"), "k", Map.of("v", 1));
        long version = store.get(List.of("ns"), "k").version();

        boolean success = store.putIfVersion(List.of("ns"), "k", Map.of("v", 2), version);
        assertTrue(success);
        assertEquals(2L, store.get(List.of("ns"), "k").version());
    }

    @Test
    @DisplayName("putIfVersion with stale version fails")
    void putIfVersionStaleFails() {
        store.put(List.of("ns"), "k", Map.of("v", 1));
        store.put(List.of("ns"), "k", Map.of("v", 2)); // version now 2

        boolean success = store.putIfVersion(List.of("ns"), "k", Map.of("v", 3), 1L);
        assertFalse(success);
    }

    @Test
    @DisplayName("delete removes item")
    void deleteRemovesItem() {
        store.put(List.of("ns"), "k", Map.of("v", 1));
        store.delete(List.of("ns"), "k");
        assertNull(store.get(List.of("ns"), "k"));
    }

    @Test
    @DisplayName("search returns items in namespace with pagination")
    void searchReturnsItemsWithPagination() {
        List<String> ns = List.of("parent");
        store.put(ns, "b", Map.of("v", 2));
        store.put(ns, "a", Map.of("v", 1));
        store.put(ns, "c", Map.of("v", 3));
        store.put(List.of("parent", "child"), "d", Map.of("v", 4));

        List<StoreItem> results = store.search(List.of("parent"), 10, 0);
        assertEquals(4, results.size(), "should include items from sub-namespaces");
        assertEquals("a", results.get(0).key(), "should be ordered by key");
    }

    @Test
    @DisplayName("search respects limit and offset")
    void searchRespectsLimitOffset() {
        List<String> ns = List.of("ns");
        for (int i = 0; i < 5; i++) {
            store.put(ns, "key" + i, Map.of("v", i));
        }

        List<StoreItem> page = store.search(ns, 2, 1);
        assertEquals(2, page.size());
        assertEquals("key1", page.get(0).key());
        assertEquals("key2", page.get(1).key());
    }
}
