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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.State;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RList;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.client.codec.Codec;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link RedisAgentStateStore} with Redisson client.
 */
@DisplayName("RedisAgentStateStore with Redisson Tests")
@SuppressWarnings({"unchecked", "rawtypes"})
class RedissonAgentStateStoreTest {

    private RedissonClient redissonClient;
    private RBucket bucket;
    private RList rList;
    private RSet rSet;
    private RKeys keys;

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        rList = mock(RList.class);
        rSet = mock(RSet.class);
        keys = mock(RKeys.class);
        when(redissonClient.getBucket(anyString(), any(Codec.class))).thenReturn(bucket);
        when(redissonClient.getList(anyString(), any(Codec.class))).thenReturn(rList);
        when(redissonClient.getSet(anyString(), any(Codec.class))).thenReturn(rSet);
        when(redissonClient.getKeys()).thenReturn(keys);
    }

    @Test
    @DisplayName("Should build stateStore with valid arguments")
    void testBuilderWithValidArguments() {
        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .redissonClient(redissonClient)
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
                                .redissonClient(redissonClient)
                                .keyPrefix("  ")
                                .build());
    }

    @Test
    @DisplayName("Should save and get single state correctly")
    void testSaveAndGetSingleState() {
        String stateJson = "{\"value\":\"test_value\",\"count\":42}";
        when(bucket.get()).thenReturn(stateJson);

        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:stateStore:")
                        .build();

        String sessionKey = "session1";
        TestState state = new TestState("test_value", 42);

        // Save state
        stateStore.save(null, sessionKey, "testModule", state);

        // Verify save operations
        verify(bucket).set(anyString());
        verify(rSet).add("testModule");

        // Get state
        Optional<TestState> loaded =
                stateStore.get(null, sessionKey, "testModule", TestState.class);
        assertTrue(loaded.isPresent());
        assertEquals("test_value", loaded.get().value());
        assertEquals(42, loaded.get().count());
    }

    @Test
    @DisplayName("Should save and get list state correctly")
    void testSaveAndGetListState() {
        when(rList.size()).thenReturn(0);
        when(rList.isEmpty()).thenReturn(false);
        when(rList.iterator())
                .thenReturn(
                        List.of(
                                        "{\"value\":\"value1\",\"count\":1}",
                                        "{\"value\":\"value2\",\"count\":2}")
                                .iterator());
        when(rList.range(0, -1))
                .thenReturn(
                        List.of(
                                "{\"value\":\"value1\",\"count\":1}",
                                "{\"value\":\"value2\",\"count\":2}"));

        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:stateStore:")
                        .build();

        String sessionKey = "session1";
        List<TestState> states = List.of(new TestState("value1", 1), new TestState("value2", 2));

        // Save list state
        stateStore.save(null, sessionKey, "testList", states);

        // Verify add was called
        verify(rList, atLeast(1)).add(anyString());

        // Get list state
        List<TestState> loaded = stateStore.getList(null, sessionKey, "testList", TestState.class);
        assertEquals(2, loaded.size());
        assertEquals("value1", loaded.get(0).value());
        assertEquals("value2", loaded.get(1).value());
    }

    @Test
    @DisplayName("Should return empty for non-existent state")
    void testGetNonExistentState() {
        when(redissonClient.getBucket(
                        eq("agentscope:stateStore:__anon__/non_existent:testModule"),
                        any(Codec.class)))
                .thenReturn(bucket);
        when(bucket.get()).thenReturn(null);

        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:stateStore:")
                        .build();

        String sessionKey = "non_existent";
        Optional<TestState> state = stateStore.get(null, sessionKey, "testModule", TestState.class);
        assertFalse(state.isPresent());
    }

    @Test
    @DisplayName("Should return empty list for non-existent list state")
    void testGetNonExistentListState() {
        when(redissonClient.getList(
                        eq("agentscope:stateStore:__anon__/non_existent:testList:list"),
                        any(Codec.class)))
                .thenReturn(rList);
        when(rList.isEmpty()).thenReturn(true);

        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:stateStore:")
                        .build();

        String sessionKey = "non_existent";
        List<TestState> states = stateStore.getList(null, sessionKey, "testList", TestState.class);
        assertTrue(states.isEmpty());
    }

    @Test
    @DisplayName("Should return true when stateStore exists")
    void testSessionExists() {
        when(keys.countExists("agentscope:stateStore:__anon__/session1:_keys")).thenReturn(1L);
        when(rSet.size()).thenReturn(2);

        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:stateStore:")
                        .build();

        String sessionKey = "session1";
        assertTrue(stateStore.exists(null, sessionKey));
    }

    @Test
    @DisplayName("Should return false when stateStore does not exist")
    void testSessionDoesNotExist() {
        when(rSet.isExists()).thenReturn(false);

        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:stateStore:")
                        .build();

        String sessionKey = "session1";
        assertFalse(stateStore.exists(null, sessionKey));
    }

    @Test
    @DisplayName("Should delete stateStore correctly")
    void testDeleteSession() {
        Set<String> trackedKeys = Set.of("module1", "module2:list");
        when(rSet.iterator()).thenReturn(trackedKeys.iterator());

        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:stateStore:")
                        .build();

        String sessionKey = "session1";
        stateStore.delete(null, sessionKey);

        // Verify iterator was called to get tracked keys
        verify(rSet).iterator();
    }

    @Test
    @DisplayName("Should list all stateStore keys")
    void testListSessionKeys() {
        when(redissonClient.getKeys()).thenReturn(keys);
        when(keys.getKeys(any(KeysScanOptions.class)))
                .thenReturn(
                        List.of(
                                "agentscope:stateStore:__anon__/session1:_keys",
                                "agentscope:stateStore:__anon__/session2:_keys"));

        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:stateStore:")
                        .build();

        Set<String> sessionIds = stateStore.listSessionIds(null);
        assertEquals(2, sessionIds.size());
        assertTrue(sessionIds.contains("session1"));
        assertTrue(sessionIds.contains("session2"));
    }

    @Test
    @DisplayName("Should clear all sessions")
    void testClearAllSessions() {
        when(redissonClient.getKeys()).thenReturn(keys);
        when(keys.getKeys(any(KeysScanOptions.class)))
                .thenReturn(
                        List.of(
                                "agentscope:stateStore:__anon__/s1:module1",
                                "agentscope:stateStore:__anon__/s1:_keys",
                                "agentscope:stateStore:__anon__/s2:module1",
                                "agentscope:stateStore:__anon__/s2:_keys"));

        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:stateStore:")
                        .build();

        StepVerifier.create(stateStore.clearAllSessions()).expectNext(4).verifyComplete();
    }

    @Test
    @DisplayName("Should shutdown client when closing stateStore")
    void testCloseShutsDownClient() {
        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:stateStore:")
                        .build();
        stateStore.close();
        verify(redissonClient).shutdown();
    }

    @Test
    @DisplayName("Should build stateStore with cluster mode RedissonClient")
    void testBuilderWithClusterRedissonClient() {
        // Redisson uses the same RedissonClient interface for all modes
        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:stateStore:")
                        .build();
        assertNotNull(stateStore);
    }

    @Test
    @DisplayName("Should build stateStore with sentinel mode RedissonClient")
    void testBuilderWithSentinelRedissonClient() {
        // Redisson uses the same RedissonClient interface for all modes
        RedisAgentStateStore stateStore =
                RedisAgentStateStore.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:stateStore:")
                        .build();
        assertNotNull(stateStore);
    }

    public record TestState(String value, int count) implements State {}
}
