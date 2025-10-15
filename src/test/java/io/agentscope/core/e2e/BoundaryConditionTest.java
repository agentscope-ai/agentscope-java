package io.agentscope.core.e2e;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Boundary condition tests for agent system.
 *
 * <p>These tests verify system behavior under boundary conditions including null inputs, empty
 * strings, very long inputs, special characters, and concurrent requests.
 *
 * <p><b>Requirements:</b> DASHSCOPE_API_KEY environment variable must be set
 */
@Tag("integration")
@Tag("e2e")
@EnabledIfEnvironmentVariable(
        named = "DASHSCOPE_API_KEY",
        matches = ".+",
        disabledReason = "Boundary tests require DASHSCOPE_API_KEY")
@DisplayName("Boundary Condition Tests")
class BoundaryConditionTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration LONG_TEST_TIMEOUT = Duration.ofSeconds(60);
    private static final String MODEL_NAME = "qwen-plus";

    private Model model;
    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        model =
                DashScopeChatModel.builder().apiKey(apiKey).modelName(MODEL_NAME).stream(true)
                        .build();
        toolkit = new Toolkit();
        System.out.println("=== Boundary Condition Test Setup Complete ===");
    }

    @Test
    @DisplayName("Should handle null input gracefully")
    void testNullInput() {
        System.out.println("\n=== Test: Null Input Handling ===");

        InMemoryMemory memory = new InMemoryMemory();
        ReActAgent agent =
                new ReActAgent(
                        "NullTestAgent", "Agent for null input test", model, toolkit, memory);

        // Test with null message content (edge case)
        try {
            // Create a message with empty content to simulate edge case
            Msg emptyMsg = TestUtils.createUserMessage("User", "");
            System.out.println("Sending empty message: '" + emptyMsg + "'");

            List<Msg> response = agent.stream(emptyMsg).collectList().block(TEST_TIMEOUT);

            // Agent should handle gracefully - either return response or handle safely
            assertNotNull(response, "Response should not be null even for empty input");
            System.out.println("Empty input handled: " + response.size() + " responses");
        } catch (Exception e) {
            // It's acceptable to throw an exception for null/empty input
            System.out.println("Exception caught (acceptable): " + e.getClass().getSimpleName());
            assertTrue(
                    e instanceof IllegalArgumentException || e instanceof NullPointerException,
                    "Should throw appropriate exception for invalid input");
        }
    }

    @Test
    @DisplayName("Should handle empty string input")
    void testEmptyString() {
        System.out.println("\n=== Test: Empty String Handling ===");

        InMemoryMemory memory = new InMemoryMemory();
        ReActAgent agent =
                new ReActAgent(
                        "EmptyStringAgent", "Agent for empty string test", model, toolkit, memory);

        // Test with empty string
        Msg emptyMsg = TestUtils.createUserMessage("User", "");
        System.out.println("Sending empty string message");

        List<Msg> response = agent.stream(emptyMsg).collectList().block(TEST_TIMEOUT);

        // Should handle gracefully
        assertNotNull(response, "Should return response for empty string");
        System.out.println("Empty string handled: " + response.size() + " responses");
    }

    @Test
    @DisplayName("Should handle very long input (10000+ characters)")
    void testVeryLongInput() {
        System.out.println("\n=== Test: Very Long Input ===");

        InMemoryMemory memory = new InMemoryMemory();
        ReActAgent agent =
                new ReActAgent(
                        "LongInputAgent", "Agent for long input test", model, toolkit, memory);

        // Create very long input (10000+ characters)
        StringBuilder longText = new StringBuilder();
        longText.append("Please summarize this long text: ");
        for (int i = 0; i < 1000; i++) {
            longText.append("This is sentence number ")
                    .append(i)
                    .append(" in a very long paragraph. ");
        }

        String longInput = longText.toString();
        System.out.println("Input length: " + longInput.length() + " characters");

        Msg longMsg = TestUtils.createUserMessage("User", longInput);

        List<Msg> response = agent.stream(longMsg).collectList().block(LONG_TEST_TIMEOUT);

        assertNotNull(response, "Should handle very long input");
        assertTrue(response.size() > 0, "Should produce response for long input");
        System.out.println("Long input handled successfully: " + response.size() + " responses");
    }

    @Test
    @DisplayName("Should handle special characters (Unicode, Emoji, etc.)")
    void testSpecialCharacters() {
        System.out.println("\n=== Test: Special Characters ===");

        InMemoryMemory memory = new InMemoryMemory();
        ReActAgent agent =
                new ReActAgent(
                        "SpecialCharAgent",
                        "Agent for special character test",
                        model,
                        toolkit,
                        memory);

        // Test various special characters
        String[] specialInputs = {
            "Hello 世界 🌍",
            "Emoji test: 😀 😃 😄 😁 🎉 🎊",
            "Math symbols: ∑ ∫ √ π ∞ ≠ ≤ ≥",
            "Mixed: Hello\nWorld\tTab\r\nNewline",
            "Symbols: !@#$%^&*()_+-=[]{}|;':\",./<>?"
        };

        for (String specialInput : specialInputs) {
            System.out.println("Testing: " + specialInput);

            Msg msg = TestUtils.createUserMessage("User", specialInput);
            List<Msg> response = agent.stream(msg).collectList().block(TEST_TIMEOUT);

            assertNotNull(response, "Should handle special characters: " + specialInput);
            assertTrue(response.size() > 0, "Should respond to special characters");
        }

        System.out.println("All special character tests passed");
    }

    @Test
    @DisplayName("Should handle concurrent requests (100 requests)")
    void testConcurrentRequests() throws InterruptedException {
        System.out.println("\n=== Test: Concurrent Requests (100 requests) ===");

        int requestCount = 100;
        CountDownLatch latch = new CountDownLatch(requestCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // Launch concurrent requests
        for (int i = 0; i < requestCount; i++) {
            final int requestId = i;

            CompletableFuture<Void> future =
                    CompletableFuture.runAsync(
                            () -> {
                                try {
                                    // Each request gets its own agent and memory
                                    InMemoryMemory memory = new InMemoryMemory();
                                    ReActAgent agent =
                                            new ReActAgent(
                                                    "ConcurrentAgent" + requestId,
                                                    "Agent for concurrent test",
                                                    model,
                                                    toolkit,
                                                    memory);

                                    Msg msg =
                                            TestUtils.createUserMessage(
                                                    "User", "Quick question " + requestId);

                                    List<Msg> response =
                                            agent.stream(msg).collectList().block(TEST_TIMEOUT);

                                    if (response != null && response.size() > 0) {
                                        successCount.incrementAndGet();
                                        if (requestId % 10 == 0) {
                                            System.out.println(
                                                    "Request "
                                                            + requestId
                                                            + " completed successfully");
                                        }
                                    } else {
                                        failureCount.incrementAndGet();
                                    }
                                } catch (Exception e) {
                                    failureCount.incrementAndGet();
                                    System.err.println(
                                            "Request " + requestId + " failed: " + e.getMessage());
                                } finally {
                                    latch.countDown();
                                }
                            });

            futures.add(future);
        }

        // Wait for all requests to complete (with generous timeout)
        boolean completed = latch.await(120, TimeUnit.SECONDS);

        assertTrue(completed, "All concurrent requests should complete within timeout");

        System.out.println("\n=== Concurrent Test Results ===");
        System.out.println("Total requests: " + requestCount);
        System.out.println("Successful: " + successCount.get());
        System.out.println("Failed: " + failureCount.get());

        // We expect at least 50% success rate due to potential rate limiting
        int minSuccessRate = requestCount / 2;
        assertTrue(
                successCount.get() >= minSuccessRate,
                "At least "
                        + minSuccessRate
                        + " requests should succeed (got "
                        + successCount.get()
                        + ")");

        System.out.println(
                "Concurrent requests test passed: "
                        + successCount.get()
                        + "/"
                        + requestCount
                        + " succeeded");
    }
}
