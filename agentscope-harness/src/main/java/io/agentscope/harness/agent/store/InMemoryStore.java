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
package io.agentscope.harness.agent.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe in-memory implementation of {@link BaseStore}.
 *
 * <p>Items are stored in a ConcurrentHashMap keyed by the concatenation of
 * namespace components and the item key, separated by {@code '\0'}.
 */
public class InMemoryStore implements BaseStore {

    private final ConcurrentMap<String, StoreItem> store = new ConcurrentHashMap<>();

    @Override
    public StoreItem get(List<String> namespace, String key) {
        return store.get(compoundKey(namespace, key));
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        store.put(compoundKey(namespace, key), new StoreItem(key, value));
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        String prefix = namespacePrefix(namespace);
        List<StoreItem> matches = new ArrayList<>();
        for (Map.Entry<String, StoreItem> entry : store.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                matches.add(entry.getValue());
            }
        }
        Collections.sort(matches, (a, b) -> a.key().compareTo(b.key()));

        int start = Math.min(offset, matches.size());
        int end = Math.min(start + limit, matches.size());
        return matches.subList(start, end);
    }

    @Override
    public void delete(List<String> namespace, String key) {
        store.remove(compoundKey(namespace, key));
    }

    /** Returns the number of items currently stored. */
    public int size() {
        return store.size();
    }

    /** Removes all items from the store. */
    public void clear() {
        store.clear();
    }

    private static String compoundKey(List<String> namespace, String key) {
        return namespacePrefix(namespace) + key;
    }

    private static String namespacePrefix(List<String> namespace) {
        StringBuilder sb = new StringBuilder();
        for (String component : namespace) {
            sb.append(component).append('\0');
        }
        return sb.toString();
    }
}
