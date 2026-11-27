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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.bus.AsyncToolRecord;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Failure handling of {@link InboxMiddleware#onReasoning} (#3973, #3971).
 *
 * <p>Two rules. A read that fails is degraded: the other source still feeds the round. A marker
 * write that fails is not: the record would be re-reported on every later round, so the round
 * fails instead.
 */
class InboxMiddlewareRobustnessTest {

    @TempDir Path tempDir;

    private MessageBus bus;
    private AgentState agentState;
    private RuntimeContext ctx;

    @BeforeEach
    void setUp() {
        bus = new WorkspaceMessageBus(new LocalFilesystem(tempDir, true, 10), "/bus");
        agentState = AgentState.builder().build();
        agentState.setReplyId("reply-1");
        ctx = RuntimeContext.builder().sessionId("session-1").agentState(agentState).build();
    }

    /** Registry whose stale scan and marker writes are scripted by each test. */
    private static final class ScriptedRegistry implements AsyncToolRegistry {
        Function<String, Mono<List<AsyncToolRecord>>> scan = session -> Mono.just(List.of());
        Function<String, Mono<Void>> marker = id -> Mono.empty();
        final List<String> marked = new ArrayList<>();

        @Override
        public Mono<Void> register(AsyncToolRecord record) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> complete(String id, String result) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> fail(String id, String error) {
            return Mono.empty();
        }

        @Override
        public Mono<List<AsyncToolRecord>> findStale(String sessionId, Duration ttl) {
            return scan.apply(sessionId);
        }

        @Override
        public Mono<Void> markTimeout(String id) {
            marked.add(id);
            return marker.apply(id);
        }
    }

    private static AsyncToolRecord running(String id) {
        return new AsyncToolRecord(
                id,
                "session-1",
                "slow_tool",
                "call-" + id,
                AsyncToolRecord.RUNNING,
                Instant.now().minus(Duration.ofMinutes(30)));
    }

    private static ReasoningInput input() {
        return new ReasoningInput(List.of(), List.of(), null);
    }

    private InboxMiddleware middlewareWith(ScriptedRegistry registry) {
        return new InboxMiddleware(bus, 100, registry, Duration.ofMinutes(10));
    }

    private static List<String> hintIds(List<AgentEvent> events) {
        List<String> ids = new ArrayList<>();
        for (AgentEvent event : events) {
            assertTrue(event instanceof HintBlockEvent, "unexpected event " + event);
            ids.add(((HintBlockEvent) event).getBlockId());
        }
        return ids;
    }

    // ==================== marker writes ====================

    @Test
    void markerWriteFailure_failsTheRoundBeforeItProceeds() {
        // Left un-marked, the record would be reported again, with "you may retry", on every
        // later round. The round fails instead of continuing with that record un-recorded.
        ScriptedRegistry registry = new ScriptedRegistry();
        registry.scan = session -> Mono.just(List.of(running("r1")));
        registry.marker = id -> Mono.error(new IllegalStateException("registry write failed"));
        List<String> reasoningStarted = new ArrayList<>();

        RuntimeException error =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                middlewareWith(registry)
                                        .onReasoning(
                                                stubAgent(),
                                                ctx,
                                                input(),
                                                next -> {
                                                    reasoningStarted.add("yes");
                                                    return Flux.empty();
                                                })
                                        .collectList()
                                        .block(Duration.ofSeconds(5)));

        assertTrue(
                error.getMessage().contains("registry write failed"),
                "the round must fail with the marker's error: " + error);
        assertTrue(reasoningStarted.isEmpty(), "reasoning must not start on an un-marked record");
        assertEquals(List.of("r1"), registry.marked);
    }

    @Test
    void everyRecordIsMarkedBeforeTheRoundProceeds() {
        ScriptedRegistry registry = new ScriptedRegistry();
        registry.scan = session -> Mono.just(List.of(running("r1"), running("r2")));
        // Each write completes on its own scheduler thread, hence the thread-safe list.
        List<String> completed = new CopyOnWriteArrayList<>();
        registry.marker =
                id -> Mono.delay(Duration.ofMillis(40)).doOnNext(tick -> completed.add(id)).then();
        List<String> completedWhenReasoningStarted = new ArrayList<>();

        List<AgentEvent> events =
                middlewareWith(registry)
                        .onReasoning(
                                stubAgent(),
                                ctx,
                                input(),
                                next -> {
                                    completedWhenReasoningStarted.addAll(completed);
                                    return Flux.empty();
                                })
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertEquals(2, events.size(), "one hint per stale record");
        assertEquals(
                new HashSet<>(List.of("r1", "r2")),
                new HashSet<>(completedWhenReasoningStarted),
                "both writes must have completed before reasoning starts");
    }

    @Test
    void roundAbortedByAMarkerWrite_deliversNoInboxHint() {
        // The inbox is drained in parallel with the stale scan. The order is fixed here rather
        // than timed: the scan answers only once the drain has delivered its entry, so that
        // entry is off the bus when the marker write then fails. (The middleware logs how many
        // drained entries it could not deliver; logging itself is not asserted.)
        Sinks.Empty<Void> drained = Sinks.empty();
        MessageBus drainedBus = mock(MessageBus.class);
        when(drainedBus.inboxDrain(any(), anyInt()))
                .thenReturn(
                        Mono.just(
                                        List.of(
                                                new BusEntry(
                                                        "e1",
                                                        Map.of("id", "h1", "hint", "inbox hint"))))
                                .doOnNext(entries -> drained.tryEmitEmpty()));
        ScriptedRegistry registry = new ScriptedRegistry();
        registry.scan = session -> drained.asMono().then(Mono.just(List.of(running("r1"))));
        registry.marker = id -> Mono.error(new IllegalStateException("registry write failed"));
        List<String> reasoningStarted = new ArrayList<>();

        RuntimeException error =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                new InboxMiddleware(
                                                drainedBus, 100, registry, Duration.ofMinutes(10))
                                        .onReasoning(
                                                stubAgent(),
                                                ctx,
                                                input(),
                                                next -> {
                                                    reasoningStarted.add("yes");
                                                    return Flux.empty();
                                                })
                                        .collectList()
                                        .block(Duration.ofSeconds(5)));

        assertTrue(error.getMessage().contains("registry write failed"));
        assertTrue(reasoningStarted.isEmpty(), "the round does not start");
        assertTrue(
                agentState.getContext().isEmpty(),
                "the entry drained before the abort is not injected");
    }

    // ==================== reads ====================

    @Test
    void staleScanFailure_degradesToTheInboxHints() {
        bus.inboxPush("session-1", Map.of("id", "h1", "hint", "inbox hint")).block();
        ScriptedRegistry registry = new ScriptedRegistry();
        registry.scan = session -> Mono.error(new IllegalStateException("registry read failed"));
        List<String> reasoningStarted = new ArrayList<>();

        List<AgentEvent> events =
                middlewareWith(registry)
                        .onReasoning(
                                stubAgent(),
                                ctx,
                                input(),
                                next -> {
                                    reasoningStarted.add("yes");
                                    return Flux.empty();
                                })
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertEquals(List.of("h1"), hintIds(events), "the inbox source still feeds the round");
        assertEquals(List.of("yes"), reasoningStarted);
        assertTrue(registry.marked.isEmpty(), "nothing was found, so nothing was marked");
    }

    @Test
    void inboxDrainFailure_degradesToTheStaleToolHints() {
        MessageBus failingBus = mock(MessageBus.class);
        when(failingBus.inboxDrain(any(), anyInt()))
                .thenReturn(Mono.error(new IllegalStateException("bus read failed")));
        ScriptedRegistry registry = new ScriptedRegistry();
        registry.scan = session -> Mono.just(List.of(running("r1")));
        List<String> reasoningStarted = new ArrayList<>();

        List<AgentEvent> events =
                new InboxMiddleware(failingBus, 100, registry, Duration.ofMinutes(10))
                        .onReasoning(
                                stubAgent(),
                                ctx,
                                input(),
                                next -> {
                                    reasoningStarted.add("yes");
                                    return Flux.empty();
                                })
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertEquals(1, events.size(), "the stale-tool source still feeds the round");
        assertTrue(((HintBlockEvent) events.get(0)).getHint().contains("slow_tool"));
        assertEquals(List.of("yes"), reasoningStarted);
        assertEquals(List.of("r1"), registry.marked);
    }

    @Test
    void bothReadsFailing_stillLetsTheRoundProceed() {
        MessageBus failingBus = mock(MessageBus.class);
        when(failingBus.inboxDrain(any(), anyInt()))
                .thenReturn(Mono.error(new IllegalStateException("bus read failed")));
        ScriptedRegistry registry = new ScriptedRegistry();
        registry.scan = session -> Mono.error(new IllegalStateException("registry read failed"));
        List<String> reasoningStarted = new ArrayList<>();

        List<AgentEvent> events =
                new InboxMiddleware(failingBus, 100, registry, Duration.ofMinutes(10))
                        .onReasoning(
                                stubAgent(),
                                ctx,
                                input(),
                                next -> {
                                    reasoningStarted.add("yes");
                                    return Flux.empty();
                                })
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertTrue(events.isEmpty());
        assertEquals(List.of("yes"), reasoningStarted);
    }

    // ==================== entries without a usable hint ====================

    @Test
    void entryWithNullPayload_isSkippedAndTheOthersAreStillDelivered() {
        MessageBus busWithNullPayload = mock(MessageBus.class);
        when(busWithNullPayload.inboxDrain(any(), anyInt()))
                .thenReturn(
                        Mono.just(
                                List.of(
                                        new BusEntry("e1", null),
                                        new BusEntry("e2", Map.of("id", "h2", "hint", "second")),
                                        new BusEntry("e3", Map.of("id", "h3")))));

        List<AgentEvent> events =
                new InboxMiddleware(busWithNullPayload)
                        .onReasoning(stubAgent(), ctx, input(), next -> Flux.empty())
                        .collectList()
                        .block(Duration.ofSeconds(5));

        // e1 has no payload at all, e3 a payload without a hint: both are skipped, neither is
        // fatal.
        assertEquals(List.of("h2"), hintIds(events));
    }

    @Test
    void entryWithoutHint_isSkippedOnTheRealBus() {
        bus.inboxPush("session-1", Map.of("id", "h-first", "hint", "first")).block();
        bus.inboxPush("session-1", Map.of("id", "h-no-hint")).block();
        bus.inboxPush("session-1", Map.of("id", "h-last", "hint", "last")).block();

        List<AgentEvent> events =
                new InboxMiddleware(bus)
                        .onReasoning(stubAgent(), ctx, input(), next -> Flux.empty())
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertEquals(List.of("h-first", "h-last"), hintIds(events), "queue order is kept");
    }

    @Test
    void deserializeHintBlock_toleratesWhatItCannotRead() {
        assertNull(InboxMiddleware.deserializeHintBlock(null));
        assertNull(InboxMiddleware.deserializeHintBlock(Map.of("id", "x")), "no hint");

        HintBlock hint =
                InboxMiddleware.deserializeHintBlock(
                        Map.of("id", "x", "hint", "text", "source", "tool_output"));
        assertEquals("x", hint.getId());
        assertEquals("text", hint.getHint());
        assertEquals("tool_output", hint.getSource());
    }

    // ==================== hints drained without an agent state (#3971) ====================

    @Test
    void hintsDrainedWithoutAgentState_areEmittedAndDoNotFailTheRound() {
        bus.inboxPush("session-1", Map.of("id", "h1", "hint", "Background result A")).block();
        RuntimeContext withoutState = RuntimeContext.builder().sessionId("session-1").build();
        List<String> reasoningStarted = new ArrayList<>();

        List<AgentEvent> events =
                new InboxMiddleware(bus)
                        .onReasoning(
                                stubAgent(),
                                withoutState,
                                input(),
                                next -> {
                                    reasoningStarted.add("yes");
                                    return Flux.empty();
                                })
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertEquals(List.of("h1"), hintIds(events), "the hint is still published as an event");
        assertEquals(List.of("yes"), reasoningStarted);
    }

    private static Agent stubAgent() {
        return new Agent() {
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
            public AgentState getAgentState() {
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
                    List<Msg> msgs, StreamOptions options) {
                return Flux.empty();
            }

            @Override
            public Flux<io.agentscope.core.agent.Event> stream(
                    List<Msg> msgs, StreamOptions options, Class<?> m) {
                return Flux.empty();
            }

            @Override
            public Flux<io.agentscope.core.agent.Event> stream(
                    List<Msg> msgs,
                    StreamOptions options,
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
