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
package io.agentscope.extensions.redis.state;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ListHashUtil;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.redis.state.jedis.JedisClientAdapter;
import io.agentscope.extensions.redis.state.lettuce.LettuceClientAdapter;
import io.agentscope.extensions.redis.state.redisson.RedissonClientAdapter;
import io.lettuce.core.RedisClient;
import io.lettuce.core.cluster.RedisClusterClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.redisson.api.RedissonClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import redis.clients.jedis.UnifiedJedis;

/**
 * Redis-based session implementation supporting multiple Redis clients.
 *
 * <p>This implementation provides a unified interface for Redis-based session storage, supporting
 * multiple Redis client implementations:
 *
 * <ul>
 *   <li>Jedis - Standalone, Cluster, Sentinel</li>
 *   <li>Lettuce - Standalone, Cluster, Sentinel</li>
 *   <li>Redisson - Standalone, Cluster, Sentinel, Master/Slave</li>
 * </ul>
 *
 * <p>The session state is stored in Redis with following key structure:
 *
 * <ul>
 *   <li>Single state: {@code {prefix}{sessionId}:{stateKey}} - Redis String containing JSON
 *   <li>List state: {@code {prefix}{sessionId}:{stateKey}:list} - Redis List containing JSON items
 *   <li>List hash: {@code {prefix}{sessionId}:{stateKey}:list:_hash} - Hash for change detection
 *   <li>AgentStateStore marker: {@code {prefix}{sessionId}:_keys} - Redis Set tracking all state keys
 * </ul>
 *
 * <p><strong>Jedis Usage Examples:</strong></p>
 *
 * <p>Jedis Standalone (using RedisClient):
 *
 * <pre>{@code
 * // Create Jedis RedisClient (new API)
 * RedisClient redisClient = RedisClient.create("redis://localhost:6379");
 *
 * // Build RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .jedisClient(redisClient)
 *     .build();
 * }</pre>
 *
 * <p>Jedis Cluster (using RedisClusterClient):
 *
 * <pre>{@code
 * // Create Jedis RedisClusterClient
 * Set<HostAndPort> nodes = new HashSet<>();
 * nodes.add(new HostAndPort("localhost", 7000));
 * nodes.add(new HostAndPort("localhost", 7001));
 * nodes.add(new HostAndPort("localhost", 7002));
 * RedisClusterClient redisClusterClient = RedisClusterClient.create(nodes);
 *
 * // Build RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .jedisClient(redisClusterClient)
 *     .build();
 * }</pre>
 *
 * <p>Jedis Sentinel (using RedisSentinelClient):
 *
 * <pre>{@code
 * // Create Jedis RedisSentinelClient
 * Set<String> sentinelNodes = new HashSet<>();
 * sentinelNodes.add("localhost:26379");
 * sentinelNodes.add("localhost:26380");
 * RedisSentinelClient redisSentinelClient = RedisSentinelClient.create("mymaster", sentinelNodes);
 *
 * // Build RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .jedisClient(redisSentinelClient)
 *     .build();
 * }</pre>
 *
 * <p><strong>Lettuce Usage Examples:</strong></p>
 *
 * <p>Lettuce Standalone:
 *
 * <pre>{@code
 * // Create Lettuce RedisClient
 * RedisClient redisClient = RedisClient.create("redis://localhost:6379");
 *
 * // Build RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .lettuceClient(redisClient)
 *     .build();
 * }</pre>
 *
 * <p>Lettuce Cluster:
 *
 * <pre>{@code
 * // Create Lettuce RedisClusterClient for cluster mode
 * RedisClusterClient clusterClient = RedisClusterClient.create(
 *     RedisURI.create("localhost", 7000));
 *
 * // Build RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .lettuceClusterClient(clusterClient)
 *     .build();
 * }</pre>
 *
 * <p>Lettuce Sentinel:
 *
 * <pre>{@code
 * // Create Lettuce RedisClient for sentinel
 * RedisURI sentinelUri = RedisURI.builder()
 *     .withSentinelMasterId("mymaster")
 *     .withSentinel("localhost", 26379)
 *     .withSentinel("localhost", 26380)
 *     .build();
 * RedisClient redisClient = RedisClient.create(sentinelUri);
 *
 * // Build RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .lettuceClient(redisClient)
 *     .build();
 * }</pre>
 *
 * <p><strong>Redisson Usage Example:</strong></p>
 *
 * <pre>{@code
 * // Create RedissonClient (configure as needed for your deployment mode)
 * Config config = new Config();
 * config.useSingleServer().setAddress("redis://localhost:6379");
 * // or for cluster: config.useClusterServers().addNodeAddress("redis://localhost:7000");
 * // or for sentinel: config.useSentinelServers().setMasterName("mymaster").addSentinelAddress("redis://localhost:26379");
 *
 * RedissonClient redissonClient = Redisson.create(config);
 *
 * // Build RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .redissonClient(redissonClient)
 *     .build();
 * }</pre>
 *
 * <p><strong>Custom Key Prefix Example:</strong></p>
 *
 * <pre>{@code
 * // Create Redis client
 * RedisClient redisClient = RedisClient.create("redis://localhost:6379");
 *
 * // Build RedisAgentStateStore with custom key prefix
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .jedisClient(redisClient)
 *     .keyPrefix("myapp:session:")
 *     .build();
 * }</pre>
 */
public class RedisAgentStateStore implements AgentStateStore {

    private static final String DEFAULT_KEY_PREFIX = "agentscope:session:";

    private static final String KEYS_SUFFIX = ":_keys";

    private static final String LIST_SUFFIX = ":list";

    private static final String HASH_SUFFIX = ":_hash";

    private final RedisClientAdapter client;

    private final String keyPrefix;

    private RedisAgentStateStore(Builder builder) {
        if (builder.client == null) {
            throw new IllegalArgumentException("Redis client cannot be null");
        }
        if (builder.keyPrefix == null || builder.keyPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("Key prefix cannot be null or empty");
        }
        this.client = builder.client;
        this.keyPrefix = builder.keyPrefix;
    }

    /**
     * Creates a new builder for {@link RedisAgentStateStore}.
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
            client.set(redisKey, json);
            // Track this key in the session's key set
            client.addToSet(keysKey, key);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save state: " + key, e);
        }
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        String slotId = slotId(userId, sessionId);
        String listKey = getListKey(slotId, key);
        String hashKey = listKey + HASH_SUFFIX;
        String keysKey = getKeysKey(slotId);
        try {
            // Compute current hash
            String currentHash = ListHashUtil.computeHash(values);
            // Get stored hash
            String storedHash = client.get(hashKey);
            // Get current list length
            long existingCount = client.getListLength(listKey);
            // Determine if full rewrite is needed
            boolean needsFullRewrite =
                    ListHashUtil.needsFullRewrite(values, storedHash, (int) existingCount);
            if (needsFullRewrite) {
                // Delete and recreate the list
                client.deleteKeys(listKey);
                for (State item : values) {
                    String json = JsonUtils.getJsonCodec().toJson(item);
                    client.rightPushList(listKey, json);
                }
            } else if (values.size() > existingCount) {
                // Incremental append
                List<? extends State> newItems = values.subList((int) existingCount, values.size());
                for (State item : newItems) {
                    String json = JsonUtils.getJsonCodec().toJson(item);
                    client.rightPushList(listKey, json);
                }
            }
            // else: no change, skip
            // Update hash
            client.set(hashKey, currentHash);
            // Track this key in the session's key set
            client.addToSet(keysKey, key + LIST_SUFFIX);
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
            String json = client.get(redisKey);
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
            List<String> jsonList = client.rangeList(redisKey, 0, -1);
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
        try {
            return client.keyExists(keysKey) && client.getSetSize(keysKey) > 0;
        } catch (Exception e) {
            throw new RuntimeException("Failed to check session existence: " + slotId, e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        String keysKey = getKeysKey(slotId);

        try {
            Set<String> trackedKeys = client.getSetMembers(keysKey);

            if (trackedKeys != null && !trackedKeys.isEmpty()) {
                Set<String> keysToDelete = new HashSet<>();
                keysToDelete.add(keysKey);

                for (String trackedKey : trackedKeys) {
                    if (trackedKey.endsWith(LIST_SUFFIX)) {
                        String baseKey =
                                trackedKey.substring(0, trackedKey.length() - LIST_SUFFIX.length());
                        keysToDelete.add(getListKey(slotId, baseKey));
                        keysToDelete.add(getListKey(slotId, baseKey) + HASH_SUFFIX);
                    } else {
                        keysToDelete.add(getStateKey(slotId, trackedKey));
                    }
                }

                client.deleteKeys(keysToDelete.toArray(new String[0]));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete session: " + slotId, e);
        }
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        String userSegment = normalizeUser(userId);
        try {
            // Pattern: {prefix}{userSegment}/{sessionId}:_keys
            String pattern = keyPrefix + userSegment + "/*" + KEYS_SUFFIX;
            Set<String> keysKeys = client.findKeysByPattern(pattern);
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

    /** Sentinel for {@code userId == null} (anonymous sessions). */
    private static final String ANON_USER = "__anon__";

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    /** Combine {@code (userId, sessionId)} into a single Redis slot identifier. */
    private static String slotId(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return normalizeUser(userId) + "/" + sessionId;
    }

    @Override
    public void close() {
        client.close();
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
                                Set<String> keys = client.findKeysByPattern(keyPrefix + "*");
                                if (!keys.isEmpty()) {
                                    client.deleteKeys(keys.toArray(new String[0]));
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
     * Builder for {@link RedisAgentStateStore}.
     *
     * <p>The builder supports multiple Redis client types. Only one client type should be set.
     *
     * <p>Supported client types:
     * <ul>
     *   <li>Jedis: {@link #jedisClient(UnifiedJedis)}
     *   <li>Lettuce Standalone/Sentinel: {@link #lettuceClient(RedisClient)}
     *   <li>Lettuce Cluster: {@link #lettuceClusterClient(RedisClusterClient)}
     *   <li>Redisson: {@link #redissonClient(RedissonClient)}
     *   <li>Custom: {@link #clientAdapter(RedisClientAdapter)}
     * </ul>
     */
    public static class Builder {

        private String keyPrefix = DEFAULT_KEY_PREFIX;

        private RedisClientAdapter client;

        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
            return this;
        }

        public Builder jedisClient(UnifiedJedis unifiedJedis) {
            this.client = JedisClientAdapter.of(unifiedJedis);
            return this;
        }

        public Builder lettuceClient(RedisClient redisClient) {
            this.client = LettuceClientAdapter.of(redisClient);
            return this;
        }

        public Builder lettuceClusterClient(RedisClusterClient redisClusterClient) {
            this.client = LettuceClientAdapter.of(redisClusterClient);
            return this;
        }

        public Builder redissonClient(RedissonClient redissonClient) {
            this.client = RedissonClientAdapter.of(redissonClient);
            return this;
        }

        public Builder clientAdapter(RedisClientAdapter clientAdapter) {
            this.client = clientAdapter;
            return this;
        }

        public RedisAgentStateStore build() {
            return new RedisAgentStateStore(this);
        }
    }
}
