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
package io.agentscope.extensions.redis.state.jedis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.ScanIteration;
import redis.clients.jedis.UnifiedJedis;

/**
 * Verifies that {@link JedisClientAdapter#findKeysByPattern(String)} delegates to Jedis'
 * {@link UnifiedJedis#scanIteration(int, String)}, which is the API that walks every master node
 * in cluster mode (so keys on other shards are not missed). The cross-node behaviour itself is
 * proven end-to-end by {@code RedisClusterIntegrationTest}; here we mock the client to assert the
 * adapter uses the right primitive.
 */
class JedisClientAdapterClusterScanTest {

    @SuppressWarnings("unchecked")
    @Test
    void findKeysByPatternDelegatesToScanIteration() {
        UnifiedJedis unifiedJedis = mock(UnifiedJedis.class);
        ScanIteration iteration = mock(ScanIteration.class);
        when(unifiedJedis.scanIteration(eq(100), eq("prefix:*"))).thenReturn(iteration);
        // collect() is final on ScanIteration; the inline mock maker (auto-enabled) stubs it.
        doAnswer(
                        inv -> {
                            Collection<String> sink = inv.getArgument(0);
                            sink.addAll(List.of("k1", "k2", "k3", "k4", "k5"));
                            return sink;
                        })
                .when(iteration)
                .collect(anyCollection());

        JedisClientAdapter adapter = JedisClientAdapter.of(unifiedJedis);
        Set<String> keys = adapter.findKeysByPattern("prefix:*");

        assertEquals(Set.of("k1", "k2", "k3", "k4", "k5"), keys);
        verify(unifiedJedis).scanIteration(100, "prefix:*");
        verify(iteration).collect(anyCollection());
    }
}
