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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.session.SessionInfo;
import io.agentscope.core.session.redis.jedis.JedisSession;
import io.agentscope.core.state.StateModule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.resps.ScanResult;

/**
 * Unit tests for {@link JedisSession}.
 */
class JedisSessionTest {

    private JedisPool jedisPool;
    private Jedis jedis;

    @BeforeEach
    void setUp() {
        jedisPool = mock(JedisPool.class);
        jedis = mock(Jedis.class);
    }

    @Test
    void testBuilderWithValidArguments() {
        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();
        assertNotNull(session);
    }

    @Test
    void testBuilderWithEmptyPrefix() {
        assertThrows(
                IllegalArgumentException.class,
                () -> JedisSession.builder().jedisPool(jedisPool).keyPrefix("  ").build());
    }

    @Test
    void testSaveSessionStateStoresJsonAndMeta() {
        when(jedisPool.getResource()).thenReturn(jedis);

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

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();
        session.saveSessionState("session1", modules);

        verify(jedisPool).getResource();
        verify(jedis).set(anyString(), anyString());
        verify(jedis).hset(anyString(), anyMap());
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

        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.get("agentscope:session:session1")).thenReturn(json);

        StateModule module1 = mock(StateModule.class);
        StateModule module2 = mock(StateModule.class);

        Map<String, StateModule> modules = new HashMap<>();
        modules.put("module1", module1);
        modules.put("module2", module2);

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();
        session.loadSessionState("session1", false, modules);

        verify(module1).loadStateDict(anyMap(), org.mockito.ArgumentMatchers.eq(false));
        verify(module2).loadStateDict(anyMap(), org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void testLoadSessionStateAllowNotExistTrue() {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.get("agentscope:session:missing")).thenReturn(null);

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();
        session.loadSessionState("missing", true, Map.of());
    }

    @Test
    void testLoadSessionStateAllowNotExistFalse() {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.get("agentscope:session:missing")).thenReturn(null);

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();
        assertThrows(
                RuntimeException.class, () -> session.loadSessionState("missing", false, Map.of()));
    }

    @Test
    void testSessionExists() {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.exists("agentscope:session:session1")).thenReturn(true);

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();
        assertTrue(session.sessionExists("session1"));
    }

    @Test
    void testSessionDoesNotExist() {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.exists("agentscope:session:session1")).thenReturn(false);

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();
        assertFalse(session.sessionExists("session1"));
    }

    @Test
    void testDeleteSession() {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.del("agentscope:session:session1", "agentscope:session:session1:meta"))
                .thenReturn(2L);

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();
        assertTrue(session.deleteSession("session1"));
    }

    @Test
    void testListSessions() {
        when(jedisPool.getResource()).thenReturn(jedis);

        @SuppressWarnings("unchecked")
        ScanResult<String> scanResult = mock(ScanResult.class);
        when(scanResult.getResult())
                .thenReturn(
                        List.of(
                                "agentscope:session:s1",
                                "agentscope:session:s2",
                                "agentscope:session:s1:meta"));
        when(scanResult.getCursor()).thenReturn("0");

        when(jedis.scan(anyString())).thenReturn(scanResult);

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
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

        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.get("agentscope:session:session1")).thenReturn(json);
        when(jedis.hget("agentscope:session:session1:meta", "lastModified")).thenReturn("12345");

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
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
        when(jedisPool.getResource()).thenReturn(jedis);

        @SuppressWarnings("unchecked")
        ScanResult<String> scanResult = mock(ScanResult.class);
        when(scanResult.getResult())
                .thenReturn(
                        List.of(
                                "agentscope:session:s1",
                                "agentscope:session:s2",
                                "agentscope:session:s1:meta",
                                "agentscope:session:s2:meta"));
        when(scanResult.getCursor()).thenReturn("0");

        when(jedis.scan(anyString())).thenReturn(scanResult);
        when(jedis.del(any(String[].class))).thenReturn(4L);

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();

        StepVerifier.create(session.clearAllSessions()).expectNext(2).verifyComplete();
    }

    @Test
    void testCloseShutsDownPool() {
        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();
        session.close();
        verify(jedisPool).close();
    }
}
