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
package io.agentscope.harness.coding.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.harness.agent.store.StoreItem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SqliteBaseStore} using an in-memory SQLite database. */
class SqliteBaseStoreTest {

    private SqliteBaseStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new SqliteBaseStore(":memory:");
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    @Test
    void put_and_get_roundtrip() {
        List<String> ns = List.of("threads", "thread-1");
        store.put(ns, "meta", Map.of("prUrl", "https://github.com/org/repo/pull/1"));

        StoreItem item = store.get(ns, "meta");
        assertNotNull(item);
        assertEquals("meta", item.key());
        assertEquals("https://github.com/org/repo/pull/1", item.value().get("prUrl"));
    }

    @Test
    void get_returnsNull_whenMissing() {
        assertNull(store.get(List.of("threads", "nonexistent"), "key"));
    }

    @Test
    void put_overwrites_existingEntry() {
        List<String> ns = List.of("queue", "t1");
        store.put(ns, "msg1", Map.of("text", "original"));
        store.put(ns, "msg1", Map.of("text", "updated"));

        StoreItem item = store.get(ns, "msg1");
        assertEquals("updated", item.value().get("text"));
    }

    @Test
    void delete_removesEntry() {
        List<String> ns = List.of("deliveries");
        store.put(ns, "delivery-abc", Map.of("processed_at", 1234L));
        store.delete(ns, "delivery-abc");

        assertNull(store.get(ns, "delivery-abc"));
    }

    @Test
    void search_returnsAllInNamespace() {
        List<String> ns = List.of("findings", "thread-2");
        store.put(ns, "f1", Map.of("severity", "HIGH"));
        store.put(ns, "f2", Map.of("severity", "LOW"));

        List<StoreItem> items = store.search(ns, -1, 0);
        assertEquals(2, items.size());
    }

    @Test
    void search_respectsLimitAndOffset() {
        List<String> ns = List.of("queue", "t2");
        for (int i = 0; i < 5; i++) {
            store.put(ns, "msg-" + i, Map.of("idx", i));
        }

        List<StoreItem> first2 = store.search(ns, 2, 0);
        assertEquals(2, first2.size());
    }

    @Test
    void count_returnsCorrectCount() {
        List<String> ns = List.of("queue", "t3");
        assertEquals(0, store.count(ns));
        store.put(ns, "a", Map.of("x", 1));
        store.put(ns, "b", Map.of("x", 2));
        assertEquals(2, store.count(ns));
    }

    @Test
    void namespaces_areIsolated() {
        List<String> ns1 = List.of("ns1");
        List<String> ns2 = List.of("ns2");
        store.put(ns1, "key", Map.of("val", "from-ns1"));
        store.put(ns2, "key", Map.of("val", "from-ns2"));

        assertEquals("from-ns1", store.get(ns1, "key").value().get("val"));
        assertEquals("from-ns2", store.get(ns2, "key").value().get("val"));
    }

    @Test
    void dedup_scenario() {
        List<String> dedupNs = List.of("deliveries");
        String deliveryId = "gh-delivery-xyz";

        assertNull(store.get(dedupNs, deliveryId), "Should not exist yet");
        store.put(dedupNs, deliveryId, Map.of("processed_at", System.currentTimeMillis()));
        assertNotNull(store.get(dedupNs, deliveryId), "Should exist after put");
    }
}
