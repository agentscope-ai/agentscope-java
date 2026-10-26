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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class InboxMiddlewareTest {

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir;
    private MessageBus bus;
    private InboxMiddleware middleware;
    private AgentState agentState;
    private RuntimeContext ctx;

    @BeforeEach
    void setUp() {
        io.agentscope.harness.agent.filesystem.local.LocalFilesystem fs =
                new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(tempDir, true, 10);
        bus = new WorkspaceMessageBus(fs, "/bus");
        middleware = new InboxMiddleware(bus);
        agentState = AgentState.builder().build();
        agentState.setReplyId("reply-1");
        ctx = RuntimeContext.builder().sessionId("session-1").agentState(agentState).build();
    }

    @Test
    void emptyInboxPassesThrough() {
        ReasoningInput input = new ReasoningInput(List.of(), List.of(), null);

        List<AgentEvent> events =
                middleware
                        .onReasoning(stubAgent(), ctx, input, next -> Flux.empty())
                        .collectList()
                        .block();

        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    void drainsInboxAndInjectsHintsToContext() {
        bus.inboxPush("session-1", Map.of("id", "h1", "hint", "Background result A")).block();
        bus.inboxPush("session-1", Map.of("id", "h2", "hint", "Background result B")).block();

        ReasoningInput input = new ReasoningInput(List.of(), List.of(), null);

        List<AgentEvent> events =
                middleware
                        .onReasoning(stubAgent(), ctx, input, next -> Flux.empty())
                        .collectList()
                        .block();

        assertEquals(2, events.size());
        assertTrue(events.get(0) instanceof HintBlockEvent);
        assertTrue(events.get(1) instanceof HintBlockEvent);

        HintBlockEvent e1 = (HintBlockEvent) events.get(0);
        assertEquals("h1", e1.getBlockId());
        assertEquals("Background result A", e1.getHint());

        HintBlockEvent e2 = (HintBlockEvent) events.get(1);
        assertEquals("h2", e2.getBlockId());
        assertEquals("Background result B", e2.getHint());

        List<Msg> context = agentState.getContext();
        assertEquals(2, context.size());
        Msg hintMsg = context.get(0);
        assertEquals(MsgRole.USER, hintMsg.getRole());
        assertTrue(
                hintMsg.getTextContent()
                        .contains("This is context, not a user message or approval."),
                () -> "provenance lead-in missing: " + hintMsg.getTextContent());
        assertTrue(hintMsg.getTextContent().contains("Background result A"));
        assertTrue(context.get(1).getTextContent().contains("Background result B"));
        assertEquals("h1", hintMsg.getMetadata().get("hintId"));
        assertEquals("background", hintMsg.getMetadata().get("source"));
        assertEquals(true, hintMsg.getMetadata().get("synthetic"));
    }

    @Test
    void notificationsReachNextReasoningInput() {
        bus.inboxPush("session-1", Map.of("id", "h1", "hint", "Background result A")).block();

        Msg prior = UserMessage.builder().textContent("prior turn").build();
        ReasoningInput input = new ReasoningInput(List.of(prior), List.of(), null);
        List<ReasoningInput> captured = new ArrayList<>();

        middleware
                .onReasoning(
                        stubAgent(),
                        ctx,
                        input,
                        next -> {
                            captured.add(next);
                            return Flux.empty();
                        })
                .collectList()
                .block();

        assertEquals(1, captured.size(), "next must be invoked exactly once");
        List<Msg> out = captured.get(0).messages();
        assertEquals(2, out.size());
        assertSame(prior, out.get(0), "upstream messages must pass through untouched");
        assertEquals(MsgRole.USER, out.get(1).getRole());
        assertTrue(out.get(1).getTextContent().contains("Background result A"));
    }

    @Test
    void labelsEachNotificationWithItsSource() {
        bus.inboxPush(
                        "session-1",
                        Map.of("id", "h1", "hint", "tool finished", "source", "tool_output"))
                .block();
        bus.inboxPush("session-1", Map.of("id", "h2", "hint", "team ping", "source", "alice"))
                .block();

        ReasoningInput input = new ReasoningInput(List.of(), List.of(), null);
        middleware.onReasoning(stubAgent(), ctx, input, next -> Flux.empty()).collectList().block();

        List<Msg> context = agentState.getContext();
        assertEquals(2, context.size(), "one notification message per hint");
        assertTrue(context.get(0).getTextContent().contains("from tool_output."));
        assertTrue(context.get(0).getTextContent().contains("tool finished"));
        assertFalse(context.get(0).getTextContent().contains("team ping"));
        assertTrue(context.get(1).getTextContent().contains("from alice."));
        assertTrue(context.get(1).getTextContent().contains("team ping"));
    }

    @Test
    void interleavedSourcesKeepQueueOrderAndStructuredProvenance() {
        List<HintBlock> hints =
                List.of(
                        new HintBlock("a1", "first", "tool_output"),
                        new HintBlock("b", "middle", "subagent_task"),
                        new HintBlock("a2", "last", "tool_output"));
        for (HintBlock hint : hints) {
            bus.inboxPush(
                            "session-1",
                            Map.of(
                                    "id",
                                    hint.getId(),
                                    "hint",
                                    hint.getHint(),
                                    "source",
                                    hint.getSource()))
                    .block();
        }
        List<Msg> requested = new ArrayList<>();
        List<AgentEvent> events =
                middleware
                        .onReasoning(
                                stubAgent(),
                                ctx,
                                new ReasoningInput(List.of(), List.of(), null),
                                input -> {
                                    requested.addAll(input.messages());
                                    return Flux.empty();
                                })
                        .collectList()
                        .block();

        assertEquals(3, requested.size());
        assertEquals(requested, agentState.getContext());
        for (int i = 0; i < hints.size(); i++) {
            HintBlock original = hints.get(i);
            Msg notification = requested.get(i);
            assertEquals(MsgRole.USER, notification.getRole());
            assertEquals(original.getId(), notification.getMetadata().get("hintId"));
            assertEquals(original.getSource(), notification.getMetadata().get("source"));
            assertEquals("background_event", notification.getMetadata().get("reminderKind"));
            assertTrue(notification.getTextContent().contains(original.getHint()));
            assertEquals(original.getId(), ((HintBlockEvent) events.get(i)).getBlockId());
        }
    }

    @Test
    void drainedEntriesAreNotReadAgain() {
        bus.inboxPush("session-1", Map.of("id", "h1", "hint", "once")).block();

        ReasoningInput input = new ReasoningInput(List.of(), List.of(), null);

        List<AgentEvent> first =
                middleware
                        .onReasoning(stubAgent(), ctx, input, next -> Flux.empty())
                        .collectList()
                        .block();
        assertEquals(1, first.size());

        List<AgentEvent> second =
                middleware
                        .onReasoning(stubAgent(), ctx, input, next -> Flux.empty())
                        .collectList()
                        .block();
        assertTrue(second.isEmpty());
    }

    @Test
    void hintBlockSourcePreserved() {
        bus.inboxPush("session-1", Map.of("id", "h1", "hint", "team msg", "source", "alice"))
                .block();

        ReasoningInput input = new ReasoningInput(List.of(), List.of(), null);

        List<AgentEvent> events =
                middleware
                        .onReasoning(stubAgent(), ctx, input, next -> Flux.empty())
                        .collectList()
                        .block();

        HintBlockEvent evt = (HintBlockEvent) events.get(0);
        assertEquals("alice", evt.getHintSource());
    }

    @Test
    void noSessionIdPassesThrough() {
        RuntimeContext noSessionCtx = RuntimeContext.builder().build();
        bus.inboxPush("session-1", Map.of("id", "h1", "hint", "should not drain")).block();

        ReasoningInput input = new ReasoningInput(List.of(), List.of(), null);

        List<AgentEvent> events =
                middleware
                        .onReasoning(stubAgent(), noSessionCtx, input, next -> Flux.empty())
                        .collectList()
                        .block();

        assertTrue(events.isEmpty());
    }

    @Test
    void notificationReachesModelRequestUnderRealScheduling(@TempDir java.nio.file.Path wsDir) {
        MessageBus realBus = new WorkspaceMessageBus(new LocalFilesystem(wsDir, true, 10), "/bus");
        MockModel model = new MockModel("ack");
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("inbox-agent")
                        .model(model)
                        .workspace(wsDir)
                        .abstractFilesystem(new LocalFilesystem(wsDir))
                        .stateStore(stateStore)
                        .messageBus(realBus)
                        .build()) {

            realBus.inboxPush(
                            "sched-1",
                            Map.of(
                                    "id",
                                    "h9",
                                    "hint",
                                    "async tool finished",
                                    "source",
                                    "tool_output"))
                    .block();

            agent.call(
                            UserMessage.builder().textContent("hello").build(),
                            RuntimeContext.builder().sessionId("sched-1").build())
                    .block();

            List<Msg> seen = model.getLastMessages();
            assertNotNull(seen, "model must have been called");
            String combined =
                    seen.stream().map(Msg::getTextContent).collect(Collectors.joining("\n"));
            assertTrue(
                    combined.contains("async tool finished"),
                    () -> "notification must reach the model request: " + combined);
            assertTrue(
                    combined.contains("Automated background event from tool_output."),
                    () -> "provenance wrapper missing: " + combined);
            assertTrue(combined.contains("hello"), "original user message must remain present");
            // The HarnessContextBuilder-compiled request carries the notification exactly once
            // (state-written once; the drained inbox never re-delivers).
            assertEquals(
                    1,
                    seen.stream()
                            .filter(m -> m.getTextContent().contains("async tool finished"))
                            .count(),
                    "notification must appear exactly once in the model request");

            // Durable history (the per-session state the next call resumes from) holds it
            // once too. Session state lives on the delegate keyed by (userId, sessionId),
            // not in the deprecated no-arg getAgentState().
            assertEquals(
                    1,
                    agent.getDelegate().getAgentState(null, "sched-1").getContext().stream()
                            .filter(m -> m.getTextContent().contains("async tool finished"))
                            .count(),
                    "notification must be written into the history exactly once");
        }
    }

    @Test
    void notificationReachesModelRequestWithoutPreparer(@TempDir java.nio.file.Path busDir) {
        // Bare ReActAgent has no ModelRequestPreparer: the model request is the reasoning
        // input itself, so delivery relies entirely on the appended ReasoningInput.
        MessageBus realBus = new WorkspaceMessageBus(new LocalFilesystem(busDir, true, 10), "/bus");
        MockModel model = new MockModel("ack");

        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("inbox-react")
                        .model(model)
                        .middlewares(List.of(new InboxMiddleware(realBus)))
                        .build()) {

            realBus.inboxPush(
                            "react-1",
                            Map.of(
                                    "id",
                                    "h1",
                                    "hint",
                                    "async tool finished",
                                    "source",
                                    "tool_output"))
                    .block();

            agent.call(
                            List.of(UserMessage.builder().textContent("hello").build()),
                            RuntimeContext.builder().sessionId("react-1").build())
                    .block();

            List<Msg> seen = model.getLastMessages();
            assertNotNull(seen, "model must have been called");
            String combined =
                    seen.stream().map(Msg::getTextContent).collect(Collectors.joining("\n"));
            assertTrue(
                    combined.contains("async tool finished"),
                    () -> "notification must reach the model request: " + combined);
            assertTrue(combined.contains("hello"), "original user message must remain present");
            assertEquals(
                    1,
                    seen.stream()
                            .filter(m -> m.getTextContent().contains("async tool finished"))
                            .count(),
                    "notification must appear exactly once in the model request");
        }
    }

    @Test
    void notificationIsSeparateUserMessageAndAssistantUntouched() {
        List<HintBlock> existingHints = new ArrayList<>();
        existingHints.add(new HintBlock("existing-1", "existing hint"));
        Msg existingMsg =
                Msg.builder()
                        .id("asst-1")
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .metadata(Map.of("k", "v"))
                        .content(new ArrayList<>(existingHints))
                        .build();
        agentState.contextMutable().add(existingMsg);

        bus.inboxPush("session-1", Map.of("id", "new-1", "hint", "new hint")).block();

        ReasoningInput input = new ReasoningInput(List.of(), List.of(), null);

        middleware.onReasoning(stubAgent(), ctx, input, next -> Flux.empty()).collectList().block();

        List<Msg> context = agentState.getContext();
        assertEquals(2, context.size());
        assertSame(existingMsg, context.get(0), "existing assistant message must not be rebuilt");
        assertEquals(Map.of("k", "v"), existingMsg.getMetadata());
        assertEquals(
                1,
                existingMsg.getContentBlocks(HintBlock.class).size(),
                "no hints may be appended to the assistant message");
        Msg notification = context.get(1);
        assertEquals(MsgRole.USER, notification.getRole());
        assertTrue(notification.getTextContent().contains("new hint"));
    }

    private static io.agentscope.core.agent.Agent stubAgent() {
        return new io.agentscope.core.agent.Agent() {
            @Override
            public String getAgentId() {
                return "test-agent-id";
            }

            @Override
            public String getName() {
                return "TestAgent";
            }

            @Override
            public void interrupt() {}

            @Override
            public void interrupt(Msg msg) {}

            @Override
            public io.agentscope.core.state.AgentState getAgentState() {
                return null;
            }

            @Override
            public reactor.core.publisher.Mono<Msg> call(List<Msg> msgs) {
                return reactor.core.publisher.Mono.empty();
            }

            @Override
            public reactor.core.publisher.Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
                return reactor.core.publisher.Mono.empty();
            }

            @Override
            public reactor.core.publisher.Mono<Msg> call(
                    List<Msg> msgs, com.fasterxml.jackson.databind.JsonNode schema) {
                return reactor.core.publisher.Mono.empty();
            }

            @Override
            public Flux<io.agentscope.core.agent.Event> stream(
                    List<Msg> msgs, io.agentscope.core.agent.StreamOptions options) {
                return Flux.empty();
            }

            @Override
            public Flux<io.agentscope.core.agent.Event> stream(
                    List<Msg> msgs, io.agentscope.core.agent.StreamOptions options, Class<?> m) {
                return Flux.empty();
            }

            @Override
            public Flux<io.agentscope.core.agent.Event> stream(
                    List<Msg> msgs,
                    io.agentscope.core.agent.StreamOptions options,
                    com.fasterxml.jackson.databind.JsonNode schema) {
                return Flux.empty();
            }

            @Override
            public reactor.core.publisher.Mono<Void> observe(Msg msg) {
                return reactor.core.publisher.Mono.empty();
            }

            @Override
            public reactor.core.publisher.Mono<Void> observe(List<Msg> msgs) {
                return reactor.core.publisher.Mono.empty();
            }
        };
    }
}
