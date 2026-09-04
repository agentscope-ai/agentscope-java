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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for handling provider server tools (e.g. Anthropic web_search) in the ReAct loop.
 *
 * <p>Server tool calls are executed on the provider's infrastructure and their results arrive in
 * the same assistant message. The agent must not dispatch them to the local toolkit, and a message
 * whose tool calls are all completed server tools should finish the loop.
 */
@DisplayName("ReActAgent Server Tool Tests")
class ReActAgentServerToolTest {

    private static ToolUseBlock serverToolUse(String id) {
        return ToolUseBlock.builder()
                .id(id)
                .name("web_search")
                .input(Map.of("query", "AgentScope"))
                .metadata(Map.of(ToolUseBlock.METADATA_SERVER_TOOL, true))
                .build();
    }

    private static ToolResultBlock serverToolResult(String id) {
        return ToolResultBlock.builder()
                .id(id)
                .name("web_search")
                .output(TextBlock.builder().text("AgentScope docs (https://example.com)").build())
                .metadata(Map.of(ToolResultBlock.METADATA_SERVER_TOOL, true))
                .state(ToolResultState.SUCCESS)
                .build();
    }

    @Test
    @DisplayName("Should finish without executing server tool when result is inline")
    void testServerToolWithInlineResultFinishes() {
        MockModel mockModel =
                new MockModel(
                        messages ->
                                List.of(
                                        ChatResponse.builder()
                                                .id("msg_server_tool")
                                                .content(
                                                        List.of(
                                                                serverToolUse("srvtoolu_01"),
                                                                serverToolResult("srvtoolu_01"),
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "Based on the"
                                                                                    + " search...")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build()));

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("You are a test assistant.")
                        .model(mockModel)
                        .toolkit(new MockToolkit())
                        .maxIters(3)
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", "Search for AgentScope");
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");
        assertEquals(MsgRole.ASSISTANT, response.getRole());
        // The loop must finish after a single model call: the server tool has its result
        // inline, so there is nothing to execute locally.
        assertEquals(1, mockModel.getCallCount(), "Model should be called exactly once");

        // The final assistant message keeps the server tool call and its result
        List<ToolUseBlock> toolUses = response.getContentBlocks(ToolUseBlock.class);
        assertEquals(1, toolUses.size());
        assertTrue(toolUses.get(0).isServerTool());

        List<ToolResultBlock> toolResults = response.getContentBlocks(ToolResultBlock.class);
        assertEquals(1, toolResults.size());
        assertTrue(toolResults.get(0).isServerTool());
        assertEquals("srvtoolu_01", toolResults.get(0).getId());

        // No TOOL-role message should have been added for the server tool
        boolean hasToolRoleMsg =
                agent.getAgentState().getContext().stream()
                        .anyMatch(m -> m.getRole() == MsgRole.TOOL);
        assertTrue(!hasToolRoleMsg, "Server tools must not produce local tool execution messages");
    }

    @Test
    @DisplayName("Should keep looping when server tool call has no result yet (pause_turn)")
    void testServerToolWithoutResultContinuesLoop() {
        final int[] callCount = {0};
        MockModel mockModel =
                new MockModel(
                        messages -> {
                            if (callCount[0]++ == 0) {
                                // First round: server tool call without result (pause_turn)
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_paused")
                                                .content(List.of(serverToolUse("srvtoolu_02")))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                            // Second round: provider completes with the final answer
                            return List.of(
                                    ChatResponse.builder()
                                            .id("msg_final")
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("Final answer")
                                                                    .build()))
                                            .usage(new ChatUsage(10, 20, 30))
                                            .build());
                        });

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("You are a test assistant.")
                        .model(mockModel)
                        .toolkit(new MockToolkit())
                        .maxIters(3)
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", "Search for AgentScope");
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");
        // The unfinished server tool call must send the conversation back to the model
        // instead of being executed locally.
        assertEquals(2, mockModel.getCallCount(), "Model should be called twice");
        assertEquals("Final answer", TestUtils.extractTextContent(response));
    }
}
