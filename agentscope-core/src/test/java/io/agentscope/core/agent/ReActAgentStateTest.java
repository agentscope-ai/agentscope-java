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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.model.SubTask;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.state.AgentMetaState;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.StatePersistence;
import io.agentscope.core.state.ToolkitState;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for ReActAgent's StateModule implementation (saveTo/loadFrom). */
@DisplayName("ReActAgent StateModule Tests")
class ReActAgentStateTest {

    private InMemorySession session;
    private SessionKey sessionKey;
    private MockModel mockModel;

    @BeforeEach
    void setUp() {
        session = new InMemorySession();
        sessionKey = SimpleSessionKey.of("test_session");
        mockModel = new MockModel("Test response");
    }

    @Nested
    @DisplayName("Agent Metadata Persistence")
    class AgentMetadataTests {

        @Test
        @DisplayName("Should save and load agent metadata")
        void testAgentMetadataSaveLoad() {
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .sysPrompt("You are a helpful assistant.")
                            .description("A test agent")
                            .model(mockModel)
                            .build();

            agent.saveTo(session, sessionKey);

            Optional<AgentMetaState> loaded =
                    session.get(sessionKey, "agent_meta", AgentMetaState.class);
            assertTrue(loaded.isPresent());
            assertEquals("TestAgent", loaded.get().name());
            assertEquals("A test agent", loaded.get().description());
            assertEquals("You are a helpful assistant.", loaded.get().systemPrompt());
            assertNotNull(loaded.get().id());
        }

        @Test
        @DisplayName("Should preserve agent ID across save/load")
        void testAgentIdPreservation() {
            ReActAgent agent = ReActAgent.builder().name("TestAgent").model(mockModel).build();

            String originalId = agent.getAgentId();
            agent.saveTo(session, sessionKey);

            Optional<AgentMetaState> loaded =
                    session.get(sessionKey, "agent_meta", AgentMetaState.class);
            assertTrue(loaded.isPresent());
            assertEquals(originalId, loaded.get().id());
        }
    }

    @Nested
    @DisplayName("Memory Persistence")
    class MemoryPersistenceTests {

        @Test
        @DisplayName("Should save and load memory messages")
        void testMemorySaveLoad() {
            InMemoryMemory memory = new InMemoryMemory();
            ReActAgent agent =
                    ReActAgent.builder().name("TestAgent").model(mockModel).memory(memory).build();

            // Add messages to memory
            memory.addMessage(createUserMsg("Hello"));
            memory.addMessage(createAssistantMsg("Hi there!"));

            agent.saveTo(session, sessionKey);

            // Create new agent and load
            InMemoryMemory newMemory = new InMemoryMemory();
            ReActAgent newAgent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .model(mockModel)
                            .memory(newMemory)
                            .build();

            newAgent.loadFrom(session, sessionKey);

            assertEquals(2, newMemory.getMessages().size());
            assertEquals("Hello", getTextContent(newMemory.getMessages().get(0)));
            assertEquals("Hi there!", getTextContent(newMemory.getMessages().get(1)));
        }

        @Test
        @DisplayName("Should not save memory when memoryManaged is false")
        void testMemoryNotManagedSkipsSave() {
            InMemoryMemory memory = new InMemoryMemory();
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .model(mockModel)
                            .memory(memory)
                            .statePersistence(
                                    StatePersistence.builder().memoryManaged(false).build())
                            .build();

            memory.addMessage(createUserMsg("Test message"));
            agent.saveTo(session, sessionKey);

            // Memory should not be saved, so loading should give empty
            InMemoryMemory newMemory = new InMemoryMemory();
            ReActAgent newAgent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .model(mockModel)
                            .memory(newMemory)
                            .build();

            newAgent.loadFrom(session, sessionKey);
            assertTrue(newMemory.getMessages().isEmpty());
        }
    }

    @Nested
    @DisplayName("Toolkit Persistence")
    class ToolkitPersistenceTests {

        @Test
        @DisplayName("Should save and load toolkit activeGroups")
        void testToolkitSaveLoad() {
            Toolkit toolkit = new Toolkit();
            toolkit.setActiveGroups(List.of("web", "file", "calculator"));

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .build();

            agent.saveTo(session, sessionKey);

            Optional<ToolkitState> loaded =
                    session.get(sessionKey, "toolkit_activeGroups", ToolkitState.class);
            assertTrue(loaded.isPresent());
            assertEquals(3, loaded.get().activeGroups().size());
            assertTrue(loaded.get().activeGroups().contains("web"));
            assertTrue(loaded.get().activeGroups().contains("file"));
            assertTrue(loaded.get().activeGroups().contains("calculator"));
        }

        @Test
        @DisplayName("Should restore toolkit activeGroups on load")
        void testToolkitLoadRestoresActiveGroups() {
            // First save with some activeGroups
            Toolkit toolkit1 = new Toolkit();
            toolkit1.setActiveGroups(List.of("group1", "group2"));

            ReActAgent agent1 =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .model(mockModel)
                            .toolkit(toolkit1)
                            .build();

            agent1.saveTo(session, sessionKey);

            // Then load into new agent with different toolkit
            Toolkit toolkit2 = new Toolkit();
            toolkit2.setActiveGroups(List.of("other"));

            ReActAgent agent2 =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .model(mockModel)
                            .toolkit(toolkit2)
                            .build();

            agent2.loadFrom(session, sessionKey);

            // Should have loaded the saved activeGroups
            // Note: Agent uses a copy of toolkit, so we need to check agent's internal toolkit
            Toolkit agentToolkit = agent2.getToolkit();
            assertEquals(2, agentToolkit.getActiveGroups().size());
            assertTrue(agentToolkit.getActiveGroups().contains("group1"));
            assertTrue(agentToolkit.getActiveGroups().contains("group2"));
        }

        @Test
        @DisplayName("Should not save toolkit when toolkitManaged is false")
        void testToolkitNotManagedSkipsSave() {
            Toolkit toolkit = new Toolkit();
            toolkit.setActiveGroups(List.of("group1", "group2"));

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .statePersistence(
                                    StatePersistence.builder().toolkitManaged(false).build())
                            .build();

            agent.saveTo(session, sessionKey);

            Optional<ToolkitState> loaded =
                    session.get(sessionKey, "toolkit_activeGroups", ToolkitState.class);
            assertFalse(loaded.isPresent());
        }
    }

    @Nested
    @DisplayName("PlanNotebook Persistence")
    class PlanNotebookPersistenceTests {

        @Test
        @DisplayName("Should save and load PlanNotebook state")
        void testPlanNotebookSaveLoad() {
            PlanNotebook notebook = PlanNotebook.builder().build();
            notebook.createPlanWithSubTasks(
                            "Test Plan",
                            "Test description",
                            "Expected outcome",
                            List.of(
                                    new SubTask("Task 1", "Desc 1", "Outcome 1"),
                                    new SubTask("Task 2", "Desc 2", "Outcome 2")))
                    .block();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .model(mockModel)
                            .planNotebook(notebook)
                            .build();

            agent.saveTo(session, sessionKey);

            // Load into new agent
            PlanNotebook newNotebook = PlanNotebook.builder().build();
            ReActAgent newAgent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .model(mockModel)
                            .planNotebook(newNotebook)
                            .build();

            newAgent.loadFrom(session, sessionKey);

            assertNotNull(newNotebook.getCurrentPlan());
            assertEquals("Test Plan", newNotebook.getCurrentPlan().getName());
            assertEquals(2, newNotebook.getCurrentPlan().getSubtasks().size());
        }

        @Test
        @DisplayName("Should not save PlanNotebook when planNotebookManaged is false")
        void testPlanNotebookNotManagedSkipsSave() {
            PlanNotebook notebook = PlanNotebook.builder().build();
            notebook.createPlanWithSubTasks(
                            "Test Plan",
                            "Description",
                            "Outcome",
                            List.of(new SubTask("T", "D", "O")))
                    .block();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .model(mockModel)
                            .planNotebook(notebook)
                            .statePersistence(
                                    StatePersistence.builder().planNotebookManaged(false).build())
                            .build();

            agent.saveTo(session, sessionKey);

            // PlanNotebook state should not be saved
            PlanNotebook newNotebook = PlanNotebook.builder().build();
            newNotebook.loadFrom(session, sessionKey);

            // Plan should be null since it wasn't saved
            assertNull(newNotebook.getCurrentPlan());
        }
    }

    @Nested
    @DisplayName("StatePersistence Configuration")
    class StatePersistenceConfigTests {

        @Test
        @DisplayName("Should respect StatePersistence.none() - only saves agent metadata")
        void testStatePersistenceNone() {
            InMemoryMemory memory = new InMemoryMemory();
            Toolkit toolkit = new Toolkit();
            PlanNotebook notebook = PlanNotebook.builder().build();

            memory.addMessage(createUserMsg("Test"));
            toolkit.setActiveGroups(List.of("group1"));
            notebook.createPlanWithSubTasks(
                            "Plan", "Desc", "Outcome", List.of(new SubTask("T", "D", "O")))
                    .block();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .model(mockModel)
                            .memory(memory)
                            .toolkit(toolkit)
                            .planNotebook(notebook)
                            .statePersistence(StatePersistence.none())
                            .build();

            agent.saveTo(session, sessionKey);

            // Only agent meta should be saved
            assertTrue(session.get(sessionKey, "agent_meta", AgentMetaState.class).isPresent());
            assertTrue(session.getList(sessionKey, "memory_messages", Msg.class).isEmpty());
            assertFalse(
                    session.get(sessionKey, "toolkit_activeGroups", ToolkitState.class)
                            .isPresent());
        }

        @Test
        @DisplayName("Should respect StatePersistence.all() - saves everything")
        void testStatePersistenceAll() {
            InMemoryMemory memory = new InMemoryMemory();
            Toolkit toolkit = new Toolkit();
            PlanNotebook notebook = PlanNotebook.builder().build();

            memory.addMessage(createUserMsg("Test"));
            toolkit.setActiveGroups(List.of("group1"));
            notebook.createPlanWithSubTasks(
                            "Plan", "Desc", "Outcome", List.of(new SubTask("T", "D", "O")))
                    .block();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .model(mockModel)
                            .memory(memory)
                            .toolkit(toolkit)
                            .planNotebook(notebook)
                            .statePersistence(StatePersistence.all())
                            .build();

            agent.saveTo(session, sessionKey);

            // Everything should be saved
            assertTrue(session.get(sessionKey, "agent_meta", AgentMetaState.class).isPresent());
            assertEquals(1, session.getList(sessionKey, "memory_messages", Msg.class).size());
            assertTrue(
                    session.get(sessionKey, "toolkit_activeGroups", ToolkitState.class)
                            .isPresent());
        }

        @Test
        @DisplayName("Should respect StatePersistence.memoryOnly()")
        void testStatePersistenceMemoryOnly() {
            InMemoryMemory memory = new InMemoryMemory();
            Toolkit toolkit = new Toolkit();

            memory.addMessage(createUserMsg("Test"));
            toolkit.setActiveGroups(List.of("group1"));

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .model(mockModel)
                            .memory(memory)
                            .toolkit(toolkit)
                            .statePersistence(StatePersistence.memoryOnly())
                            .build();

            agent.saveTo(session, sessionKey);

            // Only agent meta and memory should be saved
            assertTrue(session.get(sessionKey, "agent_meta", AgentMetaState.class).isPresent());
            assertEquals(1, session.getList(sessionKey, "memory_messages", Msg.class).size());
            assertFalse(
                    session.get(sessionKey, "toolkit_activeGroups", ToolkitState.class)
                            .isPresent());
        }
    }

    @Nested
    @DisplayName("loadIfExists()")
    class LoadIfExistsTests {

        @Test
        @DisplayName("Should return true and load when session exists")
        void testLoadIfExistsTrue() {
            InMemoryMemory memory = new InMemoryMemory();
            memory.addMessage(createUserMsg("Test message"));

            ReActAgent agent =
                    ReActAgent.builder().name("TestAgent").model(mockModel).memory(memory).build();

            agent.saveTo(session, sessionKey);

            InMemoryMemory newMemory = new InMemoryMemory();
            ReActAgent newAgent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .model(mockModel)
                            .memory(newMemory)
                            .build();

            boolean exists = newAgent.loadIfExists(session, sessionKey);

            assertTrue(exists);
            assertEquals(1, newMemory.getMessages().size());
        }

        @Test
        @DisplayName("Should return false when session doesn't exist")
        void testLoadIfExistsFalse() {
            InMemoryMemory memory = new InMemoryMemory();
            ReActAgent agent =
                    ReActAgent.builder().name("TestAgent").model(mockModel).memory(memory).build();

            SessionKey nonExistent = SimpleSessionKey.of("non_existent");
            boolean exists = agent.loadIfExists(session, nonExistent);

            assertFalse(exists);
        }
    }

    @Nested
    @DisplayName("Integration with JsonSession")
    class JsonSessionIntegrationTests {

        @TempDir Path tempDir;

        @Test
        @DisplayName("Should persist state across JsonSession save/load")
        void testJsonSessionPersistence() {
            JsonSession jsonSession = new JsonSession(tempDir);
            SessionKey key = SimpleSessionKey.of("json_test");

            // Create and save agent state
            InMemoryMemory memory = new InMemoryMemory();
            memory.addMessage(createUserMsg("Hello from JsonSession"));
            memory.addMessage(createAssistantMsg("Response from JsonSession"));

            Toolkit toolkit = new Toolkit();
            toolkit.setActiveGroups(List.of("web", "file"));

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("JsonTestAgent")
                            .sysPrompt("Test system prompt")
                            .model(mockModel)
                            .memory(memory)
                            .toolkit(toolkit)
                            .build();

            agent.saveTo(jsonSession, key);

            // Load into new agent
            InMemoryMemory newMemory = new InMemoryMemory();
            Toolkit newToolkit = new Toolkit();

            ReActAgent newAgent =
                    ReActAgent.builder()
                            .name("JsonTestAgent")
                            .model(mockModel)
                            .memory(newMemory)
                            .toolkit(newToolkit)
                            .build();

            newAgent.loadFrom(jsonSession, key);

            // Verify all state was loaded
            assertEquals(2, newMemory.getMessages().size());
            assertEquals("Hello from JsonSession", getTextContent(newMemory.getMessages().get(0)));
            // Note: Agent uses a copy of toolkit, so we need to check agent's internal toolkit
            Toolkit agentToolkit = newAgent.getToolkit();
            assertEquals(2, agentToolkit.getActiveGroups().size());
            assertTrue(agentToolkit.getActiveGroups().contains("web"));
        }

        @Test
        @DisplayName("Should handle incremental memory saves with JsonSession")
        void testIncrementalSavesWithJsonSession() {
            JsonSession jsonSession = new JsonSession(tempDir);
            SessionKey key = SimpleSessionKey.of("incremental_test");

            InMemoryMemory memory = new InMemoryMemory();
            ReActAgent agent =
                    ReActAgent.builder().name("TestAgent").model(mockModel).memory(memory).build();

            // First save
            memory.addMessage(createUserMsg("Message 1"));
            agent.saveTo(jsonSession, key);

            // Second save (incremental)
            memory.addMessage(createUserMsg("Message 2"));
            memory.addMessage(createUserMsg("Message 3"));
            agent.saveTo(jsonSession, key);

            // Load and verify
            InMemoryMemory loadedMemory = new InMemoryMemory();
            ReActAgent loadedAgent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .model(mockModel)
                            .memory(loadedMemory)
                            .build();

            loadedAgent.loadFrom(jsonSession, key);

            assertEquals(3, loadedMemory.getMessages().size());
            assertEquals("Message 1", getTextContent(loadedMemory.getMessages().get(0)));
            assertEquals("Message 2", getTextContent(loadedMemory.getMessages().get(1)));
            assertEquals("Message 3", getTextContent(loadedMemory.getMessages().get(2)));
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
