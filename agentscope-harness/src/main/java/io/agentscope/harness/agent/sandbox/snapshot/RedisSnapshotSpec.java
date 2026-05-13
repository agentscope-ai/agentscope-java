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

import redis.clients.jedis.UnifiedJedis;

/**
 * Convenience {@link SandboxSnapshotSpec} for Redis-backed snapshot storage.
 */
public class RedisSnapshotSpec extends RemoteSnapshotSpec {

    /**
     * Creates a Redis snapshot spec.
     *
     * @param jedis initialized jedis client
     * @param keyPrefix redis key prefix (optional)
     * @param ttlSeconds optional TTL in seconds (null or negative means no TTL)
     */
    public RedisSnapshotSpec(UnifiedJedis jedis, String keyPrefix, Integer ttlSeconds) {
        super(new RedisRemoteSnapshotClient(jedis, keyPrefix, ttlSeconds));
    }
}
