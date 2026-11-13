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
package io.agentscope.durable.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableMessages;
import io.agentscope.durable.DurableProtocol;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableSession;
import io.agentscope.durable.DurableTypes.Commit;
import io.agentscope.durable.DurableTypes.EventFilter;
import io.agentscope.durable.DurableTypes.EventInput;
import io.agentscope.durable.DurableTypes.Lease;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.RunChange;
import io.agentscope.durable.DurableTypes.RunStatus;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.SessionView;
import io.agentscope.durable.DurableTypes.Snapshot;
import io.agentscope.durable.DurableTypes.StepChange;
import io.agentscope.durable.DurableTypes.StepPhase;
import io.agentscope.durable.DurableTypes.StepStatus;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.DurableTypes.WaitCondition;
import io.agentscope.durable.EventProjection;
import io.agentscope.durable.EventStreamOptions;
import io.agentscope.durable.HarnessAdapter;
import io.agentscope.durable.HarnessContext;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.SessionEventClient;
import io.agentscope.durable.SessionStreamEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Identical operation semantics for local Backend access and the restricted public HTTP endpoint. */
public abstract class UserApiContract {
    protected SessionBackend backend;
    protected DurableRuntime api;
    protected DurableSession session;
    protected final SessionKey key = new SessionKey("tenant", "session");

    protected abstract SessionBackend openBackend();

    protected DurableRuntime connect(SessionBackend backend) {
        return DurableRuntime.builder()
                .backend(backend)
                .definition("agent", "v1", "1")
                .pollInterval(Duration.ofMillis(10))
                .build();
    }

    @BeforeEach
    void setupUserApi() {
        backend = openBackend();
        api = connect(backend);
        session = api.createSession(key, "agent");
    }

    @AfterEach
    void closeUserApi() {
        if (api != null) api.close();
        if (backend != null) backend.close();
    }

    private static void code(DurableException.Code code, Runnable action) {
        assertEquals(code, assertThrows(DurableException.class, action::run).code());
    }

    @Test
    void resourceLifecyclesReplayAndResynchronizeAcrossClientsWithoutDuplicateFacts() {
        var initial = session.snapshot();
        assertEquals(DurableJson.value(session.view()), initial.projection().path("session"));
        assertEquals(
                DurableJson.value(session.mainThread().view()),
                initial.projection().path("threads").path("main"));
        api.createSession(key, "agent");
        assertEquals(initial.cursor(), session.snapshot().cursor());
        var research = session.createThread("research", "agent");
        var created = session.snapshot().cursor();
        session.createThread("research", "agent");
        assertEquals(created, session.snapshot().cursor());
        research.close();
        var closed = session.snapshot().cursor();
        research.close();
        assertEquals(closed, session.snapshot().cursor());
        session.close();
        var terminal = session.snapshot();
        session.close();
        assertEquals(terminal.cursor(), session.snapshot().cursor());
        var events = session.events(null, 100).events();
        assertEquals(
                List.of(
                        "session.created",
                        "thread.created",
                        "thread.created",
                        "thread.closed",
                        "session.closed"),
                events.stream().map(PublicEvent::type).toList());
        assertTrue(
                events.stream()
                        .allMatch(
                                e ->
                                        e.turnId() == null
                                                && e.runId() == null
                                                && e.stepId() == null));
        assertTrue(
                events.stream()
                        .filter(e -> e.type().startsWith("session."))
                        .allMatch(e -> e.threadId() == null));
        var replay = new EventProjection(key);
        events.forEach(replay::accept);
        assertEquals(terminal.projection(), replay.value());
        assertEquals(terminal.cursor(), replay.cursor());
        var filter = new EventFilter("research", null, null);
        var scoped = new EventProjection(key, filter);
        research.events(null, 100).events().forEach(scoped::accept);
        assertEquals(research.snapshot().projection(), scoped.value());
        assertTrue(scoped.value().path("session").isNull());
        assertEquals(1, scoped.value().path("threads").size());
        assertEquals(
                DurableJson.value(research.view()),
                scoped.value().path("threads").path("research"));
        try (var otherClient = connect(backend)) {
            var found = otherClient.getSession(key);
            assertEquals(terminal, found.snapshot());
            code(DurableException.Code.CONFLICT, () -> found.createThread("late", "agent"));
            code(DurableException.Code.CONFLICT, () -> found.submit("late", "input"));
            backend.pruneEvents(key, terminal.cursor());
            try (var reader =
                    new SessionEventClient(
                            found, filter, initial.cursor(), scoped::reset, scoped::accept)) {
                reader.poll();
                assertEquals(found.snapshot(filter).projection(), scoped.value());
                assertEquals(terminal.cursor(), reader.cursor());
            }
        }
    }

    @Test
    void explicitThreadsKeepPublicTasksHistoryAndReacquiredHandlesSeparate() {
        var main = session.mainThread();
        var research = session.createThread("research", "agent");
        var first = main.submit("main-request", "main input");
        var second = research.submit("research-request", "research input");
        var reacquired = api.getSession(key).getThread("research").getTurn(second.turnId());
        assertEquals(research.key(), reacquired.threadKey());
        assertEquals(TurnStatus.QUEUED, reacquired.status().status());
        assertEquals(first.turnId(), session.getTurn(first.turnId()).turnId());
        code(DurableException.Code.NOT_FOUND, () -> session.getTurn(second.turnId()));
        code(DurableException.Code.CONFLICT, () -> research.submit("main-request", "main input"));
        code(DurableException.Code.CONFLICT, () -> first.cancel("main-request"));
        assertEquals(1, main.history(null, 10).items().size());
        assertEquals(main.history(null, 10), session.history(null, 10));
        assertEquals(main.listTurns(null, 10), session.listTurns(null, 10));
        assertEquals(1, research.history(null, 10).items().size());
        assertEquals(
                "research", research.history(null, 10).items().get(0).path("threadId").asText());
        assertEquals(2, session.listThreads(null, 10).items().size());
        var events = research.events(null, 100);
        assertTrue(events.events().stream().allMatch(event -> "research".equals(event.threadId())));
        assertEquals(session.events(null, 100).cursor(), events.cursor());
        assertEquals(1, research.snapshot().turns().size());
        reacquired.cancel("cancel-research");
        code(
                DurableException.Code.CONFLICT,
                () -> main.submit("cancel-research", "changed operation"));
        assertEquals(TurnStatus.QUEUED, first.status().status());
    }

    @Test
    void executionHistoryIsReadOnlyAndReacquiredThroughTheTurn() {
        var turn = session.submit("task", "hello");
        assertTrue(turn.listRuns(null, 10).items().isEmpty());
        var lease = claim();
        var run = backend.prepareRun(lease, "native-call");
        var frame = backend.frame(lease);
        backend.commit(
                lease,
                new Commit(
                        "started",
                        frame.turn().revision(),
                        frame.checkpoint(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        TurnStatus.RUNNING,
                        null,
                        null,
                        null,
                        null,
                        new RunChange(run.runId(), RunStatus.RUNNING, null, null),
                        List.of(
                                new StepChange(
                                        run.runId(),
                                        "step-1",
                                        StepStatus.RUNNING,
                                        StepPhase.REASONING,
                                        null,
                                        null))));
        var reacquired = api.getSession(key).getTurn(turn.turnId());
        assertEquals(RunStatus.RUNNING, reacquired.getRun(run.runId()).status());
        assertEquals(run.runId(), reacquired.listRuns(null, 10).items().get(0).runId());
        assertEquals(StepPhase.REASONING, reacquired.getStep(run.runId(), "step-1").phase());
        assertEquals("step-1", reacquired.listSteps(run.runId(), null, 10).items().get(0).stepId());
        var current = backend.frame(lease);
        backend.commit(
                lease,
                new Commit(
                        "model-call",
                        current.turn().revision(),
                        current.checkpoint(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(
                                new EventInput(
                                        "model-call",
                                        "model.call.started",
                                        DurableJson.value(
                                                Map.of(
                                                        "modelCallId",
                                                        "model",
                                                        "model",
                                                        "fixture",
                                                        "purpose",
                                                        "REASONING",
                                                        "iteration",
                                                        0,
                                                        "startedAt",
                                                        1000)),
                                        run.runId(),
                                        "step-1")),
                        TurnStatus.RUNNING,
                        null,
                        null,
                        null));
        var call = reacquired.getModelCall(run.runId(), "model");
        assertEquals("step-1", call.stepId());
        assertEquals(
                List.of(call), reacquired.listModelCalls(run.runId(), "step-1", null, 10).items());
        assertEquals(List.of(call), reacquired.listModelCalls(run.runId(), null, null, 10).items());
        assertFalse(call.usageComplete());
        assertEquals(TurnStatus.RUNNING, reacquired.status().status());
        var other = session.submit("different-task", "queued");
        code(DurableException.Code.NOT_FOUND, () -> other.getRun(run.runId()));
        code(DurableException.Code.NOT_FOUND, () -> other.getModelCall(run.runId(), "model"));
    }

    @Test
    void filteredPagesRetainSessionCursorAndValidateTheCompleteRunScope() {
        var first = session.submit("first", "first input");
        var second = session.submit("second", "second input");
        var research = session.createThread("research", "agent");
        var other = research.submit("research", "research input");
        var lease = claim();
        var run = backend.prepareRun(lease, "native");
        var filter = new EventFilter("main", first.turnId(), run.runId());
        var start = session.snapshot().cursor();
        checkpoint(
                lease,
                TurnStatus.RUNNING,
                null,
                List.of(
                        new EventInput(
                                "answer",
                                "message.completed",
                                DurableJson.value(
                                        Map.of(
                                                "messageId",
                                                "answer",
                                                "attempt",
                                                0,
                                                "revision",
                                                0,
                                                "text",
                                                "answer")),
                                run.runId(),
                                null),
                        new EventInput(
                                "file",
                                "artifact.published",
                                DurableJson.parse(
                                        "{\"artifactId\":\"file\",\"uri\":\"memory:report\",\"mimeType\":\"text/plain\","
                                            + "\"name\":null,\"size\":null,\"digest\":null}"),
                                run.runId(),
                                null)),
                List.of());
        second.cancel("cancel-second");
        other.cancel("cancel-other");
        var all = session.events(start, 100);
        var filtered = session.events(filter, start, 100);
        assertEquals(all.cursor(), filtered.cursor());
        assertEquals(all.highWatermark(), filtered.highWatermark());
        assertEquals(
                List.of("message.completed", "artifact.published"),
                filtered.events().stream().map(PublicEvent::type).toList());
        var snapshot = session.snapshot(filter);
        assertEquals(
                List.of(first.turnId()), snapshot.turns().stream().map(t -> t.turnId()).toList());
        assertEquals(1, snapshot.projection().path("messages").size());
        assertEquals(
                run.runId(),
                snapshot.projection().path("artifacts").path("file").path("runId").asText());
        assertEquals(
                first.turnId(),
                snapshot.projection().path("artifacts").path("file").path("turnId").asText());
        assertEquals(session.snapshot().cursor(), snapshot.cursor());
        var empty = session.events(filter, all.events().get(all.events().size() - 2).cursor(), 1);
        assertTrue(empty.events().isEmpty());
        assertEquals(all.cursor(), empty.cursor());
        code(
                DurableException.Code.NOT_FOUND,
                () -> session.events(new EventFilter("research", first.turnId(), null), null, 10));
        code(
                DurableException.Code.NOT_FOUND,
                () -> session.snapshot(new EventFilter("main", second.turnId(), run.runId())));
        code(
                DurableException.Code.NOT_FOUND,
                () -> session.events(new EventFilter("missing", null, null), null, 10));
        var projection = new EventProjection(key, filter);
        session.events(filter, null, 100).events().forEach(projection::accept);
        assertEquals(snapshot.projection().toString(), projection.value().toString());
        code(
                DurableException.Code.INVALID,
                () -> projection.accept(second.events(null, 100).events().get(0)));
    }

    @Test
    void scopedResynchronizationAndRetryNeverLeakOtherTurnsOrAcknowledgeFailedCallbacks() {
        var first = session.submit("first", "first input");
        var second = session.submit("second", "second input");
        session.createThread("research", "agent").submit("research", "research input");
        var lease = claim();
        var filter = new EventFilter("main", first.turnId(), null);
        var old = first.events(null, 1).cursor();
        backend.pruneEvents(key, session.snapshot().cursor());
        var resets = new AtomicInteger();
        var callbacks = new AtomicInteger();
        try (var reader =
                new SessionEventClient(
                        session,
                        filter,
                        old,
                        snapshot -> {
                            assertEquals(1, snapshot.turns().size());
                            assertEquals(first.turnId(), snapshot.turns().get(0).turnId());
                            assertEquals(1, snapshot.projection().path("messages").size());
                            if (resets.incrementAndGet() == 1)
                                throw new IllegalStateException("snapshot failed");
                        },
                        event -> {
                            assertEquals(first.turnId(), event.turnId());
                            if (callbacks.incrementAndGet() == 1)
                                throw new IllegalStateException("event failed");
                        })) {
            assertThrows(IllegalStateException.class, reader::poll);
            assertEquals(old, reader.cursor());
            assertEquals(0, reader.poll());
            assertEquals(2, resets.get());
            String acknowledged = reader.cursor();
            checkpoint(
                    lease,
                    TurnStatus.RUNNING,
                    null,
                    List.of(new EventInput("visible", "x.visible", DurableJson.value(Map.of()))),
                    List.of());
            second.cancel("cancel-second");
            assertThrows(IllegalStateException.class, reader::poll);
            assertEquals(acknowledged, reader.cursor());
            assertEquals(1, reader.poll());
            assertEquals(2, callbacks.get());
            assertEquals(session.snapshot().cursor(), reader.cursor());
            assertEquals(0, reader.poll());
        }
        assertEquals(1, first.snapshot().turns().size());
        assertEquals(TurnStatus.RUNNING, first.status().status());
    }

    @Test
    void managedFilteredSubscriptionStartsWithOnlyItsSelectedSnapshot() throws Exception {
        var first = session.submit("selected", "selected");
        session.submit("excluded", "excluded");
        var filter = new EventFilter("main", first.turnId(), null);
        var delivered = new CountDownLatch(1);
        var observed = new AtomicReference<Snapshot>();
        try (var subscription =
                session.observe(
                        snapshot -> {
                            observed.set(snapshot);
                            delivered.countDown();
                        },
                        event -> {},
                        filter)) {
            assertTrue(delivered.await(3, TimeUnit.SECONDS));
            assertEquals(
                    List.of(first.turnId()),
                    observed.get().turns().stream().map(t -> t.turnId()).toList());
            assertEquals(1, observed.get().projection().path("messages").size());
        }
        // The documented null-cursor replay remains unambiguous at the Java call site.
        try (var subscription = session.observe(null, snapshot -> {}, event -> {})) {
            assertFalse(subscription.isClosed());
        }
        assertEquals(TurnStatus.QUEUED, first.status().status());
    }

    @Test
    void sessionHandleStreamsTypedEventsWithoutExposingPagingOrLiveCursors() {
        var selected = session.submit("selected", "selected");
        session.submit("excluded", "excluded");
        var delivered = new ArrayList<PublicEvent>();
        var cursor = new AtomicReference<String>();
        try (var stream = selected.streamEvents(EventStreamOptions.defaults())) {
            stream.forEach(
                    event -> {
                        var committed = assertInstanceOf(SessionStreamEvent.Committed.class, event);
                        delivered.add(committed.event());
                        cursor.set(committed.event().cursor());
                    },
                    Duration.ofMillis(20),
                    Duration.ofMillis(1));
        }
        assertFalse(delivered.isEmpty());
        assertTrue(delivered.stream().allMatch(event -> selected.turnId().equals(event.turnId())));
        assertNotNull(cursor.get());
    }

    private Lease claim() {
        return backend.claim(new ThreadKey(key, "main"), "test-owner", Duration.ofSeconds(5))
                .orElseThrow();
    }

    private void checkpoint(
            Lease lease,
            TurnStatus status,
            WaitCondition waiting,
            List<EventInput> events,
            List<String> consumed) {
        var frame = backend.frame(lease);
        backend.commit(
                lease,
                new Commit(
                        "commit-" + frame.turn().revision(),
                        frame.turn().revision(),
                        DurableJson.value(Map.of("state", true)),
                        List.of(),
                        List.of(),
                        consumed,
                        events,
                        status,
                        waiting,
                        null,
                        null));
    }

    @Test
    void languageNeutralWorkflowMatchesLocalAndHttpBindings() throws Exception {
        try (var input =
                UserApiContract.class.getResourceAsStream(
                        "/durable-protocol/user-workflows.json")) {
            assertNotNull(input);
            var fixture =
                    DurableJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            String turnId = "unbound";
            for (var step : fixture.path("steps")) {
                var request =
                        DurableJson.convert(
                                DurableJson.parse(
                                        step.path("request").toString().replace("$turn", turnId)),
                                DurableProtocol.Request.class);
                if (step.has("error")) {
                    code(
                            DurableException.Code.valueOf(step.path("error").asText()),
                            () -> api.invoke(request));
                    continue;
                }
                var result = api.invoke(request);
                if (step.path("captureTurn").asBoolean()) turnId = result.path("turnId").asText();
                var expected =
                        DurableJson.parse(step.path("expect").toString().replace("$turn", turnId));
                var fields = expected.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    assertEquals(
                            DurableJson.hash(field.getValue()),
                            DurableJson.hash(result.at(field.getKey())),
                            request.operation() + " " + field.getKey());
                }
            }
        }
    }

    @Test
    void runtimeOwnsBusinessOperationsAndLookupDoesNotCreateOrClaim() {
        assertThrows(IllegalStateException.class, api::start);
        assertTrue(
                Arrays.stream(DurableRuntime.class.getMethods())
                        .noneMatch(m -> "client".equals(m.getName())));
        code(
                DurableException.Code.NOT_FOUND,
                () -> api.getSession(new SessionKey("tenant", "missing")));
        var turn = session.submit("one", "hello");
        assertEquals(TurnStatus.QUEUED, turn.status().status());
        assertEquals(0, turn.status().revision());
        assertEquals(1, session.listTurns(null, 10).items().size());
        api.close();
        assertThrows(IllegalStateException.class, turn::status);
        assertEquals(
                TurnStatus.QUEUED,
                backend.turn(new ThreadKey(key, "main"), turn.turnId()).status());
    }

    @Test
    void publicAdmissionIsIdempotentAndHistorySurvivesEventPruning() {
        var turn = session.submit("one", "hello");
        assertEquals(turn.turnId(), session.submit("one", "hello").turnId());
        code(DurableException.Code.CONFLICT, () -> session.submit("one", "changed"));
        assertEquals(
                List.of("session.created", "thread.created", "turn.queued", "message.completed"),
                session.events(null, 10).events().stream().map(PublicEvent::type).toList());
        var messages = session.history(null, 10).items();
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).path("message").path("role").asText());
        assertEquals("one", messages.get(0).path("requestId").asText());
        assertEquals("submit", messages.get(0).path("inputKind").asText());
        backend.pruneEvents(key, session.snapshot().cursor());
        assertEquals(messages, session.history(null, 10).items());
    }

    @Test
    void paginationIsBoundedAndCannotCrossNamespaceSessionOrQuery() {
        for (int i = 0; i < 3; i++) api.createSession(new SessionKey("tenant", "s" + i), "agent");
        api.createSession(new SessionKey("other", "secret"), "agent");
        var first = api.listSessions("tenant", null, 2);
        assertEquals(2, first.items().size());
        assertNotNull(first.cursor());
        var second = api.listSessions("tenant", first.cursor(), 2);
        assertEquals(
                4,
                Stream.concat(first.items().stream(), second.items().stream())
                        .map(SessionView::key)
                        .distinct()
                        .count());
        code(DurableException.Code.INVALID, () -> api.listSessions("other", first.cursor(), 2));
        session.submit("one", "one");
        session.submit("two", "two");
        var page = session.listTurns(null, 1);
        assertEquals(1, page.items().size());
        assertEquals(1, session.listTurns(page.cursor(), 1).items().size());
        code(DurableException.Code.INVALID, () -> session.history(page.cursor(), 1));
        var other = api.getSession(new SessionKey("tenant", "s0"));
        code(DurableException.Code.INVALID, () -> other.listTurns(page.cursor(), 1));
    }

    @Test
    void queuedCancellationSettlesWithoutStartingBrainOrBypassingActiveWait() {
        var active = session.submit("one", "one");
        var lease = claim();
        checkpoint(lease, TurnStatus.WAITING, new WaitCondition("x.wait", 0), List.of(), List.of());
        var queued = session.submit("two", "two");
        var receipt = queued.cancel("cancel-two");
        assertEquals(receipt, queued.cancel("cancel-two"));
        assertEquals(TurnStatus.CANCELLED, queued.result().status());
        assertEquals(TurnStatus.WAITING, active.status().status());
        assertTrue(
                backend.claim(new ThreadKey(key, "main"), "other", Duration.ofSeconds(1))
                        .isEmpty());
        code(DurableException.Code.CONFLICT, () -> active.cancel("cancel-two"));
        assertTrue(queued.listRuns(null, 10).items().isEmpty());
        assertTrue(active.listRuns(null, 10).items().isEmpty());
    }

    @Test
    void inputReceiptSurvivesClientReplacementAndAppliedInputIsNotReopened() {
        var turn = session.submit("one", "one");
        var lease = claim();
        var receipt = turn.steer("input", "check the result");
        var accepted = session.snapshot();
        try (var replacement = connect(backend)) {
            var recovered = replacement.getSession(key).getTurn(turn.turnId());
            assertEquals(receipt, recovered.steer("input", "check the result"));
            assertEquals(accepted.cursor(), session.snapshot().cursor());
            assertEquals(1, backend.frame(lease).signals().size());
            code(DurableException.Code.CONFLICT, () -> recovered.steer("input", "changed"));
            checkpoint(lease, TurnStatus.SUCCEEDED, null, List.of(), List.of("input"));
            var completed = session.snapshot();
            assertEquals(receipt, recovered.steer("input", "check the result"));
            assertEquals(completed.cursor(), session.snapshot().cursor());
            code(DurableException.Code.CONFLICT, () -> recovered.steer("late", "too late"));
            assertEquals(completed.cursor(), session.snapshot().cursor());
            assertEquals(TurnStatus.SUCCEEDED, recovered.result().status());
        }
    }

    @Test
    void cancellationCannotRewriteACommittedSuccessOrAnotherTurnsState() {
        var first = session.submit("one", "one");
        var lease = claim();
        checkpoint(lease, TurnStatus.SUCCEEDED, null, List.of(), List.of());
        var next = session.submit("two", "two");
        first.cancel("cancel-completed");
        assertEquals(TurnStatus.SUCCEEDED, first.result().status());
        assertEquals(TurnStatus.QUEUED, next.status().status());
        code(DurableException.Code.CONFLICT, () -> next.cancel("cancel-completed"));
        assertEquals(TurnStatus.QUEUED, next.status().status());
        next.cancel("cancel-next");
        assertEquals(TurnStatus.CANCELLED, next.result().status());
    }

    @Test
    void typedInputRequiresActiveRunAndWithdrawalCannotRacePastApplication() throws Exception {
        var turn = session.submit("one", "one");
        code(DurableException.Code.CONFLICT, () -> turn.steer("early", "too early"));
        var lease = claim();
        var receipt = turn.steer("steer", "focus");
        assertEquals(receipt, turn.steer("steer", "focus"));
        code(
                DurableException.Code.CONFLICT,
                () -> turn.inject("steer", DurableMessages.Input.text("focus")));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var applied =
                    pool.submit(
                            () -> {
                                start.await();
                                try {
                                    checkpoint(
                                            lease,
                                            TurnStatus.RUNNING,
                                            null,
                                            List.of(),
                                            List.of("steer"));
                                    return true;
                                } catch (DurableException e) {
                                    assertEquals(DurableException.Code.CONFLICT, e.code());
                                    return false;
                                }
                            });
            var withdrawn =
                    pool.submit(
                            () -> {
                                start.await();
                                try {
                                    turn.withdraw("withdraw", "steer");
                                    return true;
                                } catch (DurableException e) {
                                    assertEquals(DurableException.Code.CONFLICT, e.code());
                                    return false;
                                }
                            });
            start.countDown();
            assertNotEquals(applied.get(30, TimeUnit.SECONDS), withdrawn.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, session.history(null, 10).items().size());
        code(
                DurableException.Code.INVALID,
                () -> turn.signal("bypass", "interaction:secret", DurableJson.value(true)));
    }

    @Test
    void responsesAreScopedTypedAndCannotForgeAutomaticHandsResults() {
        var turn = session.submit("one", "one");
        var lease = claim();
        var manual =
                DurableJson.value(
                        Map.of(
                                "id",
                                "approval",
                                "requestId",
                                "native",
                                "turnId",
                                turn.turnId(),
                                "key",
                                "interaction:native",
                                "kind",
                                "confirmation",
                                "status",
                                "pending",
                                "automatic",
                                false));
        var automatic =
                DurableJson.value(
                        Map.of(
                                "id",
                                "tool",
                                "requestId",
                                "native-tool",
                                "turnId",
                                turn.turnId(),
                                "key",
                                "interaction:native-tool",
                                "kind",
                                "external_execution",
                                "status",
                                "pending",
                                "automatic",
                                true));
        checkpoint(
                lease,
                TurnStatus.WAITING,
                new WaitCondition("interaction:native", 0),
                List.of(
                        new EventInput("approval", "interaction.requested", manual),
                        new EventInput("tool", "interaction.requested", automatic)),
                List.of());
        assertEquals(2, turn.pendingInteractions().size());
        code(
                DurableException.Code.INVALID,
                () -> turn.respond("bad", "approval", DurableJson.value("yes")));
        code(
                DurableException.Code.CONFLICT,
                () -> turn.respond("forge", "tool", DurableJson.value("done")));
        code(DurableException.Code.CONFLICT, () -> turn.resume("skip", turn.status().revision()));
        turn.pause("pause-approval");
        var accepted = turn.respond("answer", "approval", true);
        assertEquals(accepted, turn.respond("answer", "approval", true));
        code(DurableException.Code.CONFLICT, () -> turn.respond("answer", "approval", false));
        code(DurableException.Code.CONFLICT, () -> turn.respond("second", "approval", true));
        assertEquals(TurnStatus.PAUSED, turn.status().status());
        assertTrue(
                backend.claim(session.mainThread().key(), "paused-answer", Duration.ofSeconds(1))
                        .isEmpty());
        var other = session.submit("two", "two");
        code(DurableException.Code.NOT_FOUND, () -> other.respond("cross-turn", "approval", true));
        assertEquals(
                "respond", session.history(null, 10).items().get(1).path("inputKind").asText());
        assertEquals(
                "approval",
                session.history(null, 10).items().get(1).path("interactionId").asText());
    }

    @Test
    void resumeRequiresObservedPauseAndDoesNotBypassAnUnsatisfiedWait() {
        var turn = session.submit("one", "one");
        var lease = claim();
        checkpoint(
                lease, TurnStatus.WAITING, new WaitCondition("x.ready", 0), List.of(), List.of());
        code(DurableException.Code.CONFLICT, () -> turn.resume("bypass", turn.status().revision()));
        var paused = turn.pause("pause");
        assertEquals(paused, turn.pause("pause"));
        assertEquals(TurnStatus.PAUSED, turn.status().status());
        long revision = turn.status().revision();
        code(DurableException.Code.CONFLICT, () -> turn.resume("stale", 0));
        var accepted = turn.resume("continue", revision);
        assertEquals(accepted, turn.resume("continue", revision));
        assertEquals(TurnStatus.WAITING, turn.status().status());
        assertTrue(
                backend.claim(session.mainThread().key(), "waiting", Duration.ofSeconds(1))
                        .isEmpty());
        turn.pause("pause-again");
        turn.signal("ready", "x.ready", DurableJson.value(true));
        assertEquals(TurnStatus.PAUSED, turn.status().status());
        assertTrue(
                backend.claim(session.mainThread().key(), "still-paused", Duration.ofSeconds(1))
                        .isEmpty());
        try (var replacement = connect(backend)) {
            var original = replacement.getSession(key).getTurn(turn.turnId());
            original.resume("resume-ready", original.status().revision());
        }
        assertTrue(
                backend.claim(session.mainThread().key(), "replacement", Duration.ofSeconds(1))
                        .isPresent());
        var events = session.events(null, 100).events();
        assertEquals(2, events.stream().filter(e -> "turn.paused".equals(e.type())).count());
        events.forEach(event -> DurableProtocol.validate("event", event));
    }

    @Test
    void pausedQueuedTurnKeepsFifoAndCanBeCancelledWithoutStartingAnAgent() {
        var first = session.submit("first", "first");
        var next = session.submit("next", "next");
        first.pause("pause");
        assertEquals(TurnStatus.PAUSED, first.status().status());
        assertTrue(first.listRuns(null, 10).items().isEmpty());
        assertTrue(
                backend.claim(session.mainThread().key(), "no-host", Duration.ofSeconds(1))
                        .isEmpty());
        assertEquals(TurnStatus.QUEUED, next.status().status());
        var other = session.createThread("other", "agent");
        var independent = other.submit("other-task", "other");
        assertEquals(
                independent.turnId(),
                backend.claim(other.key(), "other-host", Duration.ofSeconds(1))
                        .orElseThrow()
                        .turnId());
        long pausedRevision = first.status().revision();
        first.cancel("cancel");
        assertEquals(TurnStatus.CANCELLED, first.status().status());
        code(DurableException.Code.CONFLICT, () -> first.resume("resume", pausedRevision));
        assertEquals(next.turnId(), claim().turnId());
    }

    @Test
    void cancellationRacingResumeCannotReopenThePausedTask() throws Exception {
        var turn = session.submit("task", "input");
        turn.pause("pause");
        long revision = turn.status().revision();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var cancelled =
                    pool.submit(
                            () -> {
                                start.await();
                                return turn.cancel("cancel");
                            });
            var resumed =
                    pool.submit(
                            () -> {
                                start.await();
                                try {
                                    turn.resume("resume", revision);
                                } catch (DurableException error) {
                                    assertEquals(DurableException.Code.CONFLICT, error.code());
                                }
                                return true;
                            });
            start.countDown();
            assertEquals("CANCEL", cancelled.get(5, TimeUnit.SECONDS).operation());
            assertTrue(resumed.get(5, TimeUnit.SECONDS));
            assertEquals(TurnStatus.CANCELLED, turn.status().status());
            code(
                    DurableException.Code.CONFLICT,
                    () -> turn.resume("after-cancel", turn.status().revision()));
            assertTrue(turn.listRuns(null, 10).items().isEmpty());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void activePauseFencesNewCallsAndWaitsForCheckpointHandoff() {
        var turn = session.submit("task", "input");
        var lease = claim();
        var prepared = backend.prepareRun(lease, "first-call");
        turn.pause("pause");
        assertEquals(TurnStatus.PAUSING, turn.status().status());
        assertEquals(prepared, backend.prepareRun(lease, "first-call"));
        code(DurableException.Code.CONFLICT, () -> backend.prepareRun(lease, "new-call"));
        code(
                DurableException.Code.CONFLICT,
                () -> checkpoint(lease, TurnStatus.SUCCEEDED, null, List.of(), List.of()));
        checkpoint(lease, TurnStatus.RUNNING, null, List.of(), List.of());
        assertEquals(TurnStatus.PAUSING, turn.status().status());
        assertFalse(backend.frame(lease).checkpoint().isNull());
        backend.release(lease, Duration.ZERO);
        assertEquals(TurnStatus.PAUSED, turn.status().status());
        assertEquals("paused", turn.getRun(prepared.runId()).reason());
        assertEquals("suspended", turn.getRun(prepared.runId()).outcome());
        code(DurableException.Code.FENCED, () -> backend.frame(lease));
        assertTrue(
                backend.claim(session.mainThread().key(), "replacement", Duration.ofSeconds(1))
                        .isEmpty());
        long revision = turn.status().revision();
        turn.resume("resume", revision);
        var replacement = claim();
        assertNotEquals(lease.epoch(), replacement.epoch());
        assertFalse(backend.frame(replacement).checkpoint().isNull());
    }

    @Test
    void observationTimeoutFilteringAndCloseDoNotCancelTasks() throws Exception {
        var first = session.submit("one", "one");
        var second = session.submit("two", "two");
        var filtered = second.events(null, 1);
        assertTrue(filtered.events().isEmpty());
        assertNotNull(filtered.cursor());
        var next = second.events(filtered.cursor(), 10);
        assertEquals(2, next.events().size());
        var seen = new CountDownLatch(1);
        try (var observation = session.observe(s -> seen.countDown(), e -> {})) {
            assertTrue(seen.await(5, TimeUnit.SECONDS));
            assertEquals(TurnStatus.QUEUED, first.status().status());
        }
        for (int i = 0; i < 2; i++) {
            var replayed = new CountDownLatch(2);
            try (var stream =
                    session.stream(
                            "one",
                            DurableMessages.Input.text("one"),
                            s -> {},
                            e -> {
                                assertEquals(first.turnId(), e.turnId());
                                replayed.countDown();
                            })) {
                assertTrue(replayed.await(5, TimeUnit.SECONDS));
            }
        }
        assertEquals(2, session.listTurns(null, 10).items().size());
        var error =
                assertThrows(
                        ExecutionException.class,
                        () ->
                                first.await(Duration.ofMillis(20))
                                        .toCompletableFuture()
                                        .get(5, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, error.getCause());
        assertTrue(error.getCause().getMessage().contains(first.turnId()));
        var wait = first.await(Duration.ofSeconds(20)).toCompletableFuture();
        api.close();
        assertThrows(ExecutionException.class, () -> wait.get(5, TimeUnit.SECONDS));
        assertEquals(
                TurnStatus.QUEUED,
                backend.turn(new ThreadKey(key, "main"), first.turnId()).status());
    }

    @Test
    void replacementBrainDiscoversAdmittedWorkWithoutUserOrNotifications() throws Exception {
        String turnId = session.submit("one", "one").turnId();
        api.close();
        AtomicInteger invoked = new AtomicInteger();
        HarnessAdapter adapter =
                new HarnessAdapter() {
                    public Capabilities capabilities() {
                        return new Capabilities(
                                "1",
                                RecoveryBoundary.CHECKPOINT,
                                EventDelivery.COMPLETED,
                                false,
                                List.of("text"));
                    }

                    public void execute(HarnessContext context) {
                        invoked.incrementAndGet();
                        var f = context.frame();
                        var message =
                                new DurableMessages.Message(
                                        "1",
                                        "assistant:" + f.turn().turnId(),
                                        "assistant",
                                        List.of(new DurableMessages.Text("done")));
                        context.commit(
                                new Commit(
                                        "finish",
                                        f.turn().revision(),
                                        DurableJson.value(Map.of("done", true)),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(
                                                new EventInput(
                                                        "reply",
                                                        "message.completed",
                                                        DurableJson.value(
                                                                Map.of(
                                                                        "messageId",
                                                                        message.messageId(),
                                                                        "attempt",
                                                                        0,
                                                                        "revision",
                                                                        0,
                                                                        "text",
                                                                        "done",
                                                                        "message",
                                                                        message)))),
                                        TurnStatus.SUCCEEDED,
                                        null,
                                        DurableJson.value(message),
                                        null));
                    }
                };
        try (var brain =
                DurableRuntime.builder()
                        .backend(backend)
                        .harness("agent", "v1", adapter)
                        .pollInterval(Duration.ofMillis(10))
                        .build()) {
            brain.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!backend.turn(new ThreadKey(key, "main"), turnId).status().terminal()
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(
                    TurnStatus.SUCCEEDED,
                    backend.turn(new ThreadKey(key, "main"), turnId).status());
            assertEquals(1, invoked.get());
            try (var replacement = connect(backend)) {
                var original = replacement.getSession(key).getTurn(turnId);
                assertEquals(
                        TurnStatus.SUCCEEDED,
                        original.await(Duration.ofSeconds(1))
                                .toCompletableFuture()
                                .get(5, TimeUnit.SECONDS)
                                .status());
                assertEquals(2, replacement.getSession(key).history(null, 10).items().size());
            }
        }
    }

    @Test
    void publicProtocolRejectsInternalOperationsUnknownFieldsAndUnsupportedRestore() {
        code(
                DurableException.Code.INVALID,
                () ->
                        api.invoke(
                                new DurableProtocol.Request(
                                        DurableProtocol.VERSION,
                                        "claim",
                                        DurableJson.value(
                                                Map.of(
                                                        "session",
                                                        key,
                                                        "owner",
                                                        "attacker",
                                                        "duration",
                                                        1000)))));
        code(
                DurableException.Code.INVALID,
                () ->
                        api.invoke(
                                new DurableProtocol.Request(
                                        DurableProtocol.VERSION,
                                        "getSession",
                                        DurableJson.value(Map.of("session", key, "extra", true)))));
        code(
                DurableException.Code.INCOMPATIBLE,
                () -> session.mainThread().restore(1, 0, "restore"));
    }
}
