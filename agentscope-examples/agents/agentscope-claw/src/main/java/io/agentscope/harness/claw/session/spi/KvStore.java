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
package io.agentscope.harness.claw.session.spi;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Narrow key/value abstraction for stores that need to be swapped between local-file and Redis
 * backends. Designed for "hot path" per-user state where cross-replica freshness matters and the
 * value can be (de)serialised as a single JSON document.
 *
 * <p>Implementations:
 *
 * <ul>
 *   <li>{@link FileKvStore} — one JSON file per key on disk. Single-node only. Default.
 *   <li>{@link RedisKvStore} — one Redis hash field per key. Cross-replica consistent. Active when
 *       {@code claw.session.redis.enabled=true}.
 * </ul>
 *
 * <p>The interface is deliberately small: implementations don't have to support iteration, range
 * queries, or atomic CAS. Callers needing those build them on top of {@link #mutate}.
 */
public interface KvStore<V> {

    /** Loads the value for {@code key}, or {@link Optional#empty()} if absent. */
    Optional<V> get(String key);

    /** Writes the value for {@code key}, overwriting any previous value. */
    void put(String key, V value);

    /** Removes the value for {@code key}; no-op if absent. */
    void remove(String key);

    /**
     * Atomically replaces the value at {@code key} with {@code mutator.apply(currentOrEmpty)}.
     * Returns the new value. Implementations must serialise concurrent mutations on the same key.
     *
     * <p>The {@code emptyValue} supplier is used when no value exists yet.
     */
    V mutate(String key, V emptyValue, Function<V, V> mutator);

    /**
     * Returns all known keys for this store. May be expensive (file enumeration / Redis SCAN); only
     * called by admin/snapshot paths, not on the request hot path.
     */
    List<String> keys();
}
