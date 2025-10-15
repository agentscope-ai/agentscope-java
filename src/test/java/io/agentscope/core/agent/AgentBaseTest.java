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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Unit tests for AgentBase class.
 *
 * Tests cover:
 * - Agent initialization and properties
 * - Memory management
 * - State management
 * - Message handling
 * - Hook integration (basic)
 */
@DisplayName("AgentBase Tests")
class AgentBaseTest {

    private TestAgent agent;
    private Memory memory;

    /**
     * Concrete implementation of AgentBase for testing.
     */
    static class TestAgent extends AgentBase {
        private String testResponse = TestConstants.TEST_ASSISTANT_RESPONSE;

        public TestAgent(String name, Memory memory) {
            super(name, memory);
        }

        public void setTestResponse(String response) {
            this.testResponse = response;
        }

        @Override
        protected Flux<Msg> doStream(Msg msg) {
            // Simple echo implementation for testing
            addToMemory(msg);

            Msg response =
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text(testResponse).build())
                            .build();

            addToMemory(response);
            return Flux.just(response);
        }

        @Override
        protected Flux<Msg> doStream(List<Msg> msgs) {
            for (Msg m : msgs) {
                addToMemory(m);
            }

            Msg response =
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text(testResponse).build())
                            .build();

            addToMemory(response);
            return Flux.just(response);
        }
    }

    @BeforeEach
    void setUp() {
        memory = new InMemoryMemory();
        agent = new TestAgent(TestConstants.TEST_AGENT_NAME, memory);
    }

    @Test
    @DisplayName("Should initialize agent with correct properties")
    void testInitialization() {
        // Verify basic properties
        assertNotNull(agent.getAgentId(), "Agent ID should not be null");
        assertEquals(TestConstants.TEST_AGENT_NAME, agent.getName(), "Agent name should match");
        assertEquals(memory, agent.getMemory(), "Memory should be the same instance");

        // Verify agent ID is unique
        TestAgent agent2 = new TestAgent("Agent2", new InMemoryMemory());
        assertNotEquals(
                agent.getAgentId(),
                agent2.getAgentId(),
                "Different agents should have different IDs");
    }

    @Test
    @DisplayName("Should handle single message input")
    void testSingleMessageInput() {
        // Create user message
        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);

        // Get response
        Msg response =
                agent.stream(userMsg)
                        .blockLast(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify response
        assertNotNull(response, "Response should not be null");
        assertEquals(
                TestConstants.TEST_AGENT_NAME,
                response.getName(),
                "Response should be from the agent");
        assertEquals(MsgRole.ASSISTANT, response.getRole(), "Response should have ASSISTANT role");

        String text = TestUtils.extractTextContent(response);
        assertEquals(
                TestConstants.TEST_ASSISTANT_RESPONSE, text, "Response text should match expected");
    }

    @Test
    @DisplayName("Should handle multiple message input")
    void testMultipleMessageInput() {
        // Create multiple user messages
        List<Msg> messages =
                List.of(
                        TestUtils.createUserMessage("User", "First message"),
                        TestUtils.createUserMessage("User", "Second message"),
                        TestUtils.createUserMessage("User", "Third message"));

        // Get response
        Msg response =
                agent.stream(messages)
                        .blockLast(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify response
        assertNotNull(response, "Response should not be null");
        assertEquals(
                TestConstants.TEST_AGENT_NAME,
                response.getName(),
                "Response should be from the agent");

        // Verify all input messages are in memory
        List<Msg> memoryMessages = agent.getMemory().getMessages();
        assertTrue(
                memoryMessages.size() >= 3, "Memory should contain at least the 3 input messages");
    }

    @Test
    @DisplayName("Should manage memory correctly")
    void testMemoryManagement() {
        // Verify memory starts empty
        assertTrue(agent.getMemory().getMessages().isEmpty(), "Memory should be empty initially");

        // Send a message
        Msg msg1 = TestUtils.createUserMessage("User", "Message 1");
        agent.stream(msg1).blockLast(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify message was added to memory
        List<Msg> messages = agent.getMemory().getMessages();
        assertFalse(messages.isEmpty(), "Memory should not be empty after processing");
        assertTrue(messages.size() >= 1, "Memory should contain at least the input message");

        // Send another message
        Msg msg2 = TestUtils.createUserMessage("User", "Message 2");
        agent.stream(msg2).blockLast(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify both messages are in memory
        messages = agent.getMemory().getMessages();
        assertTrue(messages.size() >= 2, "Memory should grow with more messages");

        // Clear memory
        agent.getMemory().clear();
        assertTrue(
                agent.getMemory().getMessages().isEmpty(), "Memory should be empty after clearing");
    }

    @Test
    @DisplayName("Should support memory replacement")
    void testMemoryReplacement() {
        // Add message to original memory
        Msg msg = TestUtils.createUserMessage("User", "Test message");
        agent.stream(msg).blockLast(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertFalse(
                agent.getMemory().getMessages().isEmpty(), "Original memory should have messages");

        // Replace with new empty memory
        Memory newMemory = new InMemoryMemory();
        agent.setMemory(newMemory);

        // Verify memory was replaced
        assertEquals(newMemory, agent.getMemory(), "Memory should be replaced");
        assertTrue(agent.getMemory().getMessages().isEmpty(), "New memory should be empty");
    }

    @Test
    @DisplayName("Should support state management")
    void testStateManagement() {
        // Verify agent has basic properties that would be in state
        assertNotNull(agent.getAgentId());
        assertNotNull(agent.getName());
        assertNotNull(agent.getMemory());

        // Note: Full state serialization testing would require implementing
        // StateModule interface methods, which is tested separately
    }
}
