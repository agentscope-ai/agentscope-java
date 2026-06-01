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
package io.agentscope.core.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Tests for Issue #1555 fix: PendingToolRecoveryHook should only check for
 * ToolResultBlocks matching pending tool call IDs, not any historical ones.
 */
@DisplayName("Issue #1555: PendingToolRecoveryHook multi-turn fix")
class PendingToolRecoveryMultiTurnTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    private Model mockModel;
    private Toolkit toolkit;
    private Memory memory;

    @BeforeEach
    void setUp() {
        mockModel = mock(Model.class);
        when(mockModel.getModelName()).thenReturn("mock-model");
        toolkit = new Toolkit();
        toolkit.registerTool(new TestTool(new AtomicBoolean(false)));
        memory = new InMemoryMemory();
    }

    // ==================== A. Core Bug Fix ====================

    @Nested
    @DisplayName("A. Core Bug: multi-turn historical ToolResultBlock false positive")
    class CoreBugTests {

        @Test
        @DisplayName(
                "Multi-turn: plain text after history with ToolResultBlock should auto-recover")
        void testMultiTurnRecoveryWithHistoricalToolResults() {
            Msg turn1ToolCall = createToolUseMsg("call_1", "test_tool");
            Msg turn1FinalText = createAssistantTextMsg("Turn 1 done");
            Msg turn2ToolCall = createToolUseMsg("call_2", "test_tool");
            Msg turn3Recovery = createAssistantTextMsg("Recovered");

            when(mockModel.stream(anyList(), anyList(), any()))
                    .thenReturn(createFluxFromMsg(turn1ToolCall))
                    .thenReturn(createFluxFromMsg(turn1FinalText))
                    .thenReturn(createFluxFromMsg(turn2ToolCall))
                    .thenReturn(createFluxFromMsg(turn3Recovery));

            AtomicInteger reasoningCount = new AtomicInteger(0);
            Hook stopOnTurn2 = createConditionalStopHook(reasoningCount, 3);

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .hook(stopOnTurn2)
                            .enablePendingToolRecovery(true)
                            .build();

            // Turn 1: normal tool call completes
            Msg result1 = agent.call(createUserMsg("Turn 1")).block(TEST_TIMEOUT);
            assertNotNull(result1);
            assertTrue(result1.hasContentBlocks(TextBlock.class));
            assertTrue(
                    memory.getMessages().stream()
                            .anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class)),
                    "Memory should have ToolResultBlock from Turn 1");

            // Turn 2: tool call stopped, pending call_2
            Msg result2 = agent.call(createUserMsg("Turn 2")).block(TEST_TIMEOUT);
            assertTrue(result2.hasContentBlocks(ToolUseBlock.class));

            // Turn 3: plain text → hook should auto-recover, not be fooled by history
            Msg result3 = agent.call(createUserMsg("Turn 3")).block(TEST_TIMEOUT);
            assertNotNull(result3);
            assertTrue(
                    result3.hasContentBlocks(TextBlock.class),
                    "Turn 3 should recover successfully");
        }

        @Test
        @DisplayName("Many turns of successful tool calls, then one interrupted, then recovery")
        void testManyTurnsThenRecovery() {
            // Turn 1-3: successful tool calls building up ToolResultBlocks in memory
            Msg toolCall1 = createToolUseMsg("call_1", "test_tool");
            Msg text1 = createAssistantTextMsg("Done 1");
            Msg toolCall2 = createToolUseMsg("call_2", "test_tool");
            Msg text2 = createAssistantTextMsg("Done 2");
            Msg toolCall3 = createToolUseMsg("call_3", "test_tool");
            Msg text3 = createAssistantTextMsg("Done 3");
            // Turn 4: interrupted
            Msg toolCall4 = createToolUseMsg("call_4", "test_tool");
            // Turn 5: recovery
            Msg recovery = createAssistantTextMsg("Recovered");

            when(mockModel.stream(anyList(), anyList(), any()))
                    .thenReturn(createFluxFromMsg(toolCall1))
                    .thenReturn(createFluxFromMsg(text1))
                    .thenReturn(createFluxFromMsg(toolCall2))
                    .thenReturn(createFluxFromMsg(text2))
                    .thenReturn(createFluxFromMsg(toolCall3))
                    .thenReturn(createFluxFromMsg(text3))
                    .thenReturn(createFluxFromMsg(toolCall4))
                    .thenReturn(createFluxFromMsg(recovery));

            // Stop on the 7th reasoning call (Turn 4's tool call)
            AtomicInteger count = new AtomicInteger(0);
            Hook stopHook = createConditionalStopHook(count, 7);

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .hook(stopHook)
                            .enablePendingToolRecovery(true)
                            .build();

            // Turns 1-3: normal
            agent.call(createUserMsg("Turn 1")).block(TEST_TIMEOUT);
            agent.call(createUserMsg("Turn 2")).block(TEST_TIMEOUT);
            agent.call(createUserMsg("Turn 3")).block(TEST_TIMEOUT);

            // Memory has 3 ToolResultBlocks now
            long toolResultCount =
                    memory.getMessages().stream()
                            .filter(m -> m.hasContentBlocks(ToolResultBlock.class))
                            .count();
            assertTrue(toolResultCount >= 3, "Memory should have at least 3 ToolResultBlocks");

            // Turn 4: interrupted
            Msg result4 = agent.call(createUserMsg("Turn 4")).block(TEST_TIMEOUT);
            assertTrue(result4.hasContentBlocks(ToolUseBlock.class));

            // Turn 5: recovery despite many historical ToolResultBlocks
            Msg result5 = agent.call(createUserMsg("Turn 5")).block(TEST_TIMEOUT);
            assertNotNull(result5);
            assertTrue(
                    result5.hasContentBlocks(TextBlock.class),
                    "Should recover even with many historical ToolResultBlocks in memory");
        }
    }

    // ==================== B. HITL Scenarios ====================

    @Nested
    @DisplayName("B. HITL: user manually provides tool results")
    class HITLTests {

        @Test
        @DisplayName("HITL: user provides matching result for pending call → hook defers to doCall")
        void testHITLFullResult() {
            Msg toolCall = createToolUseMsg("call_1", "test_tool");
            Msg textAfterResume = createAssistantTextMsg("Resumed with user result");

            when(mockModel.stream(anyList(), anyList(), any()))
                    .thenReturn(createFluxFromMsg(toolCall))
                    .thenReturn(createFluxFromMsg(textAfterResume));

            Hook stopHook = createAlwaysStopHook();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .hook(stopHook)
                            .enablePendingToolRecovery(true)
                            .build();

            // Tool call stopped
            Msg result1 = agent.call(createUserMsg("Do something")).block(TEST_TIMEOUT);
            assertTrue(result1.hasContentBlocks(ToolUseBlock.class));

            // User provides result for the pending call
            Msg userResult = createToolResultMsg("call_1", "test_tool", "User provided result");
            Msg result2 = agent.call(userResult).block(TEST_TIMEOUT);

            assertNotNull(result2);
            assertTrue(
                    result2.hasContentBlocks(TextBlock.class),
                    "Agent should continue with user-provided result");
        }

        @Test
        @DisplayName("HITL in multi-turn: user provides result after history has ToolResultBlocks")
        void testHITLInMultiTurn() {
            Msg turn1ToolCall = createToolUseMsg("call_1", "test_tool");
            Msg turn1Text = createAssistantTextMsg("Done");
            Msg turn2ToolCall = createToolUseMsg("call_2", "test_tool");
            Msg textAfterResume = createAssistantTextMsg("Resumed");

            when(mockModel.stream(anyList(), anyList(), any()))
                    .thenReturn(createFluxFromMsg(turn1ToolCall))
                    .thenReturn(createFluxFromMsg(turn1Text))
                    .thenReturn(createFluxFromMsg(turn2ToolCall))
                    .thenReturn(createFluxFromMsg(textAfterResume));

            AtomicInteger count = new AtomicInteger(0);
            Hook stopOnTurn2 = createConditionalStopHook(count, 3);

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .hook(stopOnTurn2)
                            .enablePendingToolRecovery(true)
                            .build();

            // Turn 1: normal
            agent.call(createUserMsg("Turn 1")).block(TEST_TIMEOUT);

            // Turn 2: stopped
            Msg result2 = agent.call(createUserMsg("Turn 2")).block(TEST_TIMEOUT);
            assertTrue(result2.hasContentBlocks(ToolUseBlock.class));

            // Turn 3: HITL - user provides result for call_2
            Msg userResult = createToolResultMsg("call_2", "test_tool", "Manual result");
            Msg result3 = agent.call(userResult).block(TEST_TIMEOUT);

            assertNotNull(result3);
            assertTrue(
                    result3.hasContentBlocks(TextBlock.class),
                    "HITL should work in multi-turn: hook defers, doCall uses user result");
        }
    }

    // ==================== C. Unrelated ToolResultBlock ====================

    @Nested
    @DisplayName("C. Unrelated ToolResultBlock IDs")
    class UnrelatedResultTests {

        @Test
        @DisplayName("Unrelated ToolResultBlock ID should not prevent auto-recovery")
        void testUnrelatedToolResultIdDoesNotBlockRecovery() {
            Msg toolCall = createToolUseMsg("call_1", "test_tool");
            Msg recovery = createAssistantTextMsg("Auto-recovered");

            when(mockModel.stream(anyList(), anyList(), any()))
                    .thenReturn(createFluxFromMsg(toolCall))
                    .thenReturn(createFluxFromMsg(recovery));

            Hook stopHook = createAlwaysStopHook();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .hook(stopHook)
                            .enablePendingToolRecovery(true)
                            .build();

            // Tool call stopped, pending call_1
            agent.call(createUserMsg("Do something")).block(TEST_TIMEOUT);

            // User sends a result with unrelated ID (call_999)
            // Hook should NOT treat this as HITL for call_1 → should auto-patch call_1
            Msg unrelatedResult = createToolResultMsg("call_999", "some_tool", "Unrelated");

            // This should not crash - hook auto-recovers call_1,
            // doCall sees no pending calls and processes normally
            Msg result = agent.call(unrelatedResult).block(TEST_TIMEOUT);
            assertNotNull(result);
            assertTrue(
                    result.hasContentBlocks(TextBlock.class),
                    "Unrelated result ID should not block auto-recovery of pending calls");
        }
    }

    // ==================== D. Single-turn (regression) ====================

    @Nested
    @DisplayName("D. Single-turn regression: should still work as before")
    class SingleTurnRegressionTests {

        @Test
        @DisplayName("Single-turn: stopped tool call → next plain text triggers recovery")
        void testSingleTurnRecovery() {
            Msg toolCall = createToolUseMsg("call_1", "test_tool");
            Msg recovery = createAssistantTextMsg("Recovered");

            when(mockModel.stream(anyList(), anyList(), any()))
                    .thenReturn(createFluxFromMsg(toolCall))
                    .thenReturn(createFluxFromMsg(recovery));

            Hook stopHook = createAlwaysStopHook();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .hook(stopHook)
                            .enablePendingToolRecovery(true)
                            .build();

            Msg result1 = agent.call(createUserMsg("Do something")).block(TEST_TIMEOUT);
            assertTrue(result1.hasContentBlocks(ToolUseBlock.class));

            // No historical ToolResultBlocks - classic single-turn case
            assertFalse(
                    memory.getMessages().stream()
                            .anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class)),
                    "Single-turn: no ToolResultBlock in memory before recovery");

            Msg result2 = agent.call(createUserMsg("Continue")).block(TEST_TIMEOUT);
            assertNotNull(result2);
            assertTrue(
                    result2.hasContentBlocks(TextBlock.class),
                    "Single-turn recovery should still work");
        }

        @Test
        @DisplayName("No pending calls: normal multi-turn should not be affected")
        void testNoPendingCallsNormalFlow() {
            Msg text1 = createAssistantTextMsg("Response 1");
            Msg text2 = createAssistantTextMsg("Response 2");

            when(mockModel.stream(anyList(), anyList(), any()))
                    .thenReturn(createFluxFromMsg(text1))
                    .thenReturn(createFluxFromMsg(text2));

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .enablePendingToolRecovery(true)
                            .build();

            Msg result1 = agent.call(createUserMsg("Hello")).block(TEST_TIMEOUT);
            assertNotNull(result1);

            Msg result2 = agent.call(createUserMsg("Follow up")).block(TEST_TIMEOUT);
            assertNotNull(result2);
            assertTrue(result2.hasContentBlocks(TextBlock.class));
        }
    }

    // ==================== E. Multiple Pending Calls ====================

    @Nested
    @DisplayName("E. Multiple pending tool calls")
    class MultiplePendingTests {

        @Test
        @DisplayName("Multiple pending calls: all auto-recovered when user sends plain text")
        void testMultiplePendingCallsAutoRecovery() {
            // LLM returns 2 tool calls at once
            Msg multiToolCall =
                    createMultiToolUseMsg(
                            List.of(
                                    Map.entry("call_1", "test_tool"),
                                    Map.entry("call_2", "test_tool")));
            Msg recovery = createAssistantTextMsg("All recovered");

            when(mockModel.stream(anyList(), anyList(), any()))
                    .thenReturn(createFluxFromMsg(multiToolCall))
                    .thenReturn(createFluxFromMsg(recovery));

            Hook stopHook = createAlwaysStopHook();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .hook(stopHook)
                            .enablePendingToolRecovery(true)
                            .build();

            // Both calls stopped
            Msg result1 = agent.call(createUserMsg("Do things")).block(TEST_TIMEOUT);
            assertTrue(result1.hasContentBlocks(ToolUseBlock.class));
            assertEquals(
                    2,
                    result1.getContentBlocks(ToolUseBlock.class).size(),
                    "Should have 2 pending tool calls");

            // Plain text → hook should auto-patch both
            Msg result2 = agent.call(createUserMsg("Continue")).block(TEST_TIMEOUT);
            assertNotNull(result2);
            assertTrue(
                    result2.hasContentBlocks(TextBlock.class),
                    "Both pending calls should be auto-recovered");
        }

        @Test
        @DisplayName("Multiple pending calls in multi-turn: auto-recovery with historical results")
        void testMultiplePendingInMultiTurn() {
            // Turn 1: single successful tool call
            Msg turn1ToolCall = createToolUseMsg("call_1", "test_tool");
            Msg turn1Text = createAssistantTextMsg("Done");
            // Turn 2: two tool calls, both stopped
            Msg turn2MultiCall =
                    createMultiToolUseMsg(
                            List.of(
                                    Map.entry("call_2", "test_tool"),
                                    Map.entry("call_3", "test_tool")));
            Msg recovery = createAssistantTextMsg("Both recovered");

            when(mockModel.stream(anyList(), anyList(), any()))
                    .thenReturn(createFluxFromMsg(turn1ToolCall))
                    .thenReturn(createFluxFromMsg(turn1Text))
                    .thenReturn(createFluxFromMsg(turn2MultiCall))
                    .thenReturn(createFluxFromMsg(recovery));

            AtomicInteger count = new AtomicInteger(0);
            Hook stopOnTurn2 = createConditionalStopHook(count, 3);

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .hook(stopOnTurn2)
                            .enablePendingToolRecovery(true)
                            .build();

            // Turn 1: normal
            agent.call(createUserMsg("Turn 1")).block(TEST_TIMEOUT);
            assertTrue(
                    memory.getMessages().stream()
                            .anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class)));

            // Turn 2: both calls stopped
            Msg result2 = agent.call(createUserMsg("Turn 2")).block(TEST_TIMEOUT);
            assertEquals(2, result2.getContentBlocks(ToolUseBlock.class).size());

            // Turn 3: recovery with historical ToolResultBlock(call_1) in memory
            Msg result3 = agent.call(createUserMsg("Turn 3")).block(TEST_TIMEOUT);
            assertNotNull(result3);
            assertTrue(
                    result3.hasContentBlocks(TextBlock.class),
                    "Both pending calls should be auto-recovered in multi-turn");
        }
    }

    // ==================== Helpers ====================

    private Msg createUserMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private Msg createAssistantTextMsg(String text) {
        return Msg.builder()
                .name("assistant")
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private Msg createToolUseMsg(String toolId, String toolName) {
        Map<String, Object> input = Map.of();
        return Msg.builder()
                .name("assistant")
                .role(MsgRole.ASSISTANT)
                .content(
                        ToolUseBlock.builder()
                                .id(toolId)
                                .name(toolName)
                                .input(input)
                                .content(JsonUtils.getJsonCodec().toJson(input))
                                .build())
                .build();
    }

    private Msg createMultiToolUseMsg(List<Map.Entry<String, String>> toolIdNamePairs) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (Map.Entry<String, String> pair : toolIdNamePairs) {
            Map<String, Object> input = Map.of();
            blocks.add(
                    ToolUseBlock.builder()
                            .id(pair.getKey())
                            .name(pair.getValue())
                            .input(input)
                            .content(JsonUtils.getJsonCodec().toJson(input))
                            .build());
        }
        return Msg.builder().name("assistant").role(MsgRole.ASSISTANT).content(blocks).build();
    }

    private Msg createToolResultMsg(String toolId, String toolName, String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.TOOL)
                .content(
                        List.of(
                                ToolResultBlock.of(
                                        toolId, toolName, TextBlock.builder().text(text).build())))
                .build();
    }

    private Flux<ChatResponse> createFluxFromMsg(Msg msg) {
        return Flux.just(ChatResponse.builder().id("test-id").content(msg.getContent()).build());
    }

    private Hook createAlwaysStopHook() {
        return new Hook() {
            @Override
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                if (event instanceof PostReasoningEvent e) {
                    e.stopAgent();
                }
                return Mono.just(event);
            }
        };
    }

    private Hook createConditionalStopHook(AtomicInteger counter, int stopAt) {
        return new Hook() {
            @Override
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                if (event instanceof PostReasoningEvent e) {
                    if (counter.incrementAndGet() == stopAt) {
                        e.stopAgent();
                    }
                }
                return Mono.just(event);
            }
        };
    }

    static class TestTool {
        private final AtomicBoolean executed;

        TestTool(AtomicBoolean executed) {
            this.executed = executed;
        }

        @Tool(name = "test_tool", description = "A test tool")
        public ToolResultBlock testTool() {
            executed.set(true);
            return ToolResultBlock.text("Tool executed");
        }
    }
}
