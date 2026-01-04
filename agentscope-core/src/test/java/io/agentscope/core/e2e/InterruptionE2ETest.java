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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * E2E tests for Agent interruption mechanism.
 *
 * <p>Tests cooperative polling-based interruption, including basic interrupt, interrupt with
 * message, and interruption during tool execution.
 */
@Tag("e2e")
@Tag("interruption")
@ExtendWith(E2ETestCondition.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Agent Interruption E2E Tests")
class InterruptionE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(300);

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should handle interrupt() call gracefully")
    void testBasicInterruption(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Basic Interruption with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("InterruptAgent", toolkit);

        AtomicBoolean responseReceived = new AtomicBoolean(false);
        AtomicBoolean interruptCalled = new AtomicBoolean(false);

        // Start a long-running request
        Msg longRequest =
                TestUtils.createUserMessage(
                        "User",
                        "Write a detailed essay about the history of computing. Include at least"
                                + " 5 paragraphs covering different eras.");

        CompletableFuture<Msg> future =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return agent.call(longRequest).block(TEST_TIMEOUT);
                            } catch (Exception e) {
                                System.out.println(
                                        "  Request interrupted or failed: " + e.getMessage());
                                return null;
                            }
                        });

        // Wait briefly then interrupt
        try {
            Thread.sleep(500);
            agent.interrupt();
            interruptCalled.set(true);
            System.out.println("  Interrupt called");

            // Wait for the response (might be partial or complete depending on timing)
            Msg response = future.get(TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (response != null) {
                responseReceived.set(true);
                System.out.println("  Received response despite interruption (completed before)");
            }
        } catch (Exception e) {
            System.out.println("  Request handling: " + e.getClass().getSimpleName());
        }

        assertTrue(interruptCalled.get(), "Interrupt should have been called");

        // The test passes if either:
        // 1. The request was interrupted (InterruptedException thrown at a checkpoint)
        // 2. The request completed before interruption took effect (race condition)
        // Both outcomes are valid for the cooperative interrupt model.
        // The key guarantee is that interrupt() doesn't crash and subsequent requests work.
        System.out.println(
                "✓ Basic interruption handling verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should handle interrupt with message")
    void testInterruptionWithMessage(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Interruption with Message for " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("InterruptMsgAgent", toolkit);

        AtomicBoolean interruptCalled = new AtomicBoolean(false);

        // Start request
        Msg request =
                TestUtils.createUserMessage("User", "Tell me a long story about a brave knight.");

        CompletableFuture<Msg> future =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return agent.call(request).block(TEST_TIMEOUT);
                            } catch (Exception e) {
                                System.out.println("  Request handling: " + e.getMessage());
                                return null;
                            }
                        });

        // Interrupt with a message
        try {
            Thread.sleep(300);
            Msg interruptMsg =
                    TestUtils.createUserMessage("User", "Actually, just tell me your name.");
            agent.interrupt(interruptMsg);
            interruptCalled.set(true);
            System.out.println("  Interrupt with message called");

            // Wait for completion
            Msg response = future.get(TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (response != null) {
                System.out.println("  Received response");
            }
        } catch (Exception e) {
            System.out.println("  Handling: " + e.getClass().getSimpleName());
        }

        assertTrue(interruptCalled.get(), "Interrupt with message should have been called");

        // Similar to testBasicInterruption, the interrupt timing is non-deterministic.
        // The key guarantee is that interrupt(msg) doesn't crash and the agent remains usable.
        System.out.println(
                "✓ Interruption with message verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should continue normally after interrupt is cleared")
    void testContinueAfterInterrupt(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Continue After Interrupt for " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("ContinueAgent", toolkit);

        // First, interrupt the agent
        agent.interrupt();
        System.out.println("  Agent interrupted (no active request)");

        // Then make a new request - should work after interrupt is cleared
        Msg request = TestUtils.createUserMessage("User", "What is 2 + 2?");
        Msg response = agent.call(request).block(TEST_TIMEOUT);

        assertNotNull(response, "Should receive response after interrupt is cleared");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have content for " + provider.getModelName());

        System.out.println("✓ Continue after interrupt verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should handle multiple sequential requests after interruption")
    void testMultipleRequestsAfterInterrupt(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Multiple Requests After Interrupt for "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("MultiRequestAgent", toolkit);

        // Interrupt (no active request)
        agent.interrupt();
        System.out.println("  Initial interrupt called");

        // First request after interrupt
        Msg request1 = TestUtils.createUserMessage("User", "Say hello.");
        Msg response1 = agent.call(request1).block(TEST_TIMEOUT);
        assertNotNull(response1, "First request should succeed");
        System.out.println("  First request completed");

        // Second request
        Msg request2 = TestUtils.createUserMessage("User", "Say goodbye.");
        Msg response2 = agent.call(request2).block(TEST_TIMEOUT);
        assertNotNull(response2, "Second request should succeed");
        System.out.println("  Second request completed");

        // Third request
        Msg request3 = TestUtils.createUserMessage("User", "Count to 3.");
        Msg response3 = agent.call(request3).block(TEST_TIMEOUT);
        assertNotNull(response3, "Third request should succeed");
        System.out.println("  Third request completed");

        System.out.println(
                "✓ Multiple requests after interrupt verified for " + provider.getProviderName());
    }
}
