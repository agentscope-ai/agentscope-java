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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ListHashUtil;
import io.agentscope.core.state.State;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Unit tests for {@link JedisAgentStateStore} optimistic-concurrency versioning and list-state
 * cleanup.
 *
 * <p>Uses Mockito to mock Jedis resources so no real Redis server is required. This covers the
 * {@code saveIfVersion} UNVERSIONED path and the interaction between {@code save(List)} (which
 * writes a change-detection hash key) and {@code delete(String, String)} (which must remove it).
 */
@DisplayName("JedisAgentStateStore versioning and list cleanup")
class JedisAgentStateStoreTest {

    record TestState(String value) implements State {}

    private static final String LIST_KEY = "test:__anon__/s1:messages:list";
    private static final String HASH_KEY = LIST_KEY + ":_hash";
    private static final String KEYS_KEY = "test:__anon__/s1:_keys";

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
    @DisplayName("delete() removes the {key}:list:_hash key written by save(List)")
    void deleteRemovesListHashKey() {
        JedisAgentStateStore store =
                JedisAgentStateStore.builder().jedisPool(jedisPool).keyPrefix("test:").build();

        // save(List) persists the change-detection hash alongside the list.
        when(jedis.get(HASH_KEY)).thenReturn(null);
        when(jedis.llen(LIST_KEY)).thenReturn(0L);
        store.save(null, "s1", "messages", List.of(new TestState("a")));
        verify(jedis).set(HASH_KEY, ListHashUtil.computeHash(List.of(new TestState("a"))));

        // delete() must remove the list key and its change-detection hash key.
        when(jedis.smembers(KEYS_KEY)).thenReturn(Set.of("messages:list"));
        store.delete(null, "s1");

        ArgumentCaptor<String[]> deleted = ArgumentCaptor.forClass(String[].class);
        verify(jedis).del(deleted.capture());
        List<String> deletedKeys = Arrays.asList(deleted.getValue());
        assertTrue(deletedKeys.contains(KEYS_KEY), "keys set must be deleted, got " + deletedKeys);
        assertTrue(deletedKeys.contains(LIST_KEY), "list key must be deleted, got " + deletedKeys);
        assertTrue(
                deletedKeys.contains(HASH_KEY),
                "change-detection hash key must be deleted, got " + deletedKeys);
    }
}
