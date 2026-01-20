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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.State;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link RedisSession} with Lettuce client.
 */
@DisplayName("RedisSession with Lettuce Tests")
class LettuceSessionTest {

    private io.lettuce.core.RedisClient redisClient;
    private io.lettuce.core.api.StatefulRedisConnection<String, String> connection;
    private io.lettuce.core.api.sync.RedisCommands<String, String> commands;

    @BeforeEach
    void setUp() {
        redisClient = mock(io.lettuce.core.RedisClient.class);
        connection = mock(io.lettuce.core.api.StatefulRedisConnection.class);
        commands = mock(io.lettuce.core.api.sync.RedisCommands.class);
    }

    @Test
    @DisplayName("Should build session with valid arguments")
    void testBuilderWithValidArguments() {
        RedisSession session =
                RedisSession.builder()
                        .lettuceClient(redisClient)
                        .keyPrefix("agentscope:session:")
                        .build();
        assertNotNull(session);
    }

    @Test
    @DisplayName("Should throw exception when building with empty prefix")
    void testBuilderWithEmptyPrefix() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RedisSession.builder().lettuceClient(redisClient).keyPrefix("  ").build());
    }

    @Test
    @DisplayName("Should save and get single state correctly")
    void testSaveAndGetSingleState() {
        when(redisClient.connect()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);

        String stateJson = "{\"value\":\"test_value\",\"count\":42}";
        when(commands.get("agentscope:session:session1:testModule")).thenReturn(stateJson);

        RedisSession session =
                RedisSession.builder()
                        .lettuceClient(redisClient)
                        .keyPrefix("agentscope:session:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("session1");
        TestState state = new TestState("test_value", 42);

        session.save(sessionKey, "testModule", state);

        verify(commands).set(anyString(), anyString());
        verify(commands).sadd("agentscope:session:session1:_keys", "testModule");

        Optional<TestState> retrievedState = session.get(sessionKey, "testModule", TestState.class);

        verify(commands).get("agentscope:session:session1:testModule");
        assertTrue(retrievedState.isPresent());
        assertEquals("test_value", retrievedState.get().value());
        assertEquals(42, retrievedState.get().count());
    }

    @Test
    @DisplayName("Should save and get list state correctly")
    void testSaveAndGetListState() {
        when(redisClient.connect()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);

        when(commands.llen("agentscope:session:session1:testList:list")).thenReturn(0L);
        List<String> mockList = new ArrayList<>();
        mockList.add("{\"value\":\"item1\",\"count\":1}");
        mockList.add("{\"value\":\"item2\",\"count\":2}");
        when(commands.lrange("agentscope:session:session1:testList:list", 0, -1))
                .thenReturn(mockList);

        RedisSession session =
                RedisSession.builder()
                        .lettuceClient(redisClient)
                        .keyPrefix("agentscope:session:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("session1");
        List<TestState> states = new ArrayList<>();
        states.add(new TestState("item1", 1));
        states.add(new TestState("item2", 2));

        session.save(sessionKey, "testList", states);

        verify(commands).llen("agentscope:session:session1:testList:list");
        verify(commands, times(2)).rpush(anyString(), anyString());
        verify(commands).sadd("agentscope:session:session1:_keys", "testList:list");

        List<TestState> retrievedStates = session.getList(sessionKey, "testList", TestState.class);

        verify(commands).lrange("agentscope:session:session1:testList:list", 0, -1);
        assertEquals(2, retrievedStates.size());
        assertEquals("item1", retrievedStates.get(0).value());
        assertEquals(1, retrievedStates.get(0).count());
        assertEquals("item2", retrievedStates.get(1).value());
        assertEquals(2, retrievedStates.get(1).count());
    }

    @Test
    @DisplayName("Should check session existence correctly")
    void testSessionExists() {
        when(redisClient.connect()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);

        when(commands.exists("agentscope:session:session1:_keys")).thenReturn(1L);
        when(commands.scard("agentscope:session:session1:_keys")).thenReturn(1L);

        when(commands.exists("agentscope:session:session2:_keys")).thenReturn(0L);

        RedisSession session =
                RedisSession.builder()
                        .lettuceClient(redisClient)
                        .keyPrefix("agentscope:session:")
                        .build();

        SessionKey existingSessionKey = SimpleSessionKey.of("session1");
        assertTrue(session.exists(existingSessionKey));

        SessionKey nonExistingSessionKey = SimpleSessionKey.of("session2");
        assertFalse(session.exists(nonExistingSessionKey));
    }

    @Test
    @DisplayName("Should delete session correctly")
    void testDeleteSession() {
        when(redisClient.connect()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);

        Set<String> trackedKeys = new HashSet<>();
        trackedKeys.add("testModule");
        trackedKeys.add("testList:list");
        when(commands.smembers("agentscope:session:session1:_keys")).thenReturn(trackedKeys);

        RedisSession session =
                RedisSession.builder()
                        .lettuceClient(redisClient)
                        .keyPrefix("agentscope:session:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("session1");
        session.delete(sessionKey);

        verify(commands).smembers("agentscope:session:session1:_keys");
        verify(commands)
                .del(
                        "agentscope:session:session1:_keys",
                        "agentscope:session:session1:testModule",
                        "agentscope:session:session1:testList:list",
                        "agentscope:session:session1:testList:list:_hash");
    }

    @Test
    @DisplayName("Should list session keys correctly")
    void testListSessionKeys() {
        when(redisClient.connect()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);

        List<String> keysKeysList = new ArrayList<>();
        keysKeysList.add("agentscope:session:session1:_keys");
        keysKeysList.add("agentscope:session:session2:_keys");
        when(commands.keys("agentscope:session:*:_keys")).thenReturn(keysKeysList);

        RedisSession session =
                RedisSession.builder()
                        .lettuceClient(redisClient)
                        .keyPrefix("agentscope:session:")
                        .build();

        Set<SessionKey> sessionKeys = session.listSessionKeys();

        verify(commands).keys("agentscope:session:*:_keys");
        assertEquals(2, sessionKeys.size());
        Set<String> sessionIds = new HashSet<>();
        for (SessionKey key : sessionKeys) {
            sessionIds.add(key.toIdentifier());
        }
        assertTrue(sessionIds.contains("session1"));
        assertTrue(sessionIds.contains("session2"));
    }

    @Test
    @DisplayName("Should clear all sessions correctly")
    void testClearAllSessions() {
        when(redisClient.connect()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);

        List<String> keysList = new ArrayList<>();
        keysList.add("agentscope:session:session1:_keys");
        keysList.add("agentscope:session:session1:testModule");
        keysList.add("agentscope:session:session2:_keys");
        when(commands.keys("agentscope:session:*")).thenReturn(keysList);

        RedisSession session =
                RedisSession.builder()
                        .lettuceClient(redisClient)
                        .keyPrefix("agentscope:session:")
                        .build();

        StepVerifier.create(session.clearAllSessions()).expectNext(3).verifyComplete();

        verify(commands).keys("agentscope:session:*");
        verify(commands).del(keysList.toArray(new String[0]));
    }

    @Test
    @DisplayName("Should close redis client when session is closed")
    void testClose() {
        RedisSession session =
                RedisSession.builder()
                        .lettuceClient(redisClient)
                        .keyPrefix("agentscope:session:")
                        .build();

        session.close();

        verify(redisClient).shutdown();
    }

    @Test
    @DisplayName("Should build session with cluster mode RedisClient")
    void testBuilderWithClusterRedisClient() {
        // Lettuce uses the same RedisClient class for all modes, just different RedisURI
        // configurations
        RedisSession session =
                RedisSession.builder()
                        .lettuceClient(redisClient)
                        .keyPrefix("agentscope:session:")
                        .build();
        assertNotNull(session);
    }

    @Test
    @DisplayName("Should build session with sentinel mode RedisClient")
    void testBuilderWithSentinelRedisClient() {
        // Lettuce uses the same RedisClient class for all modes, just different RedisURI
        // configurations
        RedisSession session =
                RedisSession.builder()
                        .lettuceClient(redisClient)
                        .keyPrefix("agentscope:session:")
                        .build();
        assertNotNull(session);
    }

    public record TestState(String value, int count) implements State {}
}
