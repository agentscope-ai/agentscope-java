/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.SessionBase;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class StateAndSessionTest {

    @Test
    public void testStateModuleBasicFunctionality() {
        InMemoryMemory memory = new InMemoryMemory();

        // Add some messages
        memory.addMessage(
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Hello").build())
                        .build());

        memory.addMessage(
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Hi there!").build())
                        .build());

        // Test state serialization
        Map<String, Object> state = memory.stateDict();
        assertNotNull(state);
        assertTrue(state.containsKey("messages"));

        // Test state deserialization
        InMemoryMemory newMemory = new InMemoryMemory();
        newMemory.loadStateDict(state);

        assertEquals(2, newMemory.getMessages().size());
        assertEquals("Hello", newMemory.getMessages().get(0).getTextContent());
        assertEquals("Hi there!", newMemory.getMessages().get(1).getTextContent());
    }

    @Test
    public void testAgentStateManagement() {
        // Create agent components
        OpenAIChatModel model =
                OpenAIChatModel.builder().modelName("gpt-3.5-turbo").apiKey("test-key").build();

        Toolkit toolkit = new Toolkit();
        InMemoryMemory memory = new InMemoryMemory();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .build();

        // Add some conversation history
        memory.addMessage(
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What's 2+2?").build())
                        .build());

        // Test agent state serialization
        Map<String, Object> agentState = agent.stateDict();
        assertNotNull(agentState);
        assertTrue(agentState.containsKey("id"));
        assertTrue(agentState.containsKey("name"));
        assertTrue(agentState.containsKey("memory"));

        // Verify nested memory state is included
        @SuppressWarnings("unchecked")
        Map<String, Object> memoryState = (Map<String, Object>) agentState.get("memory");
        assertNotNull(memoryState);
        assertTrue(memoryState.containsKey("messages"));
    }

    @Test
    public void testJsonSessionSaveAndLoad(@TempDir Path tempDir) throws Exception {
        // Create session manager
        JsonSession session = new JsonSession(tempDir.resolve("sessions"));

        // Create agent components
        InMemoryMemory memory = new InMemoryMemory();
        memory.addMessage(
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Hello world").build())
                        .build());

        OpenAIChatModel model =
                OpenAIChatModel.builder().modelName("gpt-3.5-turbo").apiKey("test-key").build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("Test prompt")
                        .model(model)
                        .toolkit(new Toolkit())
                        .memory(memory)
                        .build();

        // Save session
        Map<String, StateModule> components =
                Map.of(
                        "agent", agent,
                        "memory", memory);

        String sessionId = "test_session_123";
        session.saveSessionState(sessionId, components);

        // Verify session exists
        assertTrue(session.sessionExists(sessionId));

        // Create new components to load into
        InMemoryMemory newMemory = new InMemoryMemory();
        ReActAgent newAgent =
                ReActAgent.builder()
                        .name("EmptyAgent")
                        .sysPrompt("Empty")
                        .model(model)
                        .toolkit(new Toolkit())
                        .memory(newMemory)
                        .build();

        Map<String, StateModule> newComponents =
                Map.of(
                        "agent", newAgent,
                        "memory", newMemory);

        // Load session
        session.loadSessionState(sessionId, newComponents);

        // Verify state was restored
        assertEquals("TestAgent", newAgent.getName());
        assertEquals(1, newMemory.getMessages().size());
        assertEquals("Hello world", newMemory.getMessages().get(0).getTextContent());

        // Test session info
        SessionBase.SessionInfo info = session.getSessionInfo(sessionId);
        assertEquals(sessionId, info.getSessionId());
        assertTrue(info.getSize() > 0);
        assertEquals(2, info.getComponentCount());

        // Test session listing
        assertTrue(session.listSessions().contains(sessionId));

        // Test session deletion
        assertTrue(session.deleteSession(sessionId));
        assertFalse(session.sessionExists(sessionId));
    }

    @Test
    public void testStateRegistration() {
        TestStateModule module = new TestStateModule();
        module.setValue("test_value");

        // Register custom attribute
        module.registerState("value");

        // Test state serialization includes registered attribute
        Map<String, Object> state = module.stateDict();
        assertTrue(state.containsKey("value"));
        assertEquals("test_value", state.get("value"));

        // Test state deserialization
        TestStateModule newModule = new TestStateModule();
        newModule.registerState("value");
        newModule.loadStateDict(state);

        assertEquals("test_value", newModule.getValue());

        // Test unregistration
        assertTrue(module.unregisterState("value"));
        assertFalse(module.isAttributeRegistered("value"));

        Map<String, Object> stateAfterUnregister = module.stateDict();
        assertFalse(stateAfterUnregister.containsKey("value"));
    }

    @Test
    public void testCustomSerialization() {
        TestStateModule module = new TestStateModule();
        module.setValue("original");

        // Register with custom serialization
        module.registerState(
                "value",
                value -> "serialized_" + value,
                value -> value.toString().replace("serialized_", ""));

        Map<String, Object> state = module.stateDict();
        assertEquals("serialized_original", state.get("value"));

        // Test deserialization
        TestStateModule newModule = new TestStateModule();
        newModule.registerState(
                "value",
                value -> "serialized_" + value,
                value -> value.toString().replace("serialized_", ""));

        newModule.loadStateDict(state);
        assertEquals("original", newModule.getValue());
    }

    /**
     * Test StateModule implementation for testing purposes.
     */
    private static class TestStateModule extends StateModuleBase {
        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
