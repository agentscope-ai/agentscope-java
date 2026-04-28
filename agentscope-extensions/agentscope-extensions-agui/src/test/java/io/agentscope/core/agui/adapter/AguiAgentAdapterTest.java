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
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
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
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(1));

        AguiEvent.RunStarted started = (AguiEvent.RunStarted) events.get(0);
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

        Event reasoningEvent = new Event(EventType.REASONING, reasoningMsg, false);
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
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));

        // Find TextMessage events
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
        Event event2 = new Event(EventType.REASONING, chunk2, false);

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

        // Count TextMessageContent events - should have 2 (one for each chunk)
        long contentCount =
                events.stream().filter(e -> e instanceof AguiEvent.TextMessageContent).count();
        assertEquals(2, contentCount, "Should have 2 content events for streaming");

        // Should only have 1 TextMessageStart (same message ID)
        long startCount =
                events.stream().filter(e -> e instanceof AguiEvent.TextMessageStart).count();
        assertEquals(1, startCount, "Should have only 1 start event for same message ID");
    }

    @Test
    void testRunWithSummaryEventUsesTextMessages() {
        Msg summaryChunk =
                Msg.builder()
                        .id("msg-summary")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder()
                                        .text("Here is the conversation summary.")
                                        .build())
                        .build();

        Msg summaryFinal =
                Msg.builder()
                        .id("msg-summary")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder()
                                        .text("Here is the conversation summary.")
                                        .build())
                        .build();

        Event summaryChunkEvent = new Event(EventType.SUMMARY, summaryChunk, false);
        Event summaryFinalEvent = new Event(EventType.SUMMARY, summaryFinal, true);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(summaryChunkEvent, summaryFinalEvent));

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

        assertNotNull(summaryContent, "Should convert SUMMARY to TextMessageContent");
        assertEquals("msg-summary", summaryContent.messageId());
        assertEquals("Here is the conversation summary.", summaryContent.delta());

        long textEndCount =
                events.stream().filter(e -> e instanceof AguiEvent.TextMessageEnd).count();
        assertEquals(1, textEndCount, "Should close the summary text message exactly once");
    }

    @Test
    void testRunWithStreamingSummaryEvents() {
        Msg summaryChunk1 =
                Msg.builder()
                        .id("msg-summary")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("First part. ").build())
                        .build();

        Msg summaryChunk2 =
                Msg.builder()
                        .id("msg-summary")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Second part.").build())
                        .build();

        Msg summaryFinal =
                Msg.builder()
                        .id("msg-summary")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("First part. Second part.").build())
                        .build();

        Event event1 = new Event(EventType.SUMMARY, summaryChunk1, false);
        Event event2 = new Event(EventType.SUMMARY, summaryChunk2, false);
        Event event3 = new Event(EventType.SUMMARY, summaryFinal, true);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(event1, event2, event3));

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
        assertEquals(2, contentCount, "Should stream summary chunks as text deltas");

        long startCount =
                events.stream().filter(e -> e instanceof AguiEvent.TextMessageStart).count();
        assertEquals(1, startCount, "Should only start the summary message once");

        long endCount = events.stream().filter(e -> e instanceof AguiEvent.TextMessageEnd).count();
        assertEquals(1, endCount, "Should only end the summary message once");
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
                                        .content("{\"city\":\"Beijing\"}")
                                        .build())
                        .build();

        Event toolCallEvent = new Event(EventType.REASONING, toolCallMsg, false);
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

        // Find ToolCallStart
        AguiEvent.ToolCallStart toolStart =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallStart)
                        .map(e -> (AguiEvent.ToolCallStart) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolStart, "Should have ToolCallStart");
        assertEquals("tc-1", toolStart.toolCallId());
        assertEquals("get_weather", toolStart.toolCallName());

        // Find ToolCallArgs
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

        // Find ToolCallEnd (triggered before result)
        AguiEvent.ToolCallEnd toolEnd =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallEnd)
                        .map(e -> (AguiEvent.ToolCallEnd) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolEnd, "Should have ToolCallEnd");
        assertEquals("tc-1", toolEnd.toolCallId());
        // ToolCallEnd no longer carries result

        // Find ToolCallResult (triggered by tool result)
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

        // Should have: RunStarted, Raw(error), RunFinished
        assertTrue(events.size() >= 3);
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));

        // Find error event
        AguiEvent.Raw errorEvent =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.Raw)
                        .map(e -> (AguiEvent.Raw) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(errorEvent, "Should have error Raw event");
        Map<String, Object> errorData = (Map<String, Object>) errorEvent.rawEvent();
        assertTrue(errorData.containsKey("error"));

        assertInstanceOf(AguiEvent.RunFinished.class, events.get(events.size() - 1));
    }

    @Test
    void testRunWithEmptyMessages() {
        when(mockAgent.stream(anyList(), any(StreamOptions.class))).thenReturn(Flux.empty());

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

        Event toolCallEvent = new Event(EventType.REASONING, toolCallMsg, false);
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

        // Should NOT have ToolCallArgs
        boolean hasToolArgs = events.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallArgs);
        assertTrue(!hasToolArgs, "Should NOT have ToolCallArgs when disabled");

        // Should still have ToolCallStart
        boolean hasToolStart = events.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallStart);
        assertTrue(hasToolStart, "Should still have ToolCallStart");
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

        Event mixedEvent = new Event(EventType.REASONING, mixedMsg, false);
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

        // Should only have 1 ToolCallStart (deduplication)
        long toolStartCount =
                events.stream().filter(e -> e instanceof AguiEvent.ToolCallStart).count();
        assertEquals(1, toolStartCount, "Should only emit 1 ToolCallStart per tool ID");
    }

    @Test
    void testReactiveStreamCompletion() {
        Msg reasoningMsg =
                Msg.builder()
                        .id("msg-1")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Done").build())
                        .build();

        Event event = new Event(EventType.REASONING, reasoningMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class))).thenReturn(Flux.just(event));

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
    void testToolUseBlockWithNullId() {
        Msg toolCallMsg =
                Msg.builder()
                        .id("msg-tc1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id(null)
                                        .name("test_tool")
                                        .input(Map.of("param", "value"))
                                        .content("{\"param\":\"value\"}")
                                        .build())
                        .build();

        Event toolCallEvent = new Event(EventType.REASONING, toolCallMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolCallEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        StepVerifier.create(adapter.run(input))
                .expectNextMatches(e -> e instanceof AguiEvent.RunStarted)
                .expectNextMatches(e -> e instanceof AguiEvent.ToolCallStart)
                .expectNextMatches(e -> e instanceof AguiEvent.ToolCallArgs)
                .expectNextMatches(e -> e instanceof AguiEvent.ToolCallEnd)
                .expectNextMatches(e -> e instanceof AguiEvent.RunFinished)
                .verifyComplete();
    }

    @Test
    void testTextMessageEndNotDuplicatedWhenLastEventAfterToolCall() {
        // Test that when a text message is interrupted by a tool call and then the last event
        // contains text blocks with the same message ID, only one TextMessageEnd is emitted
        String msgId = "msg-text";
        Msg firstMsg =
                Msg.builder()
                        .id(msgId)
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("first part").build()))
                        .build();

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
        Msg lastMsg =
                Msg.builder()
                        .id(msgId)
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("last part").build()))
                        .build();

        Event firstEvent = new Event(EventType.REASONING, firstMsg, false);
        Event toolCallEvent = new Event(EventType.REASONING, toolCall1, false);
        Event lastEvent = new Event(EventType.REASONING, lastMsg, true);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(firstEvent, toolCallEvent, lastEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // Should have exactly one TextMessageEnd for the same message ID
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
    void testToolUseBlockWithNullIdGeneratesUUID() {
        Msg toolCallMsg =
                Msg.builder()
                        .id("msg-tc1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id(null) // null ID
                                        .name("test_tool")
                                        .input(Map.of("param", "value"))
                                        .build())
                        .build();

        Event toolCallEvent = new Event(EventType.REASONING, toolCallMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolCallEvent));

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
        // Should have generated a UUID (non-null, non-empty string)
        assertNotNull(toolStart.toolCallId(), "Tool call ID should not be null");
        assertTrue(!toolStart.toolCallId().isEmpty(), "Tool call ID should not be empty");
    }

    @Test
    void testTextMessageEndWithLastEventDirectly() {
        // Test that when the last event contains text blocks and the message hasn't been ended,
        // the TextMessageContent is emitted to prevent tail-data loss, followed by TextMessageEnd.
        String msgId = "msg-text";
        Msg textMsg =
                Msg.builder()
                        .id(msgId)
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Hello world").build()))
                        .build();

        // Create a last event directly (isLast = true) without any prior events
        Event lastEvent = new Event(EventType.REASONING, textMsg, true);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(lastEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // Verify that TextMessageEnd is emitted exactly once
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

        // Verify the event sequence
        assertEquals(5, events.size(), "Should emit 5 events exactly");
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        assertInstanceOf(AguiEvent.TextMessageStart.class, events.get(1));
        assertInstanceOf(AguiEvent.TextMessageContent.class, events.get(2));
        assertEquals("Hello world", ((AguiEvent.TextMessageContent) events.get(2)).delta());
        assertInstanceOf(AguiEvent.TextMessageEnd.class, events.get(3));
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(4));
    }

    @Test
    void testExtractToolResultTextWithMultipleTextBlocks() {
        // Test extractToolResultText with multiple TextBlocks (should add newlines)
        Msg toolResultMsg =
                Msg.builder()
                        .id("msg-tr1")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("tc-1")
                                        .output(
                                                List.of(
                                                        TextBlock.builder().text("Line 1").build(),
                                                        TextBlock.builder().text("Line 2").build(),
                                                        TextBlock.builder().text("Line 3").build()))
                                        .build())
                        .build();

        Event toolResultEvent = new Event(EventType.TOOL_RESULT, toolResultMsg, true);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolResultEvent));

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

        // Verify tool result content
        AguiEvent.ToolCallResult toolResult =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallResult)
                        .map(e -> (AguiEvent.ToolCallResult) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolResult, "Should have ToolCallResult");
        // Should contain newlines between text blocks
        assertTrue(toolResult.content().contains("\n"), "Should have newlines between text blocks");
        assertTrue(toolResult.content().contains("Line 1"), "Should contain Line 1");
        assertTrue(toolResult.content().contains("Line 2"), "Should contain Line 2");
        assertTrue(toolResult.content().contains("Line 3"), "Should contain Line 3");
    }

    @Test
    void testExtractToolResultTextWithEmptyOutput() {
        // Test extractToolResultText with empty output
        Msg toolResultMsg =
                Msg.builder()
                        .id("msg-tr1")
                        .role(MsgRole.TOOL)
                        .content(ToolResultBlock.builder().id("tc-1").output(List.of()).build())
                        .build();

        Event toolResultEvent = new Event(EventType.TOOL_RESULT, toolResultMsg, true);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolResultEvent));

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
    void testToolUseBlockWithNullOrEmptyContent() {
        // Test that when ToolUseBlock has null or empty content, ToolCallArgs is not emitted
        Msg toolCallMsg =
                Msg.builder()
                        .id("msg-tc1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1")
                                        .name("test_tool")
                                        .input(Map.of("param", "value"))
                                        .content(null) // null content
                                        .build())
                        .build();

        Event toolCallEvent = new Event(EventType.REASONING, toolCallMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolCallEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // Should still have ToolCallStart
        boolean hasToolStart = events.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallStart);
        assertTrue(hasToolStart, "Should have ToolCallStart");

        // Should NOT have ToolCallArgs when content is null
        boolean hasToolArgs = events.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallArgs);
        assertTrue(!hasToolArgs, "Should NOT have ToolCallArgs when content is null");
    }

    @Test
    void testRunWithThinkingBlockDefaultDisabled() {
        // Test that reasoning is disabled by default
        Msg reasoningMsg =
                Msg.builder()
                        .id("msg-r1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ThinkingBlock.builder()
                                        .thinking("Let me think about this problem step by step...")
                                        .build())
                        .build();

        Event reasoningEvent = new Event(EventType.REASONING, reasoningMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(reasoningEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hi")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // Should NOT have any reasoning events when disabled (default)
        boolean hasReasoningMessageStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageStart);
        boolean hasReasoningMessageContent =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageContent);

        assertTrue(
                !hasReasoningMessageStart, "Should NOT have ReasoningMessageStart when disabled");
        assertTrue(
                !hasReasoningMessageContent,
                "Should NOT have ReasoningMessageContent when disabled");
    }

    @Test
    void testRunWithThinkingBlockEvent() {
        // Test reasoning events when enabled
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        Msg reasoningMsg =
                Msg.builder()
                        .id("msg-r1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ThinkingBlock.builder()
                                        .thinking("Let me think about this problem step by step...")
                                        .build())
                        .build();

        Event reasoningEvent = new Event(EventType.REASONING, reasoningMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(reasoningEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        // Find reasoning events
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
        // Test streaming reasoning events when enabled
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        // Simulate streaming thinking: multiple events with same message ID
        Msg thinkingChunk1 =
                Msg.builder()
                        .id("msg-thinking")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("First thought").build())
                        .build();

        Msg thinkingChunk2 =
                Msg.builder()
                        .id("msg-thinking")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("Second thought").build())
                        .build();

        Event event1 = new Event(EventType.REASONING, thinkingChunk1, false);
        Event event2 = new Event(EventType.REASONING, thinkingChunk2, false);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(event1, event2));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Calculate")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        // Count ReasoningMessageContent events
        long reasoningMessageContentCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageContent).count();
        assertEquals(
                2,
                reasoningMessageContentCount,
                "Should have 2 reasoning message content events for streaming");

        // Should only have 1 ReasoningMessageStart
        long reasoningMessageStartCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageStart).count();
        assertEquals(
                1,
                reasoningMessageStartCount,
                "Should have only 1 start event for same reasoning message ID");
    }

    @Test
    void testStateLeakOnMultipleSubscriptions() {
        // Verifies the fix for Issue #510
        String bugMessageId = "chatcmpl-afaa1eae32eae120";
        String bugToolId = "chatcmpl-tool-ab42f73d312799c7";

        // Simulate the first streaming text chunk
        Msg textChunk =
                Msg.builder()
                        .id(bugMessageId)
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("Preparing to call the tool...")
                                                .build()))
                        .build();
        Event textEvent = new Event(EventType.REASONING, textChunk, false);

        Msg toolCallMsg =
                Msg.builder()
                        .id(bugMessageId)
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id(bugToolId)
                                                .name("getUniversityInfo")
                                                .input(Map.of())
                                                .build()))
                        .build();
        Event toolEvent = new Event(EventType.REASONING, toolCallMsg, false);

        // Mock the agent stream to return the same events for every subscription
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(textEvent, toolEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("2010331348305129474")
                        .runId("17816087-d4aa-4743-baee-9989a4ab3c8d")
                        .messages(
                                List.of(
                                        AguiMessage.userMessage(
                                                "msg-1", "Check university score lines")))
                        .build();

        // Get the Flux pipeline to test
        Flux<AguiEvent> resultFlux = adapter.run(input);

        // First subscription: simulate a normal initial streaming request
        List<AguiEvent> firstRunEvents = resultFlux.collectList().block();
        assertNotNull(firstRunEvents);

        long firstRunStartCount =
                firstRunEvents.stream()
                        .filter(e -> e instanceof AguiEvent.TextMessageStart)
                        .count();
        assertEquals(1, firstRunStartCount, "First execution should contain 1 TextMessageStart");

        // Second subscription: simulate an automatic retry or buffer flush from the adapter layer
        List<AguiEvent> secondRunEvents = resultFlux.collectList().block();
        assertNotNull(secondRunEvents);

        long secondRunStartCount =
                secondRunEvents.stream()
                        .filter(e -> e instanceof AguiEvent.TextMessageStart)
                        .count();

        // Verify state isolation is effective; the second subscription should emit START normally
        assertEquals(
                1,
                secondRunStartCount,
                "State should be isolated; second execution should contain 1 TextMessageStart");

        // Verify that CONTENT is still emitted
        long secondRunContentCount =
                secondRunEvents.stream()
                        .filter(e -> e instanceof AguiEvent.TextMessageContent)
                        .count();
        assertTrue(secondRunContentCount > 0, "Second execution should contain TextMessageContent");
    }

    @Test
    void testToolCallStartBackfillWithoutCache() {
        Msg toolResultMsg =
                Msg.builder()
                        .id("msg-tr1")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("tc-unknown")
                                        .name("weather_lookup")
                                        .output(TextBlock.builder().text("result").build())
                                        .build())
                        .build();

        Event toolResultEvent = new Event(EventType.TOOL_RESULT, toolResultMsg, true);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolResultEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hi")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // Should have ToolCallStart backfilled for unknown tool result
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
        // Test that when reasoning is enabled
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        Msg reasoningMsg =
                Msg.builder()
                        .id("msg-r1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ThinkingBlock.builder()
                                        .thinking("Let me think about this problem step by step...")
                                        .build())
                        .build();

        Event reasoningEvent = new Event(EventType.REASONING, reasoningMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(reasoningEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hi")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        // Should have ReasoningMessageContent events
        long reasoningMessageContentCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageContent).count();
        assertTrue(
                reasoningMessageContentCount > 0,
                "Should have reasoning message content events when enabled");

        // Should have ReasoningMessageStart
        long reasoningMessageStartCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageStart).count();
        assertEquals(
                1,
                reasoningMessageStartCount,
                "Should have only 1 start event for reasoning message");
    }

    @Test
    void testRunWithThinkingAndTextMixedContent() {
        // Test reasoning and text mixed content when enabled
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        // Message with both thinking and text
        Msg mixedMsg =
                Msg.builder()
                        .id("msg-mixed")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ThinkingBlock.builder()
                                                .thinking("I need to analyze this carefully.")
                                                .build(),
                                        TextBlock.builder()
                                                .text("Based on my analysis, here's the answer.")
                                                .build()))
                        .build();

        Event mixedEvent = new Event(EventType.REASONING, mixedMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(mixedEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Question?")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        // Should have reasoning events
        boolean hasReasoningMessageStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageStart);
        boolean hasReasoningMessageContent =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageContent);

        // Should have regular text message events
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
    void testRunWithEmptyThinkingBlock() {
        // Empty thinking block should be skipped even when enabled
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        Msg reasoningMsg =
                Msg.builder()
                        .id("msg-r1")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("").build())
                        .build();

        Event reasoningEvent = new Event(EventType.REASONING, reasoningMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(reasoningEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        // Should NOT have any reasoning events for empty thinking
        boolean hasReasoningMessageStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageStart);

        assertTrue(
                !hasReasoningMessageStart,
                "Should NOT have ReasoningMessageStart for empty thinking");
    }

    @Test
    void testRunWithThinkingBlockLastEvent() {
        // Test the isLast() == true branch for ThinkingBlock when enabled
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        Msg reasoningMsg =
                Msg.builder()
                        .id("msg-r1")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("Final thinking content").build())
                        .build();

        Event reasoningEvent = new Event(EventType.REASONING, reasoningMsg, true); // isLast = true
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(reasoningEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        // Should have ReasoningMessageStart
        AguiEvent.ReasoningMessageStart reasoningMessageStart =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ReasoningMessageStart)
                        .map(e -> (AguiEvent.ReasoningMessageStart) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(reasoningMessageStart, "Should have ReasoningMessageStart");

        // Should have ReasoningMessageEnd when isLast=true
        AguiEvent.ReasoningMessageEnd reasoningMessageEnd =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ReasoningMessageEnd)
                        .map(e -> (AguiEvent.ReasoningMessageEnd) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(reasoningMessageEnd, "Should have ReasoningMessageEnd when isLast=true");
    }

    @Test
    void testToolCallStartBackfillWithNullContent() {
        Msg toolCallMsg =
                Msg.builder()
                        .id("msg-tc1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1")
                                        .name("test_tool")
                                        .input(Map.of("param", "value"))
                                        .content(null)
                                        .build())
                        .build();

        Event toolCallEvent = new Event(EventType.REASONING, toolCallMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolCallEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // Should have ToolCallStart even with null content
        long toolStartCount =
                events.stream().filter(e -> e instanceof AguiEvent.ToolCallStart).count();
        assertEquals(1, toolStartCount, "Should have ToolCallStart even with null content");

        // Should have ToolCallEnd
        long toolEndCount = events.stream().filter(e -> e instanceof AguiEvent.ToolCallEnd).count();
        assertEquals(1, toolEndCount, "Should have ToolCallEnd");
    }

    @Test
    void testRunWithThinkingAndToolCallMixed() {
        // Test thinking content mixed with tool call when enabled
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        Msg mixedMsg =
                Msg.builder()
                        .id("msg-mixed")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ThinkingBlock.builder()
                                                .thinking("I need to use a tool to get the answer.")
                                                .build(),
                                        ToolUseBlock.builder()
                                                .id("tc-1")
                                                .name("get_weather")
                                                .input(Map.of("city", "Beijing"))
                                                .build()))
                        .build();

        Event mixedEvent = new Event(EventType.REASONING, mixedMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(mixedEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        // Should have reasoning events
        boolean hasReasoningMessageStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageStart);
        boolean hasReasoningMessageContent =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageContent);

        // Should have tool call events
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

        // ReasoningMessage must be explicitly closed before ToolCallStart.
        long reasoningEndCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageEnd).count();
        assertEquals(1, reasoningEndCount, "Should emit exactly one ReasoningMessageEnd");
    }

    @Test
    void testToolCallStartBackfillWithEmptyContent() {
        Msg toolCallMsg =
                Msg.builder()
                        .id("msg-tc1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1")
                                        .name("test_tool")
                                        .input(Map.of("param", "value"))
                                        .content("")
                                        .build())
                        .build();

        Event toolCallEvent = new Event(EventType.REASONING, toolCallMsg, false);
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

        // Should have tool call events even with empty content
        long toolStartCount =
                events.stream().filter(e -> e instanceof AguiEvent.ToolCallStart).count();
        assertEquals(1, toolStartCount, "Should have ToolCallStart even with empty content");

        long toolEndCount = events.stream().filter(e -> e instanceof AguiEvent.ToolCallEnd).count();
        assertEquals(1, toolEndCount, "Should have ToolCallEnd");
    }

    @Test
    void testRunWithStreamingThinkingBlockLastEvent() {
        // Test streaming with last event (isLast=true) when enabled
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        Msg thinkingChunk1 =
                Msg.builder()
                        .id("msg-thinking")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("First thought").build())
                        .build();

        Msg thinkingChunk2 =
                Msg.builder()
                        .id("msg-thinking")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("Second thought").build())
                        .build();

        Event event1 = new Event(EventType.REASONING, thinkingChunk1, false);
        Event event2 = new Event(EventType.REASONING, thinkingChunk2, true); // Last event

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(event1, event2));

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

        // Should have ReasoningMessageEnd when isLast=true
        long messageEndCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageEnd).count();
        assertEquals(1, messageEndCount, "Should have ReasoningMessageEnd when isLast=true");
    }

    @Test
    void testRunWithStreamingThinkingBlockFirstChunk() {
        // Test streaming with first chunk (isLast=false) when enabled
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        Msg thinkingChunk1 =
                Msg.builder()
                        .id("msg-thinking")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("First thought").build())
                        .build();

        Event event1 = new Event(EventType.REASONING, thinkingChunk1, false); // Not last

        when(mockAgent.stream(anyList(), any(StreamOptions.class))).thenReturn(Flux.just(event1));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hi")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        // Should have ReasoningMessageContent for first chunk
        long messageContentCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageContent).count();
        assertEquals(
                1,
                messageContentCount,
                "Should have 1 reasoning message content event for first chunk");

        // Should have ReasoningMessageEnd for last event
        boolean hasMessageEnd =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageEnd);
        assertTrue(hasMessageEnd, "Should have ReasoningMessageEnd for last event");
    }

    @Test
    void testRunWithNullThinkingBlock() {
        // ThinkingBlock with null thinking should be converted to empty string and skipped
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        Msg reasoningMsg =
                Msg.builder()
                        .id("msg-r1")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking(null).build())
                        .build();

        Event reasoningEvent = new Event(EventType.REASONING, reasoningMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(reasoningEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        // Should NOT have any reasoning events for null/empty thinking
        boolean hasReasoningMessageStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageStart);

        assertTrue(
                !hasReasoningMessageStart,
                "Should NOT have ReasoningMessageStart for null thinking");
    }

    @Test
    void testToolResultProgressStreamingAndAccumulation() {
        // Test that tool intermediate progress is converted to reasoning blocks
        // and final result is accumulated correctly.
        AguiAdapterConfig config =
                AguiAdapterConfig.builder().enableToolProgressStream(true).build();
        AguiAgentAdapter adapterWithProgress = new AguiAgentAdapter(mockAgent, config);

        // Chunk 1: isLast = false
        Msg trChunk1 =
                Msg.builder()
                        .id("msg-tr1")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("tc-1")
                                        .output(TextBlock.builder().text("Downloading 50%").build())
                                        .build())
                        .build();
        Event event1 = new Event(EventType.TOOL_RESULT, trChunk1, false);

        // Chunk 2: isLast = true
        Msg trChunk2 =
                Msg.builder()
                        .id("msg-tr1")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("tc-1")
                                        .output(TextBlock.builder().text(", Done").build())
                                        .build())
                        .build();
        Event event2 = new Event(EventType.TOOL_RESULT, trChunk2, true);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(event1, event2));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Download it")))
                        .build();

        List<AguiEvent> events = adapterWithProgress.run(input).collectList().block();
        assertNotNull(events);

        String expectedThinkingId = "tool-thinking-tc-1";

        // Verify fake reasoning block for progress was created and ended
        boolean hasFakeReasoningStart =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ReasoningMessageStart)
                        .anyMatch(
                                e ->
                                        expectedThinkingId.equals(
                                                ((AguiEvent.ReasoningMessageStart) e).messageId()));
        assertTrue(hasFakeReasoningStart, "Should emit ReasoningMessageStart for tool progress");

        boolean hasFakeReasoningEnd =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ReasoningMessageEnd)
                        .anyMatch(
                                e ->
                                        expectedThinkingId.equals(
                                                ((AguiEvent.ReasoningMessageEnd) e).messageId()));
        assertTrue(hasFakeReasoningEnd, "Should emit ReasoningMessageEnd to close tool progress");

        // Verify final accumulated result
        AguiEvent.ToolCallResult toolResult =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallResult)
                        .map(e -> (AguiEvent.ToolCallResult) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolResult, "Should have ToolCallResult");
        assertEquals(", Done", toolResult.content(), "Should accumulate all chunks");
    }

    @Test
    void testTailEndDataPreservationWhenIsLastIsTrue() {
        // Test that content in the very last event (isLast=true) is NOT dropped
        Msg lastMsgWithContent =
                Msg.builder()
                        .id("msg-text")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Last crucial words.").build())
                        .build();

        // One single event, carries content AND isLast=true
        Event event = new Event(EventType.REASONING, lastMsgWithContent, true);
        when(mockAgent.stream(anyList(), any(StreamOptions.class))).thenReturn(Flux.just(event));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("t1")
                        .runId("r1")
                        .messages(List.of(AguiMessage.userMessage("m1", "Hi")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();
        assertNotNull(events);

        // Find events associated with this message
        boolean hasContent =
                events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageContent);
        boolean hasEnd = events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageEnd);

        assertTrue(hasContent, "Must emit TextMessageContent even if isLast is true");
        assertTrue(hasEnd, "Must emit TextMessageEnd");

        // Verify Content comes before End
        int contentIndex =
                events.indexOf(
                        events.stream()
                                .filter(e -> e instanceof AguiEvent.TextMessageContent)
                                .findFirst()
                                .get());
        int endIndex =
                events.indexOf(
                        events.stream()
                                .filter(e -> e instanceof AguiEvent.TextMessageEnd)
                                .findFirst()
                                .get());
        assertTrue(contentIndex < endIndex, "Content must be emitted before End");
    }

    @Test
    void testEmptyContentListClosesActiveLifecycle() {
        // Test that an empty content list with isLast=true properly closes active states
        Msg msg1 =
                Msg.builder()
                        .id("msg-1")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Start ").build())
                        .build();
        Event event1 = new Event(EventType.REASONING, msg1, false);

        // Empty content, but signals end
        Msg msg2 =
                Msg.builder()
                        .id("msg-1")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of()) // Empty content!
                        .build();
        Event event2 = new Event(EventType.REASONING, msg2, true);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(event1, event2));

        RunAgentInput input = RunAgentInput.builder().threadId("t1").runId("r1").build();

        List<AguiEvent> events = adapter.run(input).collectList().block();
        assertNotNull(events);

        long endCount = events.stream().filter(e -> e instanceof AguiEvent.TextMessageEnd).count();
        assertEquals(
                1,
                endCount,
                "Should successfully close the text message even if last content block is empty");
    }

    @Test
    void testToolCallArgsTailEndPreservation() {
        // Test that tool argument JSON in the final chunk is not dropped
        Msg toolMsg =
                Msg.builder()
                        .id("msg-tc1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1")
                                        .name("tool")
                                        .content("}") // The final closing bracket
                                        .build())
                        .build();

        // The tool use block comes with isLast=true
        Event event = new Event(EventType.REASONING, toolMsg, true);
        when(mockAgent.stream(anyList(), any(StreamOptions.class))).thenReturn(Flux.just(event));

        RunAgentInput input = RunAgentInput.builder().threadId("t1").runId("r1").build();

        List<AguiEvent> events = adapter.run(input).collectList().block();
        assertNotNull(events);

        AguiEvent.ToolCallArgs toolArgs =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallArgs)
                        .map(e -> (AguiEvent.ToolCallArgs) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolArgs, "Must emit ToolCallArgs even if event.isLast() is true");
        assertEquals("}", toolArgs.delta());
    }
}
