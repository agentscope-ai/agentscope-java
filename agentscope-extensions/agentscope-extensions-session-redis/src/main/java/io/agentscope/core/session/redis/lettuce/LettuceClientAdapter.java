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
 * // Create cluster client
 * RedisClusterClient clusterClient = RedisClusterClient.create(
 *     RedisURI.create("localhost", 7000));
 *
 * // Create adapter
 * LettuceClientAdapter adapter = LettuceClientAdapter.of(clusterClient);
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
 * // Create adapter
 * LettuceClientAdapter adapter = LettuceClientAdapter.of(redisClient);
 * }</pre>
 */
public class LettuceClientAdapter implements RedisClientAdapter {

    private final CommandExecutor executor;

    private final Runnable closeHandler;

    private LettuceClientAdapter(CommandExecutor executor, Runnable closeHandler) {
        this.executor = executor;
        this.closeHandler = closeHandler;
    }

    /**
     * Create adapter from RedisClient (standalone/sentinel mode).
     *
     * <p>The RedisClient can be configured for standalone or sentinel mode by providing an
     * appropriate RedisURI configuration.
     *
     * @param redisClient the RedisClient for standalone or sentinel mode
     * @return a new LettuceClientAdapter
     */
    public static LettuceClientAdapter of(RedisClient redisClient) {
        StatefulRedisConnection<String, String> connection = redisClient.connect();
        RedisCommands<String, String> commands = connection.sync();
        return new LettuceClientAdapter(
                new StandaloneCommandExecutor(commands),
                () -> {
                    connection.close();
                    redisClient.shutdown();
                });
    }

    /**
     * Create adapter from RedisClusterClient (cluster mode).
     *
     * <p>The RedisClusterClient handles connections to multiple Redis cluster nodes.
     *
     * @param redisClusterClient the RedisClusterClient for cluster mode
     * @return a new LettuceClientAdapter
     */
    public static LettuceClientAdapter of(RedisClusterClient redisClusterClient) {
        StatefulRedisClusterConnection<String, String> connection = redisClusterClient.connect();
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();
        return new LettuceClientAdapter(
                new ClusterCommandExecutor(commands),
                () -> {
                    connection.close();
                    redisClusterClient.shutdown();
                });
    }

    @Override
    public void set(String key, String value) {
        executor.set(key, value);
    }

    @Override
    public String get(String key) {
        return executor.get(key);
    }

    @Override
    public void rightPushList(String key, String value) {
        executor.rightPushList(key, value);
    }

    @Override
    public List<String> rangeList(String key, long start, long end) {
        return executor.rangeList(key, start, end);
    }

    @Override
    public long getListLength(String key) {
        return executor.getListLength(key);
    }

    @Override
    public void deleteKeys(String... keys) {
        executor.deleteKeys(keys);
    }

    @Override
    public void addToSet(String key, String member) {
        executor.addToSet(key, member);
    }

    @Override
    public Set<String> getSetMembers(String key) {
        return executor.getSetMembers(key);
    }

    @Override
    public long getSetSize(String key) {
        return executor.getSetSize(key);
    }

    @Override
    public boolean keyExists(String key) {
        return executor.keyExists(key);
    }

    @Override
    public Set<String> findKeysByPattern(String pattern) {
        return executor.findKeysByPattern(pattern);
    }

    @Override
    public void close() {
        closeHandler.run();
    }

    private interface CommandExecutor {
        void set(String key, String value);

        String get(String key);

        void rightPushList(String key, String value);

        List<String> rangeList(String key, long start, long end);

        long getListLength(String key);

        void deleteKeys(String... keys);

        void addToSet(String key, String member);

        Set<String> getSetMembers(String key);

        long getSetSize(String key);

        boolean keyExists(String key);

        Set<String> findKeysByPattern(String pattern);
    }

    private static class StandaloneCommandExecutor implements CommandExecutor {
        private final RedisCommands<String, String> commands;

        StandaloneCommandExecutor(RedisCommands<String, String> commands) {
            this.commands = commands;
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
    }

    private static class ClusterCommandExecutor implements CommandExecutor {
        private final RedisAdvancedClusterCommands<String, String> commands;

        ClusterCommandExecutor(RedisAdvancedClusterCommands<String, String> commands) {
            this.commands = commands;
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
    }
}
