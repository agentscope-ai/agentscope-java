/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiTool;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.model.ToolMergeMode;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Unit tests for AguiAgentAdapter, exercising the 2.0 fine-grained event stream
 * ({@link ReActAgent#streamEvents(List, RuntimeContext)}).
 */
@SuppressWarnings("unchecked")
class AguiAgentAdapterTest {

    private ReActAgent mockAgent;
    private AguiAgentAdapter adapter;

    @BeforeEach
    void setUp() {
        mockAgent = mock(ReActAgent.class);
        adapter = new AguiAgentAdapter(mockAgent, AguiAdapterConfig.defaultConfig());
    }

    @Test
    void testRunInjectsRunInputIntoRuntimeContext() {
        ArgumentCaptor<RuntimeContext> contextCaptor =
                ArgumentCaptor.forClass(RuntimeContext.class);
        when(mockAgent.streamEvents(anyList(), contextCaptor.capture())).thenReturn(Flux.empty());

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-ctx")
                        .runId("run-ctx")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .tools(List.of(frontendTool("frontend_lookup")))
                        .context(List.of())
                        .state(Map.of("cursor", 12))
                        .forwardedProps(Map.of("tenant", "demo"))
                        .build();

        adapter.run(input).collectList().block();

        RuntimeContext context = contextCaptor.getValue();
        assertEquals("thread-ctx", context.getSessionId());
        assertSame(input, context.get(RunAgentInput.class));
        assertEquals("thread-ctx", context.get("agui.threadId"));
        assertEquals("run-ctx", context.get("agui.runId"));
        assertSame(input.getMessages(), context.get("agui.messages"));
        assertSame(input.getTools(), context.get("agui.tools"));
        assertSame(input.getContext(), context.get("agui.context"));
        assertSame(input.getState(), context.get("agui.state"));
        assertSame(input.getForwardedProps(), context.get("agui.forwardedProps"));
    }

    @Test
    void testRunRegistersFrontendToolsForRunAndCleansUp() {
        Toolkit toolkit = new Toolkit();
        when(mockAgent.getToolkit()).thenReturn(toolkit);
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            assertInstanceOf(
                                    SchemaOnlyTool.class, toolkit.getTool("frontend_lookup"));
                            assertTrue(toolkit.isExternalTool("frontend_lookup"));
                            return Flux.empty();
                        });

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-tools")
                        .runId("run-tools")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .tools(List.of(frontendTool("frontend_lookup")))
                        .build();

        adapter.run(input).collectList().block();

        assertNull(toolkit.getTool("frontend_lookup"));
    }

    @Test
    void testRunRestoresAgentToolWhenFrontendToolHasSameName() {
        Toolkit toolkit = new Toolkit();
        SchemaOnlyTool existingTool = schemaOnlyTool("shared_lookup");
        toolkit.registerAgentTool(existingTool);
        when(mockAgent.getToolkit()).thenReturn(toolkit);
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            assertInstanceOf(
                                    SchemaOnlyTool.class, toolkit.getTool("shared_lookup"));
                            assertNotSame(existingTool, toolkit.getTool("shared_lookup"));
                            return Flux.empty();
                        });

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-tools")
                        .runId("run-tools")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .tools(List.of(frontendTool("shared_lookup")))
                        .build();

        adapter.run(input).collectList().block();

        assertSame(existingTool, toolkit.getTool("shared_lookup"));
    }

    @Test
    void testRunDoesNotRegisterFrontendToolsWhenAgentOnly() {
        Toolkit toolkit = new Toolkit();
        when(mockAgent.getToolkit()).thenReturn(toolkit);
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.empty());

        AguiAgentAdapter agentOnlyAdapter =
                new AguiAgentAdapter(
                        mockAgent,
                        AguiAdapterConfig.builder()
                                .toolMergeMode(ToolMergeMode.AGENT_ONLY)
                                .build());
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-agent-only")
                        .runId("run-agent-only")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .tools(List.of(frontendTool("frontend_lookup")))
                        .build();

        agentOnlyAdapter.run(input).collectList().block();

        assertNull(toolkit.getTool("frontend_lookup"));
    }

    @Test
    void testRunEmitsErrorEventsWhenStreamThrowsBeforeReturningFlux() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenThrow(new RuntimeException());

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-error")
                        .runId("run-error")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);
        assertEquals(3, events.size());
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        AguiEvent.RunError runError = assertInstanceOf(AguiEvent.RunError.class, events.get(1));
        assertEquals("RuntimeException", runError.message());
        assertEquals("INTERNAL_ERROR", runError.code());
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(2));
    }

    @Test
    void testRunIgnoresFrontendToolsWhenAgentHasNoToolkit() {
        when(mockAgent.getToolkit()).thenReturn(null);
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.empty());

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-no-toolkit")
                        .runId("run-no-toolkit")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .tools(List.of(frontendTool("frontend_lookup")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);
        assertEquals(2, events.size());
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(1));
    }

    @Test
    void testRunUsesFrontendPriorityWhenToolMergeModeIsNull() {
        Toolkit toolkit = new Toolkit();
        when(mockAgent.getToolkit()).thenReturn(toolkit);
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            assertInstanceOf(
                                    SchemaOnlyTool.class, toolkit.getTool("frontend_lookup"));
                            return Flux.empty();
                        });

        AguiAgentAdapter nullMergeModeAdapter =
                new AguiAgentAdapter(
                        mockAgent, AguiAdapterConfig.builder().toolMergeMode(null).build());
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-null-merge-mode")
                        .runId("run-null-merge-mode")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .tools(List.of(frontendTool("frontend_lookup")))
                        .build();

        nullMergeModeAdapter.run(input).collectList().block();

        assertNull(toolkit.getTool("frontend_lookup"));
    }

    @Test
    void testRunWithFrontendOnlyTemporarilyReplacesToolkitAndRestoresAgentTools() {
        Toolkit toolkit = new Toolkit();
        SchemaOnlyTool existingTool = schemaOnlyTool("agent_lookup");
        SchemaOnlyTool existingSharedTool = schemaOnlyTool("shared_lookup");
        toolkit.registerAgentTool(existingTool);
        toolkit.registerAgentTool(existingSharedTool);
        when(mockAgent.getToolkit()).thenReturn(toolkit);
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            assertNull(toolkit.getTool("agent_lookup"));
                            assertInstanceOf(
                                    SchemaOnlyTool.class, toolkit.getTool("frontend_lookup"));
                            assertInstanceOf(
                                    SchemaOnlyTool.class, toolkit.getTool("shared_lookup"));
                            assertNotSame(existingSharedTool, toolkit.getTool("shared_lookup"));
                            return Flux.empty();
                        });

        AguiAgentAdapter frontendOnlyAdapter =
                new AguiAgentAdapter(
                        mockAgent,
                        AguiAdapterConfig.builder()
                                .toolMergeMode(ToolMergeMode.FRONTEND_ONLY)
                                .build());
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-frontend-only")
                        .runId("run-frontend-only")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .tools(
                                List.of(
                                        frontendTool("frontend_lookup"),
                                        frontendTool("shared_lookup")))
                        .build();

        frontendOnlyAdapter.run(input).collectList().block();

        assertSame(existingTool, toolkit.getTool("agent_lookup"));
        assertSame(existingSharedTool, toolkit.getTool("shared_lookup"));
        assertNull(toolkit.getTool("frontend_lookup"));
    }

    @Test
    void testRunWithFrontendOnlySkipsToolNameThatNoLongerResolves() {
        Toolkit toolkit = new GhostToolNameToolkit();
        when(mockAgent.getToolkit()).thenReturn(toolkit);
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            assertInstanceOf(
                                    SchemaOnlyTool.class, toolkit.getTool("frontend_lookup"));
                            return Flux.empty();
                        });

        AguiAgentAdapter frontendOnlyAdapter =
                new AguiAgentAdapter(
                        mockAgent,
                        AguiAdapterConfig.builder()
                                .toolMergeMode(ToolMergeMode.FRONTEND_ONLY)
                                .build());
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-frontend-only-ghost")
                        .runId("run-frontend-only-ghost")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .tools(List.of(frontendTool("frontend_lookup")))
                        .build();

        frontendOnlyAdapter.run(input).collectList().block();

        assertNull(toolkit.getTool("frontend_lookup"));
    }

    @Test
    void testRunKeepsToolThatReplacesInjectedFrontendToolBeforeCleanup() {
        Toolkit toolkit = new Toolkit();
        SchemaOnlyTool existingTool = schemaOnlyTool("shared_lookup");
        SchemaOnlyTool replacementTool = schemaOnlyTool("shared_lookup");
        toolkit.registerAgentTool(existingTool);
        when(mockAgent.getToolkit()).thenReturn(toolkit);
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            toolkit.registerAgentTool(replacementTool);
                            return Flux.empty();
                        });

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-replaced-tool")
                        .runId("run-replaced-tool")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .tools(List.of(frontendTool("shared_lookup")))
                        .build();

        adapter.run(input).collectList().block();

        assertSame(replacementTool, toolkit.getTool("shared_lookup"));
    }

    @Test
    void testRunReturnsRunStartedAndFinishedEvents() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.empty());

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);
        assertEquals(2, events.size());
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(1));

        AguiEvent.RunStarted started = (AguiEvent.RunStarted) events.get(0);
        assertEquals("thread-1", started.getThreadId());
        assertEquals("run-1", started.getRunId());
    }

    @Test
    void testRunWithTextBlockEvent() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new TextBlockStartEvent("msg-r1", "b1"),
                                new TextBlockDeltaEvent("msg-r1", "b1", "Hello, I'm here to help!"),
                                new TextBlockEndEvent("msg-r1", "b1")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);
        // RunStarted, TextMessageStart, TextMessageContent, TextMessageEnd, RunFinished
        assertTrue(events.size() >= 4);

        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));

        boolean hasTextStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageStart);
        boolean hasTextContent =
                events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageContent);
        boolean hasTextEnd = events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageEnd);

        assertTrue(hasTextStart, "Should have TextMessageStart");
        assertTrue(hasTextContent, "Should have TextMessageContent");
        assertTrue(hasTextEnd, "Should have TextMessageEnd");

        assertInstanceOf(AguiEvent.RunFinished.class, events.get(events.size() - 1));
    }

    @Test
    void testRunWithStreamingTextEvents() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new TextBlockStartEvent("msg-stream", "b1"),
                                new TextBlockDeltaEvent("msg-stream", "b1", "Hello"),
                                new TextBlockDeltaEvent("msg-stream", "b1", ", world!"),
                                new TextBlockEndEvent("msg-stream", "b1")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hi")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        long contentCount =
                events.stream().filter(e -> e instanceof AguiEvent.TextMessageContent).count();
        assertEquals(2, contentCount, "Should have 2 content events for streaming");

        long startCount =
                events.stream().filter(e -> e instanceof AguiEvent.TextMessageStart).count();
        assertEquals(1, startCount, "Should have only 1 start event for same message ID");
    }

    @Test
    void testRunWithTextBlockEventUsesTextMessages() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new TextBlockStartEvent("msg-summary", "b1"),
                                new TextBlockDeltaEvent(
                                        "msg-summary", "b1", "Here is the conversation summary."),
                                new TextBlockEndEvent("msg-summary", "b1")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        AguiEvent.TextMessageContent summaryContent =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.TextMessageContent)
                        .map(e -> (AguiEvent.TextMessageContent) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(summaryContent, "Should convert TextBlock to TextMessageContent");
        assertEquals("msg-summary", summaryContent.messageId());
        assertEquals("Here is the conversation summary.", summaryContent.delta());

        long textEndCount =
                events.stream().filter(e -> e instanceof AguiEvent.TextMessageEnd).count();
        assertEquals(1, textEndCount, "Should close the text message exactly once");
    }

    @Test
    void testRunWithStreamingTextBlockEvents() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new TextBlockStartEvent("msg-summary", "b1"),
                                new TextBlockDeltaEvent("msg-summary", "b1", "First part. "),
                                new TextBlockDeltaEvent("msg-summary", "b1", "Second part."),
                                new TextBlockEndEvent("msg-summary", "b1")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        long contentCount =
                events.stream().filter(e -> e instanceof AguiEvent.TextMessageContent).count();
        assertEquals(2, contentCount, "Should stream text chunks as text deltas");

        long startCount =
                events.stream().filter(e -> e instanceof AguiEvent.TextMessageStart).count();
        assertEquals(1, startCount, "Should only start the text message once");

        long endCount = events.stream().filter(e -> e instanceof AguiEvent.TextMessageEnd).count();
        assertEquals(1, endCount, "Should only end the text message once");
    }

    @Test
    void testRunWithToolCallEvent() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ToolCallStartEvent("msg-tc1", "tc-1", "get_weather"),
                                new ToolCallDeltaEvent(
                                        "msg-tc1",
                                        "tc-1",
                                        "get_weather",
                                        "{\"city\":\"Beijing\"}")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Weather?")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        AguiEvent.ToolCallStart toolStart =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallStart)
                        .map(e -> (AguiEvent.ToolCallStart) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolStart, "Should have ToolCallStart");
        assertEquals("tc-1", toolStart.toolCallId());
        assertEquals("get_weather", toolStart.toolCallName());

        AguiEvent.ToolCallArgs toolArgs =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallArgs)
                        .map(e -> (AguiEvent.ToolCallArgs) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolArgs, "Should have ToolCallArgs");
        assertTrue(toolArgs.delta().contains("Beijing"));
    }

    @Test
    void testRunWithToolResultEvent() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ToolCallStartEvent("msg-tc1", "tc-1", "calculator"),
                                new ToolCallEndEvent("msg-tc1", "tc-1", "calculator"),
                                new ToolResultStartEvent("msg-tc1", "tc-1", "calculator"),
                                new ToolResultTextDeltaEvent("msg-tc1", "tc-1", "calculator", "4"),
                                new ToolResultEndEvent(
                                        "msg-tc1", "tc-1", "calculator", ToolResultState.SUCCESS)));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Calculate")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        AguiEvent.ToolCallEnd toolEnd =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallEnd)
                        .map(e -> (AguiEvent.ToolCallEnd) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolEnd, "Should have ToolCallEnd");
        assertEquals("tc-1", toolEnd.toolCallId());

        AguiEvent.ToolCallResult toolResult =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallResult)
                        .map(e -> (AguiEvent.ToolCallResult) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolResult, "Should have ToolCallResult");
        assertEquals("tc-1", toolResult.toolCallId());
        assertEquals("4", toolResult.content());
    }

    @Test
    void testRunWithAgentError() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.error(new RuntimeException("Agent error")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        assertTrue(events.size() >= 3);
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));

        AguiEvent.RunError errorEvent =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.RunError)
                        .map(e -> (AguiEvent.RunError) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(errorEvent, "Should have RunError event");
        assertTrue(errorEvent.message().contains("Agent error"));

        assertInstanceOf(AguiEvent.RunFinished.class, events.get(events.size() - 1));
    }

    @Test
    void testRunWithEmptyMessages() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.empty());

        RunAgentInput input = RunAgentInput.builder().threadId("thread-1").runId("run-1").build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);
        assertEquals(2, events.size());
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(1));
    }

    @Test
    void testRunWithDisabledToolCallArgs() {
        AguiAdapterConfig config = AguiAdapterConfig.builder().emitToolCallArgs(false).build();
        AguiAgentAdapter adapterNoArgs = new AguiAgentAdapter(mockAgent, config);

        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ToolCallStartEvent("msg-tc1", "tc-1", "test_tool"),
                                new ToolCallDeltaEvent(
                                        "msg-tc1", "tc-1", "test_tool", "{\"param\":\"value\"}")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapterNoArgs.run(input).collectList().block();

        assertNotNull(events);

        boolean hasToolArgs = events.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallArgs);
        assertFalse(hasToolArgs, "Should NOT have ToolCallArgs when disabled");

        boolean hasToolStart = events.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallStart);
        assertTrue(hasToolStart, "Should still have ToolCallStart");
    }

    @Test
    void testTextAndToolCallMixedContent() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new TextBlockStartEvent("msg-mixed", "b1"),
                                new TextBlockDeltaEvent(
                                        "msg-mixed", "b1", "Let me check the weather for you."),
                                new TextBlockEndEvent("msg-mixed", "b1"),
                                new ToolCallStartEvent("msg-mixed", "tc-1", "get_weather"),
                                new ToolCallDeltaEvent(
                                        "msg-mixed",
                                        "tc-1",
                                        "get_weather",
                                        "{\"city\":\"Shanghai\"}")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Weather?")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        boolean hasTextStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageStart);
        boolean hasTextContent =
                events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageContent);
        boolean hasTextEnd = events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageEnd);
        boolean hasToolStart = events.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallStart);

        assertTrue(hasTextStart, "Should have TextMessageStart");
        assertTrue(hasTextContent, "Should have TextMessageContent");
        assertTrue(hasTextEnd, "Should have TextMessageEnd");
        assertTrue(hasToolStart, "Should have ToolCallStart");
    }

    @Test
    void testDuplicateToolCallStartNotEmitted() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ToolCallStartEvent("msg-tc", "tc-1", "tool"),
                                new ToolCallStartEvent("msg-tc", "tc-1", "tool")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        long toolStartCount =
                events.stream().filter(e -> e instanceof AguiEvent.ToolCallStart).count();
        assertEquals(1, toolStartCount, "Should only emit 1 ToolCallStart per tool ID");
    }

    @Test
    void testReactiveStreamCompletion() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new TextBlockStartEvent("msg-1", "b1"),
                                new TextBlockDeltaEvent("msg-1", "b1", "Done"),
                                new TextBlockEndEvent("msg-1", "b1")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("t1")
                        .runId("r1")
                        .messages(List.of(AguiMessage.userMessage("m1", "Hi")))
                        .build();

        StepVerifier.create(adapter.run(input))
                .expectNextMatches(e -> e instanceof AguiEvent.RunStarted)
                .expectNextMatches(e -> e instanceof AguiEvent.TextMessageStart)
                .expectNextMatches(e -> e instanceof AguiEvent.TextMessageContent)
                .expectNextMatches(e -> e instanceof AguiEvent.TextMessageEnd)
                .expectNextMatches(e -> e instanceof AguiEvent.RunFinished)
                .verifyComplete();
    }

    @Test
    void testToolCallStartWithNullIdGeneratesUUID() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new ToolCallStartEvent("msg-tc1", null, "test_tool")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        AguiEvent.ToolCallStart toolStart =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallStart)
                        .map(e -> (AguiEvent.ToolCallStart) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolStart, "Should have ToolCallStart");
        // A null tool call ID must be replaced with a generated UUID
        assertNotNull(toolStart.toolCallId(), "Tool call ID should not be null");
        assertTrue(!toolStart.toolCallId().isEmpty(), "Tool call ID should not be empty");
    }

    @Test
    void testTextMessageEndNotDuplicatedWhenInterruptedByToolCall() {
        // A text block started, then a tool call interrupts it (closing the text message
        // defensively), and finally an explicit TextBlockEnd for the same message ID arrives.
        // Only one TextMessageEnd should be emitted.
        String msgId = "msg-text";
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new TextBlockStartEvent(msgId, "b1"),
                                new TextBlockDeltaEvent(msgId, "b1", "first part"),
                                new ToolCallStartEvent("msg-tc", "tc-1", "tool"),
                                new ToolCallEndEvent("msg-tc", "tc-1", "tool"),
                                new TextBlockEndEvent(msgId, "b1")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        long textEndCount =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.TextMessageEnd)
                        .filter(
                                e -> {
                                    AguiEvent.TextMessageEnd end = (AguiEvent.TextMessageEnd) e;
                                    return msgId.equals(end.messageId());
                                })
                        .count();
        assertEquals(1, textEndCount, "Should have exactly 1 TextMessageEnd per message ID");
    }

    @Test
    void testTextMessageEndWithLastEventDirectly() {
        String msgId = "msg-text";
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new TextBlockStartEvent(msgId, "b1"),
                                new TextBlockDeltaEvent(msgId, "b1", "Hello world"),
                                new TextBlockEndEvent(msgId, "b1")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        long textEndCount =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.TextMessageEnd)
                        .filter(
                                e -> {
                                    AguiEvent.TextMessageEnd end = (AguiEvent.TextMessageEnd) e;
                                    return msgId.equals(end.messageId());
                                })
                        .count();
        assertEquals(1, textEndCount, "Should have exactly 1 TextMessageEnd");

        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        assertInstanceOf(AguiEvent.TextMessageStart.class, events.get(1));
        assertInstanceOf(AguiEvent.TextMessageContent.class, events.get(2));
        assertInstanceOf(AguiEvent.TextMessageEnd.class, events.get(3));
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(4));
    }

    @Test
    void testToolResultWithMultipleTextDeltas() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ToolResultStartEvent("msg-tr1", "tc-1", "tool"),
                                new ToolResultTextDeltaEvent("msg-tr1", "tc-1", "tool", "Line 1"),
                                new ToolResultTextDeltaEvent("msg-tr1", "tc-1", "tool", "Line 2"),
                                new ToolResultTextDeltaEvent("msg-tr1", "tc-1", "tool", "Line 3"),
                                new ToolResultEndEvent(
                                        "msg-tr1", "tc-1", "tool", ToolResultState.SUCCESS)));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        long toolResultCount =
                events.stream().filter(e -> e instanceof AguiEvent.ToolCallResult).count();
        assertEquals(1, toolResultCount, "Should have ToolCallResult");

        AguiEvent.ToolCallResult toolResult =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallResult)
                        .map(e -> (AguiEvent.ToolCallResult) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolResult, "Should have ToolCallResult");
        assertTrue(toolResult.content().contains("Line 1"), "Should contain Line 1");
        assertTrue(toolResult.content().contains("Line 2"), "Should contain Line 2");
        assertTrue(toolResult.content().contains("Line 3"), "Should contain Line 3");
    }

    @Test
    void testToolResultWithEmptyOutput() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ToolResultStartEvent("msg-tr1", "tc-1", "tool"),
                                new ToolResultEndEvent(
                                        "msg-tr1", "tc-1", "tool", ToolResultState.SUCCESS)));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        AguiEvent.ToolCallResult toolResult =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallResult)
                        .map(e -> (AguiEvent.ToolCallResult) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolResult, "Should have ToolCallResult");
        assertTrue(
                toolResult.content() == null || toolResult.content().isEmpty(),
                "Empty output should result in null or empty content");
    }

    @Test
    void testToolCallWithNullArgsDeltaEmitsNoToolCallArgs() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ToolCallStartEvent("msg-tc1", "tc-1", "test_tool"),
                                new ToolCallDeltaEvent("msg-tc1", "tc-1", "test_tool", null)));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        boolean hasToolStart = events.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallStart);
        assertTrue(hasToolStart, "Should have ToolCallStart");

        boolean hasToolArgs = events.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallArgs);
        assertFalse(hasToolArgs, "Should NOT have ToolCallArgs when delta is null");
    }

    @Test
    void testRunWithThinkingBlockDefaultDisabled() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ThinkingBlockStartEvent("msg-r1", "b1"),
                                new ThinkingBlockDeltaEvent(
                                        "msg-r1", "b1", "Let me think about this problem."),
                                new ThinkingBlockEndEvent("msg-r1", "b1")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hi")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        boolean hasReasoningMessageStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageStart);
        boolean hasReasoningMessageContent =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageContent);

        assertFalse(
                hasReasoningMessageStart, "Should NOT have ReasoningMessageStart when disabled");
        assertFalse(
                hasReasoningMessageContent,
                "Should NOT have ReasoningMessageContent when disabled");
    }

    @Test
    void testRunWithThinkingBlockEvent() {
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ThinkingBlockStartEvent("msg-r1", "b1"),
                                new ThinkingBlockDeltaEvent(
                                        "msg-r1", "b1", "Let me think about this problem."),
                                new ThinkingBlockEndEvent("msg-r1", "b1")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        AguiEvent.ReasoningMessageStart reasoningMessageStart =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ReasoningMessageStart)
                        .map(e -> (AguiEvent.ReasoningMessageStart) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(reasoningMessageStart, "Should have ReasoningMessageStart");
        assertEquals("msg-r1", reasoningMessageStart.messageId());
        assertEquals("reasoning", reasoningMessageStart.role());

        AguiEvent.ReasoningMessageContent reasoningMessageContent =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ReasoningMessageContent)
                        .map(e -> (AguiEvent.ReasoningMessageContent) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(reasoningMessageContent, "Should have ReasoningMessageContent");
        assertTrue(
                reasoningMessageContent.delta().contains("think about this problem"),
                "Should contain thinking content");
    }

    @Test
    void testRunWithStreamingThinkingBlockEvents() {
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ThinkingBlockStartEvent("msg-thinking", "b1"),
                                new ThinkingBlockDeltaEvent("msg-thinking", "b1", "First thought"),
                                new ThinkingBlockDeltaEvent("msg-thinking", "b1", "Second thought"),
                                new ThinkingBlockEndEvent("msg-thinking", "b1")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Calculate")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        long reasoningMessageContentCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageContent).count();
        assertEquals(
                2,
                reasoningMessageContentCount,
                "Should have 2 reasoning message content events for streaming");

        long reasoningMessageStartCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageStart).count();
        assertEquals(
                1,
                reasoningMessageStartCount,
                "Should have only 1 start event for same reasoning message ID");
    }

    @Test
    void testStateLeakOnMultipleSubscriptions() {
        // Verifies the fix for Issue #510: state must be isolated across subscriptions.
        String bugMessageId = "chatcmpl-afaa1eae32eae120";
        String bugToolId = "chatcmpl-tool-ab42f73d312799c7";

        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new TextBlockStartEvent(bugMessageId, "b1"),
                                new TextBlockDeltaEvent(
                                        bugMessageId, "b1", "Preparing to call the tool..."),
                                new TextBlockEndEvent(bugMessageId, "b1"),
                                new ToolCallStartEvent(
                                        bugMessageId, bugToolId, "getUniversityInfo"),
                                new ToolCallEndEvent(
                                        bugMessageId, bugToolId, "getUniversityInfo")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("2010331348305129474")
                        .runId("17816087-d4aa-4743-baee-9989a4ab3c8d")
                        .messages(
                                List.of(
                                        AguiMessage.userMessage(
                                                "msg-1", "Check university score lines")))
                        .build();

        Flux<AguiEvent> resultFlux = adapter.run(input);

        List<AguiEvent> firstRunEvents = resultFlux.collectList().block();
        assertNotNull(firstRunEvents);

        long firstRunStartCount =
                firstRunEvents.stream()
                        .filter(e -> e instanceof AguiEvent.TextMessageStart)
                        .count();
        assertEquals(1, firstRunStartCount, "First execution should contain 1 TextMessageStart");

        List<AguiEvent> secondRunEvents = resultFlux.collectList().block();
        assertNotNull(secondRunEvents);

        long secondRunStartCount =
                secondRunEvents.stream()
                        .filter(e -> e instanceof AguiEvent.TextMessageStart)
                        .count();

        assertEquals(
                1,
                secondRunStartCount,
                "State should be isolated; second execution should contain 1 TextMessageStart");

        long secondRunContentCount =
                secondRunEvents.stream()
                        .filter(e -> e instanceof AguiEvent.TextMessageContent)
                        .count();
        assertTrue(secondRunContentCount > 0, "Second execution should contain TextMessageContent");
    }

    @Test
    void testToolCallStartBackfillWithoutCache() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ToolResultStartEvent("msg-tr1", "tc-unknown", "weather_lookup"),
                                new ToolResultTextDeltaEvent(
                                        "msg-tr1", "tc-unknown", "weather_lookup", "result"),
                                new ToolResultEndEvent(
                                        "msg-tr1",
                                        "tc-unknown",
                                        "weather_lookup",
                                        ToolResultState.SUCCESS)));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hi")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        long toolStartCount =
                events.stream().filter(e -> e instanceof AguiEvent.ToolCallStart).count();
        assertEquals(1, toolStartCount, "Should backfill ToolCallStart for unknown tool result");

        AguiEvent.ToolCallStart backfilledStart =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallStart)
                        .map(e -> (AguiEvent.ToolCallStart) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(backfilledStart, "Should backfill ToolCallStart");
        assertEquals("tc-unknown", backfilledStart.toolCallId());
        assertEquals("weather_lookup", backfilledStart.toolCallName());
    }

    @Test
    void testRunWithThinkingBlockEnabledShowsReasoningContent() {
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ThinkingBlockStartEvent("msg-r1", "b1"),
                                new ThinkingBlockDeltaEvent(
                                        "msg-r1", "b1", "Let me think about this problem."),
                                new ThinkingBlockEndEvent("msg-r1", "b1")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hi")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        long reasoningMessageContentCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageContent).count();
        assertTrue(
                reasoningMessageContentCount > 0,
                "Should have reasoning message content events when enabled");

        long reasoningMessageStartCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageStart).count();
        assertEquals(
                1,
                reasoningMessageStartCount,
                "Should have only 1 start event for reasoning message");
    }

    @Test
    void testRunWithThinkingAndTextMixedContent() {
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ThinkingBlockStartEvent("msg-mixed", "b1"),
                                new ThinkingBlockDeltaEvent(
                                        "msg-mixed", "b1", "I need to analyze this carefully."),
                                new ThinkingBlockEndEvent("msg-mixed", "b1"),
                                new TextBlockStartEvent("msg-mixed", "b2"),
                                new TextBlockDeltaEvent(
                                        "msg-mixed",
                                        "b2",
                                        "Based on my analysis, here's the answer."),
                                new TextBlockEndEvent("msg-mixed", "b2")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Question?")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        boolean hasReasoningMessageStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageStart);
        boolean hasReasoningMessageContent =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageContent);

        boolean hasTextStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageStart);
        boolean hasTextContent =
                events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageContent);

        assertTrue(hasReasoningMessageStart, "Should have ReasoningMessageStart");
        assertTrue(hasReasoningMessageContent, "Should have ReasoningMessageContent");
        assertTrue(hasTextStart, "Should have TextMessageStart for text");
        assertTrue(hasTextContent, "Should have TextMessageContent for text");
    }

    @Test
    void testRunWithEmptyThinkingBlockEmitsStartButNoContent() {
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ThinkingBlockStartEvent("msg-r1", "b1"),
                                new ThinkingBlockDeltaEvent("msg-r1", "b1", ""),
                                new ThinkingBlockEndEvent("msg-r1", "b1")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        boolean hasReasoningMessageStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageStart);
        boolean hasReasoningMessageContent =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageContent);

        assertTrue(hasReasoningMessageStart, "Should have ReasoningMessageStart for the block");
        assertFalse(
                hasReasoningMessageContent,
                "Should NOT have ReasoningMessageContent for empty thinking");
    }

    @Test
    void testRunWithThinkingBlockEndEvent() {
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ThinkingBlockStartEvent("msg-r1", "b1"),
                                new ThinkingBlockDeltaEvent(
                                        "msg-r1", "b1", "Final thinking content"),
                                new ThinkingBlockEndEvent("msg-r1", "b1")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        AguiEvent.ReasoningMessageStart reasoningMessageStart =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ReasoningMessageStart)
                        .map(e -> (AguiEvent.ReasoningMessageStart) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(reasoningMessageStart, "Should have ReasoningMessageStart");

        AguiEvent.ReasoningMessageEnd reasoningMessageEnd =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ReasoningMessageEnd)
                        .map(e -> (AguiEvent.ReasoningMessageEnd) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(reasoningMessageEnd, "Should have ReasoningMessageEnd");
    }

    @Test
    void testToolCallStartBackfillWithNullArgs() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ToolCallStartEvent("msg-tc1", "tc-1", "test_tool"),
                                new ToolCallDeltaEvent("msg-tc1", "tc-1", "test_tool", null),
                                new ToolCallEndEvent("msg-tc1", "tc-1", "test_tool")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        long toolStartCount =
                events.stream().filter(e -> e instanceof AguiEvent.ToolCallStart).count();
        assertEquals(1, toolStartCount, "Should have ToolCallStart even with null args");

        long toolEndCount = events.stream().filter(e -> e instanceof AguiEvent.ToolCallEnd).count();
        assertEquals(1, toolEndCount, "Should have ToolCallEnd");
    }

    @Test
    void testRunWithThinkingAndToolCallMixed() {
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ThinkingBlockStartEvent("msg-mixed", "b1"),
                                new ThinkingBlockDeltaEvent(
                                        "msg-mixed",
                                        "b1",
                                        "I need to use a tool to get the answer."),
                                new ThinkingBlockEndEvent("msg-mixed", "b1"),
                                new ToolCallStartEvent("msg-mixed", "tc-1", "get_weather"),
                                new ToolCallEndEvent("msg-mixed", "tc-1", "get_weather")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        boolean hasReasoningMessageStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageStart);
        boolean hasReasoningMessageContent =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageContent);
        boolean hasToolStart = events.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallStart);

        assertTrue(hasReasoningMessageStart, "Should have ReasoningMessageStart");
        assertTrue(hasReasoningMessageContent, "Should have ReasoningMessageContent");
        assertTrue(hasToolStart, "Should have ToolCallStart for tool call");

        int reasoningStartIdx = -1;
        int reasoningContentIdx = -1;
        int reasoningEndIdx = -1;
        int toolStartIdx = -1;

        for (int i = 0; i < events.size(); i++) {
            AguiEvent e = events.get(i);
            if (reasoningStartIdx < 0 && e instanceof AguiEvent.ReasoningMessageStart) {
                reasoningStartIdx = i;
            } else if (reasoningContentIdx < 0 && e instanceof AguiEvent.ReasoningMessageContent) {
                reasoningContentIdx = i;
            } else if (reasoningEndIdx < 0 && e instanceof AguiEvent.ReasoningMessageEnd) {
                reasoningEndIdx = i;
            } else if (toolStartIdx < 0 && e instanceof AguiEvent.ToolCallStart) {
                toolStartIdx = i;
            }
        }

        assertTrue(reasoningStartIdx >= 0, "Should have ReasoningMessageStart");
        assertTrue(reasoningContentIdx >= 0, "Should have ReasoningMessageContent");
        assertTrue(reasoningEndIdx >= 0, "Should have ReasoningMessageEnd before tool call");
        assertTrue(toolStartIdx >= 0, "Should have ToolCallStart");

        assertTrue(
                reasoningStartIdx < reasoningContentIdx,
                "Reasoning start should be before content");
        assertTrue(reasoningContentIdx < reasoningEndIdx, "Reasoning content should be before end");
        assertTrue(
                reasoningEndIdx < toolStartIdx, "Reasoning should be closed before ToolCallStart");

        long reasoningEndCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageEnd).count();
        assertEquals(1, reasoningEndCount, "Should emit exactly one ReasoningMessageEnd");
    }

    @Test
    void testToolCallStartBackfillWithEmptyArgs() {
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ToolCallStartEvent("msg-tc1", "tc-1", "test_tool"),
                                new ToolCallDeltaEvent("msg-tc1", "tc-1", "test_tool", ""),
                                new ToolCallEndEvent("msg-tc1", "tc-1", "test_tool")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Weather?")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        long toolStartCount =
                events.stream().filter(e -> e instanceof AguiEvent.ToolCallStart).count();
        assertEquals(1, toolStartCount, "Should have ToolCallStart even with empty args");

        long toolEndCount = events.stream().filter(e -> e instanceof AguiEvent.ToolCallEnd).count();
        assertEquals(1, toolEndCount, "Should have ToolCallEnd");
    }

    @Test
    void testRunWithStreamingThinkingBlockEndEvent() {
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ThinkingBlockStartEvent("msg-thinking", "b1"),
                                new ThinkingBlockDeltaEvent("msg-thinking", "b1", "First thought"),
                                new ThinkingBlockDeltaEvent("msg-thinking", "b1", "Second thought"),
                                new ThinkingBlockEndEvent("msg-thinking", "b1")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        long messageContentCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageContent).count();
        assertTrue(
                messageContentCount >= 1, "Should have at least 1 reasoning message content event");

        long messageEndCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageEnd).count();
        assertEquals(1, messageEndCount, "Should have ReasoningMessageEnd");
    }

    @Test
    void testRunWithStreamingThinkingBlockFirstChunk() {
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ThinkingBlockStartEvent("msg-thinking", "b1"),
                                new ThinkingBlockDeltaEvent("msg-thinking", "b1", "First thought"),
                                new ThinkingBlockEndEvent("msg-thinking", "b1")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hi")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        long messageContentCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageContent).count();
        assertEquals(
                1,
                messageContentCount,
                "Should have 1 reasoning message content event for first chunk");

        boolean hasMessageEnd =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageEnd);
        assertTrue(hasMessageEnd, "Should have ReasoningMessageEnd for last event");
    }

    @Test
    void testRunWithNullThinkingBlockEmitsStartButNoContent() {
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ThinkingBlockStartEvent("msg-r1", "b1"),
                                new ThinkingBlockDeltaEvent("msg-r1", "b1", null),
                                new ThinkingBlockEndEvent("msg-r1", "b1")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        boolean hasReasoningMessageStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageStart);
        boolean hasReasoningMessageContent =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageContent);

        assertTrue(hasReasoningMessageStart, "Should have ReasoningMessageStart for the block");
        assertFalse(
                hasReasoningMessageContent,
                "Should NOT have ReasoningMessageContent for null thinking");
    }

    private static AguiTool frontendTool(String name) {
        return new AguiTool(
                name,
                "Frontend tool",
                Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"))));
    }

    private static SchemaOnlyTool schemaOnlyTool(String name) {
        return new SchemaOnlyTool(
                ToolSchema.builder()
                        .name(name)
                        .description("Existing tool")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("query", Map.of("type", "string"))))
                        .build());
    }

    private static final class GhostToolNameToolkit extends Toolkit {

        @Override
        public Set<String> getToolNames() {
            return Set.of("ghost_lookup");
        }
    }
}
