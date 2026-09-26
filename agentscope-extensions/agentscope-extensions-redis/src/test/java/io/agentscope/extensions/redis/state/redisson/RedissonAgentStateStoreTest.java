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
package io.agentscope.extensions.redis.state.redisson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

/**
 * Unit tests for {@link RedissonAgentStateStore} optimistic-concurrency versioning.
 *
 * <p>Uses Mockito to mock Redisson resources so no real Redis server is required. This test
 * covers the {@code saveIfVersion} UNVERSIONED path, which was the single uncovered line in the
 * deprecated Redisson store.
 */
@DisplayName("RedissonAgentStateStore versioning")
class RedissonAgentStateStoreTest {

    record TestState(String value) implements State {}

    private RedissonClient redissonClient;

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
    }

    @Test
    @DisplayName("saveIfVersion with UNVERSIONED delegates to eval with UNCONDITIONAL sentinel")
    void saveIfVersionUnconditionalDelegatesToEval() {
        RedissonAgentStateStore store =
                RedissonAgentStateStore.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("test:")
                        .build();

        RScript rScript = mock(RScript.class);
        when(redissonClient.getScript(any(Codec.class))).thenReturn(rScript);
        doReturn(5L)
                .when(rScript)
                .eval(
                        any(RScript.Mode.class),
                        anyString(),
                        any(RScript.ReturnType.class),
                        any(List.class),
                        any(Object[].class));

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
        verify(redissonClient, never()).getBucket(any(), any());
    }
}
