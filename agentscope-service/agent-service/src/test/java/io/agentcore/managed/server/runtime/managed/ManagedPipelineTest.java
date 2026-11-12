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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.shutdown.AgentShuttingDownException;
import io.agentscope.core.state.AgentState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.rocketmq.client.java.impl.consumer.ManagedLitePushConsumer.Ack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Push callback lifetime, durable notification, Harness capacity and Session FIFO together. */
@Timeout(20)
class ManagedPipelineTest {
    @TempDir private Path managedCheckpointRoot;

    @TempDir Path blobs;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ManagedScope scope = new ManagedScope("t", "n", "s");
    private final Duration ttl = Duration.ofSeconds(2);
    private final InMemoryManagedMessageBus transport =
            new InMemoryManagedMessageBus(() -> System.nanoTime() / 1_000_000, 8);
    private final InMemoryManagedLeaseStore leases =
            new InMemoryManagedLeaseStore(() -> System.nanoTime() / 1_000_000);
    private final List<AutoCloseable> closing = new ArrayList<>();
    private final ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
    private DriverManagerDataSource source;
    private ManagedAdmissionStore admission;
    private ManagedJournalStore journal;
    private ManagedBatchCodec codec;
    private ManagedRuntimeWriter writer;
    private ManagedTurnPublisher publisher;
    private ScheduledExecutorService renewal;

    @BeforeEach
    void setup() {
        source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:pipeline-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        admission = ProjectionTestSupport.admission(source, mapper);
        journal =
                ProjectionTestSupport.journal(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        codec = new ManagedBatchCodec(mapper, blobs);
        journal.transport().configure(transport, codec);
        writer = ProjectionTestSupport.writer(journal, source, transport, codec, mapper);
        publisher = new ManagedTurnPublisher(admission, transport, leases, mapper, ttl);
        renewal = Executors.newScheduledThreadPool(2);
        admission.createSession(scope, "{}");
    }

    @AfterEach
    void stop() throws Exception {
        for (var item : closing) item.close();
        renewal.shutdownNow();
        transport.close();
    }

    private ManagedHarnessRuntimeTurns harnessRuntime() {
        var harnessRuntime =
                new ManagedHarnessRuntimeTurns(
                        transport,
                        leases,
                        admission,
                        journal,
                        writer,
                        mapper,
                        renewal,
                        ttl,
                        Duration.ofMillis(200),
                        1,
                        Duration.ofMillis(500));
        closing.add(harnessRuntime);
        return harnessRuntime;
    }

    private void harnessRuntimes(ManagedHarnessRuntimeTurns.Runner runner) {
        var harnessRuntime = harnessRuntime();
        closing.add(
                transport.subscribe(
                        Channel.TURNS,
                        8,
                        d -> harnessRuntime.consume(d, runner),
                        (d, e) -> errors.add(e)));
        closing.add(
                transport.subscribe(
                        Channel.TURN_RESUMES,
                        8,
                        d -> harnessRuntime.consume(d, runner),
                        (d, e) -> errors.add(e)));
    }

    private ManagedHarnessRuntimeToolEvents toolWorker() {
        return new ManagedHarnessRuntimeToolEvents(codec, journal, r -> {});
    }

    private void events(CountDownLatch gate) {
        var toolWorker = toolWorker();
        for (var channel : List.of(Channel.TOOL_EVENTS))
            closing.add(
                    transport.subscribe(
                            channel,
                            4,
                            d -> {
                                assertTrue(gate.await(10, TimeUnit.SECONDS));
                                toolWorker.consume(d);
                            },
                            (d, e) -> errors.add(e)));
    }

    @Test
    void wrongDepthCannotConfirmPublicationOrExecute() throws Exception {
        var turn = admission.acceptTurn(scope, "user", "misrouted", "{}");
        var reserved = admission.reservePublication(scope, turn.id());
        var message =
                new ManagedMessageBus.Message(
                        turn.id(),
                        scope.messageGroup(),
                        mapper.writeValueAsBytes(
                                new ManagedTurnPublisher.Command(
                                        scope, turn.id(), reserved.publicationSequence())),
                        1);
        var delivery =
                new ManagedPushDelivery(
                        "wrong-depth",
                        "consumer",
                        Channel.TURNS,
                        "turns_d1",
                        "brain_d1",
                        "broker",
                        1,
                        message);
        var activeBus = mock(ManagedMessageBus.class);
        when(activeBus.active(delivery)).thenReturn(true);
        var wrongLaneHarnessRuntime =
                new ManagedHarnessRuntimeTurns(
                        activeBus,
                        leases,
                        admission,
                        journal,
                        writer,
                        mapper,
                        renewal,
                        ttl,
                        Duration.ofMillis(200),
                        1,
                        Duration.ofMillis(500));
        closing.add(wrongLaneHarnessRuntime);
        var failure =
                assertThrows(
                        ManagedStateException.class,
                        () ->
                                wrongLaneHarnessRuntime.consume(
                                        delivery,
                                        e -> {
                                            throw new AssertionError("Must not execute");
                                        }));
        assertEquals("turn_depth_mismatch", failure.code());
        assertEquals("pending", admission.turn(scope, turn.id()).deliveryStatus());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fullHarnessCapacityStopsResumeReceivesWithoutFailedDeliveries(boolean resume)
            throws Exception {
        var admitted = new LinkedHashMap<ManagedScope, String>();
        for (int index = 0; index < 5; index++) {
            var session = new ManagedScope("t", "n", "capacity-" + index);
            admission.createSession(session, "{}");
            if (resume) admission.queue().enable(session);
            admitted.put(session, admission.acceptTurn(session, "user", "turn", "{}").id());
        }
        var harnessRuntime =
                new ManagedHarnessRuntimeTurns(
                        transport,
                        leases,
                        admission,
                        journal,
                        writer,
                        mapper,
                        renewal,
                        ttl,
                        Duration.ofMillis(200),
                        1,
                        Duration.ofMillis(500));
        closing.add(harnessRuntime);
        if (resume) {
            for (var entry : admitted.entrySet()) {
                String task =
                        "tool_"
                                + ManagedScope.fingerprint(
                                        entry.getKey().messageGroup(), entry.getValue(), "call");
                harnessRuntime.advance(
                        entry.getKey(),
                        entry.getValue(),
                        execution -> {
                            checkpoint(execution, List.of(new ToolIntent("call", "exec", "{}")));
                            execution.suspend(task);
                        });
                journal.claimTool(entry.getKey(), task, "hands");
                ManagedPushTestSupport.commitToolResult(
                        journal,
                        new ManagedToolResult(entry.getKey(), task, task, "succeeded", "{}"));
            }
        }
        events(new CountDownLatch(0));
        var entered = new CountDownLatch(admitted.size());
        var firstRunning = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var starts = new AtomicInteger();
        var attempts = new ConcurrentLinkedQueue<Integer>();
        closing.add(
                transport.subscribe(
                        resume ? Channel.TURN_RESUMES : Channel.TURNS,
                        admitted.size(),
                        new ManagedMessageBus.Listener() {
                            @Override
                            public ManagedReceiveCapacity receiveCapacity(Channel channel) {
                                return resume ? harnessRuntime.receiveCapacity() : null;
                            }

                            @Override
                            public void consume(ManagedPushDelivery delivery) throws Exception {
                                attempts.add(delivery.attempt());
                                entered.countDown();
                                harnessRuntime.consume(
                                        delivery,
                                        execution -> {
                                            if (starts.incrementAndGet() == 1) {
                                                firstRunning.countDown();
                                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                            }
                                            if (resume)
                                                execution.toolCompleted(
                                                        "tool_"
                                                                + ManagedScope.fingerprint(
                                                                        execution
                                                                                .scope()
                                                                                .messageGroup(),
                                                                        execution.turnId(),
                                                                        "call"));
                                            checkpoint(execution, List.of());
                                            execution.closeInputs();
                                            execution.finish("succeeded");
                                        });
                            }
                        },
                        (d, e) -> errors.add(e)));
        try {
            if (resume)
                new ManagedResumePublisher(journal.suspensions(), transport, leases, mapper)
                        .retry(8);
            else admitted.forEach(publisher::publish);
            assertTrue(firstRunning.await(5, TimeUnit.SECONDS));
            if (resume) {
                assertEquals(
                        admitted.size() - 1,
                        entered.getCount(),
                        "Other messages stay at Broker while execution capacity is occupied");
            } else assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(errors.isEmpty(), errors::toString);
        } finally {
            release.countDown();
        }
        await(
                () ->
                        admitted.entrySet().stream()
                                .allMatch(
                                        entry ->
                                                "succeeded"
                                                        .equals(
                                                                admission
                                                                        .turn(
                                                                                entry.getKey(),
                                                                                entry.getValue())
                                                                        .status())));
        assertEquals(admitted.size(), starts.get());
        assertTrue(attempts.size() >= admitted.size());
        assertTrue(attempts.stream().allMatch(attempt -> attempt == 1));
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void shuttingDownBrainSuspendsWithoutConsumingBrokerRetriesAndAnotherBrainRecovers()
            throws Exception {
        String turn = publishTurn();
        var retiring = harnessRuntime();
        var replacement = harnessRuntime();
        var invocations = new AtomicInteger();
        var attempts = new ConcurrentLinkedQueue<Integer>();
        var retiredCallbacks = new AtomicInteger();
        var subscription =
                transport.subscribe(
                        Channel.TURNS,
                        1,
                        delivery -> {
                            attempts.add(delivery.attempt());
                            if (invocations.incrementAndGet() <= 10) {
                                try {
                                    retiring.consume(
                                            delivery,
                                            execution -> {
                                                checkpoint(execution, List.of());
                                                throw new IllegalStateException(
                                                        new AgentShuttingDownException());
                                            });
                                } finally {
                                    retiredCallbacks.incrementAndGet();
                                }
                            } else {
                                replacement.consume(
                                        delivery,
                                        execution -> {
                                            assertTrue(execution.head().sequence() > 0);
                                            execution.closeInputs();
                                            execution.finish("succeeded");
                                        });
                            }
                        },
                        (delivery, failure) -> errors.add(failure));
        closing.add(subscription);
        await(() -> "succeeded".equals(admission.turn(scope, turn).status()));
        assertEquals(10, retiredCallbacks.get());
        assertEquals(11, invocations.get());
        assertTrue(attempts.stream().allMatch(attempt -> attempt == 1));
        assertTrue(errors.isEmpty(), errors::toString);
        assertEquals(1, admission.session(scope).settledSequence());
    }

    @Test
    void harnessRuntimeCommitsAndAdvancesFifoWithoutAToolWorkerRuntimeConsumer() throws Exception {
        String first = publishTurn(), second = publishTurn();
        var executions = new ConcurrentLinkedQueue<String>();
        harnessRuntimes(
                execution -> {
                    executions.add(execution.turnId());
                    checkpoint(execution, List.of());
                    execution.closeInputs();
                    execution.finish("succeeded");
                });
        await(() -> "succeeded".equals(admission.turn(scope, second).status()));
        assertEquals(List.of(first, second), List.copyOf(executions));
        assertEquals(2, admission.session(scope).settledSequence());
        assertTrue(journal.head(scope).sequence() >= 4);
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void suspendedOriginalRetainsFifoWhileIndependentResumeUsesReleasedHarnessSlot()
            throws Exception {
        String first = publishTurn(), second = publishTurn();
        String task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), first, "call");
        var order = new ConcurrentLinkedQueue<String>();
        var starts = new AtomicInteger();
        events(new CountDownLatch(0));
        harnessRuntimes(
                execution -> {
                    order.add(execution.turnId() + ":" + execution.epoch());
                    if (first.equals(execution.turnId()) && starts.incrementAndGet() == 1) {
                        checkpoint(
                                execution, List.of(new ToolIntent("call", "sandbox.exec", "{}")));
                        execution.suspend(task);
                    } else {
                        checkpoint(execution, List.of());
                        execution.toolCompleted(task);
                        execution.closeInputs();
                        execution.finish("succeeded");
                    }
                });
        await(() -> "waiting_tool".equals(admission.turn(scope, first).status()));
        var acks =
                new ManagedTurnAcknowledgements(
                        new SessionTurnAckLedger(source, mapper),
                        "turns",
                        "brain",
                        receipt -> Ack.ACKNOWLEDGED);
        acks.suspended(
                new ManagedTurnPublisher.Command(scope, first, 1),
                new RocketMqTurnReceipt(
                        "broker", "turns", scope.messageGroup(), "handle", "queue", 0, 0, 1),
                Duration.ofSeconds(60));
        assertEquals(0, acks.retry(10), "Tool wait must retain FIFO head");
        assertEquals(0, admission.session(scope).settledSequence());
        assertFalse(order.stream().anyMatch(s -> s.startsWith(second + ":")));
        journal.claimTool(scope, task, "hands");
        new ManagedToolResultPublisher(journal, transport, codec)
                .publish(new ManagedToolResult(scope, task, task, "succeeded", "{}"));
        await(() -> !journal.suspensions().pending(8).isEmpty());
        new ManagedResumePublisher(journal.suspensions(), transport, leases, mapper).retry(8);
        await(() -> "succeeded".equals(admission.turn(scope, second).status()));
        assertEquals(
                1, acks.retry(10), "Real terminal commit must make the captured receipt ready");
        assertEquals(List.of(first + ":1", first + ":2", second + ":3"), List.copyOf(order));
        assertEquals(2, admission.session(scope).settledSequence());
    }

    @Test
    void nativeCommitIsImmediatelyDurableAndDuplicateBatchIsIdempotent() throws Exception {
        String turn = publishTurn();
        try (var originals = new ManagedPushTestSupport.Probe(transport, Channel.TURNS, 1);
                var events = new ManagedPushTestSupport.Probe(transport, Channel.TOOL_EVENTS, 1)) {
            var invocation = originals.next();
            try (var guard =
                    ManagedExecutionGuard.acquire(
                                    scope,
                                    transport,
                                    leases,
                                    invocation.delivery(),
                                    ttl,
                                    System::nanoTime,
                                    () -> {})
                            .orElseThrow()) {
                long epoch = admission.activate(scope, turn, 1, guard.token());
                var execution =
                        new ManagedTurnExecution(
                                scope, turn, epoch, guard, admission, journal, writer);
                var first = execution.log();
                var owner = first.acquire("writer", ttl);
                var batch = List.of(checkpoint(turn, 1));
                first.commit(owner, "first", 0, batch);
                first.commit(owner, "first", 0, batch);
                assertEquals(1, journal.head(scope).sequence());
                first.release(owner);
                var fresh = execution.log();
                var second = fresh.acquire("writer2", ttl);
                fresh.commit(second, "second", 1, List.of(checkpoint(turn, 2)));
                assertEquals(2, journal.head(scope).sequence());
                assertEquals(
                        List.of(1L, 2L),
                        first.readAfter(0, 10).stream().map(SessionEvent::seq).toList());
                assertEquals(2, journal.recoveryEvents(scope, 0, 100).size());
                assertNull(events.poll(Duration.ofMillis(50)));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unconfirmedCommitStopsItsGuardAndSuccessorRestoresOnlyTheDurablePrefix(boolean committed)
            throws Exception {
        String turn = publishTurn();
        try (var originals = new ManagedPushTestSupport.Probe(transport, Channel.TURNS, 1)) {
            var invocation = originals.next();
            try (var guard =
                    ManagedExecutionGuard.acquire(
                                    scope,
                                    transport,
                                    leases,
                                    invocation.delivery(),
                                    ttl,
                                    System::nanoTime,
                                    () -> {})
                            .orElseThrow()) {
                long epoch = admission.activate(scope, turn, 1, guard.token());
                var uncertain = spy(writer);
                doAnswer(
                                call -> {
                                    if (committed) call.callRealMethod();
                                    throw new ManagedTransportException(
                                            "Commit response unavailable");
                                })
                        .when(uncertain)
                        .commit(any(), any());
                var execution =
                        new ManagedTurnExecution(
                                scope, turn, epoch, guard, admission, journal, uncertain);
                var log = execution.log();
                var owner = log.acquire("writer", ttl);
                assertThrows(
                        RuntimeException.class,
                        () -> log.commit(owner, "uncertain", 0, List.of(checkpoint(turn, 1))));
                assertThrows(RuntimeException.class, guard::check);
                assertThrows(
                        RuntimeException.class,
                        () -> log.commit(owner, "later", 1, List.of(checkpoint(turn, 2))));
                assertEquals(committed ? 1 : 0, journal.head(scope).sequence());
                assertEquals(committed ? 1 : 0, journal.recoveryEvents(scope, 0, 10).size());
            }
            try (var successor =
                    ManagedExecutionGuard.acquire(
                                    scope,
                                    transport,
                                    leases,
                                    invocation.delivery(),
                                    ttl,
                                    System::nanoTime,
                                    () -> {})
                            .orElseThrow()) {
                long epoch = admission.activate(scope, turn, 1, successor.token());
                assertEquals(2, epoch);
                var execution =
                        new ManagedTurnExecution(
                                scope, turn, epoch, successor, admission, journal, writer);
                var recovered = execution.log();
                long sequence = committed ? 1 : 0;
                assertEquals(sequence, recovered.head().seq());
                assertEquals(sequence, recovered.readAfter(0, 10).size());
                var owner = recovered.acquire("replacement", ttl);
                recovered.commit(
                        owner, "recovered", sequence, List.of(checkpoint(turn, sequence + 1)));
                assertEquals(sequence + 1, journal.head(scope).sequence());
            }
        }
    }

    @Test
    void brokerAcceptanceWithLostSendReplyBlocksNextPublicationUntilConsumerObservation()
            throws Exception {
        String first = admission.acceptTurn(scope, "u", "first", "{}").id();
        String second = admission.acceptTurn(scope, "u", "second", "{}").id();
        var ambiguous = spy(transport);
        doAnswer(
                        call -> {
                            transport.publish(call.getArgument(0), call.getArgument(1));
                            throw new ManagedTransportException("Explicit lost send reply");
                        })
                .when(ambiguous)
                .publish(any(), any());
        assertThrows(
                ManagedTransportException.class,
                () ->
                        new ManagedTurnPublisher(admission, ambiguous, leases, mapper, ttl)
                                .publish(scope, first));
        assertEquals(
                "publication_pending",
                assertThrows(ManagedStateException.class, () -> publisher.publish(scope, second))
                        .code());
        try (var originals = new ManagedPushTestSupport.Probe(transport, Channel.TURNS, 1)) {
            var firstDelivery = originals.next();
            admission.confirmPublication(scope, first, firstDelivery.delivery().brokerMessageId());
            assertTrue(publisher.publish(scope, second));
            assertEquals(1, admission.turn(scope, first).publicationSequence());
            assertEquals(2, admission.turn(scope, second).publicationSequence());
            assertNull(originals.poll(Duration.ofMillis(50)));
        }
    }

    @Test
    void failedCheckpointStoragePublishesNothingAndInvalidatesTheAttempt() throws Exception {
        Files.writeString(managedCheckpointRoot.resolve(scope.messageGroup()), "not a directory");
        try (var originals = new ManagedPushTestSupport.Probe(transport, Channel.TURNS, 1);
                var runtime = new ManagedPushTestSupport.Probe(transport, Channel.TOOL_EVENTS, 1)) {
            String turn = publishTurn();
            var invocation = originals.next();
            assertThrows(
                    ManagedTransportException.class,
                    () ->
                            harnessRuntime()
                                    .consume(
                                            invocation.delivery(),
                                            execution -> {
                                                assertThrows(
                                                        ManagedTransportException.class,
                                                        () -> checkpoint(execution, List.of()));
                                                assertThrows(
                                                        ManagedTransportException.class,
                                                        execution::check);
                                                throw new ManagedTransportException(
                                                        "Unconfirmed checkpoint ends this attempt");
                                            }));
            assertNull(runtime.poll(Duration.ofMillis(50)));
            assertEquals(0, journal.head(scope).sequence());
            assertEquals("preparing", admission.turn(scope, turn).status());
            invocation.fail();
        }
    }

    @Test
    void largeCheckpointCommitsItsManifestAndRejectsTamperedObjects() throws Exception {
        String turn = publishTurn();
        admission.activate(scope, turn, 1, "token");
        var event =
                new SessionEvent(
                        1,
                        "large",
                        1,
                        1,
                        "state/checkpoint",
                        "run",
                        turn,
                        true,
                        mapper.writeValueAsString(
                                Map.of(
                                        "projectorVersion",
                                        1,
                                        "stateJson",
                                        AgentState.builder()
                                                .summary("x".repeat(600_000))
                                                .build()
                                                .toJson(),
                                        "reason",
                                        "test")));
        var batch =
                new ManagedRuntimeBatch(
                        scope,
                        turn,
                        1,
                        "token",
                        "owner",
                        "blob",
                        0,
                        List.of(event),
                        List.of(),
                        List.of(),
                        null);
        assertThrows(
                ManagedStateException.class,
                () ->
                        AgentScopePreparation.commit(
                                journal, new SessionCommitTransactions(journal), batch));
        var wire = AgentScopeCheckpoints.prepare(journal.objects(), batch);
        AgentScopeCheckpoints.verify(journal.objects(), wire);
        assertTrue(wire.events().get(0).payloadJson().length() < 16 * 1024);
        assertEquals(
                "committed",
                AgentScopePreparation.commit(journal, new SessionCommitTransactions(journal), wire)
                        .outcome());
        Path file;
        try (var paths = Files.walk(managedCheckpointRoot)) {
            file =
                    paths.filter(
                                    path ->
                                            path.getParent()
                                                            .getFileName()
                                                            .toString()
                                                            .equals("checkpoint")
                                                    && Files.isRegularFile(path))
                            .findFirst()
                            .orElseThrow();
        }
        Files.writeString(file, "tampered");
        assertThrows(
                ManagedStateException.class,
                () -> AgentScopeCheckpoints.verify(journal.objects(), wire));
    }

    @Test
    void suspendedHeadReleasesCallbackCapacityWithoutAdvancingEpochOrT2() throws Exception {
        String first = publishTurn(), second = publishTurn();
        var other = new ManagedScope("t", "n", "other");
        admission.createSession(other, "{}");
        var otherTurn = admission.acceptTurn(other, "user", "other", "{}").id();
        var attempts = new ConcurrentLinkedQueue<Integer>();
        var executions = new ConcurrentLinkedQueue<String>();
        var harnessRuntime = harnessRuntime();
        closing.add(
                transport.subscribe(
                        Channel.TURNS,
                        1,
                        delivery -> {
                            attempts.add(delivery.attempt());
                            harnessRuntime.consume(
                                    delivery,
                                    execution -> {
                                        executions.add(execution.turnId());
                                        checkpoint(
                                                execution,
                                                List.of(new ToolIntent("call", "exec", "{}")));
                                        String task =
                                                "tool_"
                                                        + ManagedScope.fingerprint(
                                                                execution.scope().messageGroup(),
                                                                execution.turnId(),
                                                                "call");
                                        execution.suspend(task);
                                    });
                        },
                        (d, e) -> errors.add(e)));
        await(() -> "waiting_tool".equals(admission.turn(scope, first).status()));
        long sequence = journal.head(scope).sequence();
        publisher.publish(other, otherTurn);
        await(() -> executions.contains(otherTurn));
        await(() -> attempts.size() >= 6);
        assertEquals(1, admission.turn(scope, first).executionEpoch());
        assertEquals(sequence, journal.head(scope).sequence());
        assertFalse(executions.contains(second));
        assertEquals(1, executions.stream().filter(first::equals).count());
        assertTrue(attempts.stream().allMatch(attempt -> attempt == 1));
        assertTrue(transport.deadLetters().isEmpty());
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void fastResumeDefersDurablyUntilPreviousOwnerReleasesAndDuplicateDoesNotRunAgain()
            throws Exception {
        String turn = publishTurn();
        String task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "call");
        var starts = new AtomicInteger();
        var harnessRuntime = harnessRuntime();
        try (var originals = new ManagedPushTestSupport.Probe(transport, Channel.TURNS, 1);
                var resumes =
                        new ManagedPushTestSupport.Probe(transport, Channel.TURN_RESUMES, 1)) {
            var original = originals.next();
            assertThrows(
                    ManagedDeliverySuspended.class,
                    () ->
                            harnessRuntime.consume(
                                    original.delivery(),
                                    execution -> {
                                        starts.incrementAndGet();
                                        checkpoint(
                                                execution,
                                                List.of(new ToolIntent("call", "exec", "{}")));
                                        execution.suspend(task);
                                    }));
            journal.claimTool(scope, task, "hands");
            ManagedPushTestSupport.commitToolResult(
                    journal, new ManagedToolResult(scope, task, task, "succeeded", "{}"));
            var previousOwner = leases.acquire(scope.key("execution"), ttl).orElseThrow();
            new ManagedResumePublisher(journal.suspensions(), transport, leases, mapper).retry(8);
            var resumed = resumes.next();
            ManagedHarnessRuntimeTurns.Runner finish =
                    execution -> {
                        starts.incrementAndGet();
                        execution.toolCompleted(task);
                        checkpoint(execution, List.of());
                        execution.closeInputs();
                        execution.finish("succeeded");
                    };
            harnessRuntime.consume(resumed.delivery(), finish);
            assertTrue(journal.suspensions().pending(8).isEmpty(), "Retry is durably delayed");
            resumed.succeed();
            resumed.delivery().handoff().await(Duration.ofSeconds(5));
            assertEquals(1, admission.turn(scope, turn).executionEpoch());
            leases.release(previousOwner);
            new JdbcTemplate(source)
                    .update(
                            "UPDATE ma.turn_resume SET retry_at=0 WHERE scope_key=?",
                            scope.messageGroup());
            new ManagedResumePublisher(journal.suspensions(), transport, leases, mapper).retry(8);
            var retried = resumes.next();
            assertEquals(resumed.delivery().message().id(), retried.delivery().message().id());
            harnessRuntime.consume(retried.delivery(), finish);
            harnessRuntime.consume(
                    retried.delivery(),
                    execution -> {
                        throw new AssertionError("Duplicate Resume ran");
                    });
            retried.succeed();
            retried.delivery().handoff().await(Duration.ofSeconds(5));
            await(() -> "succeeded".equals(admission.turn(scope, turn).status()));
            harnessRuntime.consume(
                    original.delivery(),
                    execution -> {
                        throw new AssertionError("Terminal Turn ran");
                    });
            assertEquals(2, starts.get());
            assertEquals(2, admission.turn(scope, turn).executionEpoch());
            original.succeed();
        }
    }

    @Test
    void repeatedSuspensionAndDuplicateOldResumesKeepSuccessorInMq() throws Exception {
        String first = publishTurn(), second = publishTurn();
        var starts = new AtomicInteger();
        var executed = new ConcurrentLinkedQueue<String>();
        harnessRuntimes(
                execution -> {
                    executed.add(execution.turnId());
                    if (first.equals(execution.turnId())) {
                        int round = starts.incrementAndGet();
                        if (round > 1)
                            execution.toolCompleted(
                                    "tool_"
                                            + ManagedScope.fingerprint(
                                                    scope.messageGroup(),
                                                    first,
                                                    "call" + (round - 1)));
                        if (round <= 2) {
                            String call = "call" + round;
                            checkpoint(execution, List.of(new ToolIntent(call, "exec", "{}")));
                            execution.suspend(
                                    "tool_"
                                            + ManagedScope.fingerprint(
                                                    scope.messageGroup(), first, call));
                            return;
                        }
                    }
                    checkpoint(execution, List.of());
                    execution.closeInputs();
                    execution.finish("succeeded");
                });
        for (int round = 1; round <= 2; round++) {
            final int expected = round;
            await(
                    () ->
                            starts.get() == expected
                                    && "waiting_tool"
                                            .equals(admission.turn(scope, first).status()));
            assertFalse(executed.contains(second));
            String task =
                    "tool_" + ManagedScope.fingerprint(scope.messageGroup(), first, "call" + round);
            journal.claimTool(scope, task, "hands");
            ManagedPushTestSupport.commitToolResult(
                    journal, new ManagedToolResult(scope, task, task, "succeeded", "{}"));
            var resume = journal.suspensions().pending(8).get(0);
            new ManagedResumePublisher(journal.suspensions(), transport, leases, mapper).retry(8);
            transport.publish(
                    Channel.TURN_RESUMES,
                    new ManagedMessageBus.Message(
                            resume.resumeId(),
                            scope.messageGroup(),
                            mapper.writeValueAsBytes(resume)));
        }
        await(() -> "succeeded".equals(admission.turn(scope, second).status()));
        assertEquals(3, starts.get());
        assertEquals(List.of(first, first, first, second), List.copyOf(executed));
        assertEquals(4, admission.turn(scope, second).executionEpoch());
        assertTrue(errors.isEmpty(), errors::toString);
    }

    private String publishTurn() {
        String turn = admission.acceptTurn(scope, "user", UUID.randomUUID().toString(), "{}").id();
        publisher.publish(scope, turn);
        return turn;
    }

    @Test
    void executePublicationOutageSuspendsTheOriginalTurnWithoutConsumingFailureAttempts()
            throws Exception {
        var attempts = new ConcurrentLinkedQueue<Integer>();
        var sends = new AtomicInteger();
        var unavailable = mock(ManagedMessageBus.class);
        when(unavailable.publish(any(), any()))
                .thenAnswer(
                        invocation -> {
                            if (sends.incrementAndGet() <= 2)
                                throw new ManagedTransportException("MQ unavailable");
                            return transport.publish(
                                    invocation.getArgument(0), invocation.getArgument(1));
                        });
        journal.transport().configure(unavailable, codec);
        writer = ProjectionTestSupport.writer(journal, source, unavailable, codec, mapper);
        String turn = publishTurn();
        String task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "call");
        var runtime = harnessRuntime();
        closing.add(
                transport.subscribe(
                        Channel.TURNS,
                        2,
                        delivery -> {
                            attempts.add(delivery.attempt());
                            runtime.consume(
                                    delivery,
                                    execution -> {
                                        checkpoint(
                                                execution,
                                                List.of(
                                                        new ToolIntent(
                                                                "call", "sandbox.exec", "{}")));
                                        execution.suspend(task);
                                    });
                        },
                        (delivery, failure) -> errors.add(failure)));
        await(() -> "waiting_tool".equals(admission.turn(scope, turn).status()));
        assertEquals(1, sends.get());
        var pendingPublisher = new ManagedToolCommandPublisher(source, unavailable, codec, mapper);
        for (int i = 0; i < 2; i++) {
            new JdbcTemplate(source).update("UPDATE ma.tool_command_outbox SET next_attempt_at=0");
            pendingPublisher.publishCommands(16);
        }
        assertEquals(3, sends.get());
        assertEquals(1, attempts.size());
        assertTrue(attempts.stream().allMatch(attempt -> attempt == 1));
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void databaseQueueUsesDurableWakeupAndSkipsWithdrawnHead() throws Exception {
        admission.queue().enable(scope);
        String first = admission.acceptTurn(scope, "actor", "first", "{}").id();
        String second = admission.acceptTurn(scope, "actor", "second", "{}").id();
        admission
                .protocol()
                .acceptQueue(
                        scope,
                        "actor",
                        "withdraw",
                        new ManagedSessionQueue.Mutation("queue_" + first, 1L, null, false),
                        () -> {});
        publisher.publish(scope, second);
        var runtime = harnessRuntime();
        var starts = new AtomicInteger();
        try (var notifications =
                new ManagedPushTestSupport.Probe(transport, Channel.SESSION_READY, 1)) {
            var notification = notifications.next();
            var ready =
                    mapper.readValue(
                            notification.delivery().message().body(),
                            ManagedSessionQueue.Ready.class);
            admission.queue().observe(ready);
            notification.succeed();
            // No MQ Turn callback remains; a fresh worker can recover solely from PostgreSQL.
            var dispatch = admission.queue().acquire(scope);
            runtime.advance(
                    scope,
                    admission.queue().candidate(scope),
                    execution -> {
                        starts.incrementAndGet();
                        checkpoint(execution, List.of());
                        execution.closeInputs();
                        execution.finish("succeeded");
                    });
            admission.queue().release(dispatch);
        }
        assertEquals(1, starts.get());
        assertEquals("cancelled", admission.turn(scope, first).status());
        assertEquals("succeeded", admission.turn(scope, second).status());
        assertEquals("consumed", admission.queue().entry(scope, "queue_" + second).get("state"));
        assertNull(admission.session(scope).currentTurnId());
    }

    @Test
    void databaseQueueRetainsSuspendedOwnerAndResumesOnAnotherRuntime() throws Exception {
        admission.queue().enable(scope);
        String first = admission.acceptTurn(scope, "actor", "first", "{}").id();
        String second = admission.acceptTurn(scope, "actor", "second", "{}").id();
        String task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), first, "call");
        var original = harnessRuntime();
        original.advance(
                scope,
                first,
                execution -> {
                    checkpoint(execution, List.of(new ToolIntent("call", "exec", "{}")));
                    execution.suspend(task);
                });
        assertEquals("waiting_tool", admission.turn(scope, first).status());
        assertNull(admission.queue().candidate(scope));
        original.advance(
                scope,
                second,
                execution -> {
                    throw new AssertionError("Successor bypassed suspended owner");
                });
        original.close();
        journal.claimTool(scope, task, "hands");
        ManagedPushTestSupport.commitToolResult(
                journal, new ManagedToolResult(scope, task, task, "succeeded", "{}"));
        var replacement = harnessRuntime();
        try (var resumes = new ManagedPushTestSupport.Probe(transport, Channel.TURN_RESUMES, 1)) {
            new ManagedResumePublisher(journal.suspensions(), transport, leases, mapper).retry(8);
            var resumed = resumes.next();
            replacement.consume(
                    resumed.delivery(),
                    execution -> {
                        assertTrue(execution.head().sequence() > 0);
                        execution.toolCompleted(task);
                        checkpoint(execution, List.of());
                        execution.closeInputs();
                        execution.finish("succeeded");
                    });
            resumed.succeed();
            resumed.delivery().handoff().await(Duration.ofSeconds(5));
        }
        await(() -> "succeeded".equals(admission.turn(scope, first).status()));
        assertEquals(second, admission.queue().candidate(scope));
        replacement.advance(
                scope,
                second,
                execution -> {
                    checkpoint(execution, List.of());
                    execution.closeInputs();
                    execution.finish("succeeded");
                });
        assertEquals(3, admission.turn(scope, second).executionEpoch());
        assertEquals("consumed", admission.queue().entry(scope, "queue_" + first).get("state"));
        assertEquals("succeeded", admission.turn(scope, second).status());
    }

    @Test
    void databaseClaimSurvivesCrashBeforeCheckpointAndCannotBecomeEditableAgain() throws Exception {
        admission.queue().enable(scope);
        String turn = admission.acceptTurn(scope, "actor", "crash", "{}").id();
        var original = harnessRuntime();
        assertThrows(
                IllegalStateException.class,
                () ->
                        original.advance(
                                scope,
                                turn,
                                execution -> {
                                    throw new IllegalStateException(
                                            "simulated process failure before checkpoint");
                                }));
        original.close();
        assertEquals("claimed", admission.queue().entry(scope, "queue_" + turn).get("state"));
        assertThrows(
                ManagedStateException.class,
                () ->
                        admission
                                .protocol()
                                .acceptQueue(
                                        scope,
                                        "actor",
                                        "withdraw",
                                        new ManagedSessionQueue.Mutation(
                                                "queue_" + turn, 2L, null, false),
                                        () -> {}));
        var replacement = harnessRuntime();
        replacement.advance(
                scope,
                turn,
                execution -> {
                    assertEquals(2, execution.epoch());
                    checkpoint(execution, List.of());
                    execution.closeInputs();
                    execution.finish("succeeded");
                });
        assertEquals("succeeded", admission.turn(scope, turn).status());
    }

    private void checkpoint(ManagedTurnExecution execution, List<ToolIntent> tools) {
        long seq = execution.head().sequence();
        execution.boundary(
                "state_" + UUID.randomUUID(),
                List.of(checkpoint(execution.turnId(), seq + 1)),
                tools,
                List.of(),
                null);
    }

    private SessionEvent checkpoint(String turn, long seq) {
        return new SessionEvent(
                1,
                "state_" + UUID.randomUUID(),
                seq,
                1,
                "state/checkpoint",
                "run",
                turn,
                true,
                "{\"stateJson\":\"{\\\"context\\\":[]}\",\"reason\":\"test\"}");
    }

    private void await(BooleanSupplier ready) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!ready.getAsBoolean() && System.nanoTime() < until && errors.isEmpty())
            Thread.sleep(5);
        assertTrue(errors.isEmpty(), errors.toString());
        assertTrue(ready.getAsBoolean());
    }
}
