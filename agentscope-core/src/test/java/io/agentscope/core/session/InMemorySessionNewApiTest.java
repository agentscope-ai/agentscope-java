/*
 * Copyright 2024-2026 the original author or authors.
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.state.AgentMetaState;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.ToolkitState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for InMemorySession's new API methods. */
@DisplayName("InMemorySession New API Tests")
class InMemorySessionNewApiTest {

    private InMemorySession session;
    private SessionKey sessionKey;

    @BeforeEach
    void setUp() {
        session = new InMemorySession();
        sessionKey = SimpleSessionKey.of("test_session");
    }

    @Nested
    @DisplayName("save() and get() for single State")
    class SingleStateTests {

        @Test
        @DisplayName("Should save and retrieve AgentMetaState")
        void testSaveAndGetAgentMetaState() {
            AgentMetaState state =
                    new AgentMetaState("id_001", "Assistant", "A helpful assistant", "Be helpful.");

            session.save(sessionKey, "agent_meta", state);

            Optional<AgentMetaState> loaded =
                    session.get(sessionKey, "agent_meta", AgentMetaState.class);
            assertTrue(loaded.isPresent());
            assertEquals("id_001", loaded.get().id());
            assertEquals("Assistant", loaded.get().name());
            assertEquals("A helpful assistant", loaded.get().description());
            assertEquals("Be helpful.", loaded.get().systemPrompt());
        }

        @Test
        @DisplayName("Should save and retrieve ToolkitState")
        void testSaveAndGetToolkitState() {
            ToolkitState state = new ToolkitState(List.of("web", "file", "calculator"));

            session.save(sessionKey, "toolkit_groups", state);

            Optional<ToolkitState> loaded =
                    session.get(sessionKey, "toolkit_groups", ToolkitState.class);
            assertTrue(loaded.isPresent());
            assertEquals(3, loaded.get().activeGroups().size());
            assertTrue(loaded.get().activeGroups().contains("web"));
            assertTrue(loaded.get().activeGroups().contains("file"));
            assertTrue(loaded.get().activeGroups().contains("calculator"));
        }

        @Test
        @DisplayName("Should return empty for non-existent state")
        void testGetNonExistent() {
            Optional<AgentMetaState> loaded =
                    session.get(sessionKey, "non_existent", AgentMetaState.class);
            assertFalse(loaded.isPresent());
        }

        @Test
        @DisplayName("Should return empty for non-existent session")
        void testGetNonExistentSession() {
            SessionKey nonExistent = SimpleSessionKey.of("non_existent_session");
            Optional<AgentMetaState> loaded =
                    session.get(nonExistent, "agent_meta", AgentMetaState.class);
            assertFalse(loaded.isPresent());
        }

        @Test
        @DisplayName("Should overwrite existing state when saving again")
        void testOverwrite() {
            AgentMetaState state1 = new AgentMetaState("id_001", "Name1", null, null);
            session.save(sessionKey, "agent_meta", state1);

            AgentMetaState state2 = new AgentMetaState("id_002", "Name2", null, null);
            session.save(sessionKey, "agent_meta", state2);

            Optional<AgentMetaState> loaded =
                    session.get(sessionKey, "agent_meta", AgentMetaState.class);
            assertTrue(loaded.isPresent());
            assertEquals("id_002", loaded.get().id());
            assertEquals("Name2", loaded.get().name());
        }
    }

    @Nested
    @DisplayName("save() and getList() for List<State>")
    class ListStateTests {

        @Test
        @DisplayName("Should save and retrieve Msg list")
        void testSaveAndGetMsgList() {
            List<Msg> messages = new ArrayList<>();
            messages.add(createUserMsg("Hello"));
            messages.add(createAssistantMsg("Hi there!"));

            session.save(sessionKey, "memory_messages", messages);

            List<Msg> loaded = session.getList(sessionKey, "memory_messages", Msg.class);
            assertEquals(2, loaded.size());
            assertEquals("Hello", getTextContent(loaded.get(0)));
            assertEquals("Hi there!", getTextContent(loaded.get(1)));
        }

        @Test
        @DisplayName("Should replace list on subsequent saves")
        void testListReplacement() {
            List<Msg> messages1 = new ArrayList<>();
            messages1.add(createUserMsg("Message 1"));
            session.save(sessionKey, "memory_messages", messages1);

            List<Msg> messages2 = new ArrayList<>();
            messages2.add(createUserMsg("Message A"));
            messages2.add(createUserMsg("Message B"));
            messages2.add(createUserMsg("Message C"));
            session.save(sessionKey, "memory_messages", messages2);

            List<Msg> loaded = session.getList(sessionKey, "memory_messages", Msg.class);
            assertEquals(3, loaded.size());
            assertEquals("Message A", getTextContent(loaded.get(0)));
            assertEquals("Message B", getTextContent(loaded.get(1)));
            assertEquals("Message C", getTextContent(loaded.get(2)));
        }

        @Test
        @DisplayName("Should return empty list for non-existent key")
        void testGetNonExistentList() {
            List<Msg> loaded = session.getList(sessionKey, "non_existent", Msg.class);
            assertTrue(loaded.isEmpty());
        }

        @Test
        @DisplayName("Should return empty list for non-existent session")
        void testGetNonExistentSessionList() {
            SessionKey nonExistent = SimpleSessionKey.of("non_existent_session");
            List<Msg> loaded = session.getList(nonExistent, "memory_messages", Msg.class);
            assertTrue(loaded.isEmpty());
        }

        @Test
        @DisplayName("Should handle empty list")
        void testEmptyList() {
            List<Msg> messages = new ArrayList<>();
            session.save(sessionKey, "empty_list", messages);

            List<Msg> loaded = session.getList(sessionKey, "empty_list", Msg.class);
            assertTrue(loaded.isEmpty());
        }
    }

    @Nested
    @DisplayName("exists() and delete()")
    class ExistsAndDeleteTests {

        @Test
        @DisplayName("Should return true for existing session")
        void testExistsTrue() {
            session.save(sessionKey, "test_key", new AgentMetaState("id", "name", null, null));
            assertTrue(session.exists(sessionKey));
        }

        @Test
        @DisplayName("Should return false for non-existing session")
        void testExistsFalse() {
            assertFalse(session.exists(sessionKey));
        }

        @Test
        @DisplayName("Should delete session and all its data")
        void testDelete() {
            session.save(sessionKey, "key1", new AgentMetaState("id", "name", null, null));
            session.save(sessionKey, "key2", new ToolkitState(List.of("group1")));

            assertTrue(session.exists(sessionKey));

            session.delete(sessionKey);

            assertFalse(session.exists(sessionKey));
            assertFalse(session.get(sessionKey, "key1", AgentMetaState.class).isPresent());
            assertFalse(session.get(sessionKey, "key2", ToolkitState.class).isPresent());
        }

        @Test
        @DisplayName("Should not throw when deleting non-existent session")
        void testDeleteNonExistent() {
            session.delete(sessionKey); // Should not throw
            assertFalse(session.exists(sessionKey));
        }
    }

    @Nested
    @DisplayName("listSessionKeys()")
    class ListSessionKeysTests {

        @Test
        @DisplayName("Should list all session keys")
        void testListSessionKeys() {
            SessionKey key1 = SimpleSessionKey.of("session_1");
            SessionKey key2 = SimpleSessionKey.of("session_2");
            SessionKey key3 = SimpleSessionKey.of("session_3");

            session.save(key1, "test", new AgentMetaState("1", "n1", null, null));
            session.save(key2, "test", new AgentMetaState("2", "n2", null, null));
            session.save(key3, "test", new AgentMetaState("3", "n3", null, null));

            Set<SessionKey> keys = session.listSessionKeys();
            assertEquals(3, keys.size());
            assertTrue(keys.contains(key1));
            assertTrue(keys.contains(key2));
            assertTrue(keys.contains(key3));
        }

        @Test
        @DisplayName("Should return empty set when no sessions exist")
        void testEmptyList() {
            Set<SessionKey> keys = session.listSessionKeys();
            assertTrue(keys.isEmpty());
        }
    }

    @Nested
    @DisplayName("Multiple session keys")
    class MultipleSessionKeysTests {

        @Test
        @DisplayName("Should isolate data between different session keys")
        void testDataIsolation() {
            SessionKey key1 = SimpleSessionKey.of("session_1");
            SessionKey key2 = SimpleSessionKey.of("session_2");

            session.save(key1, "agent_meta", new AgentMetaState("id1", "Agent1", null, null));
            session.save(key2, "agent_meta", new AgentMetaState("id2", "Agent2", null, null));

            Optional<AgentMetaState> loaded1 =
                    session.get(key1, "agent_meta", AgentMetaState.class);
            Optional<AgentMetaState> loaded2 =
                    session.get(key2, "agent_meta", AgentMetaState.class);

            assertTrue(loaded1.isPresent());
            assertTrue(loaded2.isPresent());
            assertEquals("Agent1", loaded1.get().name());
            assertEquals("Agent2", loaded2.get().name());
        }

        @Test
        @DisplayName("Should delete only the specified session")
        void testSelectiveDelete() {
            SessionKey key1 = SimpleSessionKey.of("session_1");
            SessionKey key2 = SimpleSessionKey.of("session_2");

            session.save(key1, "test", new AgentMetaState("1", "n1", null, null));
            session.save(key2, "test", new AgentMetaState("2", "n2", null, null));

            session.delete(key1);

            assertFalse(session.exists(key1));
            assertTrue(session.exists(key2));
        }
    }

    @Nested
    @DisplayName("clearAll()")
    class ClearAllTests {

        @Test
        @DisplayName("Should clear all sessions")
        void testClearAll() {
            SessionKey key1 = SimpleSessionKey.of("session_1");
            SessionKey key2 = SimpleSessionKey.of("session_2");

            session.save(key1, "test", new AgentMetaState("1", "n1", null, null));
            session.save(key2, "test", new AgentMetaState("2", "n2", null, null));

            session.clearAll();

            assertFalse(session.exists(key1));
            assertFalse(session.exists(key2));
            assertTrue(session.listSessionKeys().isEmpty());
        }
    }

    // Helper methods

    private Msg createUserMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private Msg createAssistantMsg(String text) {
        return Msg.builder()
                .name("assistant")
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private String getTextContent(Msg msg) {
        return ((TextBlock) msg.getFirstContentBlock()).getText();
    }
}
