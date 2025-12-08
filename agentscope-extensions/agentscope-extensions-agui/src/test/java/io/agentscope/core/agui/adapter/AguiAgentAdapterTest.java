/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.RawEvent;
import io.agentscope.core.agui.event.RunFinishedEvent;
import io.agentscope.core.agui.event.RunStartedEvent;
import io.agentscope.core.agui.event.TextMessageContentEvent;
import io.agentscope.core.agui.event.TextMessageEndEvent;
import io.agentscope.core.agui.event.TextMessageStartEvent;
import io.agentscope.core.agui.event.ToolCallArgsEvent;
import io.agentscope.core.agui.event.ToolCallEndEvent;
import io.agentscope.core.agui.event.ToolCallStartEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Unit tests for AguiAgentAdapter.
 */
@SuppressWarnings("unchecked")
class AguiAgentAdapterTest {

    private Agent mockAgent;
    private AguiAgentAdapter adapter;

    @BeforeEach
    void setUp() {
        mockAgent = mock(Agent.class);
        adapter = new AguiAgentAdapter(mockAgent, AguiAdapterConfig.defaultConfig());
    }

    @Test
    void testRunReturnsRunStartedAndFinishedEvents() {
        // Agent returns empty stream
        when(mockAgent.stream(anyList(), any(StreamOptions.class))).thenReturn(Flux.empty());

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);
        assertEquals(2, events.size());
        assertInstanceOf(RunStartedEvent.class, events.get(0));
        assertInstanceOf(RunFinishedEvent.class, events.get(1));

        RunStartedEvent started = (RunStartedEvent) events.get(0);
        assertEquals("thread-1", started.getThreadId());
        assertEquals("run-1", started.getRunId());
    }

    @Test
    void testRunWithTextReasoningEvent() {
        Msg reasoningMsg =
                Msg.builder()
                        .id("msg-r1")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Hello, I'm here to help!").build())
                        .build();

        Event reasoningEvent = new Event(EventType.REASONING, reasoningMsg, true);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(reasoningEvent));

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

        // Verify event sequence
        assertInstanceOf(RunStartedEvent.class, events.get(0));

        // Find TextMessage events
        boolean hasTextStart = events.stream().anyMatch(e -> e instanceof TextMessageStartEvent);
        boolean hasTextContent =
                events.stream().anyMatch(e -> e instanceof TextMessageContentEvent);
        boolean hasTextEnd = events.stream().anyMatch(e -> e instanceof TextMessageEndEvent);

        assertTrue(hasTextStart, "Should have TextMessageStartEvent");
        assertTrue(hasTextContent, "Should have TextMessageContentEvent");
        assertTrue(hasTextEnd, "Should have TextMessageEndEvent");

        assertInstanceOf(RunFinishedEvent.class, events.get(events.size() - 1));
    }

    @Test
    void testRunWithStreamingTextEvents() {
        // Simulate streaming: multiple events with same message ID
        Msg chunk1 =
                Msg.builder()
                        .id("msg-stream")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Hello").build())
                        .build();

        Msg chunk2 =
                Msg.builder()
                        .id("msg-stream")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text(", world!").build())
                        .build();

        Event event1 = new Event(EventType.REASONING, chunk1, false);
        Event event2 = new Event(EventType.REASONING, chunk2, true);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(event1, event2));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hi")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // Count TextMessageContentEvents - should have 2 (one for each chunk)
        long contentCount =
                events.stream().filter(e -> e instanceof TextMessageContentEvent).count();
        assertEquals(2, contentCount, "Should have 2 content events for streaming");

        // Should only have 1 TextMessageStartEvent (same message ID)
        long startCount = events.stream().filter(e -> e instanceof TextMessageStartEvent).count();
        assertEquals(1, startCount, "Should have only 1 start event for same message ID");
    }

    @Test
    void testRunWithToolCallEvent() {
        Msg toolCallMsg =
                Msg.builder()
                        .id("msg-tc1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1")
                                        .name("get_weather")
                                        .input(Map.of("city", "Beijing"))
                                        .build())
                        .build();

        Event toolCallEvent = new Event(EventType.REASONING, toolCallMsg, true);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolCallEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Weather?")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // Find ToolCallStartEvent
        ToolCallStartEvent toolStart =
                events.stream()
                        .filter(e -> e instanceof ToolCallStartEvent)
                        .map(e -> (ToolCallStartEvent) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolStart, "Should have ToolCallStartEvent");
        assertEquals("tc-1", toolStart.getToolCallId());
        assertEquals("get_weather", toolStart.getToolCallName());

        // Find ToolCallArgsEvent
        ToolCallArgsEvent toolArgs =
                events.stream()
                        .filter(e -> e instanceof ToolCallArgsEvent)
                        .map(e -> (ToolCallArgsEvent) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolArgs, "Should have ToolCallArgsEvent");
        assertTrue(toolArgs.getDelta().contains("Beijing"));
    }

    @Test
    void testRunWithToolResultEvent() {
        // First: Tool call
        Msg toolCallMsg =
                Msg.builder()
                        .id("msg-tc1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1")
                                        .name("calculator")
                                        .input(Map.of("expr", "2+2"))
                                        .build())
                        .build();

        // Second: Tool result
        Msg toolResultMsg =
                Msg.builder()
                        .id("msg-tr1")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("tc-1")
                                        .output(TextBlock.builder().text("4").build())
                                        .build())
                        .build();

        Event toolCallEvent = new Event(EventType.REASONING, toolCallMsg, true);
        Event toolResultEvent = new Event(EventType.TOOL_RESULT, toolResultMsg, true);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolCallEvent, toolResultEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Calculate")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // Find ToolCallEndEvent (triggered by tool result)
        ToolCallEndEvent toolEnd =
                events.stream()
                        .filter(e -> e instanceof ToolCallEndEvent)
                        .map(e -> (ToolCallEndEvent) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolEnd, "Should have ToolCallEndEvent");
        assertEquals("tc-1", toolEnd.getToolCallId());
    }

    @Test
    void testRunWithAgentError() {
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.error(new RuntimeException("Agent error")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // Should have: RunStarted, RawEvent(error), RunFinished
        assertTrue(events.size() >= 3);
        assertInstanceOf(RunStartedEvent.class, events.get(0));

        // Find error event
        RawEvent errorEvent =
                events.stream()
                        .filter(e -> e instanceof RawEvent)
                        .map(e -> (RawEvent) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(errorEvent, "Should have error RawEvent");
        Map<String, Object> errorData = (Map<String, Object>) errorEvent.getRawEvent();
        assertTrue(errorData.containsKey("error"));

        assertInstanceOf(RunFinishedEvent.class, events.get(events.size() - 1));
    }

    @Test
    void testRunWithEmptyMessages() {
        when(mockAgent.stream(anyList(), any(StreamOptions.class))).thenReturn(Flux.empty());

        RunAgentInput input = RunAgentInput.builder().threadId("thread-1").runId("run-1").build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);
        assertEquals(2, events.size());
        assertInstanceOf(RunStartedEvent.class, events.get(0));
        assertInstanceOf(RunFinishedEvent.class, events.get(1));
    }

    @Test
    void testRunWithDisabledToolCallArgs() {
        AguiAdapterConfig config = AguiAdapterConfig.builder().emitToolCallArgs(false).build();
        AguiAgentAdapter adapterNoArgs = new AguiAgentAdapter(mockAgent, config);

        Msg toolCallMsg =
                Msg.builder()
                        .id("msg-tc1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1")
                                        .name("test_tool")
                                        .input(Map.of("param", "value"))
                                        .build())
                        .build();

        Event toolCallEvent = new Event(EventType.REASONING, toolCallMsg, true);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolCallEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapterNoArgs.run(input).collectList().block();

        assertNotNull(events);

        // Should NOT have ToolCallArgsEvent
        boolean hasToolArgs = events.stream().anyMatch(e -> e instanceof ToolCallArgsEvent);
        assertTrue(!hasToolArgs, "Should NOT have ToolCallArgsEvent when disabled");

        // Should still have ToolCallStartEvent
        boolean hasToolStart = events.stream().anyMatch(e -> e instanceof ToolCallStartEvent);
        assertTrue(hasToolStart, "Should still have ToolCallStartEvent");
    }

    @Test
    void testTextAndToolCallMixedContent() {
        // Message with both text and tool call
        Msg mixedMsg =
                Msg.builder()
                        .id("msg-mixed")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("Let me check the weather for you.")
                                                .build(),
                                        ToolUseBlock.builder()
                                                .id("tc-1")
                                                .name("get_weather")
                                                .input(Map.of("city", "Shanghai"))
                                                .build()))
                        .build();

        Event mixedEvent = new Event(EventType.REASONING, mixedMsg, true);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(mixedEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Weather?")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // Should have text message events AND tool call events
        boolean hasTextStart = events.stream().anyMatch(e -> e instanceof TextMessageStartEvent);
        boolean hasTextContent =
                events.stream().anyMatch(e -> e instanceof TextMessageContentEvent);
        boolean hasTextEnd = events.stream().anyMatch(e -> e instanceof TextMessageEndEvent);
        boolean hasToolStart = events.stream().anyMatch(e -> e instanceof ToolCallStartEvent);

        assertTrue(hasTextStart, "Should have TextMessageStartEvent");
        assertTrue(hasTextContent, "Should have TextMessageContentEvent");
        assertTrue(hasTextEnd, "Should have TextMessageEndEvent");
        assertTrue(hasToolStart, "Should have ToolCallStartEvent");
    }

    @Test
    void testDuplicateToolCallStartNotEmitted() {
        // Same tool call appearing in multiple events (streaming scenario)
        Msg toolCall1 =
                Msg.builder()
                        .id("msg-tc")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1")
                                        .name("tool")
                                        .input(Map.of())
                                        .build())
                        .build();

        Msg toolCall2 =
                Msg.builder()
                        .id("msg-tc")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1") // Same ID
                                        .name("tool")
                                        .input(Map.of())
                                        .build())
                        .build();

        Event event1 = new Event(EventType.REASONING, toolCall1, false);
        Event event2 = new Event(EventType.REASONING, toolCall2, true);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(event1, event2));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // Should only have 1 ToolCallStartEvent (deduplication)
        long toolStartCount = events.stream().filter(e -> e instanceof ToolCallStartEvent).count();
        assertEquals(1, toolStartCount, "Should only emit 1 ToolCallStartEvent per tool ID");
    }

    @Test
    void testReactiveStreamCompletion() {
        Msg reasoningMsg =
                Msg.builder()
                        .id("msg-1")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Done").build())
                        .build();

        Event event = new Event(EventType.REASONING, reasoningMsg, true);
        when(mockAgent.stream(anyList(), any(StreamOptions.class))).thenReturn(Flux.just(event));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("t1")
                        .runId("r1")
                        .messages(List.of(AguiMessage.userMessage("m1", "Hi")))
                        .build();

        StepVerifier.create(adapter.run(input))
                .expectNextMatches(e -> e instanceof RunStartedEvent)
                .expectNextMatches(e -> e instanceof TextMessageStartEvent)
                .expectNextMatches(e -> e instanceof TextMessageContentEvent)
                .expectNextMatches(e -> e instanceof TextMessageEndEvent)
                .expectNextMatches(e -> e instanceof RunFinishedEvent)
                .verifyComplete();
    }
}
