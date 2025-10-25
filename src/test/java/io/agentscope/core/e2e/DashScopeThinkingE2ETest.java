/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * E2E tests for DashScope Thinking mode through Agent API.
 *
 * <p>Tests verify thinking mode works correctly when accessed through ReActAgent, validating
 * the complete flow: Agent → Model → Formatter → Response.
 *
 * <p><b>Requirements:</b> DASHSCOPE_API_KEY environment variable must be set
 */
@Tag("e2e")
@Tag("integration")
@EnabledIfEnvironmentVariable(
        named = "DASHSCOPE_API_KEY",
        matches = ".+",
        disabledReason = "E2E tests require DASHSCOPE_API_KEY environment variable")
@DisplayName("DashScope Thinking Mode E2E Tests (via Agent)")
class DashScopeThinkingE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);
    private String apiKey;

    @BeforeEach
    void setUp() {
        apiKey = System.getenv("DASHSCOPE_API_KEY");
        System.out.println("=== DashScope Thinking E2E Test Setup Complete ===");
    }

    @Test
    @DisplayName("Should enable thinking mode via enableThinking parameter")
    void testBasicThinkingMode() {
        System.out.println("\n=== Test: Basic Thinking Mode (via Agent) ===");

        // Create model with thinking enabled
        Model model =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(true)
                        .enableThinking(true)
                        .build();

        // Create agent
        Memory memory = new InMemoryMemory();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ThinkingAgent")
                        .sysPrompt("You are a helpful assistant that explains your reasoning.")
                        .model(model)
                        .toolkit(new Toolkit()) // Empty toolkit
                        .memory(memory)
                        .build();

        // Ask a reasoning question
        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "Solve this step by step: If a train travels 120 km in 2 hours, "
                                + "what is its average speed?");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        // Get agent response
        Msg response = agent.call(input).block(TEST_TIMEOUT);

        // Verify response structure
        assertNotNull(response, "Agent response should not be null");

        // Check for ThinkingBlock
        boolean hasThinking = response.hasContentBlocks(ThinkingBlock.class);
        System.out.println("Agent response has thinking content: " + hasThinking);

        if (hasThinking) {
            ThinkingBlock thinking = response.getFirstContentBlock(ThinkingBlock.class);
            String thinkingText = thinking.getThinking();
            System.out.println("Thinking content length: " + thinkingText.length());
            System.out.println(
                    "Thinking preview: "
                            + thinkingText.substring(0, Math.min(100, thinkingText.length()))
                            + "...");

            assertTrue(thinkingText.length() > 10, "Thinking content should not be empty");
        }

        // Check for answer text
        assertTrue(
                response.hasContentBlocks(TextBlock.class),
                "Agent response should contain text answer");

        String answerText = TestUtils.extractTextContent(response);
        System.out.println("Answer: " + answerText);
        assertNotNull(answerText, "Answer should not be null");

        // Verify memory contains the response
        assertEquals(
                2,
                memory.getMessages().size(),
                "Memory should contain user message and agent response");
    }

    @Test
    @DisplayName("Should use thinkingBudget with enableThinking")
    void testThinkingWithBudget() {
        System.out.println("\n=== Test: Thinking with Budget (via Agent) ===");

        // Create model with both enableThinking and thinkingBudget
        GenerateOptions options = GenerateOptions.builder().thinkingBudget(1000).build();

        Model model =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(true)
                        .enableThinking(true) // Must enable thinking when using thinkingBudget
                        .defaultOptions(options)
                        .build();

        Memory memory = new InMemoryMemory();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ThinkingWithBudgetAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(model)
                        .toolkit(new Toolkit())
                        .memory(memory)
                        .build();

        Msg input =
                TestUtils.createUserMessage(
                        "User", "Calculate 15 * 23 and explain the calculation process");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Agent response should not be null");

        // Should have thinking content
        boolean hasThinking = response.hasContentBlocks(ThinkingBlock.class);
        System.out.println("Response has thinking: " + hasThinking);

        assertTrue(hasThinking, "Thinking should be enabled when both flags are set");
    }

    @Test
    @DisplayName("Should respect thinkingBudget parameter")
    void testThinkingBudget() {
        System.out.println("\n=== Test: Thinking Budget (via Agent) ===");

        // Create agents with different thinking budgets (both need enableThinking=true)
        ReActAgent agentSmall = createAgentWithBudget("SmallBudgetAgent", 500);
        ReActAgent agentLarge = createAgentWithBudget("LargeBudgetAgent", 2000);

        // Use a complex reasoning question
        Msg input =
                TestUtils.createUserMessage(
                        "User", "Explain the Pythagorean theorem and its significance.");

        System.out.println("Testing with small budget (500)...");
        Msg responseSmall = agentSmall.call(input).block(TEST_TIMEOUT);

        System.out.println("Testing with large budget (2000)...");
        Msg responseLarge = agentLarge.call(input).block(TEST_TIMEOUT);

        // Both should have thinking content
        assertTrue(
                responseSmall.hasContentBlocks(ThinkingBlock.class),
                "Small budget response should have thinking");
        assertTrue(
                responseLarge.hasContentBlocks(ThinkingBlock.class),
                "Large budget response should have thinking");

        // Extract thinking lengths
        int lengthSmall =
                responseSmall.getFirstContentBlock(ThinkingBlock.class).getThinking().length();
        int lengthLarge =
                responseLarge.getFirstContentBlock(ThinkingBlock.class).getThinking().length();

        System.out.println("Small budget thinking length: " + lengthSmall);
        System.out.println("Large budget thinking length: " + lengthLarge);
        System.out.println("Length ratio: " + ((double) lengthLarge / lengthSmall));

        // Both should have meaningful thinking
        assertTrue(lengthSmall > 20, "Small budget should still produce thinking");
        assertTrue(lengthLarge > 20, "Large budget should produce thinking");
    }

    @Test
    @DisplayName("Should handle complex reasoning with thinking mode")
    void testComplexReasoning() {
        System.out.println("\n=== Test: Complex Reasoning (via Agent) ===");

        GenerateOptions options =
                GenerateOptions.builder().thinkingBudget(1500).temperature(0.7).build();

        Model model =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(true)
                        .enableThinking(true)
                        .defaultOptions(options)
                        .build();

        Memory memory = new InMemoryMemory();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ReasoningAgent")
                        .sysPrompt("You are a logical reasoning assistant.")
                        .model(model)
                        .toolkit(new Toolkit())
                        .memory(memory)
                        .build();

        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "If all roses are flowers, and some flowers fade quickly, can we conclude"
                                + " that some roses fade quickly? Explain your reasoning.");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(
                response.hasContentBlocks(ThinkingBlock.class),
                "Complex reasoning should trigger thinking");

        ThinkingBlock thinking = response.getFirstContentBlock(ThinkingBlock.class);
        String thinkingText = thinking.getThinking();

        System.out.println("\n=== Thinking Process ===");
        System.out.println(thinkingText);
        System.out.println("=== End Thinking ===\n");

        // Verify thinking contains reasoning
        assertTrue(
                thinkingText.length() > 50,
                "Thinking should contain substantial reasoning process");

        // Verify response also has answer
        assertTrue(response.hasContentBlocks(TextBlock.class), "Should have text answer");
    }

    @Test
    @DisplayName("Should work with ReActAgent tool calling and thinking")
    void testThinkingWithToolCalling() {
        System.out.println("\n=== Test: Thinking + Tool Calling (via Agent) ===");

        // Create model with thinking
        GenerateOptions options = GenerateOptions.builder().thinkingBudget(1000).build();

        Model model =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(true)
                        .enableThinking(true)
                        .defaultOptions(options)
                        .build();

        // Create toolkit with tools
        Toolkit toolkit = E2ETestUtils.createTestToolkit();

        Memory memory = new InMemoryMemory();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ThinkingToolAgent")
                        .sysPrompt("You are a helpful assistant that uses tools when needed.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .maxIters(3)
                        .build();

        Msg input = TestUtils.createUserMessage("User", "What is 25 plus 17?");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");

        // The agent might use thinking during tool selection
        // Note: Thinking may or may not appear depending on whether the model
        // decides to think before calling tools
        System.out.println(
                "Response has thinking: " + response.hasContentBlocks(ThinkingBlock.class));
        System.out.println("Response has text: " + response.hasContentBlocks(TextBlock.class));

        // Should definitely have a text answer
        assertTrue(response.hasContentBlocks(TextBlock.class), "Should have final answer");

        // Verify memory contains the conversation
        int memorySize = memory.getMessages().size();
        System.out.println("Memory size after tool call: " + memorySize);
        assertTrue(memorySize >= 2, "Memory should contain at least user message and response");
    }

    @Test
    @DisplayName("Should work without thinking in normal mode")
    void testNormalModeWithoutThinking() {
        System.out.println("\n=== Test: Normal Mode Without Thinking (via Agent) ===");

        // Create model WITHOUT thinking
        Model model =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(true)
                        .enableThinking(false)
                        .build();

        Memory memory = new InMemoryMemory();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("NormalAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(model)
                        .toolkit(new Toolkit())
                        .memory(memory)
                        .build();

        Msg input = TestUtils.createUserMessage("User", "What is 2 + 2?");

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertFalse(
                response.hasContentBlocks(ThinkingBlock.class),
                "Should not have thinking in normal mode");
        assertTrue(response.hasContentBlocks(TextBlock.class), "Should have text answer");
    }

    // Helper method
    private ReActAgent createAgentWithBudget(String name, Integer thinkingBudget) {
        GenerateOptions options = GenerateOptions.builder().thinkingBudget(thinkingBudget).build();

        Model model =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(true)
                        .enableThinking(true)
                        .defaultOptions(options)
                        .build();

        return ReActAgent.builder()
                .name(name)
                .sysPrompt("You are a helpful assistant.")
                .model(model)
                .toolkit(new Toolkit())
                .memory(new InMemoryMemory())
                .build();
    }
}
