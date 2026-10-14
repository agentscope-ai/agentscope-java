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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.middleware.ModelRequestPreparer;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.context.ContextBudgetExceededException;
import io.agentscope.harness.agent.context.ContextPolicy;
import io.agentscope.harness.agent.context.ContextTokenEstimator;
import io.agentscope.harness.agent.context.HarnessContextBuilder;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** Verifies that compaction fallback does not swallow interrupts or rerun downstream reasoning. */
class CompactionMiddlewareTest {
    @Test
    void nativeOverflowRecoveryKeepsOneExecutionAndRecordsBothModelCalls() {
        AtomicInteger calls = new AtomicInteger();
        ChatModelBase model =
                new ChatModelBase() {
                    public String getModelName() {
                        return "overflow-once";
                    }

                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (calls.getAndIncrement() == 0)
                            return Flux.error(new IllegalStateException("context_length_exceeded"));
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(TextBlock.builder().text("answer").build()))
                                        .build());
                    }
                };
        var recovery = new CompactionMiddleware(null, new SuccessfulSummaryModel(), fixedConfig());
        var agent =
                ReActAgent.builder()
                        .name("overflow-test")
                        .model(model)
                        .sessionLogStore(new InMemorySessionLogStore())
                        .middleware(recovery.overflowRecovery())
                        .build();
        var ctx = context("user", "overflow-session");
        var run =
                agent.prepareCall(
                        List.of(userMessage("old context"), userMessage("latest request")), ctx);
        assertEquals("answer", run.stream().blockLast(Duration.ofSeconds(10)).getTextContent());
        assertEquals(AgentRun.Status.COMPLETED, run.status());
        assertEquals(2, calls.get());
        var facts = agent.sessionLog(ctx).readAfter(0, 1000);
        assertEquals(1, facts.stream().filter(e -> e.type().equals("run/start")).count());
        assertEquals(1, facts.stream().filter(e -> e.type().equals("run/end")).count());
        assertTrue(facts.stream().allMatch(e -> run.runId().equals(e.executionRunId())));
        assertTrue(facts.stream().filter(e -> e.type().equals("model/end")).count() >= 2);
    }

    /** Without a session log the recovery companion still recovers overflow once. */
    @Test
    void overflowRecoveryWorksWithoutSessionLog() {
        AtomicInteger calls = new AtomicInteger();
        var recovery = new CompactionMiddleware(null, new SuccessfulSummaryModel(), fixedConfig());
        var agent =
                ReActAgent.builder()
                        .name("overflow-no-log-test")
                        .model(new OverflowOnceModel(calls, "answer"))
                        .middleware(recovery.overflowRecovery())
                        .build();
        var reply =
                agent.call(
                                List.of(userMessage("old context"), userMessage("latest request")),
                                context("user", "no-log-session"))
                        .block(Duration.ofSeconds(10));
        assertNotNull(reply);
        assertEquals("answer", reply.getTextContent());
        assertEquals(2, calls.get());
    }

    /**
     * The overflow guard skips the retry when there is no durable state to compact: a null state
     * and an empty context both propagate the provider error untouched and spend no summary call.
     */
    @Test
    void overflowGuardSkipsRetryWithoutDurableState() {
        AtomicInteger summaryCalls = new AtomicInteger();
        var companion =
                new CompactionMiddleware(
                                null, new CountingSummaryModel(summaryCalls), noCompactionConfig())
                        .overflowRecovery();
        var input = new ReasoningInput(List.of(userMessage("latest request")), List.of(), null);

        IllegalStateException noState = new IllegalStateException("context_length_exceeded");
        StepVerifier.create(
                        companion.onReasoning(
                                null,
                                RuntimeContext.empty(),
                                input,
                                ignored -> Flux.error(noState)))
                .expectErrorSatisfies(error -> assertSame(noState, error))
                .verify();

        IllegalStateException emptyContext = new IllegalStateException("context_length_exceeded");
        RuntimeContext emptyState =
                RuntimeContext.builder().agentState(AgentState.builder().build()).build();
        StepVerifier.create(
                        companion.onReasoning(
                                null, emptyState, input, ignored -> Flux.error(emptyContext)))
                .expectErrorSatisfies(error -> assertSame(emptyContext, error))
                .verify();

        assertEquals(0, summaryCalls.get(), "the guard must not spend a summary call");
    }

    /**
     * The full middleware (standalone wiring) must also recover stream-event overflows exactly
     * once through its own reasoning boundary.
     */
    @Test
    void streamEventsOverflowRecoveryCompletesOnce() {
        AtomicInteger calls = new AtomicInteger();
        var recovery = new CompactionMiddleware(null, new SuccessfulSummaryModel(), fixedConfig());
        var agent =
                ReActAgent.builder()
                        .name("overflow-stream-test")
                        .model(new OverflowOnceModel(calls, "answer"))
                        .sessionLogStore(new InMemorySessionLogStore())
                        .middleware(recovery)
                        .build();
        List<AgentEvent> events =
                agent.streamEvents(
                                List.of(userMessage("old context"), userMessage("latest request")),
                                context("user", "stream-session"))
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertNotNull(events);
        assertEquals(2, calls.get());
        assertTrue(
                events.stream().anyMatch(event -> event.getType() == AgentEventType.AGENT_END),
                "stream must terminate with an AGENT_END event");
    }

    /**
     * Overflow recovery driven through a real agent stream must surface the compaction lifecycle as
     * EMERGENCY CustomEvents, independent of the session log store.
     */
    @Test
    void overflowRecoveryEmitsCompactionLifecycleEvents() {
        AtomicInteger calls = new AtomicInteger();
        var recovery = new CompactionMiddleware(null, new SuccessfulSummaryModel(), fixedConfig());
        var agent =
                ReActAgent.builder()
                        .name("overflow-lifecycle-test")
                        .model(new OverflowOnceModel(calls, "answer"))
                        .middleware(recovery.overflowRecovery())
                        .build();
        List<AgentEvent> events =
                agent.streamEvents(
                                List.of(userMessage("old context"), userMessage("latest request")),
                                context("user", "lifecycle-session"))
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertNotNull(events);
        List<CustomEvent> compactionEvents =
                events.stream()
                        .filter(CustomEvent.class::isInstance)
                        .map(CustomEvent.class::cast)
                        .filter(
                                event ->
                                        ConversationCompactor.STARTED_EVENT_NAME.equals(
                                                        event.getName())
                                                || ConversationCompactor.FINISHED_EVENT_NAME.equals(
                                                        event.getName()))
                        .toList();
        assertEquals(2, compactionEvents.size());
        CustomEvent started = compactionEvents.get(0);
        CustomEvent finished = compactionEvents.get(1);
        assertEquals(ConversationCompactor.STARTED_EVENT_NAME, started.getName());
        assertEquals(ConversationCompactor.FINISHED_EVENT_NAME, finished.getName());
        assertEquals("EMERGENCY", started.getValue().get("mode"));
        assertEquals("EMERGENCY", finished.getValue().get("mode"));
        assertEquals("started", started.getValue().get("status"));
        assertTrue(
                Set.of("completed", "skipped").contains(finished.getValue().get("status")),
                "settled status must be completed or skipped");
        assertEquals(
                started.getValue().get("compactionId"), finished.getValue().get("compactionId"));
    }

    /** A retry that overflows again must propagate the provider error without another call. */
    @Test
    void secondOverflowPropagatesWithoutAnotherRetry() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger summaryCalls = new AtomicInteger();
        var recovery =
                new CompactionMiddleware(
                        null, new CountingSummaryModel(summaryCalls), noCompactionConfig());
        var agent =
                ReActAgent.builder()
                        .name("overflow-always-test")
                        .model(new AlwaysOverflowModel(calls))
                        .sessionLogStore(new InMemorySessionLogStore())
                        .middleware(recovery.overflowRecovery())
                        .build();
        List<Signal<AgentEvent>> signals =
                agent.streamEvents(
                                List.of(userMessage("old context"), userMessage("latest request")),
                                context("user", "overflow-always-session"))
                        .materialize()
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertNotNull(signals);
        Signal<AgentEvent> terminal = signals.get(signals.size() - 1);
        assertTrue(terminal.isOnError(), "stream must terminate with an error signal");
        assertTrue(
                terminal.getThrowable() instanceof IllegalStateException
                        && terminal.getThrowable().getMessage() != null
                        && terminal.getThrowable()
                                .getMessage()
                                .contains("context_length_exceeded"));
        assertEquals(2, calls.get());
        assertEquals(1, summaryCalls.get());
    }

    /**
     * A second provider failure must not erase the first overflow: the retry error stays terminal
     * while the first overflow is attached as its primary cause, with no extra retry.
     */
    @Test
    void secondOverflowKeepsTheFirstOverflowAsPrimaryCause() {
        AtomicInteger calls = new AtomicInteger();
        List<IllegalStateException> overflows = new ArrayList<>();
        ChatModelBase model =
                new ChatModelBase() {
                    public String getModelName() {
                        return "overflow-capture";
                    }

                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        calls.incrementAndGet();
                        var error = new IllegalStateException("context_length_exceeded");
                        overflows.add(error);
                        return Flux.error(error);
                    }
                };
        var recovery =
                new CompactionMiddleware(null, new SuccessfulSummaryModel(), noCompactionConfig());
        var agent =
                ReActAgent.builder()
                        .name("overflow-cause-test")
                        .model(model)
                        .sessionLogStore(new InMemorySessionLogStore())
                        .middleware(recovery.overflowRecovery())
                        .build();
        List<Signal<AgentEvent>> signals =
                agent.streamEvents(
                                List.of(userMessage("old context"), userMessage("latest request")),
                                context("user", "overflow-cause-session"))
                        .materialize()
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertNotNull(signals);
        Signal<AgentEvent> terminal = signals.get(signals.size() - 1);
        assertTrue(terminal.isOnError(), "the retry failure must terminate the stream");
        assertEquals(2, overflows.size(), "exactly one retry is attempted");
        assertSame(overflows.get(1), terminal.getThrowable(), "the retry error stays terminal");
        assertSame(
                overflows.get(0),
                terminal.getThrowable().getCause(),
                "the first overflow must remain the primary cause");
    }

    /**
     * Provider errors built with an explicit null cause reject {@code initCause()}: the first
     * overflow must fall back to suppressed and the retry error must stay terminal instead of being
     * replaced by the resulting IllegalStateException.
     */
    @Test
    void nullCauseRetryErrorKeepsFirstOverflowAsSuppressed() {
        AtomicInteger calls = new AtomicInteger();
        List<Throwable> errors = new ArrayList<>();
        ChatModelBase model =
                new ChatModelBase() {
                    public String getModelName() {
                        return "overflow-null-cause";
                    }

                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        Throwable error =
                                calls.getAndIncrement() == 0
                                        ? new IllegalStateException("context_length_exceeded")
                                        : new IllegalStateException("retry failed", null);
                        errors.add(error);
                        return Flux.error(error);
                    }
                };
        var recovery =
                new CompactionMiddleware(null, new SuccessfulSummaryModel(), noCompactionConfig());
        var agent =
                ReActAgent.builder()
                        .name("overflow-null-cause-test")
                        .model(model)
                        .middleware(recovery.overflowRecovery())
                        .build();
        List<Signal<AgentEvent>> signals =
                agent.streamEvents(
                                List.of(userMessage("old context"), userMessage("latest request")),
                                context("user", "overflow-null-cause-session"))
                        .materialize()
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertNotNull(signals);
        Signal<AgentEvent> terminal = signals.get(signals.size() - 1);
        assertTrue(terminal.isOnError(), "the retry failure must terminate the stream");
        assertEquals(2, errors.size(), "exactly one retry is attempted");
        assertSame(errors.get(1), terminal.getThrowable(), "the retry error stays terminal");
        assertNull(
                terminal.getThrowable().getCause(),
                "the pre-initialized null cause cannot be replaced");
        assertEquals(1, terminal.getThrowable().getSuppressed().length);
        assertSame(
                errors.get(0),
                terminal.getThrowable().getSuppressed()[0],
                "the first overflow must be attached as suppressed");
    }

    /** A cancelled retry must keep Reactor cancellation semantics and never be rewritten. */
    @Test
    void cancelledRetryKeepsCancellationUntouched() {
        AtomicInteger calls = new AtomicInteger();
        List<Throwable> errors = new ArrayList<>();
        ChatModelBase model =
                new ChatModelBase() {
                    public String getModelName() {
                        return "overflow-then-cancel";
                    }

                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        Throwable error =
                                calls.getAndIncrement() == 0
                                        ? new IllegalStateException("context_length_exceeded")
                                        : new CancellationException("user cancelled");
                        errors.add(error);
                        return Flux.error(error);
                    }
                };
        var recovery =
                new CompactionMiddleware(null, new SuccessfulSummaryModel(), noCompactionConfig());
        var agent =
                ReActAgent.builder()
                        .name("overflow-cancel-test")
                        .model(model)
                        .middleware(recovery.overflowRecovery())
                        .build();
        List<Signal<AgentEvent>> signals =
                agent.streamEvents(
                                List.of(userMessage("old context"), userMessage("latest request")),
                                context("user", "overflow-cancel-session"))
                        .materialize()
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertNotNull(signals);
        Signal<AgentEvent> terminal = signals.get(signals.size() - 1);
        assertTrue(terminal.isOnError(), "the cancellation must surface as the terminal signal");
        assertEquals(2, errors.size());
        assertSame(errors.get(1), terminal.getThrowable());
        assertNull(
                terminal.getThrowable().getCause(),
                "a cancelled retry must not be rewritten with the first overflow");
    }

    /**
     * When the emergency summarization itself fails, the first overflow must still be the
     * reported error and no provider retry may run.
     */
    @Test
    void failedEmergencySummaryStillReportsTheFirstOverflow() {
        AtomicInteger calls = new AtomicInteger();
        List<Throwable> errors = new ArrayList<>();
        ChatModelBase model =
                new ChatModelBase() {
                    public String getModelName() {
                        return "overflow-no-retry";
                    }

                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        calls.incrementAndGet();
                        Throwable error = new IllegalStateException("context_length_exceeded");
                        errors.add(error);
                        return Flux.error(error);
                    }
                };
        var recovery =
                new CompactionMiddleware(
                        null,
                        new FailingSummaryModel(new IllegalStateException("summary unavailable")),
                        noCompactionConfig());
        var agent =
                ReActAgent.builder()
                        .name("overflow-summary-failure-test")
                        .model(model)
                        .middleware(recovery.overflowRecovery())
                        .build();
        List<Signal<AgentEvent>> signals =
                agent.streamEvents(
                                List.of(userMessage("old context"), userMessage("latest request")),
                                context("user", "overflow-summary-failure-session"))
                        .materialize()
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertNotNull(signals);
        Signal<AgentEvent> terminal = signals.get(signals.size() - 1);
        assertTrue(terminal.isOnError());
        assertSame(
                errors.get(0),
                terminal.getThrowable(),
                "the first overflow stays the reported error when summarization fails");
        assertEquals(1, calls.get(), "no provider retry may run after a failed emergency summary");
    }

    /**
     * A durable synthetic reminder (here the empty-response loop) must not block overflow
     * recovery: the compacted history cannot re-commit while synthetics remain, but the reduced
     * model view still unblocks the single retry.
     */
    @Test
    void overflowRecoveryWithSyntheticDurableHistoryStillRetries() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger summaryCalls = new AtomicInteger();
        var recovery =
                new CompactionMiddleware(
                        null, new CountingSummaryModel(summaryCalls), noCompactionConfig());
        var agent =
                ReActAgent.builder()
                        .name("overflow-synthetic-test")
                        .model(new EmptyThenOverflowThenAnswerModel(calls))
                        .middleware(recovery.overflowRecovery())
                        .build();
        var reply =
                agent.call(
                                List.of(userMessage("old context"), userMessage("latest request")),
                                context("user", "synthetic-session"))
                        .block(Duration.ofSeconds(10));
        assertNotNull(reply);
        assertEquals("answer", reply.getTextContent());
        assertEquals(3, calls.get());
        assertEquals(1, summaryCalls.get());
    }

    /**
     * The emergency retry must not commit compacted history ahead of the final request preparer:
     * when that preparer rejects the reduced retry request, the durable history must stay exactly
     * as it was before the overflow.
     */
    @Test
    void rejectedEmergencyRetryLeavesHistoryUntouched() {
        AtomicInteger calls = new AtomicInteger();
        var recovery =
                new CompactionMiddleware(null, new SuccessfulSummaryModel(), noCompactionConfig());
        AtomicInteger prepareCalls = new AtomicInteger();
        List<Msg> historyAtRejection = new ArrayList<>();
        ModelRequestPreparer preparer =
                (agent, ctx, input, callId, purpose) -> {
                    if (prepareCalls.getAndIncrement() == 0) return Mono.just(input);
                    AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
                    if (state != null) historyAtRejection.addAll(List.copyOf(state.getContext()));
                    return Mono.error(new ContextBudgetExceededException(10, 5));
                };
        var agent =
                ReActAgent.builder()
                        .name("rejected-retry-test")
                        .model(new OverflowOnceModel(calls, "answer"))
                        .middleware(recovery.overflowRecovery())
                        .modelRequestPreparer(preparer)
                        .build();
        var ctx = context("user", "rejected-retry-session");
        List<Signal<AgentEvent>> signals =
                agent.streamEvents(
                                List.of(userMessage("old context"), userMessage("latest request")),
                                ctx)
                        .materialize()
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertNotNull(signals);
        Signal<AgentEvent> terminal = signals.get(signals.size() - 1);
        assertTrue(terminal.isOnError(), "stream must terminate with an error signal");
        assertTrue(
                terminal.getThrowable() instanceof ContextBudgetExceededException,
                "the rejection from the final request preparer must propagate");
        assertEquals(1, calls.get(), "the rejected retry must not reach the provider again");
        // The emergency summarization succeeded, but its candidate history must not be written
        // before the final request preparer validated the retry request.
        assertEquals(2, historyAtRejection.size());
        assertEquals("old context", historyAtRejection.get(0).getTextContent());
        assertEquals("latest request", historyAtRejection.get(1).getTextContent());
        AgentState state = ctx.getAgentState();
        assertNotNull(state, "the call-scoped state remains readable after the failed run");
        assertEquals(2, state.getContext().size());
        assertEquals("old context", state.getContext().get(0).getTextContent());
    }

    /**
     * Real preparer chain for the same contract: the emergency candidate is validated by an actual
     * {@link HarnessContextBuilder}, whose budget check rejects the oversized summary itself; the
     * builder's own rejection propagates and the canonical history stays untouched.
     */
    @Test
    void realContextBuilderRejectsOversizedEmergencyCandidateAndKeepsHistory() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger summaryCalls = new AtomicInteger();
        var recovery =
                new CompactionMiddleware(
                        null, new LargeSummaryModel(summaryCalls), noCompactionConfig());
        var policy = new ContextPolicy(100, 0, 0, ContextTokenEstimator.approximate(), m -> {});
        var agent =
                ReActAgent.builder()
                        .name("real-chain-rejected-retry")
                        .model(new OverflowOnceModel(calls, "answer"))
                        .middleware(recovery.overflowRecovery())
                        .modelRequestPreparer(new HarnessContextBuilder(policy, null, null))
                        .build();
        var ctx = context("user", "real-chain-rejected-session");
        List<Signal<AgentEvent>> signals =
                agent.streamEvents(
                                List.of(userMessage("old context"), userMessage("latest request")),
                                ctx)
                        .materialize()
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertNotNull(signals);
        Signal<AgentEvent> terminal = signals.get(signals.size() - 1);
        assertTrue(terminal.isOnError(), "stream must terminate with an error signal");
        assertTrue(
                terminal.getThrowable() instanceof ContextBudgetExceededException,
                "the real context builder must reject the oversized emergency candidate, but got: "
                        + terminal.getThrowable());
        assertEquals(1, summaryCalls.get(), "the emergency summary must actually run");
        assertEquals(1, calls.get(), "the rejected retry must not reach the provider again");
        AgentState state = ctx.getAgentState();
        assertNotNull(state, "the call-scoped state remains readable after the failed run");
        assertEquals(2, state.getContext().size());
        assertEquals("old context", state.getContext().get(0).getTextContent());
        assertEquals("latest request", state.getContext().get(1).getTextContent());
    }

    /** An ordinary compaction failure skips compaction and invokes original reasoning once. */
    @Test
    void ordinaryCompactionFailureFallsBackToOriginalInputOnce() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null,
                        new SynchronousFailingSummaryModel(
                                new IllegalStateException("summary provider unavailable")),
                        fixedConfig());

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    return Flux.empty();
                                }))
                .verifyComplete();

        assertEquals(1, nextCalls.get());
    }

    /** A failed summary must not replace history with a failure placeholder. */
    @Test
    void ordinarySummaryFailurePreservesOriginalInput() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null,
                        new FailingSummaryModel(
                                new IllegalStateException("summary provider unavailable")),
                        fixedConfig());

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    assertEquals(input().messages().size(), next.messages().size());
                                    assertEquals(
                                            input().messages().get(0).getTextContent(),
                                            next.messages().get(0).getTextContent());
                                    return Flux.empty();
                                }))
                .verifyComplete();

        assertEquals(1, nextCalls.get());
    }

    /** An interrupt during real compaction summarization must propagate without entering reasoning. */
    @Test
    void interruptedSummaryCompactionPropagatesWithoutCallingNext() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null,
                        new FailingSummaryModel(
                                new InterruptedException("interrupted while compacting")),
                        fixedConfig());

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    return Flux.empty();
                                }))
                .expectError(InterruptedException.class)
                .verify();

        assertEquals(0, nextCalls.get());
    }

    /** An async interrupt from real compaction summarization must not degrade to reasoning. */
    @Test
    void asynchronousInterruptedSummaryCompactionPropagatesWithoutCallingNext() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null,
                        new AsyncFailingSummaryModel(
                                new InterruptedException("interrupted while compacting")),
                        fixedConfig());

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    return Flux.empty();
                                }))
                .expectError(InterruptedException.class)
                .verify();

        assertEquals(0, nextCalls.get());
    }

    /** A user interrupt wrapped by Reactor or application exceptions must also propagate. */
    @Test
    void wrappedInterruptedCompactionPropagatesWithoutCallingNext() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null,
                        new FailingSummaryModel(
                                new IllegalStateException(
                                        "compaction wrapper",
                                        new InterruptedException("interrupted while compacting"))),
                        fixedConfig());

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    return Flux.empty();
                                }))
                .expectErrorMatches(
                        error ->
                                error instanceof IllegalStateException
                                        && error.getCause() instanceof InterruptedException)
                .verify();

        assertEquals(0, nextCalls.get());
    }

    /** A cyclic exception cause chain must terminate and retain ordinary compaction fallback. */
    @Test
    void cyclicCompactionFailureCauseFallsBackWithoutLooping() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null, new FailingSummaryModel(new CyclicCauseException()), fixedConfig());

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    return Flux.empty();
                                }))
                .verifyComplete();

        assertEquals(1, nextCalls.get());
    }

    /** An interrupt from downstream reasoning must propagate without a fallback retry. */
    @Test
    void interruptedDownstreamPropagatesWithoutRetryingNext() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(null, new SuccessfulSummaryModel(), fixedConfig());

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    return Flux.error(
                                            new InterruptedException(
                                                    "interrupted while reasoning"));
                                }))
                .expectError(InterruptedException.class)
                .verify();

        assertEquals(1, nextCalls.get());
    }

    /** An interrupted concurrent session must not prevent another session from entering reasoning. */
    @Test
    void concurrentSessionInterruptDoesNotAffectOtherSession() {
        AtomicInteger interruptedNextCalls = new AtomicInteger();
        AtomicInteger activeNextCalls = new AtomicInteger();
        Set<String> compactedSessions = ConcurrentHashMap.newKeySet();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null, new PerSessionSummaryModel(compactedSessions), fixedConfig());

        Flux<AgentEvent> interrupted =
                middleware.onReasoning(
                        agent(),
                        context("user-a", "session-a"),
                        input("session-a previous", "session-a latest"),
                        next -> {
                            interruptedNextCalls.incrementAndGet();
                            return Flux.empty();
                        });
        Flux<AgentEvent> active =
                middleware.onReasoning(
                        agent(),
                        context("user-b", "session-b"),
                        input("session-b previous", "session-b latest"),
                        next -> {
                            activeNextCalls.incrementAndGet();
                            return Flux.empty();
                        });

        Flux.mergeDelayError(
                        2,
                        interrupted.materialize().subscribeOn(Schedulers.parallel()),
                        active.materialize().subscribeOn(Schedulers.parallel()))
                .collectList()
                .block();

        assertEquals(Set.of("session-a", "session-b"), compactedSessions);
        assertEquals(0, interruptedNextCalls.get());
        assertEquals(1, activeNextCalls.get());
        assertTrue(compactedSessions.contains("session-b"));
    }

    /** An interrupt in a regular model stream must make one request and end as INTERRUPTED. */
    @Test
    void modelStreamInterruptProducesOneCallAndInterruptedTerminalReason() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        CountingDelayedFirstChunkModel model = new CountingDelayedFirstChunkModel(subscribed);
        CompactionMiddleware middleware =
                new CompactionMiddleware(null, model, noCompactionConfig());
        ReActAgent agent =
                ReActAgent.builder()
                        .name("compaction-test-agent")
                        .sysPrompt("test")
                        .model(model)
                        .middleware(middleware)
                        .build();
        RuntimeContext context = context("user", "session");

        var reply =
                agent.call(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("test")
                                                .build()),
                                context)
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        assertTrue(subscribed.await(5, TimeUnit.SECONDS), "model stream should start");
        agent.interrupt(context);

        assertEquals(
                GenerateReason.INTERRUPTED, reply.get(5, TimeUnit.SECONDS).getGenerateReason());
        assertEquals(1, model.callCount.get());
    }

    /** Interrupting one concurrent user session must not cancel the other session's model stream. */
    @Test
    void interruptingOneSessionDoesNotCancelConcurrentSession() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(2);
        CountingDelayedFirstChunkModel model = new CountingDelayedFirstChunkModel(subscribed);
        CompactionMiddleware middleware =
                new CompactionMiddleware(null, model, noCompactionConfig());
        ReActAgent agent =
                ReActAgent.builder()
                        .name("compaction-test-agent")
                        .sysPrompt("test")
                        .model(model)
                        .middleware(middleware)
                        .build();
        RuntimeContext interruptedContext = context("user-a", "session-a");
        RuntimeContext activeContext = context("user-b", "session-b");

        var interruptedReply =
                agent.call(List.of(userMessage("interrupt me")), interruptedContext)
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        var activeReply =
                agent.call(List.of(userMessage("finish normally")), activeContext)
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        assertTrue(subscribed.await(5, TimeUnit.SECONDS), "both model streams should start");
        agent.interrupt(interruptedContext);

        assertEquals(
                GenerateReason.INTERRUPTED,
                interruptedReply.get(5, TimeUnit.SECONDS).getGenerateReason());
        assertFalse(
                activeReply.get(5, TimeUnit.SECONDS).getGenerateReason()
                        == GenerateReason.INTERRUPTED);
        assertEquals(2, model.callCount.get());
    }

    /** Creates stable configuration that enters the compaction branch. */
    private CompactionConfig fixedConfig() {
        return CompactionConfig.builder()
                .triggerTokens(1)
                .keepTokens(1)
                .flushBeforeCompact(false)
                .prune(null)
                .build();
    }

    /**
     * Creates stable configuration that bypasses boundary compaction for model-stream interrupt and
     * overflow recovery tests. Flush is disabled so the counting model only sees summarization
     * calls.
     */
    private CompactionConfig noCompactionConfig() {
        return CompactionConfig.builder()
                .triggerMessages(0)
                .triggerTokens(Integer.MAX_VALUE)
                .keepTokens(0)
                .flushBeforeCompact(false)
                .build();
    }

    /** Creates the ReActAgent test double required by the middleware. */
    private ReActAgent agent() {
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getName()).thenReturn("compaction-test-agent");
        return agent;
    }

    /** Creates a runtime context isolated by user and session. */
    private RuntimeContext context(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    /** Creates the minimal input containing one user message. */
    private ReasoningInput input() {
        return input("previous context", "latest request");
    }

    /** Creates input with enough conversation messages for compaction to cut a prefix. */
    private ReasoningInput input(String first, String second) {
        return new ReasoningInput(
                List.of(userMessage(first), userMessage(second)), List.of(), null);
    }

    /** Creates a minimal user message shared by single-session and concurrent-session tests. */
    private Msg userMessage(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    /** Fails the first request with a provider overflow, then answers successfully. */
    private static final class OverflowOnceModel extends ChatModelBase {
        private final AtomicInteger calls;
        private final String answer;

        private OverflowOnceModel(AtomicInteger calls, String answer) {
            this.calls = calls;
            this.answer = answer;
        }

        @Override
        public String getModelName() {
            return "overflow-once";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (calls.getAndIncrement() == 0)
                return Flux.error(new IllegalStateException("context_length_exceeded"));
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text(answer).build()))
                            .build());
        }
    }

    /** Always fails with a provider overflow error to verify single retry semantics. */
    private static final class AlwaysOverflowModel extends ChatModelBase {
        private final AtomicInteger calls;

        private AlwaysOverflowModel(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public String getModelName() {
            return "overflow-always";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            return Flux.error(new IllegalStateException("context_length_exceeded"));
        }
    }

    /** Counts summary invocations while emitting one successful summary chunk. */
    private static final class CountingSummaryModel extends ChatModelBase {
        private final AtomicInteger calls;

        private CountingSummaryModel(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public String getModelName() {
            return "counting-summary";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("summary").build()))
                            .build());
        }
    }

    /**
     * Emits an empty response first (driving the durable synthetic reminder), then fails once
     * with a provider overflow, then answers — reproducing a synthetic-bearing durable history.
     */
    private static final class EmptyThenOverflowThenAnswerModel extends ChatModelBase {
        private final AtomicInteger calls;

        private EmptyThenOverflowThenAnswerModel(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public String getModelName() {
            return "empty-then-overflow";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int call = calls.getAndIncrement();
            if (call == 0)
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.of(
                                                ThinkingBlock.builder()
                                                        .thinking("thinking only")
                                                        .build()))
                                .build());
            if (call == 1) return Flux.error(new IllegalStateException("context_length_exceeded"));
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("answer").build()))
                            .build());
        }
    }

    /** Throws while creating the summary publisher, exercising the outer compaction fallback. */
    private static final class SynchronousFailingSummaryModel extends ChatModelBase {
        private final RuntimeException error;

        private SynchronousFailingSummaryModel(RuntimeException error) {
            this.error = error;
        }

        @Override
        public String getModelName() {
            return "synchronous-failing-summary";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            throw error;
        }
    }

    /** Provides a summary model that emits one successful summary chunk. */
    private static final class SuccessfulSummaryModel extends ChatModelBase {

        @Override
        public String getModelName() {
            return "successful-summary";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("summary").build()))
                            .build());
        }
    }

    /** Emits a summary large enough to push the emergency retry candidate over a tiny budget. */
    private static final class LargeSummaryModel extends ChatModelBase {
        private final AtomicInteger calls;

        private LargeSummaryModel(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public String getModelName() {
            return "large-summary";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("S".repeat(2400)).build()))
                            .build());
        }
    }

    /** Provides a real summary-model failure for compaction boundary tests. */
    private static class FailingSummaryModel extends ChatModelBase {
        private final Throwable error;

        private FailingSummaryModel(Throwable error) {
            this.error = error;
        }

        @Override
        public String getModelName() {
            return "failing-summary";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.error(error);
        }
    }

    /** Provides an asynchronous summary-model failure for in-flight interrupt coverage. */
    private static final class AsyncFailingSummaryModel extends FailingSummaryModel {

        private AsyncFailingSummaryModel(Throwable error) {
            super(error);
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return super.doStream(messages, tools, options)
                    .delaySubscription(Duration.ofMillis(20));
        }
    }

    /** Fails one session's summary while allowing another to complete. */
    private static final class PerSessionSummaryModel extends ChatModelBase {
        private final Set<String> compactedSessions;

        private PerSessionSummaryModel(Set<String> compactedSessions) {
            this.compactedSessions = compactedSessions;
        }

        @Override
        public String getModelName() {
            return "per-session-summary";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String rendered = messages.get(0).getTextContent();
            if (rendered.contains("session-a")) {
                compactedSessions.add("session-a");
                return Flux.error(new InterruptedException("interrupted session-a"));
            }
            compactedSessions.add("session-b");
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("summary").build()))
                            .build());
        }
    }

    /** Provides a model interruptible before its first chunk to verify real ReAct stream request counts. */
    private static final class CountingDelayedFirstChunkModel extends ChatModelBase {
        private final CountDownLatch subscribed;
        private final AtomicInteger callCount = new AtomicInteger();

        private CountingDelayedFirstChunkModel(CountDownLatch subscribed) {
            this.subscribed = subscribed;
        }

        @Override
        public String getModelName() {
            return "counting-delayed-first-chunk";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(
                    () -> {
                        callCount.incrementAndGet();
                        subscribed.countDown();
                        return Flux.just(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("reply")
                                                                        .build()))
                                                .build())
                                .delaySubscription(Duration.ofMillis(200));
                    });
        }
    }

    /** Supplies a malformed cause chain to verify cycle-safe interruption detection. */
    private static final class CyclicCauseException extends RuntimeException {

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
