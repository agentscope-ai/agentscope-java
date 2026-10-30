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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link BaseStore} semantics.
 *
 * <p>Runs against {@link InMemoryStore} (canonical reference). RedisStore is omitted here because
 * harness does not pull jedis/testcontainers; other backends should match these semantics.
 *
 * <p><b>Search recursion (canonical):</b> {@link InMemoryStore#search} matches by namespace-prefix,
 * so {@code search(["a"])} <em>does</em> return items stored under child namespaces such as
 * {@code ["a","b"]}. Control-plane hosted KV must preserve this behavior.
 */
class BaseStoreContractTest {

    @Test
    void putGetRoundTrip_versionStartsAtOne() {
        BaseStore store = newStore();
        List<String> ns = List.of("ws");

        store.put(ns, "MEMORY.md", Map.of("content", "hello"));
        StoreItem item = store.get(ns, "MEMORY.md");

        assertNotNull(item);
        assertEquals("MEMORY.md", item.key());
        assertEquals("hello", item.value().get("content"));
        assertEquals(1L, item.version());
    }

    @Test
    void put_incrementsVersion() {
        BaseStore store = newStore();
        List<String> ns = List.of("ws");

        store.put(ns, "k", Map.of("v", 1));
        assertEquals(1L, store.get(ns, "k").version());

        store.put(ns, "k", Map.of("v", 2));
        assertEquals(2L, store.get(ns, "k").version());
        assertEquals(2, store.get(ns, "k").value().get("v"));
    }

    @Test
    void putIfVersion_successAndConflict() {
        BaseStore store = newStore();
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
    void putIfVersionZero_createIfAbsent() {
        BaseStore store = newStore();
        List<String> ns = List.of("create");

        assertTrue(store.putIfVersion(ns, "k", Map.of("v", 1), 0L));
        assertEquals(1L, store.get(ns, "k").version());

        assertFalse(store.putIfVersion(ns, "k", Map.of("v", 2), 0L));
        assertEquals(1, store.get(ns, "k").value().get("v"));
    }

    @Test
    void delete_isIdempotent() {
        BaseStore store = newStore();
        List<String> ns = List.of("del");

        store.put(ns, "k", Map.of("v", 1));
        store.delete(ns, "k");
        assertNull(store.get(ns, "k"));

        store.delete(ns, "k"); // second delete must not throw
        assertNull(store.get(ns, "k"));
    }

    @Test
    void search_includesChildNamespaceItems() {
        BaseStore store = newStore();

        store.put(List.of("a"), "parent", Map.of("where", "a"));
        store.put(List.of("a", "b"), "child", Map.of("where", "a/b"));

        List<StoreItem> found = store.search(List.of("a"), 100, 0);
        Set<String> keys = found.stream().map(StoreItem::key).collect(Collectors.toSet());

        // Canonical semantics (InMemoryStore prefix match): search(["a"]) returns both
        // the item under ["a"] and items under child namespaces such as ["a","b"].
        assertEquals(Set.of("parent", "child"), keys);
        assertEquals(2, found.size());
    }

    private static BaseStore newStore() {
        return new InMemoryStore();
    }
}
