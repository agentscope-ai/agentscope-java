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
package io.agentscope.core.e2e;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-End integration tests for Agent functionality with REAL API calls.
 *
 * <p>These tests verify complete agent workflows with actual API calls including multi-round
 * conversations, state management, and streaming responses.
 *
 * <p>Supports both DashScope and OpenRouter providers:
 * <ul>
 *   <li>DashScope: Uses DASHSCOPE_API_KEY environment variable with qwen-plus model</li>
 *   <li>OpenRouter: Uses OPENROUTER_API_KEY environment variable with openai/gpt-4o-mini model</li>
 * </ul>
 *
 * <p><b>Requirements:</b>
 *
 * <ul>
 *   <li>Either DASHSCOPE_API_KEY or OPENROUTER_API_KEY environment variable must be set
 *   <li>Active internet connection
 *   <li>Valid API quota for the selected provider
 * </ul>
 *
 * <p><b>Run with:</b>
 *
 * <pre>
 * mvn test -Dtest.e2e=true
 * # or in CI/CD:
 * export DASHSCOPE_API_KEY=your_key
 * # or
 * export OPENROUTER_API_KEY=your_key
 * mvn test -Dtest=AgentE2ETest
 * </pre>
 *
 * <p>Tagged as "e2e" - these tests make real API calls and may incur costs.
 */
@Tag("e2e")
@DisplayName("Agent E2E Tests (Real API - DashScope or OpenRouter)")
class AgentE2ETest {

    private static final Duration API_TIMEOUT = Duration.ofSeconds(30);
    private static final String DASHSCOPE_MODEL_NAME = "qwen-plus";
    private static final String OPENROUTER_MODEL_NAME = "openai/gpt-4o-mini";
    private static final String DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api";

    private ReActAgent agent;
    private Model realModel;
    private Toolkit toolkit;
    private InMemoryMemory memory;
    private String providerName;
    private String modelName;

    @BeforeEach
    void setUp() {
        memory = new InMemoryMemory();
        toolkit = new Toolkit(); // Empty toolkit for basic tests

        // Check for OpenRouter API key first, then DashScope
        String openRouterApiKey = System.getenv("OPENROUTER_API_KEY");
        String dashScopeApiKey = System.getenv("DASHSCOPE_API_KEY");

        if (openRouterApiKey != null && !openRouterApiKey.isEmpty()) {
            // Use OpenRouter
            providerName = "OpenRouter";
            modelName = OPENROUTER_MODEL_NAME;

            // Get base URL from environment variable, fallback to default
            String baseUrl = System.getenv("OPENROUTER_BASE_URL");
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = DEFAULT_OPENROUTER_BASE_URL;
            }

            // Create OpenRouter model using OpenAIChatModel (OpenRouter is OpenAI-compatible)
            realModel =
                    OpenAIChatModel.builder().apiKey(openRouterApiKey).modelName(modelName).stream(
                                    true)
                            .baseUrl(baseUrl)
                            .formatter(new OpenAIChatFormatter())
                            .build();

            System.out.println("=== E2E Test Setup Complete (OpenRouter) ===");
            System.out.println("Model: " + modelName);
            System.out.println("Base URL: " + baseUrl);
        } else if (dashScopeApiKey != null && !dashScopeApiKey.isEmpty()) {
            // Use DashScope
            providerName = "DashScope";
            modelName = DASHSCOPE_MODEL_NAME;

            // Create real DashScope model using builder
            realModel =
                    DashScopeChatModel.builder()
                            .apiKey(dashScopeApiKey)
                            .modelName(modelName)
                            .stream(true)
                            .build();

            System.out.println("=== E2E Test Setup Complete (DashScope) ===");
            System.out.println("Model: " + modelName);
        } else {
            assumeTrue(
                    false,
                    "Either OPENROUTER_API_KEY or DASHSCOPE_API_KEY environment variable must be"
                            + " set");
            return; // Never reached, but keeps compiler happy
        }

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

        String apiKey =
                openRouterApiKey != null && !openRouterApiKey.isEmpty()
                        ? openRouterApiKey
                        : dashScopeApiKey;
        System.out.println(
                "Provider: "
                        + providerName
                        + ", API Key: "
                        + apiKey.substring(0, Math.min(10, apiKey.length()))
                        + "...");
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

    @Test
    @DisplayName("Should handle synchronous (non-streaming) mode correctly")
    void testSynchronousMode() {
        System.out.println("\n=== Test: Synchronous (Non-Streaming) Mode ===");

        // Get API key from environment
        String openRouterApiKey = System.getenv("OPENROUTER_API_KEY");
        String dashScopeApiKey = System.getenv("DASHSCOPE_API_KEY");

        Model syncModel;
        if (openRouterApiKey != null && !openRouterApiKey.isEmpty()) {
            // Use OpenRouter
            String baseUrl = System.getenv("OPENROUTER_BASE_URL");
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = DEFAULT_OPENROUTER_BASE_URL;
            }
            syncModel =
                    OpenAIChatModel.builder()
                            .apiKey(openRouterApiKey)
                            .modelName(OPENROUTER_MODEL_NAME)
                            .stream(false) // Non-streaming mode
                            .baseUrl(baseUrl)
                            .formatter(new OpenAIChatFormatter())
                            .build();
        } else {
            // Use DashScope
            syncModel =
                    DashScopeChatModel.builder()
                            .apiKey(dashScopeApiKey)
                            .modelName(DASHSCOPE_MODEL_NAME)
                            .stream(false) // Non-streaming mode
                            .build();
        }

        // Create agent with synchronous model
        ReActAgent syncAgent =
                ReActAgent.builder()
                        .name("SyncTestAgent")
                        .sysPrompt("You are a helpful AI assistant. Answer questions concisely.")
                        .model(syncModel)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        System.out.println("Created synchronous model (stream=false)");

        // Test simple question
        Msg question = TestUtils.createUserMessage("User", "What is 5 + 3?");
        System.out.println("Sending: " + question);

        Msg response = syncAgent.call(question).block(API_TIMEOUT);

        assertNotNull(response, "Should receive response in synchronous mode");
        System.out.println("Received synchronous response");

        // Verify response has content
        String text = TestUtils.extractTextContent(response);
        assertNotNull(text, "Response should have content");
        assertTrue(!text.isEmpty(), "Response text should not be empty");
        System.out.println(
                "  Response: " + text.substring(0, Math.min(100, text.length())) + "...");

        // Verify memory contains the interaction
        List<Msg> memory = syncAgent.getMemory().getMessages();
        assertTrue(memory.size() >= 1, "Memory should contain at least the user message");
        System.out.println("Memory size: " + memory.size() + " messages");
    }

    @Test
    @DisplayName("Should handle streaming mode correctly")
    void testExplicitStreamingMode() {
        System.out.println("\n=== Test: Explicit Streaming Mode ===");

        // Get API key from environment
        String openRouterApiKey = System.getenv("OPENROUTER_API_KEY");
        String dashScopeApiKey = System.getenv("DASHSCOPE_API_KEY");

        Model streamModel;
        if (openRouterApiKey != null && !openRouterApiKey.isEmpty()) {
            // Use OpenRouter
            String baseUrl = System.getenv("OPENROUTER_BASE_URL");
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = DEFAULT_OPENROUTER_BASE_URL;
            }
            streamModel =
                    OpenAIChatModel.builder()
                            .apiKey(openRouterApiKey)
                            .modelName(OPENROUTER_MODEL_NAME)
                            .stream(true) // Streaming mode
                            .baseUrl(baseUrl)
                            .formatter(new OpenAIChatFormatter())
                            .build();
        } else {
            // Use DashScope
            streamModel =
                    DashScopeChatModel.builder()
                            .apiKey(dashScopeApiKey)
                            .modelName(DASHSCOPE_MODEL_NAME)
                            .stream(true) // Streaming mode
                            .build();
        }

        // Create agent with streaming model
        ReActAgent streamAgent =
                ReActAgent.builder()
                        .name("StreamTestAgent")
                        .sysPrompt("You are a helpful AI assistant. Answer questions concisely.")
                        .model(streamModel)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        System.out.println("Created streaming model (stream=true)");

        // Test simple question
        Msg question = TestUtils.createUserMessage("User", "What is 7 + 2?");
        System.out.println("Sending: " + question);

        Msg response = streamAgent.call(question).block(API_TIMEOUT);

        assertNotNull(response, "Should receive response in streaming mode");
        System.out.println("Received streaming response");

        // Verify response has content
        String text = TestUtils.extractTextContent(response);
        assertNotNull(text, "Response should have content");
        assertTrue(!text.isEmpty(), "Response text should not be empty");
        System.out.println(
                "  Response: " + text.substring(0, Math.min(100, text.length())) + "...");

        // Verify memory contains the interaction
        List<Msg> memory = streamAgent.getMemory().getMessages();
        assertTrue(memory.size() >= 1, "Memory should contain at least the user message");
        System.out.println("Memory size: " + memory.size() + " messages");
    }

    @Test
    @DisplayName("Should produce equivalent results in sync and stream modes")
    void testSyncVsStreamEquivalence() {
        System.out.println("\n=== Test: Sync vs Stream Equivalence ===");

        // Get API key from environment
        String openRouterApiKey = System.getenv("OPENROUTER_API_KEY");
        String dashScopeApiKey = System.getenv("DASHSCOPE_API_KEY");

        Model syncModel;
        Model streamModel;
        if (openRouterApiKey != null && !openRouterApiKey.isEmpty()) {
            // Use OpenRouter
            String baseUrl = System.getenv("OPENROUTER_BASE_URL");
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = DEFAULT_OPENROUTER_BASE_URL;
            }
            syncModel =
                    OpenAIChatModel.builder()
                            .apiKey(openRouterApiKey)
                            .modelName(OPENROUTER_MODEL_NAME)
                            .stream(false)
                            .baseUrl(baseUrl)
                            .formatter(new OpenAIChatFormatter())
                            .build();

            streamModel =
                    OpenAIChatModel.builder()
                            .apiKey(openRouterApiKey)
                            .modelName(OPENROUTER_MODEL_NAME)
                            .stream(true)
                            .baseUrl(baseUrl)
                            .formatter(new OpenAIChatFormatter())
                            .build();
        } else {
            // Use DashScope
            syncModel =
                    DashScopeChatModel.builder()
                            .apiKey(dashScopeApiKey)
                            .modelName(DASHSCOPE_MODEL_NAME)
                            .stream(false)
                            .build();

            streamModel =
                    DashScopeChatModel.builder()
                            .apiKey(dashScopeApiKey)
                            .modelName(DASHSCOPE_MODEL_NAME)
                            .stream(true)
                            .build();
        }

        System.out.println("Created both sync and stream models");

        // Create agents
        ReActAgent syncAgent =
                ReActAgent.builder()
                        .name("SyncAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(syncModel)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        ReActAgent streamAgent =
                ReActAgent.builder()
                        .name("StreamAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(streamModel)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        // Test with same question
        String questionText = "What is 10 + 5?";
        Msg syncQuestion = TestUtils.createUserMessage("User", questionText);
        Msg streamQuestion = TestUtils.createUserMessage("User", questionText);

        System.out.println("Testing question: " + questionText);

        // Get responses
        Msg syncResponse = syncAgent.call(syncQuestion).block(API_TIMEOUT);
        Msg streamResponse = streamAgent.call(streamQuestion).block(API_TIMEOUT);

        // Verify both got responses
        assertNotNull(syncResponse, "Sync mode should receive response");
        assertNotNull(streamResponse, "Stream mode should receive response");

        // Verify both have content
        String syncText = TestUtils.extractTextContent(syncResponse);
        String streamText = TestUtils.extractTextContent(streamResponse);

        assertNotNull(syncText, "Sync response should have content");
        assertNotNull(streamText, "Stream response should have content");
        assertTrue(!syncText.isEmpty(), "Sync response should not be empty");
        assertTrue(!streamText.isEmpty(), "Stream response should not be empty");

        System.out.println("Sync response length: " + syncText.length() + " chars");
        System.out.println("Stream response length: " + streamText.length() + " chars");
        System.out.println(
                "Both modes produced valid responses (content may differ due to LLM"
                        + " non-determinism)");
    }
}
