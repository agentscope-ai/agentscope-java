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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Test cases for ReActAgent error recovery during tool execution.
 *
 * <p>This test class covers the fixes for Issue #951:
 * <ul>
 *   <li>Fix 1: Error recovery in executeToolCalls() - generates error results when tool execution fails</li>
 *   <li>Fix 2: Auto-recovery in doCall() - handles orphaned pending tool call states</li>
 *   <li>Fix 3: generateAndAddErrorToolResults() - helper method for error result generation</li>
 * </ul>
 *
 * @see <a href="https://github.com/agentscope-ai/agentscope-java/issues/951">Issue #951</a>
 */
@DisplayName("ReActAgent Error Recovery Tests")
class ReActAgentErrorRecoveryTest {

    private InMemoryMemory memory;

    @BeforeEach
    void setUp() {
        memory = new InMemoryMemory();
    }

    @Nested
    @DisplayName("Fix 1: Error Recovery in executeToolCalls()")
    class ExecuteToolCallsErrorRecoveryTest {

        @Test
        @DisplayName("Should generate error results when tool execution throws exception")
        void testToolExecutionExceptionGeneratesErrorResult() {
            AtomicInteger callCount = new AtomicInteger(0);

            // Create a toolkit with a tool that throws an exception
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(createExceptionThrowingTool("failing_tool", "Simulated failure"));

            MockModel mockModel =
                    new MockModel(
                            messages -> {
                                int call = callCount.getAndIncrement();
                                if (call == 0) {
                                    // First call: request tool execution
                                    return List.of(
                                            createToolCallResponse("failing_tool", "call_1"));
                                }
                                // Second call: model receives error result and responds
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "I encountered an"
                                                                                    + " error with"
                                                                                    + " the tool")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            });

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .sysPrompt("You are a test agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .build();

            // Execute
            Msg response =
                    agent.call(TestUtils.createUserMessage("User", "Use the failing tool"))
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            // Verify
            assertNotNull(response, "Response should not be null");

            // Verify memory contains error tool result
            List<Msg> messages = memory.getMessages();
            boolean hasErrorResult =
                    messages.stream()
                            .anyMatch(
                                    m -> {
                                        if (m.hasContentBlocks(ToolResultBlock.class)) {
                                            ToolResultBlock trb =
                                                    m.getFirstContentBlock(ToolResultBlock.class);
                                            if (trb != null && !trb.getOutput().isEmpty()) {
                                                if (trb.getOutput().get(0)
                                                        instanceof TextBlock tb) {
                                                    return tb.getText().contains("Error:");
                                                }
                                            }
                                        }
                                        return false;
                                    });

            assertTrue(hasErrorResult, "Memory should contain error tool result");

            // Verify model was called twice (first for tool call, second for final response)
            assertEquals(2, mockModel.getCallCount(), "Model should be called twice");
        }

        @Test
        @DisplayName("Should generate error results for multiple failing tools")
        void testMultipleToolExecutionFailures() {
            AtomicInteger callCount = new AtomicInteger(0);

            // Create a toolkit with multiple failing tools
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(createExceptionThrowingTool("tool_1", "Error in tool 1"));
            toolkit.registerTool(createExceptionThrowingTool("tool_2", "Error in tool 2"));

            MockModel mockModel =
                    new MockModel(
                            messages -> {
                                int call = callCount.getAndIncrement();
                                if (call == 0) {
                                    // First call: request two tool executions
                                    return List.of(
                                            createToolCallResponse("tool_1", "call_1"),
                                            createToolCallResponse("tool_2", "call_2"));
                                }
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Both tools failed")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            });

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .sysPrompt("You are a test agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .build();

            // Execute
            Msg response =
                    agent.call(TestUtils.createUserMessage("User", "Use both tools"))
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            // Verify
            assertNotNull(response, "Response should not be null");

            // Count error results in memory
            long errorResultCount =
                    memory.getMessages().stream()
                            .filter(m -> m.hasContentBlocks(ToolResultBlock.class))
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .filter(
                                    trb -> {
                                        if (!trb.getOutput().isEmpty()
                                                && trb.getOutput().get(0) instanceof TextBlock tb) {
                                            return tb.getText().contains("Error:");
                                        }
                                        return false;
                                    })
                            .count();

            assertEquals(2, errorResultCount, "Should have 2 error results in memory");
        }

        @Test
        @DisplayName("Should handle tool timeout and generate error result")
        void testToolTimeoutGeneratesErrorResult() {
            AtomicInteger callCount = new AtomicInteger(0);

            // Create a toolkit with a slow tool
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(createSlowTool("slow_tool", 5000)); // 5 second delay

            MockModel mockModel =
                    new MockModel(
                            messages -> {
                                int call = callCount.getAndIncrement();
                                if (call == 0) {
                                    return List.of(createToolCallResponse("slow_tool", "call_1"));
                                }
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Tool timed out")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            });

            // Configure short timeout
            ExecutionConfig timeoutConfig =
                    ExecutionConfig.builder().timeout(Duration.ofMillis(100)).build();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .sysPrompt("You are a test agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .toolExecutionConfig(timeoutConfig)
                            .build();

            // Execute
            Msg response =
                    agent.call(TestUtils.createUserMessage("User", "Use the slow tool"))
                            .block(Duration.ofMillis(TestConstants.LONG_TEST_TIMEOUT_MS));

            // Verify agent recovered and returned response
            assertNotNull(response, "Response should not be null");

            // Verify memory contains error result with timeout message
            boolean hasTimeoutError =
                    memory.getMessages().stream()
                            .anyMatch(
                                    m -> {
                                        if (m.hasContentBlocks(ToolResultBlock.class)) {
                                            ToolResultBlock trb =
                                                    m.getFirstContentBlock(ToolResultBlock.class);
                                            if (trb != null && !trb.getOutput().isEmpty()) {
                                                if (trb.getOutput().get(0)
                                                        instanceof TextBlock tb) {
                                                    return tb.getText().contains("timeout")
                                                            || tb.getText().contains("Error:");
                                                }
                                            }
                                        }
                                        return false;
                                    });

            assertTrue(hasTimeoutError, "Memory should contain timeout error result");
        }
    }

    @Nested
    @DisplayName("Fix 2: Auto-Recovery in doCall() for Orphaned Pending States")
    class OrphanedPendingStateRecoveryTest {

        @Test
        @DisplayName(
                "Should auto-recover when pending tool calls exist without user-provided results")
        void testAutoRecoveryForOrphanedPendingCalls() {
            AtomicInteger callCount = new AtomicInteger(0);

            // Create toolkit with a working tool
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(createSimpleTool("simple_tool", "Tool executed"));

            MockModel mockModel =
                    new MockModel(
                            messages -> {
                                int call = callCount.getAndIncrement();
                                if (call == 0) {
                                    // First call: return tool call
                                    return List.of(
                                            createToolCallResponse("simple_tool", "call_orphan"));
                                }
                                // Subsequent calls: return final response
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "Recovered from"
                                                                                    + " orphaned"
                                                                                    + " state")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            });

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .sysPrompt("You are a test agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .build();

            // Simulate orphaned state: add assistant message with tool call to memory
            // but no corresponding tool result
            Msg toolCallMsg =
                    Msg.builder()
                            .name("TestAgent")
                            .role(MsgRole.ASSISTANT)
                            .content(
                                    ToolUseBlock.builder()
                                            .id("call_orphan")
                                            .name("simple_tool")
                                            .input(Map.of())
                                            .build())
                            .build();
            memory.addMessage(toolCallMsg);

            // Now call agent with a new user message (no tool results provided)
            Msg response =
                    agent.call(TestUtils.createUserMessage("User", "Continue please"))
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            // Verify agent recovered
            assertNotNull(response, "Response should not be null");

            // Verify auto-generated error result is in memory
            boolean hasAutoGeneratedError =
                    memory.getMessages().stream()
                            .anyMatch(
                                    m -> {
                                        if (m.hasContentBlocks(ToolResultBlock.class)) {
                                            ToolResultBlock trb =
                                                    m.getFirstContentBlock(ToolResultBlock.class);
                                            if (trb != null
                                                    && trb.getId() != null
                                                    && trb.getId().equals("call_orphan")) {
                                                if (!trb.getOutput().isEmpty()
                                                        && trb.getOutput().get(0)
                                                                instanceof TextBlock tb) {
                                                    return tb.getText().contains("[ERROR]")
                                                            && tb.getText()
                                                                    .contains(
                                                                            "failed or was"
                                                                                + " interrupted");
                                                }
                                            }
                                        }
                                        return false;
                                    });

            assertTrue(
                    hasAutoGeneratedError,
                    "Memory should contain auto-generated error result for orphaned tool call");
        }

        @Test
        @DisplayName("Should auto-recover for multiple orphaned pending tool calls")
        void testAutoRecoveryForMultipleOrphanedPendingCalls() {
            AtomicInteger callCount = new AtomicInteger(0);

            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(createSimpleTool("tool_a", "Tool A result"));
            toolkit.registerTool(createSimpleTool("tool_b", "Tool B result"));

            MockModel mockModel =
                    new MockModel(
                            messages -> {
                                int call = callCount.getAndIncrement();
                                if (call == 0) {
                                    return List.of(
                                            createToolCallResponse("tool_a", "call_a"),
                                            createToolCallResponse("tool_b", "call_b"));
                                }
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Recovered")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            });

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .sysPrompt("You are a test agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .build();

            // Simulate orphaned state with multiple pending tool calls
            Msg toolCallMsg =
                    Msg.builder()
                            .name("TestAgent")
                            .role(MsgRole.ASSISTANT)
                            .content(
                                    List.of(
                                            ToolUseBlock.builder()
                                                    .id("call_a")
                                                    .name("tool_a")
                                                    .input(Map.of())
                                                    .build(),
                                            ToolUseBlock.builder()
                                                    .id("call_b")
                                                    .name("tool_b")
                                                    .input(Map.of())
                                                    .build()))
                            .build();
            memory.addMessage(toolCallMsg);

            // Call agent without tool results
            Msg response =
                    agent.call(TestUtils.createUserMessage("User", "Continue"))
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response, "Response should not be null");

            // Count auto-generated error results
            long errorCount =
                    memory.getMessages().stream()
                            .filter(m -> m.hasContentBlocks(ToolResultBlock.class))
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .filter(
                                    trb -> {
                                        if (!trb.getOutput().isEmpty()
                                                && trb.getOutput().get(0) instanceof TextBlock tb) {
                                            return tb.getText().contains("[ERROR]")
                                                    && tb.getText()
                                                            .contains("failed or was interrupted");
                                        }
                                        return false;
                                    })
                            .count();

            assertEquals(2, errorCount, "Should have 2 auto-generated error results");
        }

        @Test
        @DisplayName("Should not auto-recover when user provides valid tool results")
        void testNoAutoRecoveryWhenUserProvidesResults() {
            AtomicInteger callCount = new AtomicInteger(0);

            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(createSimpleTool("simple_tool", "Tool result"));

            MockModel mockModel =
                    new MockModel(
                            messages -> {
                                int call = callCount.getAndIncrement();
                                if (call == 0) {
                                    return List.of(
                                            createToolCallResponse(
                                                    "simple_tool", "call_user_provided"));
                                }
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "Processed"
                                                                                    + " user-provided"
                                                                                    + " result")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            });

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .sysPrompt("You are a test agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .build();

            // Add pending tool call
            Msg toolCallMsg =
                    Msg.builder()
                            .name("TestAgent")
                            .role(MsgRole.ASSISTANT)
                            .content(
                                    ToolUseBlock.builder()
                                            .id("call_user_provided")
                                            .name("simple_tool")
                                            .input(Map.of())
                                            .build())
                            .build();
            memory.addMessage(toolCallMsg);

            // User provides tool result
            Msg userProvidedResult =
                    Msg.builder()
                            .name("tool")
                            .role(MsgRole.TOOL)
                            .content(
                                    ToolResultBlock.builder()
                                            .id("call_user_provided")
                                            .name("simple_tool")
                                            .output(
                                                    TextBlock.builder()
                                                            .text("User provided result")
                                                            .build())
                                            .build())
                            .build();

            Msg response =
                    agent.call(userProvidedResult)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response, "Response should not be null");

            // Verify no auto-generated error (user result was used)
            boolean hasAutoGeneratedError =
                    memory.getMessages().stream()
                            .anyMatch(
                                    m -> {
                                        if (m.hasContentBlocks(ToolResultBlock.class)) {
                                            ToolResultBlock trb =
                                                    m.getFirstContentBlock(ToolResultBlock.class);
                                            if (trb != null
                                                    && !trb.getOutput().isEmpty()
                                                    && trb.getOutput().get(0)
                                                            instanceof TextBlock tb) {
                                                return tb.getText().contains("[ERROR]")
                                                        && tb.getText()
                                                                .contains(
                                                                        "failed or was"
                                                                                + " interrupted");
                                            }
                                        }
                                        return false;
                                    });

            assertFalse(
                    hasAutoGeneratedError,
                    "Should not have auto-generated error when user provides valid result");

            // Verify user-provided result is in memory
            boolean hasUserResult =
                    memory.getMessages().stream()
                            .anyMatch(
                                    m -> {
                                        if (m.hasContentBlocks(ToolResultBlock.class)) {
                                            ToolResultBlock trb =
                                                    m.getFirstContentBlock(ToolResultBlock.class);
                                            if (trb != null
                                                    && !trb.getOutput().isEmpty()
                                                    && trb.getOutput().get(0)
                                                            instanceof TextBlock tb) {
                                                return tb.getText().equals("User provided result");
                                            }
                                        }
                                        return false;
                                    });

            assertTrue(hasUserResult, "Memory should contain user-provided tool result");
        }
    }

    @Nested
    @DisplayName("Integration: End-to-End Error Recovery Scenarios")
    class IntegrationErrorRecoveryTest {

        @Test
        @DisplayName("Should recover from tool failure and continue conversation")
        void testRecoveryAndContinuedConversation() {
            AtomicInteger callCount = new AtomicInteger(0);

            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(createExceptionThrowingTool("failing_tool", "Network error"));
            toolkit.registerTool(createSimpleTool("working_tool", "Success"));

            MockModel mockModel =
                    new MockModel(
                            messages -> {
                                int call = callCount.getAndIncrement();
                                switch (call) {
                                    case 0:
                                        // First: try failing tool
                                        return List.of(
                                                createToolCallResponse("failing_tool", "call_1"));
                                    case 1:
                                        // Second: model receives error, tries working tool
                                        return List.of(
                                                createToolCallResponse("working_tool", "call_2"));
                                    default:
                                        // Final response
                                        return List.of(
                                                ChatResponse.builder()
                                                        .content(
                                                                List.of(
                                                                        TextBlock.builder()
                                                                                .text(
                                                                                        "Task completed"
                                                                                            + " with"
                                                                                            + " working"
                                                                                            + " tool")
                                                                                .build()))
                                                        .usage(new ChatUsage(10, 20, 30))
                                                        .build());
                                }
                            });

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .sysPrompt("You are a test agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .maxIters(5)
                            .build();

            Msg response =
                    agent.call(TestUtils.createUserMessage("User", "Complete the task"))
                            .block(Duration.ofMillis(TestConstants.LONG_TEST_TIMEOUT_MS));

            assertNotNull(response, "Response should not be null");

            // Verify both tools were attempted
            List<Msg> messages = memory.getMessages();
            long toolResultCount =
                    messages.stream()
                            .filter(m -> m.hasContentBlocks(ToolResultBlock.class))
                            .count();

            assertTrue(
                    toolResultCount >= 2, "Should have at least 2 tool results (error + success)");

            // Verify final response indicates success
            String responseText = TestUtils.extractTextContent(response);
            assertTrue(
                    responseText.contains("completed") || responseText.contains("working"),
                    "Response should indicate task completion");
        }

        @Test
        @DisplayName("Should handle partial tool execution failure (some succeed, some fail)")
        void testPartialToolExecutionFailure() {
            AtomicInteger callCount = new AtomicInteger(0);

            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(createSimpleTool("good_tool", "Good result"));
            toolkit.registerTool(createExceptionThrowingTool("bad_tool", "Tool crashed"));

            MockModel mockModel =
                    new MockModel(
                            messages -> {
                                int call = callCount.getAndIncrement();
                                if (call == 0) {
                                    // Request both tools at once
                                    return List.of(
                                            createToolCallResponse("good_tool", "call_good"),
                                            createToolCallResponse("bad_tool", "call_bad"));
                                }
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "One tool worked,"
                                                                                    + " one failed")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            });

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("TestAgent")
                            .sysPrompt("You are a test agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .build();

            Msg response =
                    agent.call(TestUtils.createUserMessage("User", "Use both tools"))
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response, "Response should not be null");

            // Verify memory contains both success and error results
            List<ToolResultBlock> allResults =
                    memory.getMessages().stream()
                            .filter(m -> m.hasContentBlocks(ToolResultBlock.class))
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .toList();

            boolean hasSuccess =
                    allResults.stream()
                            .anyMatch(
                                    trb -> {
                                        if (!trb.getOutput().isEmpty()
                                                && trb.getOutput().get(0) instanceof TextBlock tb) {
                                            return tb.getText().contains("Good result");
                                        }
                                        return false;
                                    });

            boolean hasError =
                    allResults.stream()
                            .anyMatch(
                                    trb -> {
                                        if (!trb.getOutput().isEmpty()
                                                && trb.getOutput().get(0) instanceof TextBlock tb) {
                                            return tb.getText().contains("Error:");
                                        }
                                        return false;
                                    });

            assertTrue(hasSuccess, "Should have successful tool result");
            assertTrue(hasError, "Should have error tool result");
        }
    }

    // ==================== Helper Methods ====================

    private static ChatResponse createToolCallResponse(String toolName, String toolCallId) {
        Map<String, Object> args = new HashMap<>();
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .name(toolName)
                                        .id(toolCallId)
                                        .input(args)
                                        .content(JsonUtils.getJsonCodec().toJson(args))
                                        .build()))
                .usage(new ChatUsage(8, 15, 23))
                .build();
    }

    private static AgentTool createSimpleTool(String name, String result) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "Simple test tool: " + name;
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");
                schema.put("properties", new HashMap<String, Object>());
                return schema;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.of(TextBlock.builder().text(result).build()));
            }
        };
    }

    private static AgentTool createExceptionThrowingTool(String name, String errorMessage) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "Exception throwing tool: " + name;
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");
                schema.put("properties", new HashMap<String, Object>());
                return schema;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.error(new RuntimeException(errorMessage));
            }
        };
    }

    private static AgentTool createSlowTool(String name, long delayMs) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "Slow tool: " + name;
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");
                schema.put("properties", new HashMap<String, Object>());
                return schema;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.delay(Duration.ofMillis(delayMs))
                        .then(
                                Mono.just(
                                        ToolResultBlock.of(
                                                TextBlock.builder().text("Slow result").build())));
            }
        };
    }
}
