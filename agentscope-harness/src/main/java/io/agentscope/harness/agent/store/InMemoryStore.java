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
 *
 * <p>Every successful {@link #put} and {@link #putIfVersion} increments the item's
 * {@link StoreItem#version()} counter, enabling optimistic concurrency control.
 */
public class InMemoryStore implements BaseStore {

    private final ConcurrentMap<String, StoreItem> store = new ConcurrentHashMap<>();

    @Override
    public StoreItem get(List<String> namespace, String key) {
        return store.get(compoundKey(namespace, key));
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        String ck = compoundKey(namespace, key);
        store.compute(
                ck,
                (k, existing) -> {
                    long nextVersion = (existing != null) ? existing.version() + 1 : 1L;
                    return new StoreItem(key, value, nextVersion);
                });
    }

    /**
     * Atomic compare-and-swap: updates the item only when the stored version equals
     * {@code expectedVersion}. An {@code expectedVersion} of {@code 0} succeeds only when the
     * key does not yet exist.
     *
     * @return {@code true} if the item was written, {@code false} if the version did not match
     */
    @Override
    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        String ck = compoundKey(namespace, key);
        boolean[] written = {false};
        store.compute(
                ck,
                (k, existing) -> {
                    long currentVersion = (existing != null) ? existing.version() : 0L;
                    if (currentVersion != expectedVersion) {
                        return existing; // version mismatch — no update
                    }
                    written[0] = true;
                    return new StoreItem(key, value, currentVersion + 1);
                });
        return written[0];
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
