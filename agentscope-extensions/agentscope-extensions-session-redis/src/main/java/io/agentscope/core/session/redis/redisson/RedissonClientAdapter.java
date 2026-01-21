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
package io.agentscope.core.session.redis.redisson;

import io.agentscope.core.session.redis.RedisClientAdapter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RList;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.client.codec.StringCodec;

/**
 * Adapter for Redisson Redis client.
 *
 * <p>Redisson provides a comprehensive Redis client that automatically handles different deployment
 * modes through a unified {@link RedissonClient} interface.
 *
 * <p>This adapter supports all Redisson deployment modes:
 * <ul>
 *   <li>Standalone mode - single Redis instance</li>
 *   <li>Cluster mode - Redis Cluster with multiple nodes</li>
 *   <li>Sentinel mode - Redis with Sentinel for high availability</li>
 *   <li>Master/Slave mode - Redis with master-slave replication</li>
 * </ul>
 *
 * <p>RedissonClient manages connection pooling, thread safety, and mode-specific logic internally,
 * providing a seamless experience across all deployment modes.
 *
 * <p>Usage Examples:
 *
 * <p>Standalone Mode:
 * <pre>{@code
 * // Create standalone configuration
 * Config config = new Config();
 * config.useSingleServer()
 *     .setAddress("redis://localhost:6379")
 *     .setConnectionMinimumIdleSize(5)
 *     .setConnectionPoolSize(20);
 *
 * // Create RedissonClient
 * RedissonClient redissonClient = Redisson.create(config);
 *
 * // Create adapter
 * RedissonClientAdapter adapter = RedissonClientAdapter.of(redissonClient);
 *
 * // Use with RedisSession
 * Session session = RedisSession.builder()
 *     .redissonClient(redissonClient)
 *     .build();
 * }</pre>
 *
 * <p>Cluster Mode:
 * <pre>{@code
 * // Create cluster configuration
 * Config config = new Config();
 * config.useClusterServers()
 *     .addNodeAddress("redis://localhost:7000", "redis://localhost:7001", "redis://localhost:7002")
 *     .setScanInterval(2000);
 *
 * // Create RedissonClient
 * RedissonClient redissonClient = Redisson.create(config);
 *
 * // Create adapter
 * RedissonClientAdapter adapter = RedissonClientAdapter.of(redissonClient);
 * }</pre>
 *
 * <p>Sentinel Mode:
 * <pre>{@code
 * // Create sentinel configuration
 * Config config = new Config();
 * config.useSentinelServers()
 *     .setMasterName("mymaster")
 *     .addSentinelAddress("redis://localhost:26379", "redis://localhost:26380")
 *     .setDatabase(0);
 *
 * // Create RedissonClient
 * RedissonClient redissonClient = Redisson.create(config);
 *
 * // Create adapter
 * RedissonClientAdapter adapter = RedissonClientAdapter.of(redissonClient);
 * }</pre>
 *
 * <p>Master/Slave Mode:
 * <pre>{@code
 * // Create master/slave configuration
 * Config config = new Config();
 * config.useMasterSlaveServers()
 *     .setMasterAddress("redis://localhost:6379")
 *     .addSlaveAddress("redis://localhost:6380", "redis://localhost:6381")
 *     .setReadMode(ReadMode.SLAVE);
 *
 * // Create RedissonClient
 * RedissonClient redissonClient = Redisson.create(config);
 *
 * // Create adapter
 * RedissonClientAdapter adapter = RedissonClientAdapter.of(redissonClient);
 * }</pre>
 *
 * @author Kevin
 * @author jianjun.xu
 * @author benym
 * @since 1.0.8
 */
public class RedissonClientAdapter implements RedisClientAdapter {

    private final RedissonClient redissonClient;

    private RedissonClientAdapter(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * Create adapter from RedissonClient.
     *
     * <p>The RedissonClient can be configured for any deployment mode (standalone, cluster, sentinel, master/slave),
     * and the adapter will handle the mode-specific details transparently.
     *
     * @param redissonClient the RedissonClient instance
     * @return a new RedissonClientAdapter
     */
    public static RedissonClientAdapter of(RedissonClient redissonClient) {
        return new RedissonClientAdapter(redissonClient);
    }

    @Override
    public void set(String key, String value) {
        RBucket<String> bucket = redissonClient.getBucket(key, StringCodec.INSTANCE);
        bucket.set(value);
    }

    @Override
    public String get(String key) {
        RBucket<String> bucket = redissonClient.getBucket(key, StringCodec.INSTANCE);
        return bucket.get();
    }

    @Override
    public void rightPushList(String key, String value) {
        RList<String> rList = redissonClient.getList(key, StringCodec.INSTANCE);
        rList.add(value);
    }

    @Override
    public List<String> rangeList(String key, long start, long end) {
        if (start > Integer.MAX_VALUE
                || end > Integer.MAX_VALUE
                || start < Integer.MIN_VALUE
                || end < Integer.MIN_VALUE) {
            throw new IllegalArgumentException(
                    "Index out of range for Redisson RList, which supports int-based indexing.");
        }
        RList<String> rList = redissonClient.getList(key, StringCodec.INSTANCE);
        return rList.range((int) start, (int) end);
    }

    @Override
    public long getListLength(String key) {
        RList<String> rList = redissonClient.getList(key, StringCodec.INSTANCE);
        return rList.size();
    }

    @Override
    public void deleteKeys(String... keys) {
        RKeys redisKeys = redissonClient.getKeys();
        redisKeys.delete(keys);
    }

    @Override
    public void addToSet(String key, String member) {
        RSet<String> rSet = redissonClient.getSet(key, StringCodec.INSTANCE);
        rSet.add(member);
    }

    @Override
    public Set<String> getSetMembers(String key) {
        RSet<String> rSet = redissonClient.getSet(key, StringCodec.INSTANCE);
        return new HashSet<>(rSet);
    }

    @Override
    public long getSetSize(String key) {
        RSet<String> rSet = redissonClient.getSet(key, StringCodec.INSTANCE);
        return rSet.size();
    }

    @Override
    public boolean keyExists(String key) {
        RKeys redisKeys = redissonClient.getKeys();
        return redisKeys.countExists(key) > 0;
    }

    @Override
    public Set<String> findKeysByPattern(String pattern) {
        RKeys redisKeys = redissonClient.getKeys();
        KeysScanOptions options = KeysScanOptions.defaults().pattern(pattern);
        Iterable<String> keysIterable = redisKeys.getKeys(options);
        Set<String> result = new HashSet<>();
        for (String key : keysIterable) {
            result.add(key);
        }
        return result;
    }

    @Override
    public void close() {
        redissonClient.shutdown();
    }
}
