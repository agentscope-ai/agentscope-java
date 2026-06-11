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

import java.util.List;
import java.util.Map;

/**
 * Abstract interface for a namespace-based key-value store.
 *
 * <p>Items are organized by namespaces (hierarchical path-like tuples)
 * and identified by a key within each namespace.
 */
public interface BaseStore {

    /**
     * Get a single item by namespace and key.
     *
     * @param namespace hierarchical namespace path
     * @param key the item key within the namespace
     * @return the store item, or {@code null} if not found
     */
    StoreItem get(List<String> namespace, String key);

    /**
     * Store or update an item.
     *
     * @param namespace hierarchical namespace path
     * @param key the item key within the namespace
     * @param value the data to store
     */
    void put(List<String> namespace, String key, Map<String, Object> value);

    /**
     * Conditional update: stores the item only when the current stored version matches
     * {@code expectedVersion}.
     *
     * <p>This is a compare-and-swap (CAS) operation that prevents lost updates when multiple
     * writers concurrently modify the same key. Callers should:
     * <ol>
     *   <li>Read the item with {@link #get} and remember {@link StoreItem#version()}.
     *   <li>Compute the new value locally.
     *   <li>Call {@code putIfVersion} passing the remembered version as {@code expectedVersion}.
     *   <li>If the call returns {@code false}, the item was modified by another writer; retry
     *       from step 1.
     * </ol>
     *
     * <p>A special value of {@code 0} for {@code expectedVersion} means "write only if the key
     * does not yet exist" (i.e. create-if-absent).
     *
     * <p>The default implementation always returns {@code false} so that existing store
     * implementations compile without changes. Backends that support distributed deployments
     * should override this method with a proper server-side atomic check.
     *
     * @param namespace hierarchical namespace path
     * @param key the item key within the namespace
     * @param value the new data to store
     * @param expectedVersion the version the caller observed; must match the current stored version
     * @return {@code true} if the item was written, {@code false} if the version did not match
     */
    default boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        return false;
    }

    /**
     * Search for items within a namespace with pagination.
     *
     * @param namespace hierarchical namespace path
     * @param limit maximum number of items to return
     * @param offset number of items to skip
     * @return list of matching store items
     */
    List<StoreItem> search(List<String> namespace, int limit, int offset);

    /**
     * Delete an item by namespace and key.
     *
     * @param namespace hierarchical namespace path
     * @param key the item key to delete
     */
    void delete(List<String> namespace, String key);
}
