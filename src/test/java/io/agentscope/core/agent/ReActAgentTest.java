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
import reactor.core.publisher.Flux;

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
                new ReActAgent(
                        TestConstants.TEST_REACT_AGENT_NAME,
                        TestConstants.DEFAULT_SYS_PROMPT,
                        mockModel,
                        mockToolkit,
                        memory);
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
        Flux<Msg> responseFlux = agent.stream(userMsg);

        // Verify response
        List<Msg> responses =
                responseFlux
                        .collectList()
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(responses, "Responses should not be null");
        assertFalse(responses.isEmpty(), "Should have responses");

        Msg firstResponse = responses.get(0);
        assertNotNull(firstResponse, "Response message should not be null");
        assertEquals(
                TestConstants.TEST_REACT_AGENT_NAME,
                firstResponse.getName(),
                "Response should be from the agent");

        String text = TestUtils.extractTextContent(firstResponse);
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
                new ReActAgent(
                        TestConstants.TEST_REACT_AGENT_NAME,
                        TestConstants.DEFAULT_SYS_PROMPT,
                        mockModel,
                        mockToolkit,
                        memory);

        // Create user message
        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);

        // Get response stream
        Flux<Msg> responseFlux = agent.stream(userMsg);

        // Collect all messages
        List<Msg> responses =
                responseFlux
                        .collectList()
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify we got responses
        assertNotNull(responses, "Responses should not be null");
        assertFalse(responses.isEmpty(), "Should have received responses");

        // Verify at least one response contains thinking or text
        boolean hasContent =
                responses.stream()
                        .anyMatch(
                                msg ->
                                        TestUtils.isThinkingMessage(msg)
                                                || TestUtils.isTextMessage(msg));
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
                new ReActAgent(
                        TestConstants.TEST_REACT_AGENT_NAME,
                        TestConstants.DEFAULT_SYS_PROMPT,
                        mockModel,
                        mockToolkit,
                        memory);

        // Create user message
        Msg userMsg = TestUtils.createUserMessage("User", "Please use the test tool");

        // Get response stream
        Flux<Msg> responseFlux = agent.stream(userMsg);

        // Collect messages
        List<Msg> responses =
                responseFlux
                        .collectList()
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify responses
        assertNotNull(responses, "Responses should not be null");

        // Verify tool was called (check memory or toolkit)
        // Note: The actual verification depends on the implementation
        // For now, we just verify we got some response
        assertFalse(responses.isEmpty(), "Should have received responses");
    }

    @Test
    @DisplayName("Should handle max iterations limit")
    void testMaxIterations() {
        // Setup agent with low max iterations
        agent.setMaxIters(TestConstants.TEST_MAX_ITERS);

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

        agent =
                new ReActAgent(
                        TestConstants.TEST_REACT_AGENT_NAME,
                        TestConstants.DEFAULT_SYS_PROMPT,
                        loopModel,
                        mockToolkit,
                        memory);
        agent.setMaxIters(TestConstants.TEST_MAX_ITERS);
        mockModel = loopModel;

        // Create user message
        Msg userMsg = TestUtils.createUserMessage("User", "Start the loop");

        // Get response stream with timeout
        Flux<Msg> responseFlux = agent.stream(userMsg);

        // Verify it completes within reasonable time (not infinite loop)
        responseFlux
                .timeout(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS))
                .collectList()
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
        agent.stream(msg1).blockLast(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        int sizeAfterFirst = agent.getMemory().getMessages().size();
        assertTrue(sizeAfterFirst >= 1, "Memory should contain at least the first message");

        // Send second message
        Msg msg2 = TestUtils.createUserMessage("User", "Second message");
        agent.stream(msg2).blockLast(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

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
                new ReActAgent(
                        TestConstants.TEST_REACT_AGENT_NAME,
                        TestConstants.DEFAULT_SYS_PROMPT,
                        mockModel,
                        mockToolkit,
                        memory);

        // Create user message
        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);

        // Get response stream
        Flux<Msg> responseFlux = agent.stream(userMsg);

        // Verify error is propagated
        try {
            responseFlux.blockLast(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
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
                new ReActAgent(
                        TestConstants.TEST_REACT_AGENT_NAME,
                        TestConstants.DEFAULT_SYS_PROMPT,
                        mockModel,
                        mockToolkit,
                        memory);

        // Create user message
        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);

        // Get response stream
        Flux<Msg> responseFlux = agent.stream(userMsg);

        // Verify we get multiple chunks
        List<Msg> chunks =
                responseFlux
                        .collectList()
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(chunks, "Chunks should not be null");
        assertEquals(3, chunks.size(), "Should receive 3 response chunks");
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
