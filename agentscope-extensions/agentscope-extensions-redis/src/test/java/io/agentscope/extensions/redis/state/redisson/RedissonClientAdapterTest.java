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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.extensions.redis.state.RedisClientAdapter;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

class RedissonClientAdapterTest {

    private RedissonClient redissonClient;

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
    }

    @Test
    void constructsAdapterAndUnifiedStoreWithoutContactingRedis() {
        assertDoesNotThrow(() -> RedissonClientAdapter.of(redissonClient));
        assertDoesNotThrow(
                () -> RedisAgentStateStore.builder().redissonClient(redissonClient).build());
        verifyNoInteractions(redissonClient);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsIncompatibleApiAtPublicConstructionBoundary(boolean throughBuilder) {
        IllegalArgumentException cause = new IllegalArgumentException("No enum constant LONG");
        try (MockedStatic<RScript.ReturnType> returnType = mockStatic(RScript.ReturnType.class)) {
            returnType.when(() -> RScript.ReturnType.valueOf("LONG")).thenThrow(cause);

            IllegalStateException error =
                    assertThrows(
                            IllegalStateException.class,
                            () -> {
                                if (throughBuilder) {
                                    RedisAgentStateStore.builder().redissonClient(redissonClient);
                                } else {
                                    RedissonClientAdapter.of(redissonClient);
                                }
                            });

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

    @Test
    void customAdapterDoesNotResolveRedissonReturnType() {
        RedisClientAdapter client = mock(RedisClientAdapter.class);
        try (MockedStatic<RScript.ReturnType> returnType = mockStatic(RScript.ReturnType.class)) {
            assertDoesNotThrow(() -> RedisAgentStateStore.builder().clientAdapter(client).build());
            returnType.verifyNoInteractions();
            verifyNoInteractions(client);
        }
    }

    @Test
    void evalScriptDelegatesWithResolvedReturnTypeAndConvertsNumbers() {
        RedissonClientAdapter adapter = RedissonClientAdapter.of(redissonClient);
        RScript script = mock(RScript.class);
        RScript.ReturnType longReturnType = RScript.ReturnType.valueOf("LONG");
        when(redissonClient.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.eval(
                        RScript.Mode.READ_WRITE,
                        "return ARGV[1]",
                        longReturnType,
                        List.of("key1", "key2"),
                        "arg1",
                        "arg2"))
                .thenReturn(42L, 7, BigInteger.valueOf(9));

        try (MockedStatic<RScript.ReturnType> returnType = mockStatic(RScript.ReturnType.class)) {
            assertEquals(
                    42L,
                    adapter.evalScript(
                            "return ARGV[1]", List.of("key1", "key2"), List.of("arg1", "arg2")));
            assertEquals(
                    7L,
                    adapter.evalScript(
                            "return ARGV[1]", List.of("key1", "key2"), List.of("arg1", "arg2")));
            assertEquals(
                    9L,
                    adapter.evalScript(
                            "return ARGV[1]", List.of("key1", "key2"), List.of("arg1", "arg2")));
            returnType.verifyNoInteractions();
        }
        verify(redissonClient, times(3)).getScript(StringCodec.INSTANCE);
        verify(script, times(3))
                .eval(
                        RScript.Mode.READ_WRITE,
                        "return ARGV[1]",
                        longReturnType,
                        List.of("key1", "key2"),
                        "arg1",
                        "arg2");
    }

    @Test
    void evalScriptRejectsNonnumericAndNullResults() {
        RedissonClientAdapter adapter = RedissonClientAdapter.of(redissonClient);
        RScript script = mock(RScript.class);
        when(redissonClient.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.eval(
                        RScript.Mode.READ_WRITE,
                        "return ARGV[1]",
                        RScript.ReturnType.LONG,
                        List.of("key"),
                        "arg"))
                .thenReturn("42", (Object) null);

        assertEquals(
                "Unexpected Lua script result: 42",
                assertThrows(
                                IllegalStateException.class,
                                () ->
                                        adapter.evalScript(
                                                "return ARGV[1]", List.of("key"), List.of("arg")))
                        .getMessage());
        assertEquals(
                "Unexpected Lua script result: null",
                assertThrows(
                                IllegalStateException.class,
                                () ->
                                        adapter.evalScript(
                                                "return ARGV[1]", List.of("key"), List.of("arg")))
                        .getMessage());
    }
}
