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
package io.agentscope.extensions.redis.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import redis.clients.jedis.UnifiedJedis;

/**
 * Redis-backed {@link BaseStore} implementation.
 *
 * <h2>Key layout</h2>
 *
 * <p>For each item with namespace {@code [a, b, c]} and key {@code k}, two Redis keys are used:
 *
 * <ul>
 *   <li><b>Item hash</b> {@code <prefix>item:<ns>\0<k>} — a Redis hash with fields {@code value}
 *       (JSON-encoded {@code Map<String,Object>}) and {@code version} (a stringified long).
 *   <li><b>Namespace index</b> {@code <prefix>idx:<ns>} — a sorted set (all scores {@code 0})
 *       holding every {@code k} written under that exact namespace, enabling lexicographic
 *       {@link #search} via {@code ZRANGEBYLEX} without scanning the keyspace.
 * </ul>
 *
 * <p>{@code <ns>} is the namespace components joined with {@code "\0"}.
 *
 * <h2>Concurrency</h2>
 *
 * <p>{@link #put} and {@link #putIfVersion} both run as a single Lua {@code EVAL}, making the
 * version read + hash write + index update one atomic Redis operation. This makes
 * {@link #putIfVersion} safe to use as a distributed compare-and-swap primitive across multiple
 * processes sharing the same Redis instance.
 *
 * <p>{@link #search} is <em>not</em> in the same transaction as {@link #put}: index members may
 * temporarily refer to an item whose hash has not yet been written (window between {@code ZADD}
 * and {@code HSET}, which is closed by the Lua atomicity above) or to an item that was
 * concurrently deleted. The implementation tolerates the latter by skipping missing items.
 */
public class RedisStore implements BaseStore {

    /** Default Redis key prefix; pick something distinctive enough not to collide with other apps. */
    public static final String DEFAULT_KEY_PREFIX = "agentscope:store:";

    private static final String NS_SEPARATOR = "\0";

    /**
     * Atomic put: increments the version unconditionally, writes value + version + index entry.
     * Returns the new version as a string.
     */
    private static final String PUT_SCRIPT =
            "local v = tonumber(redis.call('HGET', KEYS[1], 'version') or '0') + 1 "
                    + "redis.call('HSET', KEYS[1], 'value', ARGV[1], 'version', tostring(v)) "
                    + "redis.call('ZADD', KEYS[2], 0, ARGV[2]) "
                    + "return tostring(v)";

    /**
     * Atomic CAS put: writes only when the stored version equals ARGV[3]. Returns the new version
     * on success, the literal string {@code "0"} on version mismatch. An expected version of
     * {@code 0} means "create only if absent".
     */
    private static final String PUT_IF_VERSION_SCRIPT =
            "local cur = tonumber(redis.call('HGET', KEYS[1], 'version') or '0') "
                    + "if cur ~= tonumber(ARGV[3]) then return '0' end "
                    + "local v = cur + 1 "
                    + "redis.call('HSET', KEYS[1], 'value', ARGV[1], 'version', tostring(v)) "
                    + "redis.call('ZADD', KEYS[2], 0, ARGV[2]) "
                    + "return tostring(v)";

    /** Atomic delete: removes both the item hash and its index membership. */
    private static final String DELETE_SCRIPT =
            "redis.call('DEL', KEYS[1]) " + "redis.call('ZREM', KEYS[2], ARGV[1]) " + "return 1";

    private final UnifiedJedis jedis;
    private final String keyPrefix;
    private final ObjectMapper objectMapper;

    /**
     * Creates a Redis-backed store with the {@linkplain #DEFAULT_KEY_PREFIX default key prefix}.
     *
     * @param jedis initialized Jedis client; must not be {@code null}
     */
    public RedisStore(UnifiedJedis jedis) {
        this(jedis, DEFAULT_KEY_PREFIX, new ObjectMapper());
    }

    /**
     * Creates a Redis-backed store.
     *
     * @param jedis initialized Jedis client; must not be {@code null}
     * @param keyPrefix Redis key prefix; trailing {@code ":"} is added if absent. If blank, the
     *     {@linkplain #DEFAULT_KEY_PREFIX default} is used.
     */
    public RedisStore(UnifiedJedis jedis, String keyPrefix) {
        this(jedis, keyPrefix, new ObjectMapper());
    }

    /**
     * Creates a Redis-backed store with a custom JSON mapper.
     *
     * @param jedis initialized Jedis client; must not be {@code null}
     * @param keyPrefix Redis key prefix; trailing {@code ":"} is added if absent
     * @param objectMapper Jackson mapper used to serialize / deserialize item values
     */
    public RedisStore(UnifiedJedis jedis, String keyPrefix, ObjectMapper objectMapper) {
        this.jedis = Objects.requireNonNull(jedis, "jedis must not be null");
        this.keyPrefix = normalizePrefix(keyPrefix);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public StoreItem get(List<String> namespace, String key) {
        validateKey(key);
        String itemKey = itemKey(namespace, key);
        Map<String, String> hash = jedis.hgetAll(itemKey);
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        return toItem(key, hash);
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        validateKey(key);
        String itemKey = itemKey(namespace, key);
        String idxKey = indexKey(namespace);
        String json = serialize(value);
        jedis.eval(PUT_SCRIPT, List.of(itemKey, idxKey), List.of(json, key));
    }

    @Override
    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        validateKey(key);
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
        String itemKey = itemKey(namespace, key);
        String idxKey = indexKey(namespace);
        String json = serialize(value);
        Object result =
                jedis.eval(
                        PUT_IF_VERSION_SCRIPT,
                        List.of(itemKey, idxKey),
                        List.of(json, key, Long.toString(expectedVersion)));
        return !"0".equals(asString(result));
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        if (limit <= 0) {
            return List.of();
        }
        int safeOffset = Math.max(offset, 0);
        String idxKey = indexKey(namespace);
        List<String> keys = jedis.zrangeByLex(idxKey, "-", "+", safeOffset, limit);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<StoreItem> items = new ArrayList<>(keys.size());
        for (String k : keys) {
            Map<String, String> hash = jedis.hgetAll(itemKey(namespace, k));
            if (hash == null || hash.isEmpty()) {
                continue; // stale index entry — tolerated
            }
            items.add(toItem(k, hash));
        }
        return items;
    }

    @Override
    public void delete(List<String> namespace, String key) {
        validateKey(key);
        String itemKey = itemKey(namespace, key);
        String idxKey = indexKey(namespace);
        jedis.eval(DELETE_SCRIPT, List.of(itemKey, idxKey), List.of(key));
    }

    // -------------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------------

    private StoreItem toItem(String key, Map<String, String> hash) {
        String json = hash.get("value");
        String versionStr = hash.get("version");
        long version = 0L;
        if (versionStr != null) {
            try {
                version = Long.parseLong(versionStr);
            } catch (NumberFormatException ignored) {
                // leave version as 0 — caller treats this as "unknown"
            }
        }
        Map<String, Object> value = deserialize(json);
        return new StoreItem(key, value, version);
    }

    private String serialize(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to JSON-encode store value", e);
        }
    }

    private Map<String, Object> deserialize(String json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to JSON-decode store value", e);
        }
    }

    private String itemKey(List<String> namespace, String key) {
        return keyPrefix + "{" + namespacePath(namespace) + "}:item:" + NS_SEPARATOR + key;
    }

    private String indexKey(List<String> namespace) {
        return keyPrefix + "{" + namespacePath(namespace) + "}:idx";
    }

    private static String namespacePath(List<String> namespace) {
        Objects.requireNonNull(namespace, "namespace must not be null");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < namespace.size(); i++) {
            String segment = namespace.get(i);
            if (segment == null) {
                throw new IllegalArgumentException("namespace segment must not be null");
            }
            if (i > 0) {
                sb.append(NS_SEPARATOR);
            }
            sb.append(segment);
        }
        return sb.toString();
    }

    private static void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key must not be null or empty");
        }
        if (key.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("key must not contain the NUL character");
        }
    }

    private static String asString(Object eval) {
        if (eval == null) {
            return null;
        }
        if (eval instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return eval.toString();
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return DEFAULT_KEY_PREFIX;
        }
        return prefix.endsWith(":") ? prefix : prefix + ":";
    }
}
