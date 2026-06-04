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
package io.agentscope.core.state.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
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
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/**
 * Unit tests for {@link RedisAgentStateStore} with Jedis client.
 */
@DisplayName("RedisAgentStateStore with Jedis Tests")
class JedisAgentStateStoreTest {

    private UnifiedJedis unifiedJedis;

    @BeforeEach
    void setUp() {
        unifiedJedis = mock(UnifiedJedis.class);
    }

    @Test
    @DisplayName("Should build stateStore with UnifiedJedis")
    void testBuilderWithUnifiedJedis() {
        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .jedisClient(unifiedJedis)
                        .keyPrefix("agentscope:stateStore:")
                        .build();
        assertNotNull(stateStore);
    }

    @Test
    @DisplayName("Should throw exception when building with empty prefix")
    void testBuilderWithEmptyPrefix() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RedisAgentStateStore.builder()
                                .jedisClient(unifiedJedis)
                                .keyPrefix("")
                                .build());
    }

    @Test
    @DisplayName("Should save and get single state correctly")
    void testSaveAndGetSingleState() {
        String stateJson = "{\"value\":\"test_value\",\"count\":42}";
        when(unifiedJedis.get("agentscope:stateStore:session1:testModule")).thenReturn(stateJson);

        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .jedisClient(unifiedJedis)
                        .keyPrefix("agentscope:stateStore:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("session1");
        TestState state = new TestState("test_value", 42);

        // Save state
        stateStore.save(null, sessionKey.toIdentifier(), "testModule", state);

        // Verify save operations
        verify(unifiedJedis).set(anyString(), anyString());
        verify(unifiedJedis).sadd("agentscope:stateStore:session1:_keys", "testModule");

        // Get state
        Optional<TestState> loaded =
                stateStore.get(null, sessionKey.toIdentifier(), "testModule", TestState.class);
        assertTrue(loaded.isPresent());
        assertEquals("test_value", loaded.get().value());
        assertEquals(42, loaded.get().count());
    }

    @Test
    @DisplayName("Should save and get list state correctly")
    void testSaveAndGetListState() {
        when(unifiedJedis.llen("agentscope:stateStore:session1:testList:list")).thenReturn(0L);
        when(unifiedJedis.lrange("agentscope:stateStore:session1:testList:list", 0, -1))
                .thenReturn(
                        List.of(
                                "{\"value\":\"value1\",\"count\":1}",
                                "{\"value\":\"value2\",\"count\":2}"));

        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .jedisClient(unifiedJedis)
                        .keyPrefix("agentscope:stateStore:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("session1");
        List<TestState> states = List.of(new TestState("value1", 1), new TestState("value2", 2));

        // Save list state
        stateStore.save(null, sessionKey.toIdentifier(), "testList", states);

        // Verify rpush was called for each item
        verify(unifiedJedis, atLeast(1)).rpush(anyString(), anyString());

        // Get list state
        List<TestState> loaded =
                stateStore.getList(null, sessionKey.toIdentifier(), "testList", TestState.class);
        assertEquals(2, loaded.size());
        assertEquals("value1", loaded.get(0).value());
        assertEquals("value2", loaded.get(1).value());
    }

    @Test
    @DisplayName("Should return empty for non-existent state")
    void testGetNonExistentState() {
        when(unifiedJedis.get("agentscope:stateStore:non_existent:testModule")).thenReturn(null);

        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .jedisClient(unifiedJedis)
                        .keyPrefix("agentscope:stateStore:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("non_existent");
        Optional<TestState> state =
                stateStore.get(null, sessionKey.toIdentifier(), "testModule", TestState.class);
        assertFalse(state.isPresent());
    }

    @Test
    @DisplayName("Should return empty list for non-existent list state")
    void testGetNonExistentListState() {
        when(unifiedJedis.lrange("agentscope:stateStore:non_existent:testList:list", 0, -1))
                .thenReturn(List.of());

        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .jedisClient(unifiedJedis)
                        .keyPrefix("agentscope:stateStore:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("non_existent");
        List<TestState> states =
                stateStore.getList(null, sessionKey.toIdentifier(), "testList", TestState.class);
        assertTrue(states.isEmpty());
    }

    @Test
    @DisplayName("Should return true when stateStore exists")
    void testSessionExists() {
        when(unifiedJedis.exists("agentscope:stateStore:session1:_keys")).thenReturn(true);
        when(unifiedJedis.scard("agentscope:stateStore:session1:_keys")).thenReturn(2L);

        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .jedisClient(unifiedJedis)
                        .keyPrefix("agentscope:stateStore:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("session1");
        assertTrue(stateStore.exists(null, sessionKey.toIdentifier()));
    }

    @Test
    @DisplayName("Should return false when stateStore does not exist")
    void testSessionDoesNotExist() {
        when(unifiedJedis.exists("agentscope:stateStore:session1:_keys")).thenReturn(false);

        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .jedisClient(unifiedJedis)
                        .keyPrefix("agentscope:stateStore:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("session1");
        assertFalse(stateStore.exists(null, sessionKey.toIdentifier()));
    }

    @Test
    @DisplayName("Should delete stateStore correctly")
    void testDeleteSession() {
        Set<String> trackedKeys = new HashSet<>();
        trackedKeys.add("module1");
        trackedKeys.add("module2:list");
        when(unifiedJedis.smembers("agentscope:stateStore:session1:_keys")).thenReturn(trackedKeys);

        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .jedisClient(unifiedJedis)
                        .keyPrefix("agentscope:stateStore:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("session1");
        stateStore.delete(null, sessionKey.toIdentifier());

        // Verify del was called with the keys
        verify(unifiedJedis).smembers("agentscope:stateStore:session1:_keys");
    }

    @Test
    @DisplayName("Should list all stateStore keys")
    void testListSessionKeys() {
        Set<String> keysKeys = new HashSet<>();
        keysKeys.add("agentscope:stateStore:session1:_keys");
        keysKeys.add("agentscope:stateStore:session2:_keys");
        ScanResult<String> scanResult = mock(ScanResult.class);
        when(scanResult.getResult()).thenReturn(new ArrayList<>(keysKeys));
        when(scanResult.getCursor()).thenReturn(ScanParams.SCAN_POINTER_START);
        when(unifiedJedis.scan(anyString(), any(ScanParams.class))).thenReturn(scanResult);

        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .jedisClient(unifiedJedis)
                        .keyPrefix("agentscope:stateStore:")
                        .build();

        Set<String> sessionIds = stateStore.listSessionIds(null);
        assertEquals(2, sessionIds.size());
        assertTrue(sessionIds.contains("__anon__/session1"));
        assertTrue(sessionIds.contains("__anon__/session2"));
    }

    @Test
    @DisplayName("Should clear all sessions")
    void testClearAllSessions() {
        Set<String> allKeys = new HashSet<>();
        allKeys.add("agentscope:stateStore:s1:module1");
        allKeys.add("agentscope:stateStore:s1:_keys");
        allKeys.add("agentscope:stateStore:s2:module1");
        allKeys.add("agentscope:stateStore:s2:_keys");
        ScanResult<String> scanResult = mock(ScanResult.class);
        when(scanResult.getResult()).thenReturn(new ArrayList<>(allKeys));
        when(scanResult.getCursor()).thenReturn(ScanParams.SCAN_POINTER_START);
        when(unifiedJedis.scan(anyString(), any(ScanParams.class))).thenReturn(scanResult);

        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .jedisClient(unifiedJedis)
                        .keyPrefix("agentscope:stateStore:")
                        .build();

        StepVerifier.create(stateStore.clearAllSessions()).expectNext(4).verifyComplete();
    }

    @Test
    @DisplayName("Should close jedis client when closing stateStore")
    void testCloseShutsDownClient() {
        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .jedisClient(unifiedJedis)
                        .keyPrefix("agentscope:stateStore:")
                        .build();
        stateStore.close();
        verify(unifiedJedis).close();
    }

    /** Simple test state record for testing. */
    public record TestState(String value, int count) implements State {}
}
