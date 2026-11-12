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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentcore.managed.server.runtime.managed.ManagedToolCommitGate.Decision;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.state.AgentState;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    final List<ManagedToolCommand> deliveries = new CopyOnWriteArrayList<>();
    final ExecutorService pool = Executors.newFixedThreadPool(3);
    final AtomicInteger wakes = new AtomicInteger();
    DataSource source;
    ManagedAdmissionStore admission;
    ManagedJournalStore journal;
    ManagedToolWorkerStore worker;
    ManagedToolWorkerCommands commands;
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
        admission = ProjectionTestSupport.admission(source, mapper);
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
        worker = new ManagedToolWorkerStore(physical, mapper);
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
                new ManagedToolWorkerCommands(
                        worker,
                        codec,
                        mock(ManagedToolResultPublisher.class),
                        (s, t) -> wakes.incrementAndGet(),
                        journal.transport()::confirm);
    }

    ManagedJournalStore journal(DataSource source) {
        return ProjectionTestSupport.journal(
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
        return AgentScopeCheckpoints.prepare(
                journal.objects(),
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
                codec.encode(command),
                ManagedDeliveryHandoff.direct());
    }

    void commit(ManagedRuntimeBatch batch) {
        new ManagedRuntimeWriter(journal, new SessionCommitTransactions(journal))
                .commit(batch, mock(ManagedExecutionGuard.class));
    }

    ManagedToolCommandPublisher publisher() {
        return new ManagedToolCommandPublisher(source, bus, codec, mapper);
    }

    @Test
    void commitAuthorizesOutboxWithoutPublishingAndAnotherWorkerAcceptsIt() throws Exception {
        commit(batch(1, "R1"));
        verifyNoInteractions(bus);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.tool_command_outbox WHERE authorized=TRUE AND"
                                + " broker_id IS NULL",
                        Integer.class));
        assertEquals(1, publisher().publishCommands(16));
        var command = deliveries.get(0);
        assertEquals(Decision.COMMITTED, new ManagedToolCommitGate(source, mapper).check(command));
        commands.consume(delivery(command));
        commands.consume(delivery(command));
        assertTrue(worker.claimTool(scope, command.task().id(), "owner-1").firstLaunch());
        assertFalse(worker.claimTool(scope, command.task().id(), "owner-2").firstLaunch());
        assertEquals(0, publisher().publishCommands(16));
    }

    @Test
    void fencedOrIncompleteBoundaryCannotPublishAnyTool() throws Exception {
        var staged = batch(1, "R1");
        admission.activate(scope, turn, 1, "R2");
        assertThrows(ManagedStateException.class, () -> commit(staged));
        assertEquals(0, publisher().publishCommands(16));
        assertEquals(
                0,
                jdbc.queryForObject("SELECT COUNT(*) FROM ma.tool_command_outbox", Integer.class));
        verifyNoInteractions(bus);
    }

    @Test
    void brokerOutageLeavesCommittedToolForRetryWithoutRecommittingState() throws Exception {
        var boundary = batch(1, "R1");
        commit(boundary);
        reset(bus);
        when(bus.publish(any(), any())).thenThrow(new ManagedTransportException("offline"));
        assertEquals(0, publisher().publishCommands(16));
        assertEquals(1, journal.head(scope).sequence());
        jdbc.update("UPDATE ma.tool_command_outbox SET next_attempt_at=0");
        doAnswer(
                        call -> {
                            deliveries.add(codec.decodeToolCommand(call.getArgument(1)));
                            return new ManagedMessageBus.Published("retry");
                        })
                .when(bus)
                .publish(any(), any());
        assertEquals(1, publisher().publishCommands(16));
        commands.consume(delivery(deliveries.get(0)));
        assertEquals(
                1, jdbc.queryForObject("SELECT COUNT(*) FROM ma.runtime_commit", Integer.class));
    }

    @Test
    void databaseCommitWithLostResponseCanReplayAndThenPublishOriginalAuthorization()
            throws Exception {
        var armed = new AtomicBoolean(true);
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
                                                throw new SQLException("commit response lost");
                                            return value;
                                        });
                    }
                };
        var boundary = batch(1, "R1");
        var writer = journal(faulty);
        assertThrows(
                RuntimeException.class,
                () ->
                        AgentScopePreparation.commit(
                                writer, new SessionCommitTransactions(writer), boundary));
        assertEquals(
                "committed",
                AgentScopePreparation.commit(
                                journal, new SessionCommitTransactions(journal), boundary)
                        .outcome());
        verifyNoInteractions(bus);
        assertEquals(1, publisher().publishCommands(16));
        commands.consume(delivery(deliveries.get(0)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ma.tool_task", Integer.class));
    }

    @Test
    void cancelIsDurableBeforeTheWorkerCanLaunch() throws Exception {
        admission.acceptInput(
                scope, turn, "actor", "cancel", ManagedInputInbox.Type.CANCEL, null, "{}");
        commit(batch(1, "R1"));
        publisher().publishCommands(16);
        var command = deliveries.get(0);
        assertEquals(Decision.CANCELLED, new ManagedToolCommitGate(source, mapper).check(command));
        commands.consume(delivery(command));
        assertTrue(
                worker.cancelRequested(
                        scope, worker.tool(scope, command.task().id()).orElseThrow()));
    }

    @Test
    void authorizationDatabaseOutageRetainsMessageWithoutPhysicalExecution() throws Exception {
        commit(batch(1, "R1"));
        publisher().publishCommands(16);
        var unavailable =
                new ManagedToolWorkerCommands(
                        worker,
                        codec,
                        mock(ManagedToolResultPublisher.class),
                        (s, t) -> wakes.incrementAndGet(),
                        command -> {
                            throw new DataAccessResourceFailureException("offline");
                        });
        var command = deliveries.get(0);
        assertThrows(ManagedDeliverySuspended.class, () -> unavailable.consume(delivery(command)));
        assertTrue(worker.tool(scope, command.task().id()).isEmpty());
        assertEquals(0, wakes.get());
    }
}
