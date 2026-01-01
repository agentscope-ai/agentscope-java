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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.MockToolkit;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ReActAgent's summarizing functionality.
 *
 * <p>Tests cover:
 * - Summarizing when max iterations is reached
 * - Summary includes context from memory
 * - Summary message is added to memory
 * - Proper handling of the hint message
 */
@DisplayName("ReActAgent Summarizing Tests")
class ReActAgentSummarizingTest {

    @Test
    @DisplayName("Should generate summary when max iterations reached")
    void testSummarizingOnMaxIterations() {
        InMemoryMemory memory = new InMemoryMemory();

        // Create model that returns plain text (not calling generate_response tool)
        // This causes the agent to continue iteration without finishing
        final int[] callCount = {0};
        MockModel mockModel =
                new MockModel(
                        messages -> {
                            int callNum = callCount[0]++;
                            if (callNum < 2) {
                                // First two calls: return plain text without calling finish tool
                                // This will not satisfy isFinished(), forcing more iterations
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_" + callNum)
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "I'm thinking about"
                                                                                    + " your"
                                                                                    + " request...")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                // Third call (summarizing): return final text
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_summary")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "I attempted to"
                                                                                    + " process"
                                                                                    + " your"
                                                                                    + " request but"
                                                                                    + " reached the"
                                                                                    + " maximum"
                                                                                    + " iteration"
                                                                                    + " limit.")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                        });

        MockToolkit mockToolkit = new MockToolkit();

        // Create agent with maxIters=2 for quick test
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("You are a test assistant.")
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .memory(memory)
                        .maxIters(2) // Small number to reach limit quickly
                        .build();

        // Create user message
        Msg userMsg = TestUtils.createUserMessage("User", "Please help me with a task");

        // Call agent - should reach maxIters and trigger summarizing
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify response is not null
        assertNotNull(response, "Response should not be null");

        // Verify response is from assistant
        assertEquals(MsgRole.ASSISTANT, response.getRole(), "Response should be from assistant");

        // Verify response has content (summary)
        assertNotNull(response.getContent(), "Response should have content");
        assertNotNull(response.getFirstContentBlock(), "Response should have a content block");

        // Verify it's a text block
        assertTrue(
                response.getFirstContentBlock() instanceof TextBlock,
                "Response should contain TextBlock");

        TextBlock textBlock = (TextBlock) response.getFirstContentBlock();
        String summaryText = textBlock.getText();

        // Verify summary text is not empty and is more than just an error message
        assertNotNull(summaryText, "Summary text should not be null");
        assertTrue(summaryText.length() > 10, "Summary should be substantial");

        // Verify memory contains the summary
        List<Msg> memoryMessages = agent.getMemory().getMessages();
        assertTrue(memoryMessages.contains(response), "Memory should contain summary message");
    }

    @Test
    @DisplayName("Should include context from memory in summary")
    void testSummarizingIncludesMemoryContext() {
        InMemoryMemory memory = new InMemoryMemory();

        // Pre-populate memory with some context
        Msg contextMsg1 =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What is the weather today?").build())
                        .build();
        memory.addMessage(contextMsg1);

        Msg contextMsg2 =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("I don't have that information.").build())
                        .build();
        memory.addMessage(contextMsg2);

        // Create model that captures the messages sent to it
        final List<Msg>[] capturedMessages = new List[] {null};
        final int[] callCount = {0};
        MockModel mockModel =
                new MockModel(
                        messages -> {
                            int callNum = callCount[0]++;
                            if (callNum == 0) {
                                // First call: return tool call to force maxIters
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_0")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .name("test_tool")
                                                                        .id("tool_0")
                                                                        .input(Map.of())
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                // Second call: summarizing - capture messages
                                capturedMessages[0] = messages;
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_summary")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "Based on our"
                                                                                    + " conversation,"
                                                                                    + " you asked"
                                                                                    + " about the"
                                                                                    + " weather but"
                                                                                    + " I don't"
                                                                                    + " have access"
                                                                                    + " to that"
                                                                                    + " information.")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                        });

        MockToolkit mockToolkit = new MockToolkit();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .memory(memory)
                        .maxIters(1)
                        .build();

        // Create user message that will trigger maxIters immediately
        Msg userMsg = TestUtils.createUserMessage("User", "Can you help?");

        // Call agent
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify response
        assertNotNull(response, "Response should not be null");

        // Verify the model received the context messages
        assertNotNull(capturedMessages[0], "Model should have received messages");

        // The messages should include: system prompt, pre-existing context, user msg, hint msg
        // At minimum we expect: system + 2 context + user + hint = 5 messages
        assertTrue(
                capturedMessages[0].size() >= 5,
                "Model should receive system prompt, context, user msg, and hint");

        // Verify hint message is included (should be the last message)
        Msg lastMsg = capturedMessages[0].get(capturedMessages[0].size() - 1);
        assertEquals(MsgRole.USER, lastMsg.getRole(), "Hint message should be USER role");
        String hintText = ((TextBlock) lastMsg.getFirstContentBlock()).getText();
        assertTrue(
                hintText.contains("failed to generate response"),
                "Hint message should contain expected text");
        assertTrue(hintText.contains("summarizing"), "Hint message should mention summarizing");
    }

    @Test
    @DisplayName("Should handle summarizing with empty memory")
    void testSummarizingWithEmptyMemory() {
        InMemoryMemory memory = new InMemoryMemory();

        final int[] callCount = {0};
        MockModel mockModel =
                new MockModel(
                        messages -> {
                            int callNum = callCount[0]++;
                            if (callNum == 0) {
                                // Force maxIters
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_0")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .name("test_tool")
                                                                        .id("tool_0")
                                                                        .input(Map.of())
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                // Return summary
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_summary")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "I was unable to"
                                                                                    + " complete"
                                                                                    + " the task"
                                                                                    + " within the"
                                                                                    + " iteration"
                                                                                    + " limit.")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                        });

        MockToolkit mockToolkit = new MockToolkit();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("You are a test assistant.")
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .memory(memory)
                        .maxIters(1)
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", "Help please");

        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");
        assertEquals(MsgRole.ASSISTANT, response.getRole(), "Response should be from assistant");
        assertTrue(
                response.getFirstContentBlock() instanceof TextBlock,
                "Response should be TextBlock");
    }

    @Test
    @DisplayName("Should add summary message to memory")
    void testSummaryAddedToMemory() {
        InMemoryMemory memory = new InMemoryMemory();

        final int[] callCount = {0};
        MockModel mockModel =
                new MockModel(
                        messages -> {
                            int callNum = callCount[0]++;
                            if (callNum == 0) {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_0")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .name("test_tool")
                                                                        .id("tool_0")
                                                                        .input(Map.of())
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_summary")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "This is the"
                                                                                    + " summary of"
                                                                                    + " what"
                                                                                    + " happened.")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                        });

        MockToolkit mockToolkit = new MockToolkit();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .memory(memory)
                        .maxIters(1)
                        .build();

        // Check initial memory size
        int initialMemorySize = memory.getMessages().size();

        Msg userMsg = TestUtils.createUserMessage("User", "Please help");
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // Verify memory has grown
        int finalMemorySize = memory.getMessages().size();
        assertTrue(
                finalMemorySize > initialMemorySize,
                "Memory should contain additional messages after summarizing");

        // Verify the last message is the summary
        Msg lastMessage = memory.getMessages().get(finalMemorySize - 1);
        assertEquals(response, lastMessage, "Last message in memory should be the summary");
        assertEquals(
                MsgRole.ASSISTANT, lastMessage.getRole(), "Summary message should be ASSISTANT");
    }
}
