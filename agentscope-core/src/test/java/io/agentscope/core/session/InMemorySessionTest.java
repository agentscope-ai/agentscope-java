/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.StateModule;
import io.agentscope.core.state.StateModuleBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    @DisplayName("Should save and load session state correctly")
    void testSaveAndLoadSessionState() {
        TestStateModule module = new TestStateModule();
        module.setValue("test_value");
        module.setCount(42);

        Map<String, StateModule> stateModules = new HashMap<>();
        stateModules.put("testModule", module);

        // Save session
        session.saveSessionState("session1", stateModules);

        // Verify session exists
        assertTrue(session.sessionExists("session1"));

        // Create new module and load state
        TestStateModule newModule = new TestStateModule();
        Map<String, StateModule> newStateModules = new HashMap<>();
        newStateModules.put("testModule", newModule);

        session.loadSessionState("session1", false, newStateModules);

        // Verify state was loaded
        assertEquals("test_value", newModule.getValue());
        assertEquals(42, newModule.getCount());
    }

    @Test
    @DisplayName(
            "Should throw exception when loading non-existent session with allowNotExist=false")
    void testLoadNonExistentSessionStrict() {
        TestStateModule module = new TestStateModule();
        Map<String, StateModule> stateModules = new HashMap<>();
        stateModules.put("testModule", module);

        assertThrows(
                IllegalArgumentException.class,
                () -> session.loadSessionState("non_existent", false, stateModules));
    }

    @Test
    @DisplayName("Should not throw when loading non-existent session with allowNotExist=true")
    void testLoadNonExistentSessionPermissive() {
        TestStateModule module = new TestStateModule();
        module.setValue("original");
        Map<String, StateModule> stateModules = new HashMap<>();
        stateModules.put("testModule", module);

        // Should not throw
        session.loadSessionState("non_existent", true, stateModules);

        // Original value should be preserved
        assertEquals("original", module.getValue());
    }

    @Test
    @DisplayName("Should return false for non-existent session")
    void testSessionExistsReturnsFalse() {
        assertFalse(session.sessionExists("non_existent"));
    }

    @Test
    @DisplayName("Should delete existing session")
    void testDeleteSession() {
        TestStateModule module = new TestStateModule();
        Map<String, StateModule> stateModules = new HashMap<>();
        stateModules.put("testModule", module);

        session.saveSessionState("session_to_delete", stateModules);
        assertTrue(session.sessionExists("session_to_delete"));

        // Delete session
        assertTrue(session.deleteSession("session_to_delete"));
        assertFalse(session.sessionExists("session_to_delete"));
    }

    @Test
    @DisplayName("Should return false when deleting non-existent session")
    void testDeleteNonExistentSession() {
        assertFalse(session.deleteSession("non_existent"));
    }

    @Test
    @DisplayName("Should list all sessions")
    void testListSessions() {
        TestStateModule module = new TestStateModule();
        Map<String, StateModule> stateModules = new HashMap<>();
        stateModules.put("testModule", module);

        session.saveSessionState("session1", stateModules);
        session.saveSessionState("session2", stateModules);
        session.saveSessionState("session3", stateModules);

        List<String> sessions = session.listSessions();
        assertEquals(3, sessions.size());
        assertTrue(sessions.contains("session1"));
        assertTrue(sessions.contains("session2"));
        assertTrue(sessions.contains("session3"));
    }

    @Test
    @DisplayName("Should return empty list when no sessions exist")
    void testListSessionsEmpty() {
        List<String> sessions = session.listSessions();
        assertTrue(sessions.isEmpty());
    }

    @Test
    @DisplayName("Should return session info for existing session")
    void testGetSessionInfo() {
        TestStateModule module1 = new TestStateModule();
        TestStateModule module2 = new TestStateModule();
        Map<String, StateModule> stateModules = new HashMap<>();
        stateModules.put("module1", module1);
        stateModules.put("module2", module2);

        session.saveSessionState("info_session", stateModules);

        SessionInfo info = session.getSessionInfo("info_session");
        assertNotNull(info);
        assertEquals("info_session", info.getSessionId());
        assertEquals(2, info.getComponentCount());
        assertTrue(info.getLastModified() > 0);
    }

    @Test
    @DisplayName("Should return null for non-existent session info")
    void testGetSessionInfoNonExistent() {
        SessionInfo info = session.getSessionInfo("non_existent");
        assertNull(info);
    }

    @Test
    @DisplayName("Should return correct session count")
    void testGetSessionCount() {
        assertEquals(0, session.getSessionCount());

        TestStateModule module = new TestStateModule();
        Map<String, StateModule> stateModules = new HashMap<>();
        stateModules.put("testModule", module);

        session.saveSessionState("session1", stateModules);
        assertEquals(1, session.getSessionCount());

        session.saveSessionState("session2", stateModules);
        assertEquals(2, session.getSessionCount());

        session.deleteSession("session1");
        assertEquals(1, session.getSessionCount());
    }

    @Test
    @DisplayName("Should clear all sessions")
    void testClearAll() {
        TestStateModule module = new TestStateModule();
        Map<String, StateModule> stateModules = new HashMap<>();
        stateModules.put("testModule", module);

        session.saveSessionState("session1", stateModules);
        session.saveSessionState("session2", stateModules);
        assertEquals(2, session.getSessionCount());

        session.clearAll();
        assertEquals(0, session.getSessionCount());
        assertFalse(session.sessionExists("session1"));
        assertFalse(session.sessionExists("session2"));
    }

    @Test
    @DisplayName("Should update existing session when saving again")
    void testUpdateSession() {
        TestStateModule module = new TestStateModule();
        module.setValue("initial");
        Map<String, StateModule> stateModules = new HashMap<>();
        stateModules.put("testModule", module);

        session.saveSessionState("update_session", stateModules);

        // Update the module and save again
        module.setValue("updated");
        session.saveSessionState("update_session", stateModules);

        // Load and verify
        TestStateModule loadedModule = new TestStateModule();
        Map<String, StateModule> loadModules = new HashMap<>();
        loadModules.put("testModule", loadedModule);

        session.loadSessionState("update_session", false, loadModules);
        assertEquals("updated", loadedModule.getValue());
    }

    @Test
    @DisplayName("Should handle partial component loading")
    void testPartialComponentLoading() {
        TestStateModule module1 = new TestStateModule();
        module1.setValue("value1");
        TestStateModule module2 = new TestStateModule();
        module2.setValue("value2");

        Map<String, StateModule> saveModules = new HashMap<>();
        saveModules.put("module1", module1);
        saveModules.put("module2", module2);

        session.saveSessionState("partial_session", saveModules);

        // Load only one component
        TestStateModule loadedModule = new TestStateModule();
        Map<String, StateModule> loadModules = new HashMap<>();
        loadModules.put("module1", loadedModule);

        session.loadSessionState("partial_session", false, loadModules);
        assertEquals("value1", loadedModule.getValue());
    }

    @Test
    @DisplayName("Should handle missing component during loading")
    void testMissingComponentLoading() {
        TestStateModule module = new TestStateModule();
        module.setValue("value");

        Map<String, StateModule> saveModules = new HashMap<>();
        saveModules.put("module1", module);

        session.saveSessionState("missing_component_session", saveModules);

        // Try to load with different component name
        TestStateModule loadedModule = new TestStateModule();
        loadedModule.setValue("original");
        Map<String, StateModule> loadModules = new HashMap<>();
        loadModules.put("different_module", loadedModule);

        session.loadSessionState("missing_component_session", false, loadModules);

        // Original value should be preserved since component wasn't found
        assertEquals("original", loadedModule.getValue());
    }

    /** Simple test state module for testing. */
    private static class TestStateModule extends StateModuleBase {
        private String value;
        private int count;

        public TestStateModule() {
            registerState("value");
            registerState("count");
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }
}
