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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.MockToolkit;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Performance tests for Agent functionality.
 *
 * <p>These tests verify agent performance under various load conditions including concurrent
 * execution, large memory management, and response time benchmarks.
 *
 * <p>Tagged as "integration" and "performance" - only run in CI or with explicit flag.
 *
 * <p>Run with: mvn test -Dtest.integration=true -Dtest.performance=true
 */
@Tag("integration")
@Tag("performance")
@DisplayName("Agent Performance Tests")
class AgentPerformanceTest {

    private ExecutorService executorService;
    private MockToolkit mockToolkit;

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(10);
        mockToolkit = new MockToolkit();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (executorService != null) {
            executorService.shutdown();
            executorService.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Should handle concurrent agents without conflicts")
    void testConcurrentAgents() throws Exception {
        int agentCount = 10;
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // Create and run multiple agents concurrently
        for (int i = 0; i < agentCount; i++) {
            final int agentIndex = i;
            CompletableFuture<Void> future =
                    CompletableFuture.runAsync(
                            () -> {
                                InMemoryMemory memory = new InMemoryMemory();
                                MockModel model =
                                        new MockModel("Response from agent " + agentIndex);
                                ReActAgent agent =
                                        ReActAgent.builder()
                                                .name("Agent-" + agentIndex)
                                                .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                                                .model(model)
                                                .toolkit(mockToolkit)
                                                .memory(memory)
                                                .build();

                                Msg input =
                                        TestUtils.createUserMessage(
                                                "User", "Test message " + agentIndex);
                                Msg responses =
                                        agent.call(input)
                                                .block(
                                                        Duration.ofMillis(
                                                                TestConstants
                                                                        .DEFAULT_TEST_TIMEOUT_MS));

                                assertNotNull(responses, "Agent " + agentIndex + " should respond");
                            },
                            executorService);
            futures.add(future);
        }

        // Wait for all agents to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS);

        // Verify all agents completed successfully
        assertTrue(true, "All concurrent agents completed");
    }

    @Test
    @DisplayName("Should handle large memory without performance degradation")
    void testLargeMemory() {
        InMemoryMemory memory = new InMemoryMemory();
        MockModel model = new MockModel("Response");
        ReActAgent agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(model)
                        .toolkit(mockToolkit)
                        .memory(memory)
                        .build();

        // Add many messages to memory
        int messageCount = 100;
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < messageCount; i++) {
            Msg msg = TestUtils.createUserMessage("User", "Message " + i);
            agent.call(msg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
        }

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;

        // Verify memory contains all messages
        List<Msg> allMessages = agent.getMemory().getMessages();
        assertTrue(
                allMessages.size() >= messageCount,
                "Memory should contain at least " + messageCount + " messages");

        // Performance assertion: should complete in reasonable time
        // Average time per message should be less than 100ms
        double avgTimePerMessage = (double) totalTime / messageCount;
        assertTrue(
                avgTimePerMessage < 100,
                String.format(
                        "Average time per message (%.2fms) should be less than 100ms",
                        avgTimePerMessage));

        System.out.println("Performance metrics:");
        System.out.println("  Total messages: " + messageCount);
        System.out.println("  Total time: " + totalTime + "ms");
        System.out.println(
                "  Average time per message: " + String.format("%.2f", avgTimePerMessage) + "ms");
    }

    @Test
    @DisplayName("Should respond within acceptable time limits")
    void testResponseTime() {
        InMemoryMemory memory = new InMemoryMemory();
        MockModel model = new MockModel("Quick response");
        ReActAgent agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(model)
                        .toolkit(mockToolkit)
                        .memory(memory)
                        .build();

        // Measure response time for single interaction
        Msg input = TestUtils.createUserMessage("User", "Quick test");

        long startTime = System.nanoTime();
        Msg responses =
                agent.call(input).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
        long endTime = System.nanoTime();

        assertNotNull(responses);

        long responseTimeMs = (endTime - startTime) / 1_000_000;

        // Response should be reasonably fast (< 1000ms for mock)
        assertTrue(
                responseTimeMs < 1000,
                String.format("Response time (%dms) should be less than 1000ms", responseTimeMs));

        System.out.println("Response time metrics:");
        System.out.println("  Response time: " + responseTimeMs + "ms");

        // Measure average response time over multiple calls
        int iterations = 10;
        long totalTime = 0;

        for (int i = 0; i < iterations; i++) {
            Msg msg = TestUtils.createUserMessage("User", "Test " + i);
            startTime = System.nanoTime();
            agent.call(msg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
            endTime = System.nanoTime();
            totalTime += (endTime - startTime);
        }

        long avgResponseTimeMs = (totalTime / iterations) / 1_000_000;
        assertTrue(
                avgResponseTimeMs < 1000,
                String.format(
                        "Average response time (%dms) should be less than 1000ms",
                        avgResponseTimeMs));

        System.out.println(
                "  Average response time ("
                        + iterations
                        + " iterations): "
                        + avgResponseTimeMs
                        + "ms");
    }
}
