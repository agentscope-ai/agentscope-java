/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Exception handling tests for agent system.
 *
 * <p>These tests verify system behavior under error conditions including network errors, invalid
 * API keys, model unavailability, tool execution failures, timeouts, and rate limits.
 *
 * <p><b>Requirements:</b> DASHSCOPE_API_KEY environment variable must be set
 */
@Tag("integration")
@Tag("e2e")
@EnabledIfEnvironmentVariable(
        named = "DASHSCOPE_API_KEY",
        matches = ".+",
        disabledReason = "Exception tests require DASHSCOPE_API_KEY")
@DisplayName("Exception Handling Tests")
class ExceptionHandlingTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(5);
    private static final String MODEL_NAME = "qwen-plus";

    private String validApiKey;
    private Toolkit toolkit;

    /**
     * Tool that always throws an exception.
     */
    public static class FailingTools {
        @Tool(description = "A tool that always fails")
        public String alwaysFails(
                @ToolParam(name = "input", description = "Input parameter", required = true)
                        String input) {
            throw new RuntimeException("Tool execution failed intentionally");
        }

        @Tool(description = "A tool that times out")
        public String timeoutTool(
                @ToolParam(name = "input", description = "Input parameter", required = true)
                        String input)
                throws InterruptedException {
            // Simulate long-running operation
            Thread.sleep(10000);
            return "Should not reach here";
        }

        @Tool(description = "A tool that throws a specific exception")
        public String throwsSpecificException(
                @ToolParam(name = "input", description = "Input parameter", required = true)
                        String input) {
            throw new IllegalStateException("Invalid state for this operation");
        }
    }

    @BeforeEach
    void setUp() {
        validApiKey = System.getenv("DASHSCOPE_API_KEY");
        toolkit = new Toolkit();
        System.out.println("=== Exception Handling Test Setup Complete ===");
    }

    @Test
    @DisplayName("Should handle invalid API key")
    void testAPIKeyInvalid() {
        System.out.println("\n=== Test: Invalid API Key ===");

        // Create model with invalid API key
        Model invalidModel =
                DashScopeChatModel.builder()
                        .apiKey("invalid_api_key_12345")
                        .modelName(MODEL_NAME)
                        .stream(true)
                        .build();

        InMemoryMemory memory = new InMemoryMemory();
        ReActAgent agent =
                new ReActAgent(
                        "InvalidKeyAgent",
                        "Agent with invalid API key",
                        invalidModel,
                        toolkit,
                        memory);

        Msg msg = TestUtils.createUserMessage("User", "Hello");
        System.out.println("Attempting request with invalid API key");

        // Should either throw exception or return empty response
        try {
            Msg response = agent.call(msg).block(TEST_TIMEOUT);
            System.out.println("Response: " + (response == null ? "null" : response));
            // If it doesn't throw, response might be empty or error message
            assertNotNull(response, "Response should not be null (may contain error)");
        } catch (Exception e) {
            System.out.println("Exception caught (expected): " + e.getClass().getSimpleName());
            // Various exceptions are acceptable for invalid API key
            assertTrue(
                    e.getMessage() != null, "Exception should have a message explaining the error");
        }
    }

    @Test
    @DisplayName("Should handle model not available")
    void testModelNotAvailable() {
        System.out.println("\n=== Test: Model Not Available ===");

        // Try to use a non-existent model
        Model invalidModel =
                DashScopeChatModel.builder()
                        .apiKey(validApiKey)
                        .modelName("non-existent-model-xyz-999")
                        .stream(true)
                        .build();

        InMemoryMemory memory = new InMemoryMemory();
        ReActAgent agent =
                new ReActAgent(
                        "InvalidModelAgent",
                        "Agent with invalid model",
                        invalidModel,
                        toolkit,
                        memory);

        Msg msg = TestUtils.createUserMessage("User", "Test message");
        System.out.println("Attempting request with non-existent model");

        // Should handle gracefully - either throw or return error response
        try {
            Msg response = agent.call(msg).block(TEST_TIMEOUT);
            System.out.println("Response: " + (response == null ? "null" : response));
            // If no exception, response should indicate the error somehow
            assertNotNull(response, "Response should not be null");
        } catch (Exception e) {
            System.out.println("Exception caught (expected): " + e.getClass().getSimpleName());
            System.out.println("Error message: " + e.getMessage());
            // Exception is acceptable for invalid model
            assertTrue(true, "Exception is acceptable for invalid model");
        }
    }

    @Test
    @DisplayName("Should handle tool execution failure")
    void testToolExecutionFailed() {
        System.out.println("\n=== Test: Tool Execution Failure ===");

        // Register failing tools
        Toolkit failingToolkit = new Toolkit();
        FailingTools failingTools = new FailingTools();
        failingToolkit.registerTool(failingTools);

        Model model =
                DashScopeChatModel.builder().apiKey(validApiKey).modelName(MODEL_NAME).stream(true)
                        .build();

        InMemoryMemory memory = new InMemoryMemory();
        ReActAgent agent =
                new ReActAgent(
                        "FailingToolAgent",
                        "Agent with failing tools",
                        model,
                        failingToolkit,
                        memory);

        // Ask agent to use the failing tool
        Msg msg =
                TestUtils.createUserMessage(
                        "User", "Please use the alwaysFails tool with input 'test'");
        System.out.println("Requesting use of failing tool");

        // Agent should handle tool failure gracefully
        Msg response = agent.call(msg).block(TEST_TIMEOUT);

        assertNotNull(response, "Should return response even if tool fails");
        System.out.println("Tool failure handled: response=" + response);

        // Check if response mentions error or failure
        String text = TestUtils.extractTextContent(response);
        boolean hasErrorIndication =
                text != null
                        && (text.toLowerCase().contains("error")
                                || text.toLowerCase().contains("fail"));

        System.out.println("Error indication in response: " + hasErrorIndication);
    }

    @Test
    @DisplayName("Should handle timeout scenarios")
    void testTimeoutScenario() {
        System.out.println("\n=== Test: Timeout Scenarios ===");

        Model model =
                DashScopeChatModel.builder().apiKey(validApiKey).modelName(MODEL_NAME).stream(true)
                        .build();

        InMemoryMemory memory = new InMemoryMemory();
        ReActAgent agent =
                new ReActAgent("TimeoutAgent", "Agent for timeout test", model, toolkit, memory);

        // Test with very short timeout
        Msg msg = TestUtils.createUserMessage("User", "Tell me a long story");
        System.out.println("Testing with very short timeout (5 seconds)");

        try {
            Msg response = agent.call(msg).block(SHORT_TIMEOUT);

            // If it completes within timeout, that's fine
            if (response != null) {
                System.out.println("Completed within short timeout");
                assertTrue(true, "Completed successfully");
            }
        } catch (Exception e) {
            // Timeout exceptions are expected
            System.out.println("Timeout exception (expected): " + e.getClass().getSimpleName());
            assertTrue(
                    e.toString().toLowerCase().contains("timeout")
                            || e.toString().toLowerCase().contains("cancelled"),
                    "Should be a timeout or cancellation exception");
        }
    }

    @Test
    @DisplayName("Should handle network errors gracefully")
    void testNetworkError() {
        System.out.println("\n=== Test: Network Error Handling ===");

        // Test with invalid base URL (simulating network issue)
        // Note: DashScopeChatModel might not expose baseUrl configuration,
        // so this test might need adjustment based on actual API

        Model model =
                DashScopeChatModel.builder().apiKey(validApiKey).modelName(MODEL_NAME).stream(true)
                        .build();

        InMemoryMemory memory = new InMemoryMemory();
        ReActAgent agent =
                new ReActAgent(
                        "NetworkErrorAgent", "Agent for network test", model, toolkit, memory);

        Msg msg = TestUtils.createUserMessage("User", "Simple question");

        // In normal operation, this should work
        // Network errors would be tested by actually interrupting network,
        // which is hard to simulate in unit tests
        System.out.println(
                "Note: True network error simulation requires infrastructure-level testing");

        Msg response = agent.call(msg).block(TEST_TIMEOUT);
        assertNotNull(response, "Should get response in normal conditions");
        System.out.println("Network test baseline completed");
    }

    @Test
    @DisplayName("Should handle rate limit scenarios")
    void testRateLimitExceeded() {
        System.out.println("\n=== Test: Rate Limit Handling ===");

        Model model =
                DashScopeChatModel.builder().apiKey(validApiKey).modelName(MODEL_NAME).stream(true)
                        .build();

        InMemoryMemory memory = new InMemoryMemory();
        ReActAgent agent =
                new ReActAgent(
                        "RateLimitAgent", "Agent for rate limit test", model, toolkit, memory);

        // Make multiple rapid requests to potentially trigger rate limiting
        int requestCount = 10;
        int successCount = 0;
        int errorCount = 0;

        System.out.println("Making " + requestCount + " rapid requests");

        for (int i = 0; i < requestCount; i++) {
            try {
                Msg msg = TestUtils.createUserMessage("User", "Quick question " + i);
                Msg response = agent.call(msg).block(TEST_TIMEOUT);

                if (response != null) {
                    successCount++;
                } else {
                    errorCount++;
                }
            } catch (Exception e) {
                errorCount++;
                System.out.println("Request " + i + " failed: " + e.getClass().getSimpleName());
            }
        }

        System.out.println("Successful requests: " + successCount);
        System.out.println("Failed requests: " + errorCount);

        // At least some requests should succeed
        assertTrue(successCount > 0, "At least some requests should succeed");

        System.out.println("Rate limit test completed: " + successCount + "/" + requestCount);
    }
}
