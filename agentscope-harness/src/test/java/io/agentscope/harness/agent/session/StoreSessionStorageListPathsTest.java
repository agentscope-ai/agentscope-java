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

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StoreSessionStorageListPathsTest {
    private static final List<String> SESSION_NAMESPACE =
            List.of("tenant", "__agentscope_session_log_v1__");

    @Test
    void listPathsDelegatesToStoresKeyListing() {
        var store = new KeyListingStore();
        var storage = new StoreSessionStorage(store, List.of("tenant"));

        assertEquals(List.of("commits/a.json", "commits/b.json"), storage.listPaths("commits/"));
        assertEquals(1, store.listKeysCalls);
        assertEquals(0, store.searches);
    }

    @Test
    void listPathsFallsBackToPagedSearchWhenStoreHasNoKeyListing() {
        var store = new InMemoryStore();
        var storage = new StoreSessionStorage(store, List.of("tenant"));
        var expected = new ArrayList<String>();
        for (int i = 0; i < 300; i++) {
            String path = String.format("commits/%03d.json", i);
            store.put(SESSION_NAMESPACE, path, Map.of("bytes", encode(new byte[] {(byte) i})));
            expected.add(path);
        }
        store.put(SESSION_NAMESPACE, "other/ignored.json", Map.of("bytes", encode(new byte[] {9})));

        assertEquals(expected, storage.listPaths("commits/"));
        var all = new ArrayList<>(expected);
        all.add("other/ignored.json");
        assertEquals(all, storage.listPaths(""));
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static final class KeyListingStore extends InMemoryStore {
        int listKeysCalls;
        int searches;

        @Override
        public List<String> listKeys(List<String> namespace, String keyPrefix) {
            listKeysCalls++;
            return List.of("commits/a.json", "commits/b.json");
        }

        @Override
        public List<StoreItem> search(List<String> namespace, int limit, int offset) {
            searches++;
            return super.search(namespace, limit, offset);
        }
    }
}
