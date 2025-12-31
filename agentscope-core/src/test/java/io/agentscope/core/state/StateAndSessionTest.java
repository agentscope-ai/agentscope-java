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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class StateAndSessionTest {

    @Test
    public void testMemorySaveToAndLoadFrom() {
        InMemorySession session = new InMemorySession();
        SessionKey sessionKey = SimpleSessionKey.of("test_session");

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

        // Save state using new API
        memory.saveTo(session, sessionKey);

        // Verify session exists
        assertTrue(session.exists(sessionKey));

        // Load state into new memory
        InMemoryMemory newMemory = new InMemoryMemory();
        newMemory.loadFrom(session, sessionKey);

        assertEquals(2, newMemory.getMessages().size());
        assertEquals(
                "Hello",
                ((TextBlock) newMemory.getMessages().get(0).getFirstContentBlock()).getText());
        assertEquals(
                "Hi there!",
                ((TextBlock) newMemory.getMessages().get(1).getFirstContentBlock()).getText());
    }

    @Test
    public void testAgentStateManagement() {
        InMemorySession session = new InMemorySession();
        SessionKey sessionKey = SimpleSessionKey.of("agent_session");

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

        // Save agent state
        agent.saveTo(session, sessionKey);

        // Verify session exists
        assertTrue(session.exists(sessionKey));

        // Create new agent and load state
        InMemoryMemory newMemory = new InMemoryMemory();
        ReActAgent newAgent =
                ReActAgent.builder()
                        .name("EmptyAgent")
                        .sysPrompt("Empty")
                        .model(model)
                        .toolkit(new Toolkit())
                        .memory(newMemory)
                        .build();

        newAgent.loadFrom(session, sessionKey);

        // Verify state was restored - only memory state is restored
        // Agent name is configuration, not runtime state
        assertEquals(1, newMemory.getMessages().size());
    }

    @Test
    public void testJsonSessionSaveAndLoad(@TempDir Path tempDir) throws Exception {
        // Create session manager
        JsonSession session = new JsonSession(tempDir.resolve("sessions"));
        SessionKey sessionKey = SimpleSessionKey.of("test_session_123");

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

        // Save agent state directly
        agent.saveTo(session, sessionKey);

        // Verify session exists
        assertTrue(session.exists(sessionKey));

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

        // Load agent state directly
        newAgent.loadFrom(session, sessionKey);

        // Verify state was restored - only memory state is restored
        // Agent name is configuration, not runtime state
        assertEquals(1, newMemory.getMessages().size());
        assertEquals(
                "Hello world",
                ((TextBlock) newMemory.getMessages().get(0).getFirstContentBlock()).getText());

        // Test session listing
        Set<SessionKey> sessionKeys = session.listSessionKeys();
        assertTrue(sessionKeys.stream().anyMatch(k -> k.toString().contains("test_session_123")));

        // Test session deletion
        session.delete(sessionKey);
        assertFalse(session.exists(sessionKey));
    }

    @Test
    public void testStateModuleSaveToAndLoadFrom() {
        InMemorySession session = new InMemorySession();
        SessionKey sessionKey = SimpleSessionKey.of("test_session");

        TestStateModule module = new TestStateModule();
        module.setValue("test_value");

        // Save state
        module.saveTo(session, sessionKey);

        // Verify state was saved
        Optional<TestState> loaded = session.get(sessionKey, "testModule_value", TestState.class);
        assertTrue(loaded.isPresent());
        assertEquals("test_value", loaded.get().value());

        // Load into new module
        TestStateModule newModule = new TestStateModule();
        newModule.loadFrom(session, sessionKey);

        assertEquals("test_value", newModule.getValue());
    }

    @Test
    public void testLoadIfExistsReturnsFalseForNonExistent() {
        InMemorySession session = new InMemorySession();
        SessionKey sessionKey = SimpleSessionKey.of("non_existent");

        TestStateModule module = new TestStateModule();
        module.setValue("original_value");

        // loadIfExists should return false for non-existent session
        boolean loaded = module.loadIfExists(session, sessionKey);
        assertFalse(loaded);

        // Original value should be preserved
        assertEquals("original_value", module.getValue());
    }

    @Test
    public void testLoadIfExistsReturnsTrueForExisting() {
        InMemorySession session = new InMemorySession();
        SessionKey sessionKey = SimpleSessionKey.of("existing_session");

        TestStateModule module = new TestStateModule();
        module.setValue("saved_value");
        module.saveTo(session, sessionKey);

        // Create new module and loadIfExists
        TestStateModule newModule = new TestStateModule();
        boolean loaded = newModule.loadIfExists(session, sessionKey);
        assertTrue(loaded);

        assertEquals("saved_value", newModule.getValue());
    }

    /** Test StateModule implementation for testing purposes. */
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
            session.save(sessionKey, "testModule_value", new TestState(value));
        }

        @Override
        public void loadFrom(Session session, SessionKey sessionKey) {
            session.get(sessionKey, "testModule_value", TestState.class)
                    .ifPresent(state -> this.value = state.value());
        }
    }

    /** Simple state record for testing. */
    public record TestState(String value) implements State {}
}
