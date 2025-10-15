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
package io.agentscope.core.agent.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
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
 * Unit tests for UserAgent class.
 *
 * Tests cover:
 * - Agent initialization
 * - Custom input method handling
 * - Message creation from user input
 * - Memory management
 */
@DisplayName("UserAgent Tests")
class UserAgentTest {

    private UserAgent agent;
    private InMemoryMemory memory;
    private MockUserInput mockInput;

    /**
     * Mock implementation of UserInputBase for testing.
     */
    static class MockUserInput implements UserInputBase {
        private String responseText = "Mock user input";

        public void setResponseText(String text) {
            this.responseText = text;
        }

        @Override
        public Mono<UserInputData> handleInput(
                String agentId, String agentName, Class<?> structuredModel) {
            UserInputData data =
                    new UserInputData(
                            List.of(TextBlock.builder().text(responseText).build()), null);
            return Mono.just(data);
        }
    }

    @BeforeEach
    void setUp() {
        memory = new InMemoryMemory();
        mockInput = new MockUserInput();
        agent = new UserAgent(TestConstants.TEST_USER_AGENT_NAME, memory, mockInput);
    }

    @Test
    @DisplayName("Should initialize UserAgent with correct properties")
    void testInitialization() {
        assertNotNull(agent.getAgentId(), "Agent ID should not be null");
        assertEquals(
                TestConstants.TEST_USER_AGENT_NAME, agent.getName(), "Agent name should match");
        assertEquals(memory, agent.getMemory(), "Memory should be the same instance");
    }

    @Test
    @DisplayName("Should handle user input and create message")
    void testHandleUserInput() {
        // Set mock input
        mockInput.setResponseText(TestConstants.TEST_USER_INPUT);

        // Handle input
        Msg response =
                agent.handleUserInput(null)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify message
        assertNotNull(response, "Response message should not be null");
        assertEquals(
                TestConstants.TEST_USER_AGENT_NAME,
                response.getName(),
                "Message name should match agent");
        assertEquals(MsgRole.USER, response.getRole(), "Message role should be USER");

        String text = TestUtils.extractTextContent(response);
        assertEquals(TestConstants.TEST_USER_INPUT, text, "Message text should match input");
    }

    @Test
    @DisplayName("Should add user input to memory")
    void testMemoryUpdate() {
        assertTrue(agent.getMemory().getMessages().isEmpty(), "Memory should be empty initially");

        mockInput.setResponseText("Test input");
        agent.handleUserInput(null).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        List<Msg> messages = agent.getMemory().getMessages();
        assertFalse(messages.isEmpty(), "Memory should contain the message");
        assertEquals(1, messages.size(), "Memory should contain exactly one message");
    }

    @Test
    @DisplayName("Should support custom input method override")
    void testCustomInputMethod() {
        MockUserInput customInput = new MockUserInput();
        customInput.setResponseText("Custom input text");

        agent.overrideInstanceInputMethod(customInput);

        Msg response =
                agent.handleUserInput(null)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        String text = TestUtils.extractTextContent(response);
        assertEquals("Custom input text", text);
    }
}
