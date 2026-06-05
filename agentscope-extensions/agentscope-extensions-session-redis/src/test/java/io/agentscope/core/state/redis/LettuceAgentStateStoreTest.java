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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.State;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link RedisAgentStateStore} with Lettuce client.
 */
@DisplayName("RedisAgentStateStore with Lettuce Tests")
class LettuceAgentStateStoreTest {

    @Nested
    @DisplayName("Standalone/Sentinel Mode Tests")
    class StandaloneModeTests {

        private RedisClient redisClient;

        private StatefulRedisConnection<String, String> connection;

        private RedisCommands<String, String> commands;

        @BeforeEach
        void setUp() {
            redisClient = mock(RedisClient.class);
            connection = mock(StatefulRedisConnection.class);
            commands = mock(RedisCommands.class);
            when(redisClient.connect()).thenReturn(connection);
            when(connection.sync()).thenReturn(commands);
        }

        @Test
        @DisplayName("Should build stateStore with valid arguments")
        void testBuilderWithValidArguments() {
            RedisAgentStateStore stateStore =
                    RedisAgentStateStore.builder()
                            .lettuceClient(redisClient)
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
                                    .lettuceClient(redisClient)
                                    .keyPrefix("  ")
                                    .build());
        }

        @Test
        @DisplayName("Should save and get single state correctly")
        void testSaveAndGetSingleState() {
            String stateJson = "{\"value\":\"test_value\",\"count\":42}";
            when(commands.get("agentscope:stateStore:__anon__/session1:testModule"))
                    .thenReturn(stateJson);

            RedisAgentStateStore stateStore =
                    RedisAgentStateStore.builder()
                            .lettuceClient(redisClient)
                            .keyPrefix("agentscope:stateStore:")
                            .build();

            String sessionKey = "session1";
            TestState state = new TestState("test_value", 42);

            stateStore.save(null, sessionKey, "testModule", state);

            verify(commands).set(anyString(), anyString());
            verify(commands).sadd("agentscope:stateStore:__anon__/session1:_keys", "testModule");

            Optional<TestState> retrievedState =
                    stateStore.get(null, sessionKey, "testModule", TestState.class);

            verify(commands).get("agentscope:stateStore:__anon__/session1:testModule");
            assertTrue(retrievedState.isPresent());
            assertEquals("test_value", retrievedState.get().value());
            assertEquals(42, retrievedState.get().count());
        }

        @Test
        @DisplayName("Should save and get list state correctly")
        void testSaveAndGetListState() {
            when(commands.llen("agentscope:stateStore:__anon__/session1:testList:list"))
                    .thenReturn(0L);
            List<String> mockList = new ArrayList<>();
            mockList.add("{\"value\":\"item1\",\"count\":1}");
            mockList.add("{\"value\":\"item2\",\"count\":2}");
            when(commands.lrange("agentscope:stateStore:__anon__/session1:testList:list", 0, -1))
                    .thenReturn(mockList);

            RedisAgentStateStore stateStore =
                    RedisAgentStateStore.builder()
                            .lettuceClient(redisClient)
                            .keyPrefix("agentscope:stateStore:")
                            .build();

            String sessionKey = "session1";
            List<TestState> states = new ArrayList<>();
            states.add(new TestState("item1", 1));
            states.add(new TestState("item2", 2));

            stateStore.save(null, sessionKey, "testList", states);

            verify(commands).llen("agentscope:stateStore:__anon__/session1:testList:list");
            verify(commands, times(2)).rpush(anyString(), anyString());
            verify(commands).sadd("agentscope:stateStore:__anon__/session1:_keys", "testList:list");

            List<TestState> retrievedStates =
                    stateStore.getList(null, sessionKey, "testList", TestState.class);

            verify(commands).lrange("agentscope:stateStore:__anon__/session1:testList:list", 0, -1);
            assertEquals(2, retrievedStates.size());
            assertEquals("item1", retrievedStates.get(0).value());
            assertEquals(1, retrievedStates.get(0).count());
            assertEquals("item2", retrievedStates.get(1).value());
            assertEquals(2, retrievedStates.get(1).count());
        }

        @Test
        @DisplayName("Should check stateStore existence correctly")
        void testSessionExists() {
            when(commands.exists("agentscope:stateStore:__anon__/session1:_keys")).thenReturn(1L);
            when(commands.scard("agentscope:stateStore:__anon__/session1:_keys")).thenReturn(1L);

            when(commands.exists("agentscope:stateStore:__anon__/session2:_keys")).thenReturn(0L);

            RedisAgentStateStore stateStore =
                    RedisAgentStateStore.builder()
                            .lettuceClient(redisClient)
                            .keyPrefix("agentscope:stateStore:")
                            .build();

            String existingSessionKey = "session1";
            assertTrue(stateStore.exists(null, existingSessionKey));

            String nonExistingSessionKey = "session2";
            assertFalse(stateStore.exists(null, nonExistingSessionKey));
        }

        @Test
        @DisplayName("Should delete stateStore correctly")
        void testDeleteSession() {
            Set<String> trackedKeys = new HashSet<>();
            trackedKeys.add("testModule");
            trackedKeys.add("testList:list");
            when(commands.smembers("agentscope:stateStore:__anon__/session1:_keys"))
                    .thenReturn(trackedKeys);

            RedisAgentStateStore stateStore =
                    RedisAgentStateStore.builder()
                            .lettuceClient(redisClient)
                            .keyPrefix("agentscope:stateStore:")
                            .build();

            String sessionKey = "session1";
            stateStore.delete(null, sessionKey);

            verify(commands).smembers("agentscope:stateStore:__anon__/session1:_keys");
        }

        @Test
        @DisplayName("Should list stateStore keys correctly")
        void testListSessionKeys() {
            KeyScanCursor<String> scanResult = mock(KeyScanCursor.class);
            List<String> keysKeysList = new ArrayList<>();
            keysKeysList.add("agentscope:stateStore:__anon__/session1:_keys");
            keysKeysList.add("agentscope:stateStore:__anon__/session2:_keys");
            when(scanResult.getKeys()).thenReturn(keysKeysList);
            when(scanResult.isFinished()).thenReturn(true);
            when(commands.scan(any(ScanCursor.class), any(ScanArgs.class))).thenReturn(scanResult);

            RedisAgentStateStore stateStore =
                    RedisAgentStateStore.builder()
                            .lettuceClient(redisClient)
                            .keyPrefix("agentscope:stateStore:")
                            .build();

            Set<String> sessionIds = stateStore.listSessionIds(null);

            assertEquals(2, sessionIds.size());
            assertTrue(sessionIds.contains("session1"));
            assertTrue(sessionIds.contains("session2"));
        }

        @Test
        @DisplayName("Should clear all sessions correctly")
        void testClearAllSessions() {
            KeyScanCursor<String> scanResult = mock(KeyScanCursor.class);
            List<String> keysList = new ArrayList<>();
            keysList.add("agentscope:stateStore:__anon__/session1:_keys");
            keysList.add("agentscope:stateStore:__anon__/session1:testModule");
            keysList.add("agentscope:stateStore:__anon__/session2:_keys");
            when(scanResult.getKeys()).thenReturn(keysList);
            when(scanResult.isFinished()).thenReturn(true);
            when(commands.scan(any(ScanCursor.class), any(ScanArgs.class))).thenReturn(scanResult);

            RedisAgentStateStore stateStore =
                    RedisAgentStateStore.builder()
                            .lettuceClient(redisClient)
                            .keyPrefix("agentscope:stateStore:")
                            .build();

            StepVerifier.create(stateStore.clearAllSessions()).expectNext(3).verifyComplete();

            verify(commands).del(any(String[].class));
        }

        @Test
        @DisplayName("Should close redis client when stateStore is closed")
        void testClose() {
            RedisAgentStateStore stateStore =
                    RedisAgentStateStore.builder()
                            .lettuceClient(redisClient)
                            .keyPrefix("agentscope:stateStore:")
                            .build();

            stateStore.close();

            verify(redisClient).shutdown();
        }

        @Test
        @DisplayName("Should build stateStore with sentinel mode RedisClient")
        void testBuilderWithSentinelRedisClient() {
            RedisAgentStateStore stateStore =
                    RedisAgentStateStore.builder()
                            .lettuceClient(redisClient)
                            .keyPrefix("agentscope:stateStore:")
                            .build();
            assertNotNull(stateStore);
        }
    }

    @Nested
    @DisplayName("Cluster Mode Tests")
    class ClusterModeTests {

        private RedisClusterClient redisClusterClient;

        private StatefulRedisClusterConnection<String, String> clusterConnection;

        private RedisAdvancedClusterCommands<String, String> clusterCommands;

        @BeforeEach
        void setUp() {
            redisClusterClient = mock(RedisClusterClient.class);
            clusterConnection = mock(StatefulRedisClusterConnection.class);
            clusterCommands = mock(RedisAdvancedClusterCommands.class);
            when(redisClusterClient.connect()).thenReturn(clusterConnection);
            when(clusterConnection.sync()).thenReturn(clusterCommands);
        }

        @Test
        @DisplayName("Should build stateStore with cluster client")
        void testBuilderWithClusterClient() {
            RedisAgentStateStore stateStore =
                    RedisAgentStateStore.builder()
                            .lettuceClusterClient(redisClusterClient)
                            .keyPrefix("agentscope:stateStore:")
                            .build();
            assertNotNull(stateStore);
        }

        @Test
        @DisplayName("Should save and get single state correctly in cluster mode")
        void testSaveAndGetSingleStateCluster() {
            String stateJson = "{\"value\":\"cluster_value\",\"count\":100}";
            when(clusterCommands.get("agentscope:stateStore:__anon__/clusterSession:testModule"))
                    .thenReturn(stateJson);

            RedisAgentStateStore stateStore =
                    RedisAgentStateStore.builder()
                            .lettuceClusterClient(redisClusterClient)
                            .keyPrefix("agentscope:stateStore:")
                            .build();

            String sessionKey = "clusterSession";
            TestState state = new TestState("cluster_value", 100);

            stateStore.save(null, sessionKey, "testModule", state);

            verify(clusterCommands).set(anyString(), anyString());
            verify(clusterCommands)
                    .sadd("agentscope:stateStore:__anon__/clusterSession:_keys", "testModule");

            Optional<TestState> retrievedState =
                    stateStore.get(null, sessionKey, "testModule", TestState.class);

            verify(clusterCommands).get("agentscope:stateStore:__anon__/clusterSession:testModule");
            assertTrue(retrievedState.isPresent());
            assertEquals("cluster_value", retrievedState.get().value());
            assertEquals(100, retrievedState.get().count());
        }

        @Test
        @DisplayName("Should save and get list state correctly in cluster mode")
        void testSaveAndGetListStateCluster() {
            when(clusterCommands.llen(
                            "agentscope:stateStore:__anon__/clusterSession:testList:list"))
                    .thenReturn(0L);
            List<String> mockList = new ArrayList<>();
            mockList.add("{\"value\":\"cluster_item1\",\"count\":10}");
            mockList.add("{\"value\":\"cluster_item2\",\"count\":20}");
            when(clusterCommands.lrange(
                            "agentscope:stateStore:__anon__/clusterSession:testList:list", 0, -1))
                    .thenReturn(mockList);

            RedisAgentStateStore stateStore =
                    RedisAgentStateStore.builder()
                            .lettuceClusterClient(redisClusterClient)
                            .keyPrefix("agentscope:stateStore:")
                            .build();

            String sessionKey = "clusterSession";
            List<TestState> states = new ArrayList<>();
            states.add(new TestState("cluster_item1", 10));
            states.add(new TestState("cluster_item2", 20));

            stateStore.save(null, sessionKey, "testList", states);

            verify(clusterCommands)
                    .llen("agentscope:stateStore:__anon__/clusterSession:testList:list");
            verify(clusterCommands, times(2)).rpush(anyString(), anyString());
            verify(clusterCommands)
                    .sadd("agentscope:stateStore:__anon__/clusterSession:_keys", "testList:list");

            List<TestState> retrievedStates =
                    stateStore.getList(null, sessionKey, "testList", TestState.class);

            verify(clusterCommands)
                    .lrange("agentscope:stateStore:__anon__/clusterSession:testList:list", 0, -1);
            assertEquals(2, retrievedStates.size());
            assertEquals("cluster_item1", retrievedStates.get(0).value());
            assertEquals(10, retrievedStates.get(0).count());
        }

        @Test
        @DisplayName("Should check stateStore existence correctly in cluster mode")
        void testSessionExistsCluster() {
            when(clusterCommands.exists("agentscope:stateStore:__anon__/clusterSession:_keys"))
                    .thenReturn(1L);
            when(clusterCommands.scard("agentscope:stateStore:__anon__/clusterSession:_keys"))
                    .thenReturn(1L);

            when(clusterCommands.exists("agentscope:stateStore:__anon__/nonexistent:_keys"))
                    .thenReturn(0L);

            RedisAgentStateStore stateStore =
                    RedisAgentStateStore.builder()
                            .lettuceClusterClient(redisClusterClient)
                            .keyPrefix("agentscope:stateStore:")
                            .build();

            String existingSessionKey = "clusterSession";
            assertTrue(stateStore.exists(null, existingSessionKey));

            String nonExistingSessionKey = "nonexistent";
            assertFalse(stateStore.exists(null, nonExistingSessionKey));
        }

        @Test
        @DisplayName("Should delete stateStore correctly in cluster mode")
        void testDeleteSessionCluster() {
            Set<String> trackedKeys = new HashSet<>();
            trackedKeys.add("testModule");
            trackedKeys.add("testList:list");
            when(clusterCommands.smembers("agentscope:stateStore:__anon__/clusterSession:_keys"))
                    .thenReturn(trackedKeys);

            RedisAgentStateStore stateStore =
                    RedisAgentStateStore.builder()
                            .lettuceClusterClient(redisClusterClient)
                            .keyPrefix("agentscope:stateStore:")
                            .build();

            String sessionKey = "clusterSession";
            stateStore.delete(null, sessionKey);

            verify(clusterCommands).smembers("agentscope:stateStore:__anon__/clusterSession:_keys");
        }

        @Test
        @DisplayName("Should list stateStore keys correctly in cluster mode")
        void testListSessionKeysCluster() {
            KeyScanCursor<String> scanResult = mock(KeyScanCursor.class);
            List<String> keysKeysList = new ArrayList<>();
            keysKeysList.add("agentscope:stateStore:__anon__/cluster1:_keys");
            keysKeysList.add("agentscope:stateStore:__anon__/cluster2:_keys");
            when(scanResult.getKeys()).thenReturn(keysKeysList);
            when(scanResult.isFinished()).thenReturn(true);
            when(clusterCommands.scan(any(ScanCursor.class), any(ScanArgs.class)))
                    .thenReturn(scanResult);

            RedisAgentStateStore stateStore =
                    RedisAgentStateStore.builder()
                            .lettuceClusterClient(redisClusterClient)
                            .keyPrefix("agentscope:stateStore:")
                            .build();

            Set<String> sessionIds = stateStore.listSessionIds(null);

            assertEquals(2, sessionIds.size());
            assertTrue(sessionIds.contains("cluster1"));
            assertTrue(sessionIds.contains("cluster2"));
        }

        @Test
        @DisplayName("Should close cluster client when stateStore is closed")
        void testCloseCluster() {
            RedisAgentStateStore stateStore =
                    RedisAgentStateStore.builder()
                            .lettuceClusterClient(redisClusterClient)
                            .keyPrefix("agentscope:stateStore:")
                            .build();

            stateStore.close();

            verify(redisClusterClient).shutdown();
        }
    }

    public record TestState(String value, int count) implements State {}
}
