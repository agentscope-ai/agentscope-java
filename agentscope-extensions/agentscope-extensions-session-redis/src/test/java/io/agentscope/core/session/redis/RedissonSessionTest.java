/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.session.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.session.SessionInfo;
import io.agentscope.core.session.redis.redisson.RedissonSession;
import io.agentscope.core.state.StateModule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link RedissonSession}.
 */
class RedissonSessionTest {

    private RedissonClient redissonClient;
    // Use raw types to avoid Mockito generic erasure compilation issues
    private RBucket bucket;
    private RMap metaMap;
    private RKeys keys;

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        metaMap = mock(RMap.class);
        keys = mock(RKeys.class);
    }

    @Test
    void testBuilderWithValidArguments() {
        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();
        assertNotNull(session);
    }

    @Test
    void testBuilderWithEmptyPrefix() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RedissonSession.builder()
                                .redissonClient(redissonClient)
                                .keyPrefix("  ")
                                .build());
    }

    @Test
    void testSaveSessionStateStoresJsonAndMeta() {
        when(redissonClient.getBucket(anyString(), any())).thenReturn(bucket);
        when(redissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class)))
                .thenReturn(metaMap);

        StateModule module1 = mock(StateModule.class);
        StateModule module2 = mock(StateModule.class);

        Map<String, Object> state1 = new HashMap<>();
        state1.put("key1", "value1");
        Map<String, Object> state2 = new HashMap<>();
        state2.put("key2", 42);

        when(module1.stateDict()).thenReturn(state1);
        when(module2.stateDict()).thenReturn(state2);

        Map<String, StateModule> modules = new HashMap<>();
        modules.put("module1", module1);
        modules.put("module2", module2);

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();
        session.saveSessionState("session1", modules);

        verify(redissonClient).getBucket(eq("agentscope:session:session1"), any());
        verify(bucket).set(org.mockito.ArgumentMatchers.anyString());
        verify(redissonClient)
                .getMap(
                        eq("agentscope:session:session1:meta"),
                        any(org.redisson.client.codec.Codec.class));
        verify(metaMap).put(org.mockito.ArgumentMatchers.eq("lastModified"), anyString());
    }

    @Test
    void testLoadSessionStateRestoresModules() throws Exception {
        String json =
                """
                {
                  "module1": { "key1": "value1" },
                  "module2": { "key2": 42 }
                }
                """;

        when(redissonClient.getBucket(eq("agentscope:session:session1"), any())).thenReturn(bucket);
        when(bucket.get()).thenReturn(json);

        StateModule module1 = mock(StateModule.class);
        StateModule module2 = mock(StateModule.class);

        Map<String, StateModule> modules = new HashMap<>();
        modules.put("module1", module1);
        modules.put("module2", module2);

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();
        session.loadSessionState("session1", false, modules);

        verify(module1)
                .loadStateDict(
                        org.mockito.ArgumentMatchers.anyMap(),
                        org.mockito.ArgumentMatchers.eq(false));
        verify(module2)
                .loadStateDict(
                        org.mockito.ArgumentMatchers.anyMap(),
                        org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void testLoadSessionStateAllowNotExistTrue() {
        when(redissonClient.getBucket(eq("agentscope:session:missing"), any())).thenReturn(bucket);
        when(bucket.get()).thenReturn(null);

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();
        session.loadSessionState("missing", true, Map.of());
    }

    @Test
    void testLoadSessionStateAllowNotExistFalse() {
        when(redissonClient.getBucket(eq("agentscope:session:missing"), any())).thenReturn(bucket);
        when(bucket.get()).thenReturn(null);

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();
        assertThrows(
                RuntimeException.class, () -> session.loadSessionState("missing", false, Map.of()));
    }

    @Test
    void testSessionExists() {
        when(redissonClient.getBucket(eq("agentscope:session:session1"), any())).thenReturn(bucket);
        when(bucket.isExists()).thenReturn(true);

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();
        assertTrue(session.sessionExists("session1"));
    }

    @Test
    void testSessionDoesNotExist() {
        when(redissonClient.getBucket(eq("agentscope:session:session1"), any())).thenReturn(bucket);
        when(bucket.isExists()).thenReturn(false);

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();
        assertFalse(session.sessionExists("session1"));
    }

    @Test
    void testDeleteSession() {
        when(redissonClient.getKeys()).thenReturn(keys);
        when(keys.delete("agentscope:session:session1", "agentscope:session:session1:meta"))
                .thenReturn(2L);

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();
        assertTrue(session.deleteSession("session1"));
    }

    @Test
    void testListSessions() {
        when(redissonClient.getKeys()).thenReturn(keys);
        when(keys.getKeysByPattern("agentscope:session:*"))
                .thenReturn(
                        List.of(
                                "agentscope:session:s1",
                                "agentscope:session:s2",
                                "agentscope:session:s1:meta"));

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();
        List<String> sessions = session.listSessions();

        assertEquals(List.of("s1", "s2"), sessions);
    }

    @Test
    void testGetSessionInfo() {
        String json =
                """
                {
                  "module1": { "key1": "value1" },
                  "module2": { "key2": 42 }
                }
                """;

        when(redissonClient.getBucket(eq("agentscope:session:session1"), any())).thenReturn(bucket);
        when(bucket.get()).thenReturn(json);

        when(redissonClient.getMap(
                        eq("agentscope:session:session1:meta"),
                        any(org.redisson.client.codec.Codec.class)))
                .thenReturn(metaMap);
        when(metaMap.get("lastModified")).thenReturn("12345");

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();
        SessionInfo info = session.getSessionInfo("session1");

        assertEquals("session1", info.getSessionId());
        assertEquals(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, info.getSize());
        assertEquals(2, info.getComponentCount());
        assertEquals(12345L, info.getLastModified());
    }

    @Test
    void testClearAllSessions() {
        when(redissonClient.getKeys()).thenReturn(keys);
        when(keys.getKeysByPattern("agentscope:session:*"))
                .thenReturn(
                        List.of(
                                "agentscope:session:s1",
                                "agentscope:session:s2",
                                "agentscope:session:s1:meta",
                                "agentscope:session:s2:meta"));

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();

        StepVerifier.create(session.clearAllSessions()).expectNext(2).verifyComplete();
    }

    @Test
    void testCloseShutsDownClient() {
        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();
        session.close();
        verify(redissonClient).shutdown();
    }
}
