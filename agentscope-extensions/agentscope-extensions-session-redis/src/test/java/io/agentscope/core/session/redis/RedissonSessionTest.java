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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.session.redis.redisson.RedissonSession;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
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
import org.redisson.client.codec.Codec;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link RedissonSession}.
 */
@DisplayName("RedissonSession Tests")
@SuppressWarnings({"unchecked", "rawtypes"})
class RedissonSessionTest {

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
    }

    @Test
    @DisplayName("Should build session with valid arguments")
    void testBuilderWithValidArguments() {
        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();
        assertNotNull(session);
    }

    @Test
    @DisplayName("Should throw exception when building with empty prefix")
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
    @DisplayName("Should save and get single state correctly")
    void testSaveAndGetSingleState() {
        when(redissonClient.getBucket(
                        eq("agentscope:session:session1:testModule"), any(Codec.class)))
                .thenReturn(bucket);
        when(redissonClient.getSet(eq("agentscope:session:session1:_keys"), any(Codec.class)))
                .thenReturn(rSet);

        String stateJson = "{\"value\":\"test_value\",\"count\":42}";
        when(bucket.get()).thenReturn(stateJson);

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("session1");
        TestState state = new TestState("test_value", 42);

        // Save state
        session.save(sessionKey, "testModule", state);

        // Verify save operations
        verify(bucket).set(anyString());
        verify(rSet).add("testModule");

        // Get state
        Optional<TestState> loaded = session.get(sessionKey, "testModule", TestState.class);
        assertTrue(loaded.isPresent());
        assertEquals("test_value", loaded.get().value());
        assertEquals(42, loaded.get().count());
    }

    @Test
    @DisplayName("Should save and get list state correctly")
    void testSaveAndGetListState() {
        when(redissonClient.getList(
                        eq("agentscope:session:session1:testList:list"), any(Codec.class)))
                .thenReturn(rList);
        when(redissonClient.getSet(eq("agentscope:session:session1:_keys"), any(Codec.class)))
                .thenReturn(rSet);

        when(rList.size()).thenReturn(0);
        when(rList.isEmpty()).thenReturn(false);
        when(rList.iterator())
                .thenReturn(
                        List.of(
                                        "{\"value\":\"value1\",\"count\":1}",
                                        "{\"value\":\"value2\",\"count\":2}")
                                .iterator());

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("session1");
        List<TestState> states = List.of(new TestState("value1", 1), new TestState("value2", 2));

        // Save list state
        session.save(sessionKey, "testList", states);

        // Verify add was called
        verify(rList, atLeast(1)).add(anyString());

        // Get list state
        List<TestState> loaded = session.getList(sessionKey, "testList", TestState.class);
        assertEquals(2, loaded.size());
        assertEquals("value1", loaded.get(0).value());
        assertEquals("value2", loaded.get(1).value());
    }

    @Test
    @DisplayName("Should return empty for non-existent state")
    void testGetNonExistentState() {
        when(redissonClient.getBucket(
                        eq("agentscope:session:non_existent:testModule"), any(Codec.class)))
                .thenReturn(bucket);
        when(bucket.get()).thenReturn(null);

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("non_existent");
        Optional<TestState> state = session.get(sessionKey, "testModule", TestState.class);
        assertFalse(state.isPresent());
    }

    @Test
    @DisplayName("Should return empty list for non-existent list state")
    void testGetNonExistentListState() {
        when(redissonClient.getList(
                        eq("agentscope:session:non_existent:testList:list"), any(Codec.class)))
                .thenReturn(rList);
        when(rList.isEmpty()).thenReturn(true);

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("non_existent");
        List<TestState> states = session.getList(sessionKey, "testList", TestState.class);
        assertTrue(states.isEmpty());
    }

    @Test
    @DisplayName("Should return true when session exists")
    void testSessionExists() {
        when(redissonClient.getSet(eq("agentscope:session:session1:_keys"), any(Codec.class)))
                .thenReturn(rSet);
        when(rSet.isExists()).thenReturn(true);
        when(rSet.size()).thenReturn(2);

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("session1");
        assertTrue(session.exists(sessionKey));
    }

    @Test
    @DisplayName("Should return false when session does not exist")
    void testSessionDoesNotExist() {
        when(redissonClient.getSet(eq("agentscope:session:session1:_keys"), any(Codec.class)))
                .thenReturn(rSet);
        when(rSet.isExists()).thenReturn(false);

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("session1");
        assertFalse(session.exists(sessionKey));
    }

    @Test
    @DisplayName("Should delete session correctly")
    void testDeleteSession() {
        when(redissonClient.getSet(eq("agentscope:session:session1:_keys"), any(Codec.class)))
                .thenReturn(rSet);
        when(redissonClient.getKeys()).thenReturn(keys);
        when(rSet.readAll()).thenReturn(Set.of("module1", "module2:list"));

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();

        SessionKey sessionKey = SimpleSessionKey.of("session1");
        session.delete(sessionKey);

        // Verify readAll was called to get tracked keys
        verify(rSet).readAll();
    }

    @Test
    @DisplayName("Should list all session keys")
    void testListSessionKeys() {
        when(redissonClient.getKeys()).thenReturn(keys);
        when(keys.getKeysByPattern("agentscope:session:*:_keys"))
                .thenReturn(
                        List.of(
                                "agentscope:session:session1:_keys",
                                "agentscope:session:session2:_keys"));

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
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
        when(redissonClient.getKeys()).thenReturn(keys);
        when(keys.getKeysByPattern("agentscope:session:*"))
                .thenReturn(
                        List.of(
                                "agentscope:session:s1:module1",
                                "agentscope:session:s1:_keys",
                                "agentscope:session:s2:module1",
                                "agentscope:session:s2:_keys"));

        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();

        StepVerifier.create(session.clearAllSessions()).expectNext(4).verifyComplete();
    }

    @Test
    @DisplayName("Should shutdown client when closing session")
    void testCloseShutsDownClient() {
        RedissonSession session =
                RedissonSession.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("agentscope:session:")
                        .build();
        session.close();
        verify(redissonClient).shutdown();
    }

    /** Simple test state record for testing. */
    public record TestState(String value, int count) implements State {}
}
