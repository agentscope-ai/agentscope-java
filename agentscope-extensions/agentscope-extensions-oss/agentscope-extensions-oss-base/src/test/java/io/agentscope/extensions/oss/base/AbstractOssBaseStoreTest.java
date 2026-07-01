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
package io.agentscope.extensions.oss.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Behavioural tests for {@link AbstractOssBaseStore} using the in-memory adapter. */
class AbstractOssBaseStoreTest {

    private InMemoryOssAdapter adapter;
    private AbstractOssBaseStore store;

    private static final class TestStore extends AbstractOssBaseStore {
        TestStore(OssAdapter adapter, String bucketName, String keyPrefix) {
            super(adapter, bucketName, keyPrefix);
        }
    }

    @BeforeEach
    void setUp() {
        adapter = new InMemoryOssAdapter();
        store = new TestStore(adapter, "test-bucket", "test/store/");
    }

    @Test
    void constructorRejectsNullAdapter() {
        assertThrows(NullPointerException.class, () -> new TestStore(null, "b", "test/store/"));
    }

    @Test
    void constructorRejectsBlankBucket() {
        assertThrows(
                IllegalArgumentException.class, () -> new TestStore(adapter, "", "test/store/"));
    }

    @Test
    void putThenGetRoundtrips() {
        store.put(List.of("ns1", "ns2"), "my-key", Map.of("foo", "bar"));

        StoreItem item = store.get(List.of("ns1", "ns2"), "my-key");
        assertNotNull(item);
        assertEquals("my-key", item.key());
        assertEquals("bar", item.value().get("foo"));
        assertEquals(1L, item.version());
        assertTrue(adapter.objects.containsKey("test/store/ns1/ns2/my-key.json"));
        assertTrue(adapter.objects.containsKey("test/store/ns1/ns2/my-key.version"));
    }

    @Test
    void getReturnsNullWhenMissing() {
        assertNull(store.get(List.of("ns1"), "nope"));
    }

    @Test
    void putIncrementsVersion() {
        store.put(List.of("ns"), "k", Map.of("v", 1));
        store.put(List.of("ns"), "k", Map.of("v", 2));

        StoreItem item = store.get(List.of("ns"), "k");
        assertEquals(2L, item.version());
        assertEquals(2, item.value().get("v"));
    }

    @Test
    void putIfVersionReturnsFalseOnMismatch() {
        store.put(List.of("ns"), "k", Map.of("v", 1));
        boolean ok = store.putIfVersion(List.of("ns"), "k", Map.of("v", 9), 42);
        assertFalse(ok);
        assertEquals(1, store.get(List.of("ns"), "k").value().get("v"));
    }

    @Test
    void putIfVersionSucceedsOnMatch() {
        store.put(List.of("ns"), "k", Map.of("v", 1));
        boolean ok = store.putIfVersion(List.of("ns"), "k", Map.of("v", 9), 1L);
        assertTrue(ok);
        StoreItem item = store.get(List.of("ns"), "k");
        assertEquals(9, item.value().get("v"));
        assertEquals(2L, item.version());
    }

    @Test
    void deleteRemovesDataAndVersion() {
        store.put(List.of("ns"), "k", Map.of("v", 1));
        store.delete(List.of("ns"), "k");
        assertNull(store.get(List.of("ns"), "k"));
        assertFalse(adapter.objects.containsKey("test/store/ns/k.json"));
        assertFalse(adapter.objects.containsKey("test/store/ns/k.version"));
    }

    @Test
    void searchPaginatesInOrder() {
        for (int i = 0; i < 5; i++) {
            store.put(List.of("ns"), "k" + i, Map.of("i", i));
        }
        List<StoreItem> firstPage = store.search(List.of("ns"), 2, 0);
        List<StoreItem> secondPage = store.search(List.of("ns"), 2, 2);
        assertEquals(2, firstPage.size());
        assertEquals(2, secondPage.size());
        assertEquals("k0", firstPage.get(0).key());
        assertEquals("k1", firstPage.get(1).key());
        assertEquals("k2", secondPage.get(0).key());
        assertEquals("k3", secondPage.get(1).key());
    }

    @Test
    void keyLeadingSlashesAreStripped() {
        store.put(List.of("ns"), "/leading", Map.of("v", 1));
        assertTrue(adapter.objects.containsKey("test/store/ns/leading.json"));
    }
}
