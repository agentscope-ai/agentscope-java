/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
import static org.junit.jupiter.api.Assertions.fail;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.MockToolkit;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import java.time.Duration;
import java.util.List;
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
        // Setup model to call a tool
        mockModel =
                MockModel.withToolCall(
                        TestConstants.TEST_TOOL_NAME,
                        "tool_call_123",
                        TestUtils.createToolArguments("param1", "value1"));

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(mockModel)
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

        // Verify tool was called (check memory or toolkit)
        // Note: The actual verification depends on the implementation
        // For now, we just verify we got some response
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
        long userMessageCount =
                messages.stream()
                        .filter(m -> m.getRole() == io.agentscope.core.message.MsgRole.USER)
                        .count();
        assertEquals(
                1,
                userMessageCount,
                "Should still have only 1 user message (continuation doesn't add user input)");
    }

    // Helper method to create tool call response
    private static io.agentscope.core.model.ChatResponse createToolCallResponseHelper(
            String toolName, String toolCallId, java.util.Map<String, Object> arguments) {
        return io.agentscope.core.model.ChatResponse.builder()
                .content(
                        java.util.List.of(
                                io.agentscope.core.message.ToolUseBlock.builder()
                                        .name(toolName)
                                        .id(toolCallId)
                                        .input(arguments)
                                        .build()))
                .usage(new io.agentscope.core.model.ChatUsage(8, 15, 23))
                .build();
    }
}
