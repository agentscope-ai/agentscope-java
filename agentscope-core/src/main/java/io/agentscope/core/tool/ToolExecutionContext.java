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
package io.agentscope.core.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Facade for passing custom POJOs to tool methods via priority-based resolution.
 *
 * <p>Two-layer architecture: External interface + Storage layer ({@link ContextStore}).
 *
 * <p>Priority chain: Call → Agent → Toolkit → Spring (highest to lowest)
 *
 * <p>Example:
 *
 * <pre>{@code
 * // Register custom POJO
 * ToolExecutionContext context = ToolExecutionContext.builder()
 *     .register(new UserContext("user123"))
 *     .build();
 *
 * // Auto-injected by type in tool methods
 * @Tool
 * public ToolResultBlock myTool(UserContext ctx) {
 *     return ToolResultBlock.text(ctx.getUserId());
 * }
 * }</pre>
 *
 * @see ContextStore
 * @see DefaultContextStore
 */
public class ToolExecutionContext {

    private final List<ContextStore> stores;

    private ToolExecutionContext(Builder builder) {
        this.stores =
                builder.stores != null && !builder.stores.isEmpty()
                        ? Collections.unmodifiableList(new ArrayList<>(builder.stores))
                        : Collections.emptyList();
    }

    /**
     * Retrieves an object by key and type (for multi-instance scenarios).
     *
     * @param key The key identifying the instance
     * @param type The class type to retrieve
     * @param <T> The object type
     * @return The object instance, or null if not found
     */
    public <T> T get(String key, Class<T> type) {
        // Query all stores in priority order
        for (ContextStore store : stores) {
            T obj = store.get(key, type);
            if (obj != null) {
                return obj;
            }
        }
        return null;
    }

    /**
     * Retrieves an object by type only (for singleton scenarios).
     *
     * @param type The class type to retrieve
     * @param <T> The object type
     * @return The object instance, or null if not found
     */
    public <T> T get(Class<T> type) {
        // Query all stores in priority order
        for (ContextStore store : stores) {
            T obj = store.get(type);
            if (obj != null) {
                return obj;
            }
        }
        return null;
    }

    /**
     * Checks whether an object with the specified key and type exists in any store.
     *
     * @param key The key identifying the instance
     * @param type The class type to check
     * @return true if any store contains the object, false otherwise
     */
    public boolean contains(String key, Class<?> type) {
        for (ContextStore store : stores) {
            if (store.contains(key, type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether an object of the specified type exists in any store (regardless of key).
     *
     * @param type The class type to check
     * @return true if any store contains the object, false otherwise
     */
    public boolean contains(Class<?> type) {
        for (ContextStore store : stores) {
            if (store.contains(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets all stores in priority order.
     *
     * @return Unmodifiable list of stores (never null, may be empty)
     */
    public List<ContextStore> getStores() {
        return stores;
    }

    /**
     * Merges multiple contexts into priority-based resolution chain.
     *
     * <p>Earlier contexts have higher priority.
     *
     * @param contexts The contexts to merge, in priority order (highest first)
     * @return A new merged context
     */
    public static ToolExecutionContext merge(ToolExecutionContext... contexts) {
        if (contexts == null || contexts.length == 0) {
            return empty();
        }

        // Collect all stores from all contexts in priority order
        List<ContextStore> mergedStores = new ArrayList<>();
        for (ToolExecutionContext ctx : contexts) {
            if (ctx != null && !ctx.stores.isEmpty()) {
                mergedStores.addAll(ctx.stores);
            }
        }

        if (mergedStores.isEmpty()) {
            return empty();
        }

        return new Builder().stores(mergedStores).build();
    }

    /**
     * Creates a new builder for constructing ToolExecutionContext instances.
     *
     * @return A new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates an empty context.
     *
     * @return An empty ToolExecutionContext
     */
    public static ToolExecutionContext empty() {
        return new Builder().build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ToolExecutionContext that = (ToolExecutionContext) o;
        return Objects.equals(stores, that.stores);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stores);
    }

    @Override
    public String toString() {
        return "ToolExecutionContext{" + "stores=" + stores.size() + " stores}";
    }

    /** Builder for ToolExecutionContext. */
    public static class Builder {
        private DefaultContextStore.Builder storeBuilder = DefaultContextStore.builder();
        private List<ContextStore> stores;

        private Builder() {}

        /**
         * Registers an object by its runtime type (singleton pattern).
         *
         * @param object The object to register
         * @return This builder
         */
        public Builder register(Object object) {
            this.storeBuilder.register(object);
            return this;
        }

        /**
         * Registers an object with explicit type (e.g., by interface).
         *
         * @param type The type key
         * @param object The object to register
         * @param <T> The object type
         * @return This builder
         */
        public <T> Builder register(Class<T> type, T object) {
            this.storeBuilder.register(type, object);
            return this;
        }

        /**
         * Registers an object with key and type (multi-instance pattern).
         *
         * @param key The key identifying this instance
         * @param type The type key
         * @param object The object to register
         * @param <T> The object type
         * @return This builder
         */
        public <T> Builder register(String key, Class<T> type, T object) {
            this.storeBuilder.register(key, type, object);
            return this;
        }

        /**
         * Registers an object with key (type inferred from runtime).
         *
         * @param key The key identifying this instance
         * @param object The object to register
         * @return This builder
         */
        public Builder register(String key, Object object) {
            this.storeBuilder.register(key, object);
            return this;
        }

        /**
         * Sets the store list directly (replaces all previous registrations).
         *
         * <p>This is primarily used internally by {@link #merge(ToolExecutionContext...)} to
         * combine stores from multiple contexts.
         *
         * @param stores The list of stores in priority order
         * @return This builder
         */
        Builder stores(List<ContextStore> stores) {
            this.stores = stores;
            return this;
        }

        /**
         * Adds a single store to the store list.
         *
         * <p>This allows adding custom {@link ContextStore} implementations.
         *
         * @param store The context store to add
         * @return This builder
         */
        public Builder addStore(ContextStore store) {
            if (this.stores == null) {
                this.stores = new ArrayList<>();
            }
            this.stores.add(store);
            return this;
        }

        /**
         * Builds the ToolExecutionContext.
         *
         * @return A new ToolExecutionContext
         */
        public ToolExecutionContext build() {
            if (stores == null) {
                // No explicit stores set, build from registered objects
                ContextStore store = storeBuilder.build();
                if (store != null
                        && !(store instanceof DefaultContextStore
                                && ((DefaultContextStore) store).getObjectMap().isEmpty())) {
                    stores = new ArrayList<>();
                    stores.add(store);
                }
            }
            return new ToolExecutionContext(this);
        }
    }
}
