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
package io.agentscope.core.session.redis.lettuce;

import io.agentscope.core.session.redis.RedisClientAdapter;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapter for Lettuce Redis client.
 *
 * <p>This adapter supports Lettuce 6.x+ which uses a unified {@link RedisClient} for all Redis deployment modes.
 * Lettuce's {@link RedisClient} provides a single entry point that handles different
 * deployment modes transparently based on the {@link RedisURI} configuration.
 *
 * <p>Users can pass a RedisClient configured for any mode:
 * <ul>
 *   <li>Standalone mode - configured with a single Redis URI</li>
 *   <li>Cluster mode - configured with Redis Cluster URI</li>
 *   <li>Sentinel mode - configured with sentinel URIs</li>
 * </ul>
 *
 * <p>The adapter internally manages a shared {@link StatefulRedisConnection} and
 * {@link RedisCommands} instance for efficient connection usage.
 *
 * <p>Usage Examples:
 *
 * <p>Standalone Mode:
 * <pre>{@code
 * // Create standalone RedisClient
 * RedisClient redisClient = RedisClient.create("redis://localhost:6379");
 *
 * // Create adapter
 * LettuceClientAdapter adapter = LettuceClientAdapter.of(redisClient);
 *
 * // Use with RedisSession
 * Session session = RedisSession.builder()
 *     .lettuceClient(redisClient)
 *     .build();
 * }</pre>
 *
 * <p>Cluster Mode:
 * <pre>{@code
 * // Create cluster RedisURI
 * RedisURI clusterUri = RedisURI.create("redis://localhost:7000");
 * clusterUri.setClientName("my-client");
 * clusterUri.setTimeout(Duration.ofSeconds(10));
 *
 * // Create cluster RedisClient
 * RedisClient redisClient = RedisClient.create(clusterUri);
 *
 * // Create adapter
 * LettuceClientAdapter adapter = LettuceClientAdapter.of(redisClient);
 * }</pre>
 *
 * <p>Sentinel Mode:
 * <pre>{@code
 * // Create Lettuce RedisClient for sentinel
 * RedisURI sentinelUri = RedisURI.builder()
 *     .withSentinelMasterId("mymaster")
 *     .withSentinel("localhost", 26379)
 *     .withSentinel("localhost", 26380)
 *     .withSentinel("localhost", 26381)
 *     .withDatabase(0)
 *     .withTimeout(Duration.ofSeconds(10))
 *     .build();
 * RedisClient redisClient = RedisClient.create(sentinelUri);
 *
 * // Create adapter
 * LettuceClientAdapter adapter = LettuceClientAdapter.of(redisClient);
 * }</pre>
 *
 * <p>Custom Connection Settings:
 * <pre>{@code
 * // Create RedisClient with custom settings
 * RedisClient redisClient = RedisClient.builder()
 *     .redisURIs(Arrays.asList(RedisURI.create("redis://localhost:6379")))
 *     .commandTimeout(Duration.ofSeconds(5))
 *     .build();
 *
 * // Create adapter
 * LettuceClientAdapter adapter = LettuceClientAdapter.of(redisClient);
 * }</pre>
 *
 * @author Kevin
 * @author jianjun.xu
 * @author benym
 * @since 1.0.8
 */
public class LettuceClientAdapter implements RedisClientAdapter {

    private final io.lettuce.core.RedisClient redisClient;

    private final StatefulRedisConnection<String, String> connection;

    private final RedisCommands<String, String> commands;

    private LettuceClientAdapter(io.lettuce.core.RedisClient redisClient) {
        this.redisClient = redisClient;
        this.connection = redisClient.connect();
        this.commands = connection.sync();
    }

    /**
     * Create adapter from RedisClient.
     *
     * <p>The RedisClient can be configured for standalone, cluster, or sentinel mode by
     * providing an appropriate RedisURI configuration.
     *
     * @param redisClient the RedisClient
     * @return a new LettuceClientAdapter
     */
    public static LettuceClientAdapter of(io.lettuce.core.RedisClient redisClient) {
        return new LettuceClientAdapter(redisClient);
    }

    @Override
    public void set(String key, String value) {
        commands.set(key, value);
    }

    @Override
    public String get(String key) {
        return commands.get(key);
    }

    @Override
    public void rightPushList(String key, String value) {
        commands.rpush(key, value);
    }

    @Override
    public List<String> rangeList(String key, long start, long end) {
        return commands.lrange(key, start, end);
    }

    @Override
    public long getListLength(String key) {
        return commands.llen(key);
    }

    @Override
    public void deleteKeys(String... keys) {
        commands.del(keys);
    }

    @Override
    public void addToSet(String key, String member) {
        commands.sadd(key, member);
    }

    @Override
    public Set<String> getSetMembers(String key) {
        return new HashSet<>(commands.smembers(key));
    }

    @Override
    public long getSetSize(String key) {
        return commands.scard(key);
    }

    @Override
    public boolean keyExists(String key) {
        return commands.exists(key) > 0;
    }

    @Override
    public Set<String> findKeysByPattern(String pattern) {
        Set<String> keys = new HashSet<>();
        ScanCursor cursor = ScanCursor.INITIAL;
        while (!cursor.isFinished()) {
            KeyScanCursor<String> scanResult =
                    commands.scan(cursor, ScanArgs.Builder.matches(pattern));
            if (scanResult != null) {
                keys.addAll(scanResult.getKeys());
                cursor = scanResult;
            } else {
                break;
            }
        }
        return keys;
    }

    @Override
    public void close() {
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
        redisClient.shutdown();
    }
}
