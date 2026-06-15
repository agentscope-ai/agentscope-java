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

import io.agentscope.core.session.ListHashUtil;
import io.agentscope.core.session.Session;
import io.agentscope.core.session.redis.jedis.JedisClientAdapter;
import io.agentscope.core.session.redis.lettuce.LettuceClientAdapter;
import io.agentscope.core.session.redis.redisson.RedissonClientAdapter;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
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
 *   <li>Session marker: {@code {prefix}{sessionId}:_keys} - Redis Set tracking all state keys
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
 * // Build RedisSession
 * Session session = RedisSession.builder()
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
 * // Build RedisSession
 * Session session = RedisSession.builder()
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
 * // Build RedisSession
 * Session session = RedisSession.builder()
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
 * // Build RedisSession
 * Session session = RedisSession.builder()
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
 * // Build RedisSession
 * Session session = RedisSession.builder()
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
 * // Build RedisSession
 * Session session = RedisSession.builder()
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
 * // Build RedisSession
 * Session session = RedisSession.builder()
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
 * // Build RedisSession with custom key prefix
 * Session session = RedisSession.builder()
 *     .jedisClient(redisClient)
 *     .keyPrefix("myapp:session:")
 *     .build();
 * }</pre>
 */
public class RedisSession implements Session {

    private static final String DEFAULT_KEY_PREFIX = "agentscope:session:";

    private static final String KEYS_SUFFIX = ":_keys";

    private static final String LIST_SUFFIX = ":list";

    private static final String HASH_SUFFIX = ":_hash";

    private final RedisClientAdapter client;

    private final String keyPrefix;

    private RedisSession(Builder builder) {
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
     * Creates a new builder for {@link RedisSession}.
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
        try {
            String json = JsonUtils.getJsonCodec().toJson(value);
            client.set(redisKey, json);
            // Track this key in the session's key set
            client.addToSet(keysKey, key);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save state: " + key, e);
        }
    }

    /**
     * 保存 List 类型的状态到 Redis。
     *
     * <p>采用基于哈希的增量更新策略，分为三种情况:
     * <ol>
     *   <li><b>全量重写</b>: 列表缩短了 / 已有部分被修改 / 哈希丢失 → DEL + 逐条 RPUSH 重建</li>
     *   <li><b>增量追加</b>: 已有部分没变，仅尾部新增 → 只 RPUSH 新元素（最常见的热路径）</li>
     *   <li><b>跳过</b>: 列表完全没有变化 → 零 Redis IO</li>
     * </ol>
     *
     * <p>对应 Redis 数据结构:
     * <ul>
     *   <li>数据本身 → Redis List: {@code {prefix}{sessionId}:{key}:list}
     *      每个元素是 JSON 序列化的 State</li>
     *   <li>变更检测 → Redis String: {@code {prefix}{sessionId}:{key}:list:_hash}
     *      存储采样 hash 值，用于判断已有部分是否被修改</li>
     *   <li>Key 追踪 → Redis Set: {@code {prefix}{sessionId}:_keys}
     *      记录这个 session 下有哪些 key，用于 exists/delete/listSessions</li>
     * </ul>
     *
     * @param sessionKey session 标识
     * @param key 状态 key（如 "memory_msg"）
     * @param values 内存中完整的 List（如全部消息列表）
     */
    @Override
    public void save(SessionKey sessionKey, String key, List<? extends State> values) {
        String sessionId = sessionKey.toIdentifier();
        // Redis List key: agentscope:session:user123:memory_msg:list
        String listKey = getListKey(sessionId, key);
        // Hash key: agentscope:session:user123:memory_msg:list:_hash
        String hashKey = listKey + HASH_SUFFIX;
        // 元数据 Set key: agentscope:session:user123:_keys
        String keysKey = getKeysKey(sessionId);
        try {
            // 1. 对内存中的完整列表计算采样 hash
            String currentHash = ListHashUtil.computeHash(values);
            // 2. 从 Redis 读取上次保存时的 hash
            String storedHash = client.get(hashKey);
            // 3. 获取 Redis List 中已有的元素数量
            long existingCount = client.getListLength(listKey);
            // 4. 判断是否需要全量重写
            //    - 列表缩短(有删除) → true
            //    - 哈希丢失但数据还在 → true
            //    - prefix hash 变化(内部有修改) → true
            //    - prefix hash 一致且 size 增长 → false (增量追加)
            //    - prefix hash 一致且 size 相同 → false (跳过)
            boolean needsFullRewrite =
                    ListHashUtil.needsFullRewrite(values, storedHash, (int) existingCount);
            if (needsFullRewrite) {
                // ── 分支1: 全量重写 ──
                // DEL 删除整个 Redis List，然后逐条 RPUSH 重建
                client.deleteKeys(listKey);
                for (State item : values) {
                    String json = JsonUtils.getJsonCodec().toJson(item);
                    client.rightPushList(listKey, json);
                }
            } else if (values.size() > existingCount) {
                // ── 分支2: 增量追加（最热路径）──
                // 已有部分未变，只取尾部新增的元素
                // 例如: 内存 100 条, Redis 已有 98 条 → 只 RPUSH 第 99、100 条
                List<? extends State> newItems = values.subList((int) existingCount, values.size());
                for (State item : newItems) {
                    String json = JsonUtils.getJsonCodec().toJson(item);
                    client.rightPushList(listKey, json);
                }
            }
            // ── 分支3: size == existingCount，无变化，跳过 ──
            // 5. 更新 hash 值（SET），供下次 save 时比对
            client.set(hashKey, currentHash);
            // 6. 在 _keys Set 中记录这个 key 的存在（SADD）
            //    存的是逻辑 key + 后缀，如 "memory_msg:list"
            //    delete 时靠 endsWith(":list") 区分单值和列表，反推实际 Redis key
            client.addToSet(keysKey, key + LIST_SUFFIX);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save list: " + key, e);
        }
    }

    @Override
    public <T extends State> Optional<T> get(SessionKey sessionKey, String key, Class<T> type) {
        String sessionId = sessionKey.toIdentifier();
        String redisKey = getStateKey(sessionId, key);
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
    public <T extends State> List<T> getList(SessionKey sessionKey, String key, Class<T> itemType) {
        String sessionId = sessionKey.toIdentifier();
        String redisKey = getListKey(sessionId, key);
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
    public boolean exists(SessionKey sessionKey) {
        String sessionId = sessionKey.toIdentifier();
        String keysKey = getKeysKey(sessionId);

        try {
            // Session exists if it has any tracked keys
            return client.keyExists(keysKey) && client.getSetSize(keysKey) > 0;
        } catch (Exception e) {
            throw new RuntimeException("Failed to check session existence: " + sessionId, e);
        }
    }

    @Override
    public void delete(SessionKey sessionKey) {
        String sessionId = sessionKey.toIdentifier();
        String keysKey = getKeysKey(sessionId);

        try {
            // Get all tracked keys for this session
            Set<String> trackedKeys = client.getSetMembers(keysKey);

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
                        keysToDelete.add(getListKey(sessionId, baseKey) + HASH_SUFFIX);
                    } else {
                        // It's a single state key
                        keysToDelete.add(getStateKey(sessionId, trackedKey));
                    }
                }

                client.deleteKeys(keysToDelete.toArray(new String[0]));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete session: " + sessionId, e);
        }
    }

    @Override
    public Set<SessionKey> listSessionKeys() {
        try {
            Set<String> keysKeys = client.findKeysByPattern(keyPrefix + "*" + KEYS_SUFFIX);
            // Find all session key sets
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
     * Builder for {@link RedisSession}.
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

        public RedisSession build() {
            return new RedisSession(this);
        }
    }
}
