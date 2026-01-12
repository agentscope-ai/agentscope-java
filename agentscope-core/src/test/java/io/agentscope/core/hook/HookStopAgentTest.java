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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Comprehensive tests for the Hook Stop Agent feature.
 *
 * <p>This test class covers:
 * <ul>
 *   <li>PostReasoningEvent stopAgent() / isStopRequested() API</li>
 *   <li>PostActingEvent stopAgent() / isStopRequested() API</li>
 *   <li>ReActAgent stop scenarios - returns ToolUse/ToolResult Msg</li>
 *   <li>ReActAgent resume scenarios</li>
 *   <li>Hook integration scenarios</li>
 *   <li>Edge cases and error handling</li>
 * </ul>
 */
class HookStopAgentTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    private Model mockModel;
    private Toolkit toolkit;
    private Memory memory;

    @BeforeEach
    void setUp() {
        mockModel = mock(Model.class);
        when(mockModel.getModelName()).thenReturn("test-model");
        toolkit = new Toolkit();
        memory = new InMemoryMemory();
    }

    // ==================== A. PostReasoningEvent Tests ====================

    @Nested
    @DisplayName("A. PostReasoningEvent Tests")
    class PostReasoningEventTests {

        @Test
        @DisplayName("stopAgent() sets the stop flag to true")
        void testStopAgentSetsFlag() {
            Agent mockAgent = mock(Agent.class);
            Msg msg = createTextMsg("test");
            PostReasoningEvent event = new PostReasoningEvent(mockAgent, "test-model", null, msg);

            assertFalse(event.isStopRequested(), "Initially should be false");

            event.stopAgent();

            assertTrue(event.isStopRequested(), "After stopAgent() should be true");
        }

        @Test
        @DisplayName("Default isStopRequested() returns false")
        void testDefaultNotStopped() {
            Agent mockAgent = mock(Agent.class);
            Msg msg = createTextMsg("test");
            PostReasoningEvent event = new PostReasoningEvent(mockAgent, "test-model", null, msg);

            assertFalse(event.isStopRequested());
        }

        @Test
        @DisplayName("Multiple stopAgent() calls do not cause errors")
        void testMultipleStopAgentCalls() {
            Agent mockAgent = mock(Agent.class);
            Msg msg = createTextMsg("test");
            PostReasoningEvent event = new PostReasoningEvent(mockAgent, "test-model", null, msg);

            event.stopAgent();
            event.stopAgent();
            event.stopAgent();

            assertTrue(event.isStopRequested());
        }
    }

    // ==================== B. PostActingEvent Tests ====================

    @Nested
    @DisplayName("B. PostActingEvent Tests")
    class PostActingEventTests {

        @Test
        @DisplayName("stopAgent() sets the stop flag to true")
        void testStopAgentSetsFlag() {
            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);
            ToolUseBlock toolUse = createToolUseBlock("tool1", "test_tool");
            ToolResultBlock toolResult = ToolResultBlock.text("result");

            PostActingEvent event =
                    new PostActingEvent(mockAgent, mockToolkit, toolUse, toolResult);

            assertFalse(event.isStopRequested(), "Initially should be false");

            event.stopAgent();

            assertTrue(event.isStopRequested(), "After stopAgent() should be true");
        }

        @Test
        @DisplayName("Default isStopRequested() returns false")
        void testDefaultNotStopped() {
            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);
            ToolUseBlock toolUse = createToolUseBlock("tool1", "test_tool");
            ToolResultBlock toolResult = ToolResultBlock.text("result");

            PostActingEvent event =
                    new PostActingEvent(mockAgent, mockToolkit, toolUse, toolResult);

            assertFalse(event.isStopRequested());
        }
    }

    // ==================== C. ReActAgent Stop Scenario Tests ====================

    @Nested
    @DisplayName("C. ReActAgent Stop Scenarios")
    class StopScenarioTests {

        @Test
        @DisplayName("PostReasoning stop returns message with ToolUseBlock")
        void testPostReasoningStopReturnsToolUseMsg() {
            // Setup model to return a response with tool use
            Msg toolUseMsg = createToolUseMsg("tool1", "search", Map.of("query", "test"));
            setupModelToReturnToolUse(toolUseMsg);

            // Create hook that stops after reasoning
            Hook stopHook = createPostReasoningStopHook();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .hook(stopHook)
                            .build();

            Msg userMsg = createUserMsg("Hello");
            Msg result = agent.call(userMsg).block(TEST_TIMEOUT);

            assertNotNull(result);
            assertTrue(
                    result.hasContentBlocks(ToolUseBlock.class),
                    "Stop at PostReasoning should return ToolUse message");
        }

        @Test
        @DisplayName("Stop does not execute subsequent tools")
        void testStopDoesNotExecuteSubsequentTools() {
            AtomicBoolean toolExecuted = new AtomicBoolean(false);

            // Register a tool
            toolkit.registerTool(new TestToolClass(toolExecuted));

            // Setup model to return tool call
            Msg toolUseMsg = createToolUseMsg("tool1", "test_tool", Map.of());
            setupModelToReturnToolUse(toolUseMsg);

            // Create hook that stops after reasoning
            Hook stopHook = createPostReasoningStopHook();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .hook(stopHook)
                            .build();

            agent.call(createUserMsg("test")).block(TEST_TIMEOUT);

            assertFalse(toolExecuted.get(), "Tool should not be executed when stopped");
        }
    }

    // ==================== D. ReActAgent Resume Scenario Tests ====================

    @Nested
    @DisplayName("D. ReActAgent Resume Scenarios")
    class ResumeScenarioTests {

        @Test
        @DisplayName("Resume with no args after PostReasoning stop continues Acting")
        void testResumeWithNoArgsAfterPostReasoningStop() {
            AtomicBoolean toolExecuted = new AtomicBoolean(false);
            AtomicInteger stopCount = new AtomicInteger(0);

            // Register a tool
            toolkit.registerTool(new TestToolClass(toolExecuted));

            // Setup model to return tool call first, then text response
            Msg toolUseMsg = createToolUseMsg("tool1", "test_tool", Map.of());
            Msg textResponse = createAssistantTextMsg("Done!");

            when(mockModel.stream(anyList(), anyList(), any()))
                    .thenReturn(createFluxFromMsg(toolUseMsg))
                    .thenReturn(createFluxFromMsg(textResponse));

            // Create hook that stops only on first PostReasoning
            Hook stopOnceHook =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof PostReasoningEvent e
                                    && stopCount.getAndIncrement() == 0) {
                                e.stopAgent();
                            }
                            return Mono.just(event);
                        }
                    };

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .hook(stopOnceHook)
                            .build();

            // First call - gets stopped, returns ToolUse message
            Msg result1 = agent.call(createUserMsg("test")).block(TEST_TIMEOUT);
            assertTrue(
                    result1.hasContentBlocks(ToolUseBlock.class),
                    "First call should return ToolUse message");
            assertFalse(toolExecuted.get(), "Tool should not be executed yet");

            // Resume with no args
            Msg result2 = agent.call().block(TEST_TIMEOUT);

            assertTrue(toolExecuted.get(), "Tool should be executed after resume");
            assertFalse(
                    result2.hasContentBlocks(ToolUseBlock.class),
                    "Final result should not have pending ToolUse");
        }

        @Test
        @DisplayName("Resume with ToolResult message continues execution")
        void testResumeWithToolResultMsg() {
            // Setup model to return tool call then text
            Msg toolUseMsg = createToolUseMsg("tool1", "test_tool", Map.of());
            Msg textResponse = createAssistantTextMsg("Processed result");

            when(mockModel.stream(anyList(), anyList(), any()))
                    .thenReturn(createFluxFromMsg(toolUseMsg))
                    .thenReturn(createFluxFromMsg(textResponse));

            Hook stopHook = createPostReasoningStopHook();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .hook(stopHook)
                            .build();

            // First call - gets stopped
            Msg result1 = agent.call(createUserMsg("test")).block(TEST_TIMEOUT);
            assertTrue(
                    result1.hasContentBlocks(ToolUseBlock.class),
                    "First call should return ToolUse message");

            // Provide tool result directly
            Msg toolResultMsg =
                    Msg.builder()
                            .name("test-agent")
                            .role(MsgRole.TOOL)
                            .content(
                                    ToolResultBlock.of(
                                            "tool1",
                                            "test_tool",
                                            TextBlock.builder().text("manual result").build()))
                            .build();

            Msg result = agent.call(toolResultMsg).block(TEST_TIMEOUT);

            assertNotNull(result);
            assertTrue(
                    result.hasContentBlocks(TextBlock.class),
                    "Final result should have text content");
        }

        @Test
        @DisplayName("New message with pending tool calls throws error")
        void testNewMsgWithPendingToolUseContinuesActing() {
            Msg toolUseMsg = createToolUseMsg("tool1", "test_tool", Map.of());
            setupModelToReturnToolUse(toolUseMsg);

            Hook stopHook = createPostReasoningStopHook();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .hook(stopHook)
                            .build();

            // First call - gets stopped
            Msg result1 = agent.call(createUserMsg("test")).block(TEST_TIMEOUT);
            assertTrue(
                    result1.hasContentBlocks(ToolUseBlock.class),
                    "First call should return ToolUse message");

            // Send a new regular message - should throw error due to pending tool calls
            Msg newMsg = createUserMsg("new message");

            StepVerifier.create(agent.call(newMsg))
                    .expectErrorMatches(
                            e ->
                                    e instanceof IllegalStateException
                                            && e.getMessage().contains("pending tool calls"))
                    .verify();
        }
    }

    // ==================== E. Hook Integration Tests ====================

    @Nested
    @DisplayName("E. Hook Integration Tests")
    class HookIntegrationTests {

        @Test
        @DisplayName("Hook can inspect reasoning message to decide stop")
        void testHookCanInspectReasoningMessage() {
            // Setup model to return tool call with specific name
            Msg toolUseMsg = createToolUseMsg("tool1", "dangerous_tool", Map.of());
            setupModelToReturnToolUse(toolUseMsg);

            // Hook that stops only for dangerous tools
            Hook conditionalStopHook =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof PostReasoningEvent e) {
                                Msg msg = e.getReasoningMessage();
                                boolean hasDangerousTool =
                                        msg.getContentBlocks(ToolUseBlock.class).stream()
                                                .anyMatch(t -> t.getName().contains("dangerous"));
                                if (hasDangerousTool) {
                                    e.stopAgent();
                                }
                            }
                            return Mono.just(event);
                        }
                    };

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .hook(conditionalStopHook)
                            .build();

            Msg result = agent.call(createUserMsg("test")).block(TEST_TIMEOUT);

            assertTrue(
                    result.hasContentBlocks(ToolUseBlock.class),
                    "Should return ToolUse message when dangerous tool detected");
        }

        @Test
        @DisplayName("Multiple hooks with one calling stopAgent")
        void testMultipleHooksWithStop() {
            Msg toolUseMsg = createToolUseMsg("tool1", "test_tool", Map.of());
            setupModelToReturnToolUse(toolUseMsg);

            AtomicInteger hook1Count = new AtomicInteger(0);
            AtomicInteger hook2Count = new AtomicInteger(0);

            Hook hook1 =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof PostReasoningEvent) {
                                hook1Count.incrementAndGet();
                            }
                            return Mono.just(event);
                        }

                        @Override
                        public int priority() {
                            return 10;
                        }
                    };

            Hook hook2 =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof PostReasoningEvent e) {
                                hook2Count.incrementAndGet();
                                e.stopAgent();
                            }
                            return Mono.just(event);
                        }

                        @Override
                        public int priority() {
                            return 20;
                        }
                    };

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .hook(hook1)
                            .hook(hook2)
                            .build();

            Msg result = agent.call(createUserMsg("test")).block(TEST_TIMEOUT);

            assertEquals(1, hook1Count.get());
            assertEquals(1, hook2Count.get());
            assertTrue(
                    result.hasContentBlocks(ToolUseBlock.class),
                    "Should return ToolUse message when hook2 calls stopAgent");
        }
    }

    // ==================== F. Edge Case Tests ====================

    @Nested
    @DisplayName("F. Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Stop on first iteration")
        void testStopOnFirstIteration() {
            Msg toolUseMsg = createToolUseMsg("tool1", "test_tool", Map.of());
            setupModelToReturnToolUse(toolUseMsg);

            Hook stopHook = createPostReasoningStopHook();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .hook(stopHook)
                            .build();

            Msg result = agent.call(createUserMsg("test")).block(TEST_TIMEOUT);

            assertNotNull(result);
            assertTrue(
                    result.hasContentBlocks(ToolUseBlock.class),
                    "Should return ToolUse message on first iteration stop");
            // Should only have called model once
            verify(mockModel, times(1)).stream(anyList(), anyList(), any());
        }

        @Test
        @DisplayName("Normal call after completion works")
        void testNormalCallAfterCompletion() {
            // First response: text only
            Msg textResponse1 = createAssistantTextMsg("First response");
            Msg textResponse2 = createAssistantTextMsg("Second response");

            when(mockModel.stream(anyList(), anyList(), any()))
                    .thenReturn(createFluxFromMsg(textResponse1))
                    .thenReturn(createFluxFromMsg(textResponse2));

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .build();

            Msg result1 = agent.call(createUserMsg("First")).block(TEST_TIMEOUT);
            assertNotNull(result1);
            assertFalse(
                    result1.hasContentBlocks(ToolUseBlock.class),
                    "First result should not have ToolUse");

            Msg result2 = agent.call(createUserMsg("Second")).block(TEST_TIMEOUT);
            assertNotNull(result2);
            assertFalse(
                    result2.hasContentBlocks(ToolUseBlock.class),
                    "Second result should not have ToolUse");
        }

        @Test
        @DisplayName("Agent throws error when adding regular message with pending tool calls")
        void testAgentHandlesPendingToolCallsGracefully() {
            Msg toolUseMsg = createToolUseMsg("tool1", "test_tool", Map.of());
            setupModelToReturnToolUse(toolUseMsg);

            Hook stopHook = createPostReasoningStopHook();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .memory(memory)
                            .checkRunning(false)
                            .hook(stopHook)
                            .build();

            agent.call(createUserMsg("test")).block(TEST_TIMEOUT);

            // With new design, agent will throw error when adding regular message
            // with pending tool calls
            StepVerifier.create(agent.call(createUserMsg("new")))
                    .expectErrorMatches(
                            e ->
                                    e instanceof IllegalStateException
                                            && e.getMessage().contains("pending tool calls"))
                    .verify();
        }
    }

    // ==================== Helper Methods ====================

    private Msg createTextMsg(String text) {
        return Msg.builder()
                .name("test")
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

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

    private Msg createToolUseMsg(String toolId, String toolName, Map<String, Object> input) {
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

    private ToolUseBlock createToolUseBlock(String id, String name) {
        Map<String, Object> emptyInput = Map.of();
        return ToolUseBlock.builder()
                .id(id)
                .name(name)
                .input(emptyInput)
                .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                .build();
    }

    private void setupModelToReturnToolUse(Msg toolUseMsg) {
        when(mockModel.stream(anyList(), anyList(), any()))
                .thenReturn(createFluxFromMsg(toolUseMsg));
    }

    private Flux<ChatResponse> createFluxFromMsg(Msg msg) {
        ChatResponse response =
                ChatResponse.builder().id("test-id").content(msg.getContent()).build();
        return Flux.just(response);
    }

    private Hook createPostReasoningStopHook() {
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

    private Hook createPostActingStopHook() {
        return new Hook() {
            @Override
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                if (event instanceof PostActingEvent e) {
                    e.stopAgent();
                }
                return Mono.just(event);
            }
        };
    }

    // ==================== Test Tool Classes ====================

    /** Simple test tool class for verifying tool execution. */
    static class TestToolClass {
        private final AtomicBoolean executed;

        TestToolClass(AtomicBoolean executed) {
            this.executed = executed;
        }

        @io.agentscope.core.tool.Tool(name = "test_tool", description = "A test tool")
        public ToolResultBlock testTool() {
            executed.set(true);
            return ToolResultBlock.text("Tool executed");
        }
    }
}
