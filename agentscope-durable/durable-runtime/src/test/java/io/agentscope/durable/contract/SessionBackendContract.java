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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.durable.DelegationExecutor;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableMessages;
import io.agentscope.durable.DurableProtocol;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.AppliedResult;
import io.agentscope.durable.DurableTypes.Commit;
import io.agentscope.durable.DurableTypes.CommitReceipt;
import io.agentscope.durable.DurableTypes.ControlCommand;
import io.agentscope.durable.DurableTypes.ControlKind;
import io.agentscope.durable.DurableTypes.DelegationTarget;
import io.agentscope.durable.DurableTypes.EventInput;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.Failure;
import io.agentscope.durable.DurableTypes.Frame;
import io.agentscope.durable.DurableTypes.HarnessSelector;
import io.agentscope.durable.DurableTypes.JournalAppend;
import io.agentscope.durable.DurableTypes.Lease;
import io.agentscope.durable.DurableTypes.Lifecycle;
import io.agentscope.durable.DurableTypes.ModelObservation;
import io.agentscope.durable.DurableTypes.ModelStatus;
import io.agentscope.durable.DurableTypes.OutputPage;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.RecoveryPolicy;
import io.agentscope.durable.DurableTypes.Restoration;
import io.agentscope.durable.DurableTypes.RunChange;
import io.agentscope.durable.DurableTypes.RunStatus;
import io.agentscope.durable.DurableTypes.RunStepRef;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.SessionSpec;
import io.agentscope.durable.DurableTypes.Signal;
import io.agentscope.durable.DurableTypes.Snapshot;
import io.agentscope.durable.DurableTypes.StepChange;
import io.agentscope.durable.DurableTypes.StepPhase;
import io.agentscope.durable.DurableTypes.StepStatus;
import io.agentscope.durable.DurableTypes.ThreadBinding;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ThreadSpec;
import io.agentscope.durable.DurableTypes.TokenUsage;
import io.agentscope.durable.DurableTypes.ToolCall;
import io.agentscope.durable.DurableTypes.ToolExecution;
import io.agentscope.durable.DurableTypes.TurnReceipt;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.DurableTypes.WaitCondition;
import io.agentscope.durable.EventProjection;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.Executor;
import io.agentscope.durable.HarnessAdapter;
import io.agentscope.durable.HarnessContext;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.SessionEventClient;
import io.agentscope.durable.SessionResources;
import io.agentscope.durable.SessionResources.Operation;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.durable.Wakeups;
import io.agentscope.durable.store.RecordStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public abstract class SessionBackendContract {
    @TempDir protected Path directory;
    private final SessionKey key = new SessionKey("tenant", "session");
    private final AtomicLong clock = new AtomicLong(1000);
    private RecordStore store;
    private SessionBackend backend;

    protected abstract RecordStore openStore();

    protected SessionBackend wrap(SessionBackend backend) {
        return backend;
    }

    @BeforeEach
    protected void setup() throws Exception {
        store = openStore();
        backend =
                wrap(
                        new TransactionalSessionBackend(
                                new RecordStore() {
                                    public Capabilities capabilities() {
                                        return store.capabilities();
                                    }

                                    public <T> T transaction(
                                            String key, Function<Transaction, T> action) {
                                        return store.transaction(
                                                key,
                                                tx ->
                                                        action.apply(
                                                                new Transaction() {
                                                                    public long now() {
                                                                        return clock.get();
                                                                    }

                                                                    public JsonNode get(
                                                                            String part) {
                                                                        return tx.get(part);
                                                                    }

                                                                    public void put(
                                                                            String part,
                                                                            JsonNode value) {
                                                                        tx.put(part, value);
                                                                    }

                                                                    public List<String> keys(
                                                                            String prefix,
                                                                            String after,
                                                                            int limit) {
                                                                        return tx.keys(
                                                                                prefix, after,
                                                                                limit);
                                                                    }

                                                                    public void schedule(
                                                                            String queue,
                                                                            String workId,
                                                                            long readyAt,
                                                                            JsonNode value) {
                                                                        tx.schedule(
                                                                                queue, workId,
                                                                                readyAt, value);
                                                                    }
                                                                }));
                                    }

                                    public List<Work> ready(String queue, int limit) {
                                        return store.ready(queue, limit);
                                    }

                                    public List<String> keys(
                                            String prefix, String after, int limit) {
                                        return store.keys(prefix, after, limit);
                                    }
                                }));
        backend.create(
                new SessionSpec(
                        key, new ThreadBinding("test", "v1", "1"), DurableJson.value(null)));
    }

    @Test
    void threadQueuesCheckpointsAndLeasesAreIndependentWithinOneSession() {
        var main = new ThreadKey(key, "main");
        var side = new ThreadKey(key, "research");
        backend.createThread(new ThreadSpec(side, new ThreadBinding("test", "v1", "1")));
        var first = backend.submit(main, "first", DurableJson.value("main"));
        var next = backend.submit(main, "next", DurableJson.value("queued"));
        var other = backend.submit(side, "other", DurableJson.value("research"));
        var left = backend.claim(main, "brain", Duration.ofSeconds(10)).orElseThrow();
        var right = backend.claim(side, "brain", Duration.ofSeconds(10)).orElseThrow();
        assertEquals(1, left.epoch());
        assertEquals(1, right.epoch());
        assertEquals(first.turnId(), left.turnId());
        assertEquals(other.turnId(), right.turnId());
        assertTrue(backend.claim(main, "competitor", Duration.ofSeconds(10)).isEmpty());
        backend.commit(
                left,
                new Commit(
                        "main-state",
                        0,
                        DurableJson.value(Map.of("context", "main")),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        TurnStatus.WAITING,
                        new WaitCondition("approval", 0),
                        null,
                        null));
        backend.commit(
                right,
                new Commit(
                        "side-state",
                        0,
                        DurableJson.value(Map.of("context", "research")),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        TurnStatus.SUCCEEDED,
                        null,
                        DurableJson.value("done"),
                        null));
        assertEquals(
                DurableException.Code.FENCED,
                assertThrows(DurableException.class, () -> backend.release(left, Duration.ZERO))
                        .code());
        assertTrue(backend.claim(main, "next-owner", Duration.ofSeconds(10)).isEmpty());
        assertEquals(TurnStatus.QUEUED, backend.turn(main, next.turnId()).status());
        assertEquals("main", backend.checkpoint(main, 1).checkpoint().path("context").asText());
        assertEquals("research", backend.checkpoint(side, 1).checkpoint().path("context").asText());
        assertEquals(2, backend.turns(main, null, 10).items().size());
        assertEquals(1, backend.turns(side, null, 10).items().size());
        assertEquals(3, backend.snapshot(key).turns().size());
        var following = backend.submit(side, "side-next", DurableJson.value("continue"));
        var owned = backend.claim(side, "next-owner", Duration.ofSeconds(10)).orElseThrow();
        assertEquals(following.turnId(), owned.turnId());
        assertEquals(2, owned.epoch());
        assertEquals(
                DurableException.Code.NOT_FOUND,
                assertThrows(DurableException.class, () -> backend.turn(side, first.turnId()))
                        .code());
        assertEquals(
                DurableException.Code.NOT_FOUND,
                assertThrows(
                                DurableException.class,
                                () ->
                                        backend.frame(
                                                new Lease(
                                                        side,
                                                        first.turnId(),
                                                        left.owner(),
                                                        left.epoch(),
                                                        left.expiresAt())))
                        .code());
        var events = backend.events(key, null, 100).events();
        for (int index = 0; index < events.size(); index++)
            assertEquals(Integer.toString(index + 1), events.get(index).sequence());
        assertTrue(events.stream().anyMatch(event -> "main".equals(event.threadId())));
        assertTrue(events.stream().anyMatch(event -> "research".equals(event.threadId())));
    }

    @Test
    void resourceLifecycleCommitsRollBackTogetherAndLostRepliesAreIdempotent() {
        var rollback = new AtomicBoolean();
        var lostReply = new AtomicBoolean();
        var faulty =
                wrap(
                        new TransactionalSessionBackend(
                                new RecordStore() {
                                    public <T> T transaction(
                                            String scope, Function<Transaction, T> action) {
                                        T result =
                                                store.transaction(
                                                        scope,
                                                        tx -> {
                                                            T value = action.apply(tx);
                                                            if (rollback.getAndSet(false))
                                                                throw new DurableException(
                                                                        DurableException.Code
                                                                                .UNAVAILABLE,
                                                                        "before commit");
                                                            return value;
                                                        });
                                        if (lostReply.getAndSet(false))
                                            throw new DurableException(
                                                    DurableException.Code.UNAVAILABLE,
                                                    "reply lost after commit");
                                        return result;
                                    }

                                    public List<Work> ready(String queue, int limit) {
                                        return store.ready(queue, limit);
                                    }

                                    public List<String> keys(
                                            String prefix, String after, int limit) {
                                        return store.keys(prefix, after, limit);
                                    }
                                }));
        var target = new SessionKey("tenant", "lifecycle");
        var specification =
                new SessionSpec(
                        target, new ThreadBinding("test", "v1", "1"), DurableJson.value(null));
        var side = new ThreadKey(target, "side");
        var thread = new ThreadSpec(side, specification.primaryBinding());
        rollback.set(true);
        code(DurableException.Code.UNAVAILABLE, () -> faulty.create(specification));
        code(DurableException.Code.NOT_FOUND, () -> backend.session(target));
        assertTrue(store.transaction(target.storageKey(), tx -> tx.keys("", null, 100)).isEmpty());
        lostReply.set(true);
        code(DurableException.Code.UNAVAILABLE, () -> faulty.create(specification));
        var created = backend.snapshot(target);
        assertEquals("2", created.sequence());
        faulty.create(specification);
        assertEquals(created, backend.snapshot(target));
        rollback.set(true);
        code(DurableException.Code.UNAVAILABLE, () -> faulty.createThread(thread));
        code(DurableException.Code.NOT_FOUND, () -> backend.thread(side));
        assertEquals(created, backend.snapshot(target));
        lostReply.set(true);
        code(DurableException.Code.UNAVAILABLE, () -> faulty.createThread(thread));
        faulty.createThread(thread);
        var withThread = backend.snapshot(target);
        assertEquals("3", withThread.sequence());
        rollback.set(true);
        code(DurableException.Code.UNAVAILABLE, () -> faulty.closeThread(side));
        assertEquals(Lifecycle.OPEN, backend.thread(side).status());
        assertEquals(withThread, backend.snapshot(target));
        lostReply.set(true);
        code(DurableException.Code.UNAVAILABLE, () -> faulty.closeThread(side));
        faulty.closeThread(side);
        var closedThread = backend.snapshot(target);
        assertEquals("4", closedThread.sequence());
        rollback.set(true);
        code(DurableException.Code.UNAVAILABLE, () -> faulty.closeSession(target));
        assertEquals(Lifecycle.OPEN, backend.session(target).status());
        assertEquals(closedThread, backend.snapshot(target));
        lostReply.set(true);
        code(DurableException.Code.UNAVAILABLE, () -> faulty.closeSession(target));
        faulty.closeSession(target);
        assertEquals("5", backend.snapshot(target).sequence());
        var replay = new EventProjection(target);
        backend.events(target, null, 100).events().forEach(replay::accept);
        assertEquals(backend.snapshot(target).projection(), replay.value());
    }

    @Test
    void agentCommitCannotForgeResourceLifecycleEvents() {
        submit();
        var lease = claim();
        var frame = backend.frame(lease);
        var snapshot = backend.snapshot(key);
        for (var event :
                List.of(
                        new EventInput(
                                "fake-session",
                                "session.created",
                                DurableJson.value(backend.session(key))),
                        new EventInput(
                                "fake-thread",
                                "thread.created",
                                DurableJson.value(backend.thread(lease.thread()))))) {
            DurableProtocol.validate("eventInput", event);
            code(
                    DurableException.Code.INVALID,
                    () ->
                            backend.commit(
                                    lease,
                                    commit(
                                            frame,
                                            event.eventId(),
                                            TurnStatus.RUNNING,
                                            List.of(),
                                            List.of(),
                                            List.of(event))));
            assertEquals(snapshot, backend.snapshot(key));
            assertEquals(frame.turn(), backend.frame(lease).turn());
        }
    }

    @Test
    void threadClosureAndAdmissionRaceWithoutPublishingRejectedChanges() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 8; attempt++) {
                var thread = new ThreadKey(key, "race-" + attempt);
                backend.createThread(new ThreadSpec(thread, new ThreadBinding("test", "v1", "1")));
                var start = new CountDownLatch(1);
                var close =
                        workers.submit(
                                () -> {
                                    start.await();
                                    try {
                                        backend.closeThread(thread);
                                        return true;
                                    } catch (DurableException error) {
                                        assertEquals(DurableException.Code.CONFLICT, error.code());
                                        return false;
                                    }
                                });
                var submit =
                        workers.submit(
                                () -> {
                                    start.await();
                                    try {
                                        backend.submit(
                                                thread,
                                                "submit-" + thread.threadId(),
                                                DurableJson.value("input"));
                                        return true;
                                    } catch (DurableException error) {
                                        assertEquals(DurableException.Code.CONFLICT, error.code());
                                        return false;
                                    }
                                });
                start.countDown();
                boolean closed = close.get(5, TimeUnit.SECONDS);
                assertNotEquals(closed, submit.get(5, TimeUnit.SECONDS));
                assertEquals(
                        closed ? Lifecycle.CLOSED : Lifecycle.OPEN,
                        backend.thread(thread).status());
                var events =
                        backend.events(key, null, 100).events().stream()
                                .filter(e -> thread.threadId().equals(e.threadId()))
                                .map(PublicEvent::type)
                                .toList();
                assertEquals(
                        closed
                                ? List.of("thread.created", "thread.closed")
                                : List.of("thread.created", "turn.queued"),
                        events);
            }
            var replay = new EventProjection(key);
            backend.events(key, null, 100).events().forEach(replay::accept);
            assertEquals(backend.snapshot(key).projection(), replay.value());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void sessionRequestIdentityCannotBeReusedAgainstAnotherThread() {
        var main = new ThreadKey(key, "main");
        var side = new ThreadKey(key, "research");
        backend.createThread(new ThreadSpec(side, new ThreadBinding("test", "v1", "1")));
        var input = DurableJson.value("same");
        var receipt = backend.submit(main, "same-request", input);
        assertEquals(receipt, backend.submit(main, "same-request", input));
        assertEquals(
                DurableException.Code.CONFLICT,
                assertThrows(
                                DurableException.class,
                                () -> backend.submit(side, "same-request", input))
                        .code());
        assertTrue(backend.turns(side, null, 10).items().isEmpty());
        assertEquals(main, backend.admission(key, "same-request").orElseThrow().thread());
    }

    @Test
    void lifecycleChecksPreventClosingBusyThreadsAndSubmittingToClosedParents() {
        var main = new ThreadKey(key, "main");
        var side = new ThreadKey(key, "research");
        var specification = new ThreadSpec(side, new ThreadBinding("test", "v1", "1"));
        assertEquals(backend.createThread(specification), backend.createThread(specification));
        var turn = backend.submit(main, "work", DurableJson.value("input"));
        assertEquals(
                DurableException.Code.CONFLICT,
                assertThrows(DurableException.class, () -> backend.closeThread(main)).code());
        assertEquals(
                DurableException.Code.CONFLICT,
                assertThrows(DurableException.class, () -> backend.closeSession(key)).code());
        assertEquals(Lifecycle.CLOSED, backend.closeThread(side).status());
        assertEquals(
                DurableException.Code.CONFLICT,
                assertThrows(
                                DurableException.class,
                                () ->
                                        backend.restore(
                                                new Restoration(
                                                        "closed-thread",
                                                        0,
                                                        specification,
                                                        DurableJson.value(Map.of()),
                                                        List.of())))
                        .code());
        assertEquals(
                DurableException.Code.CONFLICT,
                assertThrows(
                                DurableException.class,
                                () -> backend.submit(side, "closed", DurableJson.value("input")))
                        .code());
        backend.cancel(main, turn.turnId(), "cancel");
        assertEquals(Lifecycle.CLOSED, backend.closeSession(key).status());
        assertEquals(
                DurableException.Code.CONFLICT,
                assertThrows(
                                DurableException.class,
                                () ->
                                        backend.restore(
                                                new Restoration(
                                                        "closed-session",
                                                        0,
                                                        backend.thread(main).definition(),
                                                        DurableJson.value(Map.of()),
                                                        List.of())))
                        .code());
        assertEquals(
                DurableException.Code.CONFLICT,
                assertThrows(
                                DurableException.class,
                                () ->
                                        backend.submit(
                                                main, "closed-parent", DurableJson.value("input")))
                        .code());
        assertEquals(
                DurableException.Code.CONFLICT,
                assertThrows(
                                DurableException.class,
                                () ->
                                        backend.createThread(
                                                new ThreadSpec(
                                                        new ThreadKey(key, "new"),
                                                        specification.binding())))
                        .code());
        assertEquals(2, backend.threads(key, null, 10).items().size());
        assertEquals(TurnStatus.CANCELLED, backend.turn(main, turn.turnId()).status());
    }

    @Test
    void brainCanExecuteTwoThreadsOfOneSessionConcurrently() throws Exception {
        var main = new ThreadKey(key, "main");
        var side = new ThreadKey(key, "research");
        backend.createThread(new ThreadSpec(side, new ThreadBinding("test", "v1", "1")));
        backend.submit(main, "main", DurableJson.value("main"));
        backend.submit(side, "side", DurableJson.value("side"));
        var entered = new CountDownLatch(2);
        var proceed = new CountDownLatch(1);
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

                    public void execute(HarnessContext context) throws Exception {
                        entered.countDown();
                        if (!proceed.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("Test release missing");
                        var frame = context.frame();
                        context.commit(
                                new Commit(
                                        "finished",
                                        frame.turn().revision(),
                                        frame.checkpoint(),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        TurnStatus.SUCCEEDED,
                                        null,
                                        DurableJson.value(frame.definition().key().threadId()),
                                        null));
                    }
                };
        try (var runtime =
                DurableRuntime.builder()
                        .backend(backend)
                        .harness("test", "v1", adapter)
                        .parallelism(2)
                        .pollInterval(Duration.ofMillis(10))
                        .build()) {
            runtime.start();
            try {
                assertTrue(
                        entered.await(5, TimeUnit.SECONDS),
                        "Both Thread invocations must enter concurrently");
            } finally {
                proceed.countDown();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (backend.snapshot(key).turns().stream()
                            .anyMatch(turn -> !turn.status().terminal())
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(
                    backend.snapshot(key).turns().stream()
                            .allMatch(turn -> turn.status() == TurnStatus.SUCCEEDED));
        } finally {
            proceed.countDown();
        }
    }

    private Commit invocationCommit(Lease lease, String id, RunChange run, List<StepChange> steps) {
        var frame = backend.frame(lease);
        return new Commit(
                id,
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
                run,
                steps);
    }

    private Commit withModelEvents(Commit command, List<EventInput> events) {
        var repeated = new ArrayList<>(events);
        repeated.addAll(events); // At-least-once event delivery, including duplicates in one batch.
        return new Commit(
                command.commitId(),
                command.expectedRevision(),
                command.checkpoint(),
                command.tools(),
                command.appliedResults(),
                command.consumedSignals(),
                repeated,
                command.status(),
                command.waiting(),
                command.output(),
                command.error(),
                command.journal(),
                command.run(),
                command.steps());
    }

    private EventInput modelFact(String eventId, String type, String run, Map<String, ?> data) {
        return new EventInput(eventId, type, DurableJson.value(data), run, "model-step");
    }

    private Commit withToolFacts(
            Commit command, List<ToolCall> tools, List<AppliedResult> applied) {
        return new Commit(
                command.commitId(),
                command.expectedRevision(),
                DurableJson.value("checkpoint"),
                tools,
                applied,
                command.consumedSignals(),
                command.events(),
                command.status(),
                command.waiting(),
                command.output(),
                command.error(),
                command.journal(),
                command.run(),
                command.steps());
    }

    @Test
    protected void handsOriginSurvivesTakeoverAndApplicationRecordsItsOwnRun() {
        var task = submit();
        var thread = task.thread();
        var lease = claim();
        var first = backend.prepareRun(lease, "first").runId();
        var origin = new RunStepRef(first, "first-step");
        var intent = tool().withOrigin(origin);
        var command =
                withToolFacts(
                        invocationCommit(
                                lease,
                                "intent",
                                new RunChange(first, RunStatus.RUNNING, null, null),
                                List.of(
                                        new StepChange(
                                                first,
                                                "first-step",
                                                StepStatus.RUNNING,
                                                StepPhase.ACTING,
                                                null,
                                                null),
                                        new StepChange(
                                                first,
                                                "first-step",
                                                StepStatus.STOPPED,
                                                StepPhase.ACTING,
                                                null,
                                                "waiting"),
                                        new StepChange(
                                                first,
                                                "second-step",
                                                StepStatus.RUNNING,
                                                StepPhase.ACTING,
                                                null,
                                                null))),
                        List.of(
                                intent,
                                new ToolCall(
                                                "second-call",
                                                "remote",
                                                "build",
                                                DurableJson.value(Map.of()))
                                        .withOrigin(new RunStepRef(first, "second-step"))),
                        List.of());
        var receipt = backend.commit(lease, command);
        assertEquals(receipt, backend.commit(lease, command));
        var execution =
                backend.frame(lease).tools().stream()
                        .filter(t -> t.request().call().callId().equals(intent.callId()))
                        .findFirst()
                        .orElseThrow();
        String id = execution.request().executionId();
        backend.beginDispatch(lease, id);
        clock.set(lease.expiresAt() + 1);
        var replacement =
                backend.claim(thread, "replacement", Duration.ofSeconds(30)).orElseThrow();
        assertEquals(origin, backend.execution(thread, id).request().call().origin());
        var result =
                new ExecutionResult(
                        ExecutionState.SUCCEEDED, id, DurableJson.value("output"), null);
        backend.acceptResult(thread, id, result);
        backend.acceptResult(thread, id, result);
        assertFalse(backend.execution(thread, id).applied());
        var second = backend.prepareRun(replacement, "second").runId();
        var appliedAt = new RunStepRef(second, "resumed-step");
        var apply =
                withToolFacts(
                        invocationCommit(
                                replacement,
                                "apply",
                                new RunChange(second, RunStatus.RUNNING, null, null),
                                List.of(
                                        new StepChange(
                                                second,
                                                "resumed-step",
                                                StepStatus.RUNNING,
                                                StepPhase.ACTING,
                                                "first-step",
                                                null))),
                        List.of(intent),
                        List.of(new AppliedResult(id, appliedAt)));
        var appliedReceipt = backend.commit(replacement, apply);
        assertEquals(appliedReceipt, backend.commit(replacement, apply));
        var persisted = backend.execution(thread, id);
        assertEquals(origin, persisted.request().call().origin());
        assertEquals(appliedAt, persisted.appliedAt());
        assertTrue(persisted.applied());
        var events = backend.events(key, null, 1000).events();
        for (String type : List.of("tool.requested", "tool.dispatching", "tool.result.accepted")) {
            var event =
                    events.stream()
                            .filter(
                                    e ->
                                            e.type().equals(type)
                                                    && e.data()
                                                            .path("executionId")
                                                            .asText()
                                                            .equals(id))
                            .findFirst()
                            .orElseThrow();
            assertEquals(origin.runId(), event.runId());
            assertEquals(origin.stepId(), event.stepId());
        }
        var applied = events.stream().filter(e -> e.type().equals("tool.result.applied")).toList();
        assertEquals(1, applied.size());
        assertEquals(second, applied.get(0).runId());
        assertEquals("resumed-step", applied.get(0).stepId());
        var projected =
                backend.snapshot(key)
                        .projection()
                        .path("tools")
                        .path(EventProjection.toolId("main", task.turnId(), intent.callId()))
                        .path("data");
        assertEquals(DurableJson.value(origin), projected.path("origin"));
        assertEquals(DurableJson.value(appliedAt), projected.path("appliedAt"));

        var before = backend.frame(replacement);
        code(
                DurableException.Code.INVALID,
                () ->
                        backend.commit(
                                replacement,
                                withToolFacts(
                                        invocationCommit(
                                                replacement, "missing-origin", null, List.of()),
                                        List.of(
                                                new ToolCall(
                                                        "unscoped",
                                                        "remote",
                                                        "build",
                                                        DurableJson.value(Map.of()))),
                                        List.of())));
        code(
                DurableException.Code.NOT_FOUND,
                () ->
                        backend.commit(
                                replacement,
                                withToolFacts(
                                        invocationCommit(
                                                replacement, "wrong-step", null, List.of()),
                                        List.of(
                                                new ToolCall(
                                                                "bad",
                                                                "remote",
                                                                "build",
                                                                DurableJson.value(Map.of()))
                                                        .withOrigin(
                                                                new RunStepRef(
                                                                        second, "first-step"))),
                                        List.of())));
        code(
                DurableException.Code.CONFLICT,
                () ->
                        backend.commit(
                                replacement,
                                withToolFacts(
                                        invocationCommit(
                                                replacement, "changed-origin", null, List.of()),
                                        List.of(intent.withOrigin(appliedAt)),
                                        List.of())));
        code(
                DurableException.Code.CONFLICT,
                () ->
                        backend.commit(
                                replacement,
                                withToolFacts(
                                        invocationCommit(
                                                replacement,
                                                "changed-application",
                                                null,
                                                List.of()),
                                        List.of(),
                                        List.of(new AppliedResult(id, origin)))));
        assertEquals(before, backend.frame(replacement));
    }

    private void startModelCall(Lease lease, String run) {
        backend.commit(
                lease,
                withModelEvents(
                        invocationCommit(
                                lease,
                                "model-start",
                                new RunChange(run, RunStatus.RUNNING, null, null),
                                List.of(
                                        new StepChange(
                                                run,
                                                "model-step",
                                                StepStatus.RUNNING,
                                                StepPhase.REASONING,
                                                null,
                                                null))),
                        List.of(
                                modelFact(
                                        "call-start",
                                        "model.call.started",
                                        run,
                                        Map.of(
                                                "modelCallId",
                                                "call",
                                                "model",
                                                "fixture",
                                                "purpose",
                                                "REASONING",
                                                "iteration",
                                                0,
                                                "startedAt",
                                                1000)),
                                modelFact(
                                        "request-start",
                                        "model.request.started",
                                        run,
                                        Map.of(
                                                "modelCallId",
                                                "call",
                                                "requestId",
                                                "request-1",
                                                "attempt",
                                                1,
                                                "provider",
                                                "fixture",
                                                "startedAt",
                                                1000)))));
    }

    @Test
    protected void modelRequestsAreAtomicScopedAndDeduplicatedAcrossReceiptRetries() {
        var task = submit();
        var lease = claim();
        var run = backend.prepareRun(lease, "invoke").runId();
        startModelCall(lease, run);
        var usage = new TokenUsage(10L, 3L, Map.of("cachedTokens", 4L));
        var ended =
                DurableJson.value(
                                Map.of(
                                        "modelCallId",
                                        "call",
                                        "requestId",
                                        "request-1",
                                        "status",
                                        "COMPLETED",
                                        "endedAt",
                                        1010,
                                        "durationMillis",
                                        10,
                                        "usage",
                                        usage,
                                        "usageComplete",
                                        true))
                        .deepCopy();
        ((ObjectNode) ended).putNull("errorType");
        var finish =
                withModelEvents(
                        invocationCommit(
                                lease,
                                "model-end",
                                new RunChange(run, RunStatus.COMPLETED, "completed", null),
                                List.of(
                                        new StepChange(
                                                run,
                                                "model-step",
                                                StepStatus.COMPLETED,
                                                StepPhase.REASONING,
                                                null,
                                                null))),
                        List.of(
                                new EventInput(
                                        "request-end",
                                        "model.request.ended",
                                        ended,
                                        run,
                                        "model-step"),
                                modelFact(
                                        "call-end",
                                        "model.call.ended",
                                        run,
                                        Map.of(
                                                "modelCallId",
                                                "call",
                                                "status",
                                                "COMPLETED",
                                                "endedAt",
                                                1010,
                                                "usage",
                                                usage))));
        var receipt = backend.commit(lease, finish);
        assertEquals(receipt, backend.commit(lease, finish));
        var call = backend.modelCall(lease.thread(), task.turnId(), run, "call");
        assertEquals(ModelObservation.PROVIDER_ATTEMPTS, call.observation());
        assertEquals(ModelStatus.COMPLETED, call.status());
        assertEquals(usage, call.usage());
        assertTrue(call.usageComplete());
        assertEquals(1, call.requests().size());
        var page = backend.modelCalls(lease.thread(), task.turnId(), run, "model-step", null, 1);
        assertEquals(List.of(call), page.items());
        assertTrue(
                backend.modelCalls(
                                lease.thread(), task.turnId(), run, "model-step", page.cursor(), 1)
                        .items()
                        .isEmpty());
        assertThrows(
                DurableException.class,
                () ->
                        backend.modelCalls(
                                lease.thread(), task.turnId(), run, null, page.cursor(), 1));
        assertThrows(
                DurableException.class,
                () -> backend.modelCall(lease.thread(), "wrong-turn", run, "call"));
        var snapshot = backend.snapshot(key);
        code(
                DurableException.Code.CONFLICT,
                () ->
                        backend.commit(
                                lease,
                                withModelEvents(
                                        invocationCommit(
                                                lease, "late-model-facts", null, List.of()),
                                        List.of(
                                                modelFact(
                                                        "late-call",
                                                        "model.call.started",
                                                        run,
                                                        Map.of(
                                                                "modelCallId",
                                                                "new",
                                                                "model",
                                                                "fixture",
                                                                "purpose",
                                                                "REASONING",
                                                                "iteration",
                                                                0,
                                                                "startedAt",
                                                                1011))))));
        assertEquals(snapshot, backend.snapshot(key));
    }

    @Test
    protected void lostModelRequestsRetainKnownPartialUsageWithoutInventedCompletion() {
        var task = submit();
        var lease = claim();
        var run = backend.prepareRun(lease, "invoke").runId();
        startModelCall(lease, run);
        var usage = new TokenUsage(7L, null, Map.of());
        backend.commit(
                lease,
                withModelEvents(
                        invocationCommit(lease, "partial-usage", null, List.of()),
                        List.of(
                                modelFact(
                                        "usage",
                                        "model.request.usage",
                                        run,
                                        Map.of(
                                                "modelCallId",
                                                "call",
                                                "requestId",
                                                "request-1",
                                                "usage",
                                                usage)))));
        clock.addAndGet(2000);
        claim();
        var call = backend.modelCall(lease.thread(), task.turnId(), run, "call");
        assertEquals(ModelStatus.LOST, call.status());
        assertEquals(usage, call.usage());
        assertFalse(call.usageComplete());
        assertNull(call.endedAt());
        assertEquals(ModelStatus.LOST, call.requests().get(0).status());
        assertNull(call.requests().get(0).durationMillis());
        assertNull(call.usage().outputTokens());
    }

    @Test
    protected void invocationPreparationIsIdempotentAndDoesNotCreateAnotherTask() {
        var task = submit();
        var lease = claim();
        assertTrue(
                backend.runs(new ThreadKey(key, "main"), task.turnId(), null, 10)
                        .items()
                        .isEmpty());
        var first = backend.prepareRun(lease, "invoke-one");
        assertEquals(first, backend.prepareRun(lease, "invoke-one"));
        assertEquals(RunStatus.PREPARED, first.status());
        assertEquals(null, first.startedAt());
        assertEquals(task.turnId(), first.turnId());
        code(
                DurableException.Code.CONFLICT,
                () -> backend.prepareRun(lease, "different-invocation"));
        assertEquals(1, backend.snapshot(key).turns().size());
        assertEquals(
                1,
                backend.runs(new ThreadKey(key, "main"), task.turnId(), null, 10).items().size());
    }

    @Test
    protected void nativeInvocationCompletesWithoutAutomaticallyCompletingItsTurn() {
        var task = submit();
        var lease = claim();
        var run = backend.prepareRun(lease, "invoke");
        var start =
                invocationCommit(
                        lease,
                        "start",
                        new RunChange(run.runId(), RunStatus.RUNNING, null, null),
                        List.of(
                                new StepChange(
                                        run.runId(),
                                        "step-one",
                                        StepStatus.RUNNING,
                                        StepPhase.REASONING,
                                        null,
                                        null)));
        var receipt = backend.commit(lease, start);
        assertEquals(receipt, backend.commit(lease, start));
        clock.incrementAndGet();
        backend.commit(
                lease,
                invocationCommit(
                        lease,
                        "end",
                        new RunChange(run.runId(), RunStatus.COMPLETED, "task_completed", null),
                        List.of(
                                new StepChange(
                                        run.runId(),
                                        "step-one",
                                        StepStatus.COMPLETED,
                                        StepPhase.REASONING,
                                        null,
                                        null))));
        var ended = backend.run(new ThreadKey(key, "main"), task.turnId(), run.runId());
        assertEquals(RunStatus.COMPLETED, ended.status());
        assertEquals(1000L, ended.startedAt());
        assertEquals(1001L, ended.endedAt());
        assertEquals(
                TurnStatus.RUNNING,
                backend.turn(new ThreadKey(key, "main"), task.turnId()).status());
        assertEquals(
                StepStatus.COMPLETED,
                backend.step(new ThreadKey(key, "main"), task.turnId(), run.runId(), "step-one")
                        .status());
        var next = backend.prepareRun(lease, "second-call");
        assertNotEquals(run.runId(), next.runId());
        assertEquals(run.runId(), next.previousRunId());
        assertEquals(
                2,
                backend.runs(new ThreadKey(key, "main"), task.turnId(), null, 10).items().size());
    }

    @Test
    protected void takeoverPreservesTheTurnAndCreatesNewRunAndContinuationStepIdentities() {
        var task = submit();
        var firstOwner = claim();
        var run = backend.prepareRun(firstOwner, "invoke");
        backend.commit(
                firstOwner,
                invocationCommit(
                        firstOwner,
                        "start",
                        new RunChange(run.runId(), RunStatus.RUNNING, null, null),
                        List.of(
                                new StepChange(
                                        run.runId(),
                                        "original-step",
                                        StepStatus.RUNNING,
                                        StepPhase.ACTING,
                                        null,
                                        null))));
        clock.addAndGet(2000);
        var replacement = claim();
        assertEquals(task.turnId(), replacement.turnId());
        assertEquals(
                RunStatus.LOST,
                backend.run(new ThreadKey(key, "main"), task.turnId(), run.runId()).status());
        assertEquals(
                StepStatus.LOST,
                backend.step(
                                new ThreadKey(key, "main"),
                                task.turnId(),
                                run.runId(),
                                "original-step")
                        .status());
        code(DurableException.Code.FENCED, () -> backend.prepareRun(firstOwner, "invoke"));
        var resumed = backend.prepareRun(replacement, "resumed-invoke");
        assertNotEquals(run.runId(), resumed.runId());
        backend.commit(
                replacement,
                invocationCommit(
                        replacement,
                        "resume",
                        new RunChange(resumed.runId(), RunStatus.RUNNING, null, null),
                        List.of(
                                new StepChange(
                                        resumed.runId(),
                                        "new-step",
                                        StepStatus.RUNNING,
                                        StepPhase.ACTING,
                                        "original-step",
                                        null))));
        var step =
                backend.step(
                        new ThreadKey(key, "main"), task.turnId(), resumed.runId(), "new-step");
        assertEquals("original-step", step.resumedFromStepId());
        assertEquals(1, step.index());
        assertEquals(StepPhase.ACTING, step.phase());
        code(
                DurableException.Code.NOT_FOUND,
                () ->
                        backend.step(
                                new ThreadKey(key, "main"),
                                task.turnId(),
                                run.runId(),
                                "new-step"));
    }

    @Test
    protected void invalidInvocationFactsRollBackCheckpointAndPublicEventsTogether() {
        var task = submit();
        var lease = claim();
        var run = backend.prepareRun(lease, "invoke");
        var snapshot = backend.snapshot(key);
        var bad =
                invocationCommit(
                        lease,
                        "invalid-step",
                        new RunChange(run.runId(), RunStatus.RUNNING, null, null),
                        List.of(
                                new StepChange(
                                        run.runId(),
                                        "step",
                                        StepStatus.COMPLETED,
                                        StepPhase.REASONING,
                                        null,
                                        null)));
        code(DurableException.Code.CONFLICT, () -> backend.commit(lease, bad));
        assertEquals(
                RunStatus.PREPARED,
                backend.run(new ThreadKey(key, "main"), task.turnId(), run.runId()).status());
        assertEquals(snapshot.cursor(), backend.snapshot(key).cursor());
        assertTrue(
                backend.steps(new ThreadKey(key, "main"), task.turnId(), run.runId(), null, 10)
                        .items()
                        .isEmpty());
        assertTrue(
                backend.receipt(new ThreadKey(key, "main"), task.turnId(), "invalid-step")
                        .isEmpty());
    }

    @Test
    protected void invocationQueriesAreScopedAndReleaseDoesNotPretendCompletion() {
        var task = submit();
        var other =
                backend.submit(
                        new ThreadKey(key, "main"), "other-task", DurableJson.value("queued"));
        var lease = claim();
        var run = backend.prepareRun(lease, "invoke");
        code(
                DurableException.Code.NOT_FOUND,
                () -> backend.run(new ThreadKey(key, "main"), other.turnId(), run.runId()));
        var page = backend.runs(new ThreadKey(key, "main"), task.turnId(), null, 1);
        assertEquals(List.of(run), page.items());
        code(
                DurableException.Code.INVALID,
                () -> backend.runs(new ThreadKey(key, "main"), other.turnId(), page.cursor(), 1));
        backend.release(lease, Duration.ZERO);
        var ended = backend.run(new ThreadKey(key, "main"), task.turnId(), run.runId());
        assertEquals(RunStatus.STOPPED, ended.status());
        assertEquals(null, ended.startedAt());
        assertEquals("host_released", ended.reason());
        assertEquals(
                TurnStatus.RUNNING,
                backend.turn(new ThreadKey(key, "main"), task.turnId()).status());
    }

    @Test
    protected void readinessSelectsExactFrozenVersionsBeforeApplyingTheLimit() {
        for (int i = 0; i < 12; i++) {
            var other = new SessionKey(key.namespace(), "old-" + i);
            backend.create(
                    new SessionSpec(
                            other,
                            new ThreadBinding("test", "old-definition", "old-state"),
                            DurableJson.value(null)));
            backend.submit(new ThreadKey(other, "main"), "input", DurableJson.value("older work"));
        }
        submit();
        var selected = new HarnessSelector("test", "v1", "1");
        assertEquals(
                List.of(new ThreadKey(key, "main")),
                backend.ready(List.of(selected), 1).stream().map(ThreadSpec::key).toList());
        assertTrue(
                backend.ready(List.of(new HarnessSelector("test", "v1", "wrong-state")), 1)
                        .isEmpty());
        var all = backend.ready(List.of(HarnessSelector.all("test"), selected), 100);
        assertEquals(13, all.size());
        assertEquals(13, all.stream().distinct().count());
    }

    @Test
    protected void newThreadsCannotJumpAnOlderReadyTaskInAnotherSession() {
        var sessions =
                new ArrayList<>(
                        List.of(new SessionKey("fair", "one"), new SessionKey("fair", "two")));
        sessions.sort((left, right) -> left.storageKey().compareTo(right.storageKey()));
        var busy = sessions.get(0);
        var other = sessions.get(1);
        var binding = new ThreadBinding("fair", "v1", "1");
        for (var session : sessions)
            backend.create(new SessionSpec(session, binding, DurableJson.value(null)));
        var first = new ThreadKey(busy, "main");
        var waiting = new ThreadKey(other, "main");
        backend.submit(first, "first", DurableJson.value("oldest"));
        clock.incrementAndGet();
        backend.submit(waiting, "waiting", DurableJson.value("older than new Threads"));
        clock.incrementAndGet();
        for (int i = 0; i < 12; i++) {
            var thread = new ThreadKey(busy, "busy-" + i);
            backend.createThread(new ThreadSpec(thread, binding));
            backend.submit(thread, "new-" + i, DurableJson.value("new"));
        }
        assertEquals(
                List.of(first, waiting),
                backend.ready(List.of(HarnessSelector.all("fair")), 2).stream()
                        .map(ThreadSpec::key)
                        .toList());
    }

    @Test
    protected void checkpointMetadataBindsNativeStateAndDoesNotAdvanceForPublicOnlyCommits() {
        submit();
        var lease = claim();
        var first = backend.frame(lease);
        assertTrue(first.checkpoint().isNull());
        assertEquals(null, first.checkpointMetadata());
        backend.commit(
                lease, commit(first, "cp1", TurnStatus.RUNNING, List.of(), List.of(), List.of()));
        var frame = backend.frame(lease);
        frame.checkpointMetadata().verify(frame.definition(), frame.checkpoint());
        assertEquals("1", frame.checkpointMetadata().position());
        backend.commit(
                lease, commit(frame, "cp2", TurnStatus.RUNNING, List.of(), List.of(), List.of()));
        assertEquals(frame.checkpointMetadata(), backend.frame(lease).checkpointMetadata());
        assertEquals(2, backend.frame(lease).turn().revision());
        code(
                DurableException.Code.INVALID,
                () ->
                        backend.commit(
                                lease,
                                new Commit(
                                        "discard",
                                        2,
                                        null,
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        TurnStatus.RUNNING,
                                        null,
                                        null,
                                        null)));
        assertEquals(frame.checkpointMetadata(), backend.frame(lease).checkpointMetadata());
    }

    @Test
    protected void corruptedCheckpointFailsBeforeNativeExecution() {
        submit();
        var lease = claim();
        backend.commit(
                lease,
                commit(
                        backend.frame(lease),
                        "cp",
                        TurnStatus.RUNNING,
                        List.of(),
                        List.of(),
                        List.of()));
        store.transaction(
                key.storageKey(),
                tx -> {
                    tx.put(
                            "thread-data:" + DurableJson.hash("main") + ":checkpoint",
                            DurableJson.value("corrupted"));
                    return null;
                });
        code(
                DurableException.Code.INVALID,
                () -> new HarnessContext(backend, lease, new AtomicBoolean()).frame());
        assertFalse(backend.snapshot(key).projection().toString().contains("corrupted"));
    }

    @Test
    protected void backendCapabilitiesAndEveryPublicEventConformToTheProtocol() {
        var capabilities = backend.capabilities();
        DurableProtocol.validate("backendCapabilities", capabilities);
        assertTrue(capabilities.durable());
        assertTrue(capabilities.atomicCheckpointAndIntent());
        assertTrue(capabilities.fencedOwnership());
        assertEquals(
                System.getProperty("durable.test.jdbcUrl") != null,
                capabilities.sharedAcrossHosts());
        submit();
        var lease = claim();
        backend.commit(
                lease,
                commit(
                        backend.frame(lease),
                        "done",
                        TurnStatus.SUCCEEDED,
                        List.of(),
                        List.of(),
                        List.of()));
        backend.events(key, null, 100)
                .events()
                .forEach(event -> DurableProtocol.validate("event", event));
        DurableProtocol.validate("snapshot", backend.snapshot(key));
    }

    private Lease claim() {
        return backend.claim(new ThreadKey(key, "main"), "owner", Duration.ofSeconds(1))
                .orElseThrow();
    }

    @Test
    protected void changedProgressIsReplayableEvenWhenAnEarlierStateReturns() {
        submit();
        var lease = claim();
        backend.commit(
                lease,
                commit(
                        backend.frame(lease),
                        "intent",
                        TurnStatus.RUNNING,
                        List.of(new ToolCall("call", "hands", "job", DurableJson.value(Map.of()))),
                        List.of(),
                        List.of()));
        var id = backend.frame(lease).tools().get(0).request().executionId();
        backend.beginDispatch(lease, id);
        var running = new ExecutionResult(ExecutionState.RUNNING, "original", null, null);
        backend.acceptResult(new ThreadKey(key, "main"), id, running);
        backend.acceptResult(new ThreadKey(key, "main"), id, running);
        backend.acceptResult(
                new ThreadKey(key, "main"), id, ExecutionResult.unknown("temporarily unreachable"));
        backend.acceptResult(new ThreadKey(key, "main"), id, running);
        var events = backend.events(key, null, 100).events();
        assertEquals(3, events.stream().filter(e -> e.type().equals("tool.progress")).count());
        var projection = new EventProjection(key);
        events.forEach(projection::accept);
        assertEquals(backend.snapshot(key).projection(), projection.value());
        assertEquals(
                "RUNNING",
                projection
                        .value()
                        .path("tools")
                        .path(EventProjection.toolId("main", lease.turnId(), "call"))
                        .path("data")
                        .path("result")
                        .path("state")
                        .asText());
    }

    @Test
    protected void artifactReferencesSurviveReplayRetentionAndResynchronization() {
        submit();
        var lease = claim();
        var artifact =
                new DurableMessages.Artifact(
                        "main",
                        lease.turnId(),
                        null,
                        null,
                        "report",
                        "https://example.org/report",
                        "text/plain",
                        "report.txt",
                        42L,
                        null);
        backend.commit(
                lease,
                commit(
                        backend.frame(lease),
                        "artifact",
                        TurnStatus.SUCCEEDED,
                        List.of(),
                        List.of(),
                        List.of(
                                new EventInput(
                                        "artifact",
                                        "artifact.published",
                                        DurableJson.value(artifact)))));
        var snapshot = backend.snapshot(key);
        backend.pruneEvents(key, snapshot.cursor());
        var projection = new EventProjection(key);
        projection.reset(backend.snapshot(key));
        assertEquals(
                artifact,
                DurableJson.convert(
                        projection.value().path("artifacts").get("report"),
                        DurableMessages.Artifact.class));
        assertTrue(backend.events(key, snapshot.cursor(), 100).events().isEmpty());
    }

    private TurnReceipt submit() {
        return backend.submit(new ThreadKey(key, "main"), "request", DurableJson.value("hello"));
    }

    @Test
    protected void notificationsFollowCommittedFactsAcrossAllWriteEntrypoints() throws Exception {
        var hints = Wakeups.local();
        backend = backend.withWakeups(hints);
        var visible = new ArrayList<Snapshot>();
        // Intentionally read here to verify the notification is outside the transaction.
        // Production callbacks only schedule work; they must not block the publisher.
        try (var subscription =
                hints.subscribe(session -> visible.add(backend.snapshot(session)))) {
            backend.create(
                    new SessionSpec(
                            key, new ThreadBinding("test", "v1", "1"), DurableJson.value(null)));
            var turn = submit();
            assertEquals(turn.turnId(), visible.get(1).turns().get(0).turnId());
            var lease = claim();
            backend.renew(lease, Duration.ofSeconds(1));
            assertEquals(3, visible.size());
            var receipt =
                    backend.commit(
                            lease,
                            commit(
                                    backend.frame(lease),
                                    "intent",
                                    TurnStatus.RUNNING,
                                    List.of(tool()),
                                    List.of(),
                                    List.of()));
            assertEquals(receipt.cursor(), visible.get(3).cursor());
            var execution = backend.frame(lease).tools().get(0).request().executionId();
            backend.beginDispatch(lease, execution);
            backend.acceptResult(
                    new ThreadKey(key, "main"),
                    execution,
                    new ExecutionResult(
                            ExecutionState.SUCCEEDED,
                            "operation",
                            DurableJson.value("result"),
                            null));
            backend.signal(
                    new ThreadKey(key, "main"),
                    turn.turnId(),
                    new Signal("signal", "resume", DurableJson.value(true)));
            backend.release(lease, Duration.ZERO);
            backend.cancel(new ThreadKey(key, "main"), turn.turnId(), "cancel");
            assertTrue(visible.get(8).turns().get(0).cancellationRequested());
            backend.pruneEvents(key, backend.snapshot(key).cursor());
            assertEquals(10, visible.size());
            backend.admission(key, "request");
            backend.sessions(null, 100);
            backend.turn(new ThreadKey(key, "main"), turn.turnId());
            backend.events(key, backend.snapshot(key).cursor(), 100);
            backend.receipt(new ThreadKey(key, "main"), turn.turnId(), "intent");
            code(
                    DurableException.Code.CONFLICT,
                    () ->
                            backend.submit(
                                    new ThreadKey(key, "main"),
                                    "request",
                                    DurableJson.value("changed")));
            assertEquals(10, visible.size(), "Reads and rejected transactions must not notify");
        }
    }

    @Test
    protected void notificationAndReportingFailureDoNotChangeAnAcknowledgedAdmission()
            throws Exception {
        var hints = Wakeups.local();
        var reporting = new AtomicInteger();
        backend =
                backend.withWakeups(
                        hints,
                        (session, error) -> {
                            reporting.incrementAndGet();
                            throw new IllegalStateException("Error reporter unavailable");
                        });
        try (var subscription =
                hints.subscribe(
                        session -> {
                            throw new IllegalStateException("Notification unavailable");
                        })) {
            var receipt = submit();
            assertEquals(receipt, backend.admission(key, "request").orElseThrow());
            assertEquals(receipt, submit());
            assertEquals(2, reporting.get());
            assertEquals(1, backend.snapshot(key).turns().size());
        }
    }

    @Test
    protected void callbackFailureRetriesOnlyTheUnacknowledgedEventWithoutSnapshotFallback() {
        String start = backend.snapshot(key).cursor();
        submit();
        backend.submit(new ThreadKey(key, "main"), "second", DurableJson.value("next"));
        var page = backend.events(key, start, 100);
        var calls = new ArrayList<String>();
        var snapshots = new AtomicInteger();
        try (var reader =
                new SessionEventClient(
                        backend,
                        key,
                        start,
                        snapshot -> snapshots.incrementAndGet(),
                        event -> {
                            calls.add(event.eventId());
                            if (calls.size() == 2)
                                throw new DurableException(
                                        DurableException.Code.CURSOR_EXPIRED,
                                        "Business callback failed");
                        })) {
            code(DurableException.Code.CURSOR_EXPIRED, reader::poll);
            assertEquals(page.events().get(0).cursor(), reader.cursor());
            assertEquals(1, reader.poll());
            assertEquals(
                    List.of(
                            page.events().get(0).eventId(),
                            page.events().get(1).eventId(),
                            page.events().get(1).eventId()),
                    calls);
            assertEquals(0, snapshots.get());
            assertEquals(page.cursor(), reader.cursor());
        }
    }

    @Test
    protected void failedSnapshotCallbackDoesNotAcknowledgeResynchronization() {
        submit();
        String old = backend.snapshot(key).cursor();
        backend.submit(new ThreadKey(key, "main"), "second", DurableJson.value("next"));
        var snapshot = backend.snapshot(key);
        backend.pruneEvents(key, snapshot.cursor());
        var calls = new AtomicInteger();
        try (var reader =
                new SessionEventClient(
                        backend,
                        key,
                        old,
                        value -> {
                            if (calls.incrementAndGet() == 1)
                                throw new IllegalStateException("Projection unavailable");
                            assertEquals(snapshot, value);
                        },
                        event -> fail("No event should precede snapshot resynchronization"))) {
            assertThrows(IllegalStateException.class, reader::poll);
            assertEquals(old, reader.cursor());
            assertEquals(0, reader.poll());
            assertEquals(snapshot.cursor(), reader.cursor());
            assertEquals(2, calls.get());
        }
    }

    @Test
    protected void acceptedInputMustBeConsumedOrWithdrawnBeforeSuccess() {
        var turn = submit();
        var lease = claim();
        var thread = new ThreadKey(key, "main");
        backend.signal(
                thread,
                turn.turnId(),
                new Signal("late", "input:steer", DurableJson.value("also check the result")));
        var frame = backend.frame(lease);
        code(
                DurableException.Code.CONFLICT,
                () ->
                        backend.commit(
                                lease,
                                commit(
                                        frame,
                                        "premature",
                                        TurnStatus.SUCCEEDED,
                                        List.of(),
                                        List.of(),
                                        List.of())));
        assertEquals(frame.turn().revision(), backend.frame(lease).turn().revision());
        assertEquals(1, backend.frame(lease).signals().size());
        backend.commit(
                lease,
                new Commit(
                        "applied-and-done",
                        frame.turn().revision(),
                        DurableJson.value(Map.of("input", "applied")),
                        List.of(),
                        List.of(),
                        List.of("late"),
                        List.of(),
                        TurnStatus.SUCCEEDED,
                        null,
                        DurableJson.value("done"),
                        null));
        assertEquals(TurnStatus.SUCCEEDED, backend.turn(thread, turn.turnId()).status());

        var next = backend.submit(thread, "next", DurableJson.value("new task"));
        var nextLease = claim();
        backend.signal(
                thread,
                next.turnId(),
                new Signal("withdrawn", "input:inject", DurableJson.value("ignore")));
        backend.withdraw(thread, next.turnId(), "withdrawn", "withdraw");
        backend.commit(
                nextLease,
                commit(
                        backend.frame(nextLease),
                        "done",
                        TurnStatus.SUCCEEDED,
                        List.of(),
                        List.of(),
                        List.of()));
        assertEquals(TurnStatus.SUCCEEDED, backend.turn(thread, next.turnId()).status());
    }

    @Test
    protected void claimRetryRecoversSameLeaseAndReadyIndexTracksWaits() {
        submit();
        assertEquals(
                List.of(new ThreadKey(key, "main")),
                backend.ready(List.of(HarnessSelector.all("test")), 10).stream()
                        .map(ThreadSpec::key)
                        .toList());
        assertTrue(backend.ready(List.of(HarnessSelector.all("unserved")), 10).isEmpty());
        var lease = claim();
        assertEquals(
                lease,
                backend.claim(new ThreadKey(key, "main"), lease.owner(), Duration.ofSeconds(30))
                        .orElseThrow());
        assertTrue(
                backend.claim(new ThreadKey(key, "main"), "competitor", Duration.ofSeconds(30))
                        .isEmpty());
        var frame = backend.frame(lease);
        backend.commit(
                lease,
                new Commit(
                        "wait-index",
                        frame.turn().revision(),
                        frame.checkpoint(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        TurnStatus.WAITING,
                        new WaitCondition("approval", 0),
                        null,
                        null));
        assertTrue(backend.ready(List.of(HarnessSelector.all("test")), 10).isEmpty());
        backend.signal(
                new ThreadKey(key, "main"),
                lease.turnId(),
                new Signal("a", "approval", DurableJson.value(true)));
        assertEquals(1, backend.ready(List.of(HarnessSelector.all("test")), 10).size());
    }

    @Test
    protected void incrementalJournalSharesReceiptRollbackAndFencing() {
        submit();
        var lease = claim();
        var frame = backend.frame(lease);
        var entry = DurableJson.value(Map.of("native", "entry"));
        var command =
                new Commit(
                        "journal-1",
                        frame.turn().revision(),
                        DurableJson.value("head"),
                        List.of(tool()),
                        List.of(),
                        List.of(),
                        List.of(),
                        TurnStatus.RUNNING,
                        null,
                        null,
                        null,
                        new JournalAppend(0, List.of(entry)));
        var receipt = backend.commit(lease, command);
        assertEquals(receipt, backend.commit(lease, command));
        assertEquals(List.of(entry), backend.journal(lease, 0, 10));
        var invalid =
                new Commit(
                        "journal-invalid",
                        receipt.revision(),
                        DurableJson.value("bad-head"),
                        List.of(),
                        List.of(new AppliedResult("missing-result", null)),
                        List.of(),
                        List.of(),
                        TurnStatus.RUNNING,
                        null,
                        null,
                        null,
                        new JournalAppend(1, List.of(DurableJson.value("must rollback"))));
        assertThrows(DurableException.class, () -> backend.commit(lease, invalid));
        assertEquals(List.of(entry), backend.journal(lease, 0, 10));
        assertEquals("head", backend.frame(lease).checkpoint().asText());
        clock.addAndGet(60_000);
        assertTrue(
                backend.claim(new ThreadKey(key, "main"), "replacement", Duration.ofSeconds(30))
                        .isPresent());
        code(DurableException.Code.FENCED, () -> backend.journal(lease, 0, 10));
    }

    @Test
    protected void historicalRunsAndEventsDoNotAccumulateInSessionOrRunHeaders() {
        for (int i = 0; i < 40; i++) {
            backend.submit(new ThreadKey(key, "main"), "history-" + i, DurableJson.value("input"));
            var lease = claim();
            var frame = backend.frame(lease);
            backend.commit(
                    lease,
                    commit(
                            frame,
                            "finish-" + i,
                            TurnStatus.SUCCEEDED,
                            List.of(),
                            List.of(),
                            List.of()));
        }
        store.transaction(
                key.storageKey(),
                tx -> {
                    assertFalse(tx.get("").has("turns"));
                    assertFalse(tx.get("").has("events"));
                    assertFalse(tx.get("").has("checkpoint"));
                    assertTrue(tx.get("").toString().length() < 2048);
                    var records = tx.keys("turn:", null, 100);
                    assertEquals(40, records.size());
                    for (var record : records) {
                        assertFalse(tx.get(record).path("value").has("receipts"));
                        assertFalse(tx.get(record).path("value").has("tools"));
                    }
                    return null;
                });
        assertEquals(40, backend.snapshot(key).turns().size());
        assertTrue(backend.ready(List.of(HarnessSelector.all("test")), 10).isEmpty());
    }

    @Test
    protected void largeCheckpointAndJournalUseScopedImmutableContent() {
        submit();
        var lease = claim();
        var big = DurableJson.value("state-".repeat(5000));
        var frame = backend.frame(lease);
        backend.commit(
                lease,
                new Commit(
                        "large",
                        frame.turn().revision(),
                        big,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        TurnStatus.RUNNING,
                        null,
                        null,
                        null,
                        new JournalAppend(0, List.of(big))));
        assertEquals(big, backend.frame(lease).checkpoint());
        assertEquals(List.of(big), backend.journal(lease, 0, 5));
        store.transaction(
                key.storageKey(),
                tx -> {
                    assertTrue(
                            tx.get("thread-data:" + DurableJson.hash("main") + ":checkpoint")
                                    .hasNonNull("reference"));
                    assertTrue(
                            tx.get("thread-data:" + DurableJson.hash("main") + ":checkpoint")
                                            .toString()
                                            .length()
                                    < 1024);
                    assertTrue(
                            tx.get(
                                            "thread-data:"
                                                    + DurableJson.hash("main")
                                                    + ":journal:initial:00000000000000000001")
                                    .hasNonNull("reference"));
                    return null;
                });
        byte[] bytes = "private artifact".getBytes(StandardCharsets.UTF_8);
        var ref = backend.putObject(key, bytes);
        assertEquals(ref, backend.putObject(key, bytes));
        assertArrayEquals(bytes, backend.readObject(key, ref));
        code(
                DurableException.Code.INVALID,
                () -> backend.readObject(new SessionKey("other", "session"), ref));
    }

    @Test
    protected void withdrawalAndNativeApplicationHaveOneWinner() {
        var turn = submit();
        var signal = new Signal("steer", "input:steer", DurableJson.value("direction"));
        backend.signal(new ThreadKey(key, "main"), turn.turnId(), signal);
        var lease = claim();
        var beforeWithdrawal = backend.frame(lease);
        backend.withdraw(new ThreadKey(key, "main"), turn.turnId(), signal.id(), "withdraw-1");
        assertTrue(backend.frame(lease).signals().isEmpty());
        backend.withdraw(new ThreadKey(key, "main"), turn.turnId(), signal.id(), "withdraw-1");
        code(
                DurableException.Code.CONFLICT,
                () ->
                        backend.commit(
                                lease,
                                new Commit(
                                        "apply-withdrawn",
                                        beforeWithdrawal.turn().revision(),
                                        DurableJson.value("changed"),
                                        List.of(),
                                        List.of(),
                                        List.of(signal.id()),
                                        List.of(),
                                        TurnStatus.RUNNING,
                                        null,
                                        null,
                                        null)));
        assertTrue(backend.frame(lease).checkpoint().isNull());
        var applied = new Signal("inject", "input:inject", DurableJson.value("additional input"));
        backend.signal(new ThreadKey(key, "main"), turn.turnId(), applied);
        var frame = backend.frame(lease);
        backend.commit(
                lease,
                new Commit(
                        "apply-input",
                        frame.turn().revision(),
                        DurableJson.value("applied"),
                        List.of(),
                        List.of(),
                        List.of(applied.id()),
                        List.of(),
                        TurnStatus.RUNNING,
                        null,
                        null,
                        null));
        code(
                DurableException.Code.CONFLICT,
                () ->
                        backend.withdraw(
                                new ThreadKey(key, "main"),
                                turn.turnId(),
                                applied.id(),
                                "too-late"));
    }

    @Test
    protected void resourceReceiptsFreezeReadsAndWritesAcrossLeaseReplacementAndCheckpoint() {
        submit();
        var lease = claim();
        var write =
                new SessionResources.Request(
                        "put",
                        Operation.PUT,
                        List.of("workspace"),
                        "note",
                        DurableJson.value(Map.of("text", "original")),
                        0,
                        0,
                        0);
        backend.resource(lease, write);
        var read =
                new SessionResources.Request(
                        "read", Operation.GET, List.of("workspace"), "note", null, 0, 0, 0);
        var original = backend.resource(lease, read);
        long version = original.path("version").asLong();
        var cas =
                new SessionResources.Request(
                        "cas",
                        Operation.CAS,
                        List.of("workspace"),
                        "note",
                        DurableJson.value(Map.of("text", "changed")),
                        version,
                        0,
                        0);
        assertTrue(backend.resource(lease, cas).asBoolean());
        assertTrue(
                backend.resource(lease, cas).asBoolean(), "CAS retry returns its durable receipt");
        assertEquals(
                original,
                backend.resource(lease, read),
                "Read receipt freezes finalization observations");
        backend.release(lease, Duration.ZERO);
        var replacement = claim();
        assertEquals(original, backend.resource(replacement, read));
        code(DurableException.Code.FENCED, () -> backend.resource(lease, write));
        backend.commit(
                replacement,
                commit(
                        backend.frame(replacement),
                        "checkpoint",
                        TurnStatus.SUCCEEDED,
                        List.of(),
                        List.of(),
                        List.of()));
        var resources = backend.checkpointResources(new ThreadKey(key, "main"), 1);
        assertEquals(1, resources.size());
        assertEquals("changed", resources.get(0).value().path("text").asText());
    }

    @Test
    protected void sourceStreamRejectsMissingPrefixAndUnsealedTerminalAndPreservesGapFacts() {
        submit();
        var lease = claim();
        backend.commit(
                lease,
                commit(
                        backend.frame(lease),
                        "tool",
                        TurnStatus.RUNNING,
                        List.of(tool()),
                        List.of(),
                        List.of()));
        var execution = backend.frame(lease).tools().get(0).request();
        backend.beginDispatch(lease, execution.executionId());
        var first =
                ExecutionMessages.event(
                        execution,
                        1,
                        new ExecutionResult(
                                ExecutionState.RUNNING, execution.executionId(), null, null));
        var second =
                ExecutionMessages.event(
                        execution, 2, null, new OutputPage(0, 3, "YWJj", 10, false, null));
        code(DurableException.Code.CONFLICT, () -> backend.acceptExecutionEvent(second));
        backend.acceptExecutionEvent(first);
        backend.acceptExecutionEvent(second);
        backend.acceptExecutionEvent(second);
        var terminal =
                new ExecutionResult(
                        ExecutionState.SUCCEEDED,
                        execution.executionId(),
                        DurableJson.value("done"),
                        null);
        code(
                DurableException.Code.CONFLICT,
                () ->
                        backend.acceptExecutionEvent(
                                ExecutionMessages.event(execution, 3, terminal)));
        var gap =
                ExecutionMessages.event(
                        execution,
                        3,
                        null,
                        new OutputPage(3, 10, "", 10, true, "provider output retention expired"));
        backend.acceptExecutionEvent(gap);
        backend.acceptExecutionEvent(ExecutionMessages.event(execution, 4, terminal));
        assertEquals(terminal, backend.frame(lease).tools().get(0).result());
        var output =
                backend.events(key, null, 1000).events().stream()
                        .filter(event -> event.type().startsWith("tool.output"))
                        .toList();
        assertEquals(2, output.size());
        assertEquals("tool.output.gap", output.get(1).type());
        output.forEach(event -> DurableProtocol.validate("event", event));
    }

    private Commit commit(
            Frame f,
            String id,
            TurnStatus status,
            List<ToolCall> tools,
            List<String> applied,
            List<EventInput> events) {
        return new Commit(
                id,
                f.turn().revision(),
                DurableJson.value(Map.of("native", "checkpoint")),
                tools,
                applied.stream().map(execution -> new AppliedResult(execution, null)).toList(),
                List.of(),
                events,
                status,
                null,
                DurableJson.value("done"),
                null);
    }

    private ToolCall tool() {
        return new ToolCall(
                "call", "remote", "build", DurableJson.value(Map.of("command", "test")));
    }

    private void code(DurableException.Code expected, Runnable action) {
        assertEquals(expected, assertThrows(DurableException.class, action::run).code());
    }

    @Test
    protected void lostAdmissionReplyAndReopenedBackendReturnOriginalRun() {
        TurnReceipt receipt = submit();
        SessionBackend reopened = wrap(new TransactionalSessionBackend(openStore()));
        assertEquals(
                receipt,
                reopened.submit(new ThreadKey(key, "main"), "request", DurableJson.value("hello")));
        code(
                DurableException.Code.CONFLICT,
                () ->
                        reopened.submit(
                                new ThreadKey(key, "main"),
                                "request",
                                DurableJson.value("changed")));
        code(
                DurableException.Code.CONFLICT,
                () ->
                        reopened.create(
                                new SessionSpec(
                                        key,
                                        new ThreadBinding("test", "v2", "1"),
                                        DurableJson.value(null))));
        assertEquals(1, reopened.snapshot(key).turns().size());
    }

    @Test
    protected void concurrentAdmissionHasOneReceipt() throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        try {
            var calls = IntStream.range(0, 24).mapToObj(i -> pool.submit(this::submit)).toList();
            TurnReceipt receipt = calls.get(0).get();
            for (var call : calls) assertEquals(receipt, call.get());
            assertEquals(1, backend.snapshot(key).turns().size());
        } finally {
            pool.shutdownNow();
        }
    }

    private record ParentExecution(Lease lease, ExecutionRequest request) {}

    private ParentExecution admittedDelegation(ThreadKey thread, String requestId) {
        backend.createThread(new ThreadSpec(thread, new ThreadBinding("test", "v1", "1")));
        var receipt = backend.submit(thread, requestId, DurableJson.value("parent"));
        var lease =
                backend.claim(thread, "parent-owner:" + requestId, Duration.ofMinutes(1))
                        .orElseThrow();
        assertEquals(receipt.turnId(), lease.turnId());
        String run = backend.prepareRun(lease, "invoke:" + requestId).runId();
        String step = "step:" + requestId;
        backend.commit(
                lease,
                withToolFacts(
                        invocationCommit(
                                lease,
                                "parent-intent",
                                new RunChange(run, RunStatus.RUNNING, null, null),
                                List.of(
                                        new StepChange(
                                                run,
                                                step,
                                                StepStatus.RUNNING,
                                                StepPhase.ACTING,
                                                null,
                                                null))),
                        List.of(tool().withOrigin(new RunStepRef(run, step))),
                        List.of()));
        var request = backend.frame(lease).tools().get(0).request();
        backend.beginDispatch(lease, request.executionId());
        return new ParentExecution(lease, request);
    }

    @Test
    protected void durableChildAdmissionAndCompletionAreReusedByAReplacementParent() {
        var request = admittedDelegation(new ThreadKey(key, "main"), "parent").request();
        var executor = new DelegationExecutor(backend, "child", "v1", "1");
        assertEquals(ExecutionState.NOT_FOUND, executor.query(request, null).state());
        var first = executor.startExecution(request);
        assertEquals(first, executor.startExecution(request));
        HarnessAdapter child =
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
                        var frame = context.frame();
                        context.commit(
                                new Commit(
                                        "done",
                                        frame.turn().revision(),
                                        frame.checkpoint(),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        TurnStatus.SUCCEEDED,
                                        null,
                                        DurableJson.value(42),
                                        null));
                    }
                };
        try (var runtime =
                DurableRuntime.builder().backend(backend).harness("child", "v1", child).build()) {
            runtime.tick(DelegationExecutor.childThread(request));
        }
        var replacement =
                new DelegationExecutor(
                        backend,
                        ignored -> {
                            throw new AssertionError(
                                    "Persisted delegation must not resolve a new target");
                        });
        assertEquals(ExecutionState.SUCCEEDED, replacement.query(request, null).state());
        assertEquals(42, replacement.query(request, null).result().asInt());
        assertEquals(
                1, backend.turns(DelegationExecutor.childThread(request), null, 10).items().size());
        var foreign =
                new ExecutionRequest(
                        request.executionId(),
                        new ThreadKey(new SessionKey(key.namespace(), "other-parent"), "main"),
                        "parent",
                        tool());
        assertEquals(ExecutionState.NOT_FOUND, replacement.query(foreign, null).state());
        backend.create(
                new SessionSpec(
                        foreign.session(),
                        new ThreadBinding("test", "v1", "1"),
                        DurableJson.value(null)));
        var otherRequest = admittedDelegation(foreign.thread(), "other-parent").request();
        var other = executor.startExecution(otherRequest);
        assertNotEquals(first.handle(), other.handle());
        assertEquals(ExecutionState.RUNNING, replacement.query(otherRequest, null).state());
        assertEquals(ExecutionState.SUCCEEDED, replacement.query(request, null).state());
    }

    @Test
    protected void delegationCancellationBeforeAdmissionNeverResolvesOrStartsAChild() {
        var parent = admittedDelegation(new ThreadKey(key, "main"), "parent");
        var executor =
                new DelegationExecutor(
                        backend,
                        ignored -> {
                            throw new AssertionError(
                                    "Cancelled delegation must not resolve a target");
                        });
        var before = backend.snapshot(key);
        code(
                DurableException.Code.CONFLICT,
                () -> executor.cancel(parent.request(), "wrong-handle"));
        assertEquals(before, backend.snapshot(key));
        var cancelled = executor.cancel(parent.request(), null);
        assertEquals(ExecutionState.CANCELLED, cancelled.state());
        assertEquals(cancelled, executor.cancel(parent.request(), null));
        assertEquals(cancelled, executor.startExecution(parent.request()));
        assertEquals(cancelled, executor.query(parent.request(), null));
        assertEquals(1, backend.threads(key, null, 10).items().size());
        assertEquals(1, backend.snapshot(key).turns().size());
        assertNull(backend.delegation(parent.request()).orElseThrow().child());
        assertTrue(backend.delegation(parent.request()).orElseThrow().cancelRequested());
        var changed =
                new ExecutionRequest(
                        parent.request().executionId(),
                        parent.request().thread(),
                        parent.request().turnId(),
                        parent.request().call().withOrigin(null));
        code(DurableException.Code.CONFLICT, () -> executor.startExecution(changed));
        assertEquals(
                1,
                backend.events(key, null, 100).events().stream()
                        .filter(e -> e.type().equals("tool.delegation.cancel_requested"))
                        .count());
    }

    @Test
    protected void cancellationWinsAgainstAnAlreadyResolvingDelegation() throws Exception {
        var parent = admittedDelegation(new ThreadKey(key, "main"), "parent").request();
        var resolving = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var executor =
                new DelegationExecutor(
                        backend,
                        request -> {
                            resolving.countDown();
                            try {
                                if (!proceed.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("Resolver did not resume");
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(error);
                            }
                            return new DelegationTarget(
                                    new ThreadSpec(
                                            DelegationExecutor.childThread(request),
                                            new ThreadBinding("child", "v1", "1")),
                                    DurableJson.value("input"));
                        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var starting = pool.submit(() -> executor.startExecution(parent));
            assertTrue(resolving.await(5, TimeUnit.SECONDS));
            assertEquals(ExecutionState.CANCELLED, executor.cancel(parent, null).state());
            proceed.countDown();
            assertEquals(ExecutionState.CANCELLED, starting.get(5, TimeUnit.SECONDS).state());
            assertEquals(1, backend.snapshot(key).turns().size());
            code(
                    DurableException.Code.NOT_FOUND,
                    () -> backend.thread(DelegationExecutor.childThread(parent)));
        } finally {
            proceed.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    protected void reusedChildThreadKeepsOtherTurnsAndAdvancesWhileItsParentIsPaused() {
        var child =
                new ThreadSpec(
                        new ThreadKey(key, "shared-child"), new ThreadBinding("child", "v1", "1"));
        backend.createThread(child);
        var unrelated = backend.submit(child.key(), "unrelated", DurableJson.value("other caller"));
        var first = admittedDelegation(new ThreadKey(key, "main"), "first-parent");
        var second = admittedDelegation(new ThreadKey(key, "second-parent"), "second-parent");
        var executor =
                new DelegationExecutor(
                        backend,
                        request ->
                                new DelegationTarget(child, DurableJson.value(request.turnId())));
        var firstChild = executor.startExecution(first.request());
        var secondChild = executor.startExecution(second.request());
        assertNotEquals(firstChild.handle(), secondChild.handle());
        assertEquals(
                child.key(),
                backend.execution(first.request().thread(), first.request().executionId())
                        .delegation()
                        .thread());
        var parent = backend.turn(child.key(), secondChild.handle()).parent();
        assertEquals(second.request().thread(), parent.thread());
        assertEquals(second.request().turnId(), parent.turnId());
        assertEquals(second.request().executionId(), parent.executionId());
        assertEquals(second.request().call().origin(), parent.origin());
        code(
                DurableException.Code.CONFLICT,
                () -> executor.cancel(first.request(), secondChild.handle()));
        assertEquals(
                ExecutionState.CANCELLED,
                executor.cancel(first.request(), firstChild.handle()).state());
        assertEquals(TurnStatus.QUEUED, backend.turn(child.key(), unrelated.turnId()).status());
        assertEquals(TurnStatus.QUEUED, backend.turn(child.key(), secondChild.handle()).status());
        backend.control(
                second.request().thread(),
                new ControlCommand(
                        second.request().turnId(),
                        "pause-parent",
                        ControlKind.PAUSE,
                        null,
                        null,
                        0));
        backend.release(second.lease(), Duration.ZERO);
        backend.cancel(child.key(), unrelated.turnId(), "finish-unrelated");
        var childLease =
                backend.claim(child.key(), "child-owner", Duration.ofMinutes(1)).orElseThrow();
        assertEquals(secondChild.handle(), childLease.turnId());
        var frame = backend.frame(childLease);
        backend.commit(
                childLease,
                new Commit(
                        "child-done",
                        frame.turn().revision(),
                        DurableJson.value("child-context"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        TurnStatus.SUCCEEDED,
                        null,
                        DurableJson.value(42),
                        null));
        var replacement =
                new DelegationExecutor(
                        backend,
                        ignored -> {
                            throw new AssertionError("Target already frozen");
                        });
        assertEquals(
                ExecutionState.SUCCEEDED, replacement.startExecution(second.request()).state());
        assertEquals(
                42, replacement.query(second.request(), secondChild.handle()).result().asInt());
        assertEquals(
                TurnStatus.PAUSED,
                backend.turn(second.request().thread(), second.request().turnId()).status());
        assertEquals(3, backend.turns(child.key(), null, 10).items().size());
        assertEquals(
                2,
                backend.events(key, null, 1000).events().stream()
                        .filter(e -> e.type().equals("turn.delegated"))
                        .count());
    }

    @Test
    protected void delegationAdmissionRollsBackAndLostRepliesKeepTheFrozenMapping() {
        var parent = admittedDelegation(new ThreadKey(key, "main"), "parent").request();
        var target =
                new DelegationTarget(
                        new ThreadSpec(
                                DelegationExecutor.childThread(parent),
                                new ThreadBinding("child", "v1", "1")),
                        DurableJson.value("child-input"));
        var rollback = new AtomicBoolean();
        var lostReply = new AtomicBoolean();
        var faulty =
                wrap(
                        new TransactionalSessionBackend(
                                new RecordStore() {
                                    public <T> T transaction(
                                            String scope, Function<Transaction, T> action) {
                                        T value =
                                                store.transaction(
                                                        scope,
                                                        tx -> {
                                                            T result = action.apply(tx);
                                                            if (rollback.getAndSet(false))
                                                                throw new DurableException(
                                                                        DurableException.Code
                                                                                .UNAVAILABLE,
                                                                        "before commit");
                                                            return result;
                                                        });
                                        if (lostReply.getAndSet(false))
                                            throw new DurableException(
                                                    DurableException.Code.UNAVAILABLE,
                                                    "reply lost");
                                        return value;
                                    }

                                    public List<Work> ready(String queue, int limit) {
                                        return store.ready(queue, limit);
                                    }

                                    public List<String> keys(
                                            String prefix, String after, int limit) {
                                        return store.keys(prefix, after, limit);
                                    }
                                }));
        var before = backend.snapshot(key);
        rollback.set(true);
        code(DurableException.Code.UNAVAILABLE, () -> faulty.delegate(parent, target));
        assertEquals(before, backend.snapshot(key));
        assertTrue(backend.delegation(parent).isEmpty());
        code(DurableException.Code.NOT_FOUND, () -> backend.thread(target.child().key()));
        lostReply.set(true);
        code(DurableException.Code.UNAVAILABLE, () -> faulty.delegate(parent, target));
        var saved = backend.delegation(parent).orElseThrow();
        assertEquals(saved, faulty.delegate(parent, target));
        var snapshot = backend.snapshot(key);
        code(
                DurableException.Code.CONFLICT,
                () ->
                        backend.delegate(
                                parent,
                                new DelegationTarget(
                                        target.child(), DurableJson.value("changed-input"))));
        code(
                DurableException.Code.INVALID,
                () ->
                        backend.delegate(
                                parent,
                                new DelegationTarget(
                                        new ThreadSpec(
                                                parent.thread(),
                                                new ThreadBinding("test", "v1", "1")),
                                        target.input())));
        code(
                DurableException.Code.INVALID,
                () ->
                        backend.delegate(
                                parent,
                                new DelegationTarget(
                                        new ThreadSpec(
                                                new ThreadKey(
                                                        new SessionKey("foreign", "session"),
                                                        "child"),
                                                target.child().binding()),
                                        target.input())));
        assertEquals(snapshot, backend.snapshot(key));
        var childView = backend.turn(saved.child().thread(), saved.child().turnId());
        assertEquals(parent.call().origin(), childView.parent().origin());
        var projected =
                snapshot.projection()
                        .path("tools")
                        .path(
                                EventProjection.toolId(
                                        "main", parent.turnId(), parent.call().callId()))
                        .path("data");
        assertEquals(DurableJson.value(saved.child()), projected.path("child"));
    }

    @Test
    protected void reusedThreadsCannotCreateACyclicDelegationQueue() {
        var first = admittedDelegation(new ThreadKey(key, "main"), "first-parent").request();
        var second = admittedDelegation(new ThreadKey(key, "other"), "second-parent").request();
        backend.delegate(
                first,
                new DelegationTarget(
                        backend.thread(second.thread()).definition(), DurableJson.value("child")));
        var before = backend.snapshot(key);
        code(
                DurableException.Code.CONFLICT,
                () ->
                        backend.delegate(
                                second,
                                new DelegationTarget(
                                        backend.thread(first.thread()).definition(),
                                        DurableJson.value("cycle"))));
        assertEquals(before, backend.snapshot(key));
        assertTrue(backend.delegation(second).isEmpty());
    }

    @Test
    protected void persistedTimerWakesWithoutAnInMemoryCallback() {
        var calls = new AtomicInteger();
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
                        var frame = context.frame();
                        boolean first = calls.incrementAndGet() == 1;
                        context.commit(
                                new Commit(
                                        "step:" + calls.get(),
                                        frame.turn().revision(),
                                        frame.checkpoint(),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        first ? TurnStatus.WAITING : TurnStatus.SUCCEEDED,
                                        first
                                                ? new WaitCondition("timer", frame.now() + 500)
                                                : null,
                                        null,
                                        null));
                    }
                };
        var turn = submit();
        try (var runtime =
                DurableRuntime.builder().backend(backend).harness("test", "v1", adapter).build()) {
            runtime.tick(new ThreadKey(key, "main"));
            runtime.tick(new ThreadKey(key, "main"));
            assertEquals(1, calls.get());
        }
        clock.addAndGet(600);
        try (var runtime =
                DurableRuntime.builder().backend(backend).harness("test", "v1", adapter).build()) {
            runtime.tick(new ThreadKey(key, "main"));
        }
        assertEquals(
                TurnStatus.SUCCEEDED,
                backend.turn(new ThreadKey(key, "main"), turn.turnId()).status());
    }

    @Test
    protected void cancellationBetweenFrameReadAndDispatchNeverStartsAnUnadmittedTool() {
        var turn = submit();
        var lease = claim();
        backend.commit(
                lease,
                commit(
                        backend.frame(lease),
                        "intent",
                        TurnStatus.RUNNING,
                        List.of(tool()),
                        List.of(),
                        List.of()));
        var tool = backend.frame(lease).tools().get(0);
        backend.cancel(new ThreadKey(key, "main"), turn.turnId(), "cancel");
        code(
                DurableException.Code.CONFLICT,
                () -> backend.beginDispatch(lease, tool.request().executionId()));
        assertFalse(backend.frame(lease).tools().get(0).dispatchAttempted());
    }

    @Test
    protected void toolResultDuringPauseRemainsUnappliedUntilExplicitResume() {
        var turn = submit();
        var thread = new ThreadKey(key, "main");
        var lease = claim();
        backend.commit(
                lease,
                commit(
                        backend.frame(lease),
                        "intent",
                        TurnStatus.RUNNING,
                        List.of(tool()),
                        List.of(),
                        List.of()));
        String execution = backend.frame(lease).tools().get(0).request().executionId();
        backend.beginDispatch(lease, execution);
        backend.control(
                thread,
                new ControlCommand(turn.turnId(), "pause", ControlKind.PAUSE, null, null, 0));
        code(DurableException.Code.CONFLICT, () -> backend.beginDispatch(lease, execution));
        var result =
                new ExecutionResult(
                        ExecutionState.SUCCEEDED,
                        "original-operation",
                        DurableJson.value("original result"),
                        null);
        backend.acceptResult(thread, execution, result);
        backend.release(lease, Duration.ZERO);
        backend.acceptResult(thread, execution, result);
        assertEquals(TurnStatus.PAUSED, backend.turn(thread, turn.turnId()).status());
        clock.addAndGet(10_000);
        assertTrue(backend.claim(thread, "replacement", Duration.ofSeconds(5)).isEmpty());
        assertFalse(backend.execution(thread, execution).applied());
        backend.control(
                thread,
                new ControlCommand(
                        turn.turnId(),
                        "resume",
                        ControlKind.RESUME,
                        null,
                        null,
                        backend.turn(thread, turn.turnId()).revision()));
        var replacement = backend.claim(thread, "replacement", Duration.ofSeconds(5)).orElseThrow();
        var restored = backend.frame(replacement);
        assertEquals(1, restored.tools().size());
        assertEquals(execution, restored.tools().get(0).request().executionId());
        assertEquals(result, restored.tools().get(0).result());
        backend.commit(
                replacement,
                commit(
                        restored,
                        "apply",
                        TurnStatus.SUCCEEDED,
                        List.of(),
                        List.of(execution),
                        List.of()));
        assertTrue(backend.execution(thread, execution).applied());
    }

    @Test
    protected void pauseAfterOwnerExpiryFencesOldWritesWithoutInventingAnInvocationCompletion() {
        var turn = submit();
        var thread = new ThreadKey(key, "main");
        var old = claim();
        var prepared = backend.prepareRun(old, "prepared-before-crash");
        clock.addAndGet(20_000);
        backend.control(
                thread,
                new ControlCommand(turn.turnId(), "pause", ControlKind.PAUSE, null, null, 0));
        assertEquals(TurnStatus.PAUSED, backend.turn(thread, turn.turnId()).status());
        var lost = backend.run(thread, turn.turnId(), prepared.runId());
        assertEquals(RunStatus.LOST, lost.status());
        assertEquals("ownership_lost", lost.reason());
        assertNull(lost.startedAt());
        code(DurableException.Code.FENCED, () -> backend.frame(old));
        assertTrue(backend.claim(thread, "replacement", Duration.ofSeconds(5)).isEmpty());
        backend.control(
                thread,
                new ControlCommand(
                        turn.turnId(),
                        "resume",
                        ControlKind.RESUME,
                        null,
                        null,
                        backend.turn(thread, turn.turnId()).revision()));
        var replacement = backend.claim(thread, "replacement", Duration.ofSeconds(5)).orElseThrow();
        assertNotEquals(old.epoch(), replacement.epoch());
        var next = backend.prepareRun(replacement, "after-resume");
        assertNotEquals(prepared.runId(), next.runId());
        assertEquals(prepared.runId(), next.previousRunId());
    }

    @Test
    protected void pauseAndCompletionHaveOneTransactionalWinner() throws Exception {
        var turn = submit();
        var thread = new ThreadKey(key, "main");
        var lease = claim();
        var finish =
                commit(
                        backend.frame(lease),
                        "finish",
                        TurnStatus.SUCCEEDED,
                        List.of(),
                        List.of(),
                        List.of());
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var paused =
                    pool.submit(
                            () -> {
                                start.await();
                                try {
                                    backend.control(
                                            thread,
                                            new ControlCommand(
                                                    turn.turnId(),
                                                    "pause",
                                                    ControlKind.PAUSE,
                                                    null,
                                                    null,
                                                    0));
                                    return true;
                                } catch (DurableException error) {
                                    assertEquals(DurableException.Code.CONFLICT, error.code());
                                    return false;
                                }
                            });
            var completed =
                    pool.submit(
                            () -> {
                                start.await();
                                try {
                                    backend.commit(lease, finish);
                                    return true;
                                } catch (DurableException error) {
                                    assertEquals(DurableException.Code.CONFLICT, error.code());
                                    return false;
                                }
                            });
            start.countDown();
            boolean accepted = paused.get(5, TimeUnit.SECONDS);
            assertNotEquals(accepted, completed.get(5, TimeUnit.SECONDS));
            if (accepted) {
                backend.release(lease, Duration.ZERO);
                assertEquals(TurnStatus.PAUSED, backend.turn(thread, turn.turnId()).status());
            } else {
                assertEquals(TurnStatus.SUCCEEDED, backend.turn(thread, turn.turnId()).status());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private HarnessAdapter failingAdapter(AtomicInteger calls, RuntimeException failure) {
        return new HarnessAdapter() {
            public Capabilities capabilities() {
                return new Capabilities(
                        "1",
                        RecoveryBoundary.CHECKPOINT,
                        EventDelivery.COMPLETED,
                        false,
                        List.of("text"));
            }

            public void execute(HarnessContext context) {
                calls.incrementAndGet();
                throw failure;
            }
        };
    }

    @Test
    protected void retriesAndFrozenBackoffSurviveHostReplacementAndExhaustTheBudget() {
        var calls = new AtomicInteger();
        var adapter =
                failingAdapter(
                        calls,
                        new DurableException(
                                DurableException.Code.UNAVAILABLE, "temporary outage"));
        var turn = submit();
        var thread = new ThreadKey(key, "main");
        var policy = new RecoveryPolicy(2, 200, 400);
        try (var first =
                DurableRuntime.builder()
                        .backend(backend)
                        .harness("test", "v1", adapter)
                        .recoveryPolicy(policy)
                        .onError((k, e) -> {})
                        .build()) {
            first.tick(thread);
            var view = backend.turn(thread, turn.turnId());
            assertEquals(TurnStatus.WAITING, view.status());
            assertNull(view.waiting());
            assertEquals(1, view.recovery().failures());
            assertEquals(1200L, view.recovery().retryAt());
        }
        try (var replacement =
                DurableRuntime.builder()
                        .backend(backend)
                        .harness("test", "v1", adapter)
                        .recoveryPolicy(new RecoveryPolicy(50, 1, 1))
                        .onError((k, e) -> {})
                        .build()) {
            clock.set(1199);
            replacement.tick(thread);
            assertEquals(1, calls.get());
            clock.set(1200);
            replacement.tick(thread);
            var second = backend.turn(thread, turn.turnId());
            assertEquals(2, calls.get());
            assertEquals(policy, second.recovery().policy());
            assertEquals(1600L, second.recovery().retryAt());
            backend.signal(
                    thread,
                    turn.turnId(),
                    new Signal("hint", "app:wakeup", DurableJson.value(true)));
            clock.set(1599);
            replacement.tick(thread);
            assertEquals(2, calls.get());
            clock.set(1600);
            replacement.tick(thread);
            var failed = backend.turn(thread, turn.turnId());
            assertEquals(TurnStatus.FAILED, failed.status());
            assertEquals(3, failed.recovery().failures());
            assertFalse(failed.recovery().retryAllowed());
            assertNull(failed.recovery().retryAt());
            clock.addAndGet(10000);
            replacement.tick(thread);
            assertEquals(3, calls.get());
            assertEquals(
                    3,
                    backend.events(key, null, 1000).events().stream()
                            .filter(e -> e.type().equals("turn.recovery"))
                            .count());
            assertTrue(backend.runs(thread, turn.turnId(), null, 10).items().isEmpty());
        }
    }

    @Test
    protected void runtimeRetriesAnUncertainFailureReceiptWithoutCountingItTwice() {
        var calls = new AtomicInteger();
        var replies = new AtomicInteger();
        var reported =
                (SessionBackend)
                        Proxy.newProxyInstance(
                                SessionBackend.class.getClassLoader(),
                                new Class<?>[] {SessionBackend.class},
                                (proxy, method, args) -> {
                                    Object value;
                                    try {
                                        value = method.invoke(backend, args);
                                    } catch (InvocationTargetException error) {
                                        throw error.getCause();
                                    }
                                    if (method.getName().equals("recordFailure")
                                            && replies.getAndIncrement() == 0)
                                        throw new DurableException(
                                                DurableException.Code.UNAVAILABLE, "reply lost");
                                    return value;
                                });
        var turn = submit();
        var thread = new ThreadKey(key, "main");
        try (var runtime =
                DurableRuntime.builder()
                        .backend(reported)
                        .harness(
                                "test",
                                "v1",
                                failingAdapter(
                                        calls,
                                        new DurableException(
                                                DurableException.Code.UNAVAILABLE,
                                                "model unavailable")))
                        .recoveryPolicy(new RecoveryPolicy(2, 200, 400))
                        .onError((k, e) -> {})
                        .build()) {
            runtime.tick(thread);
            assertEquals(1, calls.get());
            assertEquals(2, replies.get());
            assertEquals(1, backend.turn(thread, turn.turnId()).recovery().failures());
            assertEquals(1200L, backend.turn(thread, turn.turnId()).recovery().retryAt());
        }
    }

    @Test
    protected void failureReceiptIsIdempotentAfterLostReplyAndOldOwnerCannotAlterIt() {
        var turn = submit();
        var lease = claim();
        var run = backend.prepareRun(lease, "run");
        var frame = backend.frame(lease);
        backend.commit(
                lease,
                new Commit(
                        "running",
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
                        List.of()));
        var failure = new Failure(true, "unavailable", new RecoveryPolicy(2, 100, 200));
        var first = backend.recordFailure(lease, failure);
        code(DurableException.Code.CONFLICT, () -> backend.prepareRun(lease, "bypass-backoff"));
        backend.release(lease, Duration.ZERO);
        assertEquals(first, backend.recordFailure(lease, failure));
        clock.addAndGet(100);
        var replacement = claim();
        assertEquals(first, backend.recordFailure(lease, failure));
        code(
                DurableException.Code.CONFLICT,
                () ->
                        backend.recordFailure(
                                lease, new Failure(false, "changed", failure.policy())));
        var second = backend.recordFailure(replacement, failure);
        assertEquals(2, second.failures());
        assertEquals(first, backend.recordFailure(lease, failure));
        assertEquals(2, backend.turn(lease.thread(), turn.turnId()).recovery().failures());
        var stopped = backend.run(lease.thread(), turn.turnId(), run.runId());
        assertEquals(RunStatus.FAILED, stopped.status());
        assertEquals("failed", stopped.outcome());
        code(
                DurableException.Code.FENCED,
                () ->
                        backend.commit(
                                lease,
                                commit(
                                        frame,
                                        "stale",
                                        TurnStatus.SUCCEEDED,
                                        List.of(),
                                        List.of(),
                                        List.of())));
    }

    @Test
    protected void pauseAndResumePreserveRecoveryBackoffAndCancellationBypassesIt() {
        var turn = submit();
        var thread = new ThreadKey(key, "main");
        var lease = claim();
        backend.recordFailure(
                lease, new Failure(true, "temporary", new RecoveryPolicy(2, 500, 1000)));
        backend.control(
                thread,
                new ControlCommand(turn.turnId(), "pause", ControlKind.PAUSE, null, null, 0));
        backend.release(lease, Duration.ZERO);
        var paused = backend.turn(thread, turn.turnId());
        assertEquals(TurnStatus.PAUSED, paused.status());
        clock.set(1100);
        backend.control(
                thread,
                new ControlCommand(
                        turn.turnId(),
                        "resume",
                        ControlKind.RESUME,
                        null,
                        null,
                        paused.revision()));
        assertTrue(backend.claim(thread, "early", Duration.ofSeconds(1)).isEmpty());
        assertEquals(1, backend.turn(thread, turn.turnId()).recovery().failures());
        backend.cancel(thread, turn.turnId(), "cancel");
        var cancellation = claim();
        assertTrue(backend.frame(cancellation).turn().cancellationRequested());
        assertEquals(1, backend.frame(cancellation).turn().recovery().failures());
    }

    @Test
    protected void permanentFailureStopsWithoutRetryAndClassificationCanBeConfigured() {
        var calls = new AtomicInteger();
        var adapter = failingAdapter(calls, new IllegalArgumentException("invalid input"));
        var turn = submit();
        var thread = new ThreadKey(key, "main");
        try (var runtime =
                DurableRuntime.builder()
                        .backend(backend)
                        .harness("test", "v1", adapter)
                        .onError((k, e) -> {})
                        .build()) {
            runtime.tick(thread);
            var failed = backend.turn(thread, turn.turnId());
            assertEquals(TurnStatus.FAILED, failed.status());
            assertEquals(1, failed.recovery().failures());
            assertFalse(failed.recovery().retryable());
            clock.addAndGet(10000);
            runtime.tick(thread);
            assertEquals(1, calls.get());
        }
        var next = backend.submit(thread, "custom", DurableJson.value("custom provider"));
        try (var runtime =
                DurableRuntime.builder()
                        .backend(backend)
                        .harness("test", "v1", adapter)
                        .retryableFailure(error -> error instanceof IllegalArgumentException)
                        .onError((k, e) -> {})
                        .build()) {
            runtime.tick(thread);
            assertEquals(TurnStatus.WAITING, backend.turn(thread, next.turnId()).status());
            assertTrue(backend.turn(thread, next.turnId()).recovery().retryAllowed());
        }
    }

    @Test
    protected void exhaustedRecoveryReconcilesUnknownToolsWithoutStartingAnotherInvocation() {
        var turn = submit();
        var thread = new ThreadKey(key, "main");
        var lease = claim();
        backend.commit(
                lease,
                commit(
                        backend.frame(lease),
                        "intent",
                        TurnStatus.WAITING,
                        List.of(tool()),
                        List.of(),
                        List.of()));
        lease = claim();
        var execution = backend.frame(lease).tools().get(0).request().executionId();
        backend.beginDispatch(lease, execution);
        backend.acceptResult(
                thread,
                execution,
                new ExecutionResult(ExecutionState.UNKNOWN, null, null, "unconfirmed"));
        backend.recordFailure(
                lease, new Failure(true, "retry budget exhausted", new RecoveryPolicy(0, 1, 1)));
        var stoppedLease = lease;
        code(DurableException.Code.CONFLICT, () -> backend.prepareRun(stoppedLease, "forbidden"));
        code(DurableException.Code.CONFLICT, () -> backend.beginDispatch(stoppedLease, execution));
        backend.release(lease, Duration.ZERO);
        var calls = new AtomicInteger();
        var queries = new AtomicInteger();
        Executor executor =
                new Executor() {
                    public Capabilities capabilities() {
                        return new Capabilities(true, true, false, false);
                    }

                    public ExecutionResult startExecution(ExecutionRequest request) {
                        throw new AssertionError("Unknown operation must never restart");
                    }

                    public ExecutionResult query(ExecutionRequest request, String handle) {
                        queries.incrementAndGet();
                        return new ExecutionResult(
                                ExecutionState.UNKNOWN, null, null, "unconfirmed");
                    }

                    public ExecutionResult cancel(ExecutionRequest request, String handle) {
                        throw new AssertionError("Cancellation is not supported by this provider");
                    }
                };
        try (var runtime =
                DurableRuntime.builder()
                        .backend(backend)
                        .harness(
                                "test",
                                "v1",
                                failingAdapter(calls, new IllegalStateException("must not invoke")))
                        .executor("remote", executor)
                        .onError((k, e) -> {})
                        .build()) {
            runtime.tick(thread);
            assertEquals(0, calls.get());
            assertEquals(1, queries.get());
            assertEquals(TurnStatus.WAITING, backend.turn(thread, turn.turnId()).status());
            assertEquals(
                    ExecutionState.UNKNOWN, backend.execution(thread, execution).result().state());
            backend.acceptResult(
                    thread,
                    execution,
                    new ExecutionResult(
                            ExecutionState.SUCCEEDED,
                            null,
                            DurableJson.value("original result"),
                            null));
            runtime.tick(thread);
            assertEquals(TurnStatus.FAILED, backend.turn(thread, turn.turnId()).status());
            assertEquals(0, calls.get());
            assertEquals(1, backend.turn(thread, turn.turnId()).recovery().failures());
            assertEquals(
                    "original result",
                    backend.execution(thread, execution).result().result().asText());
        }
    }

    @Test
    protected void atomicCommitRollsBackToolsCheckpointAndEvents() {
        submit();
        Lease lease = claim();
        Frame before = backend.frame(lease);
        Snapshot snapshot = backend.snapshot(key);
        Commit invalid =
                commit(
                        before,
                        "invalid",
                        TurnStatus.SUCCEEDED,
                        List.of(tool()),
                        List.of(),
                        List.of());
        code(DurableException.Code.INVALID, () -> backend.commit(lease, invalid));
        assertEquals(before.turn().revision(), backend.frame(lease).turn().revision());
        assertEquals(before.checkpoint(), backend.frame(lease).checkpoint());
        assertTrue(backend.frame(lease).tools().isEmpty());
        assertEquals(snapshot.cursor(), backend.snapshot(key).cursor());
        assertTrue(
                backend.receipt(new ThreadKey(key, "main"), lease.turnId(), "invalid").isEmpty());
    }

    @Test
    protected void lostCommitReplyIsQueryableAndPriorReceiptSurvivesFencing() {
        submit();
        Lease old = claim();
        Frame f = backend.frame(old);
        Commit cmd = commit(f, "commit", TurnStatus.RUNNING, List.of(), List.of(), List.of());
        CommitReceipt receipt = backend.commit(old, cmd);
        clock.addAndGet(2000);
        Lease replacement = claim();
        assertTrue(replacement.epoch() > old.epoch());
        assertEquals(
                receipt,
                backend.receipt(new ThreadKey(key, "main"), old.turnId(), "commit").orElseThrow());
        assertEquals(receipt, backend.commit(old, cmd));
        code(
                DurableException.Code.FENCED,
                () ->
                        backend.commit(
                                old,
                                commit(
                                        backend.frame(replacement),
                                        "late",
                                        TurnStatus.RUNNING,
                                        List.of(),
                                        List.of(),
                                        List.of())));
        code(
                DurableException.Code.CONFLICT,
                () ->
                        backend.commit(
                                replacement,
                                new Commit(
                                        "commit",
                                        0,
                                        DurableJson.value("different"),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        TurnStatus.RUNNING,
                                        null,
                                        null,
                                        null)));
    }

    @Test
    protected void resultIntakeWakesOriginalRunAndApplicationSharesCheckpointCommit() {
        submit();
        Lease first = claim();
        backend.commit(
                first,
                commit(
                        backend.frame(first),
                        "intent",
                        TurnStatus.WAITING,
                        List.of(tool()),
                        List.of(),
                        List.of()));
        Lease second = claim();
        ToolExecution execution = backend.frame(second).tools().get(0);
        String id = execution.request().executionId();
        assertEquals(execution, backend.execution(new ThreadKey(key, "main"), id));
        assertFalse(backend.execution(new ThreadKey(key, "main"), id).dispatchAttempted());
        assertFalse(backend.beginDispatch(second, id).dispatchAttempted());
        assertTrue(backend.execution(new ThreadKey(key, "main"), id).dispatchAttempted());
        assertTrue(backend.beginDispatch(second, id).dispatchAttempted());
        backend.release(second, Duration.ofDays(1));
        ExecutionResult result =
                new ExecutionResult(
                        ExecutionState.SUCCEEDED, "operation", DurableJson.value("ok"), null);
        backend.acceptResult(new ThreadKey(key, "main"), id, result);
        backend.acceptResult(new ThreadKey(key, "main"), id, result);
        Lease third = claim();
        Frame ready = backend.frame(third);
        assertFalse(ready.tools().get(0).applied());
        code(
                DurableException.Code.INVALID,
                () ->
                        backend.commit(
                                third,
                                new Commit(
                                        "without-checkpoint",
                                        ready.turn().revision(),
                                        null,
                                        List.of(),
                                        List.of(new AppliedResult(id, null)),
                                        List.of(),
                                        List.of(),
                                        TurnStatus.SUCCEEDED,
                                        null,
                                        null,
                                        null)));
        assertFalse(backend.frame(third).tools().get(0).applied());
        backend.commit(
                third,
                commit(ready, "applied", TurnStatus.SUCCEEDED, List.of(), List.of(id), List.of()));
        assertEquals(
                TurnStatus.SUCCEEDED,
                backend.turn(new ThreadKey(key, "main"), first.turnId()).status());
        var types =
                backend.events(key, null, 100).events().stream().map(PublicEvent::type).toList();
        assertEquals(1, types.stream().filter("tool.result.accepted"::equals).count());
        assertEquals(1, types.stream().filter("tool.result.applied"::equals).count());
        assertTrue(types.indexOf("tool.result.accepted") < types.indexOf("tool.result.applied"));
    }

    @Test
    protected void signalsSurviveEarlyDeliveryAndRejectChangedDuplicate() {
        TurnReceipt r = submit();
        Signal early = new Signal("signal", "approval", DurableJson.value(true));
        backend.signal(new ThreadKey(key, "main"), r.turnId(), early);
        backend.signal(new ThreadKey(key, "main"), r.turnId(), early);
        code(
                DurableException.Code.CONFLICT,
                () ->
                        backend.signal(
                                new ThreadKey(key, "main"),
                                r.turnId(),
                                new Signal("signal", "approval", DurableJson.value(false))));
        Lease lease = claim();
        Frame frame = backend.frame(lease);
        assertEquals(List.of(early), frame.signals());
        backend.commit(
                lease,
                new Commit(
                        "consume",
                        0,
                        DurableJson.value("approved"),
                        List.of(),
                        List.of(),
                        List.of("signal"),
                        List.of(),
                        TurnStatus.SUCCEEDED,
                        null,
                        null,
                        null));
        assertEquals(
                TurnStatus.SUCCEEDED,
                backend.turn(new ThreadKey(key, "main"), r.turnId()).status());
    }

    @Test
    protected void cursorsAreSessionBoundAndSnapshotSurvivesRetention() {
        submit();
        Lease lease = claim();
        backend.commit(
                lease,
                commit(
                        backend.frame(lease),
                        "events",
                        TurnStatus.RUNNING,
                        List.of(),
                        List.of(),
                        List.of(
                                new EventInput(
                                        "e1",
                                        "message.delta",
                                        DurableJson.value(
                                                Map.of(
                                                        "messageId",
                                                        "m",
                                                        "attempt",
                                                        1,
                                                        "revision",
                                                        0,
                                                        "text",
                                                        "old"))),
                                new EventInput(
                                        "e2",
                                        "message.delta",
                                        DurableJson.value(
                                                Map.of(
                                                        "messageId",
                                                        "m",
                                                        "attempt",
                                                        2,
                                                        "revision",
                                                        0,
                                                        "text",
                                                        "new"))),
                                new EventInput(
                                        "e3",
                                        "message.completed",
                                        DurableJson.value(
                                                Map.of(
                                                        "messageId",
                                                        "m",
                                                        "attempt",
                                                        2,
                                                        "revision",
                                                        0,
                                                        "text",
                                                        "new final"))))));
        Snapshot snapshot = backend.snapshot(key);
        assertEquals(
                "new final",
                snapshot.projection().path("messages").path("m").path("text").asText());
        String old = backend.events(key, null, 1).cursor();
        backend.pruneEvents(key, snapshot.cursor());
        code(DurableException.Code.CURSOR_EXPIRED, () -> backend.events(key, old, 10));
        assertTrue(backend.events(key, snapshot.cursor(), 10).events().isEmpty());
        SessionKey other = new SessionKey("other", "session");
        backend.create(
                new SessionSpec(
                        other, new ThreadBinding("test", "v1", "1"), DurableJson.value(null)));
        code(DurableException.Code.INVALID, () -> backend.events(other, snapshot.cursor(), 10));
        assertEquals(snapshot.projection(), backend.snapshot(key).projection());
    }

    @Test
    protected void cancellingUnknownExternalEffectDoesNotClaimItStopped() {
        submit();
        Lease l = claim();
        backend.commit(
                l,
                commit(
                        backend.frame(l),
                        "intent",
                        TurnStatus.WAITING,
                        List.of(tool()),
                        List.of(),
                        List.of()));
        Lease second = claim();
        String id = backend.frame(second).tools().get(0).request().executionId();
        backend.beginDispatch(second, id);
        backend.acceptResult(
                new ThreadKey(key, "main"), id, ExecutionResult.unknown("lost start reply"));
        backend.cancel(new ThreadKey(key, "main"), l.turnId(), "cancel");
        code(
                DurableException.Code.INVALID,
                () ->
                        backend.commit(
                                second,
                                commit(
                                        backend.frame(second),
                                        "cancelled",
                                        TurnStatus.CANCELLED,
                                        List.of(),
                                        List.of(),
                                        List.of())));
        assertEquals(
                TurnStatus.CANCELLING,
                backend.turn(new ThreadKey(key, "main"), l.turnId()).status());
    }

    @Test
    protected void aSecondRunCannotOvertakeAnUnfinishedSessionRun() {
        TurnReceipt first = submit();
        TurnReceipt second =
                backend.submit(new ThreadKey(key, "main"), "second", DurableJson.value("second"));
        Lease owner = claim();
        assertEquals(first.turnId(), owner.turnId());
        assertTrue(
                backend.claim(new ThreadKey(key, "main"), "other", Duration.ofSeconds(1))
                        .isEmpty());
        backend.commit(
                owner,
                commit(
                        backend.frame(owner),
                        "done",
                        TurnStatus.SUCCEEDED,
                        List.of(),
                        List.of(),
                        List.of()));
        assertEquals(second.turnId(), claim().turnId());
    }

    @Test
    protected void runtimeRecoversOriginalToolAfterLostStartReplyWithoutRepeatingIt() {
        AtomicInteger starts = new AtomicInteger();
        Executor remote =
                new Executor() {
                    public Capabilities capabilities() {
                        return new Capabilities(false, true, true);
                    }

                    public ExecutionResult startExecution(ExecutionRequest r) {
                        starts.incrementAndGet();
                        throw new IllegalStateException("reply lost");
                    }

                    public ExecutionResult query(ExecutionRequest r, String handle) {
                        return new ExecutionResult(
                                ExecutionState.SUCCEEDED, "op", DurableJson.value("ok"), null);
                    }

                    public ExecutionResult cancel(ExecutionRequest r, String handle) {
                        return query(r, handle);
                    }
                };
        HarnessAdapter adapter =
                new HarnessAdapter() {
                    public Capabilities capabilities() {
                        return new Capabilities(
                                "1",
                                RecoveryBoundary.TOOL_BOUNDARY,
                                EventDelivery.COMPLETED,
                                true,
                                List.of("text"));
                    }

                    public void execute(HarnessContext context) {
                        Frame frame = context.frame();
                        if (frame.tools().isEmpty())
                            context.commit(
                                    commit(
                                            frame,
                                            "intent",
                                            TurnStatus.WAITING,
                                            List.of(tool()),
                                            List.of(),
                                            List.of()));
                        else
                            context.commit(
                                    commit(
                                            frame,
                                            "done",
                                            TurnStatus.SUCCEEDED,
                                            List.of(),
                                            frame.tools().stream()
                                                    .map(t -> t.request().executionId())
                                                    .toList(),
                                            List.of()));
                    }
                };
        TurnReceipt receipt = submit();
        try (DurableRuntime runtime =
                DurableRuntime.builder()
                        .backend(backend)
                        .harness("test", "v1", adapter)
                        .executor("remote", remote)
                        .onError((k, e) -> fail(e))
                        .build()) {
            runtime.tick(new ThreadKey(key, "main"));
            runtime.tick(new ThreadKey(key, "main"));
        }
        assertEquals(1, starts.get());
        clock.addAndGet(2000);
        try (DurableRuntime replacement =
                DurableRuntime.builder()
                        .backend(backend)
                        .harness("test", "v1", adapter)
                        .executor("remote", remote)
                        .onError((k, e) -> fail(e))
                        .build()) {
            replacement.tick(new ThreadKey(key, "main"));
        }
        assertEquals(1, starts.get());
        assertEquals(
                TurnStatus.SUCCEEDED,
                backend.turn(new ThreadKey(key, "main"), receipt.turnId()).status());
    }
}
