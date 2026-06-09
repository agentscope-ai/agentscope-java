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
package io.agentscope.extensions.redis.state.jedis;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ListHashUtil;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Redis-based session implementation using Jedis.
 *
 * @deprecated Use {@link io.agentscope.extensions.redis.state.RedisAgentStateStore} with jedisClient instead.
 * RedisAgentStateStore provides a unified session implementation that works with multiple Redis clients.
 * For basic usage, simple constructors are available.
 * For advanced configuration, use {@link RedisAgentStateStore#builder()}.
 *
 * <p>This implementation stores session state in Redis with the following key structure:
 *
 * <ul>
 *   <li>Single state: {@code {prefix}{sessionId}:{stateKey}} - Redis String containing JSON
 *   <li>List state: {@code {prefix}{sessionId}:{stateKey}:list} - Redis List containing JSON items
 *   <li>AgentStateStore marker: {@code {prefix}{sessionId}:_keys} - Redis Set tracking all state keys
 * </ul>
 *
 * <ul>
 *   <li>Incremental list storage (only appends new items)
 *   <li>Type-safe state serialization using Jackson
 *   <li>Automatic session key tracking
 * </ul>
 */
@Deprecated
public class JedisAgentStateStore implements AgentStateStore {

    private static final String DEFAULT_KEY_PREFIX = "agentscope:session:";
    private static final String KEYS_SUFFIX = ":_keys";
    private static final String LIST_SUFFIX = ":list";
    private static final String HASH_SUFFIX = ":_hash";

    private final JedisPool jedisPool;
    private final String keyPrefix;

    private JedisAgentStateStore(Builder builder) {
        if (builder.keyPrefix == null || builder.keyPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("Key prefix cannot be null or empty");
        }
        if (builder.jedisPool == null) {
            throw new IllegalArgumentException("JedisPool cannot be null");
        }
        this.keyPrefix = builder.keyPrefix;
        this.jedisPool = builder.jedisPool;
    }

    /**
     * Creates a new builder for {@link JedisAgentStateStore}.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        String slotId = slotId(userId, sessionId);
        String redisKey = getStateKey(slotId, key);
        String keysKey = getKeysKey(slotId);

        try (Jedis jedis = jedisPool.getResource()) {
            String json = JsonUtils.getJsonCodec().toJson(value);
            jedis.set(redisKey, json);
            // Track this key in the session's key set
            jedis.sadd(keysKey, key);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save state: " + key, e);
        }
    }

    /**
     * Save a list of state values with hash-based change detection.
     *
     * <p>This method uses hash-based change detection to handle both append-only and mutable lists:
     *
     * <ul>
     *   <li>If the hash changes (list was modified), the Redis list is deleted and recreated
     *   <li>If the list shrinks, the Redis list is deleted and recreated
     *   <li>If the list only grows (append-only), only new items are appended
     *   <li>If nothing changes, the operation is skipped
     * </ul>
     *
     * @param userId the user identifier
     * @param sessionId the session identifier
     * @param key the state key (e.g., "memory_messages")
     * @param values the list of state values to save
     */
    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        String slotId = slotId(userId, sessionId);
        String listKey = getListKey(slotId, key);
        String hashKey = listKey + HASH_SUFFIX;
        String keysKey = getKeysKey(slotId);

        try (Jedis jedis = jedisPool.getResource()) {
            // Compute current hash
            String currentHash = ListHashUtil.computeHash(values);

            // Get stored hash
            String storedHash = jedis.get(hashKey);

            // Get current list length
            long existingCount = jedis.llen(listKey);

            // Determine if full rewrite is needed
            boolean needsFullRewrite =
                    ListHashUtil.needsFullRewrite(values, storedHash, (int) existingCount);

            if (needsFullRewrite) {
                // Delete and recreate the list
                jedis.del(listKey);
                for (State item : values) {
                    String json = JsonUtils.getJsonCodec().toJson(item);
                    jedis.rpush(listKey, json);
                }
            } else if (values.size() > existingCount) {
                // Incremental append
                List<? extends State> newItems = values.subList((int) existingCount, values.size());
                for (State item : newItems) {
                    String json = JsonUtils.getJsonCodec().toJson(item);
                    jedis.rpush(listKey, json);
                }
            }
            // else: no change, skip

            // Update hash
            jedis.set(hashKey, currentHash);

            // Track this key in the session's key set
            jedis.sadd(keysKey, key + LIST_SUFFIX);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save list: " + key, e);
        }
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        String slotId = slotId(userId, sessionId);
        String redisKey = getStateKey(slotId, key);

        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get(redisKey);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(JsonUtils.getJsonCodec().fromJson(json, type));
        } catch (Exception e) {
            throw new RuntimeException("Failed to get state: " + key, e);
        }
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        String slotId = slotId(userId, sessionId);
        String redisKey = getListKey(slotId, key);

        try (Jedis jedis = jedisPool.getResource()) {
            List<String> jsonList = jedis.lrange(redisKey, 0, -1);
            if (jsonList == null || jsonList.isEmpty()) {
                return List.of();
            }

            List<T> result = new ArrayList<>();
            for (String json : jsonList) {
                T item = JsonUtils.getJsonCodec().fromJson(json, itemType);
                result.add(item);
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get list: " + key, e);
        }
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        String keysKey = getKeysKey(slotId);

        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.exists(keysKey) && jedis.scard(keysKey) > 0;
        } catch (Exception e) {
            throw new RuntimeException("Failed to check session existence: " + slotId, e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        String keysKey = getKeysKey(slotId);

        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> trackedKeys = jedis.smembers(keysKey);

            if (trackedKeys != null && !trackedKeys.isEmpty()) {
                Set<String> keysToDelete = new HashSet<>();
                keysToDelete.add(keysKey);

                for (String trackedKey : trackedKeys) {
                    if (trackedKey.endsWith(LIST_SUFFIX)) {
                        String baseKey =
                                trackedKey.substring(0, trackedKey.length() - LIST_SUFFIX.length());
                        keysToDelete.add(getListKey(slotId, baseKey));
                    } else {
                        keysToDelete.add(getStateKey(slotId, trackedKey));
                    }
                }

                jedis.del(keysToDelete.toArray(new String[0]));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete session: " + slotId, e);
        }
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        String userSegment = normalizeUser(userId);
        try (Jedis jedis = jedisPool.getResource()) {
            String pattern = keyPrefix + userSegment + "/*" + KEYS_SUFFIX;
            Set<String> keysKeys = jedis.keys(pattern);
            Set<String> sessionIds = new HashSet<>();
            String userPrefix = keyPrefix + userSegment + "/";
            for (String keysKey : keysKeys) {
                String withoutPrefix = keysKey.substring(userPrefix.length());
                String sessionId =
                        withoutPrefix.substring(0, withoutPrefix.length() - KEYS_SUFFIX.length());
                sessionIds.add(sessionId);
            }
            return sessionIds;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    private static final String ANON_USER = "__anon__";

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    private static String slotId(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return normalizeUser(userId) + "/" + sessionId;
    }

    @Override
    public void close() {
        jedisPool.close();
    }

    /**
     * Clear all sessions stored in Redis (for testing or cleanup).
     *
     * @return Mono that completes with the number of deleted session keys
     */
    public Mono<Integer> clearAllSessions() {
        return Mono.fromSupplier(
                        () -> {
                            try (Jedis jedis = jedisPool.getResource()) {
                                Set<String> keys = jedis.keys(keyPrefix + "*");
                                if (!keys.isEmpty()) {
                                    jedis.del(keys.toArray(new String[0]));
                                }
                                return keys.size();
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to clear sessions", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Get the Redis key for a single state value.
     *
     * @param sessionId the session ID
     * @param key the state key
     * @return Redis key in format {prefix}{sessionId}:{key}
     */
    private String getStateKey(String sessionId, String key) {
        return keyPrefix + sessionId + ":" + key;
    }

    /**
     * Get the Redis key for a list state value.
     *
     * @param sessionId the session ID
     * @param key the state key
     * @return Redis key in format {prefix}{sessionId}:{key}:list
     */
    private String getListKey(String sessionId, String key) {
        return keyPrefix + sessionId + ":" + key + LIST_SUFFIX;
    }

    /**
     * Get the Redis key for tracking session keys.
     *
     * @param sessionId the session ID
     * @return Redis key in format {prefix}{sessionId}:_keys
     */
    private String getKeysKey(String sessionId) {
        return keyPrefix + sessionId + KEYS_SUFFIX;
    }

    /**
     * Builder for {@link JedisAgentStateStore}.
     */
    public static class Builder {

        private String keyPrefix = DEFAULT_KEY_PREFIX;
        private JedisPool jedisPool;

        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
            return this;
        }

        public Builder jedisPool(JedisPool jedisPool) {
            this.jedisPool = jedisPool;
            return this;
        }

        public JedisAgentStateStore build() {
            return new JedisAgentStateStore(this);
        }
    }
}
