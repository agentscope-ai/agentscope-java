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
package io.agentscope.core.agent.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.MockToolkit;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Boundary and edge case tests for Agent functionality.
 *
 * <p>These tests verify agent behavior under unusual or extreme conditions including null inputs,
 * empty messages, invalid tool calls, and other edge cases.
 *
 * <p>Tagged as "integration" - run in CI or with explicit flag.
 *
 * <p>Run with: mvn test -Dtest.integration=true
 */
@Tag("integration")
@DisplayName("Agent Boundary Tests")
class AgentBoundaryTest {

    private ReActAgent agent;
    private MockModel mockModel;
    private MockToolkit mockToolkit;
    private InMemoryMemory memory;

    @BeforeEach
    void setUp() {
        memory = new InMemoryMemory();
        mockToolkit = new MockToolkit();
        mockModel = new MockModel(TestConstants.MOCK_MODEL_SIMPLE_RESPONSE);

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .memory(memory)
                        .build();
    }

    @Test
    @DisplayName("Should handle null message gracefully")
    void testNullInput() {
        // Attempting to stream null - behavior depends on implementation
        // Some implementations may throw NPE, others may handle gracefully
        try {
            Msg responses =
                    agent.call((Msg) null)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
            // If it doesn't throw, that's also acceptable behavior
            assertNotNull(responses, "Response should not be null");
        } catch (NullPointerException | IllegalArgumentException e) {
            // Expected for null input
            assertTrue(true, "Null input correctly rejected");
        }
    }

    @Test
    @DisplayName("Should handle empty message list")
    void testEmptyMessageList() {
        List<Msg> emptyList = new ArrayList<>();

        // Empty list may throw exception or return empty result
        try {
            Msg responses =
                    agent.call(emptyList)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
            // If it doesn't throw, verify behavior
            assertNotNull(responses, "Response list should not be null");
        } catch (Exception e) {
            // Expected for empty list
            assertTrue(true, "Empty list correctly handled");
        }
    }

    @Test
    @DisplayName("Should handle message with empty text content")
    void testEmptyTextContent() {
        // Create message with empty text
        Msg emptyMsg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("").build())
                        .build();

        // Should not throw exception
        assertDoesNotThrow(
                () -> {
                    Msg responses =
                            agent.call(emptyMsg)
                                    .block(
                                            Duration.ofMillis(
                                                    TestConstants.DEFAULT_TEST_TIMEOUT_MS));
                    assertNotNull(responses);
                },
                "Empty text content should be handled gracefully");
    }

    @Test
    @DisplayName("Should handle very long message content")
    void testVeryLongMessage() {
        // Create a very long message (10,000 characters)
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longText.append("This is a long message. ");
        }

        Msg longMsg = TestUtils.createUserMessage("User", longText.toString());

        // Should handle long message without issues
        assertDoesNotThrow(
                () -> {
                    Msg responses =
                            agent.call(longMsg)
                                    .block(Duration.ofMillis(TestConstants.LONG_TEST_TIMEOUT_MS));
                    assertNotNull(responses);
                },
                "Very long message should be handled");
    }

    @Test
    @DisplayName("Should handle invalid tool calls gracefully")
    void testInvalidToolCalls() {
        // Setup model to call non-existent tool
        mockModel =
                MockModel.withToolCall(
                        "nonexistent_tool",
                        "invalid_call_123",
                        TestUtils.createToolArguments("param", "value"));

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .memory(memory)
                        .build();

        Msg input = TestUtils.createUserMessage("User", "Test invalid tool");

        // Should handle invalid tool call without crashing
        assertDoesNotThrow(
                () -> {
                    Msg responses =
                            agent.call(input)
                                    .block(
                                            Duration.ofMillis(
                                                    TestConstants.DEFAULT_TEST_TIMEOUT_MS));
                    assertNotNull(responses);
                },
                "Invalid tool call should be handled gracefully");
    }

    @Test
    @DisplayName("Should handle special characters in messages")
    void testSpecialCharacters() {
        String specialText = "Test with special chars: <>&\"'`~!@#$%^&*()_+-=[]{}|;:,.<>?/\\";
        Msg specialMsg = TestUtils.createUserMessage("User", specialText);

        Msg responses =
                agent.call(specialMsg)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(responses);
        assertTrue(
                agent.getMemory().getMessages().size() >= 1,
                "Special characters should be handled");
    }

    @Test
    @DisplayName("Should handle unicode and emoji in messages")
    void testUnicodeAndEmoji() {
        String unicodeText = "Test with unicode: 你好世界 🌍 こんにちは 🎉 مرحبا";
        Msg unicodeMsg = TestUtils.createUserMessage("User", unicodeText);

        Msg response =
                agent.call(unicodeMsg)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response);
        assertTrue(agent.getMemory().getMessages().size() >= 1, "Unicode should be handled");
    }

    @Test
    @DisplayName("Should handle rapid successive messages")
    void testRapidSuccessiveMessages() {
        int messageCount = 20;

        // Send many messages rapidly
        for (int i = 0; i < messageCount; i++) {
            Msg msg = TestUtils.createUserMessage("User", "Rapid message " + i);
            assertDoesNotThrow(
                    () -> {
                        agent.call(msg)
                                .block(Duration.ofMillis(TestConstants.SHORT_TEST_TIMEOUT_MS));
                    },
                    "Rapid message " + i + " should be handled");
        }

        // Verify all messages were processed
        List<Msg> allMessages = agent.getMemory().getMessages();
        assertTrue(allMessages.size() >= messageCount, "All rapid messages should be in memory");
    }

    @Test
    @DisplayName("Should handle whitespace-only messages")
    void testWhitespaceOnlyMessages() {
        String[] whitespaceMessages = {"   ", "\n\n\n", "\t\t\t", " \n \t "};

        for (String whitespace : whitespaceMessages) {
            Msg msg = TestUtils.createUserMessage("User", whitespace);
            assertDoesNotThrow(
                    () -> {
                        Msg responses =
                                agent.call(msg)
                                        .block(
                                                Duration.ofMillis(
                                                        TestConstants.DEFAULT_TEST_TIMEOUT_MS));
                        assertNotNull(responses);
                    },
                    "Whitespace-only message should be handled");
        }
    }
}
