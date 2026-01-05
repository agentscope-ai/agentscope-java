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
package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.State;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for InMemorySession. */
@DisplayName("InMemorySession Tests")
class InMemorySessionTest {

    private InMemorySession session;

    @BeforeEach
    void setUp() {
        session = new InMemorySession();
    }

    @Test
    @DisplayName("Should save and get single state correctly")
    void testSaveAndGetSingleState() {
        SessionKey sessionKey = SimpleSessionKey.of("session1");
        TestState state = new TestState("test_value", 42);

        // Save state
        session.save(sessionKey, "testModule", state);

        // Verify session exists
        assertTrue(session.exists(sessionKey));

        // Get state
        Optional<TestState> loaded = session.get(sessionKey, "testModule", TestState.class);
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
        session.save(sessionKey, "testList", states);

        // Get list state
        List<TestState> loaded = session.getList(sessionKey, "testList", TestState.class);
        assertEquals(2, loaded.size());
        assertEquals("value1", loaded.get(0).value());
        assertEquals("value2", loaded.get(1).value());
    }

    @Test
    @DisplayName("Should return empty for non-existent state")
    void testGetNonExistentState() {
        SessionKey sessionKey = SimpleSessionKey.of("non_existent");

        Optional<TestState> state = session.get(sessionKey, "testModule", TestState.class);
        assertFalse(state.isPresent());
    }

    @Test
    @DisplayName("Should return empty list for non-existent list state")
    void testGetNonExistentListState() {
        SessionKey sessionKey = SimpleSessionKey.of("non_existent");

        List<TestState> states = session.getList(sessionKey, "testList", TestState.class);
        assertTrue(states.isEmpty());
    }

    @Test
    @DisplayName("Should return false for non-existent session")
    void testSessionExistsReturnsFalse() {
        SessionKey sessionKey = SimpleSessionKey.of("non_existent");
        assertFalse(session.exists(sessionKey));
    }

    @Test
    @DisplayName("Should delete existing session")
    void testDeleteSession() {
        SessionKey sessionKey = SimpleSessionKey.of("session_to_delete");
        session.save(sessionKey, "testModule", new TestState("value", 0));
        assertTrue(session.exists(sessionKey));

        // Delete session
        session.delete(sessionKey);
        assertFalse(session.exists(sessionKey));
    }

    @Test
    @DisplayName("Should list all session keys")
    void testListSessionKeys() {
        SessionKey key1 = SimpleSessionKey.of("session1");
        SessionKey key2 = SimpleSessionKey.of("session2");
        SessionKey key3 = SimpleSessionKey.of("session3");

        session.save(key1, "testModule", new TestState("value1", 1));
        session.save(key2, "testModule", new TestState("value2", 2));
        session.save(key3, "testModule", new TestState("value3", 3));

        Set<SessionKey> sessionKeys = session.listSessionKeys();
        assertEquals(3, sessionKeys.size());
    }

    @Test
    @DisplayName("Should return empty set when no sessions exist")
    void testListSessionKeysEmpty() {
        Set<SessionKey> sessionKeys = session.listSessionKeys();
        assertTrue(sessionKeys.isEmpty());
    }

    @Test
    @DisplayName("Should return correct session count")
    void testGetSessionCount() {
        assertEquals(0, session.getSessionCount());

        SessionKey key1 = SimpleSessionKey.of("session1");
        SessionKey key2 = SimpleSessionKey.of("session2");

        session.save(key1, "testModule", new TestState("value1", 1));
        assertEquals(1, session.getSessionCount());

        session.save(key2, "testModule", new TestState("value2", 2));
        assertEquals(2, session.getSessionCount());

        session.delete(key1);
        assertEquals(1, session.getSessionCount());
    }

    @Test
    @DisplayName("Should clear all sessions")
    void testClearAll() {
        SessionKey key1 = SimpleSessionKey.of("session1");
        SessionKey key2 = SimpleSessionKey.of("session2");

        session.save(key1, "testModule", new TestState("value1", 1));
        session.save(key2, "testModule", new TestState("value2", 2));
        assertEquals(2, session.getSessionCount());

        session.clearAll();
        assertEquals(0, session.getSessionCount());
        assertFalse(session.exists(key1));
        assertFalse(session.exists(key2));
    }

    @Test
    @DisplayName("Should update existing state when saving again")
    void testUpdateState() {
        SessionKey sessionKey = SimpleSessionKey.of("update_session");

        session.save(sessionKey, "testModule", new TestState("initial", 1));

        // Update the state
        session.save(sessionKey, "testModule", new TestState("updated", 2));

        // Load and verify
        Optional<TestState> loaded = session.get(sessionKey, "testModule", TestState.class);
        assertTrue(loaded.isPresent());
        assertEquals("updated", loaded.get().value());
        assertEquals(2, loaded.get().count());
    }

    @Test
    @DisplayName("Should handle multiple keys in same session")
    void testMultipleKeysInSameSession() {
        SessionKey sessionKey = SimpleSessionKey.of("multi_key_session");

        session.save(sessionKey, "module1", new TestState("value1", 1));
        session.save(sessionKey, "module2", new TestState("value2", 2));

        Optional<TestState> loaded1 = session.get(sessionKey, "module1", TestState.class);
        Optional<TestState> loaded2 = session.get(sessionKey, "module2", TestState.class);

        assertTrue(loaded1.isPresent());
        assertTrue(loaded2.isPresent());
        assertEquals("value1", loaded1.get().value());
        assertEquals("value2", loaded2.get().value());
    }

    @Test
    @DisplayName("Should return empty for missing key in existing session")
    void testMissingKeyInExistingSession() {
        SessionKey sessionKey = SimpleSessionKey.of("existing_session");

        session.save(sessionKey, "module1", new TestState("value1", 1));

        Optional<TestState> loaded = session.get(sessionKey, "missing_key", TestState.class);
        assertFalse(loaded.isPresent());
    }

    /** Simple test state record for testing. */
    public record TestState(String value, int count) implements State {}
}
