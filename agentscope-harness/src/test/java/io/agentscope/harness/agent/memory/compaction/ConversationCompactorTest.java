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
package io.agentscope.harness.agent.memory.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig.PruneConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** Tests message routing between summarization, memory flushing, and the preserved tail. */
class ConversationCompactorTest {

    /** Verifies that a prior summary is summarized again but is neither flushed nor retained. */
    @Test
    void compactIfNeeded_routesPriorSummaryToSummaryInputButNotFlushOrTail() {
        Fixture fixture = new Fixture();

        List<Msg> compacted =
                fixture.compact(
                        List.of(
                                summary("S1"),
                                message("M2"),
                                message("TAIL_1"),
                                message("TAIL_2")));

        assertTrue(fixture.model.prompt(0).contains("S1"));
        assertTrue(fixture.model.prompt(0).contains("M2"));
        assertEquals(List.of("M2"), texts(fixture.flushInputs.get(0)));
        assertEquals(3, compacted.size());
        assertTrue(compacted.get(0).getId().startsWith(ConversationCompactor.SUMMARY_MSG_NAME));
        assertEquals(ConversationCompactor.SUMMARY_MSG_NAME, compacted.get(0).getName());
        assertEquals(List.of("TAIL_1", "TAIL_2"), texts(compacted.subList(1, compacted.size())));
    }

    /** Verifies that multiple prior summaries in the prefix are replaced by one new summary. */
    @Test
    void compactIfNeeded_mergesMultiplePriorSummariesIntoOneReplacement() {
        Fixture fixture = new Fixture();

        List<Msg> compacted =
                fixture.compact(
                        List.of(
                                summary("S0"),
                                message("M1"),
                                summary("S1"),
                                message("M2"),
                                message("TAIL_1"),
                                message("TAIL_2")));

        String prompt = fixture.model.prompt(0);
        assertTrue(prompt.contains("S0"));
        assertTrue(prompt.contains("S1"));
        assertEquals(List.of("M1", "M2"), texts(fixture.flushInputs.get(0)));
        assertEquals(
                1,
                compacted.stream()
                        .filter(msg -> ConversationCompactor.SUMMARY_MSG_NAME.equals(msg.getName()))
                        .count());
    }

    /** Verifies that a summary-only prefix is not treated as empty conversation history. */
    @Test
    void compactIfNeeded_summaryOnlyPrefixDoesNotBecomeEmptyHistory() {
        Fixture fixture = new Fixture();

        List<Msg> compacted =
                fixture.compact(
                        List.of(summary("ONLY_OLD_SUMMARY"), message("TAIL_1"), message("TAIL_2")));

        assertTrue(fixture.model.prompt(0).contains("ONLY_OLD_SUMMARY"));
        assertTrue(fixture.flushInputs.get(0).isEmpty());
        assertTrue(text(compacted.get(0)).contains("ROUND_1"));
        assertFalse(text(compacted.get(0)).contains("No previous conversation history."));
    }

    /** Verifies that chained compaction summarizes the prior summary with the new prefix. */
    @Test
    void compactIfNeeded_chainedCompactionUsesPreviousSummaryAndNewPrefix() {
        Fixture fixture = new Fixture();
        List<Msg> first =
                fixture.compact(
                        List.of(
                                summary("S1"),
                                message("M2"),
                                message("TAIL_1"),
                                message("TAIL_2")));
        List<Msg> secondInput = new ArrayList<>(first);
        secondInput.add(message("M3"));
        secondInput.add(message("TAIL_3"));
        secondInput.add(message("TAIL_4"));

        List<Msg> second = fixture.compact(secondInput);

        assertTrue(fixture.model.prompt(1).contains("ROUND_1"));
        assertTrue(fixture.model.prompt(1).contains("M3"));
        assertFalse(text(second.get(0)).contains("ROUND_1"));
        assertTrue(text(second.get(0)).contains("ROUND_2"));
        assertEquals(List.of("TAIL_1", "TAIL_2", "M3"), texts(fixture.flushInputs.get(1)));
    }

    /** Verifies that disabling memory flush does not remove the prior summary from summarization. */
    @Test
    void compactIfNeeded_skipsFlushWhenDisabledAndStillUsesPriorSummary() {
        Fixture fixture = new Fixture(false);

        fixture.compact(
                List.of(summary("S1"), message("M2"), message("TAIL_1"), message("TAIL_2")));

        assertTrue(fixture.model.prompt(0).contains("S1"));
        assertTrue(fixture.model.prompt(0).contains("M2"));
        assertTrue(fixture.flushInputs.isEmpty());
    }

    /** Verifies that memory-flush fallback does not swallow a user interruption. */
    @Test
    void compactIfNeeded_propagatesInterruptedMemoryFlush() {
        RecordingModel model = new RecordingModel();
        MemoryFlushManager flushManager = mock(MemoryFlushManager.class);
        when(flushManager.flushMemories(any(RuntimeContext.class), anyList()))
                .thenReturn(Mono.error(new InterruptedException("interrupted while flushing")));
        ConversationCompactor compactor = new ConversationCompactor(model, flushManager);

        StepVerifier.create(
                        compactor.compactIfNeeded(
                                mock(RuntimeContext.class),
                                compactableMessages(),
                                config(true),
                                "agent-id",
                                "session-id"))
                .expectError(InterruptedException.class)
                .verify();
    }

    /** Verifies that the durable compaction end event contains before and after metrics. */
    @Test
    void compactIfNeeded_recordsCompactionMetrics() {
        InMemorySessionLogStore store = new InMemorySessionLogStore();
        RuntimeContext runtimeContext =
                RuntimeContext.builder().userId("user").sessionId("session").build();
        var log = store.open(new SessionKey("user", "agent-id", "session"), runtimeContext);
        SessionRecorder recorder = new SessionRecorder(log, "turn");
        runtimeContext.put(SessionRecorder.CONTEXT_KEY, recorder);

        List<Msg> input = compactableMessages();
        MemoryFlushManager flushManager = new MemoryFlushManager(null, null);
        try {
            List<Msg> compacted =
                    new ConversationCompactor(new RecordingModel(), flushManager)
                            .compactIfNeeded(
                                    runtimeContext, input, config(false), "agent-id", "session")
                            .block()
                            .orElseThrow();

            var end =
                    log.readAfter(0, 100).stream()
                            .filter(event -> event.type().equals("compaction/end"))
                            .findFirst()
                            .orElseThrow();
            assertEquals("completed", end.data().get("status"));
            assertEquals(input.size(), end.data().get("beforeMsgCount"));
            assertEquals(
                    TokenCounterUtil.calculateToken(input), end.data().get("beforeTokenCount"));
            assertEquals(compacted.size(), end.data().get("afterMsgCount"));
            assertEquals(
                    TokenCounterUtil.calculateToken(compacted), end.data().get("afterTokenCount"));
            assertTrue(end.data().containsKey("durationMs"));
        } finally {
            recorder.close();
        }
    }

    /** Lifecycle CustomEvents fire on the parent stream even without a session recorder. */
    @Test
    void compactIfNeeded_emitsLifecycleEventsIndependentOfSessionRecorder() {
        ParentEventSink sink = new ParentEventSink();
        RuntimeContext runtimeContext =
                RuntimeContext.builder().userId("user").sessionId("no-recorder").build();

        List<Msg> input = compactableMessages();
        List<Msg> compacted =
                new ConversationCompactor(new RecordingModel(), new MemoryFlushManager(null, null))
                        .compactIfNeeded(
                                runtimeContext,
                                input,
                                config(false),
                                "agent-id",
                                "no-recorder",
                                sink.emitter(),
                                "NORMAL")
                        .block()
                        .orElseThrow();

        sink.assertEveryEmitAccepted();
        List<AgentEvent> emitted = sink.delivered();
        assertEquals(2, emitted.size());
        CustomEvent started = (CustomEvent) emitted.get(0);
        CustomEvent finished = (CustomEvent) emitted.get(1);
        assertEquals(ConversationCompactor.STARTED_EVENT_NAME, started.getName());
        assertEquals(ConversationCompactor.FINISHED_EVENT_NAME, finished.getName());
        assertEquals("started", started.getValue().get("status"));
        assertEquals("NORMAL", started.getValue().get("mode"));
        assertEquals(input.size(), started.getValue().get("beforeMsgCount"));
        assertEquals("completed", finished.getValue().get("status"));
        assertEquals("NORMAL", finished.getValue().get("mode"));
        assertEquals(compacted.size(), finished.getValue().get("afterMsgCount"));
        assertTrue(finished.getValue().containsKey("durationMs"));
        assertEquals(
                started.getValue().get("compactionId"), finished.getValue().get("compactionId"));
    }

    /** No lifecycle event fires when the trigger condition is not met. */
    @Test
    void compactIfNeeded_emitsNoLifecycleEventsWhenNotTriggered() {
        ParentEventSink sink = new ParentEventSink();
        RuntimeContext runtimeContext =
                RuntimeContext.builder().userId("user").sessionId("short").build();

        new ConversationCompactor(new RecordingModel(), new MemoryFlushManager(null, null))
                .compactIfNeeded(
                        runtimeContext,
                        List.of(message("only one"), message("only two")),
                        config(false),
                        "agent-id",
                        "short",
                        sink.emitter(),
                        "NORMAL")
                .block();

        assertTrue(sink.delivered().isEmpty());
        assertTrue(sink.emitResults().isEmpty());
    }

    /** A failing emission never fails an otherwise successful compaction. */
    @Test
    void compactIfNeeded_swallowsEmitterFailure() {
        AgentEventEmitter failing =
                event -> {
                    throw new IllegalStateException("sink closed");
                };
        RuntimeContext runtimeContext =
                RuntimeContext.builder().userId("user").sessionId("failing-emitter").build();

        List<Msg> compacted =
                new ConversationCompactor(new RecordingModel(), new MemoryFlushManager(null, null))
                        .compactIfNeeded(
                                runtimeContext,
                                compactableMessages(),
                                config(false),
                                "agent-id",
                                "failing-emitter",
                                failing,
                                "EMERGENCY")
                        .block()
                        .orElseThrow();

        assertFalse(compacted.isEmpty());
    }

    /** A skipped compaction still reports the unchanged after-counts so the after metrics are set. */
    @Test
    void compactIfNeeded_skippedEventCarriesUnchangedAfterCounts() {
        ParentEventSink sink = new ParentEventSink();
        RuntimeContext runtimeContext =
                RuntimeContext.builder().userId("user").sessionId("skipped").build();
        CompactionConfig skippedConfig =
                CompactionConfig.builder()
                        .triggerMessages(3)
                        .triggerTokens(0)
                        .keepMessages(10)
                        .keepTokens(0)
                        .summaryPrompt("SUMMARY_INPUT:\n{messages}")
                        .flushBeforeCompact(false)
                        .truncateArgs(null)
                        .prune(null)
                        .build();
        List<Msg> input = compactableMessages();

        new ConversationCompactor(new RecordingModel(), new MemoryFlushManager(null, null))
                .compactIfNeeded(
                        runtimeContext,
                        input,
                        skippedConfig,
                        "agent-id",
                        "skipped",
                        sink.emitter(),
                        "NORMAL")
                .block();

        sink.assertEveryEmitAccepted();
        List<AgentEvent> emitted = sink.delivered();
        assertEquals(2, emitted.size());
        CustomEvent finished = (CustomEvent) emitted.get(1);
        assertEquals("skipped", finished.getValue().get("status"));
        assertEquals(input.size(), finished.getValue().get("afterMsgCount"));
        assertEquals(
                TokenCounterUtil.calculateToken(input), finished.getValue().get("afterTokenCount"));
    }

    /** An unknown mode label is coerced to the controlled NORMAL value, never emitted verbatim. */
    @Test
    void compactIfNeeded_coercesUnknownModeToNormal() {
        ParentEventSink sink = new ParentEventSink();
        RuntimeContext runtimeContext =
                RuntimeContext.builder().userId("user").sessionId("weird-mode").build();

        new ConversationCompactor(new RecordingModel(), new MemoryFlushManager(null, null))
                .compactIfNeeded(
                        runtimeContext,
                        compactableMessages(),
                        config(false),
                        "agent-id",
                        "weird-mode",
                        sink.emitter(),
                        "NOT-A-MODE")
                .block();

        sink.assertEveryEmitAccepted();
        List<AgentEvent> emitted = sink.delivered();
        assertEquals(2, emitted.size());
        assertEquals("NORMAL", ((CustomEvent) emitted.get(0)).getValue().get("mode"));
        assertEquals("NORMAL", ((CustomEvent) emitted.get(1)).getValue().get("mode"));
    }

    /** A failing compaction still emits a matching finished event carrying the failure status. */
    @Test
    void compactIfNeeded_emitsFinishedEventOnCompactionFailure() {
        ParentEventSink sink = new ParentEventSink();
        RuntimeContext runtimeContext =
                RuntimeContext.builder().userId("user").sessionId("emit-fail").build();
        Model failingModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.error(new IllegalStateException("summary failed"));
                    }

                    @Override
                    public String getModelName() {
                        return "failing-model";
                    }
                };

        StepVerifier.create(
                        new ConversationCompactor(failingModel, new MemoryFlushManager(null, null))
                                .compactIfNeeded(
                                        runtimeContext,
                                        compactableMessages(),
                                        config(false),
                                        "agent-id",
                                        "emit-fail",
                                        sink.emitter(),
                                        "EMERGENCY"))
                .expectErrorMessage("summary failed")
                .verify();

        sink.assertEveryEmitAccepted();
        List<AgentEvent> emitted = sink.delivered();
        assertEquals(2, emitted.size());
        CustomEvent started = (CustomEvent) emitted.get(0);
        CustomEvent finished = (CustomEvent) emitted.get(1);
        assertEquals("started", started.getValue().get("status"));
        assertEquals("failed", finished.getValue().get("status"));
        assertEquals("IllegalStateException", finished.getValue().get("errorType"));
        assertEquals("EMERGENCY", finished.getValue().get("mode"));
        assertFalse(finished.getValue().containsKey("afterMsgCount"));
        assertEquals(
                started.getValue().get("compactionId"), finished.getValue().get("compactionId"));
    }

    /**
     * A failing resource acquisition (here: a closed recorder rejecting the start record) still
     * emits a matching finished event and lets the original error propagate.
     */
    @Test
    void compactIfNeeded_emitsFailedFinishedWhenStartRecordingFails() {
        ParentEventSink sink = new ParentEventSink();
        RuntimeContext runtimeContext =
                RuntimeContext.builder().userId("user").sessionId("closed-recorder").build();
        InMemorySessionLogStore store = new InMemorySessionLogStore();
        var log = store.open(new SessionKey("user", "agent-id", "closed-recorder"), runtimeContext);
        SessionRecorder recorder = new SessionRecorder(log, "turn");
        runtimeContext.put(SessionRecorder.CONTEXT_KEY, recorder);
        recorder.close();

        StepVerifier.create(
                        new ConversationCompactor(
                                        new RecordingModel(), new MemoryFlushManager(null, null))
                                .compactIfNeeded(
                                        runtimeContext,
                                        compactableMessages(),
                                        config(false),
                                        "agent-id",
                                        "closed-recorder",
                                        sink.emitter(),
                                        "NORMAL"))
                .expectErrorMatches(
                        error ->
                                error instanceof SessionLogException
                                        && String.valueOf(error.getMessage()).contains("closed"))
                .verify();

        sink.assertEveryEmitAccepted();
        List<AgentEvent> emitted = sink.delivered();
        assertEquals(2, emitted.size());
        CustomEvent started = (CustomEvent) emitted.get(0);
        CustomEvent finished = (CustomEvent) emitted.get(1);
        assertEquals(ConversationCompactor.STARTED_EVENT_NAME, started.getName());
        assertEquals(ConversationCompactor.FINISHED_EVENT_NAME, finished.getName());
        assertEquals("failed", finished.getValue().get("status"));
        assertFalse(finished.getValue().containsKey("errorType"));
        assertFalse(finished.getValue().containsKey("afterMsgCount"));
        assertEquals(
                started.getValue().get("compactionId"), finished.getValue().get("compactionId"));
    }

    /**
     * A parent-stream cancellation delivered while the compaction work is in flight (the start
     * record already committed and the model call already started) settles the execution once
     * through the usingWhen cancel cleanup and writes exactly one durable cancelled end for a
     * healthy recorder, before the managed close.
     */
    @Test
    void compactIfNeeded_recordsCancelledEndOnParentCancel() {
        ParentEventSink sink = new ParentEventSink();
        RuntimeContext runtimeContext =
                RuntimeContext.builder().userId("user").sessionId("cancel-session").build();
        InMemorySessionLogStore store = new InMemorySessionLogStore();
        var log = store.open(new SessionKey("user", "agent-id", "cancel-session"), runtimeContext);
        SessionRecorder recorder = new SessionRecorder(log, "turn");
        runtimeContext.put(SessionRecorder.CONTEXT_KEY, recorder);
        CountDownLatch modelInvoked = new CountDownLatch(1);
        Model hangingModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        modelInvoked.countDown();
                        return Flux.never();
                    }

                    @Override
                    public String getModelName() {
                        return "hanging-model";
                    }
                };

        try {
            Disposable subscription =
                    new ConversationCompactor(hangingModel, new MemoryFlushManager(null, null))
                            .compactIfNeeded(
                                    runtimeContext,
                                    compactableMessages(),
                                    config(false),
                                    "agent-id",
                                    "cancel-session",
                                    sink.emitter(),
                                    "NORMAL")
                            .subscribe();
            // The model call starts only after the start-record resource was delivered, so this
            // deterministically cancels at the cleanup stage with the acquisition complete.
            assertTrue(awaitLatch(modelInvoked), "compaction work never started");

            subscription.dispose();

            sink.assertEveryEmitAccepted();
            List<AgentEvent> emitted = sink.delivered();
            assertEquals(2, emitted.size());
            CustomEvent started = (CustomEvent) emitted.get(0);
            CustomEvent finished = (CustomEvent) emitted.get(1);
            assertEquals(ConversationCompactor.STARTED_EVENT_NAME, started.getName());
            assertEquals(ConversationCompactor.FINISHED_EVENT_NAME, finished.getName());
            assertEquals("cancelled", finished.getValue().get("status"));
            assertEquals(
                    started.getValue().get("compactionId"),
                    finished.getValue().get("compactionId"));

            // The owning call execution's managed shutdown is SessionRecorder.finish(state,status):
            // it flushes on boundedElastic and then closes. Await that exact contract
            // deterministically, then verify close keeps the durable pair stable.
            recorder.finish(agentState("cancel-session"), "cancelled").block();
            SessionEvent durableEnd = assertDurableCompactionPair(log, "cancelled");
            assertEquals(
                    finished.getValue().get("compactionId"), durableEnd.data().get("compactionId"));
            recorder.close();
            assertDurableCompactionPair(log, "cancelled");
        } finally {
            recorder.close();
        }
    }

    /**
     * A cancellation delivered while the start-record resource acquisition is parked inside its
     * commit settles once through the {@code doFinally} safety net; the parked acquisition exits
     * afterwards and the late resource completion cannot start a second settlement. The test
     * synchronizes on the acquisition entering and exiting the commit gate, so the parked stage
     * is exercised deterministically instead of by scheduling luck, and the gate keeps waiting
     * through cancellation interrupts so the healthy recorder under test never flips into a
     * failure state. The durable end is carried by the owning call's managed shutdown contract
     * ({@code finish(state,status)}) before the close.
     */
    @Test
    void compactIfNeeded_recordsCancelledEndWhenCancelledBeforeStartRecordLands() {
        ParentEventSink sink = new ParentEventSink();
        RuntimeContext runtimeContext =
                RuntimeContext.builder().userId("user").sessionId("before-delivery").build();
        InMemorySessionLogStore store = new InMemorySessionLogStore();
        var log = store.open(new SessionKey("user", "agent-id", "before-delivery"), runtimeContext);
        GatedSessionLog gatedLog = new GatedSessionLog(log);
        SessionRecorder recorder = new SessionRecorder(gatedLog, "turn");
        runtimeContext.put(SessionRecorder.CONTEXT_KEY, recorder);
        AtomicBoolean modelInvoked = new AtomicBoolean();
        Model neverModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        modelInvoked.set(true);
                        return Flux.never();
                    }

                    @Override
                    public String getModelName() {
                        return "never-model";
                    }
                };
        // The terminal emission itself releases the parked commit, so the acquisition stage
        // stays parked for the whole cancellation walk without any wall-clock dependency.
        AgentEventEmitter emitter =
                event -> {
                    sink.emit(event);
                    if (event instanceof CustomEvent custom
                            && ConversationCompactor.FINISHED_EVENT_NAME.equals(custom.getName())) {
                        gatedLog.releaseGate();
                    }
                };

        try {
            Disposable subscription =
                    new ConversationCompactor(neverModel, new MemoryFlushManager(null, null))
                            .compactIfNeeded(
                                    runtimeContext,
                                    compactableMessages(),
                                    config(false),
                                    "agent-id",
                                    "before-delivery",
                                    emitter,
                                    "NORMAL")
                            .subscribe();
            // Deterministic stage: the start record is buffered and the acquisition is now parked
            // inside its commit, so the cancel below is delivered before the resource lands.
            assertTrue(
                    awaitLatch(gatedLog.commitEnteredLatch()),
                    "acquisition never reached the commit gate");
            subscription.dispose();
            // The cancel walk must not wedge or fail the parked acquisition: the terminal
            // emission opens the gate and the acquisition exits its commit on its own.
            assertTrue(
                    awaitLatch(gatedLog.commitExitedLatch()),
                    "parked acquisition never exited the commit gate");
            assertFalse(modelInvoked.get(), "compaction work must not start before the record");

            sink.assertEveryEmitAccepted();
            List<AgentEvent> emitted = sink.delivered();
            assertEquals(2, emitted.size());
            CustomEvent started = (CustomEvent) emitted.get(0);
            CustomEvent finished = (CustomEvent) emitted.get(1);
            assertEquals(ConversationCompactor.STARTED_EVENT_NAME, started.getName());
            assertEquals(ConversationCompactor.FINISHED_EVENT_NAME, finished.getName());
            assertEquals("cancelled", finished.getValue().get("status"));
            assertEquals(
                    started.getValue().get("compactionId"),
                    finished.getValue().get("compactionId"));

            // Managed shutdown barrier: the same finish(state,status) contract the owning call
            // execution uses; the durable end travels with its flush before the close.
            recorder.finish(agentState("before-delivery"), "cancelled").block();
            SessionEvent durableEnd = assertDurableCompactionPair(log, "cancelled");
            assertEquals(
                    finished.getValue().get("compactionId"), durableEnd.data().get("compactionId"));
            recorder.close();
            assertDurableCompactionPair(log, "cancelled");
        } finally {
            gatedLog.releaseGate();
            recorder.close();
        }
    }

    /** Asserts the durable log holds exactly one compaction/start and one matching end. */
    private static SessionEvent assertDurableCompactionPair(SessionLog log, String expectedStatus) {
        List<SessionEvent> events = log.readAfter(0, 100);
        List<SessionEvent> starts =
                events.stream().filter(event -> "compaction/start".equals(event.type())).toList();
        List<SessionEvent> ends =
                events.stream().filter(event -> "compaction/end".equals(event.type())).toList();
        assertEquals(1, starts.size(), () -> "durable starts: " + starts);
        assertEquals(1, ends.size(), () -> "durable ends: " + ends);
        assertEquals(
                starts.get(0).data().get("compactionId"), ends.get(0).data().get("compactionId"));
        assertEquals(expectedStatus, ends.get(0).data().get("status"));
        return ends.get(0);
    }

    /** Each subscription of the returned Mono owns an independent execution and settles once. */
    @Test
    void compactIfNeeded_settlesOncePerSubscription() {
        ParentEventSink sink = new ParentEventSink();
        RuntimeContext runtimeContext =
                RuntimeContext.builder().userId("user").sessionId("double").build();

        Mono<Optional<List<Msg>>> compaction =
                new ConversationCompactor(new RecordingModel(), new MemoryFlushManager(null, null))
                        .compactIfNeeded(
                                runtimeContext,
                                compactableMessages(),
                                config(false),
                                "agent-id",
                                "double",
                                sink.emitter(),
                                "NORMAL");

        compaction.block();
        compaction.block();

        sink.assertEveryEmitAccepted();
        List<AgentEvent> emitted = sink.delivered();
        assertEquals(4, emitted.size());
        CustomEvent firstStarted = (CustomEvent) emitted.get(0);
        CustomEvent secondStarted = (CustomEvent) emitted.get(2);
        assertEquals(ConversationCompactor.STARTED_EVENT_NAME, firstStarted.getName());
        assertEquals(
                ConversationCompactor.FINISHED_EVENT_NAME,
                ((CustomEvent) emitted.get(1)).getName());
        assertEquals("completed", ((CustomEvent) emitted.get(1)).getValue().get("status"));
        assertEquals("completed", ((CustomEvent) emitted.get(3)).getValue().get("status"));
        assertFalse(
                firstStarted
                        .getValue()
                        .get("compactionId")
                        .equals(secondStarted.getValue().get("compactionId")));
    }

    /** Minimal call-scoped state required by the managed-shutdown barrier. */
    private static AgentState agentState(String sessionId) {
        return AgentState.builder().sessionId(sessionId).userId("user").build();
    }

    /** Waits for a test latch without leaking {@link InterruptedException} into call sites. */
    private static boolean awaitLatch(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * A live parent {@code streamEvents()}-style sink: a real unicast sink with a subscribed
     * consumer, recording both the delivered events and every emission result.
     */
    private static final class ParentEventSink {

        private final Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        private final List<AgentEvent> delivered = new ArrayList<>();
        private final List<Sinks.EmitResult> emitResults = new ArrayList<>();

        private ParentEventSink() {
            sink.asFlux().subscribe(delivered::add);
        }

        private void emit(AgentEvent event) {
            emitResults.add(sink.tryEmitNext(event));
        }

        private AgentEventEmitter emitter() {
            return this::emit;
        }

        private List<AgentEvent> delivered() {
            return delivered;
        }

        private List<Sinks.EmitResult> emitResults() {
            return emitResults;
        }

        private void assertEveryEmitAccepted() {
            assertTrue(
                    emitResults.stream().allMatch(result -> result == Sinks.EmitResult.OK),
                    () -> "emit results: " + emitResults);
        }
    }

    /**
     * Delegating log whose commit boundary can be held open to control acquisition stages and
     * that reports commit entry/exit so tests can synchronize on the parked stage
     * deterministically. The gate keeps waiting through interrupts, so cancelling a parked
     * acquisition cannot turn a healthy recorder into a failure.
     */
    private static final class GatedSessionLog implements SessionLog {

        private final SessionLog delegate;
        private final CountDownLatch commitGate = new CountDownLatch(1);
        private final CountDownLatch commitEntered = new CountDownLatch(1);
        private final CountDownLatch commitExited = new CountDownLatch(1);

        private GatedSessionLog(SessionLog delegate) {
            this.delegate = delegate;
        }

        /** Opens the commit boundary; once released the gate stays open. */
        private void releaseGate() {
            commitGate.countDown();
        }

        private CountDownLatch commitEnteredLatch() {
            return commitEntered;
        }

        private CountDownLatch commitExitedLatch() {
            return commitExited;
        }

        @Override
        public Head head() {
            return delegate.head();
        }

        @Override
        public Writer acquire(String owner, Duration lease) {
            return delegate.acquire(owner, lease);
        }

        @Override
        public void renew(Writer writer, Duration lease) {
            delegate.renew(writer, lease);
        }

        @Override
        public void release(Writer writer) {
            delegate.release(writer);
        }

        @Override
        public Head commit(
                Writer writer, String batchId, long expectedSeq, List<SessionEvent> events) {
            commitEntered.countDown();
            try {
                awaitGateReleased();
                return delegate.commit(writer, batchId, expectedSeq, events);
            } finally {
                commitExited.countDown();
            }
        }

        /**
         * Waits for the gate like a JDBC call would: a cancellation interrupt parks the thread
         * again instead of surfacing as a storage failure. The gate is opened by the terminal
         * emission of the compaction.
         */
        private void awaitGateReleased() {
            boolean released = false;
            while (!released) {
                try {
                    released = commitGate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    // Keep waiting; the cancellation must not fail the commit.
                }
            }
        }

        @Override
        public List<SessionEvent> readAfter(long after, int limit) {
            return delegate.readAfter(after, limit);
        }
    }

    /** Verifies that failed compaction records only metrics known before execution. */
    @Test
    void compactIfNeeded_failedEventOmitsAfterMetrics() {
        InMemorySessionLogStore store = new InMemorySessionLogStore();
        RuntimeContext runtimeContext =
                RuntimeContext.builder().userId("user").sessionId("session").build();
        var log = store.open(new SessionKey("user", "agent-id", "failed-session"), runtimeContext);
        SessionRecorder recorder = new SessionRecorder(log, "turn");
        runtimeContext.put(SessionRecorder.CONTEXT_KEY, recorder);
        Model failingModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.error(new IllegalStateException("summary failed"));
                    }

                    @Override
                    public String getModelName() {
                        return "failing-model";
                    }
                };

        List<Msg> input = compactableMessages();
        try {
            StepVerifier.create(
                            new ConversationCompactor(
                                            failingModel, new MemoryFlushManager(null, null))
                                    .compactIfNeeded(
                                            runtimeContext,
                                            input,
                                            config(false),
                                            "agent-id",
                                            "failed-session"))
                    .expectErrorMessage("summary failed")
                    .verify();

            var end =
                    log.readAfter(0, 100).stream()
                            .filter(event -> event.type().equals("compaction/end"))
                            .findFirst()
                            .orElseThrow();
            assertEquals("failed", end.data().get("status"));
            assertEquals(input.size(), end.data().get("beforeMsgCount"));
            assertEquals(
                    TokenCounterUtil.calculateToken(input), end.data().get("beforeTokenCount"));
            assertFalse(end.data().containsKey("afterMsgCount"));
            assertFalse(end.data().containsKey("afterTokenCount"));
            assertEquals("IllegalStateException", end.data().get("errorType"));
            assertFalse(String.valueOf(end.data()).contains("summary failed"));
            assertTrue(end.data().containsKey("durationMs"));
        } finally {
            recorder.close();
        }
    }

    /** Verifies that compaction events never carry raw message content or input lists. */
    @Test
    void compactIfNeeded_eventsOmitsRawMessageContent() {
        InMemorySessionLogStore store = new InMemorySessionLogStore();
        RuntimeContext runtimeContext =
                RuntimeContext.builder().userId("user").sessionId("redacted-session").build();
        var log =
                store.open(new SessionKey("user", "agent-id", "redacted-session"), runtimeContext);
        SessionRecorder recorder = new SessionRecorder(log, "turn");
        runtimeContext.put(SessionRecorder.CONTEXT_KEY, recorder);

        String longSecret = "z".repeat(256);
        List<Msg> input =
                List.of(
                        message("SENSITIVE-MARKER " + longSecret),
                        message("M2"),
                        message("TAIL_1"),
                        message("TAIL_2"));
        try {
            new ConversationCompactor(new RecordingModel(), new MemoryFlushManager(null, null))
                    .compactIfNeeded(
                            runtimeContext, input, config(false), "agent-id", "redacted-session")
                    .block()
                    .orElseThrow();

            var events = log.readAfter(0, 100);
            var start =
                    events.stream()
                            .filter(event -> event.type().equals("compaction/start"))
                            .findFirst()
                            .orElseThrow();
            assertEquals("started", start.data().get("status"));
            assertEquals(input.size(), start.data().get("beforeMsgCount"));
            assertFalse(start.data().containsKey("inputMessages"));
            String serialized =
                    events.stream()
                            .map(event -> event.type() + ":" + event.data())
                            .reduce("", (left, right) -> left + "\n" + right);
            assertFalse(serialized.contains("SENSITIVE-MARKER"));
            assertFalse(serialized.contains(longSecret));
        } finally {
            recorder.close();
        }
    }

    /** Creates a regular user message. */
    private static Msg message(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /** Creates a summary message injected by the compactor. */
    private static Msg summary(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .name(ConversationCompactor.SUMMARY_MSG_NAME)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /** Creates an input that always triggers compaction and retains a two-message tail. */
    private static List<Msg> compactableMessages() {
        return List.of(message("M1"), message("M2"), message("TAIL_1"), message("TAIL_2"));
    }

    /** Creates focused compaction configuration for fallback-boundary tests. */
    private static CompactionConfig config(boolean flushBeforeCompact) {
        return CompactionConfig.builder()
                .triggerMessages(3)
                .triggerTokens(0)
                .keepMessages(2)
                .keepTokens(0)
                .summaryPrompt("SUMMARY_INPUT:\n{messages}")
                .flushBeforeCompact(flushBeforeCompact)
                .truncateArgs(null)
                .prune(null)
                .build();
    }

    /** Returns the text from the first content block of a message. */
    private static String text(Msg message) {
        return ((TextBlock) message.getContent().get(0)).getText();
    }

    /** Returns message texts for concise routing assertions. */
    private static List<String> texts(List<Msg> messages) {
        return messages.stream().map(ConversationCompactorTest::text).toList();
    }

    /** Holds isolated compactor dependencies and invocation records for each test. */
    private static final class Fixture {

        private final RecordingModel model = new RecordingModel();
        private final MemoryFlushManager flushManager = mock(MemoryFlushManager.class);
        private final RuntimeContext runtimeContext = mock(RuntimeContext.class);
        private final List<List<Msg>> flushInputs = new ArrayList<>();
        private final ConversationCompactor compactor;
        private final CompactionConfig config;

        /** Creates a fixture with memory flushing enabled. */
        private Fixture() {
            this(true);
        }

        /** Creates a fixture with the requested memory-flush setting. */
        private Fixture(boolean flushBeforeCompact) {
            when(flushManager.flushMemories(any(RuntimeContext.class), anyList()))
                    .thenAnswer(
                            invocation -> {
                                List<Msg> input = invocation.getArgument(1);
                                flushInputs.add(List.copyOf(input));
                                return Mono.empty();
                            });
            compactor = new ConversationCompactor(model, flushManager);
            config = config(flushBeforeCompact);
        }

        /** Runs compaction and requires the supplied input to trigger it. */
        private List<Msg> compact(List<Msg> messages) {
            Optional<List<Msg>> result =
                    compactor
                            .compactIfNeeded(
                                    runtimeContext, messages, config, "agent-id", "session-id")
                            .block();
            assertTrue(result.isPresent());
            return result.orElseThrow();
        }
    }

    /** Records summarization prompts and returns a distinct fixed summary for each invocation. */
    private static final class RecordingModel implements Model {

        private final List<List<Msg>> inputs = new ArrayList<>();

        /** Records the model input and emits a fixed text response for the current invocation. */
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            String summary = "ROUND_" + inputs.size();
            return Flux.just(
                    ChatResponse.builder()
                            .id("summary-" + inputs.size())
                            .content(List.of(TextBlock.builder().text(summary).build()))
                            .build());
        }

        /** Returns the test model name. */
        @Override
        public String getModelName() {
            return "recording-model";
        }

        /** Returns the summarization prompt for the specified invocation. */
        private String prompt(int index) {
            return text(inputs.get(index).get(0));
        }
    }
}
