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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolErrorKind;
import io.agentscope.core.message.ToolErrorMetadata;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class RepeatedFailureGuardTest {

    private static final String TOOL = "read_file";
    private static final RuntimeContext SESSION_1 =
            RuntimeContext.builder().userId("u").sessionId("s1").threadId("main").build();
    private static final RuntimeContext SESSION_2 =
            RuntimeContext.builder().userId("u").sessionId("s2").threadId("main").build();

    private final RepeatedFailureGuard guard = new RepeatedFailureGuard();
    private final Map<RuntimeContext, TestTurn> turns = new HashMap<>();

    private record TestTurn(
            AgentState state,
            RuntimeContext effectiveContext,
            RuntimeContext context,
            Sinks.Many<AgentEvent> events,
            Disposable subscription) {}

    private TestTurn turn(RuntimeContext ctx) {
        return turns.computeIfAbsent(
                ctx,
                unused -> {
                    AgentState state = AgentState.builder().build();
                    RuntimeContext live =
                            ctx == null
                                    ? null
                                    : RuntimeContext.builder(ctx).agentState(state).build();
                    Sinks.Many<AgentEvent> events = Sinks.many().unicast().onBackpressureBuffer();
                    AtomicReference<RuntimeContext> execution = new AtomicReference<>();
                    Disposable subscription =
                            guard.onAgent(
                                            null,
                                            live,
                                            new AgentInput(List.of()),
                                            in -> {
                                                // Mirror journal derivation before admission and
                                                // capability derivation after state-ready.
                                                RuntimeContext ready =
                                                        live == null
                                                                ? null
                                                                : RuntimeContext.builder(live)
                                                                        .build();
                                                guard.onAgentStateReady(
                                                        null, ready, state, in.msgs());
                                                execution.set(
                                                        ready == null
                                                                ? null
                                                                : RuntimeContext.builder(ready)
                                                                        .build());
                                                return events.asFlux();
                                            })
                                    .subscribe();
                    return new TestTurn(state, live, execution.get(), events, subscription);
                });
    }

    // Drive window/cap assertions through the synchronous production notification only.
    private Flux<AgentEvent> observeActing(
            Object unused,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    TestTurn turn = turn(ctx);
                    if (!input.toolCalls().isEmpty()) {
                        turn.state
                                .contextMutable()
                                .add(
                                        Msg.builder()
                                                .role(MsgRole.ASSISTANT)
                                                .content(new ArrayList<>(input.toolCalls()))
                                                .build());
                        for (ToolUseBlock call : input.toolCalls()) {
                            guard.onAgentEvent(
                                    null,
                                    turn.context,
                                    new ToolResultStartEvent(
                                            "reply", call.getId(), call.getName()));
                        }
                    }
                    return next.apply(input)
                            .doOnNext(event -> guard.onAgentEvent(null, turn.context, event));
                });
    }

    @AfterEach
    void closeTurns() {
        turns.values().forEach(turn -> turn.subscription.dispose());
        turns.clear();
    }

    private void fail(RuntimeContext ctx, String tool, ToolErrorKind kind, String scope) {
        fail(ctx, tool, kind, scope, false);
    }

    private void fail(
            RuntimeContext ctx, String tool, ToolErrorKind kind, String scope, boolean retriable) {
        emit(ctx, eventFor(tool, ToolResultState.ERROR, kind, scope, retriable));
    }

    private static ToolResultEndEvent eventFor(
            String tool,
            ToolResultState state,
            ToolErrorKind kind,
            String scope,
            Boolean retriable) {
        ToolResultEndEvent event = new ToolResultEndEvent("reply", "call", tool, state);
        if (kind != null) {
            event.withMetadata(ToolErrorMetadata.of(kind, scope, retriable));
        }
        return event;
    }

    private void emit(RuntimeContext ctx, ToolResultEndEvent event) {
        observeActing(null, ctx, new ActingInput(List.of()), input -> Flux.just(event)).blockLast();
    }

    private List<Msg> nextReasoning(RuntimeContext ctx) {
        TestTurn turn = turns.get(ctx);
        return reasoningMessages(turn == null ? ctx : turn.context);
    }

    private List<Msg> reasoningMessages(RuntimeContext ctx) {
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();
        guard.onReasoning(
                        null,
                        ctx,
                        new ReasoningInput(List.of(), List.of(), null),
                        input -> {
                            seen.set(input);
                            return Flux.<AgentEvent>empty();
                        })
                .blockLast();
        assertNotNull(seen.get());
        return seen.get().messages();
    }

    private void newTurn(RuntimeContext ctx) {
        TestTurn previous = turns.remove(ctx);
        if (previous != null) {
            previous.subscription.dispose();
        }
        turn(ctx);
    }

    @Test
    void twoFailuresDoNotTriggerAndTheThirdAddsOneReminderOnce() {
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertTrue(nextReasoning(SESSION_1).isEmpty());

        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        List<Msg> reminder = nextReasoning(SESSION_1);
        assertEquals(1, reminder.size());
        String text = reminder.get(0).getTextContent();
        assertTrue(text.contains(TOOL), text);
        assertTrue(text.contains("not_found"), text);
        assertTrue(text.contains("a.txt"), text);
        assertEquals(Boolean.TRUE, reminder.get(0).getMetadata().get("synthetic"));
        assertEquals("repeated_failure", reminder.get(0).getMetadata().get("reminderKind"));
        assertTrue(text.length() <= RepeatedFailureGuard.MAX_REMINDER_CHARS);

        assertTrue(nextReasoning(SESSION_1).isEmpty(), "reminder is one-shot");
    }

    @Test
    void differentScopesOrKindsDoNotMerge() {
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "b.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.INVALID_ARGUMENT, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    @Test
    void successOnTheMatchingScopeClearsItsFailures() {
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        ToolResultEndEvent success = eventFor(TOOL, ToolResultState.SUCCESS, null, null, null);
        success.withMetadata(Map.of(ToolErrorMetadata.METADATA_ERROR_SCOPE, "a.txt"));
        emit(SESSION_1, success);
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertTrue(nextReasoning(SESSION_1).isEmpty());

        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertEquals(1, nextReasoning(SESSION_1).size());
    }

    @Test
    void successOnAnotherToolDoesNotClearFailures() {
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        emit(SESSION_1, eventFor("grep", ToolResultState.SUCCESS, null, null, null));
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertEquals(1, nextReasoning(SESSION_1).size());
    }

    @Test
    void retriableFailuresUseTheHigherThreshold() {
        for (int i = 0; i < RepeatedFailureGuard.RETRIABLE_FAILURE_THRESHOLD - 1; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.TIMEOUT, "a.txt", true);
        }
        assertTrue(nextReasoning(SESSION_1).isEmpty());
        fail(SESSION_1, TOOL, ToolErrorKind.TIMEOUT, "a.txt", true);
        assertEquals(1, nextReasoning(SESSION_1).size());
    }

    @Test
    void unclassifiedErrorsAreNeverCounted() {
        for (int i = 0; i < 10; i++) {
            emit(SESSION_1, eventFor(TOOL, ToolResultState.ERROR, null, null, null));
        }
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    @Test
    void deniedWithoutKindIsTreatedAsPermissionDenied() {
        for (int i = 0; i < RepeatedFailureGuard.FAILURE_THRESHOLD; i++) {
            emit(SESSION_1, eventFor(TOOL, ToolResultState.DENIED, null, null, null));
        }
        List<Msg> reminder = nextReasoning(SESSION_1);
        assertEquals(1, reminder.size());
        assertTrue(reminder.get(0).getTextContent().contains("permission_denied"));
    }

    @Test
    void sessionsAreIsolated() {
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_2, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_2, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
        assertTrue(nextReasoning(SESSION_2).isEmpty());

        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertEquals(1, nextReasoning(SESSION_1).size());
        assertTrue(nextReasoning(SESSION_2).isEmpty());
    }

    @Test
    void atMostTwoRemindersPerTurn() {
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        assertEquals(1, nextReasoning(SESSION_1).size());
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        assertEquals(1, nextReasoning(SESSION_1).size());
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        assertTrue(nextReasoning(SESSION_1).isEmpty(), "third reminder is suppressed");
    }

    @Test
    void aNewTurnResetsTheCounters() {
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        assertEquals(1, nextReasoning(SESSION_1).size());

        newTurn(SESSION_1);
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertEquals(1, nextReasoning(SESSION_1).size());
    }

    @Test
    void finishingTheCallResetsTheCounters() {
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        guard.onAgentFinish(null, turn(SESSION_1).context, null).block();
        assertTrue(nextReasoning(SESSION_1).isEmpty());
        newTurn(SESSION_1);
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    private static Object invocationToken(RuntimeContext ctx) throws ClassNotFoundException {
        return ctx.get(Class.forName(RepeatedFailureGuard.class.getName() + "$InvocationToken"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copiedContextsShareTheWindowAndFinishOrCancellationClearsIt(boolean finish)
            throws Exception {
        TestTurn active = turn(SESSION_1);
        assertNotSame(active.effectiveContext, active.context);
        Object token = invocationToken(active.effectiveContext);
        assertNotNull(token);
        assertSame(token, invocationToken(active.context));
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        RuntimeContext reasoning = RuntimeContext.builder(active.context).build();
        assertEquals(1, reasoningMessages(reasoning).size());
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        if (finish) {
            guard.onAgentFinish(null, RuntimeContext.builder(reasoning).build(), null).block();
        } else {
            active.subscription.dispose();
        }
        assertTrue(reasoningMessages(reasoning).isEmpty(), "pending second reminder was cleared");
    }

    @Test
    void reusingSourceContextInstallsFreshTokensWithoutMutatingTheSource() throws Exception {
        RuntimeContext source = RuntimeContext.builder().sessionId("reused-source").build();
        TestTurn first = turn(source);
        Object firstToken = invocationToken(first.context);
        assertNotNull(firstToken);
        assertNull(invocationToken(source));
        fail(source, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(source, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        newTurn(source);
        TestTurn second = turn(source);
        assertNotSame(firstToken, invocationToken(second.context));
        assertNull(invocationToken(source));
        fail(source, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(source, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertTrue(nextReasoning(source).isEmpty());
        fail(source, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertEquals(1, nextReasoning(source).size());
    }

    @Test
    void childSubscriptionReplacesInheritedTokenWithoutChangingTheParent() throws Exception {
        TestTurn parent = turn(SESSION_1);
        Object parentToken = invocationToken(parent.context);
        RuntimeContext childSource =
                RuntimeContext.builder(parent.context).threadId("child").build();
        assertSame(parentToken, invocationToken(childSource));
        TestTurn child = turn(childSource);
        Object childToken = invocationToken(child.context);
        assertNotNull(childToken);
        assertNotSame(parentToken, childToken);
        assertSame(parentToken, invocationToken(parent.context));
        assertSame(parentToken, invocationToken(childSource));
        for (int i = 0; i < 2; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
            fail(childSource, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        assertTrue(nextReasoning(SESSION_1).isEmpty());
        assertTrue(nextReasoning(childSource).isEmpty());
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(childSource, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        child.subscription.dispose();
        assertTrue(nextReasoning(childSource).isEmpty());
        assertEquals(1, nextReasoning(SESSION_1).size(), "child cleanup preserves parent reminder");
    }

    @Test
    void lateFinallyUsesCapturedTokenEvenIfTheEffectiveContextMarkerWasReplaced() throws Exception {
        RuntimeContext effective = RuntimeContext.builder(SESSION_1).build();
        Sinks.Many<AgentEvent> oldEvents = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<AgentEvent> newEvents = Sinks.many().unicast().onBackpressureBuffer();
        List<RuntimeContext> admitted = new ArrayList<>();
        Flux<AgentEvent> invocation =
                guard.onAgent(
                        null,
                        effective,
                        new AgentInput(List.of()),
                        input -> {
                            RuntimeContext ready = RuntimeContext.builder(effective).build();
                            guard.onAgentStateReady(
                                    null, ready, AgentState.builder().build(), input.msgs());
                            admitted.add(ready);
                            return admitted.size() == 1 ? oldEvents.asFlux() : newEvents.asFlux();
                        });
        Disposable oldSubscription = invocation.subscribe();
        RuntimeContext old = admitted.get(0);
        Object oldToken = invocationToken(old);
        guard.onAgentFinish(null, old, null).block();
        Disposable newSubscription = invocation.subscribe();
        try {
            RuntimeContext fresh = admitted.get(1);
            assertNotSame(oldToken, invocationToken(fresh));
            assertSame(invocationToken(fresh), invocationToken(effective));
            for (int i = 0; i < 3; i++) {
                guard.onAgentEvent(
                        null,
                        fresh,
                        eventFor(
                                TOOL,
                                ToolResultState.ERROR,
                                ToolErrorKind.NOT_FOUND,
                                "a.txt",
                                null));
            }
            assertTrue(reasoningMessages(old).isEmpty());
            guard.onAgentFinish(null, old, null).block();
            assertEquals(Sinks.EmitResult.OK, oldEvents.tryEmitComplete());
            assertEquals(1, reasoningMessages(fresh).size(), "old drain cannot clear new owner");
        } finally {
            oldSubscription.dispose();
            newSubscription.dispose();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queuedSubscriptionCannotClaimOrClearTheActiveWindow(boolean cancelQueued)
            throws Exception {
        TestTurn active = turn(SESSION_1);
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        RuntimeContext queued = RuntimeContext.builder(active.context).build();
        assertSame(invocationToken(active.context), invocationToken(queued));
        Sinks.One<Void> admission = Sinks.one();
        Sinks.Many<AgentEvent> events = Sinks.many().unicast().onBackpressureBuffer();
        Disposable subscription =
                guard.onAgent(
                                null,
                                queued,
                                new AgentInput(List.of()),
                                input ->
                                        admission
                                                .asMono()
                                                .thenMany(
                                                        Flux.defer(
                                                                () -> {
                                                                    guard.onAgentStateReady(
                                                                            null,
                                                                            queued,
                                                                            AgentState.builder()
                                                                                    .build(),
                                                                            input.msgs());
                                                                    return events.asFlux();
                                                                })))
                        .subscribe();
        try {
            assertNotSame(invocationToken(active.context), invocationToken(queued));
            for (int i = 0; i < 3; i++) {
                guard.onAgentEvent(
                        null,
                        queued,
                        eventFor(
                                TOOL,
                                ToolResultState.ERROR,
                                ToolErrorKind.NOT_FOUND,
                                "a.txt",
                                null));
            }
            assertTrue(reasoningMessages(queued).isEmpty(), "queued events have no owner");
            assertTrue(nextReasoning(SESSION_1).isEmpty(), "queued events cannot enter A's window");
            if (cancelQueued) {
                subscription.dispose();
            }
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
            assertTrue(reasoningMessages(queued).isEmpty(), "B cannot consume A's reminder");
            List<Msg> activeReminder = nextReasoning(SESSION_1);
            assertEquals(1, activeReminder.size(), "subscription/cancellation preserves A's count");
            assertTrue(activeReminder.get(0).getTextContent().contains("3 times"));
            guard.onAgentFinish(null, active.context, null).block();
            if (!cancelQueued) {
                assertEquals(Sinks.EmitResult.OK, admission.tryEmitEmpty());
                for (int i = 0; i < 2; i++) {
                    guard.onAgentEvent(
                            null,
                            queued,
                            eventFor(
                                    TOOL,
                                    ToolResultState.ERROR,
                                    ToolErrorKind.NOT_FOUND,
                                    "a.txt",
                                    null));
                }
                assertTrue(reasoningMessages(queued).isEmpty(), "B starts with fresh counters");
                guard.onAgentEvent(
                        null,
                        queued,
                        eventFor(
                                TOOL,
                                ToolResultState.ERROR,
                                ToolErrorKind.NOT_FOUND,
                                "a.txt",
                                null));
                assertEquals(1, reasoningMessages(queued).size(), "B initializes on admission");
                guard.onAgentFinish(null, queued, null).block();
                assertEquals(Sinks.EmitResult.OK, events.tryEmitComplete());
            }
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void lateOldEventsReasoningFinishAndDrainCannotTouchTheNewOwner() {
        TestTurn old = turn(SESSION_1);
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        guard.onAgentFinish(null, old.context, null).block();
        RuntimeContext fresh = RuntimeContext.builder(old.context).build();
        Sinks.Many<AgentEvent> events = Sinks.many().unicast().onBackpressureBuffer();
        Disposable subscription =
                guard.onAgent(
                                null,
                                fresh,
                                new AgentInput(List.of()),
                                input ->
                                        Flux.defer(
                                                () -> {
                                                    guard.onAgentStateReady(
                                                            null,
                                                            fresh,
                                                            AgentState.builder().build(),
                                                            input.msgs());
                                                    return events.asFlux();
                                                }))
                        .subscribe();
        try {
            for (int i = 0; i < 2; i++) {
                guard.onAgentEvent(
                        null,
                        fresh,
                        eventFor(
                                TOOL,
                                ToolResultState.ERROR,
                                ToolErrorKind.NOT_FOUND,
                                "a.txt",
                                null));
            }
            for (int i = 0; i < 3; i++) {
                guard.onAgentEvent(
                        null,
                        old.context,
                        eventFor(
                                TOOL,
                                ToolResultState.ERROR,
                                ToolErrorKind.NOT_FOUND,
                                "a.txt",
                                null));
            }
            assertTrue(reasoningMessages(fresh).isEmpty(), "late events cannot count for B");
            guard.onAgentEvent(
                    null,
                    fresh,
                    eventFor(TOOL, ToolResultState.ERROR, ToolErrorKind.NOT_FOUND, "a.txt", null));
            assertTrue(reasoningMessages(old.context).isEmpty(), "old reasoning cannot consume B");
            guard.onAgentFinish(null, old.context, null).block();
            assertEquals(Sinks.EmitResult.OK, old.events.tryEmitComplete());
            List<Msg> reminder = reasoningMessages(fresh);
            assertEquals(1, reminder.size(), "late finish/drain cannot clear B's pending reminder");
            assertTrue(reminder.get(0).getTextContent().contains("3 times"));
            guard.onAgentFinish(null, fresh, null).block();
            assertEquals(Sinks.EmitResult.OK, events.tryEmitComplete());
            assertTrue(reasoningMessages(fresh).isEmpty());
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void eachSignatureUsesItsOwnInterventionProgress() {
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        assertEquals(1, nextReasoning(SESSION_1).size());

        // A different loop reaches its own first threshold instead of inheriting the first
        // signature's progress.
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, "grep", ToolErrorKind.INVALID_ARGUMENT, "a.txt");
        }
        List<Msg> second = nextReasoning(SESSION_1);
        assertEquals(1, second.size());
        assertTrue(second.get(0).getTextContent().contains("grep"));
    }

    @Test
    void aSuccessLetsTheSameSignatureStartOver() {
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        assertEquals(1, nextReasoning(SESSION_1).size());

        ToolResultEndEvent success = eventFor(TOOL, ToolResultState.SUCCESS, null, null, null);
        success.withMetadata(Map.of(ToolErrorMetadata.METADATA_ERROR_SCOPE, "a.txt"));
        emit(SESSION_1, success);
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        assertEquals(1, nextReasoning(SESSION_1).size());
    }

    @Test
    void unknownToolNameFallsBackToAStableDomain() {
        for (int i = 0; i < 3; i++) {
            emit(
                    SESSION_1,
                    eventFor(
                            null, ToolResultState.ERROR, ToolErrorKind.INTERNAL_ERROR, null, null));
        }
        List<Msg> reminder = nextReasoning(SESSION_1);
        assertEquals(1, reminder.size());
        assertTrue(reminder.get(0).getTextContent().contains("unknown"));
    }

    @Test
    void reminderTextIsBoundedEvenForDeepScopes() {
        String scope = "x".repeat(5_000);
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, scope);
        }
        List<Msg> reminder = nextReasoning(SESSION_1);
        assertEquals(1, reminder.size());
        String text = reminder.get(0).getTextContent();
        assertTrue(text.length() <= RepeatedFailureGuard.MAX_REMINDER_CHARS, text);
        assertFalse(text.isEmpty());
    }

    private void completedCall(
            Map<String, Object> arguments, ToolResultState state, String... chunks) {
        List<AgentEvent> events = new ArrayList<>();
        for (String chunk : chunks) {
            events.add(new ToolResultTextDeltaEvent("reply", "call", "execute", chunk));
        }
        events.add(eventFor("execute", state, null, null, null));
        observeActing(
                        null,
                        SESSION_1,
                        new ActingInput(List.of(new ToolUseBlock("call", "execute", arguments))),
                        input -> Flux.fromIterable(events))
                .blockLast();
    }

    @Test
    void identicalSuccessfulCallsGetANeutralReminderAndKeepTheirState() {
        for (int i = 0; i < 3; i++) {
            completedCall(
                    Map.of("command", "business unknown"),
                    ToolResultState.SUCCESS,
                    "Unknown public tool: unknown");
        }
        String text = nextReasoning(SESSION_1).get(0).getTextContent();
        assertTrue(text.contains("identical arguments"), text);
        assertTrue(text.contains("does not imply failure"), text);
        assertFalse(text.contains("has failed"), text);
        assertFalse(text.contains("business unknown"), "raw arguments must not be echoed");
        assertFalse(text.contains("Unknown public tool"), "raw output must not be echoed");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    @Test
    void fingerprintsIgnoreMapOrderingCallIdsAndTextChunkBoundaries() {
        completedCall(
                Map.of("a", 1, "nested", Map.of("x", 2, "y", 3)),
                ToolResultState.SUCCESS,
                "same",
                " result");
        completedCall(
                Map.of("nested", Map.of("y", 3, "x", 2), "a", 1),
                ToolResultState.SUCCESS,
                "same result");
        completedCall(
                Map.of("a", 1, "nested", Map.of("x", 2, "y", 3)),
                ToolResultState.SUCCESS,
                "sa",
                "me result");
        assertEquals(1, nextReasoning(SESSION_1).size());
    }

    @Test
    void changedArgumentsOrResultsDoNotAccumulateARepetition() {
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "one");
        completedCall(Map.of("command", "b"), ToolResultState.SUCCESS, "one");
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "two");
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "one");
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "one");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    @Test
    void anUnclassifiedErrorCanRepeatWithoutBeingClassified() {
        for (int i = 0; i < 3; i++) {
            completedCall(Map.of("command", "a"), ToolResultState.ERROR, "opaque result");
        }
        assertTrue(
                nextReasoning(SESSION_1)
                        .get(0)
                        .getTextContent()
                        .contains("does not imply failure"));
    }

    @Test
    void successForAnotherScopeDoesNotClearTheFailureWindow() {
        fail(SESSION_1, "execute", ToolErrorKind.COMMAND_FAILED, "python");
        fail(SESSION_1, "execute", ToolErrorKind.COMMAND_FAILED, "python");
        completedCall(Map.of("command", "ls"), ToolResultState.SUCCESS, "listing");
        fail(SESSION_1, "execute", ToolErrorKind.COMMAND_FAILED, "python");
        String text = nextReasoning(SESSION_1).get(0).getTextContent();
        assertTrue(text.contains("command_failed"), text);
        assertTrue(text.contains("diagnostic output"), text);
    }

    @Test
    void successForTheExactCallClearsOnlyThatCallsFailures() {
        for (int i = 0; i < 2; i++) {
            ToolResultEndEvent error =
                    eventFor(
                            "execute",
                            ToolResultState.ERROR,
                            ToolErrorKind.COMMAND_FAILED,
                            "python",
                            null);
            observeActing(
                            null,
                            SESSION_1,
                            new ActingInput(
                                    List.of(
                                            new ToolUseBlock(
                                                    "call",
                                                    "execute",
                                                    Map.of("command", "python a")))),
                            input -> Flux.just(error))
                    .blockLast();
        }
        completedCall(Map.of("command", "ls"), ToolResultState.SUCCESS, "listing");
        completedCall(Map.of("command", "python a"), ToolResultState.SUCCESS, "fixed");
        fail(SESSION_1, "execute", ToolErrorKind.COMMAND_FAILED, "python");
        fail(SESSION_1, "execute", ToolErrorKind.COMMAND_FAILED, "python");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    @Test
    void dataResultsAreFingerprintedAndInterleavedCallsStayIsolated() {
        for (int i = 0; i < 3; i++) {
            String id = "data-" + i;
            observeActing(
                            null,
                            SESSION_1,
                            new ActingInput(
                                    List.of(
                                            new ToolUseBlock(id, TOOL, Map.of("path", "a")),
                                            new ToolUseBlock("other", TOOL, Map.of("path", "b")))),
                            input ->
                                    Flux.just(
                                            new ToolResultTextDeltaEvent(
                                                    "reply", "other", TOOL, "unrelated-" + id),
                                            new ToolResultDataDeltaEvent(
                                                    "reply",
                                                    id,
                                                    TOOL,
                                                    TextBlock.builder().text("same").build()),
                                            new ToolResultEndEvent(
                                                    "reply",
                                                    "other",
                                                    TOOL,
                                                    ToolResultState.SUCCESS),
                                            new ToolResultEndEvent(
                                                    "reply", id, TOOL, ToolResultState.SUCCESS)))
                    .blockLast();
        }
        assertEquals(1, nextReasoning(SESSION_1).size());
    }

    @Test
    void repetitionRespectsTheSharedCapAndResetsOnNewTurns() {
        for (int n = 0; n < 3; n++) {
            for (int i = 0; i < 3; i++) {
                completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "same");
            }
            assertEquals(n < 2 ? 1 : 0, nextReasoning(SESSION_1).size());
        }
        newTurn(SESSION_1);
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "same");
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "same");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    @Test
    void completedEmptyOutputIsEquivalentAcrossDeltaShapesAndResetsChangedResults() {
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "x");
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "x");
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS);
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "x");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS);
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "");
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS);
        assertEquals(1, nextReasoning(SESSION_1).size());
    }

    @Test
    void aChangedResultRetractsPendingRepetitionWithoutSpendingAnIntervention() {
        for (int i = 0; i < 3; i++) {
            completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "x");
        }
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "changed");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
        for (int n = 0; n < 2; n++) {
            for (int i = 0; i < 3; i++) {
                completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "changed");
            }
            assertEquals(1, nextReasoning(SESSION_1).size());
        }
    }

    @Test
    void multipleThresholdsInOneBatchSpendOnlyTheDeliveredReminder() {
        List<ToolUseBlock> calls = new ArrayList<>();
        List<AgentEvent> events = new ArrayList<>();
        for (String command : List.of("a", "b")) {
            for (int i = 0; i < 3; i++) {
                String id = command + i;
                calls.add(new ToolUseBlock(id, "execute", Map.of("command", command)));
                events.add(new ToolResultTextDeltaEvent("reply", id, "execute", "same"));
                events.add(new ToolResultEndEvent("reply", id, "execute", ToolResultState.SUCCESS));
            }
        }
        observeActing(null, SESSION_1, new ActingInput(calls), input -> Flux.fromIterable(events))
                .blockLast();
        assertEquals(1, nextReasoning(SESSION_1).size());
        completedCall(Map.of("command", "b"), ToolResultState.SUCCESS, "same");
        assertEquals(1, nextReasoning(SESSION_1).size());
        for (int i = 0; i < 3; i++) {
            completedCall(Map.of("command", "b"), ToolResultState.SUCCESS, "same");
        }
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    @Test
    void classifiedAndFingerprintPathsDoNotDoubleRemindForOneFailureLoop() {
        for (int n = 0; n < 3; n++) {
            ToolResultEndEvent error =
                    eventFor(
                            "execute",
                            ToolResultState.ERROR,
                            ToolErrorKind.COMMAND_FAILED,
                            "python",
                            null);
            observeActing(
                            null,
                            SESSION_1,
                            new ActingInput(
                                    List.of(
                                            new ToolUseBlock(
                                                    "call",
                                                    "execute",
                                                    Map.of("command", "python a")))),
                            input ->
                                    Flux.just(
                                            new ToolResultTextDeltaEvent(
                                                    "reply", "call", "execute", "same"),
                                            error))
                    .blockLast();
        }
        assertEquals(1, nextReasoning(SESSION_1).size());
        ToolResultEndEvent error =
                eventFor(
                        "execute",
                        ToolResultState.ERROR,
                        ToolErrorKind.COMMAND_FAILED,
                        "python",
                        null);
        observeActing(
                        null,
                        SESSION_1,
                        new ActingInput(
                                List.of(
                                        new ToolUseBlock(
                                                "call", "execute", Map.of("command", "python a")))),
                        input ->
                                Flux.just(
                                        new ToolResultTextDeltaEvent(
                                                "reply", "call", "execute", "same"),
                                        error))
                .blockLast();
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    private void sameCallBatch(int count, boolean classified) {
        List<ToolUseBlock> calls = new ArrayList<>();
        List<AgentEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = "batch-" + i;
            calls.add(new ToolUseBlock(id, "execute", Map.of("command", "python a")));
            events.add(new ToolResultTextDeltaEvent("reply", id, "execute", "same"));
            ToolResultEndEvent end =
                    new ToolResultEndEvent(
                            "reply",
                            id,
                            "execute",
                            classified ? ToolResultState.ERROR : ToolResultState.SUCCESS);
            if (classified) {
                end.withMetadata(ToolErrorMetadata.of(ToolErrorKind.COMMAND_FAILED, "python"));
            }
            events.add(end);
        }
        observeActing(null, SESSION_1, new ActingInput(calls), input -> Flux.fromIterable(events))
                .blockLast();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void secondReminderRequiresThreeOccurrencesAfterActualInjection(boolean classified) {
        sameCallBatch(6, classified);
        String first = nextReasoning(SESSION_1).get(0).getTextContent();
        assertTrue(first.contains("6 times"), first);
        sameCallBatch(1, classified);
        assertTrue(nextReasoning(SESSION_1).isEmpty());
        sameCallBatch(1, classified);
        assertTrue(nextReasoning(SESSION_1).isEmpty());
        sameCallBatch(1, classified);
        assertEquals(
                1,
                nextReasoning(SESSION_1).size(),
                "three further occurrences must work even after the eight-entry window saturates");
    }

    @Test
    void classificationChangesRetractAndResetUnclassifiedRepetitionsWithTheSameOutput() {
        for (int i = 0; i < 3; i++) {
            completedCall(Map.of("command", "python a"), ToolResultState.ERROR, "same");
        }
        sameCallBatch(1, true);
        assertTrue(
                nextReasoning(SESSION_1).isEmpty(),
                "classified result retracts old neutral pending");
        completedCall(Map.of("command", "python a"), ToolResultState.ERROR, "same");
        completedCall(Map.of("command", "python a"), ToolResultState.ERROR, "same");
        assertTrue(
                nextReasoning(SESSION_1).isEmpty(),
                "switching back starts a new repetition window");
        completedCall(Map.of("command", "python a"), ToolResultState.ERROR, "same");
        assertTrue(
                nextReasoning(SESSION_1)
                        .get(0)
                        .getTextContent()
                        .contains("does not imply failure"));
    }

    private void digestCall(Object digest, ToolResultState state, String output) {
        ToolResultEndEvent end = eventFor("execute", state, null, null, null);
        if (digest != null) {
            end.withMetadata(Map.of("agentscope_output_sha256", digest));
        }
        observeActing(
                        null,
                        SESSION_1,
                        new ActingInput(
                                List.of(
                                        new ToolUseBlock(
                                                "call", "execute", Map.of("command", "a")))),
                        input ->
                                Flux.just(
                                        new ToolResultTextDeltaEvent(
                                                "reply", "call", "execute", output),
                                        end))
                .blockLast();
    }

    @Test
    void trustedDigestCountsIdenticalRawOutputDespiteRandomDisplayPaths() {
        for (int i = 0; i < 3; i++) {
            digestCall("a".repeat(64), ToolResultState.SUCCESS, "output at /tmp/random-" + i);
        }
        String reminder = nextReasoning(SESSION_1).get(0).getTextContent();
        assertTrue(reminder.contains("does not imply failure"), reminder);
        assertFalse(reminder.contains("has failed"), reminder);
        assertFalse(reminder.contains("/tmp/"), reminder);
    }

    @Test
    void digestChangeRetractsPendingReminderWithoutSpendingBudget() {
        for (int i = 0; i < 3; i++) {
            digestCall("a".repeat(64), ToolResultState.SUCCESS, "same display");
        }
        digestCall("b".repeat(64), ToolResultState.SUCCESS, "same display");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
        for (int n = 0; n < 2; n++) {
            for (int i = 0; i < 3; i++) {
                digestCall("b".repeat(64), ToolResultState.SUCCESS, "random-" + i);
            }
            assertEquals(1, nextReasoning(SESSION_1).size());
        }
    }

    @Test
    void digestDoesNotEraseResultStateDifferences() {
        digestCall("a".repeat(64), ToolResultState.SUCCESS, "same");
        digestCall("a".repeat(64), ToolResultState.SUCCESS, "same");
        digestCall("a".repeat(64), ToolResultState.ERROR, "same");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    @Test
    void absentOrInvalidDigestFallsBackToExactTextAndData() {
        for (Object invalid :
                List.of(
                        "",
                        "a".repeat(63),
                        "A".repeat(64),
                        "g".repeat(64),
                        " " + "a".repeat(64),
                        123)) {
            newTurn(SESSION_1);
            digestCall(null, ToolResultState.SUCCESS, "same");
            digestCall(invalid, ToolResultState.SUCCESS, "same");
            digestCall(invalid, ToolResultState.SUCCESS, "same");
            assertEquals(1, nextReasoning(SESSION_1).size(), "invalid digest: " + invalid);
            newTurn(SESSION_1);
            for (int i = 0; i < 3; i++) {
                digestCall(invalid, ToolResultState.SUCCESS, "exact path /tmp/" + i);
            }
            assertTrue(nextReasoning(SESSION_1).isEmpty(), "paths must not be normalized");
        }
        newTurn(SESSION_1);
        for (int i = 0; i < 3; i++) {
            ToolResultEndEvent end = eventFor(TOOL, ToolResultState.SUCCESS, null, null, null);
            end.withMetadata(Map.of("agentscope_output_sha256", "bad"));
            observeActing(
                            null,
                            SESSION_1,
                            new ActingInput(List.of(new ToolUseBlock("call", TOOL, Map.of()))),
                            input ->
                                    Flux.just(
                                            new ToolResultDataDeltaEvent(
                                                    "reply",
                                                    "call",
                                                    TOOL,
                                                    TextBlock.builder().text("same").build()),
                                            end))
                    .blockLast();
        }
        assertEquals(1, nextReasoning(SESSION_1).size());
    }

    @Test
    void stateReadyInputIdentifiesResumedCallsWithoutSharedAgentState() {
        List<ToolUseBlock> calls = new ArrayList<>();
        List<AgentEvent> events = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String id = "resumed-" + i;
            calls.add(new ToolUseBlock(id, TOOL, Map.of("path", "a")));
            events.add(new ToolResultStartEvent("reply", id, TOOL));
            events.add(new ToolResultTextDeltaEvent("reply", id, TOOL, "same"));
            events.add(new ToolResultEndEvent("reply", id, TOOL, ToolResultState.SUCCESS));
        }
        List<Msg> acceptedMessages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(new ArrayList<>(calls))
                                .build());
        guard.onAgent(
                        null,
                        SESSION_1,
                        new AgentInput(List.of()),
                        input ->
                                Flux.defer(
                                        () -> {
                                            guard.onAgentStateReady(
                                                    null,
                                                    SESSION_1,
                                                    AgentState.builder().build(),
                                                    acceptedMessages);
                                            return Flux.fromIterable(events)
                                                    .doOnNext(
                                                            event ->
                                                                    guard.onAgentEvent(
                                                                            null, SESSION_1, event))
                                                    .doOnComplete(
                                                            () ->
                                                                    assertEquals(
                                                                            1,
                                                                            nextReasoning(SESSION_1)
                                                                                    .size()));
                                        }))
                .blockLast();
        assertTrue(nextReasoning(SESSION_1).isEmpty(), "completion clears the turn");
        assertFalse(
                guard.activePoints()
                        .contains(
                                io.agentscope.core.middleware.MiddlewareBase.ExtensionPoint
                                        .ON_ACTING));
    }

    @Test
    void permissionDenialGuidanceStopsInsteadOfSuggestingBypass() {
        String text = RepeatedFailureGuard.renderText(TOOL, "permission_denied", "a", 3);
        assertTrue(text.contains("stop this operation"), text);
        assertTrue(text.contains("Do not bypass"), text);
        assertTrue(text.contains("tools, identities, or paths"), text);
        assertTrue(text.contains("explicit authorization"), text);
        assertFalse(text.contains("choose another"), text);
        assertFalse(text.contains("use another tool"), text);
        assertTrue(
                RepeatedFailureGuard.renderText(TOOL, "not_found", "a", 3)
                        .contains("or choose another one before trying again"));
    }

    @Test
    void forwardedChildFailuresDoNotEnterTheParentWindow() {
        for (int i = 0; i < 3; i++) {
            ToolResultEndEvent child =
                    eventFor(TOOL, ToolResultState.ERROR, ToolErrorKind.NOT_FOUND, "a", null);
            child.withSource("parent/child/session");
            emit(SESSION_1, child);
        }
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    @Test
    void missingContextStillCountsUnderOneSlot() {
        assertNull(
                guard.onReasoning(
                                null,
                                null,
                                new ReasoningInput(List.of(), List.of(), null),
                                input -> Flux.<AgentEvent>empty())
                        .blockLast());
        for (int i = 0; i < 3; i++) {
            emit(null, eventFor(TOOL, ToolResultState.ERROR, ToolErrorKind.NOT_FOUND, "a", null));
        }
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();
        guard.onReasoning(
                        null,
                        null,
                        new ReasoningInput(List.of(), List.of(), null),
                        input -> {
                            seen.set(input);
                            return Flux.empty();
                        })
                .blockLast();
        assertNotNull(seen.get());
        assertEquals(1, seen.get().messages().size());
    }
}
