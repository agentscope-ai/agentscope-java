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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentscope.builder.runtime.managed.ManagedToolCommitGate.Decision;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.state.AgentState;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** The same admission/crash/locking contract runs on H2 and real PostgreSQL. */
@Timeout(20)
class ManagedToolSubmissionTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    final ManagedScope scope = new ManagedScope("tenant", "ns", UUID.randomUUID().toString());
    final List<ManagedToolCommand> deliveries = new ArrayList<>();
    final ExecutorService pool = Executors.newFixedThreadPool(3);
    final AtomicInteger wakes = new AtomicInteger();
    DataSource source;
    ManagedAdmissionStore admission;
    ManagedJournalStore journal;
    ManagedHandsStore worker;
    ManagedHandsCommands commands;
    ManagedBatchCodec codec;
    ManagedMessageBus bus;
    JdbcTemplate jdbc;
    String turn;

    protected DataSource database() throws Exception {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:submission-"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    protected void closeDatabase() throws Exception {}

    @BeforeEach
    void setup() throws Exception {
        source = database();
        ManagedAdmissionStore.initialize(source);
        jdbc = new JdbcTemplate(source);
        admission = new ManagedAdmissionStore(source, mapper);
        admission.createSession(scope, "{}");
        turn = admission.acceptTurn(scope, "actor", "turn", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "turn-broker");
        admission.activate(scope, turn, 1, "R1");
        journal = journal(source);
        var physical =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:physical-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(physical);
        worker = new ManagedHandsStore(physical, mapper);
        codec = new ManagedBatchCodec(mapper, root.resolve("payloads"));
        bus = mock(ManagedMessageBus.class);
        when(bus.publish(any(), any()))
                .thenAnswer(
                        invocation -> {
                            assertEquals(Channel.TOOL_EXECUTE, invocation.getArgument(0));
                            deliveries.add(codec.decodeToolCommand(invocation.getArgument(1)));
                            return new ManagedMessageBus.Published(
                                    "execute-broker-" + deliveries.size());
                        });
        journal.transport().configure(bus, codec);
        commands =
                new ManagedHandsCommands(
                        worker,
                        codec,
                        mock(ManagedToolResultPublisher.class),
                        (s, t) -> wakes.incrementAndGet(),
                        journal.transport()::confirm);
    }

    ManagedJournalStore journal(DataSource source) {
        return new ManagedJournalStore(
                source, mapper, new ManagedCheckpointObjects(mapper, root.resolve("checkpoints")));
    }

    @AfterEach
    void cleanup() throws Exception {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        journal.transport().close();
        closeDatabase();
    }

    ManagedRuntimeBatch batch(long epoch, String token) throws Exception {
        long sequence = journal.head(scope).sequence();
        var checkpoint =
                new SessionEvent(
                        1,
                        UUID.randomUUID().toString(),
                        sequence + 1,
                        System.currentTimeMillis(),
                        "state/checkpoint",
                        "run",
                        turn,
                        true,
                        mapper.writeValueAsString(
                                Map.of("stateJson", AgentState.builder().build().toJson())));
        return journal.objects()
                .prepare(
                        new ManagedRuntimeBatch(
                                scope,
                                turn,
                                epoch,
                                token,
                                token,
                                UUID.randomUUID().toString(),
                                sequence,
                                List.of(checkpoint),
                                List.of(
                                        new ToolIntent(
                                                "call",
                                                "sandbox.exec",
                                                "{\"native_tool_call_id\":\"native\"}",
                                                "sealed")),
                                List.of(),
                                null));
    }

    ManagedPushDelivery delivery(ManagedToolCommand command) {
        return new ManagedPushDelivery(
                UUID.randomUUID().toString(),
                "W1",
                Channel.TOOL_EXECUTE,
                "execute",
                "workers",
                "broker",
                1,
                codec.encode(command));
    }

    void commit(ManagedRuntimeBatch batch) {
        new ManagedRuntimeWriter(journal).commit(batch, mock(ManagedExecutionGuard.class));
    }

    @Test
    void deliveryBeforeCommitSuspendsThenAnotherWorkerAcceptsTheCommittedTask() throws Exception {
        var batch = batch(1, "R1");
        reset(bus);
        when(bus.publish(any(), any()))
                .thenAnswer(
                        invocation -> {
                            var command = codec.decodeToolCommand(invocation.getArgument(1));
                            deliveries.add(command);
                            assertTrue(journal.tool(scope, command.task().id()).isEmpty());
                            assertThrows(
                                    ManagedDeliverySuspended.class,
                                    () -> commands.consume(delivery(command)));
                            assertTrue(worker.tool(scope, command.task().id()).isEmpty());
                            assertEquals(0, wakes.get());
                            return new ManagedMessageBus.Published("accepted");
                        });
        commit(batch);
        var command = deliveries.get(0);
        var W2 =
                new ManagedHandsCommands(
                        worker,
                        codec,
                        mock(ManagedToolResultPublisher.class),
                        (s, t) -> wakes.incrementAndGet(),
                        new ManagedToolCommitGate(source, mapper)::check);
        W2.consume(delivery(command));
        commands.consume(delivery(command));
        assertTrue(worker.tool(scope, command.task().id()).isPresent());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ma.tool_task", Integer.class));
        assertEquals(
                "accepted",
                jdbc.queryForObject("SELECT broker_id FROM ma.tool_command_outbox", String.class));
        assertTrue(worker.claimTool(scope, command.task().id(), "owner-1").firstLaunch());
        assertFalse(worker.claimTool(scope, command.task().id(), "owner-2").firstLaunch());
    }

    @Test
    void crashAfterSendBeforeCommitKeepsMessageUntilDurableEpochReplacement() throws Exception {
        var batch = batch(1, "R1");
        var sent = journal.publishTools(batch); // R1 never commits.
        var command = deliveries.get(0);
        assertThrows(ManagedDeliverySuspended.class, () -> commands.consume(delivery(command)));
        assertFalse(
                jdbc.queryForObject(
                        "SELECT authorized FROM ma.tool_command_outbox", Boolean.class));
        assertTrue(worker.tool(scope, command.task().id()).isEmpty());
        long epoch = admission.activate(scope, turn, 1, "R2");
        commands.consume(delivery(command)); // ACK is safe only now.
        assertEquals(0, wakes.get());
        assertEquals(
                "stale_execution",
                assertThrows(ManagedStateException.class, () -> journal.commit(batch, sent))
                        .code());
        assertEquals(1, journal.transport().pending(scope, turn).size());
        commit(batch(epoch, "R2"));
        commands.consume(delivery(deliveries.get(1)));
        assertTrue(worker.tool(scope, command.task().id()).isPresent());
        assertEquals(2, deliveries.size());
    }

    @Test
    void concurrentWorkersPersistOnlyOneAdmissionForTheSameCommittedExecute() throws Exception {
        commit(batch(1, "R1"));
        var command = deliveries.get(0);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        Callable<Void> receive =
                () -> {
                    ready.countDown();
                    assertTrue(go.await(5, TimeUnit.SECONDS));
                    commands.consume(delivery(command));
                    return null;
                };
        var a = pool.submit(receive);
        var b = pool.submit(receive);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        a.get(5, TimeUnit.SECONDS);
        b.get(5, TimeUnit.SECONDS);
        assertEquals(
                1,
                worker.history(scope, command.task().id()).stream()
                        .filter("received"::equals)
                        .count());
        assertTrue(worker.claimTool(scope, command.task().id(), "winner").firstLaunch());
        assertFalse(worker.claimTool(scope, command.task().id(), "replacement").firstLaunch());
    }

    @Test
    void committedTaskSurvivesRuntimeDeathAndEpochReplacement() throws Exception {
        commit(batch(1, "R1"));
        admission.activate(scope, turn, 1, "R2");
        var command = deliveries.get(0);
        commands.consume(delivery(command));
        assertTrue(worker.tool(scope, command.task().id()).isPresent());
        assertEquals(1, wakes.get());
    }

    @Test
    void lostBrokerResponseCannotCommitOrAuthorizeExecution() throws Exception {
        reset(bus);
        when(bus.publish(any(), any()))
                .thenAnswer(
                        invocation -> {
                            deliveries.add(codec.decodeToolCommand(invocation.getArgument(1)));
                            throw new ManagedTransportException("accepted, response lost");
                        });
        var batch = batch(1, "R1");
        assertThrows(ManagedTransportException.class, () -> commit(batch));
        assertEquals(0, journal.head(scope).sequence());
        assertThrows(
                ManagedDeliverySuspended.class,
                () -> commands.consume(delivery(deliveries.get(0))));
        assertEquals(0, wakes.get());
    }

    @Test
    void delayedOldCommitCannotPassTheWorkerDiscardDecision() throws Exception {
        var batch = batch(1, "R1");
        var sent = journal.publishTools(batch);
        admission.activate(scope, turn, 1, "R2");
        var command = deliveries.get(0);
        assertEquals(Decision.DISCARDED, journal.transport().confirm(command));
        assertThrows(ManagedStateException.class, () -> journal.commit(batch, sent));
        assertTrue(journal.tool(scope, command.task().id()).isEmpty());
    }

    @Test
    void commitWinningSessionLockIsVisibleBeforeWorkerMayDiscard() throws Exception {
        var batch = batch(1, "R1");
        var sent = journal.publishTools(batch);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var gated =
                ManagedToolTransportTest.gated(
                        source,
                        (sql, before) -> {
                            if (!before && sql.startsWith("SELECT") && sql.contains("FOR UPDATE")) {
                                locked.countDown();
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            }
                        });
        var writer = journal(gated);
        try {
            var committing = pool.submit(() -> writer.commit(batch, sent));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var checking = pool.submit(() -> journal.transport().confirm(deliveries.get(0)));
            assertThrows(TimeoutException.class, () -> checking.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals("committed", committing.get(5, TimeUnit.SECONDS).outcome());
            assertEquals(Decision.COMMITTED, checking.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            writer.transport().close();
        }
    }

    @Test
    void pendingDecisionDoesNotAckACommitThatWinsImmediatelyAfterTheCheck() throws Exception {
        var batch = batch(1, "R1");
        var sent = journal.publishTools(batch);
        var checked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var racing =
                new ManagedHandsCommands(
                        worker,
                        codec,
                        mock(ManagedToolResultPublisher.class),
                        (owner, task) -> wakes.incrementAndGet(),
                        command -> {
                            var decision = journal.transport().confirm(command);
                            checked.countDown();
                            try {
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException interrupted) {
                                throw new AssertionError(interrupted);
                            }
                            return decision;
                        });
        var consuming = pool.submit(() -> racing.consume(delivery(deliveries.get(0))));
        try {
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            journal.commit(batch, sent);
            release.countDown();
            var failure =
                    assertThrows(
                            ExecutionException.class, () -> consuming.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ManagedDeliverySuspended.class, failure.getCause());
            assertEquals(0, wakes.get());
            commands.consume(delivery(deliveries.get(0)));
            assertEquals(1, wakes.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void databaseCommitWithLostResponseStillAuthorizesTheOriginalMessage() throws Exception {
        var armed = new AtomicBoolean();
        var faulty =
                new DelegatingDataSource(source) {
                    @Override
                    public Connection getConnection() throws SQLException {
                        var connection = super.getConnection();
                        return (Connection)
                                Proxy.newProxyInstance(
                                        Connection.class.getClassLoader(),
                                        new Class<?>[] {Connection.class},
                                        (proxy, method, args) -> {
                                            var value =
                                                    ManagedToolTransportTest.invoke(
                                                            connection, method, args);
                                            if (method.getName().equals("commit")
                                                    && armed.compareAndSet(true, false))
                                                throw new SQLException(
                                                        "commit succeeded, response lost");
                                            return value;
                                        });
                    }
                };
        var writer = journal(faulty);
        reset(bus);
        when(bus.publish(any(), any()))
                .thenAnswer(
                        invocation -> {
                            deliveries.add(codec.decodeToolCommand(invocation.getArgument(1)));
                            armed.set(true); // Proposal already committed; fail only the following
                            // task transaction.
                            return new ManagedMessageBus.Published("accepted");
                        });
        writer.transport().configure(bus, codec);
        try {
            var batch = batch(1, "R1");
            assertThrows(
                    RuntimeException.class,
                    () ->
                            new ManagedRuntimeWriter(writer)
                                    .commit(batch, mock(ManagedExecutionGuard.class)));
            var command = deliveries.get(0);
            assertTrue(journal.tool(scope, command.task().id()).isPresent());
            assertTrue(
                    jdbc.queryForObject(
                            "SELECT authorized FROM ma.tool_command_outbox", Boolean.class));
            commands.consume(delivery(command));
            assertEquals(1, wakes.get());
        } finally {
            writer.transport().close();
        }
    }

    @Test
    void authorizationDatabaseOutageRetainsTheMqMessageWithoutCreatingExecution() throws Exception {
        journal.publishTools(batch(1, "R1"));
        var unavailable =
                new ManagedHandsCommands(
                        worker,
                        codec,
                        mock(ManagedToolResultPublisher.class),
                        (owner, task) -> wakes.incrementAndGet(),
                        command -> {
                            throw new DataAccessResourceFailureException("offline");
                        });
        var command = deliveries.get(0);
        assertThrows(ManagedDeliverySuspended.class, () -> unavailable.consume(delivery(command)));
        assertTrue(worker.tool(scope, command.task().id()).isEmpty());
        assertEquals(0, wakes.get());
    }

    @Test
    void cancelBeforeCommitIsPersistedBeforeTheWorkerCanLaunch() throws Exception {
        var batch = batch(1, "R1");
        var sent = journal.publishTools(batch);
        admission.acceptInput(
                scope, turn, "actor", "cancel", ManagedInputInbox.Type.CANCEL, null, "{}");
        journal.commit(batch, sent);
        var command = deliveries.get(0);
        commands.consume(delivery(command));
        assertTrue(
                worker.cancelRequested(
                        scope, worker.tool(scope, command.task().id()).orElseThrow()));
    }

    @Test
    void recoveryCannotAllowT2ToPassUnfinishedT1() throws Exception {
        var oldBatch = batch(1, "R1");
        journal.publishTools(oldBatch);
        String T2 = admission.acceptTurn(scope, "actor", "T2", "{}").id();
        admission.reservePublication(scope, T2);
        admission.confirmPublication(scope, T2, "T2-broker");
        assertEquals(
                "missing_predecessor",
                assertThrows(
                                ManagedStateException.class,
                                () -> admission.activate(scope, T2, 2, "R2"))
                        .code());
        long epoch = admission.activate(scope, turn, 1, "R2");
        commit(batch(epoch, "R2"));
        commands.consume(delivery(deliveries.get(1)));
        assertEquals(
                "missing_predecessor",
                assertThrows(
                                ManagedStateException.class,
                                () -> admission.activate(scope, T2, 2, "R3"))
                        .code());
    }

    @Test
    void runtimeStartupDoesNotPublishPendingProposals() throws Exception {
        journal.publishTools(batch(1, "R1"));
        clearInvocations(bus);
        journal.transport().start(bus, codec);
        // Join two maintenance cycles without relying on their internal implementation.
        Thread.sleep(450);
        verifyNoInteractions(bus);
        assertEquals(1, journal.transport().pending(scope, turn).size());
    }
}
