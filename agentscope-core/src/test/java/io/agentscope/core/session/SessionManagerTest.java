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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.State;
import io.agentscope.core.state.StateModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Simple test state module implementation using the new API. */
    private static class TestStateModule implements StateModule {
        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        @Override
        public void saveTo(Session session, SessionKey sessionKey) {
            session.save(sessionKey, "testStateModule_value", new TestState(value));
        }

        @Override
        public void loadFrom(Session session, SessionKey sessionKey) {
            session.get(sessionKey, "testStateModule_value", TestState.class)
                    .ifPresent(state -> this.value = state.value());
        }
    }

    /** Simple state record for testing. */
    public record TestState(String value) implements State {}

    /** Simple test session implementation for testing custom session support. */
    private static class TestSession implements Session {

        private final Map<String, Map<String, State>> singleStates = new ConcurrentHashMap<>();
        private final Map<String, Map<String, List<State>>> listStates = new ConcurrentHashMap<>();

        @Override
        public void save(SessionKey sessionKey, String key, State value) {
            String sessionId = sessionKey.toString();
            singleStates.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(key, value);
        }

        @Override
        public void save(SessionKey sessionKey, String key, List<? extends State> values) {
            String sessionId = sessionKey.toString();
            listStates
                    .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                    .put(key, List.copyOf(values));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends State> Optional<T> get(SessionKey sessionKey, String key, Class<T> type) {
            String sessionId = sessionKey.toString();
            Map<String, State> states = singleStates.get(sessionId);
            if (states == null) {
                return Optional.empty();
            }
            State state = states.get(key);
            if (state == null) {
                return Optional.empty();
            }
            return Optional.of((T) state);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends State> List<T> getList(
                SessionKey sessionKey, String key, Class<T> itemType) {
            String sessionId = sessionKey.toString();
            Map<String, List<State>> lists = listStates.get(sessionId);
            if (lists == null) {
                return List.of();
            }
            List<State> list = lists.get(key);
            if (list == null) {
                return List.of();
            }
            return (List<T>) list;
        }

        @Override
        public boolean exists(SessionKey sessionKey) {
            String sessionId = sessionKey.toString();
            return singleStates.containsKey(sessionId) || listStates.containsKey(sessionId);
        }

        @Override
        public void delete(SessionKey sessionKey) {
            String sessionId = sessionKey.toString();
            singleStates.remove(sessionId);
            listStates.remove(sessionId);
        }

        @Override
        public Set<SessionKey> listSessionKeys() {
            return Set.of();
        }
    }
}
