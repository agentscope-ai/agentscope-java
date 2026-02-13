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

import io.agentscope.core.session.redis.RedisClientAdapter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.RedisClusterClient;
import redis.clients.jedis.RedisSentinelClient;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/**
 * Adapter for Jedis Redis client.
 *
 * <p>This adapter supports all Jedis client types:
 * <ul>
 *   <li>{@link RedisClient} - Standalone client</li>
 *   <li>{@link RedisClusterClient} - Cluster client</li>
 *   <li>{@link RedisSentinelClient} - Sentinel client</li>
 *   <li>{@link UnifiedJedis} - Base unified interface</li>
 * </ul>
 *
 * <p>All new client types extend {@link UnifiedJedis}, providing a unified interface that
 * handles different Redis deployment modes transparently.
 *
 * <p>Usage examples:
 *
 * <p>Using RedisClient (Standalone):
 * <pre>{@code
 * // Create standalone client
 * RedisClient redisClient = RedisClient.create("redis://localhost:6379");
 *
 * // Create adapter
 * JedisClientAdapter adapter = JedisClientAdapter.of(redisClient);
 * }</pre>
 *
 * <p>Using RedisClusterClient (Cluster):
 * <pre>{@code
 * // Create cluster client
 * Set<HostAndPort> nodes = new HashSet<>();
 * nodes.add(new HostAndPort("localhost", 7000));
 * nodes.add(new HostAndPort("localhost", 7001));
 * RedisClusterClient clusterClient = RedisClusterClient.create(nodes);
 *
 * // Create adapter
 * JedisClientAdapter adapter = JedisClientAdapter.of(clusterClient);
 * }</pre>
 *
 * <p>Using RedisSentinelClient (Sentinel):
 * <pre>{@code
 * // Create sentinel client
 * Set<String> sentinels = new HashSet<>();
 * sentinels.add("localhost:26379");
 * RedisSentinelClient sentinelClient = RedisSentinelClient.create("mymaster", sentinels);
 *
 * // Create adapter
 * JedisClientAdapter adapter = JedisClientAdapter.of(sentinelClient);
 * }</pre>
 *
 * <p>Using UnifiedJedis (Direct):
 * <pre>{@code
 * // Create any client type that extends UnifiedJedis
 * UnifiedJedis unifiedJedis = new RedisClient("redis://localhost:6379");
 *
 * // Create adapter
 * JedisClientAdapter adapter = JedisClientAdapter.of(unifiedJedis);
 * }</pre>
 */
public class JedisClientAdapter implements RedisClientAdapter {

    private final UnifiedJedis unifiedJedis;

    private JedisClientAdapter(UnifiedJedis unifiedJedis) {
        this.unifiedJedis = unifiedJedis;
    }

    /**
     * Create adapter from UnifiedJedis.
     *
     * @param unifiedJedis the UnifiedJedis instance (any subclass)
     * @return a new JedisClientAdapter
     */
    public static JedisClientAdapter of(UnifiedJedis unifiedJedis) {
        return new JedisClientAdapter(unifiedJedis);
    }

    @Override
    public void set(String key, String value) {
        unifiedJedis.set(key, value);
    }

    @Override
    public String get(String key) {
        return unifiedJedis.get(key);
    }

    @Override
    public void rightPushList(String key, String value) {
        unifiedJedis.rpush(key, value);
    }

    @Override
    public List<String> rangeList(String key, long start, long end) {
        return unifiedJedis.lrange(key, start, end);
    }

    @Override
    public long getListLength(String key) {
        return unifiedJedis.llen(key);
    }

    @Override
    public void deleteKeys(String... keys) {
        unifiedJedis.del(keys);
    }

    @Override
    public void addToSet(String key, String member) {
        unifiedJedis.sadd(key, member);
    }

    @Override
    public Set<String> getSetMembers(String key) {
        return unifiedJedis.smembers(key);
    }

    @Override
    public long getSetSize(String key) {
        return unifiedJedis.scard(key);
    }

    @Override
    public boolean keyExists(String key) {
        return unifiedJedis.exists(key);
    }

    @Override
    public Set<String> findKeysByPattern(String pattern) {
        Set<String> matchingKeys = new HashSet<>();
        String cursor = ScanParams.SCAN_POINTER_START;
        ScanParams scanParams = new ScanParams().match(pattern);
        do {
            ScanResult<String> scanResult = unifiedJedis.scan(cursor, scanParams);
            if (scanResult != null) {
                matchingKeys.addAll(scanResult.getResult());
                cursor = scanResult.getCursor();
            } else {
                break;
            }
        } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
        return matchingKeys;
    }

    @Override
    public void close() {
        unifiedJedis.close();
    }
}
