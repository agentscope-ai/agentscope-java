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
