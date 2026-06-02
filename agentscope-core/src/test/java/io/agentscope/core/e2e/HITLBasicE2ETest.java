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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

/**
 * E2E tests for Human-in-the-Loop (HITL) functionality.
 *
 * <p>Tests agent pause/resume behavior for human review and confirmation.
 */
@Tag("e2e")
@Tag("hitl")
@ExtendWith(E2ETestCondition.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Human-in-the-Loop E2E Tests")
class HITLBasicE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(300);

    private static final List<String> SENSITIVE_TOOLS = List.of("delete_file", "send_email");

    // ==================== Test Tools ====================

    /**
     * Safe tools that don't require confirmation.
     */
    public static class SafeTools {

        @Tool(description = "Get the current time")
        public String getCurrentTime() {
            return "Current time: 2025-01-03 10:30:00";
        }

        @Tool(description = "Read a file (safe operation)")
        public String readFile(@ToolParam(name = "filename") String filename) {
            return "Contents of " + filename + ": Hello, World!";
        }
    }

    /**
     * Sensitive tools that require human confirmation.
     */
    public static class SensitiveTools {

        @Tool(name = "delete_file", description = "Delete a file (requires confirmation)")
        public String deleteFile(@ToolParam(name = "filename") String filename) {
            return "File '" + filename + "' deleted successfully (simulated)";
        }

        @Tool(name = "send_email", description = "Send an email (requires confirmation)")
        public String sendEmail(
                @ToolParam(name = "to") String to, @ToolParam(name = "subject") String subject) {
            return "Email sent to " + to + " with subject: " + subject + " (simulated)";
        }
    }

    // ==================== HITL Hooks ====================

    /**
     * Hook that stops agent when sensitive tools are invoked.
     */
    private static Hook createConfirmationHook(AtomicBoolean stopCalled, AtomicBoolean shouldStop) {
        return new Hook() {
            @Override
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                if (event instanceof PostReasoningEvent e) {
                    Msg reasoningMsg = e.getReasoningMessage();
                    List<ToolUseBlock> toolCalls =
                            reasoningMsg.getContentBlocks(ToolUseBlock.class);

                    boolean hasSensitive =
                            toolCalls.stream().anyMatch(t -> SENSITIVE_TOOLS.contains(t.getName()));

                    if (hasSensitive && shouldStop.get()) {
                        System.out.println("  [HITL] Sensitive tool detected, stopping agent...");
                        stopCalled.set(true);
                        e.stopAgent();
                    }
                }
                return Mono.just(event);
            }
        };
    }

    /**
     * Hook that counts how many times reasoning occurs.
     */
    private static Hook createCountingHook(AtomicInteger reasoningCount) {
        return new Hook() {
            @Override
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                if (event instanceof PostReasoningEvent) {
                    reasoningCount.incrementAndGet();
                }
                return Mono.just(event);
            }
        };
    }

    // ==================== Tests ====================

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("Should pause agent on sensitive tool invocation")
    void testPauseOnSensitiveTool(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: Pause on Sensitive Tool with " + provider.getProviderName() + " ===");

        AtomicBoolean stopCalled = new AtomicBoolean(false);
        AtomicBoolean shouldStop = new AtomicBoolean(true);
        Hook confirmationHook = createConfirmationHook(stopCalled, shouldStop);

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SafeTools());
        toolkit.registerTool(new SensitiveTools());

        ReActAgent agent =
                provider.createAgentBuilder("HITLAgent", toolkit).hook(confirmationHook).build();

        // Ask to delete a file - should trigger HITL
        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "Please delete the file named 'temp.txt' directly using the delete_file"
                                + " tool. I confirm that I want to proceed.");
        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Should receive response");

        // If the model tried to use delete_file, stopCalled should be true
        // and response should contain pending tool use
        Assertions.assertTrue(stopCalled.get());

        System.out.println("Agent was stopped by HITL hook");
        assertTrue(
                response.hasContentBlocks(ToolUseBlock.class),
                "Response should contain pending tool calls when stopped");

        List<ToolUseBlock> pendingTools = response.getContentBlocks(ToolUseBlock.class);
        System.out.println("Pending tools: " + pendingTools.size());
        pendingTools.forEach(t -> System.out.println("  - " + t.getName()));

        System.out.println("✓ HITL pause test completed for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("Should not pause on safe tool invocation")
    void testNoStopOnSafeTool(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: No Stop on Safe Tool with " + provider.getProviderName() + " ===");

        AtomicBoolean stopCalled = new AtomicBoolean(false);
        AtomicBoolean shouldStop = new AtomicBoolean(true);
        Hook confirmationHook = createConfirmationHook(stopCalled, shouldStop);

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SafeTools());
        toolkit.registerTool(new SensitiveTools());

        ReActAgent agent =
                provider.createAgentBuilder("SafeToolAgent", toolkit)
                        .hook(confirmationHook)
                        .build();

        // Ask for safe operation - should not trigger HITL
        Msg input = TestUtils.createUserMessage("User", "What is the current time?");
        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Should receive response");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have content for " + provider.getModelName());

        System.out.println("Response: " + TestUtils.extractTextContent(response));

        // Safe tools should not trigger stop
        // (Note: The model might not use tools at all for this simple query)
        assertFalse(
                stopCalled.get() && response.hasContentBlocks(ToolUseBlock.class),
                "Should not have stopped with pending tool calls for safe operation");

        System.out.println("✓ Safe tool execution completed for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("Should allow resuming agent after pause")
    void testResumeAfterPause(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: Resume After Pause with " + provider.getProviderName() + " ===");

        AtomicBoolean stopCalled = new AtomicBoolean(false);
        AtomicBoolean shouldStop = new AtomicBoolean(true);
        AtomicInteger reasoningCount = new AtomicInteger(0);

        Hook confirmationHook = createConfirmationHook(stopCalled, shouldStop);
        Hook countingHook = createCountingHook(reasoningCount);

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SafeTools());
        toolkit.registerTool(new SensitiveTools());

        ReActAgent agent =
                provider.createAgentBuilder("ResumeAgent", toolkit)
                        .hook(confirmationHook)
                        .hook(countingHook)
                        .build();

        // Ask to delete a file - may trigger HITL
        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "Please delete the file named 'test.txt' directly using the delete_file"
                                + " tool. I confirm that I want to proceed.");
        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Should receive first response");
        int firstCount = reasoningCount.get();
        System.out.println("First call reasoning count: " + firstCount);

        assertTrue(stopCalled.get());
        assertTrue(response.hasContentBlocks(ToolUseBlock.class));
        System.out.println("Agent paused, simulating user confirmation and resume...");

        // Reset stop flag for next call (allow tool execution this time)
        stopCalled.set(false);

        shouldStop.set(false);

        // Resume by starting a new conversation with explicit instruction
        // to proceed with the previously requested action
        Msg resumeResponse = agent.call().block(TEST_TIMEOUT);

        assertNotNull(resumeResponse, "Should receive resume response");
        assertTrue(
                ContentValidator.hasMeaningfulContent(resumeResponse),
                "Resume response should have content");

        int secondCount = reasoningCount.get();
        System.out.println("After resume reasoning count: " + secondCount);
        System.out.println("Resume response: " + TestUtils.extractTextContent(resumeResponse));

        System.out.println("✓ Resume after pause test completed for " + provider.getProviderName());
    }
}
