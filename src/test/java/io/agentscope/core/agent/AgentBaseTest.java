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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.interruption.InterruptContext;
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
 * <p>Tests cover:
 * - Agent initialization and properties
 * - Message handling
 * - Observe functionality
 * - Interrupt handling
 *
 * <p>Note: Memory management, state management, and pending tool calls are tested in specific
 * agent implementations (ReActAgent) that actually use these features.
 */
@DisplayName("AgentBase Tests")
class AgentBaseTest {

    private TestAgent agent;

    /**
     * Concrete implementation of AgentBase for testing.
     */
    static class TestAgent extends AgentBase {
        private String testResponse = TestConstants.TEST_ASSISTANT_RESPONSE;

        public TestAgent(String name) {
            super(name);
        }

        public void setTestResponse(String response) {
            this.testResponse = response;
        }

        @Override
        protected Mono<Msg> doCall(Msg msg) {
            // Simple echo implementation for testing
            Msg response =
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text(testResponse).build())
                            .build();

            return Mono.just(response);
        }

        @Override
        protected Mono<Msg> doCall(List<Msg> msgs) {
            Msg response =
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text(testResponse).build())
                            .build();

            return Mono.just(response);
        }

        @Override
        protected Mono<Void> doObserve(Msg msg) {
            // TestAgent doesn't need to observe, just complete
            return Mono.empty();
        }

        @Override
        protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
            Msg interruptMsg =
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Interrupted").build())
                            .build();
            return Mono.just(interruptMsg);
        }
    }

    @BeforeEach
    void setUp() {
        agent = new TestAgent(TestConstants.TEST_AGENT_NAME);
    }

    @Test
    @DisplayName("Should initialize agent with correct properties")
    void testInitialization() {
        // Verify basic properties
        assertNotNull(agent.getAgentId(), "Agent ID should not be null");
        assertEquals(TestConstants.TEST_AGENT_NAME, agent.getName(), "Agent name should match");

        // Verify agent ID is unique
        TestAgent agent2 = new TestAgent("Agent2");
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
    }

    @Test
    @DisplayName("Should handle observe without generating reply")
    void testObserve() {
        // Create a message to observe
        Msg msg = TestUtils.createUserMessage("User", "Observed message");

        // Observe should complete without error
        agent.observe(msg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Since TestAgent doesn't track observations, we just verify it completes
        // Actual observation behavior is tested in specific implementations (e.g., ReActAgent)
    }

    @Test
    @DisplayName("Should handle observe with multiple messages")
    void testObserveMultiple() {
        // Create multiple messages to observe
        List<Msg> messages =
                List.of(
                        TestUtils.createUserMessage("User", "Message 1"),
                        TestUtils.createUserMessage("User", "Message 2"));

        // Observe should complete without error
        agent.observe(messages).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
    }

    @Test
    @DisplayName("Should handle interrupt")
    void testInterrupt() {
        // Test interrupt() method
        agent.interrupt();

        // Verify agent can still be called (interrupt handling is internal)
        Msg msg = TestUtils.createUserMessage("User", "Test after interrupt");
        Msg response =
                agent.call(msg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Agent should still respond after interrupt");
    }

    @Test
    @DisplayName("Should handle interrupt with message")
    void testInterruptWithMessage() {
        Msg interruptMsg = TestUtils.createUserMessage("User", "Stop");

        // Test interrupt(Msg) method
        agent.interrupt(interruptMsg);

        // Verify agent can still be called
        Msg msg = TestUtils.createUserMessage("User", "Test after interrupt");
        Msg response =
                agent.call(msg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Agent should still respond after interrupt with message");
    }

    @Test
    @DisplayName("Should support continuation without new input")
    void testContinuation() {
        // Call without arguments (continuation)
        Msg response = agent.call().block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify response
        assertNotNull(response, "Response should not be null for continuation");
        assertEquals(MsgRole.ASSISTANT, response.getRole(), "Response should have ASSISTANT role");
    }
}
