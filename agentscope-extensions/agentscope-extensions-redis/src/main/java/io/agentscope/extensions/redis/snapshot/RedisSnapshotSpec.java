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
package io.agentscope.extensions.redis.snapshot;

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import redis.clients.jedis.UnifiedJedis;

/**
 * Convenience {@link SandboxSnapshotSpec} for Redis-backed snapshot storage.
 *
 * <p>Supports both Jedis ({@link UnifiedJedis}) and Lettuce ({@link RedisCommands} standalone /
 * sentinel, {@link RedisAdvancedClusterCommands} cluster) clients.
 */
public class RedisSnapshotSpec extends RemoteSnapshotSpec {

    /**
     * Creates a Redis snapshot spec backed by Jedis.
     *
     * @param jedis initialized jedis client
     * @param keyPrefix redis key prefix (optional)
     * @param ttlSeconds optional TTL in seconds (null or negative means no TTL)
     */
    public RedisSnapshotSpec(UnifiedJedis jedis, String keyPrefix, Integer ttlSeconds) {
        super(new RedisRemoteSnapshotClient(jedis, keyPrefix, ttlSeconds));
    }

    /**
     * Creates a Redis snapshot spec backed by a Lettuce standalone or Sentinel client.
     *
     * <p>Example:
     * <pre>{@code
     * RedisClient client = RedisClient.create("redis://localhost:6379");
     * StatefulRedisConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE);
     *
     * SandboxSnapshotSpec spec = new RedisSnapshotSpec(connection.sync(), "myapp:snapshot:", 3600);
     * }</pre>
     *
     * @param commands initialized Lettuce sync commands bound to a byte[] codec (see {@link
     *     ByteArrayCodec})
     * @param keyPrefix redis key prefix (optional)
     * @param ttlSeconds optional TTL in seconds (null or negative means no TTL)
     */
    public RedisSnapshotSpec(
            RedisCommands<byte[], byte[]> commands, String keyPrefix, Integer ttlSeconds) {
        super(new LettuceRemoteSnapshotClient(commands, keyPrefix, ttlSeconds));
    }

    /**
     * Creates a Redis snapshot spec backed by a Lettuce cluster client.
     *
     * <p>Example:
     * <pre>{@code
     * RedisClusterClient client = RedisClusterClient.create("redis://localhost:7000");
     * StatefulRedisClusterConnection<byte[], byte[]> connection =
     *     client.connect(ByteArrayCodec.INSTANCE);
     *
     * SandboxSnapshotSpec spec = new RedisSnapshotSpec(connection.sync(), "myapp:snapshot:", 3600);
     * }</pre>
     *
     * @param commands initialized Lettuce cluster sync commands bound to a byte[] codec (see
     *     {@link ByteArrayCodec})
     * @param keyPrefix redis key prefix (optional)
     * @param ttlSeconds optional TTL in seconds (null or negative means no TTL)
     */
    public RedisSnapshotSpec(
            RedisAdvancedClusterCommands<byte[], byte[]> commands,
            String keyPrefix,
            Integer ttlSeconds) {
        super(new LettuceRemoteSnapshotClient(commands, keyPrefix, ttlSeconds));
    }
}
