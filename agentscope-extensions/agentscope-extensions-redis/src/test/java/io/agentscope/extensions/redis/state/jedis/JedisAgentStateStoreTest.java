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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/**
 * Unit tests for {@link JedisAgentStateStore} optimistic-concurrency versioning.
 *
 * <p>Uses Mockito to mock Jedis resources so no real Redis server is required. This test covers
 * the {@code saveIfVersion} UNVERSIONED path, which was the single uncovered line in the
 * deprecated Jedis store.
 */
@DisplayName("JedisAgentStateStore versioning")
class JedisAgentStateStoreTest {

    record TestState(String value) implements State {}

    private JedisPool jedisPool;
    private Jedis jedis;

    @BeforeEach
    void setUp() {
        jedisPool = mock(JedisPool.class);
        jedis = mock(Jedis.class);
        when(jedisPool.getResource()).thenReturn(jedis);
    }

    @Test
    @DisplayName("saveIfVersion with UNVERSIONED delegates to eval with UNCONDITIONAL sentinel")
    void saveIfVersionUnconditionalDelegatesToEval() {
        JedisAgentStateStore store =
                JedisAgentStateStore.builder().jedisPool(jedisPool).keyPrefix("test:").build();

        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(5L);

        long version =
                store.saveIfVersion(
                        "user",
                        "session",
                        "agent_state",
                        new TestState("v"),
                        AgentStateStore.UNVERSIONED);

        assertEquals(5L, version);
        // Regression: the UNVERSIONED path must not call getVersioned (which reads back as
        // State.class — a marker interface Jackson cannot instantiate).
        verify(jedis, never()).get(anyString());
    }

    @Test
    @DisplayName("listSessionIds uses SCAN and never the blocking KEYS command")
    void listSessionIdsUsesScanInsteadOfKeys() {
        JedisAgentStateStore store =
                JedisAgentStateStore.builder().jedisPool(jedisPool).keyPrefix("test:").build();

        ScanResult<String> firstPage = new ScanResult<>("17", List.of("test:__anon__/s1:_keys"));
        ScanResult<String> lastPage =
                new ScanResult<>(ScanParams.SCAN_POINTER_START, List.of("test:__anon__/s2:_keys"));
        when(jedis.scan(anyString(), any(ScanParams.class))).thenReturn(firstPage, lastPage);

        Set<String> sessions = store.listSessionIds(null);

        assertEquals(Set.of("s1", "s2"), sessions);
        // Blocking KEYS spans the whole keyspace on large/shared servers and must not be used.
        verify(jedis, never()).keys(anyString());
        verify(jedis, atLeastOnce()).scan(anyString(), any(ScanParams.class));
    }
}
