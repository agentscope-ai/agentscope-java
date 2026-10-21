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
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch.Suspension;
import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor.Outcome;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor.OutputPage;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor.State;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.state.AgentState;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Faults cross independent databases; no result can rely on a sender-private runtime row. */
@Timeout(20)
class ManagedHandsReliabilityTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    final ManagedScope scope = new ManagedScope("tenant", "ns", "session");
    ManagedAdmissionStore admission;
    ManagedJournalStore brain;
    ManagedHandsStore hands;
    ManagedBatchCodec codec;
    JdbcTemplate runtime;
    JdbcTemplate physicalRows;
    String turn;
    String task;
    String other;

    @BeforeEach
    void setup() {
        var source = database();
        ManagedAdmissionStore.initialize(source);
        runtime = new JdbcTemplate(source);
        var physical = database();
        ManagedAdmissionStore.initialize(physical);
        physicalRows = new JdbcTemplate(physical);
        admission = new ManagedAdmissionStore(source, mapper);
        admission.createSession(scope, "{}");
        brain = new ManagedJournalStore(source, mapper, new ManagedCheckpointObjects(mapper, root));
        hands = new ManagedHandsStore(physical, mapper);
        codec = new ManagedBatchCodec(mapper, root.resolve("payloads"));
        turn = admission.acceptTurn(scope, "actor", "turn", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "brain");
        boundary(
                List.of(
                        new ToolIntent("call", "sandbox.exec", "{}"),
                        new ToolIntent("other", "sandbox.exec", "{}")),
                null);
        task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "call");
        other = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "other");
        for (String id : List.of(task, other))
            hands.receive(
                    new ManagedToolCommand(
                            scope,
                            "execute_" + id,
                            "execute",
                            brain.tool(scope, id).orElseThrow(),
                            null));
    }

    DriverManagerDataSource database() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:hands-fault-"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    void boundary(List<ToolIntent> tools, Suspension suspension) {
        try {
            long sequence = brain.head(scope).sequence();
            String id = UUID.randomUUID().toString();
            var event =
                    new SessionEvent(
                            1,
                            id,
                            sequence + 1,
                            System.currentTimeMillis(),
                            "state/checkpoint",
                            "run",
                            turn,
                            true,
                            mapper.writeValueAsString(
                                    Map.of("stateJson", AgentState.builder().build().toJson())));
            brain.commit(
                    brain.objects()
                            .prepare(
                                    new ManagedRuntimeBatch(
                                            scope,
                                            turn,
                                            1,
                                            "brain",
                                            "writer",
                                            id,
                                            sequence,
                                            List.of(event),
                                            tools,
                                            List.of(),
                                            null,
                                            suspension)));
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    ManagedToolResult result(String id) {
        return new ManagedToolResult(scope, id, id, "succeeded", "{\"value\":\"original\"}");
    }

    void completed(String id) {
        hands.claimTool(scope, id, "collector");
        hands.stageToolResult(result(id));
        hands.complete(result(id));
    }

    void receive(String id) {
        for (var event : hands.toolEvents().read(scope, id, 0, 256)) brain.commitToolEvent(event);
    }

    @Test
    void repeatingACommittedToolIntentRetainsItsOriginalExecuteCommand() {
        String original =
                runtime.queryForObject(
                        "SELECT payload FROM ma.tool_command_outbox WHERE task_id=?",
                        String.class,
                        task);
        completed(task);
        receive(task);
        boundary(List.of(new ToolIntent("call", "sandbox.exec", "{}", "fresh-ciphertext")), null);
        assertEquals(
                original,
                runtime.queryForObject(
                        "SELECT payload FROM ma.tool_command_outbox WHERE task_id=?",
                        String.class,
                        task));
        assertEquals(
                2,
                runtime.queryForObject(
                        "SELECT COUNT(*) FROM ma.tool_command_outbox", Integer.class));
        assertEquals(2, brain.head(scope).sequence());
        assertEquals(result(task).result(), brain.tool(scope, task).orElseThrow().result());
    }

    @Test
    void replayBeforeExecuteCannotCreateAnUnconfiguredExecution() {
        var value =
                new ManagedJournalStore.ToolTask(
                        "early-replay",
                        turn,
                        "early",
                        "early-replay",
                        "sandbox.exec",
                        "{}",
                        1,
                        "ready",
                        null,
                        null,
                        null);
        hands.receive(new ManagedToolCommand(scope, "replay", "replay", value, null));
        assertTrue(hands.tool(scope, value.id()).isEmpty());
        hands.receive(new ManagedToolCommand(scope, "execute", "execute", value, "sealed"));
        assertEquals("sealed", hands.configuration(scope, value.id()));
    }

    @Test
    void cancellationScanAdvancesPastAlreadyForwardedUnknownTools() {
        admission.acceptInput(
                scope, turn, "actor", "cancel", ManagedInputInbox.Type.CANCEL, null, "{}");
        brain.transport().cancellations(1);
        brain.transport().cancellations(1);
        assertEquals(
                2,
                runtime.queryForObject(
                        "SELECT COUNT(*) FROM ma.tool_command_outbox WHERE kind='cancel'",
                        Integer.class));
    }

    @ParameterizedTest
    @EnumSource(
            value = State.class,
            names = {"UNKNOWN", "NOT_FOUND"})
    void cancellationQueriesTheOriginalUnknownExecutionWithoutRestarting(State observation) {
        var starts = new AtomicInteger();
        var cancels = new AtomicInteger();
        var old = hands.claimTool(scope, task, "old").task();
        hands.recordToolHandle(scope, task, "old", task);
        hands.markToolUnknown(scope, task, "old");
        hands.receive(new ManagedToolCommand(scope, "cancel", "cancel", old, null));
        var provider =
                cancellationProvider(
                        new Outcome(observation, null),
                        new Outcome(State.CANCELLED, "{}"),
                        starts,
                        cancels);
        assertTrue(drive(provider));
        assertEquals(0, starts.get());
        assertEquals(1, cancels.get());
        assertEquals("cancelled", hands.tool(scope, task).orElseThrow().status());
        assertEquals(
                0,
                physicalRows.queryForObject(
                        "SELECT COUNT(*) FROM ma.hands_resume_outbox", Integer.class));
    }

    @Test
    void cancellationWithoutExternalConfirmationKeepsUnknownAndCannotRestart() {
        var starts = new AtomicInteger();
        var cancels = new AtomicInteger();
        var old = hands.claimTool(scope, task, "old").task();
        hands.markToolUnknown(scope, task, "old");
        hands.receive(new ManagedToolCommand(scope, "cancel", "cancel", old, null));
        assertFalse(
                drive(
                        cancellationProvider(
                                new Outcome(State.NOT_FOUND, null),
                                new Outcome(State.UNKNOWN, null),
                                starts,
                                cancels)));
        assertEquals(0, starts.get());
        assertEquals(1, cancels.get());
        assertEquals("unknown", hands.tool(scope, task).orElseThrow().status());
        assertEquals(
                0,
                physicalRows.queryForObject(
                        "SELECT COUNT(*) FROM ma.hands_resume_outbox", Integer.class));
    }

    @Test
    void confirmedOriginalResultWinsOverALaterCancellation() {
        var starts = new AtomicInteger();
        var cancels = new AtomicInteger();
        var old = hands.claimTool(scope, task, "old").task();
        hands.receive(new ManagedToolCommand(scope, "cancel", "cancel", old, null));
        assertTrue(
                drive(
                        cancellationProvider(
                                new Outcome(State.SUCCEEDED, "{}"),
                                new Outcome(State.CANCELLED, "{}"),
                                starts,
                                cancels)));
        assertEquals(0, starts.get());
        assertEquals(0, cancels.get());
        assertEquals("succeeded", hands.tool(scope, task).orElseThrow().status());
    }

    ManagedToolExecutor cancellationProvider(
            Outcome observation,
            Outcome cancellation,
            AtomicInteger starts,
            AtomicInteger cancels) {
        return new ManagedToolExecutor() {
            public boolean idempotentStart() {
                return true;
            }

            public boolean waitForCompletion(ManagedJournalStore.ToolTask value) {
                return false;
            }

            public String start(ManagedScope owner, ManagedJournalStore.ToolTask value) {
                starts.incrementAndGet();
                return value.executionId();
            }

            public Outcome query(ManagedScope owner, ManagedJournalStore.ToolTask value) {
                return observation;
            }

            public Outcome cancel(ManagedScope owner, ManagedJournalStore.ToolTask value) {
                cancels.incrementAndGet();
                return cancellation;
            }
        };
    }

    boolean drive(ManagedToolExecutor provider) {
        var bus = mock(ManagedMessageBus.class);
        when(bus.publish(any(), any())).thenReturn(new ManagedMessageBus.Published("broker"));
        var renewals = Executors.newSingleThreadScheduledExecutor();
        try {
            return new ManagedToolExecutions(
                            new InMemoryManagedLeaseStore(System::currentTimeMillis),
                            new InMemoryManagedInputInbox(System::currentTimeMillis),
                            hands,
                            new ManagedToolResultPublisher(hands, bus, codec),
                            renewals,
                            Duration.ofSeconds(5),
                            Duration.ofSeconds(1),
                            Duration.ofMillis(5))
                    .execute(scope, task, provider);
        } finally {
            renewals.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void brainWaitsForAllResultsAcrossInstancesAndArrivalOrders(int earlyResults) throws Exception {
        var ids = List.of(task, other);
        var replacement =
                new ManagedJournalStore(
                        runtime.getDataSource(),
                        mapper,
                        new ManagedCheckpointObjects(mapper, root));
        for (int i = 0; i < earlyResults; i++) {
            completed(ids.get(i));
            for (var event : hands.toolEvents().read(scope, ids.get(i), 0, 256))
                replacement.commitToolEvent(event);
        }
        assertTrue(brain.suspensions().pending(10).isEmpty());
        boundary(List.of(), Suspension.tools("suspension", ids));
        for (int i = earlyResults; i < ids.size(); i++) {
            assertTrue(brain.suspensions().pending(10).isEmpty());
            completed(ids.get(i));
            for (var event : hands.toolEvents().read(scope, ids.get(i), 0, 256))
                replacement.commitToolEvent(event);
        }
        var ready = brain.suspensions().pending(10);
        assertEquals(1, ready.size());
        receive(task);
        receive(other);
        assertEquals(ready, replacement.suspensions().pending(10));
        assertEquals(
                0,
                physicalRows.queryForObject(
                        "SELECT COUNT(*) FROM ma.hands_resume_outbox", Integer.class));
        var bus = mock(ManagedMessageBus.class);
        var messages = new ArrayList<ManagedMessageBus.Message>();
        when(bus.publish(any(), any()))
                .thenAnswer(
                        call -> {
                            assertEquals(
                                    ManagedMessageBus.Channel.TURN_RESUMES, call.getArgument(0));
                            messages.add(call.getArgument(1));
                            return new ManagedMessageBus.Published("brain-broker");
                        });
        var leases = new InMemoryManagedLeaseStore(System::currentTimeMillis);
        try (var publisher =
                new ManagedResumePublisher(replacement.suspensions(), bus, leases, mapper)) {
            assertEquals(1, publisher.retry(10));
            assertEquals(0, publisher.retry(10));
        }
        assertEquals(
                ready.get(0),
                mapper.readValue(messages.get(0).body(), ManagedSuspensions.Resume.class));
        assertEquals(scope.messageGroup(), messages.get(0).messageGroup());
        var next = new ManagedAdmissionStore(runtime.getDataSource(), mapper);
        assertEquals(2, next.activateResume(ready.get(0), "replacement"));
        assertEquals("preparing", next.turn(scope, turn).status());
        assertEquals("replacement", next.turn(scope, turn).executionToken());
    }

    @Test
    void concurrentFinalResultsCreateOneResume() throws Exception {
        boundary(List.of(), Suspension.tools("suspension", List.of(task, other)));
        completed(task);
        completed(other);
        var replacement =
                new ManagedJournalStore(
                        runtime.getDataSource(),
                        mapper,
                        new ManagedCheckpointObjects(mapper, root));
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first =
                    workers.submit(
                            () -> {
                                start.await();
                                receive(task);
                                return null;
                            });
            var second =
                    workers.submit(
                            () -> {
                                start.await();
                                for (var event : hands.toolEvents().read(scope, other, 0, 256))
                                    replacement.commitToolEvent(event);
                                return null;
                            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
        assertEquals(1, brain.suspensions().pending(10).size());
        assertEquals(
                1, runtime.queryForObject("SELECT COUNT(*) FROM ma.turn_resume", Integer.class));
    }

    @Test
    void legacyWorkerNoticeOnlyRequeuesCanonicalBrainResume() throws Exception {
        boundary(List.of(), Suspension.tools("suspension", List.of(task)));
        completed(task);
        var notice =
                new ManagedToolResume(
                        1,
                        scope,
                        "old-worker-notice",
                        turn,
                        task,
                        task,
                        ManagedScope.fingerprint(result(task).result()),
                        hands.toolEvents().cursor(scope, task).orElseThrow().finalSeq());
        assertFalse(brain.transport().reconcileLegacyResume(notice, brain));
        receive(task);
        runtime.update("UPDATE ma.turn_resume SET delivery_status='queued'");
        var bus = mock(ManagedMessageBus.class);
        when(bus.active(any())).thenReturn(true);
        var renewals = Executors.newSingleThreadScheduledExecutor();
        try (var turns =
                new ManagedBrainTurns(
                        bus,
                        new InMemoryManagedLeaseStore(System::currentTimeMillis),
                        admission,
                        brain,
                        new ManagedRuntimeWriter(brain),
                        mapper,
                        renewals,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(1),
                        1,
                        Duration.ofMillis(500))) {
            var message =
                    new ManagedMessageBus.Message(
                            notice.notificationId(),
                            notice.messageGroup(),
                            mapper.writeValueAsBytes(notice));
            turns.consume(
                    new ManagedPushDelivery(
                            "delivery",
                            "consumer",
                            ManagedMessageBus.Channel.TURN_RESUMES,
                            "topic",
                            "group",
                            "broker",
                            1,
                            message),
                    execution -> {
                        throw new AssertionError("Legacy notice must not run inference");
                    });
        } finally {
            renewals.shutdownNow();
        }
        assertEquals(1, brain.suspensions().pending(10).size());
        assertEquals("waiting_tool", admission.turn(scope, turn).status());
        assertEquals(1, admission.turn(scope, turn).executionEpoch());
        var wrong =
                new ManagedToolResume(
                        1, scope, "wrong", turn, task, task, "incorrect", notice.finalSequence());
        assertEquals(
                "tool_result_conflict",
                assertThrows(
                                ManagedStateException.class,
                                () -> brain.transport().reconcileLegacyResume(wrong, brain))
                        .code());
    }

    @Test
    void toolResultAndBrainResumeOutboxRollBackTogether() {
        boundary(List.of(), Suspension.tools("suspension", List.of(task)));
        completed(task);
        runtime.execute("ALTER TABLE ma.turn_resume ALTER COLUMN resume_id VARCHAR(5)");
        assertThrows(DataAccessException.class, () -> receive(task));
        assertNull(brain.tool(scope, task).orElseThrow().result());
        assertTrue(brain.suspensions().pending(10).isEmpty());
        assertEquals(
                "waiting",
                runtime.queryForObject(
                        "SELECT status FROM ma.turn_suspension WHERE suspension_id='suspension'",
                        String.class));
        runtime.execute("ALTER TABLE ma.turn_resume ALTER COLUMN resume_id TEXT");
        receive(task);
        assertEquals(result(task).result(), brain.tool(scope, task).orElseThrow().result());
        assertEquals(1, brain.suspensions().pending(10).size());
    }

    @Test
    void bufferedFinalCannotCloseAGapAndFillingItDrainsMoreThanOnePage() {
        var claimed = hands.claimTool(scope, task, "old").task();
        hands.toolEvents().open(scope, claimed, "old", true);
        var current = hands.claimTool(scope, task, "new").task();
        assertEquals(
                "stale_hands",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        hands.toolEvents()
                                                .capture(
                                                        scope,
                                                        claimed,
                                                        "old",
                                                        new OutputPage(
                                                                0, 1, "eA==", 1, true, null)))
                        .code());
        for (int i = 0; i < 130; i++)
            hands.toolEvents()
                    .capture(
                            scope,
                            current,
                            "new",
                            new OutputPage(i, i + 1, "eA==", 130, i == 129, null));
        hands.stageToolResult(result(task));
        hands.complete(result(task));
        var events = hands.toolEvents().read(scope, task, 0, 256);
        for (int i = events.size() - 1; i >= 1; i--) brain.commitToolEvent(events.get(i));
        assertNull(brain.tool(scope, task).orElseThrow().result());
        brain.commitToolEvent(events.get(0));
        assertEquals(result(task).result(), brain.tool(scope, task).orElseThrow().result());
        assertEquals(
                events.size(), brain.toolEvents().cursor(scope, task).orElseThrow().consumedSeq());
        long before = brain.publicEvents(scope, 0, 256).size();
        for (var event : events) brain.commitToolEvent(event);
        assertEquals(before, brain.publicEvents(scope, 0, 256).size());
        var original = events.get(0);
        var conflict =
                new ManagedToolEvent(
                        scope,
                        task,
                        task,
                        original.sourceSeq(),
                        original.type(),
                        Map.of("status", "changed"));
        assertEquals(
                "tool_event_conflict",
                assertThrows(ManagedStateException.class, () -> brain.commitToolEvent(conflict))
                        .code());
        assertEquals(
                1,
                runtime.queryForObject(
                        "SELECT COUNT(*) FROM ma.tool_event_conflict", Integer.class));
        assertEquals(1, brain.head(scope).sequence());
    }

    @Test
    void workerCompletionAndReplayNeverUseTheRetiredResumeOutbox() {
        hands.claimTool(scope, task, "collector");
        hands.stageToolResult(result(task));
        physicalRows.execute("DROP TABLE ma.hands_resume_outbox");
        hands.complete(result(task));
        assertEquals("succeeded", hands.tool(scope, task).orElseThrow().status());
        assertNotNull(hands.toolEvents().cursor(scope, task).orElseThrow().finalSeq());
        hands.replay(scope, task);
        assertTrue(
                hands.toolEvents()
                        .publication(scope, task, System.currentTimeMillis())
                        .isPresent());
    }

    @Test
    void durableUnknownAcceptsAConfirmedLateResultButRejectsAConflictingOutcome() {
        hands.claimTool(scope, task, "first");
        hands.recordToolHandle(scope, task, "first", task);
        hands.markToolUnknown(scope, task, "first");
        assertTrue(hands.history(scope, task).contains("unknown"));
        assertFalse(hands.claimTool(scope, task, "replacement").firstLaunch());
        hands.stageToolResult(result(task));
        hands.complete(result(task));
        assertTrue(hands.history(scope, task).contains("succeeded"));
        assertThrows(
                ManagedStateException.class,
                () ->
                        hands.stageToolResult(
                                new ManagedToolResult(scope, task, task, "failed", "{}")));
        assertEquals(result(task).result(), hands.tool(scope, task).orElseThrow().result());
        assertNull(brain.tool(scope, task).orElseThrow().result());
    }

    @Test
    void brokerAcceptanceWithLostResponsesRetainsStableCommandEventAndResumeIdentities() {
        var bus = mock(ManagedMessageBus.class);
        var messages = new ArrayList<ManagedMessageBus.Message>();
        var calls = new AtomicInteger();
        when(bus.publish(any(), any()))
                .thenAnswer(
                        invocation -> {
                            messages.add(invocation.getArgument(1));
                            if (calls.getAndIncrement() == 0)
                                throw new ManagedTransportException(
                                        "Broker accepted but response was lost");
                            return new ManagedMessageBus.Published("broker");
                        });
        brain.transport().configure(bus, codec);
        var command =
                new ManagedToolCommand(
                        scope,
                        "execute_" + task,
                        "execute",
                        brain.tool(scope, task).orElseThrow(),
                        null);
        assertThrows(ManagedTransportException.class, () -> brain.transport().send(command));
        brain.transport().send(command);
        assertEquals(messages.get(0).id(), messages.get(1).id());
        assertArrayEquals(messages.get(0).body(), messages.get(1).body());
        completed(task);
        messages.clear();
        calls.set(0);
        var publisher = new ManagedToolResultPublisher(hands, bus, codec);
        assertThrows(ManagedTransportException.class, () -> publisher.flush(scope, task));
        hands.replay(scope, task);
        assertEquals(1, publisher.repair(10));
        assertEquals(messages.get(0).id(), messages.get(1).id());
        messages.clear();
        calls.set(0);
        receive(task);
        boundary(List.of(), Suspension.tools("retry-suspension", List.of(task)));
        var leases = new InMemoryManagedLeaseStore(System::currentTimeMillis);
        try (var first = new ManagedResumePublisher(brain.suspensions(), bus, leases, mapper)) {
            assertEquals(0, first.retry(10));
        }
        assertEquals(1, brain.suspensions().pending(10).size());
        try (var replacement =
                new ManagedResumePublisher(brain.suspensions(), bus, leases, mapper)) {
            assertEquals(1, replacement.retry(10));
        }
        assertEquals(messages.get(0).id(), messages.get(1).id());
        assertArrayEquals(messages.get(0).body(), messages.get(1).body());
        assertEquals(
                0,
                physicalRows.queryForObject(
                        "SELECT COUNT(*) FROM ma.hands_resume_outbox", Integer.class));
        assertTrue(brain.suspensions().pending(10).isEmpty());
        assertEquals(result(task).result(), brain.tool(scope, task).orElseThrow().result());
    }
}
