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
package io.agentscope.extensions.mongodb.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Contract tests for {@link BaseStore} semantics against a real MongoDB instance.
 *
 * <p>Mirrors the canonical contract defined in {@code BaseStoreContractTest} (agentscope-harness).
 * Skipped automatically when MongoDB is not reachable at {@code localhost:27017}.
 *
 * <p><b>Search semantics note:</b> {@code MongoBaseStore.search()} matches by exact namespace
 * (not prefix), so {@code search(["a"])} does NOT return items stored under child namespaces such
 * as {@code ["a","b"]}. This differs from {@code InMemoryStore} which uses prefix matching. The
 * search test below validates MongoDB's exact-match behavior.
 */
@DisplayName("BaseStore contract — MongoDB")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MongoBaseStoreContractTest {

    private static MongoClient client;
    private static MongoDatabase db;
    private static BaseStore store;

    @BeforeAll
    static void setUp() {
        try {
            client = MongoClients.create("mongodb://localhost:27017");
            client.getDatabase("ping").runCommand(new Document("ping", 1));
        } catch (Exception e) {
            Assumptions.abort("MongoDB not available: " + e.getMessage());
        }
        db = client.getDatabase("test_base_contract_" + System.currentTimeMillis());
        store = new MongoBaseStore(db, "test_base");
    }

    @AfterAll
    static void tearDown() {
        if (db != null) {
            db.drop();
        }
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("put + get round-trip: version starts at 1")
    void putGetRoundTrip_versionStartsAtOne() {
        List<String> ns = List.of("ws");

        store.put(ns, "MEMORY.md", Map.of("content", "hello"));
        StoreItem item = store.get(ns, "MEMORY.md");

        assertNotNull(item);
        assertEquals("MEMORY.md", item.key());
        assertEquals("hello", item.value().get("content"));
        assertEquals(1L, item.version());
    }

    @Test
    @Order(2)
    @DisplayName("put increments version on each call")
    void put_incrementsVersion() {
        List<String> ns = List.of("ver");

        store.put(ns, "k", Map.of("v", 1));
        assertEquals(1L, store.get(ns, "k").version());

        store.put(ns, "k", Map.of("v", 2));
        assertEquals(2L, store.get(ns, "k").version());
        assertEquals(2, store.get(ns, "k").value().get("v"));
    }

    @Test
    @Order(3)
    @DisplayName("putIfVersion: CAS success and conflict")
    void putIfVersion_successAndConflict() {
        List<String> ns = List.of("cas");

        store.put(ns, "k", Map.of("v", 1));
        long v1 = store.get(ns, "k").version();

        assertTrue(store.putIfVersion(ns, "k", Map.of("v", 2), v1));
        assertEquals(2, store.get(ns, "k").value().get("v"));
        assertEquals(v1 + 1, store.get(ns, "k").version());

        assertFalse(store.putIfVersion(ns, "k", Map.of("v", 3), v1));
        assertEquals(2, store.get(ns, "k").value().get("v"));
    }

    @Test
    @Order(4)
    @DisplayName("putIfVersion(0): create-if-absent")
    void putIfVersionZero_createIfAbsent() {
        List<String> ns = List.of("create");

        assertTrue(store.putIfVersion(ns, "k", Map.of("v", 1), 0L));
        assertEquals(1L, store.get(ns, "k").version());

        assertFalse(store.putIfVersion(ns, "k", Map.of("v", 2), 0L));
        assertEquals(1, store.get(ns, "k").value().get("v"));
    }

    @Test
    @Order(5)
    @DisplayName("delete is idempotent")
    void delete_isIdempotent() {
        List<String> ns = List.of("del");

        store.put(ns, "k", Map.of("v", 1));
        store.delete(ns, "k");
        assertNull(store.get(ns, "k"));

        store.delete(ns, "k");
        assertNull(store.get(ns, "k"));
    }

    @Test
    @Order(6)
    @DisplayName("search: exact namespace match (MongoDB behavior)")
    void search_exactNamespaceMatch() {
        store.put(List.of("s"), "inNs", Map.of("where", "s"));
        store.put(List.of("s", "t"), "inChild", Map.of("where", "s/t"));

        List<StoreItem> found = store.search(List.of("s"), 100, 0);
        Set<String> keys = found.stream().map(StoreItem::key).collect(Collectors.toSet());

        // MongoBaseStore uses exact namespace match (not prefix).
        // Only items directly under ["s"] are returned, not ["s","t"].
        assertEquals(Set.of("inNs"), keys);
        assertEquals(1, found.size());
    }
}
