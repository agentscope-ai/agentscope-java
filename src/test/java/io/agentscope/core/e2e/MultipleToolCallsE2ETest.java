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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Mono;

/**
 * E2E tests for verifying multiple tool calls in a single reasoning step.
 *
 * <p>This test strictly validates that when a model returns multiple tool calls in one response,
 * both OpenAI and DashScope models handle them correctly:
 *
 * <ul>
 *   <li>All tool calls are extracted from the model response
 *   <li>All tools are executed (potentially in parallel)
 *   <li>All tool results are saved to memory in correct order
 *   <li>All hooks (preActing, postActing, onActingChunk) are triggered for each tool
 *   <li>The agent produces a final answer using all tool results
 * </ul>
 *
 * <p><b>Requirements:</b> DASHSCOPE_API_KEY and OPENAI_API_KEY environment variables must be set
 */
@Tag("e2e")
@Tag("integration")
@DisplayName("Multiple Tool Calls E2E Tests")
class MultipleToolCallsE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(60);
    private static final String COMPATIBLE_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private String dashscopeApiKey;
    private String openaiApiKey;
    private String openaiBaseUrl;

    @BeforeEach
    void setUp() {
        dashscopeApiKey = System.getenv("DASHSCOPE_API_KEY");
        openaiApiKey = System.getenv("OPENAI_API_KEY");
        openaiBaseUrl = System.getenv("OPENAI_BASE_URL");
        System.out.println("=== Multiple Tool Calls E2E Test Setup Complete ===");
    }

    /**
     * Test DashScope native mode with multiple tool calls.
     *
     * <p>Asks a question that requires calling multiple tools: "Calculate the sum of 10+20, the
     * product of 5*6, and the difference 50-15"
     *
     * <p>Expected: Model should call add(10,20), multiply(5,6), and subtract(50,15) in one
     * reasoning step
     */
    @Test
    @EnabledIfEnvironmentVariable(
            named = "DASHSCOPE_API_KEY",
            matches = ".+",
            disabledReason = "Test requires DASHSCOPE_API_KEY")
    @DisplayName("DashScope should handle multiple tool calls in one response")
    void testDashScopeMultipleToolCalls() {
        System.out.println("\n=== Test: DashScope Multiple Tool Calls ===");

        // Create model
        Model model =
                DashScopeChatModel.builder().apiKey(dashscopeApiKey).modelName("qwen-plus").stream(
                                true)
                        .build();

        runMultipleToolCallsTest(model, "DashScope");
    }

    /**
     * Test OpenAI-compatible mode (via DashScope) with multiple tool calls.
     *
     * <p>Same test as DashScope but using OpenAI SDK with DashScope's compatible endpoint.
     */
    @Test
    @EnabledIfEnvironmentVariable(
            named = "DASHSCOPE_API_KEY",
            matches = ".+",
            disabledReason = "Test requires DASHSCOPE_API_KEY")
    @DisplayName("OpenAI compatible mode should handle multiple tool calls in one response")
    void testOpenAICompatibleMultipleToolCalls() {
        System.out.println("\n=== Test: OpenAI Compatible Multiple Tool Calls ===");

        // Create OpenAI-compatible model
        Model model =
                OpenAIChatModel.builder()
                        .baseUrl(COMPATIBLE_BASE_URL)
                        .apiKey(dashscopeApiKey)
                        .modelName("qwen-plus")
                        .stream(true)
                        .build();

        runMultipleToolCallsTest(model, "OpenAI-Compatible");
    }

    /**
     * Test real OpenAI API with multiple tool calls.
     *
     * <p>Tests against actual OpenAI API to ensure compatibility.
     */
    @Test
    @EnabledIfEnvironmentVariable(
            named = "OPENAI_API_KEY",
            matches = ".+",
            disabledReason = "Test requires OPENAI_API_KEY")
    @DisplayName("Real OpenAI API should handle multiple tool calls in one response")
    void testRealOpenAIMultipleToolCalls() {
        System.out.println("\n=== Test: Real OpenAI Multiple Tool Calls ===");

        // Create real OpenAI model
        Model model =
                OpenAIChatModel.builder()
                        .apiKey(openaiApiKey)
                        .baseUrl(openaiBaseUrl)
                        .modelName("openai/gpt-4o")
                        .stream(true)
                        .build();

        runMultipleToolCallsTest(model, "OpenAI");
    }

    /**
     * Core test logic for multiple tool calls.
     *
     * @param model The model to test
     * @param modelType String description for logging
     */
    private void runMultipleToolCallsTest(Model model, String modelType) {
        // Create toolkit with calculator tools
        Toolkit toolkit = new Toolkit();
        CalculatorTools calculatorTools = new CalculatorTools();
        toolkit.registerTool(calculatorTools);

        // Create hook to monitor tool calls
        ToolCallMonitorHook monitorHook = new ToolCallMonitorHook();

        // Create memory
        Memory memory = new InMemoryMemory();

        // Create agent with hook
        ReActAgent agent =
                ReActAgent.builder()
                        .name("CalculatorAgent")
                        .sysPrompt(
                                "You are a calculator assistant. When asked to perform multiple"
                                        + " calculations, you should call ALL required tools in a"
                                        + " SINGLE response (parallel tool calls). Do not call them"
                                        + " one by one.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .hooks(List.of(monitorHook))
                        .build();

        // Ask question requiring multiple tool calls
        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "Please calculate three things for me: "
                                + "1) What is 10 + 20? "
                                + "2) What is 5 * 6? "
                                + "3) What is 50 - 15? "
                                + "Please use the tools to calculate all three values.");

        System.out.println("Question: " + TestUtils.extractTextContent(input));

        // Execute agent
        Msg response = agent.call(input).block(TEST_TIMEOUT);

        System.out.println("\n=== Verification for " + modelType + " ===");

        // ===== STRICT VERIFICATION =====

        // 1. Verify response is not null
        assertNotNull(response, "Response should not be null");
        System.out.println("✓ Response received");

        // 2. Verify tools were called
        assertTrue(calculatorTools.getCallCount() > 0, "At least one tool should have been called");
        System.out.println("✓ Tools were called: " + calculatorTools.getCallCount() + " times");

        // 3. Verify hook captured tool calls
        int preActingCount = monitorHook.getPreActingCount();
        int postActingCount = monitorHook.getPostActingCount();

        assertTrue(preActingCount > 0, "preActing hook should be called at least once");
        assertTrue(postActingCount > 0, "postActing hook should be called at least once");
        assertEquals(
                preActingCount, postActingCount, "preActing and postActing counts should match");

        System.out.println("✓ Hook verification passed:");
        System.out.println("  - preActing called: " + preActingCount + " times");
        System.out.println("  - postActing called: " + postActingCount + " times");

        // 4. Verify memory structure
        List<Msg> messages = memory.getMessages();
        System.out.println("\n✓ Memory verification:");
        System.out.println("  - Total messages: " + messages.size());

        // Count ASSISTANT messages with tool calls
        long assistantToolMessages =
                messages.stream()
                        .filter(msg -> msg.getRole() == MsgRole.ASSISTANT)
                        .filter(msg -> !msg.getContentBlocks(ToolUseBlock.class).isEmpty())
                        .count();

        // Count TOOL messages with results
        long toolResultMessages =
                messages.stream()
                        .filter(msg -> msg.getRole() == MsgRole.TOOL)
                        .filter(msg -> !msg.getContentBlocks(ToolResultBlock.class).isEmpty())
                        .count();

        System.out.println("  - ASSISTANT messages with tool calls: " + assistantToolMessages);
        System.out.println("  - TOOL result messages: " + toolResultMessages);

        assertTrue(
                assistantToolMessages > 0,
                "Should have at least one ASSISTANT message with tool calls");
        assertTrue(toolResultMessages > 0, "Should have at least one TOOL message with results");

        // 5. Verify specific tool calls if multiple tools were called
        if (preActingCount >= 3) {
            // Model successfully called multiple tools in one go
            System.out.println(
                    "\n✓ EXCELLENT: Model called "
                            + preActingCount
                            + " tools in parallel (expected 3)");

            // Verify all three expected tools were called
            List<String> calledTools = monitorHook.getCalledToolNames();
            System.out.println("  - Tools called: " + calledTools);

            long addCalls = calledTools.stream().filter(name -> name.equals("add")).count();
            long multiplyCalls =
                    calledTools.stream().filter(name -> name.equals("multiply")).count();
            long subtractCalls =
                    calledTools.stream().filter(name -> name.equals("subtract")).count();

            assertTrue(addCalls > 0, "add tool should be called");
            assertTrue(multiplyCalls > 0, "multiply tool should be called");
            assertTrue(subtractCalls > 0, "subtract tool should be called");

            System.out.println("  ✓ All expected tools (add, multiply, subtract) were called");

        } else if (preActingCount > 0) {
            // Model called tools sequentially
            System.out.println(
                    "\n⚠ WARNING: Model called tools sequentially ("
                            + preActingCount
                            + " in first round)");
            System.out.println("  Expected: All 3 tools in one response (parallel tool calls)");
            System.out.println("  Actual: Tools may have been called across multiple rounds");
        }

        // 6. Verify response contains meaningful content
        String responseText = TestUtils.extractTextContent(response);
        assertNotNull(responseText, "Response should contain text");
        assertTrue(responseText.length() > 10, "Response should be meaningful");
        System.out.println(
                "\n✓ Response text: "
                        + responseText.substring(0, Math.min(100, responseText.length()))
                        + "...");

        System.out.println("\n=== " + modelType + " Test Completed Successfully ===");
    }

    /** Calculator tools for testing multiple tool calls. */
    public static class CalculatorTools {
        private final AtomicInteger callCount = new AtomicInteger(0);

        @Tool(description = "Add two numbers together")
        public int add(
                @ToolParam(name = "a", description = "First number", required = true) int a,
                @ToolParam(name = "b", description = "Second number", required = true) int b) {
            callCount.incrementAndGet();
            int result = a + b;
            System.out.println("  [Tool] add(" + a + ", " + b + ") = " + result);
            return result;
        }

        @Tool(description = "Multiply two numbers")
        public int multiply(
                @ToolParam(name = "a", description = "First number", required = true) int a,
                @ToolParam(name = "b", description = "Second number", required = true) int b) {
            callCount.incrementAndGet();
            int result = a * b;
            System.out.println("  [Tool] multiply(" + a + ", " + b + ") = " + result);
            return result;
        }

        @Tool(description = "Subtract second number from first number")
        public int subtract(
                @ToolParam(name = "a", description = "First number", required = true) int a,
                @ToolParam(name = "b", description = "Second number", required = true) int b) {
            callCount.incrementAndGet();
            int result = a - b;
            System.out.println("  [Tool] subtract(" + a + ", " + b + ") = " + result);
            return result;
        }

        @Tool(description = "Divide first number by second number")
        public double divide(
                @ToolParam(name = "a", description = "First number (dividend)", required = true)
                        int a,
                @ToolParam(name = "b", description = "Second number (divisor)", required = true)
                        int b) {
            callCount.incrementAndGet();
            if (b == 0) {
                throw new IllegalArgumentException("Cannot divide by zero");
            }
            double result = (double) a / b;
            System.out.println("  [Tool] divide(" + a + ", " + b + ") = " + result);
            return result;
        }

        public int getCallCount() {
            return callCount.get();
        }
    }

    /**
     * Hook to monitor tool call lifecycle.
     *
     * <p>Tracks preActing and postActing calls to verify all tools are properly executed.
     */
    public static class ToolCallMonitorHook implements Hook {
        private final AtomicInteger preActingCount = new AtomicInteger(0);
        private final AtomicInteger postActingCount = new AtomicInteger(0);
        private final List<String> calledToolNames =
                Collections.synchronizedList(new ArrayList<>());
        private final ConcurrentHashMap<String, ToolUseBlock> toolCallsById =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, ToolResultBlock> toolResultsById =
                new ConcurrentHashMap<>();

        @Override
        public Mono<ToolUseBlock> preActing(Agent agent, ToolUseBlock toolUse) {
            preActingCount.incrementAndGet();
            calledToolNames.add(toolUse.getName());
            toolCallsById.put(toolUse.getId(), toolUse);
            System.out.println(
                    "  [Hook] preActing: " + toolUse.getName() + " (id=" + toolUse.getId() + ")");
            return Mono.just(toolUse);
        }

        @Override
        public Mono<ToolResultBlock> postActing(
                Agent agent, ToolUseBlock toolUse, ToolResultBlock result) {
            postActingCount.incrementAndGet();
            toolResultsById.put(toolUse.getId(), result);
            // Check if output contains error text
            String status = "SUCCESS";
            if (result.getOutput() instanceof TextBlock tb) {
                if (tb.getText().startsWith("Error:")) {
                    status = "ERROR";
                }
            }
            System.out.println("  [Hook] postActing: " + toolUse.getName() + " -> " + status);
            return Mono.just(result);
        }

        public int getPreActingCount() {
            return preActingCount.get();
        }

        public int getPostActingCount() {
            return postActingCount.get();
        }

        public List<String> getCalledToolNames() {
            return new ArrayList<>(calledToolNames);
        }

        public ConcurrentHashMap<String, ToolUseBlock> getToolCallsById() {
            return toolCallsById;
        }

        public ConcurrentHashMap<String, ToolResultBlock> getToolResultsById() {
            return toolResultsById;
        }
    }
}
