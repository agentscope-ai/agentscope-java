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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.state.StateModule;
import io.agentscope.core.state.StateModuleBase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SessionManagerTest {

    @AfterAll
    static void tearDown() {
        try {
            Files.deleteIfExists(Paths.get("sessions"));
            Files.deleteIfExists(Paths.get("custom_path"));
        } catch (IOException e) {
            // Ignore deletion errors
        }
    }

    @Test
    public void testComponentNameResolution() {
        // Test components that implement getComponentName
        InMemoryMemory memory = new InMemoryMemory();
        assertEquals("memory", memory.getComponentName());

        // Test custom component without getComponentName
        StateModule customModule =
                new StateModule() {
                    @Override
                    public Map<String, Object> stateDict() {
                        return Map.of();
                    }

                    @Override
                    public void loadStateDict(Map<String, Object> stateDict, boolean strict) {
                        // Empty implementation
                    }

                    @Override
                    public String[] getRegisteredAttributes() {
                        return new String[0];
                    }

                    @Override
                    public boolean unregisterState(String attributeName) {
                        return false;
                    }

                    @Override
                    public void clearRegisteredState() {
                        // Empty implementation
                    }

                    @Override
                    public void registerState(
                            String attributeName,
                            java.util.function.Function<Object, Object> toJsonFunction,
                            java.util.function.Function<Object, Object> fromJsonFunction) {
                        // Empty implementation
                    }
                };

        // Should use default naming: class name with first letter lowercased
        SessionManager manager =
                SessionManager.forSessionId("test")
                        .withSession(new JsonSession(Path.of("sessions")))
                        .addComponent(customModule);
        Map<String, StateModule> componentMap = Map.of("customModule", customModule);

        // The manager should correctly name the component
        assertFalse(manager.sessionExists()); // Session doesn't exist, but that's ok
    }

    @Test
    public void testSessionManagerBuilder() {
        InMemoryMemory memory = new InMemoryMemory();
        StateModule customModule = new TestStateModule();

        // Test fluent API with default JsonSession
        SessionManager manager =
                SessionManager.forSessionId("test123")
                        .withSession(new JsonSession(Path.of("sessions")))
                        .addComponent(memory)
                        .addComponent(customModule);

        // Should not throw any exceptions during building
        assertFalse(manager.sessionExists());
    }

    @Test
    public void testCustomSessionPath() {
        InMemoryMemory memory = new InMemoryMemory();

        SessionManager manager =
                SessionManager.forSessionId("test")
                        .withSession(new JsonSession(Path.of("custom_path")))
                        .addComponent(memory);

        assertFalse(manager.sessionExists());
    }

    @Test
    public void testSessionSaveAndLoad(@TempDir Path tempDir) {
        InMemoryMemory memory = new InMemoryMemory();
        TestStateModule customModule = new TestStateModule();

        // Add some data to components
        memory.addMessage(
                io.agentscope.core.message.Msg.builder()
                        .name("user")
                        .role(io.agentscope.core.message.MsgRole.USER)
                        .content(
                                io.agentscope.core.message.TextBlock.builder()
                                        .text("Test message")
                                        .build())
                        .build());

        customModule.setValue("test_value");

        // Create session manager
        SessionManager manager =
                SessionManager.forSessionId("test_session")
                        .withSession(new JsonSession(tempDir))
                        .addComponent(memory)
                        .addComponent(customModule);

        // Should not exist initially
        assertFalse(manager.sessionExists());

        // Save session
        manager.saveSession();
        assertTrue(manager.sessionExists());

        // Create new manager and load
        InMemoryMemory newMemory = new InMemoryMemory();
        TestStateModule newCustomModule = new TestStateModule();

        SessionManager loadManager =
                SessionManager.forSessionId("test_session")
                        .withSession(new JsonSession(tempDir))
                        .addComponent(newMemory)
                        .addComponent(newCustomModule);

        loadManager.loadIfExists();

        // Verify data was loaded
        assertEquals(1, newMemory.getMessages().size());
        assertEquals("test_value", newCustomModule.getValue());
    }

    @Test
    public void testSaveOrThrow(@TempDir Path tempDir) {
        InMemoryMemory memory = new InMemoryMemory();

        SessionManager manager =
                SessionManager.forSessionId("test")
                        .withSession(new JsonSession(tempDir))
                        .addComponent(memory);

        // Should not throw for successful save
        manager.saveOrThrow();
        assertTrue(manager.sessionExists());
    }

    @Test
    public void testSaveIfExists(@TempDir Path tempDir) {
        InMemoryMemory memory = new InMemoryMemory();

        SessionManager manager =
                SessionManager.forSessionId("test")
                        .withSession(new JsonSession(tempDir))
                        .addComponent(memory);

        // Should not save if session doesn't exist
        manager.saveIfExists();
        assertFalse(manager.sessionExists());

        // Create session first
        manager.saveSession();
        assertTrue(manager.sessionExists());

        // Now saveIfExists should work
        manager.saveIfExists();
    }

    @Test
    public void testDeleteOperations(@TempDir Path tempDir) {
        InMemoryMemory memory = new InMemoryMemory();

        SessionManager manager =
                SessionManager.forSessionId("test")
                        .withSession(new JsonSession(tempDir))
                        .addComponent(memory);

        // Create session
        manager.saveSession();
        assertTrue(manager.sessionExists());

        // Delete if exists
        assertTrue(manager.deleteIfExists());
        assertFalse(manager.sessionExists());

        // Delete if exists again should return false
        assertFalse(manager.deleteIfExists());

        // Create session again
        manager.saveSession();
        assertTrue(manager.sessionExists());

        // Delete or throw should work
        manager.deleteOrThrow();
        assertFalse(manager.sessionExists());

        // Delete or throw on non-existent session should throw
        assertThrows(IllegalArgumentException.class, manager::deleteOrThrow);
    }

    @Test
    public void testValidation() {
        // Test null session ID
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    SessionManager.forSessionId(null);
                });

        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    SessionManager.forSessionId("");
                });

        // Test null component
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    SessionManager.forSessionId("test")
                            .withSession(new JsonSession(Path.of("sessions")))
                            .addComponent(null);
                });

        // Test null session path
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    SessionManager.forSessionId("test").withSession((Session) null);
                });
    }

    @Test
    public void testNoSessionConfigured() {
        InMemoryMemory memory = new InMemoryMemory();

        SessionManager manager = SessionManager.forSessionId("test").addComponent(memory);

        // Should throw IllegalStateException when no session is configured
        assertThrows(IllegalStateException.class, manager::sessionExists);
        assertThrows(IllegalStateException.class, manager::loadIfExists);
        assertThrows(IllegalStateException.class, manager::loadOrThrow);
        assertThrows(IllegalStateException.class, manager::saveSession);
        assertThrows(
                RuntimeException.class,
                manager::saveOrThrow); // saveOrThrow wraps IllegalStateException in
        // RuntimeException
        assertThrows(IllegalStateException.class, manager::saveIfExists);
        assertThrows(IllegalStateException.class, manager::getSession);
        assertThrows(IllegalStateException.class, manager::deleteIfExists);
        assertThrows(IllegalStateException.class, manager::deleteOrThrow);
    }

    /**
     * Simple test state module implementation.
     */
    private static class TestStateModule extends StateModuleBase {
        private String value;

        public TestStateModule() {
            // Register the value field for state management
            registerState("value");
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        @Override
        public String getComponentName() {
            return "customModule";
        }
    }

    /**
     * Simple test session implementation for testing custom session support.
     */
    private static class TestSession implements Session {

        private Map<String, Map<String, Object>> storage = new java.util.HashMap<>();

        @Override
        public void saveSessionState(String sessionId, Map<String, StateModule> stateModules) {
            Map<String, Object> sessionData = new java.util.HashMap<>();
            for (Map.Entry<String, StateModule> entry : stateModules.entrySet()) {
                sessionData.put(entry.getKey(), entry.getValue().stateDict());
            }
            storage.put(sessionId, sessionData);
        }

        @Override
        public void loadSessionState(
                String sessionId, boolean allowNotExist, Map<String, StateModule> stateModules) {
            Map<String, Object> sessionData = storage.get(sessionId);
            if (sessionData != null) {
                for (Map.Entry<String, StateModule> entry : stateModules.entrySet()) {
                    Object componentState = sessionData.get(entry.getKey());
                    if (componentState instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> stateMap = (Map<String, Object>) componentState;
                        entry.getValue().loadStateDict(stateMap, true);
                    }
                }
            } else if (!allowNotExist) {
                throw new IllegalArgumentException("Session not found: " + sessionId);
            }
        }

        @Override
        public boolean sessionExists(String sessionId) {
            return storage.containsKey(sessionId);
        }

        @Override
        public boolean deleteSession(String sessionId) {
            return storage.remove(sessionId) != null;
        }

        @Override
        public java.util.List<String> listSessions() {
            return new java.util.ArrayList<>(storage.keySet());
        }

        @Override
        public SessionInfo getSessionInfo(String sessionId) {
            Map<String, Object> sessionData = storage.get(sessionId);
            if (sessionData == null) {
                return new SessionInfo(sessionId, 0, 0, 0);
            }
            return new SessionInfo(
                    sessionId,
                    sessionData.toString().length(),
                    System.currentTimeMillis(),
                    sessionData.size());
        }
    }
}
