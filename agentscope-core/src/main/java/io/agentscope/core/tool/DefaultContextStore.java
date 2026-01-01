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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory {@link ContextStore} using two-level Map: Class → (Key → Object).
 *
 * <p>Supports singleton (one per type) and multi-instance (keyed) patterns. Immutable and
 * thread-safe.
 */
class DefaultContextStore implements ContextStore {

    private static final String DEFAULT_KEY = "";

    // Two-level map: Class -> (Key -> Object)
    private final Map<Class<?>, Map<String, Object>> objectMap;

    private DefaultContextStore(Builder builder) {
        if (builder.objectMap != null) {
            Map<Class<?>, Map<String, Object>> copy = new HashMap<>();
            for (Map.Entry<Class<?>, Map<String, Object>> entry : builder.objectMap.entrySet()) {
                copy.put(
                        entry.getKey(),
                        Collections.unmodifiableMap(new HashMap<>(entry.getValue())));
            }
            this.objectMap = Collections.unmodifiableMap(copy);
        } else {
            this.objectMap = Collections.emptyMap();
        }
    }

    /**
     * Retrieves an object by key and type.
     *
     * @param key The key identifying the instance
     * @param type The class type to retrieve
     * @param <T> The object type
     * @return The object instance, or null if not found
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Map<String, Object> keyMap = objectMap.get(type);
        if (keyMap == null) {
            return null;
        }

        Object obj = keyMap.get(key);
        if (obj != null && type.isInstance(obj)) {
            return (T) obj;
        }
        return null;
    }

    /**
     * Retrieves an object by type only (singleton case).
     *
     * <p>This method returns the object stored with the default key (empty string).
     * If no object exists with the default key, it returns null.
     *
     * @param type The class type to retrieve
     * @param <T> The object type
     * @return The object instance, or null if not found
     */
    @Override
    public <T> T get(Class<T> type) {
        return get(DEFAULT_KEY, type);
    }

    /**
     * Checks whether an object with the specified key and type exists.
     *
     * @param key The key identifying the instance
     * @param type The class type to check
     * @return true if the object exists, false otherwise
     */
    @Override
    public boolean contains(String key, Class<?> type) {
        Map<String, Object> keyMap = objectMap.get(type);
        return keyMap != null && keyMap.containsKey(key);
    }

    /**
     * Checks whether any object of the specified type exists.
     *
     * @param type The class type to check
     * @return true if at least one object of this type exists, false otherwise
     */
    @Override
    public boolean contains(Class<?> type) {
        Map<String, Object> keyMap = objectMap.get(type);
        return keyMap != null && !keyMap.isEmpty();
    }

    /**
     * Returns an unmodifiable view of the internal object map.
     *
     * <p>The map structure is: Class → (Key → Object), where the outer map is keyed by type and
     * the inner map is keyed by instance identifier. This allows both singleton pattern (using
     * default key "") and multi-instance pattern (using custom keys).
     *
     * @return Unmodifiable map containing all stored objects, never null (may be empty)
     */
    public Map<Class<?>, Map<String, Object>> getObjectMap() {
        return objectMap;
    }

    /**
     * Creates a new builder for DefaultContextStore.
     *
     * @return A new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates an empty DefaultContextStore.
     *
     * @return An empty store
     */
    public static DefaultContextStore empty() {
        return new Builder().build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DefaultContextStore that = (DefaultContextStore) o;
        return Objects.equals(objectMap, that.objectMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(objectMap);
    }

    @Override
    public String toString() {
        return "DefaultContextStore{" + "objectMap=" + objectMap + '}';
    }

    /** Builder for DefaultContextStore. */
    public static class Builder {
        private Map<Class<?>, Map<String, Object>> objectMap;

        private Builder() {}

        /**
         * Registers an object with its exact type as the key (singleton case).
         *
         * <p>The object is registered with the default key (empty string), indicating a singleton
         * instance for this type.
         *
         * <p>Example:
         * <pre>{@code
         * DatabaseConfig config = new DatabaseConfig();
         * builder.register(config);  // Singleton - no key needed
         * }</pre>
         *
         * @param object The object to register (must not be null)
         * @return This builder
         * @throws NullPointerException if object is null
         */
        @SuppressWarnings("unchecked")
        public Builder register(Object object) {
            Objects.requireNonNull(object, "Object must not be null");
            Class<Object> type = (Class<Object>) object.getClass();
            return register(DEFAULT_KEY, type, object);
        }

        /**
         * Registers an object with an explicit type key (singleton case).
         *
         * <p>This allows registering objects by their interface or superclass type.
         *
         * <p>Example:
         * <pre>{@code
         * UserContextImpl impl = new UserContextImpl();
         * builder.register(UserContext.class, impl);  // Register by interface
         * }</pre>
         *
         * @param type The type key (must not be null)
         * @param object The object to register (must not be null)
         * @param <T> The object type
         * @return This builder
         * @throws NullPointerException if type or object is null
         * @throws IllegalArgumentException if object is not an instance of type
         */
        public <T> Builder register(Class<T> type, T object) {
            return register(DEFAULT_KEY, type, object);
        }

        /**
         * Registers an object with a custom key and explicit type.
         *
         * <p>This allows storing multiple instances of the same type, distinguished by keys.
         *
         * <p>Example:
         * <pre>{@code
         * UserContext admin = new UserContext("admin");
         * UserContext guest = new UserContext("guest");
         * builder.register("admin", UserContext.class, admin)
         *        .register("guest", UserContext.class, guest);
         * }</pre>
         *
         * @param key The key identifying this instance (must not be null)
         * @param type The type key (must not be null)
         * @param object The object to register (must not be null)
         * @param <T> The object type
         * @return This builder
         * @throws NullPointerException if key, type, or object is null
         * @throws IllegalArgumentException if object is not an instance of type
         */
        public <T> Builder register(String key, Class<T> type, T object) {
            Objects.requireNonNull(key, "Key must not be null");
            Objects.requireNonNull(type, "Type must not be null");
            Objects.requireNonNull(object, "Object must not be null");
            if (!type.isInstance(object)) {
                throw new IllegalArgumentException(
                        "Object must be an instance of type " + type.getName());
            }

            if (this.objectMap == null) {
                this.objectMap = new HashMap<>();
            }

            Map<String, Object> keyMap = this.objectMap.computeIfAbsent(type, k -> new HashMap<>());
            keyMap.put(key, object);
            return this;
        }

        /**
         * Registers an object with a custom key using its runtime type.
         *
         * <p>This is a convenience method for registering keyed objects without specifying type.
         *
         * <p>Example:
         * <pre>{@code
         * UserContext admin = new UserContext("admin");
         * builder.register("admin", admin);  // Type inferred from object
         * }</pre>
         *
         * @param key The key identifying this instance (must not be null)
         * @param object The object to register (must not be null)
         * @return This builder
         * @throws NullPointerException if key or object is null
         */
        @SuppressWarnings("unchecked")
        public Builder register(String key, Object object) {
            Objects.requireNonNull(key, "Key must not be null");
            Objects.requireNonNull(object, "Object must not be null");
            Class<Object> type = (Class<Object>) object.getClass();
            return register(key, type, object);
        }

        /**
         * Builds the DefaultContextStore.
         *
         * @return A new DefaultContextStore
         */
        public DefaultContextStore build() {
            return new DefaultContextStore(this);
        }
    }
}
