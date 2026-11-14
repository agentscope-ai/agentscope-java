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

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.api.sync.RedisKeyCommands;
import io.lettuce.core.api.sync.RedisStringCommands;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * {@link RemoteSnapshotClient} backed by Redis binary values through a Lettuce client.
 *
 * <p>Like {@link RedisRemoteSnapshotClient}, but for applications that standardize on Lettuce
 * instead of Jedis. The supported client types mirror {@code LettuceClientAdapter}:
 *
 * <ul>
 *   <li>{@link RedisCommands} — standalone and Sentinel modes
 *   <li>{@link RedisAdvancedClusterCommands} — cluster mode
 * </ul>
 *
 * <p>The blocking sync API is used on purpose: {@link RemoteSnapshotClient} exposes a blocking
 * {@link InputStream} contract, so commands block the calling thread exactly like the Jedis
 * implementation, while Lettuce's own IO threads stay non-blocking. Lettuce connections are
 * thread-safe, so one client can be shared across sessions. Command timeouts follow the {@code
 * RedisURI} timeout configured on the client (default 60s), which replaces Jedis' socket
 * timeouts.
 *
 * <p>The connection and client lifecycle stay with the caller: {@link RemoteSnapshotClient} has
 * no close hook, and the Jedis implementation equally leaves its {@code UnifiedJedis} open.
 *
 * <p>Usage example (standalone mode):
 * <pre>{@code
 * RedisClient client = RedisClient.create("redis://localhost:6379");
 * StatefulRedisConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE);
 *
 * RemoteSnapshotClient snapshotClient =
 *     new LettuceRemoteSnapshotClient(connection.sync(), "myapp:snapshot:", 3600);
 * }</pre>
 */
public class LettuceRemoteSnapshotClient implements RemoteSnapshotClient {

    private final RedisStringCommands<byte[], byte[]> stringCommands;
    private final RedisKeyCommands<byte[], byte[]> keyCommands;
    private final String keyPrefix;
    private final Integer ttlSeconds;

    /**
     * Creates a Redis-backed snapshot client using Lettuce sync commands (standalone or Sentinel
     * mode), bound to a {@code byte[]} codec (see {@link ByteArrayCodec}).
     *
     * @param commands initialized Lettuce sync commands, e.g. {@code connection.sync()}
     * @param keyPrefix redis key prefix (optional)
     * @param ttlSeconds optional TTL in seconds (null or negative means no TTL)
     */
    public LettuceRemoteSnapshotClient(
            RedisCommands<byte[], byte[]> commands, String keyPrefix, Integer ttlSeconds) {
        this(commands, commands, keyPrefix, ttlSeconds);
    }

    /**
     * Creates a Redis-backed snapshot client using Lettuce cluster sync commands (cluster mode),
     * bound to a {@code byte[]} codec (see {@link ByteArrayCodec}).
     *
     * @param commands initialized Lettuce cluster sync commands, e.g. {@code
     *     clusterConnection.sync()}
     * @param keyPrefix redis key prefix (optional)
     * @param ttlSeconds optional TTL in seconds (null or negative means no TTL)
     */
    public LettuceRemoteSnapshotClient(
            RedisAdvancedClusterCommands<byte[], byte[]> commands,
            String keyPrefix,
            Integer ttlSeconds) {
        this(commands, commands, keyPrefix, ttlSeconds);
    }

    private LettuceRemoteSnapshotClient(
            RedisStringCommands<byte[], byte[]> stringCommands,
            RedisKeyCommands<byte[], byte[]> keyCommands,
            String keyPrefix,
            Integer ttlSeconds) {
        this.stringCommands = Objects.requireNonNull(stringCommands, "commands must not be null");
        this.keyCommands = Objects.requireNonNull(keyCommands, "commands must not be null");
        this.keyPrefix = normalizePrefix(keyPrefix);
        this.ttlSeconds = ttlSeconds != null && ttlSeconds > 0 ? ttlSeconds : null;
    }

    @Override
    public void upload(String snapshotId, InputStream data) throws Exception {
        byte[] key = redisKey(snapshotId);
        byte[] value = data.readAllBytes();
        stringCommands.set(key, value);
        if (ttlSeconds != null) {
            keyCommands.expire(key, ttlSeconds);
        }
    }

    @Override
    public InputStream download(String snapshotId) throws Exception {
        byte[] data = stringCommands.get(redisKey(snapshotId));
        if (data == null) {
            throw new FileNotFoundException(
                    "Snapshot not found in Redis: " + composeKey(snapshotId));
        }
        return new ByteArrayInputStream(data);
    }

    @Override
    public boolean exists(String snapshotId) throws Exception {
        return keyCommands.exists(redisKey(snapshotId)) > 0;
    }

    private byte[] redisKey(String snapshotId) {
        return composeKey(snapshotId).getBytes(StandardCharsets.UTF_8);
    }

    private String composeKey(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
        return keyPrefix + snapshotId + ".tar";
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "agentscope:sandbox:snapshots:";
        }
        return prefix.endsWith(":") ? prefix : prefix + ":";
    }
}
