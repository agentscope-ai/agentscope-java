package io.agentscope.core.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Tests for conversation flow patterns.
 *
 * <p>These tests verify different conversation patterns including user-to-agent flow, agent
 * reasoning, and response generation with REAL API calls.
 *
 * <p><b>Requirements:</b> DASHSCOPE_API_KEY environment variable must be set
 */
@Tag("e2e")
@Tag("integration")
@EnabledIfEnvironmentVariable(
        named = "DASHSCOPE_API_KEY",
        matches = ".+",
        disabledReason = "E2E tests require DASHSCOPE_API_KEY environment variable")
@DisplayName("Conversation Flow Tests")
class ConversationFlowTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);
    private ReActAgent agent;
    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        toolkit = E2ETestUtils.createTestToolkit();
        agent = E2ETestUtils.createReActAgentWithRealModel("ConversationAgent", toolkit);
        System.out.println("=== Conversation Flow Test Setup Complete ===");
    }

    @Test
    @DisplayName("Should complete user-to-agent flow")
    void testUserToAgentFlow() {
        System.out.println("\n=== Test: User to Agent Flow ===");

        // Arrange
        Msg userInput = TestUtils.createUserMessage("User", "Hello! Can you help me?");
        System.out.println("Sending: " + userInput);

        // Act
        List<Msg> responses = E2ETestUtils.waitForResponse(agent, userInput, TEST_TIMEOUT);

        // Assert
        assertNotNull(responses, "Response should not be null");
        assertTrue(responses.size() > 0, "Should receive at least one response");

        System.out.println("Received " + responses.size() + " response chunks");

        // Verify agent responds with meaningful content
        assertTrue(
                E2ETestUtils.hasMeaningfulContent(responses),
                "Agent should provide meaningful response");

        // Check first response has text
        String firstResponseText = TestUtils.extractTextContent(responses.get(0));
        assertNotNull(firstResponseText, "First response should have text content");
        assertFalse(firstResponseText.trim().isEmpty(), "Response should not be empty");
    }

    @Test
    @DisplayName("Should demonstrate agent reasoning with calculation")
    void testAgentReasoning() {
        System.out.println("\n=== Test: Agent Reasoning ===");

        // Arrange - question that requires reasoning
        Msg userInput =
                TestUtils.createUserMessage(
                        "User",
                        "I need to calculate the sum of 25 and 17, then multiply by 2. What's the"
                                + " result?");
        System.out.println("Sending: " + userInput);

        // Act
        List<Msg> responses = E2ETestUtils.waitForResponse(agent, userInput, TEST_TIMEOUT);

        // Assert
        assertNotNull(responses, "Response should not be null");
        assertTrue(responses.size() > 0, "Should receive responses");

        System.out.println("Received " + responses.size() + " response chunks");

        // Agent should reason through the problem
        assertTrue(
                E2ETestUtils.hasMeaningfulContent(responses),
                "Response should contain reasoning and answer");

        // Should contain the correct answer (25 + 17 = 42, 42 * 2 = 84)
        boolean hasAnswer = E2ETestUtils.responsesContain(responses, "84");
        System.out.println("Response contains answer '84': " + hasAnswer);
    }

    @Test
    @DisplayName("Should demonstrate agent acting with tools")
    void testAgentActing() {
        System.out.println("\n=== Test: Agent Acting ===");

        // Arrange - task that requires tool use
        Msg userInput = TestUtils.createUserMessage("User", "Please calculate the factorial of 6");
        System.out.println("Sending: " + userInput);

        // Act
        List<Msg> responses = E2ETestUtils.waitForResponse(agent, userInput, TEST_TIMEOUT);

        // Assert
        assertNotNull(responses, "Response should not be null");
        assertTrue(responses.size() > 0, "Should receive responses");

        System.out.println("Received " + responses.size() + " response chunks");

        // Verify response contains calculation result
        assertTrue(
                E2ETestUtils.hasMeaningfulContent(responses),
                "Response should contain factorial result");

        // Should contain the correct answer (6! = 720)
        boolean hasAnswer = E2ETestUtils.responsesContain(responses, "720");
        System.out.println("Response contains answer '720': " + hasAnswer);
    }

    @Test
    @DisplayName("Should generate appropriate responses for different inputs")
    void testResponseGeneration() {
        System.out.println("\n=== Test: Response Generation ===");

        // Test different types of inputs
        String[] questions = {"Hello", "What is 10 plus 20?", "Tell me a fun fact", "Thank you"};

        for (String questionText : questions) {
            System.out.println("\nTesting: " + questionText);

            Msg input = TestUtils.createUserMessage("User", questionText);
            List<Msg> responses = E2ETestUtils.waitForResponse(agent, input, TEST_TIMEOUT);

            assertNotNull(responses, "Response should not be null for: " + questionText);
            assertTrue(responses.size() > 0, "Should receive responses for: " + questionText);

            assertTrue(
                    E2ETestUtils.hasMeaningfulContent(responses),
                    "Response should be substantial for: " + questionText);

            System.out.println("  Received " + responses.size() + " chunks");
        }

        // Verify all interactions are in memory
        List<Msg> allMessages = agent.getMemory().getMessages();
        assertTrue(allMessages.size() >= questions.length, "Should have all messages in memory");

        System.out.println("\nFinal memory: " + allMessages.size() + " messages");
    }
}
