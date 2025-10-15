package io.agentscope.core.e2e;

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
 * End-to-end tests for ReAct agent workflow.
 *
 * <p>These tests verify the complete ReAct flow from user input to final response, including tool
 * calls and multi-round conversations with REAL API calls.
 *
 * <p><b>Requirements:</b> DASHSCOPE_API_KEY environment variable must be set
 */
@Tag("e2e")
@Tag("integration")
@EnabledIfEnvironmentVariable(
        named = "DASHSCOPE_API_KEY",
        matches = ".+",
        disabledReason = "E2E tests require DASHSCOPE_API_KEY environment variable")
@DisplayName("ReAct E2E Tests")
class ReActE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);
    private ReActAgent agent;
    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        toolkit = E2ETestUtils.createTestToolkit();
        agent = E2ETestUtils.createReActAgentWithRealModel("TestReActAgent", toolkit);
        System.out.println("=== ReAct E2E Test Setup Complete ===");
    }

    @Test
    @DisplayName("Should complete simple ReAct flow with calculation")
    void testSimpleReActFlow() {
        System.out.println("\n=== Test: Simple ReAct Flow ===");

        // Arrange
        Msg input = TestUtils.createUserMessage("User", "What is 15 plus 27?");
        System.out.println("Sending: " + input);

        // Act
        List<Msg> responses = E2ETestUtils.waitForResponse(agent, input, TEST_TIMEOUT);

        // Assert
        assertNotNull(responses, "Response should not be null");
        assertTrue(responses.size() > 0, "Should receive at least one response");

        System.out.println("Received " + responses.size() + " response chunks");

        // Verify response contains meaningful content
        assertTrue(
                E2ETestUtils.hasMeaningfulContent(responses),
                "Response should contain meaningful content");

        // The answer might be in the response (42)
        boolean hasAnswer = E2ETestUtils.responsesContain(responses, "42");
        System.out.println("Response contains answer '42': " + hasAnswer);

        // At minimum, should have some response
        String firstResponseText = TestUtils.extractTextContent(responses.get(0));
        assertNotNull(firstResponseText, "First response should have text content");
    }

    @Test
    @DisplayName("Should handle multi-round conversation with context")
    void testMultiRoundConversation() {
        System.out.println("\n=== Test: Multi-Round Conversation ===");

        // Round 1: First question
        Msg question1 = TestUtils.createUserMessage("User", "What is 10 plus 5?");
        System.out.println("Round 1: " + question1);

        List<Msg> response1 = E2ETestUtils.waitForResponse(agent, question1, TEST_TIMEOUT);
        assertNotNull(response1, "First response should not be null");
        assertTrue(response1.size() > 0, "Should receive first response");

        int memoryAfterRound1 = agent.getMemory().getMessages().size();
        System.out.println("Memory after round 1: " + memoryAfterRound1 + " messages");

        // Round 2: Follow-up question
        Msg question2 = TestUtils.createUserMessage("User", "Now multiply that result by 2");
        System.out.println("Round 2: " + question2);

        List<Msg> response2 = E2ETestUtils.waitForResponse(agent, question2, TEST_TIMEOUT);
        assertNotNull(response2, "Second response should not be null");

        int memoryAfterRound2 = agent.getMemory().getMessages().size();
        System.out.println("Memory after round 2: " + memoryAfterRound2 + " messages");

        // Verify memory grew
        assertTrue(memoryAfterRound2 > memoryAfterRound1, "Memory should grow with more messages");

        // Round 3: Check if agent remembers first number
        Msg question3 =
                TestUtils.createUserMessage("User", "What was the first number I asked about?");
        System.out.println("Round 3: " + question3);

        List<Msg> response3 = E2ETestUtils.waitForResponse(agent, question3, TEST_TIMEOUT);
        assertNotNull(response3, "Third response should not be null");

        // Agent should be able to reference context
        System.out.println("Final memory: " + agent.getMemory().getMessages().size() + " messages");
    }

    @Test
    @DisplayName("Should handle complex calculation chain")
    void testComplexCalculationChain() {
        System.out.println("\n=== Test: Complex Calculation Chain ===");

        // Arrange - multi-step calculation
        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "First add 10 and 20, then multiply the result by 3, then calculate the"
                                + " factorial of 5");
        System.out.println("Sending: " + input);

        // Act
        List<Msg> responses = E2ETestUtils.waitForResponse(agent, input, TEST_TIMEOUT);

        // Assert
        assertNotNull(responses, "Response should not be null");
        assertTrue(responses.size() > 0, "Should receive responses");

        System.out.println("Received " + responses.size() + " response chunks");

        // Verify response has content
        assertTrue(
                E2ETestUtils.hasMeaningfulContent(responses),
                "Response should contain calculation results");

        // Expected results: 10+20=30, 30*3=90, 5!=120
        boolean hasResult =
                E2ETestUtils.responsesContain(responses, "90")
                        || E2ETestUtils.responsesContain(responses, "120");
        System.out.println("Response contains expected results: " + hasResult);
    }

    @Test
    @DisplayName("Should handle tool error gracefully")
    void testToolErrorHandling() {
        System.out.println("\n=== Test: Tool Error Handling ===");

        // Arrange - request factorial of negative number (should error)
        Msg input = TestUtils.createUserMessage("User", "Calculate the factorial of negative 5");
        System.out.println("Sending: " + input);

        // Act
        List<Msg> responses = E2ETestUtils.waitForResponse(agent, input, TEST_TIMEOUT);

        // Assert
        assertNotNull(responses, "Should receive responses even with tool error");
        assertTrue(responses.size() > 0, "Should have at least one response");

        System.out.println("Received " + responses.size() + " response chunks");

        // Agent should handle the error and provide explanation
        assertTrue(
                E2ETestUtils.hasMeaningfulContent(responses), "Should provide error explanation");

        // Response might mention error, negative, cannot, invalid, etc.
        boolean handlesError =
                E2ETestUtils.responsesContain(responses, "error")
                        || E2ETestUtils.responsesContain(responses, "negative")
                        || E2ETestUtils.responsesContain(responses, "cannot")
                        || E2ETestUtils.responsesContain(responses, "invalid");

        System.out.println("Response indicates error handling: " + handlesError);
    }

    @Test
    @DisplayName("Should handle mixed conversation with and without tools")
    void testMixedConversation() {
        System.out.println("\n=== Test: Mixed Conversation ===");

        // Round 1: Greeting (no tools needed)
        Msg greeting = TestUtils.createUserMessage("User", "Hello! How are you?");
        System.out.println("Round 1 (no tools): " + greeting);

        List<Msg> response1 = E2ETestUtils.waitForResponse(agent, greeting, TEST_TIMEOUT);
        assertNotNull(response1, "Should respond to greeting");
        assertTrue(
                E2ETestUtils.hasMeaningfulContent(response1),
                "Greeting response should have content");

        // Round 2: Calculation (tools needed)
        Msg calculation = TestUtils.createUserMessage("User", "Can you calculate 25 plus 17?");
        System.out.println("Round 2 (with tools): " + calculation);

        List<Msg> response2 = E2ETestUtils.waitForResponse(agent, calculation, TEST_TIMEOUT);
        assertNotNull(response2, "Should respond to calculation");
        assertTrue(
                E2ETestUtils.hasMeaningfulContent(response2),
                "Calculation response should have content");

        // Round 3: Thanks (no tools needed)
        Msg thanks = TestUtils.createUserMessage("User", "Thanks for your help!");
        System.out.println("Round 3 (no tools): " + thanks);

        List<Msg> response3 = E2ETestUtils.waitForResponse(agent, thanks, TEST_TIMEOUT);
        assertNotNull(response3, "Should respond to thanks");

        // Verify all interactions are in memory
        List<Msg> allMessages = agent.getMemory().getMessages();
        assertTrue(allMessages.size() >= 3, "Should have at least 3 user messages in memory");

        System.out.println("Final memory size: " + allMessages.size() + " messages");
    }
}
