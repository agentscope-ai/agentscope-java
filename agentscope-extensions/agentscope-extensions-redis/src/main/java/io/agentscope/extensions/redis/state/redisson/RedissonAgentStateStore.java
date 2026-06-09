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
package io.agentscope.extensions.redis.state.redisson;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RList;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Redis-based session implementation using Redisson.
 *
 * @deprecated Use {@link io.agentscope.extensions.redis.state.RedisAgentStateStore} with redissonClient instead.
 * RedisAgentStateStore provides a unified session implementation that works with multiple Redis clients.
 * For basic usage, simple constructors are available.
 * For advanced configuration, use {@link RedisAgentStateStore#builder()}
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
public class RedissonAgentStateStore implements AgentStateStore {

    private static final String DEFAULT_KEY_PREFIX = "agentscope:session:";
    private static final String KEYS_SUFFIX = ":_keys";
    private static final String LIST_SUFFIX = ":list";

    private final RedissonClient redissonClient;
    private final String keyPrefix;

    private RedissonAgentStateStore(Builder builder) {
        if (builder.keyPrefix == null || builder.keyPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("Key prefix cannot be null or empty");
        }
        if (builder.redissonClient == null) {
            throw new IllegalArgumentException("RedissonClient cannot be null");
        }
        this.keyPrefix = builder.keyPrefix;
        this.redissonClient = builder.redissonClient;
    }

    /**
     * Creates a new builder for {@link RedissonAgentStateStore}.
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

        try {
            String json = JsonUtils.getJsonCodec().toJson(value);

            RBucket<String> bucket = redissonClient.getBucket(redisKey, StringCodec.INSTANCE);
            bucket.set(json);

            // Track this key in the session's key set
            RSet<String> keysSet = redissonClient.getSet(keysKey, StringCodec.INSTANCE);
            keysSet.add(key);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save state: " + key, e);
        }
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        String slotId = slotId(userId, sessionId);
        String redisKey = getListKey(slotId, key);
        String keysKey = getKeysKey(slotId);

        try {
            RList<String> rList = redissonClient.getList(redisKey, StringCodec.INSTANCE);

            // Get current list length to support incremental append
            int existingCount = rList.size();

            // Only append new items
            if (values.size() > existingCount) {
                List<? extends State> newItems = values.subList(existingCount, values.size());

                for (State item : newItems) {
                    String json = JsonUtils.getJsonCodec().toJson(item);
                    rList.add(json);
                }
            }

            // Track this key in the session's key set
            RSet<String> keysSet = redissonClient.getSet(keysKey, StringCodec.INSTANCE);
            keysSet.add(key + LIST_SUFFIX);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save list: " + key, e);
        }
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        String slotId = slotId(userId, sessionId);
        String redisKey = getStateKey(slotId, key);

        try {
            RBucket<String> bucket = redissonClient.getBucket(redisKey, StringCodec.INSTANCE);
            String json = bucket.get();

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

        try {
            RList<String> rList = redissonClient.getList(redisKey, StringCodec.INSTANCE);

            if (rList.isEmpty()) {
                return List.of();
            }

            List<T> result = new ArrayList<>();
            for (String json : rList) {
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

        try {
            RSet<String> keysSet = redissonClient.getSet(keysKey, StringCodec.INSTANCE);
            return keysSet.isExists() && keysSet.size() > 0;
        } catch (Exception e) {
            throw new RuntimeException("Failed to check session existence: " + slotId, e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        String keysKey = getKeysKey(slotId);

        try {
            RSet<String> keysSet = redissonClient.getSet(keysKey, StringCodec.INSTANCE);
            Set<String> trackedKeys = keysSet.readAll();

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

                RKeys keys = redissonClient.getKeys();
                keys.delete(keysToDelete.toArray(new String[0]));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete session: " + slotId, e);
        }
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        String userSegment = normalizeUser(userId);
        try {
            RKeys keys = redissonClient.getKeys();
            String pattern = keyPrefix + userSegment + "/*" + KEYS_SUFFIX;
            Iterable<String> keysIterable = keys.getKeysByPattern(pattern);

            Set<String> sessionIds = new HashSet<>();
            String userPrefix = keyPrefix + userSegment + "/";
            for (String keysKey : keysIterable) {
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

    @Override
    public void close() {
        redissonClient.shutdown();
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

    /**
     * Clear all sessions stored in Redis (for testing or cleanup).
     *
     * @return Mono that completes with the number of deleted session keys
     */
    public Mono<Integer> clearAllSessions() {
        return Mono.fromSupplier(
                        () -> {
                            try {
                                RKeys keys = redissonClient.getKeys();
                                Iterable<String> keyIterable =
                                        keys.getKeysByPattern(keyPrefix + "*");

                                List<String> keysToDelete = new ArrayList<>();
                                for (String key : keyIterable) {
                                    keysToDelete.add(key);
                                }

                                if (!keysToDelete.isEmpty()) {
                                    keys.delete(keysToDelete.toArray(new String[0]));
                                }

                                return keysToDelete.size();
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
     * Builder for {@link RedissonAgentStateStore}.
     */
    public static class Builder {

        private String keyPrefix = DEFAULT_KEY_PREFIX;
        private RedissonClient redissonClient;

        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
            return this;
        }

        public Builder redissonClient(RedissonClient redissonClient) {
            this.redissonClient = redissonClient;
            return this;
        }

        public RedissonAgentStateStore build() {
            return new RedissonAgentStateStore(this);
        }
    }
}
