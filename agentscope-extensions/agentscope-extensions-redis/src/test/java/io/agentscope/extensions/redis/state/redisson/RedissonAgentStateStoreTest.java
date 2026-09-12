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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.extensions.redis.state.RedisStateVersionSupport;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;

/**
 * Unit tests for {@link RedissonAgentStateStore} compatibility and optimistic-concurrency versioning.
 *
 * <p>Uses Mockito to mock Redisson resources so no real Redis server is required. This test
 * covers construction and the {@code saveIfVersion} UNVERSIONED regression in the deprecated store.
 */
@DisplayName("RedissonAgentStateStore compatibility and versioning")
class RedissonAgentStateStoreTest {

    record TestState(String value) implements State {}

    private RedissonClient redissonClient;

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
    }

    @Test
    void constructsWithoutContactingRedis() {
        assertDoesNotThrow(
                () -> RedissonAgentStateStore.builder().redissonClient(redissonClient).build());
        verifyNoInteractions(redissonClient);
    }

    @Test
    void rejectsIncompatibleApiBeforeContactingRedis() {
        IllegalArgumentException cause = new IllegalArgumentException("No enum constant LONG");
        try (MockedStatic<RScript.ReturnType> returnType = mockStatic(RScript.ReturnType.class)) {
            returnType.when(() -> RScript.ReturnType.valueOf("LONG")).thenThrow(cause);

            IllegalStateException error =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    RedissonAgentStateStore.builder()
                                            .redissonClient(redissonClient)
                                            .build());

            assertSame(cause, error.getCause());
            assertTrue(error.getMessage().contains("requires the Redisson 4.x API"));
            assertTrue(error.getMessage().contains("Redisson 3.x is incompatible"));
            assertTrue(
                    error.getMessage()
                            .contains("Align your Redisson dependencies, including any starter"));
            assertTrue(error.getMessage().contains("4.2.0"));
            String version = RScript.class.getPackage().getImplementationVersion();
            assertTrue(
                    error.getMessage()
                            .contains(
                                    "Loaded Redisson API implementation version: "
                                            + (version == null ? "unknown" : version)));
            verifyNoInteractions(redissonClient);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void validatesPrefixBeforeResolvingReturnType(String prefix) {
        try (MockedStatic<RScript.ReturnType> returnType = mockStatic(RScript.ReturnType.class)) {
            IllegalArgumentException error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    RedissonAgentStateStore.builder()
                                            .keyPrefix(prefix)
                                            .redissonClient(redissonClient)
                                            .build());
            assertEquals("Key prefix cannot be null or empty", error.getMessage());
            returnType.verifyNoInteractions();
            verifyNoInteractions(redissonClient);
        }
    }

    @Test
    void validatesClientBeforeResolvingReturnType() {
        try (MockedStatic<RScript.ReturnType> returnType = mockStatic(RScript.ReturnType.class)) {
            IllegalArgumentException error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> RedissonAgentStateStore.builder().build());
            assertEquals("RedissonClient cannot be null", error.getMessage());
            returnType.verifyNoInteractions();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {6L, -1L})
    void saveIfVersionPreservesVersionAndConflictResults(long result) {
        RedissonAgentStateStore store =
                RedissonAgentStateStore.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("test:")
                        .build();
        RScript script = mock(RScript.class);
        RScript.ReturnType longReturnType = RScript.ReturnType.valueOf("LONG");
        List<Object> keys =
                List.of(
                        "test:user/session:agent_state",
                        "test:user/session:agent_state:ver",
                        "test:user/session:_keys");
        when(redissonClient.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.eval(
                        RScript.Mode.READ_WRITE,
                        RedisStateVersionSupport.SAVE_SCRIPT,
                        longReturnType,
                        keys,
                        "{\"value\":\"v\"}",
                        "5",
                        "agent_state"))
                .thenReturn(result);

        try (MockedStatic<RScript.ReturnType> returnType = mockStatic(RScript.ReturnType.class)) {
            assertEquals(
                    result == -1L ? AgentStateStore.UNVERSIONED : result,
                    store.saveIfVersion("user", "session", "agent_state", new TestState("v"), 5L));
            returnType.verifyNoInteractions();
        }
        verify(redissonClient).getScript(StringCodec.INSTANCE);
        verify(script)
                .eval(
                        RScript.Mode.READ_WRITE,
                        RedisStateVersionSupport.SAVE_SCRIPT,
                        longReturnType,
                        keys,
                        "{\"value\":\"v\"}",
                        "5",
                        "agent_state");
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
        verify(redissonClient).getScript(StringCodec.INSTANCE);
        verify(rScript)
                .eval(
                        RScript.Mode.READ_WRITE,
                        RedisStateVersionSupport.SAVE_SCRIPT,
                        RScript.ReturnType.valueOf("LONG"),
                        List.of(
                                "test:user/session:agent_state",
                                "test:user/session:agent_state:ver",
                                "test:user/session:_keys"),
                        "{\"value\":\"v\"}",
                        RedisStateVersionSupport.UNCONDITIONAL,
                        "agent_state");
        // Regression: the UNVERSIONED path must not call getVersioned (which reads back as
        // State.class — a marker interface Jackson cannot instantiate).
        verify(redissonClient, never()).getBucket(any(), any());
    }
}
