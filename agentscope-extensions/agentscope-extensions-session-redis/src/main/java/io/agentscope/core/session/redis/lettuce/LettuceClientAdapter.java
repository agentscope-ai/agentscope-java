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
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter for Lettuce Redis client.
 *
 * <p>This adapter supports multiple Redis deployment modes through different client types:
 *
 * <ul>
 *   <li>{@link RedisClient} - Standalone and Sentinel modes
 *   <li>{@link RedisClusterClient} - Cluster mode
 * </ul>
 *
 * <p>The adapter internally manages a shared connection and commands instance for efficient
 * connection usage.
 *
 * <p>This implementation uses direct conditional checks to handle Lettuce's separate command
 * APIs for standalone/sentinel and cluster modes. This is the simplest approach given
 * Lettuce's lack of a unified API across deployment modes.
 *
 * <p>Usage Examples:
 *
 * <p>Standalone Mode:
 * <pre>{@code
 * // Create standalone RedisClient
 * RedisClient redisClient = RedisClient.create("redis://localhost:6379");
 *
 * // Use with RedisSession
 * Session session = RedisSession.builder()
 *     .lettuceClient(redisClient)
 *     .build();
 * }</pre>
 *
 * <p>Cluster Mode:
 * <pre>{@code
 * // Create cluster client
 * RedisClusterClient clusterClient = RedisClusterClient.create("redis://localhost:7000");
 *
 * // Use with RedisSession
 * Session session = RedisSession.builder()
 *     .lettuceClusterClient(clusterClient)
 *     .build();
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
 *     .build();
 * RedisClient redisClient = RedisClient.create(sentinelUri);
 *
 * // Use with RedisSession
 * Session session = RedisSession.builder()
 *     .lettuceClient(redisClient)
 *     .build();
 * }</pre>
 */
public class LettuceClientAdapter implements RedisClientAdapter {

    private static final Logger log = LoggerFactory.getLogger(LettuceClientAdapter.class);

    /**
     * Redis commands for standalone and sentinel modes.
     * Null when operating in cluster mode.
     */
    private final RedisCommands<String, String> commands;

    /**
     * Redis cluster commands for cluster mode.
     * Null when operating in standalone or sentinel mode.
     */
    private final RedisAdvancedClusterCommands<String, String> clusterCommands;

    /**
     * Closeable resource handler for cleaning up connections and clients.
     * Uses a strategy pattern to handle different cleanup logic for
     * standalone/sentinel and cluster modes.
     */
    private final AutoCloseable closeable;

    private LettuceClientAdapter(
            RedisCommands<String, String> commands,
            RedisAdvancedClusterCommands<String, String> clusterCommands,
            AutoCloseable closeable) {
        this.commands = commands;
        this.clusterCommands = clusterCommands;
        this.closeable = closeable;
    }

    /**
     * Create adapter from RedisClient (standalone/sentinel mode).
     *
     * @param redisClient the RedisClient for standalone or sentinel mode
     * @return a new LettuceClientAdapter
     * @throws NullPointerException if redisClient is null
     */
    public static LettuceClientAdapter of(RedisClient redisClient) {
        if (redisClient == null) {
            throw new NullPointerException("redisClient must not be null");
        }
        StatefulRedisConnection<String, String> connection = redisClient.connect();
        return new LettuceClientAdapter(
                connection.sync(), null, new StandaloneCloser(connection, redisClient));
    }

    /**
     * Create adapter from RedisClusterClient (cluster mode).
     *
     * @param redisClusterClient the RedisClusterClient for cluster mode
     * @return a new LettuceClientAdapter
     * @throws NullPointerException if redisClusterClient is null
     */
    public static LettuceClientAdapter of(RedisClusterClient redisClusterClient) {
        if (redisClusterClient == null) {
            throw new NullPointerException("redisClusterClient must not be null");
        }
        StatefulRedisClusterConnection<String, String> connection = redisClusterClient.connect();
        return new LettuceClientAdapter(
                null, connection.sync(), new ClusterCloser(connection, redisClusterClient));
    }

    @Override
    public void set(String key, String value) {
        if (commands != null) {
            commands.set(key, value);
        } else {
            clusterCommands.set(key, value);
        }
    }

    @Override
    public String get(String key) {
        if (commands != null) {
            return commands.get(key);
        } else {
            return clusterCommands.get(key);
        }
    }

    @Override
    public void rightPushList(String key, String value) {
        if (commands != null) {
            commands.rpush(key, value);
        } else {
            clusterCommands.rpush(key, value);
        }
    }

    @Override
    public List<String> rangeList(String key, long start, long end) {
        if (commands != null) {
            return commands.lrange(key, start, end);
        } else {
            return clusterCommands.lrange(key, start, end);
        }
    }

    @Override
    public long getListLength(String key) {
        if (commands != null) {
            return commands.llen(key);
        } else {
            return clusterCommands.llen(key);
        }
    }

    @Override
    public void deleteKeys(String... keys) {
        if (commands != null) {
            commands.del(keys);
        } else {
            clusterCommands.del(keys);
        }
    }

    @Override
    public void addToSet(String key, String member) {
        if (commands != null) {
            commands.sadd(key, member);
        } else {
            clusterCommands.sadd(key, member);
        }
    }

    @Override
    public Set<String> getSetMembers(String key) {
        if (commands != null) {
            return new HashSet<>(commands.smembers(key));
        } else {
            return new HashSet<>(clusterCommands.smembers(key));
        }
    }

    @Override
    public long getSetSize(String key) {
        if (commands != null) {
            return commands.scard(key);
        } else {
            return clusterCommands.scard(key);
        }
    }

    @Override
    public boolean keyExists(String key) {
        if (commands != null) {
            return commands.exists(key) > 0;
        } else {
            return clusterCommands.exists(key) > 0;
        }
    }

    @Override
    public Set<String> findKeysByPattern(String pattern) {
        if (commands != null) {
            return scanKeys(pattern, commands::scan);
        } else {
            return scanKeys(pattern, clusterCommands::scan);
        }
    }

    /**
     * Helper method to scan keys using the provided scan function.
     *
     * @param pattern the key pattern to match
     * @param scanFunction the function to perform the scan
     * @return the set of matching keys
     */
    private static Set<String> scanKeys(String pattern, ScanFunction scanFunction) {
        Set<String> keys = new HashSet<>();
        ScanCursor cursor = ScanCursor.INITIAL;
        ScanArgs scanArgs = ScanArgs.Builder.matches(pattern);
        while (!cursor.isFinished()) {
            KeyScanCursor<String> scanResult = scanFunction.scan(cursor, scanArgs);
            keys.addAll(scanResult.getKeys());
            cursor = scanResult;
        }
        return keys;
    }

    /**
     * Functional interface for scan operations.
     */
    @FunctionalInterface
    private interface ScanFunction {
        KeyScanCursor<String> scan(ScanCursor cursor, ScanArgs scanArgs);
    }

    @Override
    public void close() {
        try {
            closeable.close();
        } catch (Exception e) {
            log.warn("Exception while closing Lettuce adapter: {}", e.getMessage());
            if (log.isDebugEnabled()) {
                log.debug("Full stack trace:", e);
            }
        }
    }

    private static class StandaloneCloser implements AutoCloseable {
        private final StatefulRedisConnection<String, String> connection;
        private final RedisClient client;

        StandaloneCloser(StatefulRedisConnection<String, String> connection, RedisClient client) {
            this.connection = connection;
            this.client = client;
        }

        @Override
        public void close() {
            try {
                connection.close();
                client.shutdown();
            } catch (Exception e) {
                log.debug("Exception closing Lettuce standalone: {}", e.getMessage());
            }
        }
    }

    private static class ClusterCloser implements AutoCloseable {
        private final StatefulRedisClusterConnection<String, String> connection;
        private final RedisClusterClient client;

        ClusterCloser(
                StatefulRedisClusterConnection<String, String> connection,
                RedisClusterClient client) {
            this.connection = connection;
            this.client = client;
        }

        @Override
        public void close() {
            try {
                connection.close();
                client.shutdown();
            } catch (Exception e) {
                log.debug("Exception closing Lettuce cluster: {}", e.getMessage());
            }
        }
    }
}
