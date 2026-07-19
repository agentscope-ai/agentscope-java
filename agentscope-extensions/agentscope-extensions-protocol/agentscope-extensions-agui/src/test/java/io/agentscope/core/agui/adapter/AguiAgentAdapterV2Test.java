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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.DataBlockStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

@SuppressWarnings("unchecked")
class AguiAgentAdapterV2Test {

    @Test
    void testRunUsesReActStreamEventsWithRuntimeContext() {
        ReActAgent agent = mock(ReActAgent.class);
        ArgumentCaptor<RuntimeContext> contextCaptor =
                ArgumentCaptor.forClass(RuntimeContext.class);
        when(agent.streamEvents(anyList(), contextCaptor.capture()))
                .thenReturn(Flux.just(new AgentStartEvent("thread-v2", "reply-1", "react")));

        RunAgentInput input =
                inputBuilder()
                        .tools(List.of())
                        .context(List.of())
                        .state(Map.of("cursor", 1))
                        .forwardedProps(Map.of("tenant", "demo"))
                        .build();

        List<AguiEvent> events =
                new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                        .run(input)
                        .collectList()
                        .block();

        assertNotNull(events);
        assertEquals(List.of(AguiEventType.RUN_STARTED, AguiEventType.RUN_FINISHED), types(events));
        RuntimeContext context = contextCaptor.getValue();
        assertEquals("thread-v2", context.getSessionId());
        assertSame(input, context.get(RunAgentInput.class));
        assertSame(input.getForwardedProps(), context.get("agui.forwardedProps"));
    }

    @Test
    void testRunUsesHarnessStreamEventsViaReflection() {
        HarnessAgent agent = new HarnessAgent();
        agent.setEvents(Flux.just(new TextBlockDeltaEvent("reply-harness", "block-1", "hi")));

        List<AguiEvent> events =
                new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                        .run(input())
                        .collectList()
                        .block();

        assertNotNull(events);
        assertEquals("thread-v2", agent.getSeenContext().getSessionId());
        assertEquals("run-v2", agent.getSeenContext().get("agui.runId"));
        assertEquals(1, agent.getSeenMessages().size());
        assertTrue(events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageContent));
    }

    @Test
    void testRunFallsBackToLegacyStreamForGenericAgent() {
        Agent agent = mock(Agent.class);
        when(agent.stream(anyList(), any(StreamOptions.class))).thenReturn(Flux.empty());

        List<AguiEvent> events =
                new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                        .run(input())
                        .collectList()
                        .block();

        assertNotNull(events);
        verify(agent).stream(anyList(), any(StreamOptions.class));
        verify(agent, never()).stream(
                anyList(), any(StreamOptions.class), any(RuntimeContext.class));
        assertEquals(List.of(AguiEventType.RUN_STARTED, AguiEventType.RUN_FINISHED), types(events));
    }

    @Test
    void testTextBlockEventsConvertToAguiTextMessageEvents() {
        List<AguiEvent> events =
                runReActEvents(
                        new TextBlockStartEvent("reply-text", "block-1"),
                        new TextBlockDeltaEvent("reply-text", "block-1", "hel"),
                        new TextBlockDeltaEvent("reply-text", "block-1", "lo"),
                        new TextBlockEndEvent("reply-text", "block-1"));

        assertEquals(
                List.of(
                        AguiEventType.RUN_STARTED,
                        AguiEventType.TEXT_MESSAGE_START,
                        AguiEventType.TEXT_MESSAGE_CONTENT,
                        AguiEventType.TEXT_MESSAGE_CONTENT,
                        AguiEventType.TEXT_MESSAGE_END,
                        AguiEventType.RUN_FINISHED),
                types(events));
        AguiEvent.TextMessageContent firstDelta =
                assertInstanceOf(AguiEvent.TextMessageContent.class, events.get(2));
        assertEquals("hel", firstDelta.delta());
    }

    @Test
    void testThinkingEventsAreIgnoredWhenReasoningDisabled() {
        List<AguiEvent> events =
                runReActEvents(
                        new ThinkingBlockStartEvent("reply-thinking", "block-1"),
                        new ThinkingBlockDeltaEvent("reply-thinking", "block-1", "hidden"),
                        new ThinkingBlockEndEvent("reply-thinking", "block-1"));

        assertFalse(events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageContent));
        assertEquals(List.of(AguiEventType.RUN_STARTED, AguiEventType.RUN_FINISHED), types(events));
    }

    @Test
    void testThinkingEventsConvertWhenReasoningEnabled() {
        List<AguiEvent> events =
                runReActEvents(
                        AguiAdapterConfig.builder().enableReasoning(true).build(),
                        new ThinkingBlockStartEvent("reply-thinking", "block-1"),
                        new ThinkingBlockDeltaEvent("reply-thinking", "block-1", "visible"),
                        new ThinkingBlockEndEvent("reply-thinking", "block-1"));

        assertEquals(
                List.of(
                        AguiEventType.RUN_STARTED,
                        AguiEventType.REASONING_MESSAGE_START,
                        AguiEventType.REASONING_MESSAGE_CONTENT,
                        AguiEventType.REASONING_MESSAGE_END,
                        AguiEventType.RUN_FINISHED),
                types(events));
    }

    @Test
    void testToolCallArgsRespectConfig() {
        List<AguiEvent> enabled =
                runReActEvents(
                        new ToolCallStartEvent("reply-tool", "tool-1", "lookup"),
                        new ToolCallDeltaEvent("reply-tool", "tool-1", "lookup", "{\"q\""),
                        new ToolCallEndEvent("reply-tool", "tool-1", "lookup"));
        List<AguiEvent> disabled =
                runReActEvents(
                        AguiAdapterConfig.builder().emitToolCallArgs(false).build(),
                        new ToolCallStartEvent("reply-tool", "tool-1", "lookup"),
                        new ToolCallDeltaEvent("reply-tool", "tool-1", "lookup", "{\"q\""),
                        new ToolCallEndEvent("reply-tool", "tool-1", "lookup"));

        assertTrue(enabled.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallArgs));
        assertFalse(disabled.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallArgs));
    }

    @Test
    void testToolResultDeltasAreAggregatedIntoToolCallResult() {
        List<AguiEvent> events =
                runReActEvents(
                        new ToolCallStartEvent("reply-tool", "tool-1", "lookup"),
                        new ToolCallEndEvent("reply-tool", "tool-1", "lookup"),
                        new ToolResultStartEvent("reply-tool", "tool-1", "lookup"),
                        new ToolResultTextDeltaEvent("reply-tool", "tool-1", "lookup", "hel"),
                        new ToolResultTextDeltaEvent("reply-tool", "tool-1", "lookup", "lo"),
                        new ToolResultEndEvent("reply-tool", "tool-1", "lookup", null));

        AguiEvent.ToolCallResult result =
                events.stream()
                        .filter(AguiEvent.ToolCallResult.class::isInstance)
                        .map(AguiEvent.ToolCallResult.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertEquals("hello", result.content());
        assertEquals("reply-tool", result.messageId());
    }

    @Test
    void testToolResultDataDeltaContributesToToolCallResult() {
        List<AguiEvent> events =
                runReActEvents(
                        new ToolResultStartEvent("reply-tool", "tool-1", "lookup"),
                        new ToolResultDataDeltaEvent(
                                "reply-tool",
                                "tool-1",
                                "lookup",
                                TextBlock.builder().text("data").build()),
                        new ToolResultEndEvent("reply-tool", "tool-1", "lookup", null));

        AguiEvent.ToolCallResult result =
                events.stream()
                        .filter(AguiEvent.ToolCallResult.class::isInstance)
                        .map(AguiEvent.ToolCallResult.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertEquals("data", result.content());
    }

    @Test
    void testCustomAndUnmappedEventsConvertWithoutDroppingSignals() {
        List<AguiEvent> events =
                runReActEvents(
                        new CustomEvent("state_updated", Map.of("task", "done")),
                        new DataBlockStartEvent("reply-raw", "data-1"));

        assertTrue(events.stream().anyMatch(e -> e instanceof AguiEvent.Custom));
        assertTrue(events.stream().anyMatch(e -> e instanceof AguiEvent.Raw));
    }

    @Test
    void testTextAndToolCallOrderFollowsCoreBlockLifecycle() {
        List<AguiEvent> events =
                runReActEvents(
                        new TextBlockDeltaEvent("reply-mixed", "text-1", "hello"),
                        new TextBlockEndEvent("reply-mixed", "text-1"),
                        new ToolCallStartEvent("reply-mixed", "tool-1", "lookup"),
                        new ToolCallEndEvent("reply-mixed", "tool-1", "lookup"));

        assertEquals(
                List.of(
                        AguiEventType.RUN_STARTED,
                        AguiEventType.TEXT_MESSAGE_START,
                        AguiEventType.TEXT_MESSAGE_CONTENT,
                        AguiEventType.TEXT_MESSAGE_END,
                        AguiEventType.TOOL_CALL_START,
                        AguiEventType.TOOL_CALL_END,
                        AguiEventType.RUN_FINISHED),
                types(events));
    }

    @Test
    void testTextAndThinkingStartOnlyEventsDoNotEmitEmptyMessages() {
        List<AguiEvent> textEvents =
                runReActEvents(
                        new TextBlockStartEvent("reply-empty-text", "text-1"),
                        new TextBlockEndEvent("reply-empty-text", "text-1"));
        List<AguiEvent> thinkingEvents =
                runReActEvents(
                        AguiAdapterConfig.builder().enableReasoning(true).build(),
                        new ThinkingBlockStartEvent("reply-empty-thinking", "thinking-1"),
                        new ThinkingBlockEndEvent("reply-empty-thinking", "thinking-1"));

        assertEquals(
                List.of(AguiEventType.RUN_STARTED, AguiEventType.RUN_FINISHED), types(textEvents));
        assertEquals(
                List.of(AguiEventType.RUN_STARTED, AguiEventType.RUN_FINISHED),
                types(thinkingEvents));
    }

    @Test
    void testToolCallWithoutArgsStillEmitsStartAndEnd() {
        List<AguiEvent> events =
                runReActEvents(
                        new ToolCallStartEvent("reply-tool", "tool-1", "lookup"),
                        new ToolCallEndEvent("reply-tool", "tool-1", "lookup"));

        assertEquals(
                List.of(
                        AguiEventType.RUN_STARTED,
                        AguiEventType.TOOL_CALL_START,
                        AguiEventType.TOOL_CALL_END,
                        AguiEventType.RUN_FINISHED),
                types(events));
    }

    @Test
    void testRunEmitsErrorEventsWhenStreamEventsThrowsBeforeReturningFlux() {
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenThrow(new IllegalStateException("boom"));

        List<AguiEvent> events =
                new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                        .run(input())
                        .collectList()
                        .block();

        assertErrorRun(events, "boom", "INVALID_INPUT_ERROR");
    }

    @Test
    void testRunEmitsErrorEventsWhenStreamEventsFailsMidStream() {
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.error(new RuntimeException("boom")));

        List<AguiEvent> events =
                new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                        .run(input())
                        .collectList()
                        .block();

        assertErrorRun(events, "boom", "INTERNAL_ERROR");
    }

    @Test
    void testRunClosesTextMessageBeforeErrorWhenStreamEventsFailsAfterDelta() {
        List<AguiEvent> events =
                runReActFlux(
                        Flux.concat(
                                Flux.just(new TextBlockDeltaEvent("reply-text", "text-1", "hi")),
                                Flux.error(new RuntimeException("boom"))));

        assertEquals(
                List.of(
                        AguiEventType.RUN_STARTED,
                        AguiEventType.TEXT_MESSAGE_START,
                        AguiEventType.TEXT_MESSAGE_CONTENT,
                        AguiEventType.TEXT_MESSAGE_END,
                        AguiEventType.RUN_ERROR,
                        AguiEventType.RUN_FINISHED),
                types(events));
    }

    @Test
    void testRunClosesReasoningMessageBeforeErrorWhenStreamEventsFailsAfterDelta() {
        List<AguiEvent> events =
                runReActFlux(
                        AguiAdapterConfig.builder().enableReasoning(true).build(),
                        Flux.concat(
                                Flux.just(
                                        new ThinkingBlockDeltaEvent(
                                                "reply-thinking", "thinking-1", "thinking")),
                                Flux.error(new RuntimeException("boom"))));

        assertEquals(
                List.of(
                        AguiEventType.RUN_STARTED,
                        AguiEventType.REASONING_MESSAGE_START,
                        AguiEventType.REASONING_MESSAGE_CONTENT,
                        AguiEventType.REASONING_MESSAGE_END,
                        AguiEventType.RUN_ERROR,
                        AguiEventType.RUN_FINISHED),
                types(events));
    }

    @Test
    void testRunClosesToolCallBeforeErrorWhenStreamEventsFailsAfterArgs() {
        List<AguiEvent> events =
                runReActFlux(
                        Flux.concat(
                                Flux.just(
                                        new ToolCallDeltaEvent(
                                                "reply-tool", "tool-1", "lookup", "{\"q\"")),
                                Flux.error(new RuntimeException("boom"))));

        assertEquals(
                List.of(
                        AguiEventType.RUN_STARTED,
                        AguiEventType.TOOL_CALL_START,
                        AguiEventType.TOOL_CALL_ARGS,
                        AguiEventType.TOOL_CALL_END,
                        AguiEventType.RUN_ERROR,
                        AguiEventType.RUN_FINISHED),
                types(events));
    }

    @Test
    void testRunBackfillsRecordedToolCallBeforeErrorWhenStreamEventsFailsAfterStart() {
        List<AguiEvent> events =
                runReActFlux(
                        Flux.concat(
                                Flux.just(new ToolCallStartEvent("reply-tool", "tool-1", "lookup")),
                                Flux.error(new RuntimeException("boom"))));

        assertEquals(
                List.of(
                        AguiEventType.RUN_STARTED,
                        AguiEventType.TOOL_CALL_START,
                        AguiEventType.TOOL_CALL_END,
                        AguiEventType.RUN_ERROR,
                        AguiEventType.RUN_FINISHED),
                types(events));
    }

    private static List<AguiEvent> runReActEvents(AgentEvent... agentEvents) {
        return runReActEvents(AguiAdapterConfig.defaultConfig(), agentEvents);
    }

    private static List<AguiEvent> runReActEvents(
            AguiAdapterConfig config, AgentEvent... agentEvents) {
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.fromArray(agentEvents));
        return new AguiAgentAdapter(agent, config).run(input()).collectList().block();
    }

    private static List<AguiEvent> runReActFlux(Flux<AgentEvent> agentEvents) {
        return runReActFlux(AguiAdapterConfig.defaultConfig(), agentEvents);
    }

    private static List<AguiEvent> runReActFlux(
            AguiAdapterConfig config, Flux<AgentEvent> agentEvents) {
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(agentEvents);
        return new AguiAgentAdapter(agent, config).run(input()).collectList().block();
    }

    private static void assertErrorRun(List<AguiEvent> events, String message, String code) {
        assertNotNull(events);
        assertEquals(3, events.size());
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        AguiEvent.RunError runError = assertInstanceOf(AguiEvent.RunError.class, events.get(1));
        assertEquals(message, runError.message());
        assertEquals(code, runError.code());
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(2));
    }

    private static List<AguiEventType> types(List<AguiEvent> events) {
        return events.stream().map(AguiEvent::getType).toList();
    }

    private static RunAgentInput input() {
        return inputBuilder().build();
    }

    private static RunAgentInput.Builder inputBuilder() {
        return RunAgentInput.builder()
                .threadId("thread-v2")
                .runId("run-v2")
                .messages(List.of(AguiMessage.userMessage("msg-1", "hello")));
    }
}
