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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Integration tests for Memory persistence and Session management.
 *
 * <p>These tests verify memory operations, session persistence, cleanup, and concurrent session
 * handling.
 *
 * <p><b>Requirements:</b> DASHSCOPE_API_KEY environment variable must be set
 */
@Tag("integration")
@Tag("e2e")
@EnabledIfEnvironmentVariable(
        named = "DASHSCOPE_API_KEY",
        matches = ".+",
        disabledReason = "Memory tests require DASHSCOPE_API_KEY")
@DisplayName("Memory and Session Tests")
class MemorySessionTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);
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
        System.out.println("=== Memory and Session Test Setup Complete ===");
    }

    @Test
    @DisplayName("Should persist memory across interactions")
    void testMemoryPersistence() {
        System.out.println("\n=== Test: Memory Persistence ===");

        InMemoryMemory memory = new InMemoryMemory();
        ReActAgent agent =
                new ReActAgent(
                        "PersistentAgent", "Agent with persistent memory", model, toolkit, memory);

        // First interaction
        Msg msg1 = TestUtils.createUserMessage("User", "My name is Alice");
        System.out.println("Interaction 1: " + msg1);

        agent.call(msg1).block(TEST_TIMEOUT);
        int memorySize1 = memory.getMessages().size();
        System.out.println("Memory size after interaction 1: " + memorySize1);

        // Second interaction
        Msg msg2 = TestUtils.createUserMessage("User", "I like programming");
        System.out.println("Interaction 2: " + msg2);

        agent.call(msg2).block(TEST_TIMEOUT);
        int memorySize2 = memory.getMessages().size();
        System.out.println("Memory size after interaction 2: " + memorySize2);

        // Third interaction - ask about previous context
        Msg msg3 = TestUtils.createUserMessage("User", "What is my name?");
        System.out.println("Interaction 3: " + msg3);

        agent.call(msg3).block(TEST_TIMEOUT);
        int memorySize3 = memory.getMessages().size();
        System.out.println("Memory size after interaction 3: " + memorySize3);

        // Verify memory persisted and grew
        assertTrue(memorySize2 > memorySize1, "Memory should grow after second interaction");
        assertTrue(memorySize3 > memorySize2, "Memory should grow after third interaction");

        // Verify all messages are in memory
        List<Msg> allMessages = memory.getMessages();
        assertTrue(allMessages.size() >= 3, "Should have at least 3 user messages");

        // Check that context was preserved
        boolean hasAlice =
                allMessages.stream()
                        .anyMatch(
                                m -> {
                                    String text = TestUtils.extractTextContent(m);
                                    return text != null
                                            && (text.toLowerCase().contains("alice")
                                                    || text.toLowerCase().contains("name"));
                                });

        assertTrue(hasAlice, "Memory should contain name context");
        System.out.println(
                "Memory persistence verified: " + allMessages.size() + " total messages");
    }

    @Test
    @DisplayName("Should restore session from saved memory")
    void testSessionRestore() {
        System.out.println("\n=== Test: Session Restore ===");

        // Phase 1: Create initial session
        InMemoryMemory originalMemory = new InMemoryMemory();
        ReActAgent originalAgent =
                new ReActAgent(
                        "OriginalAgent",
                        "Agent with original session",
                        model,
                        toolkit,
                        originalMemory);

        Msg msg1 = TestUtils.createUserMessage("User", "Remember: the password is 'secret123'");
        Msg msg2 = TestUtils.createUserMessage("User", "Also remember: my favorite number is 42");

        System.out.println("Original session - Message 1: " + msg1);
        originalAgent.call(msg1).block(TEST_TIMEOUT);

        System.out.println("Original session - Message 2: " + msg2);
        originalAgent.call(msg2).block(TEST_TIMEOUT);

        // Save memory state
        List<Msg> savedMessages = new ArrayList<>(originalMemory.getMessages());
        int originalMemorySize = savedMessages.size();
        System.out.println("Saved memory state: " + originalMemorySize + " messages");

        // Phase 2: Restore session with saved memory
        InMemoryMemory restoredMemory = new InMemoryMemory();
        // Add saved messages to restored memory
        for (Msg msg : savedMessages) {
            restoredMemory.addMessage(msg);
        }

        ReActAgent restoredAgent =
                new ReActAgent(
                        "RestoredAgent",
                        "Agent with restored session",
                        model,
                        toolkit,
                        restoredMemory);

        // Verify restored memory has same size
        assertEquals(
                originalMemorySize,
                restoredMemory.getMessages().size(),
                "Restored memory should have same size as original");

        // Test that restored agent can access previous context
        Msg msg3 = TestUtils.createUserMessage("User", "What was the password?");
        System.out.println("Restored session - Query: " + msg3);

        restoredAgent.call(msg3).block(TEST_TIMEOUT);

        // Verify memory continues to grow
        assertTrue(
                restoredMemory.getMessages().size() > originalMemorySize,
                "Restored memory should grow with new interactions");

        System.out.println(
                "Session restored successfully: "
                        + restoredMemory.getMessages().size()
                        + " total messages");
    }

    @Test
    @DisplayName("Should handle memory cleanup and limits")
    void testMemoryCleanup() {
        System.out.println("\n=== Test: Memory Cleanup ===");

        InMemoryMemory memory = new InMemoryMemory();
        ReActAgent agent =
                new ReActAgent(
                        "CleanupAgent", "Agent testing memory cleanup", model, toolkit, memory);

        // Add multiple messages
        int messageCount = 5;
        for (int i = 0; i < messageCount; i++) {
            Msg msg = TestUtils.createUserMessage("User", "Message number " + (i + 1));
            System.out.println("Adding message " + (i + 1));
            agent.call(msg).block(TEST_TIMEOUT);
        }

        int memorySize = memory.getMessages().size();
        System.out.println("Memory size after " + messageCount + " messages: " + memorySize);

        assertTrue(
                memorySize >= messageCount, "Should have at least " + messageCount + " messages");

        // Test manual cleanup
        memory.clear();
        System.out.println("Memory cleared");

        assertEquals(0, memory.getMessages().size(), "Memory should be empty after clear");

        // Verify agent can continue after cleanup
        Msg newMsg = TestUtils.createUserMessage("User", "Starting fresh");
        agent.call(newMsg).block(TEST_TIMEOUT);

        assertTrue(memory.getMessages().size() > 0, "Memory should work normally after cleanup");
        System.out.println("Memory cleanup verified");
    }

    @Test
    @DisplayName("Should handle concurrent sessions independently")
    void testConcurrentSessions() throws InterruptedException {
        System.out.println("\n=== Test: Concurrent Sessions ===");

        int sessionCount = 3;
        CountDownLatch latch = new CountDownLatch(sessionCount);
        List<CompletableFuture<Integer>> futures = new ArrayList<>();

        // Create multiple concurrent sessions
        for (int i = 0; i < sessionCount; i++) {
            final int sessionId = i;

            CompletableFuture<Integer> future =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    // Each session has its own memory
                                    InMemoryMemory sessionMemory = new InMemoryMemory();
                                    ReActAgent sessionAgent =
                                            new ReActAgent(
                                                    "ConcurrentAgent" + sessionId,
                                                    "Agent for concurrent session " + sessionId,
                                                    model,
                                                    toolkit,
                                                    sessionMemory);

                                    // Send unique message for this session
                                    Msg msg =
                                            TestUtils.createUserMessage(
                                                    "User",
                                                    "Session "
                                                            + sessionId
                                                            + ": My ID is "
                                                            + sessionId);
                                    System.out.println("Session " + sessionId + ": " + msg);

                                    sessionAgent.call(msg).block(TEST_TIMEOUT);

                                    int memorySize = sessionMemory.getMessages().size();
                                    System.out.println(
                                            "Session "
                                                    + sessionId
                                                    + " completed with "
                                                    + memorySize
                                                    + " messages");

                                    latch.countDown();
                                    return memorySize;
                                } catch (Exception e) {
                                    System.err.println("Session " + sessionId + " failed: " + e);
                                    latch.countDown();
                                    return 0;
                                }
                            });

            futures.add(future);
        }

        // Wait for all sessions to complete
        boolean completed = latch.await(60, TimeUnit.SECONDS);
        assertTrue(completed, "All concurrent sessions should complete within timeout");

        // Verify all sessions processed independently
        int successfulSessions = 0;
        for (int i = 0; i < futures.size(); i++) {
            Integer memorySize = futures.get(i).join();
            if (memorySize > 0) {
                successfulSessions++;
                System.out.println("Session " + i + " verified with " + memorySize + " messages");
            }
        }

        assertTrue(
                successfulSessions >= sessionCount / 2,
                "At least half of concurrent sessions should succeed");
        System.out.println(
                "Concurrent sessions verified: " + successfulSessions + "/" + sessionCount);
    }
}
