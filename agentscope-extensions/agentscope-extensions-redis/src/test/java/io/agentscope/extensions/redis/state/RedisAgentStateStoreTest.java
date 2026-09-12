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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link RedisAgentStateStore} optimistic-concurrency versioning, using a mocked
 * {@link RedisClientAdapter} so no Redis server is required.
 */
@DisplayName("RedisAgentStateStore versioning")
class RedisAgentStateStoreTest {

    record TestState(String value) implements State {}

    @Mock private RedisClientAdapter client;

    private RedisAgentStateStore store;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        store = RedisAgentStateStore.builder().clientAdapter(client).build();
    }

    @Test
    @DisplayName(
            "saveIfVersion with UNVERSIONED returns the Lua version without reading state back")
    void saveIfVersionUnconditionalNoReadBack() {
        when(client.evalScript(any(), anyList(), anyList())).thenReturn(7L);

        long version =
                store.saveIfVersion(
                        "user",
                        "s1",
                        "agent_state",
                        new TestState("v"),
                        AgentStateStore.UNVERSIONED);

        assertEquals(7L, version);
        // Regression: the UNVERSIONED path must not read the payload back. The previous
        // implementation called getVersioned(..., State.class), whose deserialization into the
        // `State` marker interface raised Jackson's InvalidDefinitionException.
        verify(client, never()).get(any());
    }

    @Test
    @DisplayName("saveIfVersion with a concrete expected version delegates to the Lua script")
    void saveIfVersionCasDelegatesToLua() {
        when(client.evalScript(any(), anyList(), anyList())).thenReturn(5L);

        long version = store.saveIfVersion("user", "s1", "agent_state", new TestState("v"), 4L);

        assertEquals(5L, version);
        verify(client, times(1)).evalScript(any(), anyList(), anyList());
    }
}
