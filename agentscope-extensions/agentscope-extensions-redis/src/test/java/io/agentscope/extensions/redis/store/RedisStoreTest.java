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
package io.agentscope.extensions.redis.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.ScanIteration;
import redis.clients.jedis.UnifiedJedis;

class RedisStoreTest {

    private final UnifiedJedis jedis = mock(UnifiedJedis.class);
    private final Map<String, List<String>> indexes = new HashMap<>();
    private final Map<String, Map<String, String>> hashes = new HashMap<>();
    private String prefix = "test:";
    private RedisStore store;

    @BeforeEach
    void setUp() {
        store = new RedisStore(jedis, prefix);
        when(jedis.zrangeByLex(anyString(), eq("-"), eq("+")))
                .thenAnswer(
                        invocation ->
                                indexes.getOrDefault(invocation.getArgument(0), List.of()).stream()
                                        .sorted()
                                        .toList());
        when(jedis.hgetAll(anyString()))
                .thenAnswer(invocation -> hashes.getOrDefault(invocation.getArgument(0), Map.of()));
    }

    @Test
    void searchIncludesChildNamespacesWithoutMatchingSiblingPrefix() {
        storedItem("a", "parent", "parent");
        storedItem("a\0b", "child", "child");
        storedItem("a\0b\0c", "grandchild", "grandchild");
        storedItem("ab", "sibling", "sibling");
        scannedIndexes("test:idx:a\0*", "test:idx:a\0b", "test:idx:a\0b\0c");

        assertEquals(
                List.of("child", "grandchild", "parent"), keys(store.search(List.of("a"), 100, 0)));
    }

    @Test
    void rootSearchIncludesExactRootAndAllDescendantsOnce() {
        storedItem("", "root", "root");
        storedItem("a", "parent", "parent");
        storedItem("a\0b", "child", "child");
        scannedIndexes("test:idx:*", "test:idx:", "test:idx:a", "test:idx:a\0b");

        assertEquals(List.of("child", "parent", "root"), keys(store.search(List.of(), 100, 0)));
    }

    @Test
    void searchPaginatesMergedResultsAndPreservesDuplicateKeys() {
        storedItem("a", "same", "parent");
        storedItem("a", "zebra", "last");
        storedItem("a\0b", "same", "child");
        storedItem("a\0b\0c", "alpha", "first");
        indexes.get("test:idx:a").add("missing");
        scannedIndexes("test:idx:a\0*", "test:idx:a\0b\0c", "test:idx:a\0b");

        List<StoreItem> page = store.search(List.of("a"), 2, 1);

        assertEquals(List.of("same", "same"), keys(page));
        assertEquals(
                List.of("parent", "child"),
                page.stream().map(item -> item.value().get("marker")).toList());
        assertEquals(List.of("alpha", "same"), keys(store.search(List.of("a"), 2, -1)));
        assertEquals(3, store.search(List.of("a"), Integer.MAX_VALUE, 1).size());
        assertTrue(store.search(List.of("a"), 1, Integer.MAX_VALUE).isEmpty());
    }

    @Test
    void searchTreatsNamespaceGlobCharactersLiterally() {
        String namespace = "a*?[x]\\name";
        storedItem(namespace, "parent", "parent");
        storedItem(namespace + "\0b", "child", "child");
        scannedIndexes("test:idx:a\\*\\?\\[x\\]\\\\name\0*", "test:idx:" + namespace + "\0b");

        assertEquals(List.of("child", "parent"), keys(store.search(List.of(namespace), 100, 0)));
    }

    @Test
    void rootSearchTreatsKeyPrefixGlobCharactersLiterally() {
        prefix = "[test]*?:\\:";
        store = new RedisStore(jedis, prefix);
        storedItem("a", "parent", "parent");
        scannedIndexes("\\[test\\]\\*\\?:\\\\:idx:*", prefix + "idx:a");

        assertEquals(List.of("parent"), keys(store.search(List.of(), 100, 0)));
    }

    @Test
    void searchWithNonPositiveLimitDoesNotReadRedis() {
        assertTrue(store.search(List.of("a"), 0, 0).isEmpty());
        assertTrue(store.search(List.of("a"), -1, 0).isEmpty());
        verifyNoInteractions(jedis);
    }

    private void storedItem(String namespace, String key, String marker) {
        indexes.computeIfAbsent(prefix + "idx:" + namespace, ignored -> new ArrayList<>()).add(key);
        hashes.put(
                prefix + "item:" + namespace + "\0" + key,
                Map.of("value", "{\"marker\":\"" + marker + "\"}", "version", "1"));
    }

    private void scannedIndexes(String pattern, String... indexKeys) {
        ScanIteration iteration = mock(ScanIteration.class);
        when(jedis.scanIteration(1000, pattern)).thenReturn(iteration);
        when(iteration.collect(any(Set.class)))
                .thenAnswer(
                        invocation -> {
                            Set<String> found = invocation.getArgument(0);
                            found.addAll(List.of(indexKeys));
                            return found;
                        });
    }

    private static List<String> keys(List<StoreItem> items) {
        return items.stream().map(StoreItem::key).toList();
    }
}
