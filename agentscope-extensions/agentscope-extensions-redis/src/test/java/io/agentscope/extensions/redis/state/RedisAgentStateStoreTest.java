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
package io.agentscope.extensions.redis.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link RedisAgentStateStore} optimistic-concurrency versioning, using a mocked
 * {@link RedisClientAdapter} so no Redis server is required.
 */
@DisplayName("RedisAgentStateStore versioning")
class RedisAgentStateStoreTest {

    record TestState(String value) implements State {}

    @Mock private RedisClientAdapter client;

    private RedisAgentStateStore store;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        store = RedisAgentStateStore.builder().clientAdapter(client).build();
    }

    @Test
    @DisplayName(
            "saveIfVersion with UNVERSIONED returns the Lua version without reading state back")
    void saveIfVersionUnconditionalNoReadBack() {
        when(client.evalScript(any(), anyList(), anyList())).thenReturn(7L);

        long version =
                store.saveIfVersion(
                        "user",
                        "s1",
                        "agent_state",
                        new TestState("v"),
                        AgentStateStore.UNVERSIONED);

        assertEquals(7L, version);
        // Regression: the UNVERSIONED path must not read the payload back. The previous
        // implementation called getVersioned(..., State.class), whose deserialization into the
        // `State` marker interface raised Jackson's InvalidDefinitionException.
        verify(client, never()).get(any());
    }

    @Test
    @DisplayName("saveIfVersion with a concrete expected version delegates to the Lua script")
    void saveIfVersionCasDelegatesToLua() {
        when(client.evalScript(any(), anyList(), anyList())).thenReturn(5L);

        long version = store.saveIfVersion("user", "s1", "agent_state", new TestState("v"), 4L);

        assertEquals(5L, version);
        verify(client, times(1)).evalScript(any(), anyList(), anyList());
    }

    @Test
    @DisplayName("new session save passes only cluster-safe keys to Lua")
    void newSessionSaveUsesOneHashSlotForLuaKeys() {
        when(client.evalScript(any(), anyList(), anyList())).thenReturn(1L);

        store.save("user", "s1", "agent_state", new TestState("v"));

        List<String> keys = capturedLuaKeys();
        assertClusterSafeSessionKeys(keys, "{user/s1}");
    }

    @Test
    @DisplayName("key layout helper builds expected cluster-safe and v0 keys")
    void keyLayoutHelperBuildsExpectedKeysAndRejectsBlankSession() {
        RedisAgentStateKeyLayout v1 = RedisAgentStateKeyLayout.v1("custom:session:", null, "s1");
        RedisAgentStateKeyLayout v0 = RedisAgentStateKeyLayout.v0("custom:session:", "user", "s1");

        assertEquals("custom:session:{__anon__/s1}:agent_state", v1.getStateKey("agent_state"));
        assertEquals("custom:session:{__anon__/s1}:_keys", v1.getKeysKey());
        assertEquals("custom:session:user/s1:agent_state", v0.getStateKey("agent_state"));
        assertEquals(
                "custom:session:{user/*}:_keys",
                RedisAgentStateKeyLayout.v1KeysPattern("custom:session:", "user"));
        assertEquals(
                "custom:session:user/*:_keys",
                RedisAgentStateKeyLayout.v0KeysPattern("custom:session:", "user"));
        assertEquals(
                Optional.of("s1"),
                RedisAgentStateKeyLayout.parseV1SessionIdFromKeysKey(
                        "custom:session:{__anon__/s1}:_keys", "custom:session:", "__anon__"));
        assertEquals(
                Optional.of("s1"),
                RedisAgentStateKeyLayout.parseV0SessionIdKeysKey(
                        "custom:session:user/s1:_keys", "custom:session:", "user"));
        assertEquals(
                Optional.empty(),
                RedisAgentStateKeyLayout.parseV1SessionIdFromKeysKey(
                        "custom:session:user/s1:_keys", "custom:session:", "user"));
        assertEquals(
                Optional.empty(),
                RedisAgentStateKeyLayout.parseV0SessionIdKeysKey(
                        "custom:session:{user/s1}:_keys", "custom:session:", "user"));
        assertEquals("messages:list", v0.getListTrackKey("messages"));
        assertTrue(RedisAgentStateKeyLayout.isListTrackKey("messages:list"));
        assertEquals("messages", RedisAgentStateKeyLayout.baseKeyFromListTrackKey("messages:list"));
        assertEquals("{__anon__/s1}", v1.slotId());
        assertThrows(
                IllegalArgumentException.class,
                () -> RedisAgentStateKeyLayout.v1("custom:session:", "user", " "));
    }

    @Test
    @DisplayName("existing v0 session continues to write and read v0 layout")
    void existingLegacySessionKeepsLegacyLayout() {
        when(client.keyExists("agentscope:session:{user/s1}:_keys")).thenReturn(false);
        when(client.keyExists("agentscope:session:user/s1:_keys")).thenReturn(true);
        when(client.evalScript(any(), anyList(), anyList())).thenReturn(3L);
        when(client.get("agentscope:session:user/s1:agent_state"))
                .thenReturn("{\"value\":\"legacy\"}");

        store.save("user", "s1", "agent_state", new TestState("saved"));
        long version = store.saveIfVersion("user", "s1", "agent_state", new TestState("cas"), 2L);
        Optional<TestState> loaded = store.get("user", "s1", "agent_state", TestState.class);

        assertEquals(3L, version);
        assertTrue(loaded.isPresent());
        assertEquals(new TestState("legacy"), loaded.get());
        List<String> keys = capturedAllLuaKeys();
        assertTrue(keys.stream().allMatch(key -> key.contains("agentscope:session:user/s1:")));
        assertFalse(keys.stream().anyMatch(key -> key.contains("{user/s1}")));
        verify(client, never()).get("agentscope:session:{user/s1}:agent_state");
    }

    @Test
    @DisplayName("list state keeps v0 layout for existing v0 sessions")
    void listStateUsesLegacyLayoutForSaveAndRead() {
        when(client.keyExists("agentscope:session:user/s1:_keys")).thenReturn(true);

        store.save("user", "s1", "messages", List.of(new TestState("new-list")));
        when(client.rangeList("agentscope:session:user/s1:messages:list", 0, -1))
                .thenReturn(List.of("{\"value\":\"legacy-list\"}"));

        verify(client).get("agentscope:session:user/s1:messages:list:_hash");
        verify(client).getListLength("agentscope:session:user/s1:messages:list");
        verify(client)
                .rightPushList(
                        "agentscope:session:user/s1:messages:list", "{\"value\":\"new-list\"}");
        verify(client).set(anyString(), anyString());
        verify(client).addToSet("agentscope:session:user/s1:_keys", "messages:list");
        verify(client, never()).addToSet("agentscope:session:{user/s1}:_keys", "messages:list");

        List<TestState> loaded = store.getList("user", "s1", "messages", TestState.class);

        assertEquals(List.of(new TestState("legacy-list")), loaded);
        verify(client).rangeList("agentscope:session:user/s1:messages:list", 0, -1);
        verify(client, never()).rangeList("agentscope:session:{user/s1}:messages:list", 0, -1);
    }

    @Test
    @DisplayName("list save does not move an existing v1 session back to v0 layout")
    void listSaveDoesNotMoveExistingClusterSafeSessionBackToLegacyLayout() {
        AtomicBoolean v0MarkerCreated = new AtomicBoolean(false);
        when(client.keyExists("agentscope:session:user/s1:_keys"))
                .thenAnswer(invocation -> v0MarkerCreated.get());
        when(client.evalScript(any(), anyList(), anyList())).thenReturn(1L);
        when(client.get("agentscope:session:{user/s1}:agent_state"))
                .thenReturn("{\"value\":\"cluster-safe\"}");
        doAnswer(
                        invocation -> {
                            String keysKey = invocation.getArgument(0);
                            if ("agentscope:session:user/s1:_keys".equals(keysKey)) {
                                v0MarkerCreated.set(true);
                            }
                            return null;
                        })
                .when(client)
                .addToSet(anyString(), anyString());

        store.save("user", "s1", "agent_state", new TestState("cluster-safe"));
        store.save("user", "s1", "messages", List.of(new TestState("message")));
        VersionedState<TestState> loaded =
                store.getVersioned("user", "s1", "agent_state", TestState.class);

        assertEquals(new TestState("cluster-safe"), loaded.value());
        verify(client).addToSet("agentscope:session:{user/s1}:_keys", "messages:list");
        verify(client, never()).addToSet("agentscope:session:user/s1:_keys", "messages:list");
        verify(client).get("agentscope:session:{user/s1}:agent_state");
        verify(client, never()).get("agentscope:session:user/s1:agent_state");
    }

    @Test
    @DisplayName("new versioned state uses cluster-safe layout when v0 layout does not exist")
    void newVersionedStateUsesClusterSafeLayoutWhenLegacyDoesNotExist() {
        when(client.keyExists("agentscope:session:user/s1:_keys")).thenReturn(false);
        when(client.get("agentscope:session:{user/s1}:missing")).thenReturn(null);
        when(client.keyExists("agentscope:session:{user/s1}:_keys")).thenReturn(true);
        when(client.getSetMembers("agentscope:session:{user/s1}:_keys"))
                .thenReturn(Set.of("agent_state"));

        VersionedState<TestState> loaded =
                store.getVersioned("user", "s1", "missing", TestState.class);
        store.delete("user", "s1");

        assertEquals(0L, loaded.version());
        assertEquals(null, loaded.value());
        verify(client).get("agentscope:session:{user/s1}:missing");
        verify(client, never()).get("agentscope:session:user/s1:missing");
        verify(client).getSetMembers("agentscope:session:{user/s1}:_keys");
        verify(client, never()).getSetMembers("agentscope:session:user/s1:_keys");
        ArgumentCaptor<String[]> deletedKeys = ArgumentCaptor.forClass(String[].class);
        verify(client).deleteKeys(deletedKeys.capture());
        List<String> deleted = Arrays.asList(deletedKeys.getValue());
        assertTrue(deleted.contains("agentscope:session:{user/s1}:_keys"));
        assertTrue(deleted.contains("agentscope:session:{user/s1}:agent_state"));
        assertTrue(deleted.contains("agentscope:session:{user/s1}:agent_state:ver"));
    }

    @Test
    @DisplayName("delete session uses v0 layout when only v0 marker exists")
    void deleteSessionUsesLegacyLayoutWhenOnlyLegacyMarkerExists() {
        when(client.keyExists("agentscope:session:{user/s1}:_keys")).thenReturn(false);
        when(client.keyExists("agentscope:session:user/s1:_keys")).thenReturn(true);
        when(client.getSetMembers("agentscope:session:user/s1:_keys"))
                .thenReturn(Set.of("agent_state", "messages:list"));

        store.delete("user", "s1");

        verify(client).getSetMembers("agentscope:session:user/s1:_keys");
        verify(client, never()).getSetMembers("agentscope:session:{user/s1}:_keys");
        ArgumentCaptor<String[]> deletedKeys = ArgumentCaptor.forClass(String[].class);
        verify(client).deleteKeys(deletedKeys.capture());
        List<String> deleted = Arrays.asList(deletedKeys.getValue());
        assertTrue(deleted.contains("agentscope:session:user/s1:_keys"));
        assertTrue(deleted.contains("agentscope:session:user/s1:agent_state"));
        assertTrue(deleted.contains("agentscope:session:user/s1:agent_state:ver"));
        assertTrue(deleted.contains("agentscope:session:user/s1:messages:list"));
        assertTrue(deleted.contains("agentscope:session:user/s1:messages:list:_hash"));
    }

    @Test
    @DisplayName("exists selects v1 first and falls back to v0 only when v1 marker is missing")
    void existsSelectsClusterSafeLayoutBeforeLegacyFallback() {
        when(client.keyExists("agentscope:session:{user/s1}:_keys")).thenReturn(true);
        when(client.getSetSize("agentscope:session:{user/s1}:_keys")).thenReturn(0L);

        assertFalse(store.exists("user", "s1"));
        verify(client, never()).keyExists("agentscope:session:user/s1:_keys");
        verify(client, never()).getSetSize("agentscope:session:user/s1:_keys");

        reset(client);
        when(client.keyExists("agentscope:session:{user/s1}:_keys")).thenReturn(false);
        when(client.keyExists("agentscope:session:user/s1:_keys")).thenReturn(true);
        when(client.getSetSize("agentscope:session:user/s1:_keys")).thenReturn(1L);

        assertTrue(store.exists("user", "s1"));

        reset(client);
        when(client.keyExists("agentscope:session:{user/s1}:_keys")).thenReturn(false);
        when(client.keyExists("agentscope:session:user/s1:_keys")).thenReturn(false);

        assertFalse(store.exists("user", "s1"));
    }

    @Test
    @DisplayName("listSessionIds merges v0 and cluster-safe sessions without braces")
    void listSessionIdsIncludesLegacyAndClusterSafeSessionsWithoutDuplicates() {
        when(client.findKeysByPattern(anyString()))
                .thenAnswer(
                        invocation -> {
                            String pattern = invocation.getArgument(0);
                            if (pattern.contains("{")) {
                                return Set.of(
                                        "agentscope:session:{user/new-only}:_keys",
                                        "agentscope:session:{user/mixed}:_keys");
                            }
                            return Set.of(
                                    "agentscope:session:user/legacy-only:_keys",
                                    "agentscope:session:user/mixed:_keys");
                        });

        Set<String> sessionIds = store.listSessionIds("user");

        assertEquals(Set.of("legacy-only", "new-only", "mixed"), sessionIds);
        assertEquals(sessionIds.size(), new HashSet<>(sessionIds).size());
        assertFalse(sessionIds.stream().anyMatch(id -> id.contains("{") || id.contains("}")));
    }

    private List<String> capturedLuaKeys() {
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(client, times(1)).evalScript(any(), keysCaptor.capture(), anyList());
        return keysCaptor.getValue();
    }

    private List<String> capturedAllLuaKeys() {
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(client, atLeastOnce()).evalScript(any(), keysCaptor.capture(), anyList());
        return keysCaptor.getAllValues().stream().flatMap(List::stream).toList();
    }

    private static void assertClusterSafeSessionKeys(List<String> keys, String expectedHashTag) {
        assertFalse(keys.isEmpty());
        assertTrue(
                keys.stream().allMatch(key -> key.contains(expectedHashTag)),
                () -> "Lua keys must use the same Redis Cluster hash tag: " + keys);
    }
}
