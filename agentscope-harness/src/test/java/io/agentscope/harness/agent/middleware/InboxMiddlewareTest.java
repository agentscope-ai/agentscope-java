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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.bus.InMemoryMessageBus;
import io.agentscope.core.bus.MessageBus;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class InboxMiddlewareTest {

    private MessageBus bus;
    private InboxMiddleware middleware;
    private AgentState agentState;
    private RuntimeContext ctx;

    @BeforeEach
    void setUp() {
        bus = new InMemoryMessageBus();
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
        assertEquals(1, context.size());
        Msg hintMsg = context.get(0);
        assertEquals(MsgRole.ASSISTANT, hintMsg.getRole());
        List<HintBlock> hints = hintMsg.getContentBlocks(HintBlock.class);
        assertEquals(2, hints.size());
        assertEquals("Background result A", hints.get(0).getHint());
        assertEquals("Background result B", hints.get(1).getHint());
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
    void injectAppendsToExistingAssistantMessage() {
        List<HintBlock> existingHints = new ArrayList<>();
        existingHints.add(new HintBlock("existing-1", "existing hint"));
        Msg existingMsg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(new ArrayList<>(existingHints))
                        .build();
        agentState.contextMutable().add(existingMsg);

        bus.inboxPush("session-1", Map.of("id", "new-1", "hint", "new hint")).block();

        ReasoningInput input = new ReasoningInput(List.of(), List.of(), null);

        middleware.onReasoning(stubAgent(), ctx, input, next -> Flux.empty()).collectList().block();

        List<Msg> context = agentState.getContext();
        assertEquals(1, context.size());
        List<HintBlock> allHints = context.get(0).getContentBlocks(HintBlock.class);
        assertEquals(2, allHints.size());
        assertEquals("existing hint", allHints.get(0).getHint());
        assertEquals("new hint", allHints.get(1).getHint());
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
