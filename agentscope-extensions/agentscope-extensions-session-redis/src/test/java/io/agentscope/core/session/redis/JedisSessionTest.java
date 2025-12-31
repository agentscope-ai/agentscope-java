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
package io.agentscope.core.session.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.session.redis.jedis.JedisSession;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.State;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Unit tests for {@link JedisSession}.
 */
@DisplayName("JedisSession Tests")
class JedisSessionTest {

    private JedisPool jedisPool;
    private Jedis jedis;

    @BeforeEach
    void setUp() {
        jedisPool = mock(JedisPool.class);
        jedis = mock(Jedis.class);
    }

    @Test
    @DisplayName("Should build session with valid arguments")
    void testBuilderWithValidArguments() {
        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();
        assertNotNull(session);
    }

    @Test
    @DisplayName("Should throw exception when building with empty prefix")
    void testBuilderWithEmptyPrefix() {
        assertThrows(
                IllegalArgumentException.class,
                () -> JedisSession.builder().jedisPool(jedisPool).keyPrefix("  ").build());
    }

    @Test
    @DisplayName("Should save and get single state correctly")
    void testSaveAndGetSingleState() {
        when(jedisPool.getResource()).thenReturn(jedis);

        String stateJson = "{\"value\":\"test_value\",\"count\":42}";
        when(jedis.get("agentscope:session:session1:testModule")).thenReturn(stateJson);

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("session1");
        TestState state = new TestState("test_value", 42);

        // Save state
        session.save(sessionKey, "testModule", state);

        // Verify save operations
        verify(jedis).set(anyString(), anyString());
        verify(jedis).sadd("agentscope:session:session1:_keys", "testModule");

        // Get state
        Optional<TestState> loaded = session.get(sessionKey, "testModule", TestState.class);
        assertTrue(loaded.isPresent());
        assertEquals("test_value", loaded.get().value());
        assertEquals(42, loaded.get().count());
    }

    @Test
    @DisplayName("Should save and get list state correctly")
    void testSaveAndGetListState() {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.llen("agentscope:session:session1:testList:list")).thenReturn(0L);
        when(jedis.lrange("agentscope:session:session1:testList:list", 0, -1))
                .thenReturn(
                        List.of(
                                "{\"value\":\"value1\",\"count\":1}",
                                "{\"value\":\"value2\",\"count\":2}"));

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("session1");
        List<TestState> states = List.of(new TestState("value1", 1), new TestState("value2", 2));

        // Save list state
        session.save(sessionKey, "testList", states);

        // Verify rpush was called for each item
        verify(jedis, atLeast(1)).rpush(anyString(), anyString());

        // Get list state
        List<TestState> loaded = session.getList(sessionKey, "testList", TestState.class);
        assertEquals(2, loaded.size());
        assertEquals("value1", loaded.get(0).value());
        assertEquals("value2", loaded.get(1).value());
    }

    @Test
    @DisplayName("Should return empty for non-existent state")
    void testGetNonExistentState() {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.get("agentscope:session:non_existent:testModule")).thenReturn(null);

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("non_existent");
        Optional<TestState> state = session.get(sessionKey, "testModule", TestState.class);
        assertFalse(state.isPresent());
    }

    @Test
    @DisplayName("Should return empty list for non-existent list state")
    void testGetNonExistentListState() {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.lrange("agentscope:session:non_existent:testList:list", 0, -1))
                .thenReturn(List.of());

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("non_existent");
        List<TestState> states = session.getList(sessionKey, "testList", TestState.class);
        assertTrue(states.isEmpty());
    }

    @Test
    @DisplayName("Should return true when session exists")
    void testSessionExists() {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.exists("agentscope:session:session1:_keys")).thenReturn(true);
        when(jedis.scard("agentscope:session:session1:_keys")).thenReturn(2L);

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("session1");
        assertTrue(session.exists(sessionKey));
    }

    @Test
    @DisplayName("Should return false when session does not exist")
    void testSessionDoesNotExist() {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.exists("agentscope:session:session1:_keys")).thenReturn(false);

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("session1");
        assertFalse(session.exists(sessionKey));
    }

    @Test
    @DisplayName("Should delete session correctly")
    void testDeleteSession() {
        when(jedisPool.getResource()).thenReturn(jedis);

        Set<String> trackedKeys = new HashSet<>();
        trackedKeys.add("module1");
        trackedKeys.add("module2:list");
        when(jedis.smembers("agentscope:session:session1:_keys")).thenReturn(trackedKeys);

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("session1");
        session.delete(sessionKey);

        // Verify del was called with the keys
        verify(jedis).smembers("agentscope:session:session1:_keys");
    }

    @Test
    @DisplayName("Should list all session keys")
    void testListSessionKeys() {
        when(jedisPool.getResource()).thenReturn(jedis);

        Set<String> keysKeys = new HashSet<>();
        keysKeys.add("agentscope:session:session1:_keys");
        keysKeys.add("agentscope:session:session2:_keys");
        when(jedis.keys("agentscope:session:*:_keys")).thenReturn(keysKeys);

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();

        Set<SessionKey> sessionKeys = session.listSessionKeys();
        assertEquals(2, sessionKeys.size());
        assertTrue(sessionKeys.contains(SimpleSessionKey.of("session1")));
        assertTrue(sessionKeys.contains(SimpleSessionKey.of("session2")));
    }

    @Test
    @DisplayName("Should clear all sessions")
    void testClearAllSessions() {
        when(jedisPool.getResource()).thenReturn(jedis);

        Set<String> allKeys = new HashSet<>();
        allKeys.add("agentscope:session:s1:module1");
        allKeys.add("agentscope:session:s1:_keys");
        allKeys.add("agentscope:session:s2:module1");
        allKeys.add("agentscope:session:s2:_keys");
        when(jedis.keys("agentscope:session:*")).thenReturn(allKeys);

        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();

        StepVerifier.create(session.clearAllSessions()).expectNext(4).verifyComplete();
    }

    @Test
    @DisplayName("Should close jedis pool when closing session")
    void testCloseShutsDownPool() {
        JedisSession session =
                JedisSession.builder()
                        .jedisPool(jedisPool)
                        .keyPrefix("agentscope:session:")
                        .build();
        session.close();
        verify(jedisPool).close();
    }

    /** Simple test state record for testing. */
    public record TestState(String value, int count) implements State {}
}
