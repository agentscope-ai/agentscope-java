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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * E2E tests for OpenAI-compatible mode using DashScope's compatible endpoint.
 *
 * <p>Tests verify OpenAI SDK integration works correctly with DashScope's OpenAI-compatible API
 * endpoint, validating the complete flow: Agent → OpenAIChatModel → OpenAI SDK → DashScope API.
 *
 * <p><b>Requirements:</b> DASHSCOPE_API_KEY environment variable must be set
 *
 * <p><b>Base URL:</b> https://dashscope.aliyuncs.com/compatible-mode/v1
 */
@Tag("e2e")
@Tag("integration")
@EnabledIfEnvironmentVariable(
        named = "DASHSCOPE_API_KEY",
        matches = ".+",
        disabledReason = "E2E tests require DASHSCOPE_API_KEY environment variable")
@DisplayName("OpenAI Compatible Mode E2E Tests (DashScope)")
class OpenAICompatibleE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String COMPATIBLE_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String apiKey;

    @BeforeEach
    void setUp() {
        apiKey = System.getenv("DASHSCOPE_API_KEY");
        System.out.println("=== OpenAI Compatible Mode E2E Test Setup Complete ===");
    }

    @Test
    @DisplayName("Should work with streaming mode")
    void testStreamingMode() {
        System.out.println("\n=== Test: Streaming Mode (via OpenAI SDK) ===");

        // Create OpenAI-compatible model pointing to DashScope
        Model model =
                OpenAIChatModel.builder()
                        .baseUrl(COMPATIBLE_BASE_URL)
                        .apiKey(apiKey)
                        .modelName("qwen-plus")
                        .stream(true)
                        .build();

        Memory memory = new InMemoryMemory();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("StreamingAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(model)
                        .toolkit(new Toolkit())
                        .memory(memory)
                        .build();

        Msg input =
                TestUtils.createUserMessage(
                        "User", "Calculate the sum of 123 and 456. Just give me the result.");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(response.hasContentBlocks(TextBlock.class), "Should have text content");

        String answerText = TestUtils.extractTextContent(response);
        System.out.println("Answer: " + answerText);
        assertNotNull(answerText, "Answer should not be null");
        assertTrue(answerText.length() > 0, "Answer should not be empty");
    }

    @Test
    @DisplayName("Should work with non-streaming mode")
    void testNonStreamingMode() {
        System.out.println("\n=== Test: Non-Streaming Mode (via OpenAI SDK) ===");

        Model model =
                OpenAIChatModel.builder()
                        .baseUrl(COMPATIBLE_BASE_URL)
                        .apiKey(apiKey)
                        .modelName("qwen-plus")
                        .stream(false)
                        .build();

        Memory memory = new InMemoryMemory();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("NonStreamingAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(model)
                        .toolkit(new Toolkit())
                        .memory(memory)
                        .build();

        Msg input = TestUtils.createUserMessage("User", "What is the capital of France?");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(response.hasContentBlocks(TextBlock.class), "Should have text content");

        String answerText = TestUtils.extractTextContent(response);
        System.out.println("Answer: " + answerText);
        assertTrue(answerText.toLowerCase().contains("paris"), "Should mention Paris");
    }

    @Test
    @DisplayName("Should work with tool calling in streaming mode")
    void testToolCallingStreaming() {
        System.out.println("\n=== Test: Tool Calling Streaming (via OpenAI SDK) ===");

        Model model =
                OpenAIChatModel.builder()
                        .baseUrl(COMPATIBLE_BASE_URL)
                        .apiKey(apiKey)
                        .modelName("qwen-plus")
                        .stream(true)
                        .build();

        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        Memory memory = new InMemoryMemory();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ToolCallingAgent")
                        .sysPrompt("You are a helpful assistant that uses tools when needed.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .maxIters(3)
                        .build();

        Msg input = TestUtils.createUserMessage("User", "What is 15 multiplied by 8?");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(response.hasContentBlocks(TextBlock.class), "Should have final answer");

        String answerText = TestUtils.extractTextContent(response);
        System.out.println("Answer: " + answerText);

        // Verify the answer contains the correct result
        assertTrue(answerText.contains("120"), "Answer should contain the correct result 120");

        // CRITICAL: Verify tool was actually called and executed successfully
        boolean foundToolUse = false;
        boolean foundToolResult = false;
        boolean toolExecutedSuccessfully = false;

        for (Msg msg : memory.getMessages()) {
            // Check for ToolUseBlock with correct parameters
            if (msg.getRole() == MsgRole.ASSISTANT && msg.hasContentBlocks(ToolUseBlock.class)) {
                ToolUseBlock toolUse = msg.getFirstContentBlock(ToolUseBlock.class);
                if ("multiply".equals(toolUse.getName())) {
                    foundToolUse = true;
                    // Verify parameters are correct
                    assertTrue(
                            toolUse.getInput().containsKey("a"),
                            "Tool call should have parameter 'a'");
                    assertTrue(
                            toolUse.getInput().containsKey("b"),
                            "Tool call should have parameter 'b'");
                    System.out.println(
                            "Tool call found: multiply("
                                    + toolUse.getInput().get("a")
                                    + ", "
                                    + toolUse.getInput().get("b")
                                    + ")");
                }
            }

            // Check for ToolResultBlock with successful execution
            if (msg.getRole() == MsgRole.TOOL && msg.hasContentBlocks(ToolResultBlock.class)) {
                ToolResultBlock toolResult = msg.getFirstContentBlock(ToolResultBlock.class);
                if ("multiply".equals(toolResult.getName())) {
                    foundToolResult = true;
                    // Extract result from ToolResultBlock output
                    List<ContentBlock> outputs = toolResult.getOutput();
                    if (!outputs.isEmpty() && outputs.get(0) instanceof TextBlock tb) {
                        String resultText = tb.getText().trim();
                        System.out.println("Tool result: " + resultText);
                        // Check that result is "120" and not an error message
                        if ("120".equals(resultText)) {
                            toolExecutedSuccessfully = true;
                        }
                    }
                }
            }
        }

        assertTrue(foundToolUse, "Memory should contain a ToolUseBlock for multiply");
        assertTrue(foundToolResult, "Memory should contain a ToolResultBlock for multiply");
        assertTrue(
                toolExecutedSuccessfully,
                "Tool execution should succeed with result 120 (not an error)");

        // Verify memory structure: user message + tool use + tool result + final answer
        assertTrue(
                memory.getMessages().size() >= 4,
                "Memory should contain at least 4 messages (user, tool use, tool result, final"
                        + " answer)");
    }

    @Test
    @DisplayName("Should work with tool calling in non-streaming mode")
    void testToolCallingNonStreaming() {
        System.out.println("\n=== Test: Tool Calling Non-Streaming (via OpenAI SDK) ===");

        Model model =
                OpenAIChatModel.builder()
                        .baseUrl(COMPATIBLE_BASE_URL)
                        .apiKey(apiKey)
                        .modelName("qwen-plus")
                        .stream(false)
                        .build();

        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        Memory memory = new InMemoryMemory();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ToolCallingAgent")
                        .sysPrompt("You are a helpful assistant that uses tools when needed.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .maxIters(3)
                        .build();

        Msg input = TestUtils.createUserMessage("User", "What is 12 plus 34?");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(response.hasContentBlocks(TextBlock.class), "Should have final answer");

        String answerText = TestUtils.extractTextContent(response);
        System.out.println("Answer: " + answerText);

        // Verify the answer contains the correct result
        assertTrue(answerText.contains("46"), "Answer should contain the correct result 46");

        // CRITICAL: Verify tool was actually called and executed successfully
        boolean foundToolUse = false;
        boolean foundToolResult = false;
        boolean toolExecutedSuccessfully = false;

        for (Msg msg : memory.getMessages()) {
            if (msg.getRole() == MsgRole.ASSISTANT && msg.hasContentBlocks(ToolUseBlock.class)) {
                ToolUseBlock toolUse = msg.getFirstContentBlock(ToolUseBlock.class);
                if ("add".equals(toolUse.getName())) {
                    foundToolUse = true;
                    assertTrue(
                            toolUse.getInput().containsKey("a"),
                            "Tool call should have parameter 'a'");
                    assertTrue(
                            toolUse.getInput().containsKey("b"),
                            "Tool call should have parameter 'b'");
                    System.out.println(
                            "Tool call found: add("
                                    + toolUse.getInput().get("a")
                                    + ", "
                                    + toolUse.getInput().get("b")
                                    + ")");
                }
            }

            if (msg.getRole() == MsgRole.TOOL && msg.hasContentBlocks(ToolResultBlock.class)) {
                ToolResultBlock toolResult = msg.getFirstContentBlock(ToolResultBlock.class);
                if ("add".equals(toolResult.getName())) {
                    foundToolResult = true;
                    // Extract result from ToolResultBlock output
                    List<ContentBlock> outputs = toolResult.getOutput();
                    if (!outputs.isEmpty() && outputs.get(0) instanceof TextBlock tb) {
                        String resultText = tb.getText().trim();
                        System.out.println("Tool result: " + resultText);
                        if ("46".equals(resultText)) {
                            toolExecutedSuccessfully = true;
                        }
                    }
                }
            }
        }

        assertTrue(foundToolUse, "Memory should contain a ToolUseBlock for add");
        assertTrue(foundToolResult, "Memory should contain a ToolResultBlock for add");
        assertTrue(
                toolExecutedSuccessfully,
                "Tool execution should succeed with result 46 (not an error)");

        assertTrue(
                memory.getMessages().size() >= 4,
                "Memory should contain at least 4 messages (user, tool use, tool result, final"
                        + " answer)");
    }

    @Test
    @DisplayName("Should handle multi-turn conversation")
    void testMultiTurnConversation() {
        System.out.println("\n=== Test: Multi-Turn Conversation (via OpenAI SDK) ===");

        Model model =
                OpenAIChatModel.builder()
                        .baseUrl(COMPATIBLE_BASE_URL)
                        .apiKey(apiKey)
                        .modelName("qwen-plus")
                        .stream(true)
                        .build();

        Memory memory = new InMemoryMemory();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ConversationAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(model)
                        .toolkit(new Toolkit())
                        .memory(memory)
                        .build();

        // Turn 1
        Msg input1 = TestUtils.createUserMessage("User", "My name is Alice.");
        System.out.println("Turn 1: " + TestUtils.extractTextContent(input1));
        Msg response1 = agent.call(input1).block(TEST_TIMEOUT);
        System.out.println("Response 1: " + TestUtils.extractTextContent(response1));
        assertNotNull(response1);

        // Turn 2
        Msg input2 = TestUtils.createUserMessage("User", "What is my name?");
        System.out.println("Turn 2: " + TestUtils.extractTextContent(input2));
        Msg response2 = agent.call(input2).block(TEST_TIMEOUT);
        System.out.println("Response 2: " + TestUtils.extractTextContent(response2));

        assertNotNull(response2);
        String answer2 = TestUtils.extractTextContent(response2);
        assertTrue(
                answer2.toLowerCase().contains("alice"),
                "Should remember the name from previous turn");

        // Verify memory
        assertTrue(
                memory.getMessages().size() >= 4,
                "Memory should contain at least 4 messages (2 turns)");
    }

    @Test
    @DisplayName("Should handle complex reasoning tasks")
    void testComplexReasoning() {
        System.out.println("\n=== Test: Complex Reasoning (via OpenAI SDK) ===");

        Model model =
                OpenAIChatModel.builder()
                        .baseUrl(COMPATIBLE_BASE_URL)
                        .apiKey(apiKey)
                        .modelName("qwen-plus")
                        .stream(true)
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
                        "If it takes 5 machines 5 minutes to make 5 widgets, "
                                + "how long would it take 100 machines to make 100 widgets?");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(response.hasContentBlocks(TextBlock.class), "Should have text answer");

        String answerText = TestUtils.extractTextContent(response);
        System.out.println("\n=== Reasoning Answer ===");
        System.out.println(answerText);
        System.out.println("=== End Answer ===\n");

        assertTrue(answerText.length() > 20, "Should provide detailed reasoning");
    }

    @Test
    @DisplayName("Should work with qwen-turbo model")
    void testQwenTurboModel() {
        System.out.println("\n=== Test: qwen-turbo Model (via OpenAI SDK) ===");

        Model model =
                OpenAIChatModel.builder()
                        .baseUrl(COMPATIBLE_BASE_URL)
                        .apiKey(apiKey)
                        .modelName("qwen-turbo")
                        .stream(true)
                        .build();

        Memory memory = new InMemoryMemory();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TurboAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(model)
                        .toolkit(new Toolkit())
                        .memory(memory)
                        .build();

        Msg input = TestUtils.createUserMessage("User", "Say hello in 3 different languages.");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(response.hasContentBlocks(TextBlock.class), "Should have text content");

        String answerText = TestUtils.extractTextContent(response);
        System.out.println("Answer: " + answerText);
        assertTrue(answerText.length() > 10, "Should provide greetings");
    }
}
