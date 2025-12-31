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
package io.agentscope.core.session.redis.jedis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.session.ListHashUtil;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.State;
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
 * <p>This implementation stores session state in Redis with the following key structure:
 *
 * <ul>
 *   <li>Single state: {@code {prefix}{sessionId}:{stateKey}} - Redis String containing JSON
 *   <li>List state: {@code {prefix}{sessionId}:{stateKey}:list} - Redis List containing JSON items
 *   <li>Session marker: {@code {prefix}{sessionId}:_keys} - Redis Set tracking all state keys
 * </ul>
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Incremental list storage (only appends new items)
 *   <li>Type-safe state serialization using Jackson
 *   <li>Automatic session key tracking
 * </ul>
 */
public class JedisSession implements Session {

    private static final String DEFAULT_KEY_PREFIX = "agentscope:session:";
    private static final String KEYS_SUFFIX = ":_keys";
    private static final String LIST_SUFFIX = ":list";
    private static final String HASH_SUFFIX = ":_hash";

    private final JedisPool jedisPool;
    private final String keyPrefix;
    private final ObjectMapper objectMapper;

    private JedisSession(Builder builder) {
        if (builder.keyPrefix == null || builder.keyPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("Key prefix cannot be null or empty");
        }
        if (builder.jedisPool == null) {
            throw new IllegalArgumentException("JedisPool cannot be null");
        }
        this.keyPrefix = builder.keyPrefix;
        this.jedisPool = builder.jedisPool;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Creates a new builder for {@link JedisSession}.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void save(SessionKey sessionKey, String key, State value) {
        String sessionId = sessionKey.toIdentifier();
        String redisKey = getStateKey(sessionId, key);
        String keysKey = getKeysKey(sessionId);

        try (Jedis jedis = jedisPool.getResource()) {
            String json = objectMapper.writeValueAsString(value);
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
     * @param sessionKey the session identifier
     * @param key the state key (e.g., "memory_messages")
     * @param values the list of state values to save
     */
    @Override
    public void save(SessionKey sessionKey, String key, List<? extends State> values) {
        String sessionId = sessionKey.toIdentifier();
        String listKey = getListKey(sessionId, key);
        String hashKey = listKey + HASH_SUFFIX;
        String keysKey = getKeysKey(sessionId);

        try (Jedis jedis = jedisPool.getResource()) {
            // Compute current hash
            String currentHash = ListHashUtil.computeHash(values);

            // Get stored hash
            String storedHash = jedis.get(hashKey);

            // Get current list length
            long existingCount = jedis.llen(listKey);

            // Determine if full rewrite is needed
            boolean needsFullRewrite =
                    ListHashUtil.needsFullRewrite(
                            currentHash, storedHash, values.size(), (int) existingCount);

            if (needsFullRewrite) {
                // Delete and recreate the list
                jedis.del(listKey);
                for (State item : values) {
                    String json = objectMapper.writeValueAsString(item);
                    jedis.rpush(listKey, json);
                }
            } else if (values.size() > existingCount) {
                // Incremental append
                List<? extends State> newItems = values.subList((int) existingCount, values.size());
                for (State item : newItems) {
                    String json = objectMapper.writeValueAsString(item);
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
    public <T extends State> Optional<T> get(SessionKey sessionKey, String key, Class<T> type) {
        String sessionId = sessionKey.toIdentifier();
        String redisKey = getStateKey(sessionId, key);

        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get(redisKey);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            throw new RuntimeException("Failed to get state: " + key, e);
        }
    }

    @Override
    public <T extends State> List<T> getList(SessionKey sessionKey, String key, Class<T> itemType) {
        String sessionId = sessionKey.toIdentifier();
        String redisKey = getListKey(sessionId, key);

        try (Jedis jedis = jedisPool.getResource()) {
            List<String> jsonList = jedis.lrange(redisKey, 0, -1);
            if (jsonList == null || jsonList.isEmpty()) {
                return List.of();
            }

            List<T> result = new ArrayList<>();
            for (String json : jsonList) {
                T item = objectMapper.readValue(json, itemType);
                result.add(item);
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get list: " + key, e);
        }
    }

    @Override
    public boolean exists(SessionKey sessionKey) {
        String sessionId = sessionKey.toIdentifier();
        String keysKey = getKeysKey(sessionId);

        try (Jedis jedis = jedisPool.getResource()) {
            // Session exists if it has any tracked keys
            return jedis.exists(keysKey) && jedis.scard(keysKey) > 0;
        } catch (Exception e) {
            throw new RuntimeException("Failed to check session existence: " + sessionId, e);
        }
    }

    @Override
    public void delete(SessionKey sessionKey) {
        String sessionId = sessionKey.toIdentifier();
        String keysKey = getKeysKey(sessionId);

        try (Jedis jedis = jedisPool.getResource()) {
            // Get all tracked keys for this session
            Set<String> trackedKeys = jedis.smembers(keysKey);

            if (trackedKeys != null && !trackedKeys.isEmpty()) {
                // Build list of actual Redis keys to delete
                Set<String> keysToDelete = new HashSet<>();
                keysToDelete.add(keysKey);

                for (String trackedKey : trackedKeys) {
                    if (trackedKey.endsWith(LIST_SUFFIX)) {
                        // It's a list key
                        String baseKey =
                                trackedKey.substring(0, trackedKey.length() - LIST_SUFFIX.length());
                        keysToDelete.add(getListKey(sessionId, baseKey));
                    } else {
                        // It's a single state key
                        keysToDelete.add(getStateKey(sessionId, trackedKey));
                    }
                }

                jedis.del(keysToDelete.toArray(new String[0]));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete session: " + sessionId, e);
        }
    }

    @Override
    public Set<SessionKey> listSessionKeys() {
        try (Jedis jedis = jedisPool.getResource()) {
            // Find all session key sets
            Set<String> keysKeys = jedis.keys(keyPrefix + "*" + KEYS_SUFFIX);

            Set<SessionKey> sessionKeys = new HashSet<>();
            for (String keysKey : keysKeys) {
                // Extract session ID from the keys key
                // Pattern: {prefix}{sessionId}:_keys
                String withoutPrefix = keysKey.substring(keyPrefix.length());
                String sessionId =
                        withoutPrefix.substring(0, withoutPrefix.length() - KEYS_SUFFIX.length());
                sessionKeys.add(SimpleSessionKey.of(sessionId));
            }
            return sessionKeys;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
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
     * Builder for {@link JedisSession}.
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

        public JedisSession build() {
            return new JedisSession(this);
        }
    }
}
