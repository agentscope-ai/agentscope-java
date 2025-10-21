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
package io.agentscope.core.agent.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * End-to-End integration tests for Agent functionality with REAL DashScope API calls.
 *
 * <p>These tests verify complete agent workflows with actual API calls including multi-round
 * conversations, state management, and streaming responses.
 *
 * <p><b>Requirements:</b>
 *
 * <ul>
 *   <li>DASHSCOPE_API_KEY environment variable must be set
 *   <li>Active internet connection
 *   <li>Valid DashScope API quota
 * </ul>
 *
 * <p><b>Run with:</b>
 *
 * <pre>
 * mvn test -Dtest.e2e=true
 * # or in CI/CD:
 * export DASHSCOPE_API_KEY=your_key
 * mvn test -Dtest=AgentE2ETest
 * </pre>
 *
 * <p>Tagged as "e2e" - these tests make real API calls and may incur costs.
 */
@Tag("e2e")
@DisplayName("Agent E2E Tests (Real DashScope API)")
@EnabledIfEnvironmentVariable(
        named = "DASHSCOPE_API_KEY",
        matches = ".+",
        disabledReason = "Requires DASHSCOPE_API_KEY environment variable")
class AgentE2ETest {

    private static final Duration API_TIMEOUT = Duration.ofSeconds(30);
    private static final String MODEL_NAME = "qwen-plus";

    private ReActAgent agent;
    private DashScopeChatModel realModel;
    private Toolkit toolkit;
    private InMemoryMemory memory;

    @BeforeEach
    void setUp() {
        // Get API key from environment
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isEmpty(), "DASHSCOPE_API_KEY must be set");

        memory = new InMemoryMemory();
        toolkit = new Toolkit(); // Empty toolkit for basic tests

        // Create real DashScope model using builder
        realModel =
                DashScopeChatModel.builder().apiKey(apiKey).modelName(MODEL_NAME).stream(true)
                        .build();

        agent =
                ReActAgent.builder()
                        .name("E2ETestAgent")
                        .sysPrompt(
                                "You are a helpful AI assistant. Answer questions clearly and"
                                        + " concisely.")
                        .model(realModel)
                        .toolkit(toolkit)
                        .memory(memory)
                        .build();

        System.out.println("=== E2E Test Setup Complete ===");
        System.out.println("Model: " + MODEL_NAME);
        System.out.println(
                "API Key: " + apiKey.substring(0, Math.min(10, apiKey.length())) + "...");
    }

    @Test
    @DisplayName("Should complete full conversation flow with real API calls")
    void testCompleteConversationFlow() {
        System.out.println("\n=== Test: Complete Conversation Flow ===");

        // Round 1: Simple arithmetic question
        Msg question1 = TestUtils.createUserMessage("User", "What is 2+2?");
        System.out.println("Sending: " + question1);

        Msg response1 = agent.call(question1).block(API_TIMEOUT);

        assertNotNull(response1, "Should receive response from API");

        System.out.println("Response 1: Received response");

        // Verify memory contains the interaction
        List<Msg> memoryAfterRound1 = agent.getMemory().getMessages();
        assertTrue(
                memoryAfterRound1.size() >= 1, "Memory should contain at least the user message");

        // Round 2: Different topic
        Msg question2 = TestUtils.createUserMessage("User", "What is the capital of France?");
        System.out.println("Sending: " + question2);

        Msg response2 = agent.call(question2).block(API_TIMEOUT);

        assertNotNull(response2, "Should receive second response");

        // Verify memory contains both rounds
        List<Msg> memoryAfterRound2 = agent.getMemory().getMessages();
        assertTrue(
                memoryAfterRound2.size() > memoryAfterRound1.size(),
                "Memory should grow with more messages");

        System.out.println("Memory after 2 rounds: " + memoryAfterRound2.size() + " messages");

        // Verify response has meaningful content
        String text = TestUtils.extractTextContent(response2);
        assertTrue(text != null && text.length() > 5, "Response should contain meaningful content");
    }

    @Test
    @DisplayName("Should handle multi-round interaction with context preservation")
    void testMultiRoundInteraction() {
        System.out.println("\n=== Test: Multi-Round Interaction ===");

        int rounds = 3; // Limited for API quota

        for (int i = 0; i < rounds; i++) {
            Msg msg =
                    TestUtils.createUserMessage(
                            "User", "Tell me a fact about the number " + (i + 1));
            System.out.println("Round " + (i + 1) + ": " + msg);

            agent.call(msg).block(API_TIMEOUT);

            int memorySize = agent.getMemory().getMessages().size();
            System.out.println("  Memory size after round: " + memorySize);
        }

        // Verify all rounds are in memory
        List<Msg> allMessages = agent.getMemory().getMessages();
        assertTrue(
                allMessages.size() >= rounds, "Should have at least " + rounds + " user messages");

        System.out.println("Final memory size: " + allMessages.size() + " messages");
    }

    @Test
    @DisplayName("Should handle streaming responses from real API")
    void testStreamingResponse() {
        System.out.println("\n=== Test: Streaming Response ===");

        Msg question =
                TestUtils.createUserMessage(
                        "User", "Write a short poem about spring (max 2 lines)");
        System.out.println("Sending: " + question);

        // Get streaming response
        Msg streamedResponse = agent.call(question).block(API_TIMEOUT);

        assertNotNull(streamedResponse, "Should receive streamed response");
        System.out.println("Received streamed response");

        // Verify response has content
        String text = TestUtils.extractTextContent(streamedResponse);
        assertNotNull(text, "Response should have content");
        assertTrue(!text.isEmpty(), "Response text should not be empty");
        System.out.println("  Response: " + text.substring(0, Math.min(50, text.length())) + "...");
    }

    @Test
    @DisplayName("Should preserve conversation context across multiple interactions")
    void testConversationContext() {
        System.out.println("\n=== Test: Conversation Context ===");

        // First interaction: Set context
        Msg context = TestUtils.createUserMessage("User", "My favorite color is blue");
        System.out.println("Setting context: " + context);

        agent.call(context).block(API_TIMEOUT);

        int initialMemorySize = agent.getMemory().getMessages().size();
        System.out.println("Memory after context: " + initialMemorySize);

        // Second interaction: Add more context
        Msg moreContext = TestUtils.createUserMessage("User", "I also like programming");
        System.out.println("Adding context: " + moreContext);

        agent.call(moreContext).block(API_TIMEOUT);

        // Verify state is preserved
        List<Msg> allMessages = agent.getMemory().getMessages();
        assertTrue(allMessages.size() > initialMemorySize, "Memory should grow");

        System.out.println("Final memory: " + allMessages.size() + " messages");

        // Check that context is in memory
        boolean hasBlue =
                allMessages.stream()
                        .anyMatch(
                                m -> {
                                    String text = TestUtils.extractTextContent(m);
                                    return text != null && text.toLowerCase().contains("blue");
                                });
        boolean hasProgramming =
                allMessages.stream()
                        .anyMatch(
                                m -> {
                                    String text = TestUtils.extractTextContent(m);
                                    return text != null
                                            && text.toLowerCase().contains("programming");
                                });

        assertTrue(hasBlue || hasProgramming, "Historical context should be preserved");
    }

    @Test
    @DisplayName("Should handle simple questions correctly")
    void testSimpleQuestions() {
        System.out.println("\n=== Test: Simple Questions ===");

        String[] questions = {
            "What is the largest planet in our solar system?",
            "Who wrote Romeo and Juliet?",
            "What is the boiling point of water in Celsius?"
        };

        for (String questionText : questions) {
            Msg question = TestUtils.createUserMessage("User", questionText);
            System.out.println("\nQuestion: " + questionText);

            Msg response = agent.call(question).block(API_TIMEOUT);

            assertNotNull(response, "Should receive response for: " + questionText);

            // Print response
            String text = TestUtils.extractTextContent(response);
            if (text != null) {
                System.out.println(
                        "Answer: " + text.substring(0, Math.min(100, text.length())) + "...");
            }
        }

        System.out.println("\nAll questions answered successfully");
        System.out.println("Final memory size: " + agent.getMemory().getMessages().size());
    }
}
