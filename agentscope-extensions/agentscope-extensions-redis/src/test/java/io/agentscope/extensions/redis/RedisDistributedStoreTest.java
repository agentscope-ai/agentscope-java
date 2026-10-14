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
package io.agentscope.extensions.redis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;

@DisplayName("RedisDistributedStore")
class RedisDistributedStoreTest {

    @Test
    @DisplayName("existing factory keeps the V0 agent-state layout")
    void existingFactoryKeepsV0AgentStateLayout() {
        UnifiedJedis jedis = mock(UnifiedJedis.class);
        when(jedis.scard("tenant:session:user/s1:_keys")).thenReturn(0L);
        RedisDistributedStore store = RedisDistributedStore.fromJedis(jedis, "tenant:");

        assertFalse(store.agentStateStore().exists("user", "s1"));

        verify(jedis).scard("tenant:session:user/s1:_keys");
    }

    @Test
    @DisplayName("configured V1 layout reaches the agent-state store")
    void configuredV1LayoutReachesAgentStateStore() {
        UnifiedJedis jedis = mock(UnifiedJedis.class);
        when(jedis.scard("tenant:session:{user/s1}:_keys")).thenReturn(0L);
        RedisDistributedStore store =
                RedisDistributedStore.fromJedis(
                        jedis, "tenant:", RedisAgentStateStore.KeyLayoutVersion.V1);

        assertFalse(store.agentStateStore().exists("user", "s1"));

        verify(jedis).scard("tenant:session:{user/s1}:_keys");
    }
}
