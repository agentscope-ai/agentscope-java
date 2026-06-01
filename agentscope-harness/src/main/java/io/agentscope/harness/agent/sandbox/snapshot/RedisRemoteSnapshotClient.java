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
package io.agentscope.harness.agent.sandbox.snapshot;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import redis.clients.jedis.UnifiedJedis;

/**
 * {@link RemoteSnapshotClient} backed by Redis binary values.
 */
public class RedisRemoteSnapshotClient implements RemoteSnapshotClient {

    private final UnifiedJedis jedis;
    private final String keyPrefix;
    private final Integer ttlSeconds;

    /**
     * Creates a Redis-backed snapshot client.
     *
     * @param jedis initialized jedis client
     * @param keyPrefix redis key prefix (optional)
     * @param ttlSeconds optional TTL in seconds (null or negative means no TTL)
     */
    public RedisRemoteSnapshotClient(UnifiedJedis jedis, String keyPrefix, Integer ttlSeconds) {
        this.jedis = Objects.requireNonNull(jedis, "jedis must not be null");
        this.keyPrefix = normalizePrefix(keyPrefix);
        this.ttlSeconds = ttlSeconds != null && ttlSeconds > 0 ? ttlSeconds : null;
    }

    @Override
    public void upload(String snapshotId, InputStream data) throws Exception {
        byte[] key = redisKey(snapshotId);
        byte[] value = data.readAllBytes();
        jedis.set(key, value);
        if (ttlSeconds != null) {
            jedis.expire(key, ttlSeconds);
        }
    }

    @Override
    public InputStream download(String snapshotId) throws Exception {
        byte[] data = jedis.get(redisKey(snapshotId));
        if (data == null) {
            throw new FileNotFoundException(
                    "Snapshot not found in Redis: " + composeKey(snapshotId));
        }
        return new ByteArrayInputStream(data);
    }

    @Override
    public boolean exists(String snapshotId) throws Exception {
        return jedis.exists(redisKey(snapshotId));
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
