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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.ScanIteration;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisException;

class JedisClientAdapterTest {

    @Test
    void findKeysByPatternCollectsAllClusterScanResults() {
        UnifiedJedis unifiedJedis = mock(UnifiedJedis.class);
        ScanIteration iteration = mock(ScanIteration.class);
        when(unifiedJedis.scanIteration(1000, "agent:*")).thenReturn(iteration);
        when(iteration.collect(any(Set.class)))
                .thenAnswer(
                        invocation -> {
                            Set<String> keys = invocation.getArgument(0);
                            keys.addAll(Set.of("agent:one", "agent:two"));
                            return keys;
                        });
        doThrow(new AssertionError("single-node scan must not be used for cluster-wide scan"))
                .when(unifiedJedis)
                .scan(anyString(), any());

        Set<String> keys = JedisClientAdapter.of(unifiedJedis).findKeysByPattern("agent:*");

        assertEquals(Set.of("agent:one", "agent:two"), keys);
    }

    @Test
    void findKeysByPatternCollectsAndDeduplicatesStandaloneScanResults() {
        RedisClient standaloneJedis = mock(RedisClient.class);
        ScanIteration iteration = mock(ScanIteration.class);
        when(standaloneJedis.scanIteration(1000, "agent:*")).thenReturn(iteration);
        when(iteration.collect(any(Set.class)))
                .thenAnswer(
                        invocation -> {
                            Set<String> keys = invocation.getArgument(0);
                            keys.addAll(List.of("agent:one", "agent:two", "agent:one"));
                            return keys;
                        });

        Set<String> keys = JedisClientAdapter.of(standaloneJedis).findKeysByPattern("agent:*");

        assertEquals(Set.of("agent:one", "agent:two"), keys);
        verify(standaloneJedis).scanIteration(1000, "agent:*");
        verify(standaloneJedis, never()).scan(anyString(), any());
    }

    @Test
    void findKeysByPatternPropagatesScanIterationFailure() {
        UnifiedJedis unifiedJedis = mock(UnifiedJedis.class);
        ScanIteration iteration = mock(ScanIteration.class);
        JedisException expected = new JedisException("scan failed");
        when(unifiedJedis.scanIteration(1000, "agent:*")).thenReturn(iteration);
        when(iteration.collect(any(Set.class))).thenThrow(expected);

        JedisException actual =
                assertThrows(
                        JedisException.class,
                        () -> JedisClientAdapter.of(unifiedJedis).findKeysByPattern("agent:*"));

        assertSame(expected, actual);
        verify(unifiedJedis).scanIteration(1000, "agent:*");
        verify(unifiedJedis, never()).scan(anyString(), any());
    }
}
