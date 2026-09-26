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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;

class AsyncToolMiddlewareTest {

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir;
    private MessageBus bus;
    private AgentState agentState;
    private RuntimeContext ctx;

    @BeforeEach
    void setUp() {
        io.agentscope.harness.agent.filesystem.local.LocalFilesystem fs =
                new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(tempDir, true, 10);
        bus = new WorkspaceMessageBus(fs, "/bus");
        agentState = AgentState.builder().build();
        agentState.setReplyId("reply-1");
        ctx = RuntimeContext.builder().sessionId("session-1").agentState(agentState).build();
    }

    @Test
    void downstreamReceivesSubscriberContextForEachSubscription() {
        AsyncToolMiddleware middleware = new AsyncToolMiddleware(bus, Duration.ofSeconds(5));
        ToolUseBlock tool = ToolUseBlock.builder().id("t1").name("context_tool").build();
        ActingInput input = new ActingInput(List.of(tool));
        List<AgentEvent> firstForwarded = new CopyOnWriteArrayList<>();
        List<AgentEvent> secondForwarded = new CopyOnWriteArrayList<>();
        AgentEventEmitter firstEmitter = firstForwarded::add;
        AgentEventEmitter secondEmitter = secondForwarded::add;

        Flux<AgentEvent> acting =
                middleware.onActing(
                        stubAgent(),
                        ctx,
                        input,
                        next ->
                                Flux.deferContextual(
                                        context -> {
                                            AgentEventEmitter emitter =
                                                    AgentEventEmitter.fromContext(context)
                                                            .orElseThrow();
                                            assertSame(
                                                    emitter,
                                                    AgentEventEmitter.fromForwardingContext(context)
                                                            .orElseThrow());
                                            AgentEvent event =
                                                    new ToolResultTextDeltaEvent(
                                                            "r1",
                                                            "t1",
                                                            "context_tool",
                                                            context.get("request-id"));
                                            emitter.emit(event);
                                            return Flux.just(event);
                                        }));

        List<AgentEvent> firstEvents =
                acting.contextWrite(
                                Context.of(
                                        AgentEventEmitter.CONTEXT_KEY,
                                        firstEmitter,
                                        AgentEventEmitter.FORWARDING_CONTEXT_KEY,
                                        firstEmitter,
                                        "request-id",
                                        "request-1"))
                        .collectList()
                        .block(Duration.ofSeconds(5));
        List<AgentEvent> secondEvents =
                acting.contextWrite(
                                Context.of(
                                        AgentEventEmitter.CONTEXT_KEY,
                                        secondEmitter,
                                        AgentEventEmitter.FORWARDING_CONTEXT_KEY,
                                        secondEmitter,
                                        "request-id",
                                        "request-2"))
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertEquals(1, firstForwarded.size());
        assertEquals(1, secondForwarded.size());
        assertEquals(firstEvents, firstForwarded);
        assertEquals(secondEvents, secondForwarded);
        assertEquals("request-1", ((ToolResultTextDeltaEvent) firstForwarded.get(0)).getDelta());
        assertEquals("request-2", ((ToolResultTextDeltaEvent) secondForwarded.get(0)).getDelta());
    }

    @Test
    void backgroundExecutionRetainsContextAfterOffload() {
        AsyncToolMiddleware middleware = new AsyncToolMiddleware(bus, Duration.ofMillis(50));
        ToolUseBlock tool = ToolUseBlock.builder().id("t1").name("context_tool").build();
        ActingInput input = new ActingInput(List.of(tool));
        Sinks.One<String> release = Sinks.one();
        Sinks.One<String> observed = Sinks.one();
        List<AgentEvent> forwarded = new CopyOnWriteArrayList<>();
        AgentEventEmitter emitter = forwarded::add;
        Flux<AgentEvent> downstream =
                release.asMono()
                        .thenMany(
                                Flux.deferContextual(
                                        context -> {
                                            AgentEventEmitter current =
                                                    AgentEventEmitter.fromContext(context)
                                                            .orElseThrow();
                                            assertSame(emitter, current);
                                            AgentEvent event =
                                                    new ToolResultTextDeltaEvent(
                                                            "r1",
                                                            "t1",
                                                            "context_tool",
                                                            "background result");
                                            current.emit(event);
                                            observed.tryEmitValue(context.get("request-id"));
                                            return Flux.just(event);
                                        }));

        List<AgentEvent> events =
                middleware
                        .onActing(stubAgent(), ctx, input, next -> downstream)
                        .contextWrite(
                                Context.of(
                                        AgentEventEmitter.CONTEXT_KEY,
                                        emitter,
                                        "request-id",
                                        "background-request"))
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertTrue(
                events.stream()
                        .anyMatch(
                                event ->
                                        event instanceof ToolResultTextDeltaEvent delta
                                                && delta.getDelta()
                                                        .contains("running in background")));
        assertEquals(Sinks.EmitResult.OK, release.tryEmitValue("continue"));
        assertEquals("background-request", observed.asMono().block(Duration.ofSeconds(5)));
        assertEquals(1, forwarded.size());
    }

    @Test
    void toolCompletesBeforeTimeout_passesThrough() {
        AsyncToolMiddleware middleware = new AsyncToolMiddleware(bus, Duration.ofSeconds(5));

        ToolUseBlock tool = ToolUseBlock.builder().id("t1").name("fast_tool").build();
        ActingInput input = new ActingInput(List.of(tool));

        Flux<AgentEvent> downstream =
                Flux.just(
                        new ToolResultStartEvent("r1", "t1", "fast_tool"),
                        new ToolResultTextDeltaEvent("r1", "t1", "fast_tool", "result text"),
                        new ToolResultEndEvent("r1", "t1", "fast_tool", ToolResultState.SUCCESS));

        List<AgentEvent> events =
                middleware
                        .onActing(stubAgent(), ctx, input, next -> downstream)
                        .collectList()
                        .block();

        assertEquals(3, events.size());
        assertTrue(events.get(0) instanceof ToolResultStartEvent);
        assertTrue(events.get(1) instanceof ToolResultTextDeltaEvent);
        assertTrue(events.get(2) instanceof ToolResultEndEvent);

        ToolResultTextDeltaEvent delta = (ToolResultTextDeltaEvent) events.get(1);
        assertEquals("result text", delta.getDelta());

        List<BusEntry> inbox = bus.inboxDrain("session-1", 100).block();
        assertTrue(inbox.isEmpty());
    }

    @Test
    void toolExceedsTimeout_emitsPlaceholderAndOffloads() throws Exception {
        AsyncToolMiddleware middleware = new AsyncToolMiddleware(bus, Duration.ofMillis(200));

        ToolUseBlock tool = ToolUseBlock.builder().id("t1").name("slow_tool").build();
        ActingInput input = new ActingInput(List.of(tool));

        Flux<AgentEvent> slowDownstream =
                Flux.just((AgentEvent) new ToolResultStartEvent("r1", "t1", "slow_tool"))
                        .concatWith(
                                Mono.delay(Duration.ofSeconds(2))
                                        .thenMany(
                                                Flux.just(
                                                        new ToolResultTextDeltaEvent(
                                                                "r1",
                                                                "t1",
                                                                "slow_tool",
                                                                "slow result"),
                                                        new ToolResultEndEvent(
                                                                "r1",
                                                                "t1",
                                                                "slow_tool",
                                                                ToolResultState.SUCCESS))));

        List<AgentEvent> events =
                middleware
                        .onActing(stubAgent(), ctx, input, next -> slowDownstream)
                        .collectList()
                        .block();

        boolean hasPlaceholder =
                events.stream()
                        .anyMatch(
                                e ->
                                        e instanceof ToolResultTextDeltaEvent d
                                                && d.getDelta().contains("running in background"));
        assertTrue(hasPlaceholder, "Should emit placeholder text delta");

        boolean hasEnd =
                events.stream()
                        .anyMatch(
                                e ->
                                        e instanceof ToolResultEndEvent end
                                                && end.getState() == ToolResultState.SUCCESS);
        assertTrue(hasEnd, "Should emit end event");

        assertFalse(agentState.getContext().isEmpty(), "Placeholder should be written to context");

        Thread.sleep(3000);

        List<BusEntry> inbox = bus.inboxDrain("session-1", 100).block();
        assertFalse(inbox.isEmpty(), "Background result should be pushed to inbox");

        String hint = inbox.get(0).payload().get("hint").toString();
        assertTrue(hint.contains("slow result"), "Inbox should contain the actual result");
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
            public Mono<Msg> call(List<Msg> msgs) {
                return Mono.empty();
            }

            @Override
            public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
                return Mono.empty();
            }

            @Override
            public Mono<Msg> call(List<Msg> msgs, com.fasterxml.jackson.databind.JsonNode schema) {
                return Mono.empty();
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
            public Mono<Void> observe(Msg msg) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> observe(List<Msg> msgs) {
                return Mono.empty();
            }
        };
    }
}
