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
package io.agentscope.extensions.redis.state;

import org.junit.jupiter.api.DisplayName;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/** Runs the fixes suite through the Redisson standalone client adapter. */
@DisplayName("RedisAgentStateStore fixes — Redisson")
class RedisAgentStateStoreFixesRedissonTest extends RedisAgentStateStoreFixesTest {

    @Override
    protected RedisAgentStateStore buildStore(int port, String keyPrefix) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(8);
        RedissonClient client = Redisson.create(config);
        return RedisAgentStateStore.builder().redissonClient(client).keyPrefix(keyPrefix).build();
    }
}
