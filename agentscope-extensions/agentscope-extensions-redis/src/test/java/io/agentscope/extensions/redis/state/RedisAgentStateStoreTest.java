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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for deterministic Redis agent-state key layouts. */
@DisplayName("RedisAgentStateStore key layouts")
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
    @DisplayName("default V0 supports single and list state")
    void defaultV0SupportsSingleAndListState() {
        when(client.evalScript(any(), anyList(), anyList())).thenReturn(1L);
        when(client.getSetSize("agentscope:session:user/s1:_keys")).thenReturn(2L);
        when(client.rangeList("agentscope:session:user/s1:messages:list", 0, -1))
                .thenReturn(List.of("{\"value\":\"item\"}"));

        store.save("user", "s1", "agent_state", new TestState("v"));
        store.save("user", "s1", "messages", List.of(new TestState("item")));

        assertEquals(
                List.of(
                        "agentscope:session:user/s1:agent_state",
                        "agentscope:session:user/s1:agent_state:ver",
                        "agentscope:session:user/s1:_keys"),
                capturedLuaKeys());
        assertEquals(
                List.of(new TestState("item")),
                store.getList("user", "s1", "messages", TestState.class));
        assertTrue(store.exists("user", "s1"));
        verify(client).addToSet("agentscope:session:user/s1:_keys", "messages:list");
        verify(client).set(eq("agentscope:session:user/s1:messages:list:_hash"), anyString());
    }

    @Test
    @DisplayName("explicit V1 supports interleaved single-value and list operations")
    void explicitV1SupportsInterleavedSingleAndListOperations() {
        RedisAgentStateStore v1Store = newStore(RedisAgentStateStore.KeyLayoutVersion.V1);
        when(client.evalScript(any(), anyList(), anyList())).thenReturn(1L);
        when(client.get("agentscope:session:{user/s1}:state")).thenReturn("{\"value\":\"single\"}");
        when(client.rangeList("agentscope:session:{user/s1}:state:list", 0, -1))
                .thenReturn(List.of("{\"value\":\"list\"}"));
        when(client.getSetSize("agentscope:session:{user/s1}:_keys")).thenReturn(2L);

        v1Store.save("user", "s1", "state", new TestState("single"));
        v1Store.save("user", "s1", "state", List.of(new TestState("list")));
        assertEquals(
                new TestState("single"),
                v1Store.getVersioned("user", "s1", "state", TestState.class).value());
        assertEquals(
                List.of(new TestState("list")),
                v1Store.getList("user", "s1", "state", TestState.class));
        assertTrue(v1Store.exists("user", "s1"));

        assertEquals(
                List.of(
                        "agentscope:session:{user/s1}:state",
                        "agentscope:session:{user/s1}:state:ver",
                        "agentscope:session:{user/s1}:_keys"),
                capturedLuaKeys());
        verify(client).addToSet("agentscope:session:{user/s1}:_keys", "state:list");
    }

    @Test
    @DisplayName("builder rejects invalid key layout configuration")
    void builderRejectsInvalidKeyLayoutConfiguration() {
        IllegalArgumentException nullLayout =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                RedisAgentStateStore.builder()
                                        .clientAdapter(client)
                                        .keyLayoutVersion(null)
                                        .build());
        assertEquals("Key layout version cannot be null", nullLayout.getMessage());

        for (String prefix : List.of("scope{bad:", "scope}bad:")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RedisAgentStateStore.builder()
                                    .clientAdapter(client)
                                    .keyPrefix(prefix)
                                    .keyLayoutVersion(RedisAgentStateStore.KeyLayoutVersion.V1)
                                    .build());
        }
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("V1 rejects unsafe user and session identifiers before Redis I/O")
    void v1RejectsUnsafeIdentifiersBeforeRedisIo() {
        RedisAgentStateStore v1Store = newStore(RedisAgentStateStore.KeyLayoutVersion.V1);

        assertThrows(
                IllegalArgumentException.class,
                () -> v1Store.save("bad/user", "s1", "state", new TestState("v")));
        assertThrows(
                IllegalArgumentException.class,
                () -> v1Store.getVersioned("bad{user", "s1", "state", TestState.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> v1Store.saveIfVersion("bad}user", "s1", "state", new TestState("v"), 1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> v1Store.save("user", "bad/session", "state", List.of(new TestState("v"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> v1Store.get("user", "bad{session", "state", TestState.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> v1Store.getList("user", "bad}session", "state", TestState.class));
        assertThrows(IllegalArgumentException.class, () -> v1Store.exists("user", " "));
        assertThrows(IllegalArgumentException.class, () -> v1Store.delete("bad/user", "s1"));
        assertThrows(IllegalArgumentException.class, () -> v1Store.listSessionIds("bad/user"));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("V0 retains compatible identifiers while both layouts reject blank sessions")
    void v0RetainsIdentifierCompatibilityAndBlankSessionValidation() {
        when(client.get("agentscope:session:u/{x}/s/{y}:state"))
                .thenReturn("{\"value\":\"compatible\"}");

        Optional<TestState> loaded = store.get("u/{x}", "s/{y}", "state", TestState.class);

        assertEquals(Optional.of(new TestState("compatible")), loaded);
        assertThrows(
                IllegalArgumentException.class,
                () -> store.get("user", " ", "state", TestState.class));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        newStore(RedisAgentStateStore.KeyLayoutVersion.V1)
                                .get("user", " ", "state", TestState.class));
    }

    @Test
    @DisplayName("V0 listing escapes glob literals and filters neighboring namespaces")
    void v0ListingEscapesGlobLiteralsAndFiltersNamespaces() {
        String prefix = "tenant*?:[x]\\:";
        String user = "u*?[x]\\";
        RedisAgentStateStore customStore =
                RedisAgentStateStore.builder()
                        .clientAdapter(client)
                        .keyPrefix(prefix)
                        .keyLayoutVersion(RedisAgentStateStore.KeyLayoutVersion.V0)
                        .build();
        String pattern = "tenant\\*\\?:\\[x\\]\\\\:u\\*\\?\\[x\\]\\\\/*:_keys";
        when(client.findKeysByPattern(pattern))
                .thenReturn(
                        Set.of(
                                prefix + user + "/wanted:_keys",
                                prefix + "neighbor/other:_keys",
                                "other:" + user + "/foreign:_keys"));

        assertEquals(Set.of("wanted"), customStore.listSessionIds(user));
        verify(client).findKeysByPattern(pattern);
    }

    @Test
    @DisplayName("V1 listing escapes glob literals and filters neighboring namespaces")
    void v1ListingEscapesGlobLiteralsAndFiltersNamespaces() {
        String prefix = "tenant*?:[x]\\:";
        String user = "u*?[x]\\";
        RedisAgentStateStore customStore =
                RedisAgentStateStore.builder()
                        .clientAdapter(client)
                        .keyPrefix(prefix)
                        .keyLayoutVersion(RedisAgentStateStore.KeyLayoutVersion.V1)
                        .build();
        String pattern = "tenant\\*\\?:\\[x\\]\\\\:{u\\*\\?\\[x\\]\\\\/*}:_keys";
        when(client.findKeysByPattern(pattern))
                .thenReturn(
                        Set.of(
                                prefix + "{" + user + "/wanted}:_keys",
                                prefix + "{neighbor/other}:_keys",
                                "other:{" + user + "/foreign}:_keys"));

        assertEquals(Set.of("wanted"), customStore.listSessionIds(user));
        verify(client).findKeysByPattern(pattern);
    }

    @Test
    @DisplayName("V0 delete submits every tracked Redis key in a single-key adapter call")
    void v0DeleteUsesOnlySingleKeyAdapterCalls() {
        when(client.getSetMembers("agentscope:session:user/s1:_keys"))
                .thenReturn(Set.of("agent_state", "messages:list"));

        store.delete("user", "s1");

        assertSingleKeyDeletes(
                Set.of(
                        "agentscope:session:user/s1:_keys",
                        "agentscope:session:user/s1:agent_state",
                        "agentscope:session:user/s1:agent_state:ver",
                        "agentscope:session:user/s1:messages:list",
                        "agentscope:session:user/s1:messages:list:_hash"));
        verify(client, never()).getSetMembers("agentscope:session:{user/s1}:_keys");
    }

    @Test
    @DisplayName("V1 delete submits unknown-slot tracked names only as single-key calls")
    void v1DeleteUsesOnlySingleKeyAdapterCallsForUnknownSlots() {
        RedisAgentStateStore v1Store = newStore(RedisAgentStateStore.KeyLayoutVersion.V1);
        when(client.getSetMembers("agentscope:session:{user/s1}:_keys"))
                .thenReturn(Set.of("foreign{slot}"));

        v1Store.delete("user", "s1");

        assertSingleKeyDeletes(
                Set.of(
                        "agentscope:session:{user/s1}:_keys",
                        "agentscope:session:{user/s1}:foreign{slot}",
                        "agentscope:session:{user/s1}:foreign{slot}:ver"));
        verify(client, never()).getSetMembers("agentscope:session:user/s1:_keys");
    }

    @Test
    @DisplayName("delete is a no-op when the configured layout tracks no state")
    void deleteDoesNothingWhenConfiguredLayoutHasNoTrackedKeys() {
        when(client.getSetMembers("agentscope:session:user/s1:_keys")).thenReturn(Set.of());

        store.delete("user", "s1");

        verify(client, never()).deleteKeys(any(String[].class));
        verify(client, never()).getSetMembers("agentscope:session:{user/s1}:_keys");
    }

    @Test
    @DisplayName("clearAllSessions deletes matching keys one at a time")
    void clearAllSessionsDeletesClusterKeysIndividually() {
        Set<String> keys =
                Set.of("agentscope:session:{user/s1}:state", "agentscope:session:{user/s2}:state");
        when(client.findKeysByPattern("agentscope:session:*")).thenReturn(keys);

        assertEquals(2, store.clearAllSessions().block());

        assertSingleKeyDeletes(keys);
    }

    @Test
    @DisplayName("delete wraps Redis failures for the configured layout")
    void deleteWrapsConfiguredLayoutFailure() {
        IllegalStateException failure = new IllegalStateException("Redis unavailable");
        when(client.getSetMembers("agentscope:session:user/s1:_keys")).thenThrow(failure);

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> store.delete("user", "s1"));

        assertEquals("Failed to delete session: user/s1", thrown.getMessage());
        assertEquals(failure, thrown.getCause());
        verify(client, never()).getSetMembers("agentscope:session:{user/s1}:_keys");
    }

    @Test
    @DisplayName("exists wraps Redis failures for the configured layout")
    void existsWrapsConfiguredLayoutFailure() {
        IllegalStateException failure = new IllegalStateException("Redis unavailable");
        when(client.getSetSize("agentscope:session:{user/s1}:_keys")).thenThrow(failure);
        RedisAgentStateStore v1Store = newStore(RedisAgentStateStore.KeyLayoutVersion.V1);

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> v1Store.exists("user", "s1"));

        assertEquals("Failed to check session existence: user/s1", thrown.getMessage());
        assertEquals(failure, thrown.getCause());
        verify(client, never()).getSetSize("agentscope:session:user/s1:_keys");
    }

    @Test
    @DisplayName("key layout helper builds deterministic keys")
    void keyLayoutHelperBuildsDeterministicKeys() {
        RedisAgentStateKeyLayout v1 = RedisAgentStateKeyLayout.v1("custom:session:", null, "s1");
        RedisAgentStateKeyLayout v0 = RedisAgentStateKeyLayout.v0("custom:session:", "user", "s1");

        assertEquals("custom:session:{__anon__/s1}:agent_state", v1.getStateKey("agent_state"));
        assertEquals("custom:session:{__anon__/s1}:_keys", v1.getKeysKey());
        assertEquals("custom:session:user/s1:agent_state", v0.getStateKey("agent_state"));
        assertEquals("messages:list", v0.getListTrackKey("messages"));
        assertTrue(RedisAgentStateKeyLayout.isListTrackKey("messages:list"));
        assertEquals("messages", RedisAgentStateKeyLayout.baseKeyFromListTrackKey("messages:list"));
        assertEquals("{__anon__/s1}", v1.slotId());
        assertDoesNotThrow(() -> RedisAgentStateKeyLayout.v0("custom:", "u/{x}", "s/{y}"));
    }

    private RedisAgentStateStore newStore(RedisAgentStateStore.KeyLayoutVersion version) {
        return RedisAgentStateStore.builder()
                .clientAdapter(client)
                .keyLayoutVersion(version)
                .build();
    }

    private List<String> capturedLuaKeys() {
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(client, times(1)).evalScript(any(), keysCaptor.capture(), anyList());
        return keysCaptor.getValue();
    }

    private void assertSingleKeyDeletes(Set<String> expectedKeys) {
        ArgumentCaptor<String[]> keysCaptor = ArgumentCaptor.forClass(String[].class);
        verify(client, times(expectedKeys.size())).deleteKeys(keysCaptor.capture());
        assertTrue(keysCaptor.getAllValues().stream().allMatch(keys -> keys.length == 1));
        assertEquals(
                expectedKeys,
                keysCaptor.getAllValues().stream()
                        .map(keys -> keys[0])
                        .collect(Collectors.toSet()));
    }
}
