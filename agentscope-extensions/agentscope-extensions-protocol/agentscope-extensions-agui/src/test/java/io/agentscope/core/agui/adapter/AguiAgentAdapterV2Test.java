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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * Unit tests for the {@link AguiAgentAdapter} v2 event-stream migration and the human-in-the-loop
 * (HITL) bridging added to close agentscope-java issue #2437.
 *
 * <p>These tests exercise the {@code agent instanceof ReActAgent} branch, which consumes the
 * fine-grained {@link AgentEvent} stream from {@link ReActAgent#streamEvents(List, RuntimeContext)}
 * and translates it into AG-UI protocol events. The existing {@code AguiAgentAdapterTest} continues
 * to cover the deprecated v1 {@code stream(...)} fallback path.
 */
class AguiAgentAdapterV2Test {

    private static final String REPLY = "reply-1";

    private ReActAgent mockReActAgent() {
        return mock(ReActAgent.class);
    }

    private AguiAgentAdapter adapterFor(ReActAgent agent, AguiAdapterConfig config) {
        return new AguiAgentAdapter(agent, config);
    }

    private void stubStream(ReActAgent agent, AgentEvent... events) {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.fromArray(events));
    }

    private RunAgentInput input() {
        return RunAgentInput.builder()
                .threadId("thread-1")
                .runId("run-1")
                .messages(List.of(AguiMessage.userMessage("m-1", "Hello")))
                .build();
    }

    private RunAgentInput inputWithForwardedProps(Map<String, Object> props) {
        return RunAgentInput.builder()
                .threadId("thread-1")
                .runId("run-1")
                .messages(List.of(AguiMessage.userMessage("m-1", "Hello")))
                .forwardedProps(props)
                .build();
    }

    // ------------------------------------------------------------------
    // v2 migration: text streaming
    // ------------------------------------------------------------------

    @Test
    void textBlockEventsMapToTextMessageEvents() {
        ReActAgent agent = mockReActAgent();
        stubStream(
                agent,
                new TextBlockStartEvent(REPLY, "blk-1"),
                new TextBlockDeltaEvent(REPLY, "blk-1", "Hello "),
                new TextBlockDeltaEvent(REPLY, "blk-1", "world"),
                new TextBlockEndEvent(REPLY, "blk-1"));
        AguiAgentAdapter adapter = adapterFor(agent, AguiAdapterConfig.defaultConfig());

        List<AguiEvent> events = adapter.run(input()).collectList().block();

        assertNotNull(events);
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));

        AguiEvent.TextMessageStart start = findFirst(events, AguiEvent.TextMessageStart.class);
        assertEquals("blk-1", start.messageId());

        List<AguiEvent.TextMessageContent> contents =
                findAll(events, AguiEvent.TextMessageContent.class);
        assertEquals(2, contents.size());
        assertEquals("Hello ", contents.get(0).delta());
        assertEquals("world", contents.get(1).delta());

        assertEquals(1, findAll(events, AguiEvent.TextMessageEnd.class).size());
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(events.size() - 1));
    }

    @Test
    void textDeltaWithoutStartStillOpensMessage() {
        ReActAgent agent = mockReActAgent();
        stubStream(agent, new TextBlockDeltaEvent(REPLY, "blk-1", "orphan"));
        AguiAgentAdapter adapter = adapterFor(agent, AguiAdapterConfig.defaultConfig());

        List<AguiEvent> events = adapter.run(input()).collectList().block();

        assertNotNull(events);
        // A synthetic start must precede the content and an end must be emitted at finish.
        assertEquals(1, findAll(events, AguiEvent.TextMessageStart.class).size());
        assertEquals(1, findAll(events, AguiEvent.TextMessageContent.class).size());
        assertEquals(1, findAll(events, AguiEvent.TextMessageEnd.class).size());
    }

    // ------------------------------------------------------------------
    // v2 migration: reasoning gated by config
    // ------------------------------------------------------------------

    @Test
    void thinkingEventsAreSuppressedWhenReasoningDisabled() {
        ReActAgent agent = mockReActAgent();
        stubStream(
                agent,
                new ThinkingBlockStartEvent(REPLY, "think-1"),
                new ThinkingBlockDeltaEvent(REPLY, "think-1", "pondering"),
                new ThinkingBlockEndEvent(REPLY, "think-1"));
        AguiAgentAdapter adapter = adapterFor(agent, AguiAdapterConfig.defaultConfig());

        List<AguiEvent> events = adapter.run(input()).collectList().block();

        assertNotNull(events);
        assertTrue(findAll(events, AguiEvent.ReasoningMessageStart.class).isEmpty());
        assertTrue(findAll(events, AguiEvent.ReasoningMessageContent.class).isEmpty());
    }

    @Test
    void thinkingEventsMapToReasoningWhenReasoningEnabled() {
        ReActAgent agent = mockReActAgent();
        stubStream(
                agent,
                new ThinkingBlockStartEvent(REPLY, "think-1"),
                new ThinkingBlockDeltaEvent(REPLY, "think-1", "pondering"),
                new ThinkingBlockEndEvent(REPLY, "think-1"));
        AguiAgentAdapter adapter =
                adapterFor(agent, AguiAdapterConfig.builder().enableReasoning(true).build());

        List<AguiEvent> events = adapter.run(input()).collectList().block();

        assertNotNull(events);
        assertEquals(1, findAll(events, AguiEvent.ReasoningMessageStart.class).size());
        assertEquals(1, findAll(events, AguiEvent.ReasoningMessageContent.class).size());
        assertEquals(
                "pondering", findFirst(events, AguiEvent.ReasoningMessageContent.class).delta());
        assertEquals(1, findAll(events, AguiEvent.ReasoningMessageEnd.class).size());
    }

    // ------------------------------------------------------------------
    // v2 migration: tool calls and tool results
    // ------------------------------------------------------------------

    @Test
    void toolCallAndResultEventsMapInOrder() {
        ReActAgent agent = mockReActAgent();
        stubStream(
                agent,
                new ToolCallStartEvent(REPLY, "tc-1", "search"),
                new ToolCallDeltaEvent(REPLY, "tc-1", "search", "{\"q\":"),
                new ToolCallDeltaEvent(REPLY, "tc-1", "search", "\"cats\"}"),
                new ToolCallEndEvent(REPLY, "tc-1", "search"),
                new ToolResultStartEvent(REPLY, "tc-1", "search"),
                new ToolResultTextDeltaEvent(REPLY, "tc-1", "search", "found "),
                new ToolResultTextDeltaEvent(REPLY, "tc-1", "search", "5 cats"),
                new ToolResultEndEvent(REPLY, "tc-1", "search", ToolResultState.SUCCESS));
        AguiAgentAdapter adapter = adapterFor(agent, AguiAdapterConfig.defaultConfig());

        List<AguiEvent> events = adapter.run(input()).collectList().block();

        assertNotNull(events);

        AguiEvent.ToolCallStart start = findFirst(events, AguiEvent.ToolCallStart.class);
        assertEquals("tc-1", start.toolCallId());
        assertEquals("search", start.toolCallName());

        List<AguiEvent.ToolCallArgs> args = findAll(events, AguiEvent.ToolCallArgs.class);
        assertEquals(2, args.size());
        assertEquals("{\"q\":", args.get(0).delta());
        assertEquals("\"cats\"}", args.get(1).delta());

        // The tool call must be closed exactly once.
        assertEquals(1, findAll(events, AguiEvent.ToolCallEnd.class).size());

        AguiEvent.ToolCallResult result = findFirst(events, AguiEvent.ToolCallResult.class);
        assertEquals("tc-1", result.toolCallId());
        assertEquals("found 5 cats", result.content());

        // Ordering: ToolCallEnd must precede ToolCallResult.
        assertTrue(
                indexOf(events, AguiEvent.ToolCallEnd.class)
                        < indexOf(events, AguiEvent.ToolCallResult.class));
    }

    @Test
    void toolCallArgsSuppressedWhenDisabled() {
        ReActAgent agent = mockReActAgent();
        stubStream(
                agent,
                new ToolCallStartEvent(REPLY, "tc-1", "search"),
                new ToolCallDeltaEvent(REPLY, "tc-1", "search", "{\"q\":1}"),
                new ToolCallEndEvent(REPLY, "tc-1", "search"));
        AguiAgentAdapter adapter =
                adapterFor(agent, AguiAdapterConfig.builder().emitToolCallArgs(false).build());

        List<AguiEvent> events = adapter.run(input()).collectList().block();

        assertNotNull(events);
        assertTrue(findAll(events, AguiEvent.ToolCallArgs.class).isEmpty());
        assertEquals(1, findAll(events, AguiEvent.ToolCallStart.class).size());
        assertEquals(1, findAll(events, AguiEvent.ToolCallEnd.class).size());
    }

    // ------------------------------------------------------------------
    // HITL outbound: RequireUserConfirmEvent -> RunFinished interrupt outcome
    // ------------------------------------------------------------------

    @Test
    void requireUserConfirmProducesInterruptOutcomeOnRunFinished() {
        ReActAgent agent = mockReActAgent();
        ToolUseBlock pending =
                ToolUseBlock.builder()
                        .id("tc-danger")
                        .name("delete_file")
                        .input(Map.of("path", "/etc/hosts"))
                        .build();
        stubStream(
                agent,
                new TextBlockStartEvent(REPLY, "blk-1"),
                new TextBlockDeltaEvent(REPLY, "blk-1", "I need permission"),
                new RequireUserConfirmEvent(REPLY, List.of(pending)));
        AguiAgentAdapter adapter = adapterFor(agent, AguiAdapterConfig.defaultConfig());

        List<AguiEvent> events = adapter.run(input()).collectList().block();

        assertNotNull(events);

        // Any dangling text message must be closed before pausing.
        assertEquals(1, findAll(events, AguiEvent.TextMessageEnd.class).size());

        AguiEvent last = events.get(events.size() - 1);
        AguiEvent.RunFinished finished = assertInstanceOf(AguiEvent.RunFinished.class, last);
        assertNotNull(finished.outcome());
        AguiEvent.RunFinishedInterruptOutcome outcome =
                assertInstanceOf(AguiEvent.RunFinishedInterruptOutcome.class, finished.outcome());
        assertEquals(1, outcome.interrupts().size());
        AguiEvent.Interrupt interrupt = outcome.interrupts().get(0);
        assertEquals("tc-danger", interrupt.toolCallId());
        assertEquals(AguiAgentAdapter.CONFIRM_INTERRUPT_REASON, interrupt.reason());
        assertNotNull(interrupt.id());
    }

    @Test
    void normalCompletionProducesRunFinishedWithoutInterrupt() {
        ReActAgent agent = mockReActAgent();
        stubStream(agent, new TextBlockDeltaEvent(REPLY, "blk-1", "done"));
        AguiAgentAdapter adapter = adapterFor(agent, AguiAdapterConfig.defaultConfig());

        List<AguiEvent> events = adapter.run(input()).collectList().block();

        assertNotNull(events);
        AguiEvent.RunFinished finished =
                assertInstanceOf(AguiEvent.RunFinished.class, events.get(events.size() - 1));
        assertFalse(finished.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome);
    }

    // ------------------------------------------------------------------
    // HITL inbound: forwardedProps confirm results -> ConfirmResult metadata
    // ------------------------------------------------------------------

    @Test
    void forwardedConfirmResultsAreAttachedToResumedMessage() {
        ReActAgent agent = mockReActAgent();
        ArgumentCaptor<List<Msg>> msgsCaptor = ArgumentCaptor.forClass(List.class);
        when(agent.streamEvents(msgsCaptor.capture(), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        Map<String, Object> confirmEntry = new java.util.HashMap<>();
        confirmEntry.put("toolCallId", "tc-danger");
        confirmEntry.put("confirmed", true);
        confirmEntry.put("toolName", "delete_file");
        confirmEntry.put("input", Map.of("path", "/tmp/x"));
        Map<String, Object> props =
                Map.of(AguiAgentAdapter.FORWARDED_PROPS_CONFIRM_RESULTS_KEY, List.of(confirmEntry));

        AguiAgentAdapter adapter = adapterFor(agent, AguiAdapterConfig.defaultConfig());
        adapter.run(inputWithForwardedProps(props)).collectList().block();

        List<Msg> forwarded = msgsCaptor.getValue();
        assertNotNull(forwarded);
        assertFalse(forwarded.isEmpty());
        Msg last = forwarded.get(forwarded.size() - 1);
        assertNotNull(last.getMetadata());
        Object rawResults = last.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertInstanceOf(List.class, rawResults);
        List<?> confirmResults = (List<?>) rawResults;
        assertEquals(1, confirmResults.size());
        ConfirmResult cr = assertInstanceOf(ConfirmResult.class, confirmResults.get(0));
        assertTrue(cr.isConfirmed());
        assertEquals("tc-danger", cr.getToolCall().getId());
        assertEquals("delete_file", cr.getToolCall().getName());
        // The resumed tool call must carry a raw-args JSON string in content: the tool executor
        // validates getContent() (not the parsed input map), and applying the ConfirmResult fully
        // replaces the stored ToolUseBlock. A null content would fail schema validation.
        assertNotNull(cr.getToolCall().getContent());
        assertTrue(cr.getToolCall().getContent().contains("/tmp/x"));
    }

    @Test
    void noForwardedConfirmResultsLeavesMessagesUnchanged() {
        ReActAgent agent = mockReActAgent();
        ArgumentCaptor<List<Msg>> msgsCaptor = ArgumentCaptor.forClass(List.class);
        when(agent.streamEvents(msgsCaptor.capture(), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        AguiAgentAdapter adapter = adapterFor(agent, AguiAdapterConfig.defaultConfig());
        adapter.run(input()).collectList().block();

        List<Msg> forwarded = msgsCaptor.getValue();
        assertNotNull(forwarded);
        Msg last = forwarded.get(forwarded.size() - 1);
        boolean hasConfirm =
                last.getMetadata() != null
                        && last.getMetadata().containsKey(Msg.METADATA_CONFIRM_RESULTS);
        assertFalse(hasConfirm);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static <T extends AguiEvent> T findFirst(List<AguiEvent> events, Class<T> type) {
        for (AguiEvent e : events) {
            if (type.isInstance(e)) {
                return type.cast(e);
            }
        }
        throw new AssertionError("No event of type " + type.getSimpleName());
    }

    private static <T extends AguiEvent> List<T> findAll(List<AguiEvent> events, Class<T> type) {
        List<T> matches = new ArrayList<>();
        for (AguiEvent e : events) {
            if (type.isInstance(e)) {
                matches.add(type.cast(e));
            }
        }
        return matches;
    }

    private static int indexOf(List<AguiEvent> events, Class<? extends AguiEvent> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
