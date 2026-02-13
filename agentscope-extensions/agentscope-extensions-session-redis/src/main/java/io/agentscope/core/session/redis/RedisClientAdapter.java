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
package io.agentscope.core.session.redis;

import java.util.List;
import java.util.Set;

/**
 * Adapter interface for Redis client operations.
 *
 * <p>This interface provides a unified abstraction over different Redis client implementations
 * (Jedis, Lettuce, Redisson), allowing the RedisSession to work with any of them without
 * modification.
 *
 * <p>All method names are designed to be self-explanatory and follow a consistent naming pattern:
 * <ul>
 *   <li>String operations: {@code set}, {@code get}</li>
 *   <li>List operations: {@code rightPushList}, {@code rangeList}, {@code getListLength}</li>
 *   <li>Set operations: {@code addToSet}, {@code getSetMembers}, {@code getSetSize}</li>
 *   <li>Key operations: {@code keyExists}, {@code deleteKeys}, {@code findKeysByPattern}</li>
 * </ul>
 */
public interface RedisClientAdapter {

    /**
     * Set a string value.
     *
     * @param key the Redis key
     * @param value the string value
     */
    void set(String key, String value);

    /**
     * Get a string value.
     *
     * @param key the Redis key
     * @return the string value, or null if not found
     */
    String get(String key);

    /**
     * Append a value to the right end of a list.
     *
     * @param key the Redis list key
     * @param value the value to append
     */
    void rightPushList(String key, String value);

    /**
     * Get a range of elements from a list.
     *
     * @param key the Redis list key
     * @param start the start index (inclusive)
     * @param end the end index (inclusive, -1 for all elements)
     * @return list of values
     */
    List<String> rangeList(String key, long start, long end);

    /**
     * Get the length of a list.
     *
     * @param key the Redis list key
     * @return the length of the list
     */
    long getListLength(String key);

    /**
     * Delete one or more keys.
     *
     * @param keys the keys to delete
     */
    void deleteKeys(String... keys);

    /**
     * Add a member to a set.
     *
     * @param key the Redis set key
     * @param member the member to add
     */
    void addToSet(String key, String member);

    /**
     * Get all members of a set.
     *
     * @param key the Redis set key
     * @return set of members
     */
    Set<String> getSetMembers(String key);

    /**
     * Get the number of members in a set.
     *
     * @param key the Redis set key
     * @return the number of members
     */
    long getSetSize(String key);

    /**
     * Check if a key exists.
     *
     * @param key the Redis key
     * @return true if the key exists
     */
    boolean keyExists(String key);

    /**
     * Find all keys matching a pattern.
     *
     * @param pattern the key pattern (e.g., "prefix:*")
     * @return set of matching keys
     */
    Set<String> findKeysByPattern(String pattern);

    /**
     * Close the adapter and release resources.
     */
    void close();
}
