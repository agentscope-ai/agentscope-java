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
import io.agentscope.core.interruption.InterruptContext;
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
import reactor.core.publisher.Mono;

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
        protected Mono<Msg> doCall(Msg msg) {
            // Simple echo implementation for testing
            addToMemory(msg);

            Msg response =
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text(testResponse).build())
                            .build();

            addToMemory(response);
            return Mono.just(response);
        }

        @Override
        protected Mono<Msg> doCall(List<Msg> msgs) {
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
            return Mono.just(response);
        }

        @Override
        protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
            Msg interruptMsg =
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Interrupted").build())
                            .build();
            addToMemory(interruptMsg);
            return Mono.just(interruptMsg);
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
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

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
                agent.call(messages)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

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
        agent.call(msg1).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify message was added to memory
        List<Msg> messages = agent.getMemory().getMessages();
        assertFalse(messages.isEmpty(), "Memory should not be empty after processing");
        assertTrue(messages.size() >= 1, "Memory should contain at least the input message");

        // Send another message
        Msg msg2 = TestUtils.createUserMessage("User", "Message 2");
        agent.call(msg2).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

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
        agent.call(msg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

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

    @Test
    @DisplayName("Should trigger interrupt without message")
    void testInterrupt() {
        // Verify interrupt methods exist and work
        assertNotNull(agent.getInterruptFlag(), "Should have interrupt flag");
        assertFalse(agent.getInterruptFlag().get(), "Interrupt flag should be false initially");

        // Test interrupt() method
        agent.interrupt();
        assertTrue(
                agent.getInterruptFlag().get(), "Interrupt flag should be set after interrupt()");

        // Test interrupt flag is visible
        assertTrue(agent.getInterruptFlag().get(), "Flag should remain set");
    }

    @Test
    @DisplayName("Should trigger interrupt with message")
    void testInterruptWithMessage() {
        Msg interruptMsg = TestUtils.createUserMessage("User", "Stop");

        // Test interrupt(Msg) method
        agent.interrupt(interruptMsg);
        assertTrue(
                agent.getInterruptFlag().get(),
                "Interrupt flag should be set after interrupt(Msg)");

        // Note: The interrupt message is stored but only added to memory during handleInterrupt
        // This test just verifies the API accepts the message and sets the flag
    }

    @Test
    @DisplayName("Should reset interrupt flag on each call")
    void testInterruptFlagReset() {
        // Set interrupt flag
        agent.interrupt();
        assertTrue(agent.getInterruptFlag().get(), "Flag should be set");

        // Make a call (this will handle the interrupt and complete)
        Msg msg = TestUtils.createUserMessage("User", "Test");
        agent.call(msg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Make another call - flag should be reset at the start
        Msg msg2 = TestUtils.createUserMessage("User", "Second call");
        agent.setTestResponse("Normal response");
        Msg response2 =
                agent.call(msg2).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify second call proceeds normally (not interrupted)
        String text = TestUtils.extractTextContent(response2);
        assertEquals("Normal response", text, "Second call should not be interrupted");
    }

    @Test
    @DisplayName("Should return correct interrupt flag")
    void testGetInterruptFlag() {
        // Get interrupt flag
        java.util.concurrent.atomic.AtomicBoolean flag = agent.getInterruptFlag();

        assertNotNull(flag, "Interrupt flag should not be null");
        assertFalse(flag.get(), "Interrupt flag should be false initially");

        // Trigger interrupt
        agent.interrupt();
        assertTrue(flag.get(), "Interrupt flag should be true after interrupt");
    }

    @Test
    @DisplayName("Should manage pending tool calls")
    void testPendingToolCallsManagement() {
        // Create sample tool calls
        io.agentscope.core.message.ToolUseBlock toolCall1 =
                io.agentscope.core.message.ToolUseBlock.builder()
                        .name("tool1")
                        .id("call-1")
                        .input(java.util.Map.of("param", "value"))
                        .build();
        io.agentscope.core.message.ToolUseBlock toolCall2 =
                io.agentscope.core.message.ToolUseBlock.builder()
                        .name("tool2")
                        .id("call-2")
                        .input(java.util.Map.of("param", "value"))
                        .build();

        List<io.agentscope.core.message.ToolUseBlock> toolCalls = List.of(toolCall1, toolCall2);

        // Create a test agent that exposes setPendingToolCalls
        TestAgent testAgent =
                new TestAgent("TestAgent", new InMemoryMemory()) {
                    @Override
                    protected Mono<Msg> doCall(Msg msg) {
                        // Set pending tool calls before potential interrupt
                        setPendingToolCalls(toolCalls);

                        // Simulate some work
                        addToMemory(msg);

                        // Trigger interrupt
                        interrupt();

                        // Try to check interrupted (will throw)
                        try {
                            checkInterrupted();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }

                        return Mono.just(
                                Msg.builder()
                                        .name(getName())
                                        .role(MsgRole.ASSISTANT)
                                        .content(
                                                TextBlock.builder()
                                                        .text("Should not reach")
                                                        .build())
                                        .build());
                    }
                };

        // Call agent (will be interrupted)
        Msg msg = TestUtils.createUserMessage("User", "Test");
        Msg response =
                testAgent.call(msg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify response is from handleInterrupt
        assertNotNull(response, "Should get interrupt response");
        assertEquals("Interrupted", TestUtils.extractTextContent(response));
    }

    @Test
    @DisplayName("Should call handleInterrupt when interrupted")
    void testHandleInterruptCallback() {
        // Create a custom agent that tracks handleInterrupt calls
        final boolean[] handleInterruptCalled = {false};

        TestAgent customAgent =
                new TestAgent("CustomAgent", new InMemoryMemory()) {
                    @Override
                    protected Mono<Msg> doCall(Msg msg) {
                        addToMemory(msg);
                        // Trigger interrupt
                        interrupt();
                        // Check interrupted (will throw)
                        try {
                            checkInterrupted();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        return super.doCall(msg);
                    }

                    @Override
                    protected Mono<Msg> handleInterrupt(
                            InterruptContext context, Msg... originalArgs) {
                        handleInterruptCalled[0] = true;
                        return super.handleInterrupt(context, originalArgs);
                    }
                };

        // Call agent
        Msg msg = TestUtils.createUserMessage("User", "Test");
        customAgent.call(msg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify handleInterrupt was called
        assertTrue(handleInterruptCalled[0], "handleInterrupt should be called on interruption");
    }
}
