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
package io.agentscope.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for InMemoryAgentStateStore. */
@DisplayName("InMemoryAgentStateStore Tests")
class InMemoryAgentStateStoreTest {

    private InMemoryAgentStateStore stateStore;

    @BeforeEach
    void setUp() {
        stateStore = new InMemoryAgentStateStore();
    }

    @Test
    @DisplayName("Should save and get single state correctly")
    void testSaveAndGetSingleState() {
        SessionKey sessionKey = SimpleSessionKey.of("session1");
        TestState state = new TestState("test_value", 42);

        // Save state
        stateStore.save(null, sessionKey.toIdentifier(), "testModule", state);

        // Verify session exists
        assertTrue(stateStore.exists(null, sessionKey.toIdentifier()));

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
        SessionKey sessionKey = SimpleSessionKey.of("session1");
        List<TestState> states = List.of(new TestState("value1", 1), new TestState("value2", 2));

        // Save list state
        stateStore.save(null, sessionKey.toIdentifier(), "testList", states);

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
        SessionKey sessionKey = SimpleSessionKey.of("non_existent");

        Optional<TestState> state =
                stateStore.get(null, sessionKey.toIdentifier(), "testModule", TestState.class);
        assertFalse(state.isPresent());
    }

    @Test
    @DisplayName("Should return empty list for non-existent list state")
    void testGetNonExistentListState() {
        SessionKey sessionKey = SimpleSessionKey.of("non_existent");

        List<TestState> states =
                stateStore.getList(null, sessionKey.toIdentifier(), "testList", TestState.class);
        assertTrue(states.isEmpty());
    }

    @Test
    @DisplayName("Should return false for non-existent session")
    void testSessionExistsReturnsFalse() {
        SessionKey sessionKey = SimpleSessionKey.of("non_existent");
        assertFalse(stateStore.exists(null, sessionKey.toIdentifier()));
    }

    @Test
    @DisplayName("Should delete existing session")
    void testDeleteSession() {
        SessionKey sessionKey = SimpleSessionKey.of("session_to_delete");
        stateStore.save(null, sessionKey.toIdentifier(), "testModule", new TestState("value", 0));
        assertTrue(stateStore.exists(null, sessionKey.toIdentifier()));

        // Delete session
        stateStore.delete(null, sessionKey.toIdentifier());
        assertFalse(stateStore.exists(null, sessionKey.toIdentifier()));
    }

    @Test
    @DisplayName("Should list all session keys")
    void testListSessionKeys() {
        SessionKey key1 = SimpleSessionKey.of("session1");
        SessionKey key2 = SimpleSessionKey.of("session2");
        SessionKey key3 = SimpleSessionKey.of("session3");

        stateStore.save(null, key1.toIdentifier(), "testModule", new TestState("value1", 1));
        stateStore.save(null, key2.toIdentifier(), "testModule", new TestState("value2", 2));
        stateStore.save(null, key3.toIdentifier(), "testModule", new TestState("value3", 3));

        Set<String> sessionIds = stateStore.listSessionIds(null);
        assertEquals(3, sessionIds.size());
    }

    @Test
    @DisplayName("Should return empty set when no sessions exist")
    void testListSessionKeysEmpty() {
        Set<String> sessionIds = stateStore.listSessionIds(null);
        assertTrue(sessionIds.isEmpty());
    }

    @Test
    @DisplayName("Should return correct session count")
    void testGetSessionCount() {
        assertEquals(0, stateStore.getSessionCount());

        SessionKey key1 = SimpleSessionKey.of("session1");
        SessionKey key2 = SimpleSessionKey.of("session2");

        stateStore.save(null, key1.toIdentifier(), "testModule", new TestState("value1", 1));
        assertEquals(1, stateStore.getSessionCount());

        stateStore.save(null, key2.toIdentifier(), "testModule", new TestState("value2", 2));
        assertEquals(2, stateStore.getSessionCount());

        stateStore.delete(null, key1.toIdentifier());
        assertEquals(1, stateStore.getSessionCount());
    }

    @Test
    @DisplayName("Should clear all sessions")
    void testClearAll() {
        SessionKey key1 = SimpleSessionKey.of("session1");
        SessionKey key2 = SimpleSessionKey.of("session2");

        stateStore.save(null, key1.toIdentifier(), "testModule", new TestState("value1", 1));
        stateStore.save(null, key2.toIdentifier(), "testModule", new TestState("value2", 2));
        assertEquals(2, stateStore.getSessionCount());

        stateStore.clearAll();
        assertEquals(0, stateStore.getSessionCount());
        assertFalse(stateStore.exists(null, key1.toIdentifier()));
        assertFalse(stateStore.exists(null, key2.toIdentifier()));
    }

    @Test
    @DisplayName("Should update existing state when saving again")
    void testUpdateState() {
        SessionKey sessionKey = SimpleSessionKey.of("update_session");

        stateStore.save(null, sessionKey.toIdentifier(), "testModule", new TestState("initial", 1));

        // Update the state
        stateStore.save(null, sessionKey.toIdentifier(), "testModule", new TestState("updated", 2));

        // Load and verify
        Optional<TestState> loaded =
                stateStore.get(null, sessionKey.toIdentifier(), "testModule", TestState.class);
        assertTrue(loaded.isPresent());
        assertEquals("updated", loaded.get().value());
        assertEquals(2, loaded.get().count());
    }

    @Test
    @DisplayName("Should handle multiple keys in same session")
    void testMultipleKeysInSameSession() {
        SessionKey sessionKey = SimpleSessionKey.of("multi_key_session");

        stateStore.save(null, sessionKey.toIdentifier(), "module1", new TestState("value1", 1));
        stateStore.save(null, sessionKey.toIdentifier(), "module2", new TestState("value2", 2));

        Optional<TestState> loaded1 =
                stateStore.get(null, sessionKey.toIdentifier(), "module1", TestState.class);
        Optional<TestState> loaded2 =
                stateStore.get(null, sessionKey.toIdentifier(), "module2", TestState.class);

        assertTrue(loaded1.isPresent());
        assertTrue(loaded2.isPresent());
        assertEquals("value1", loaded1.get().value());
        assertEquals("value2", loaded2.get().value());
    }

    @Test
    @DisplayName("Should return empty for missing key in existing session")
    void testMissingKeyInExistingSession() {
        SessionKey sessionKey = SimpleSessionKey.of("existing_session");

        stateStore.save(null, sessionKey.toIdentifier(), "module1", new TestState("value1", 1));

        Optional<TestState> loaded =
                stateStore.get(null, sessionKey.toIdentifier(), "missing_key", TestState.class);
        assertFalse(loaded.isPresent());
    }

    /** Simple test state record for testing. */
    public record TestState(String value, int count) implements State {}
}
