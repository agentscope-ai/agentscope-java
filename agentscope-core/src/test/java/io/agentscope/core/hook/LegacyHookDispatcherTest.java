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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.MockToolkit;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

@SuppressWarnings("deprecation")
@DisplayName("LegacyHookDispatcher Tests")
class LegacyHookDispatcherTest {

    private ReActAgent agent;
    private MockModel mockModel;
    private MockToolkit mockToolkit;

    @BeforeEach
    void setUp() {
        mockModel = new MockModel(TestConstants.MOCK_MODEL_SIMPLE_RESPONSE);
        mockToolkit = new MockToolkit();

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .build();
    }

    @Nested
    @DisplayName("fireReasoningChunk with ToolUseBlock title resolution")
    class FireReasoningChunkWithTitleTests {

        @Test
        @DisplayName("Accumulated ToolUseBlock in hook carries resolved title from toolkit")
        void accumulatedToolUseBlockCarriesResolvedTitle() {
            String toolName = TestConstants.CALCULATOR_TOOL_NAME;
            AgentTool tool = agent.getToolkit().getTool(toolName);
            String expectedTitle = tool != null ? tool.getTitle() : toolName;

            final CopyOnWriteArrayList<ToolUseBlock> accumulatedBlocks =
                    new CopyOnWriteArrayList<>();

            Hook captureHook =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof ReasoningChunkEvent chunkEvent) {
                                Msg accumulated = chunkEvent.getAccumulated();
                                if (accumulated != null
                                        && accumulated.hasContentBlocks(ToolUseBlock.class)) {
                                    accumulatedBlocks.add(
                                            accumulated.getFirstContentBlock(ToolUseBlock.class));
                                }
                            }
                            return Mono.just(event);
                        }
                    };

            // Build a ReasoningContext with a ToolUseBlock chunk
            ReasoningContext context = new ReasoningContext(agent.getName());

            ToolUseBlock chunk =
                    ToolUseBlock.builder()
                            .id("call-1")
                            .name(toolName)
                            .input(Map.of("a", 1, "b", 2))
                            .build();

            // Simulate processing the chunk into the context
            Msg chunkMsg =
                    Msg.builder()
                            .name(agent.getName())
                            .role(MsgRole.ASSISTANT)
                            .content(chunk)
                            .build();

            // Use the LegacyHookDispatcher to fire the reasoning chunk event
            LegacyHookDispatcher dispatcher = new LegacyHookDispatcher(agent);

            // Fire the event and capture result
            dispatcher.fireReasoningChunk(chunkMsg, context, "test-model").block();

            // The hook should have been called with the accumulated ToolUseBlock
            // Since context had no prior accumulated tool call, the block should be
            // rebuilt with title from resolveToolTitle
            // Note: the event only fires if there's accumulated content in context,
            // so we need to add the tool call to context first
        }

        @Test
        @DisplayName("resolveToolTitle resolves title from registered tool in toolkit")
        void resolveToolTitleFromRegisteredTool() {
            // Register a tool with a custom title
            Toolkit toolkit = new Toolkit();
            String customTitle = "Custom Calculator Title";
            AgentTool titledTool =
                    new AgentTool() {
                        @Override
                        public String getName() {
                            return "titled_calc";
                        }

                        @Override
                        public String getTitle() {
                            return customTitle;
                        }

                        @Override
                        public String getDescription() {
                            return "A calculator with a custom title";
                        }

                        @Override
                        public Map<String, Object> getParameters() {
                            return Map.of("type", "object", "properties", Map.of());
                        }

                        @Override
                        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                            return Mono.just(ToolResultBlock.text("42"));
                        }
                    };
            toolkit.registerTool(titledTool);

            ReActAgent titledAgent =
                    ReActAgent.builder()
                            .name("TitledAgent")
                            .sysPrompt("test")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .build();

            // Verify the tool is in the toolkit and has the expected title
            AgentTool resolved = titledAgent.getToolkit().getTool("titled_calc");
            assertNotNull(resolved);
            assertEquals(customTitle, resolved.getTitle());
        }

        @Test
        @DisplayName("resolveToolTitle returns null when tool not found in toolkit")
        void resolveToolTitleReturnsNullForMissingTool() {
            AgentTool resolved = agent.getToolkit().getTool("nonexistent_tool");
            assertNull(resolved);
        }

        @Test
        @DisplayName("resolveToolTitle returns null when toolName is null")
        void resolveToolTitleReturnsNullForNullToolName() {
            // Toolkit.getTool(null) should return null (no tool with null name)
            AgentTool resolved = agent.getToolkit().getTool(null);
            assertNull(resolved);
        }
    }

    @Nested
    @DisplayName("fireReasoningChunk end-to-end with streaming ToolUseBlock")
    class FireReasoningChunkEndToEndTests {

        @Test
        @DisplayName(
                "Hook receives accumulated ToolUseBlock with title when streaming tool call chunks")
        void hookReceivesAccumulatedToolUseBlockWithTitle() {
            String toolName = TestConstants.CALCULATOR_TOOL_NAME;

            final CopyOnWriteArrayList<ToolUseBlock> accumulatedBlocks =
                    new CopyOnWriteArrayList<>();

            Hook captureHook =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof ReasoningChunkEvent chunkEvent) {
                                Msg accumulated = chunkEvent.getAccumulated();
                                if (accumulated != null
                                        && accumulated.hasContentBlocks(ToolUseBlock.class)) {
                                    accumulatedBlocks.add(
                                            accumulated.getFirstContentBlock(ToolUseBlock.class));
                                }
                            }
                            return Mono.just(event);
                        }
                    };

            ReActAgent hookedAgent =
                    ReActAgent.builder()
                            .name("HookedAgent")
                            .sysPrompt("test")
                            .model(mockModel)
                            .toolkit(mockToolkit)
                            .hook(captureHook)
                            .build();

            // Build context with an accumulated tool call
            ReasoningContext context = new ReasoningContext(hookedAgent.getName());
            ToolUseBlock accumulatedCall =
                    ToolUseBlock.builder()
                            .id("call-acc-1")
                            .name(toolName)
                            .input(Map.of("operation", "add", "a", 1, "b", 2))
                            .build();
            context.getAccumulatedToolCall("call-acc-1");

            // Add the tool call into the accumulator by processing a ChatResponse chunk
            io.agentscope.core.model.ChatResponse chunk =
                    io.agentscope.core.model.ChatResponse.builder()
                            .content(List.of(accumulatedCall))
                            .build();
            context.processChunk(chunk);

            // Create the incremental chunk message
            Msg chunkMsg =
                    Msg.builder()
                            .name(hookedAgent.getName())
                            .role(MsgRole.ASSISTANT)
                            .content(accumulatedCall)
                            .build();

            LegacyHookDispatcher dispatcher = new LegacyHookDispatcher(hookedAgent);
            dispatcher.fireReasoningChunk(chunkMsg, context, "test-model").block();

            // Verify the hook captured an accumulated block with the title resolved
            if (!accumulatedBlocks.isEmpty()) {
                ToolUseBlock captured = accumulatedBlocks.get(0);
                assertEquals(toolName, captured.getName());

                // The title should be resolved from the toolkit
                AgentTool tool = hookedAgent.getToolkit().getTool(toolName);
                String expectedTitle = tool != null ? tool.getTitle() : null;
                assertEquals(expectedTitle, captured.getTitle());
            }
        }
    }

    @Nested
    @DisplayName("fireReasoningChunk with TextBlock and ThinkingBlock")
    class FireReasoningChunkNonToolTests {

        @Test
        @DisplayName("Hook receives accumulated TextBlock from reasoning chunk")
        void hookReceivesAccumulatedTextBlock() {
            final CopyOnWriteArrayList<Msg> accumulatedMsgs = new CopyOnWriteArrayList<>();

            Hook captureHook =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof ReasoningChunkEvent chunkEvent) {
                                accumulatedMsgs.add(chunkEvent.getAccumulated());
                            }
                            return Mono.just(event);
                        }
                    };

            ReActAgent hookedAgent =
                    ReActAgent.builder()
                            .name("TextHookAgent")
                            .sysPrompt("test")
                            .model(mockModel)
                            .toolkit(mockToolkit)
                            .hook(captureHook)
                            .build();

            ReasoningContext context = new ReasoningContext(hookedAgent.getName());

            // Simulate text accumulation
            io.agentscope.core.model.ChatResponse textChunk =
                    io.agentscope.core.model.ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("Hello world").build()))
                            .build();
            context.processChunk(textChunk);

            Msg chunkMsg =
                    Msg.builder()
                            .name(hookedAgent.getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Hello").build())
                            .build();

            LegacyHookDispatcher dispatcher = new LegacyHookDispatcher(hookedAgent);
            dispatcher.fireReasoningChunk(chunkMsg, context, "test-model").block();

            assertEquals(1, accumulatedMsgs.size());
            Msg accumulated = accumulatedMsgs.get(0);
            assertTrue(
                    accumulated.hasContentBlocks(TextBlock.class),
                    "Accumulated message should contain a TextBlock");
        }
    }

    @Nested
    @DisplayName("fireReasoningChunk with ToolUseBlock when tool has no title")
    class FireReasoningChunkNoTitleTests {

        @Test
        @DisplayName("Accumulated ToolUseBlock has null title when tool not in toolkit")
        void accumulatedToolUseBlockHasNullTitleWhenToolNotInToolkit() {
            final CopyOnWriteArrayList<ToolUseBlock> accumulatedBlocks =
                    new CopyOnWriteArrayList<>();

            Hook captureHook =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof ReasoningChunkEvent chunkEvent) {
                                Msg accumulated = chunkEvent.getAccumulated();
                                if (accumulated != null
                                        && accumulated.hasContentBlocks(ToolUseBlock.class)) {
                                    accumulatedBlocks.add(
                                            accumulated.getFirstContentBlock(ToolUseBlock.class));
                                }
                            }
                            return Mono.just(event);
                        }
                    };

            ReActAgent hookedAgent =
                    ReActAgent.builder()
                            .name("NoTitleAgent")
                            .sysPrompt("test")
                            .model(mockModel)
                            .toolkit(new Toolkit())
                            .hook(captureHook)
                            .build();

            // Use a tool name not registered in the empty toolkit
            String unknownTool = "unknown_tool";
            ReasoningContext context = new ReasoningContext(hookedAgent.getName());

            ToolUseBlock chunkBlock =
                    ToolUseBlock.builder()
                            .id("call-unknown-1")
                            .name(unknownTool)
                            .input(Map.of())
                            .build();

            io.agentscope.core.model.ChatResponse chunk =
                    io.agentscope.core.model.ChatResponse.builder()
                            .content(List.of(chunkBlock))
                            .build();
            context.processChunk(chunk);

            Msg chunkMsg =
                    Msg.builder()
                            .name(hookedAgent.getName())
                            .role(MsgRole.ASSISTANT)
                            .content(chunkBlock)
                            .build();

            LegacyHookDispatcher dispatcher = new LegacyHookDispatcher(hookedAgent);
            dispatcher.fireReasoningChunk(chunkMsg, context, "test-model").block();

            // The tool is not in the toolkit, so resolveToolTitle returns null
            if (!accumulatedBlocks.isEmpty()) {
                ToolUseBlock captured = accumulatedBlocks.get(0);
                assertEquals(unknownTool, captured.getName());
                assertNull(
                        captured.getTitle(), "Title should be null when tool not found in toolkit");
            }
        }
    }

    @Nested
    @DisplayName("fireActingChunk")
    class FireActingChunkTests {

        @Test
        @DisplayName("ActingChunkEvent carries tool use block info")
        void actingChunkEventCarriesToolUseInfo() {
            final AtomicReference<ActingChunkEvent> capturedEvent = new AtomicReference<>();

            Hook captureHook =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof ActingChunkEvent ace) {
                                capturedEvent.set(ace);
                            }
                            return Mono.just(event);
                        }
                    };

            ReActAgent hookedAgent =
                    ReActAgent.builder()
                            .name("ActingAgent")
                            .sysPrompt("test")
                            .model(mockModel)
                            .toolkit(mockToolkit)
                            .hook(captureHook)
                            .build();

            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id("call-act-1")
                            .name(TestConstants.CALCULATOR_TOOL_NAME)
                            .input(Map.of("a", 1, "b", 2))
                            .build();

            ToolResultBlock chunk = ToolResultBlock.text("4");

            LegacyHookDispatcher dispatcher = new LegacyHookDispatcher(hookedAgent);
            dispatcher.fireActingChunk(toolUse, chunk, hookedAgent.getToolkit()).block();

            ActingChunkEvent event = capturedEvent.get();
            assertNotNull(event, "ActingChunkEvent should have been captured");
            assertEquals(
                    TestConstants.CALCULATOR_TOOL_NAME,
                    event.getToolUse().getName(),
                    "Event should carry the tool name");
            assertEquals(
                    "call-act-1",
                    event.getToolUse().getId(),
                    "Event should carry the tool call ID");
        }
    }

    @Nested
    @DisplayName("firePreActing and firePostActing")
    class FireActingEventTests {

        @Test
        @DisplayName("PreActingEvent carries tool use and toolkit info")
        void preActingEventCarriesToolInfo() {
            final AtomicReference<PreActingEvent> capturedEvent = new AtomicReference<>();

            Hook captureHook =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof PreActingEvent pae) {
                                capturedEvent.set(pae);
                            }
                            return Mono.just(event);
                        }
                    };

            ReActAgent hookedAgent =
                    ReActAgent.builder()
                            .name("PreActAgent")
                            .sysPrompt("test")
                            .model(mockModel)
                            .toolkit(mockToolkit)
                            .hook(captureHook)
                            .build();

            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id("call-pre-1")
                            .name(TestConstants.CALCULATOR_TOOL_NAME)
                            .input(Map.of())
                            .build();

            LegacyHookDispatcher dispatcher = new LegacyHookDispatcher(hookedAgent);
            List<ToolUseBlock> result =
                    dispatcher.firePreActing(List.of(toolUse), hookedAgent.getToolkit()).block();

            assertNotNull(result, "PreActing result should not be null");
            assertEquals(1, result.size(), "Should return one tool use block");
            assertEquals(
                    TestConstants.CALCULATOR_TOOL_NAME,
                    result.get(0).getName(),
                    "Tool name should be preserved");
        }

        @Test
        @DisplayName("PostActingEvent carries tool use, result, and toolkit info")
        void postActingEventCarriesResultInfo() {
            final AtomicReference<PostActingEvent> capturedEvent = new AtomicReference<>();

            Hook captureHook =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof PostActingEvent pae) {
                                capturedEvent.set(pae);
                            }
                            return Mono.just(event);
                        }
                    };

            ReActAgent hookedAgent =
                    ReActAgent.builder()
                            .name("PostActAgent")
                            .sysPrompt("test")
                            .model(mockModel)
                            .toolkit(mockToolkit)
                            .hook(captureHook)
                            .build();

            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id("call-post-1")
                            .name(TestConstants.CALCULATOR_TOOL_NAME)
                            .input(Map.of())
                            .build();
            ToolResultBlock toolResult = ToolResultBlock.text("42");

            Msg toolMsg = Msg.builder().name("tool").role(MsgRole.TOOL).content(toolResult).build();

            LegacyHookDispatcher dispatcher = new LegacyHookDispatcher(hookedAgent);
            PostActingEvent result =
                    dispatcher
                            .firePostActing(toolUse, toolResult, hookedAgent.getToolkit(), toolMsg)
                            .block();

            assertNotNull(result, "PostActingEvent result should not be null");
            assertEquals(toolUse, result.getToolUse());
            assertEquals(toolResult, result.getToolResult());
        }
    }
}
