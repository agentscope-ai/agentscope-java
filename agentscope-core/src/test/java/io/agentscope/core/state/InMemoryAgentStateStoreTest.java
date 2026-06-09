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
        String sessionKey = "session1";
        TestState state = new TestState("test_value", 42);

        // Save state
        stateStore.save(null, sessionKey, "testModule", state);

        // Verify session exists
        assertTrue(stateStore.exists(null, sessionKey));

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
        String sessionKey = "session1";
        List<TestState> states = List.of(new TestState("value1", 1), new TestState("value2", 2));

        // Save list state
        stateStore.save(null, sessionKey, "testList", states);

        // Get list state
        List<TestState> loaded = stateStore.getList(null, sessionKey, "testList", TestState.class);
        assertEquals(2, loaded.size());
        assertEquals("value1", loaded.get(0).value());
        assertEquals("value2", loaded.get(1).value());
    }

    @Test
    @DisplayName("Should return empty for non-existent state")
    void testGetNonExistentState() {
        String sessionKey = "non_existent";

        Optional<TestState> state = stateStore.get(null, sessionKey, "testModule", TestState.class);
        assertFalse(state.isPresent());
    }

    @Test
    @DisplayName("Should return empty list for non-existent list state")
    void testGetNonExistentListState() {
        String sessionKey = "non_existent";

        List<TestState> states = stateStore.getList(null, sessionKey, "testList", TestState.class);
        assertTrue(states.isEmpty());
    }

    @Test
    @DisplayName("Should return false for non-existent session")
    void testSessionExistsReturnsFalse() {
        String sessionKey = "non_existent";
        assertFalse(stateStore.exists(null, sessionKey));
    }

    @Test
    @DisplayName("Should delete existing session")
    void testDeleteSession() {
        String sessionKey = "session_to_delete";
        stateStore.save(null, sessionKey, "testModule", new TestState("value", 0));
        assertTrue(stateStore.exists(null, sessionKey));

        // Delete session
        stateStore.delete(null, sessionKey);
        assertFalse(stateStore.exists(null, sessionKey));
    }

    @Test
    @DisplayName("Should list all session keys")
    void testListSessionKeys() {
        String key1 = "session1";
        String key2 = "session2";
        String key3 = "session3";

        stateStore.save(null, key1, "testModule", new TestState("value1", 1));
        stateStore.save(null, key2, "testModule", new TestState("value2", 2));
        stateStore.save(null, key3, "testModule", new TestState("value3", 3));

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

        String key1 = "session1";
        String key2 = "session2";

        stateStore.save(null, key1, "testModule", new TestState("value1", 1));
        assertEquals(1, stateStore.getSessionCount());

        stateStore.save(null, key2, "testModule", new TestState("value2", 2));
        assertEquals(2, stateStore.getSessionCount());

        stateStore.delete(null, key1);
        assertEquals(1, stateStore.getSessionCount());
    }

    @Test
    @DisplayName("Should clear all sessions")
    void testClearAll() {
        String key1 = "session1";
        String key2 = "session2";

        stateStore.save(null, key1, "testModule", new TestState("value1", 1));
        stateStore.save(null, key2, "testModule", new TestState("value2", 2));
        assertEquals(2, stateStore.getSessionCount());

        stateStore.clearAll();
        assertEquals(0, stateStore.getSessionCount());
        assertFalse(stateStore.exists(null, key1));
        assertFalse(stateStore.exists(null, key2));
    }

    @Test
    @DisplayName("Should update existing state when saving again")
    void testUpdateState() {
        String sessionKey = "update_session";

        stateStore.save(null, sessionKey, "testModule", new TestState("initial", 1));

        // Update the state
        stateStore.save(null, sessionKey, "testModule", new TestState("updated", 2));

        // Load and verify
        Optional<TestState> loaded =
                stateStore.get(null, sessionKey, "testModule", TestState.class);
        assertTrue(loaded.isPresent());
        assertEquals("updated", loaded.get().value());
        assertEquals(2, loaded.get().count());
    }

    @Test
    @DisplayName("Should handle multiple keys in same session")
    void testMultipleKeysInSameSession() {
        String sessionKey = "multi_key_session";

        stateStore.save(null, sessionKey, "module1", new TestState("value1", 1));
        stateStore.save(null, sessionKey, "module2", new TestState("value2", 2));

        Optional<TestState> loaded1 = stateStore.get(null, sessionKey, "module1", TestState.class);
        Optional<TestState> loaded2 = stateStore.get(null, sessionKey, "module2", TestState.class);

        assertTrue(loaded1.isPresent());
        assertTrue(loaded2.isPresent());
        assertEquals("value1", loaded1.get().value());
        assertEquals("value2", loaded2.get().value());
    }

    @Test
    @DisplayName("Should return empty for missing key in existing session")
    void testMissingKeyInExistingSession() {
        String sessionKey = "existing_session";

        stateStore.save(null, sessionKey, "module1", new TestState("value1", 1));

        Optional<TestState> loaded =
                stateStore.get(null, sessionKey, "missing_key", TestState.class);
        assertFalse(loaded.isPresent());
    }

    /** Simple test state record for testing. */
    public record TestState(String value, int count) implements State {}
}
