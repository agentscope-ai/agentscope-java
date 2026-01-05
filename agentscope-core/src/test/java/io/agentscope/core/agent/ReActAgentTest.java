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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.MockToolkit;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ReActAgent class.
 *
 * Tests cover:
 * - Basic agent initialization
 * - Reply generation with simple text responses
 * - ReAct loop with reasoning and acting
 * - Tool calling capabilities
 * - Memory management
 * - Streaming support
 * - Error handling
 * - Max iterations limiting
 */
@DisplayName("ReActAgent Tests")
class ReActAgentTest {

    private ReActAgent agent;
    private MockModel mockModel;
    private MockToolkit mockToolkit;
    private InMemoryMemory memory;

    @BeforeEach
    void setUp() {
        memory = new InMemoryMemory();
        mockModel = new MockModel(TestConstants.MOCK_MODEL_SIMPLE_RESPONSE);
        mockToolkit = new MockToolkit();

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .memory(memory)
                        .build();
    }

    @Test
    @DisplayName("Should initialize ReActAgent with correct properties")
    void testInitialization() {
        // Verify agent properties
        assertNotNull(agent.getAgentId(), "Agent ID should not be null");
        assertEquals(
                TestConstants.TEST_REACT_AGENT_NAME, agent.getName(), "Agent name should match");
        assertEquals(memory, agent.getMemory(), "Memory should be the same instance");
        assertEquals(
                TestConstants.DEFAULT_MAX_ITERS,
                agent.getMaxIters(),
                "Default max iterations should be 10");

        // Verify memory is initially empty
        assertTrue(agent.getMemory().getMessages().isEmpty(), "Memory should be empty initially");
    }

    @Test
    @DisplayName("Should generate simple text response")
    void testSimpleReply() {
        // Create user message
        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);

        // Get response
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify response
        assertNotNull(response, "Response should not be null");

        assertEquals(
                TestConstants.TEST_REACT_AGENT_NAME,
                response.getName(),
                "Response should be from the agent");

        String text = TestUtils.extractTextContent(response);
        assertNotNull(text, "Response text should not be null");
        assertFalse(text.isEmpty(), "Response text should not be empty");

        // Verify memory was updated
        List<Msg> messages = agent.getMemory().getMessages();
        assertTrue(messages.size() >= 1, "Memory should contain at least the user message");

        // Verify model was called
        assertEquals(1, mockModel.getCallCount(), "Model should be called once");
    }

    @Test
    @DisplayName("Should handle thinking and final response")
    void testThinkingResponse() {
        // Setup model with thinking
        mockModel =
                MockModel.withThinking(
                        TestConstants.MOCK_MODEL_THINKING_RESPONSE,
                        TestConstants.MOCK_MODEL_FINAL_RESPONSE);

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .memory(memory)
                        .build();

        // Create user message
        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);

        // Get response
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify we got response
        assertNotNull(response, "Response should not be null");

        // Verify response contains thinking or text
        boolean hasContent =
                TestUtils.isThinkingMessage(response) || TestUtils.isTextMessage(response);
        assertTrue(hasContent, "Should have thinking or text content");
    }

    @Test
    @DisplayName("Should call tools when requested by model")
    void testToolCalling() {
        // Use a mutable reference to track call count
        final int[] callCount = {0};

        // Setup model to call a tool then finish
        MockModel toolModel =
                new MockModel(
                        messages -> {
                            int currentCall = callCount[0]++;
                            if (currentCall == 0) {
                                // First call: return tool call
                                return List.of(
                                        createToolCallResponseHelper(
                                                TestConstants.TEST_TOOL_NAME,
                                                "tool_call_123",
                                                TestUtils.createToolArguments("param1", "value1")));
                            }
                            // Second call: return text response (finish)
                            return List.of(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text(
                                                                            "Tool executed"
                                                                                + " successfully")
                                                                    .build()))
                                            .usage(new ChatUsage(10, 20, 30))
                                            .build());
                        });

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(toolModel)
                        .toolkit(mockToolkit)
                        .memory(memory)
                        .build();

        // Create user message
        Msg userMsg = TestUtils.createUserMessage("User", "Please use the test tool");

        // Get response
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify response
        assertNotNull(response, "Response should not be null");

        // Verify tool was called via toolkit
        assertTrue(
                mockToolkit.wasToolCalled(TestConstants.TEST_TOOL_NAME), "Tool should be called");
        assertEquals(1, mockToolkit.getCallCount(), "Tool should be called once");

        // Verify memory contains tool result
        List<Msg> messages = agent.getMemory().getMessages();
        boolean hasToolResult =
                messages.stream().anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class));
        assertTrue(hasToolResult, "Memory should contain tool result");
    }

    @Test
    @DisplayName("Should execute multiple tools in acting phase")
    void testMultipleToolExecution() {
        // Use a mutable reference to track call count
        final int[] callCount = {0};

        // Setup model to call two tools sequentially
        MockModel toolModel =
                new MockModel(
                        messages -> {
                            int callNum = callCount[0]++;
                            if (callNum == 0) {
                                // First reasoning: call calculator
                                Map<String, Object> calcArgs = new HashMap<>();
                                calcArgs.put("operation", "add");
                                calcArgs.put("a", 5);
                                calcArgs.put("b", 3);
                                return List.of(
                                        createToolCallResponseHelper(
                                                TestConstants.CALCULATOR_TOOL_NAME,
                                                "tool_call_1",
                                                calcArgs));
                            } else if (callNum == 1) {
                                // Second reasoning: call test tool
                                return List.of(
                                        createToolCallResponseHelper(
                                                TestConstants.TEST_TOOL_NAME,
                                                "tool_call_2",
                                                TestUtils.createToolArguments()));
                            } else {
                                // Final response
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("All tools executed")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                        });

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(toolModel)
                        .toolkit(mockToolkit)
                        .memory(memory)
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", "Execute tools");
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");

        // Verify both tools were called
        assertTrue(
                mockToolkit.wasToolCalled(TestConstants.CALCULATOR_TOOL_NAME),
                "Calculator tool should be called");
        assertTrue(
                mockToolkit.wasToolCalled(TestConstants.TEST_TOOL_NAME),
                "Test tool should be called");
        assertEquals(2, mockToolkit.getCallCount(), "Two tools should be called");

        // Verify tool call order
        List<String> history = mockToolkit.getToolCallHistory();
        assertEquals(
                TestConstants.CALCULATOR_TOOL_NAME,
                history.get(0),
                "Calculator should be called first");
        assertEquals(
                TestConstants.TEST_TOOL_NAME, history.get(1), "Test tool should be called second");
    }

    @Test
    @DisplayName("Should handle tool execution errors in acting phase")
    void testToolExecutionError() {
        // Register a tool that throws an error
        mockToolkit.withErrorTool("failing_tool", "Tool execution failed");

        final int[] callCount = {0};
        mockModel =
                new MockModel(
                        messages -> {
                            int currentCall = callCount[0]++;
                            if (currentCall == 0) {
                                // Call the failing tool
                                return List.of(
                                        createToolCallResponseHelper(
                                                "failing_tool",
                                                "tool_call_error",
                                                TestUtils.createToolArguments()));
                            } else {
                                // Model should handle the error and provide a response
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Handled tool error")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                        });

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .memory(memory)
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", "Call failing tool");
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");

        // Verify tool was attempted
        assertTrue(mockToolkit.wasToolCalled("failing_tool"), "Failing tool should be called");

        // Verify memory contains tool result with error content
        List<Msg> messages = agent.getMemory().getMessages();
        boolean hasErrorToolResult =
                messages.stream()
                        .anyMatch(
                                m -> {
                                    ToolResultBlock trb =
                                            m.getFirstContentBlock(ToolResultBlock.class);
                                    if (trb != null && !trb.getOutput().isEmpty()) {
                                        // Check if output contains error text
                                        if (trb.getOutput().get(0) instanceof TextBlock tb) {
                                            return tb.getText().contains("Error:");
                                        }
                                    }
                                    return false;
                                });
        assertTrue(hasErrorToolResult, "Memory should contain error tool result");
    }

    @Test
    @DisplayName("Should save tool results to memory during acting phase")
    void testToolResultsInMemory() {
        final int[] callCount = {0};
        mockModel =
                new MockModel(
                        messages -> {
                            int currentCall = callCount[0]++;
                            if (currentCall == 0) {
                                Map<String, Object> calcArgs = new HashMap<>();
                                calcArgs.put("operation", "multiply");
                                calcArgs.put("a", 4);
                                calcArgs.put("b", 7);
                                return List.of(
                                        createToolCallResponseHelper(
                                                TestConstants.CALCULATOR_TOOL_NAME,
                                                "calc_123",
                                                calcArgs));
                            } else {
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Result is 28")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                        });

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .memory(memory)
                        .build();

        int initialMemorySize = memory.getMessages().size();

        Msg userMsg = TestUtils.createUserMessage("User", "Calculate 4 * 7");
        agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        List<Msg> messages = memory.getMessages();

        // Memory should contain: user message, assistant tool call, tool result, final response
        assertTrue(
                messages.size() > initialMemorySize + 1, "Memory should contain multiple messages");

        // Find tool result message
        Msg toolResultMsg =
                messages.stream()
                        .filter(m -> m.hasContentBlocks(ToolResultBlock.class))
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolResultMsg, "Tool result message should be in memory");

        ToolResultBlock toolResult = toolResultMsg.getFirstContentBlock(ToolResultBlock.class);
        assertEquals("calc_123", toolResult.getId(), "Tool call ID should match");
        // Verify tool executed successfully by checking output doesn't contain error
        for (ContentBlock output : toolResult.getOutput()) {
            if (output instanceof TextBlock tb) {
                assertFalse(tb.getText().startsWith("Error:"), "Tool should execute successfully");
            }
        }
    }

    @Test
    @DisplayName("Should handle max iterations limit")
    void testMaxIterations() {
        // Setup model to always return tool calls (infinite loop scenario)
        MockModel loopModel =
                new MockModel(
                        messages -> {
                            return List.of(
                                    createToolCallResponseHelper(
                                            TestConstants.TEST_TOOL_NAME,
                                            "tool_call_" + System.nanoTime(),
                                            TestUtils.createToolArguments()));
                        });

        // Setup agent with low max iterations using builder
        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(loopModel)
                        .toolkit(mockToolkit)
                        .memory(memory)
                        .maxIters(TestConstants.TEST_MAX_ITERS)
                        .build();
        mockModel = loopModel;

        // Create user message
        Msg userMsg = TestUtils.createUserMessage("User", "Start the loop");

        // Get response with timeout
        // Verify it completes within reasonable time (not infinite loop)
        agent.call(userMsg)
                .timeout(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS))
                .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify max iterations was respected
        assertTrue(
                mockModel.getCallCount() <= TestConstants.TEST_MAX_ITERS + 1,
                "Model calls should not exceed max iterations");
    }

    @Test
    @DisplayName("Should maintain conversation history in memory")
    void testMemoryManagement() {
        // Send first message
        Msg msg1 = TestUtils.createUserMessage("User", "First message");
        agent.call(msg1).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        int sizeAfterFirst = agent.getMemory().getMessages().size();
        assertTrue(sizeAfterFirst >= 1, "Memory should contain at least the first message");

        // Send second message
        Msg msg2 = TestUtils.createUserMessage("User", "Second message");
        agent.call(msg2).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        int sizeAfterSecond = agent.getMemory().getMessages().size();
        assertTrue(sizeAfterSecond > sizeAfterFirst, "Memory should grow with more messages");

        // Verify both messages are in history
        List<Msg> allMessages = agent.getMemory().getMessages();
        assertTrue(
                allMessages.stream()
                        .anyMatch(m -> TestUtils.extractTextContent(m).contains("First message")),
                "First message should be in memory");
        assertTrue(
                allMessages.stream()
                        .anyMatch(m -> TestUtils.extractTextContent(m).contains("Second message")),
                "Second message should be in memory");
    }

    @Test
    @DisplayName("Should handle model errors gracefully")
    void testErrorHandling() {
        // Setup model to throw error
        String errorMessage = "Mock model error";
        mockModel = new MockModel("").withError(errorMessage);

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .memory(memory)
                        .build();

        // Create user message
        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);

        // Get response
        // Verify error is propagated
        try {
            agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
            fail("Should have thrown an exception");
        } catch (Exception e) {
            assertTrue(
                    e.getMessage().contains(errorMessage)
                            || (e.getCause() != null
                                    && e.getCause().getMessage().contains(errorMessage)),
                    "Exception should contain error message");
        }
    }

    @Test
    @DisplayName("Should support streaming responses")
    void testStreaming() {
        // Setup model with multiple response chunks
        mockModel = new MockModel(List.of("First chunk", "Second chunk", "Third chunk"));

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .memory(memory)
                        .build();

        // Create user message
        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);

        // Get response
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");
    }

    @Test
    @DisplayName("Should continue generation based on current memory without new input")
    void testContinueGeneration() {
        // Setup: Add some messages to memory first
        Msg userMsg = TestUtils.createUserMessage("User", "Tell me a story");
        agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        int initialCallCount = mockModel.getCallCount();
        int initialMemorySize = agent.getMemory().getMessages().size();

        // Call without parameters to continue generation
        Msg continueResponse =
                agent.call().block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify response
        assertNotNull(continueResponse, "Continue response should not be null");
        assertEquals(
                TestConstants.TEST_REACT_AGENT_NAME,
                continueResponse.getName(),
                "Response should be from the agent");

        // Verify model was called again
        assertTrue(
                mockModel.getCallCount() > initialCallCount,
                "Model should be called again for continuation");

        // Verify memory was updated with the new response (but no new user message was added)
        assertTrue(
                agent.getMemory().getMessages().size() > initialMemorySize,
                "Memory should contain the continuation response");

        // Verify no new user message was added (only agent responses)
        List<Msg> messages = agent.getMemory().getMessages();
        long userMessageCount = messages.stream().filter(m -> m.getRole() == MsgRole.USER).count();
        assertEquals(
                1,
                userMessageCount,
                "Should still have only 1 user message (continuation doesn't add user input)");
    }

    @Test
    @DisplayName("Should return correct values from getter methods")
    void testGetters() {
        // Verify all getter methods return expected values
        assertNotNull(agent.getSysPrompt(), "System prompt should not be null");
        assertEquals(TestConstants.DEFAULT_SYS_PROMPT, agent.getSysPrompt());

        assertNotNull(agent.getModel(), "Model should not be null");
        assertEquals(mockModel, agent.getModel());

        assertNotNull(agent.getToolkit(), "Toolkit should not be null");
        // Agent uses a copy of toolkit for state isolation, so we verify it's a Toolkit instance
        assertInstanceOf(Toolkit.class, agent.getToolkit());

        assertEquals(TestConstants.DEFAULT_MAX_ITERS, agent.getMaxIters());
    }

    @Test
    @DisplayName("Should have interrupt API methods")
    void testInterruptAfterToolCompletion() {
        // Verify ReActAgent inherits interrupt API from AgentBase
        assertNotNull(agent.getInterruptFlag(), "Should have interrupt flag");
        assertFalse(agent.getInterruptFlag().get(), "Interrupt flag should be false initially");

        // Test interrupt() method
        agent.interrupt();
        assertTrue(agent.getInterruptFlag().get(), "Interrupt flag should be set");
    }

    @Test
    @DisplayName("Should support interrupt with message")
    void testInterruptRecoveryMessage() {
        Msg interruptMsg = TestUtils.createUserMessage("User", "Stop processing");

        // Test interrupt(Msg) method
        agent.interrupt(interruptMsg);
        assertTrue(agent.getInterruptFlag().get(), "Interrupt flag should be set");

        // Note: The interrupt message is stored but only added to memory during handleInterrupt
        // This test just verifies the API accepts the message and sets the flag
    }

    @Test
    @DisplayName("Should not generate fake tool results after simplification")
    void testNoFakeToolResults() {
        // This test verifies that after PR#15, the ToolResultBlock.interrupted() method
        // was removed and ReActAgent no longer generates fake interrupted results.
        // We verify this by checking that the method doesn't exist in ToolResultBlock.

        // Verify ToolResultBlock class doesn't have interrupted() method
        boolean hasInterruptedMethod =
                Arrays.stream(ToolResultBlock.class.getMethods())
                        .anyMatch(
                                m ->
                                        m.getName().equals("interrupted")
                                                && m.getParameterCount() == 0);

        assertFalse(
                hasInterruptedMethod,
                "ToolResultBlock should not have interrupted() method after simplification");
    }

    @Test
    @DisplayName("Should use normal tool results not fake ones")
    void testAllToolResultsSaved() {
        // Verify that ReActAgent uses the real toolkit and doesn't inject fake results
        // This is verified by checking that the agent has a toolkit configured

        assertNotNull(agent.getToolkit(), "Agent should have a toolkit configured");

        // Verify toolkit can be called (basic sanity check)
        assertNotNull(
                agent.getToolkit().getTool(TestConstants.CALCULATOR_TOOL_NAME),
                "Toolkit should have calculator tool");
        assertNotNull(
                agent.getToolkit().getTool(TestConstants.TEST_TOOL_NAME),
                "Toolkit should have test tool");
    }

    // Helper method to create tool call response
    private static ChatResponse createToolCallResponseHelper(
            String toolName, String toolCallId, Map<String, Object> arguments) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .name(toolName)
                                        .id(toolCallId)
                                        .input(arguments)
                                        .build()))
                .usage(new ChatUsage(8, 15, 23))
                .build();
    }
}
