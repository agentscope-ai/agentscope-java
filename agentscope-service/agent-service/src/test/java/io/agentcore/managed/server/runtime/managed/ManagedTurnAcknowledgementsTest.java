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
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedTurnPublisher.Command;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.rocketmq.client.java.impl.consumer.ManagedLitePushConsumer.Ack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class ManagedTurnAcknowledgementsTest {
    final ObjectMapper mapper = new ObjectMapper();
    final ManagedScope scope = new ManagedScope("t", "n", "ack-" + UUID.randomUUID());
    final AtomicLong clock = new AtomicLong(1000);
    DataSource source;
    JdbcTemplate jdbc;
    Command command;

    protected DataSource database() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:ack-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @BeforeEach
    void setup() {
        source = database();
        ManagedAdmissionStore.initialize(source);
        jdbc = new JdbcTemplate(source);
        var admission = new ManagedAdmissionStore(source, mapper);
        admission.createSession(scope, "{}");
        var turn = admission.acceptTurn(scope, "user", "first", "{}");
        admission.reservePublication(scope, turn.id());
        admission.confirmPublication(scope, turn.id(), "broker");
        command = new Command(scope, turn.id(), 1);
    }

    @AfterEach
    void cleanup() {
        if (jdbc != null)
            jdbc.update("DELETE FROM ma.turn_ack_receipt WHERE scope_key=?", scope.messageGroup());
    }

    @Test
    void waitingTurnUsesSixtySecondDefaultWithoutActivatingANewEpoch() throws Exception {
        status("waiting_tool");
        var bus = mock(ManagedMessageBus.class);
        when(bus.active(any())).thenReturn(true);
        var delivery =
                new ManagedPushDelivery(
                        "delivery",
                        "consumer",
                        ManagedMessageBus.Channel.TURNS,
                        "turns",
                        "brain",
                        "broker",
                        1,
                        new ManagedMessageBus.Message(
                                command.turnId(),
                                scope.messageGroup(),
                                mapper.writeValueAsBytes(command)));
        var timer = Executors.newSingleThreadScheduledExecutor();
        var admission = new ManagedAdmissionStore(source, mapper);
        try (var runtime =
                new ManagedHarnessRuntimeTurns(
                        bus,
                        null,
                        admission,
                        null,
                        null,
                        mapper,
                        timer,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(15),
                        1)) {
            var suspended =
                    assertThrows(
                            ManagedDeliverySuspended.class,
                            () ->
                                    runtime.consume(
                                            delivery,
                                            execution -> fail("Waiting head must not execute")));
            assertEquals(Duration.ofSeconds(60), suspended.delay());
            assertEquals(0, admission.turn(scope, command.turnId()).executionEpoch());
        } finally {
            timer.shutdownNow();
        }
    }

    RocketMqTurnReceipt receipt(String handle) {
        return new RocketMqTurnReceipt(
                "broker-" + command.turnId(),
                "turns",
                scope.messageGroup(),
                handle,
                "routing",
                0,
                0,
                1);
    }

    ManagedTurnAcknowledgements replica(Function<RocketMqTurnReceipt, Ack> ack) {
        return new ManagedTurnAcknowledgements(
                new SessionTurnAckLedger(source, mapper, clock::get), "turns", "brain", ack);
    }

    void status(String status) {
        if (Set.of("succeeded", "failed", "cancelled").contains(status))
            jdbc.update(
                    "UPDATE ma.turn_ack_receipt SET ready=TRUE WHERE scope_key=? AND turn_id=?",
                    scope.messageGroup(),
                    command.turnId());
        jdbc.update(
                "UPDATE ma.turn SET status=? WHERE scope_key=? AND turn_id=?",
                status,
                scope.messageGroup(),
                command.turnId());
    }

    long rows() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ma.turn_ack_receipt WHERE scope_key=?",
                Long.class,
                scope.messageGroup());
    }

    @ParameterizedTest
    @ValueSource(strings = {"accepted", "running", "waiting_tool", "waiting_input"})
    void resultOrSuspensionAloneMustNotReleaseT1(String status) {
        status(status);
        var owner =
                replica(
                        r -> {
                            fail("Only durable terminal may ACK");
                            return Ack.RETRY;
                        });
        owner.suspended(command, receipt("old"), Duration.ofSeconds(60));
        assertEquals(0, owner.retry(10));
        assertEquals(1, rows());
    }

    @ParameterizedTest
    @ValueSource(strings = {"succeeded", "failed", "cancelled"})
    void anotherReplicaAcksEvenWhenCompletionPrecedesReceiptCapture(String terminal) {
        status(terminal);
        replica(
                        r -> {
                            fail("Owner is gone");
                            return Ack.RETRY;
                        })
                .suspended(command, receipt("valid"), Duration.ofSeconds(60));
        var calls = new AtomicInteger();
        var peer =
                replica(
                        r -> {
                            assertEquals("valid", r.handle());
                            calls.incrementAndGet();
                            return Ack.ACKNOWLEDGED;
                        });
        assertEquals(1, peer.retry(10));
        assertEquals(0, peer.retry(10));
        assertEquals(1, calls.get());
        assertEquals(0, rows());
    }

    @Test
    void rolledBackTerminalDoesNotProduceAnAck() {
        var owner =
                replica(
                        r -> {
                            fail("Transaction was rolled back");
                            return Ack.RETRY;
                        });
        owner.suspended(command, receipt("valid"), Duration.ofSeconds(60));
        new TransactionTemplate(new DataSourceTransactionManager(source))
                .executeWithoutResult(
                        tx -> {
                            status("succeeded");
                            tx.setRollbackOnly();
                        });
        assertEquals(0, owner.retry(10));
        assertEquals(1, rows());
    }

    @Test
    void expiredGenerationCannotReplaceOrDeleteTheFreshGeneration() {
        var owner = replica(r -> Ack.RETRY);
        owner.suspended(command, receipt("new"), Duration.ofSeconds(60));
        owner.suspended(command, receipt("old"), Duration.ofSeconds(60));
        owner.suspended(command, receipt("new"), Duration.ofSeconds(60));
        assertEquals(2, rows());
        status("succeeded");
        assertEquals(
                1,
                replica(r -> "old".equals(r.handle()) ? Ack.EXPIRED : Ack.ACKNOWLEDGED).retry(10));
        assertEquals(0, rows());
    }

    @Test
    void inFlightOldResponseDoesNotEraseNewCapability() {
        var owner = replica(r -> Ack.RETRY);
        owner.suspended(command, receipt("old"), Duration.ofSeconds(60));
        status("succeeded");
        assertEquals(
                0,
                replica(
                                r -> {
                                    owner.suspended(
                                            command, receipt("new"), Duration.ofSeconds(60));
                                    return Ack.EXPIRED;
                                })
                        .retry(10));
        assertEquals(1, rows());
        assertEquals(
                1,
                replica(
                                r -> {
                                    assertEquals("new", r.handle());
                                    return Ack.ACKNOWLEDGED;
                                })
                        .retry(10));
    }

    @Test
    void ambiguousAckOrCrashedDrainerCanBeRetriedByAnotherReplica() {
        var owner =
                replica(
                        r -> {
                            throw new ManagedTransportException("ACK response lost");
                        });
        owner.suspended(command, receipt("valid"), Duration.ofSeconds(60));
        status("succeeded");
        assertEquals(0, owner.retry(10));
        var peer = replica(r -> Ack.ACKNOWLEDGED);
        assertEquals(0, peer.retry(10));
        clock.addAndGet(10001);
        assertEquals(1, peer.retry(10));
    }

    @Test
    void simultaneousReplicasClaimOnlyOneAttempt() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var owner =
                replica(
                        r -> {
                            entered.countDown();
                            try {
                                assertTrue(finish.await(3, TimeUnit.SECONDS));
                            } catch (InterruptedException e) {
                                throw new AssertionError(e);
                            }
                            return Ack.ACKNOWLEDGED;
                        });
        owner.suspended(command, receipt("valid"), Duration.ofSeconds(60));
        status("succeeded");
        var running = CompletableFuture.supplyAsync(() -> owner.retry(10));
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(
                    0,
                    replica(
                                    r -> {
                                        fail("Already claimed");
                                        return Ack.RETRY;
                                    })
                            .retry(10));
        } finally {
            finish.countDown();
        }
        assertEquals(1, running.get(3, TimeUnit.SECONDS));
    }

    @Test
    void timeoutDiscardsOnlyOldCapabilityAndRedeliveryCanRefreshIt() {
        var owner =
                replica(
                        r -> {
                            fail("Expired capability must not be retried");
                            return Ack.RETRY;
                        });
        owner.suspended(command, receipt("old"), Duration.ofSeconds(60));
        clock.addAndGet(60001);
        status("succeeded");
        assertEquals(0, owner.retry(10));
        assertEquals(0, rows());
        owner.suspended(command, receipt("new"), Duration.ofSeconds(60));
        assertEquals(1, replica(r -> Ack.ACKNOWLEDGED).retry(10));
    }

    @Test
    void publicationAndConsumerGroupRemainIsolated() {
        var owner = replica(r -> Ack.ACKNOWLEDGED);
        owner.suspended(
                new Command(scope, command.turnId(), 2),
                receipt("wrong-sequence"),
                Duration.ofSeconds(60));
        assertEquals(0, rows());
        owner.suspended(command, receipt("valid"), Duration.ofSeconds(60));
        status("succeeded");
        var other =
                new ManagedTurnAcknowledgements(
                        new SessionTurnAckLedger(source, mapper, clock::get),
                        "turns",
                        "other",
                        r -> {
                            fail("Wrong group");
                            return Ack.RETRY;
                        });
        assertEquals(0, other.retry(10));
        assertEquals(1, rows());
        assertFalse(receipt("private-handle").toString().contains("private-handle"));
        assertEquals(1, owner.retry(10));
    }

    @Test
    void captureCannotMissATerminalTransactionAlreadyHoldingTheSessionLock() throws Exception {
        var locked = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var capturing = new CountDownLatch(1);
        var owner = replica(r -> Ack.ACKNOWLEDGED);
        var terminal =
                CompletableFuture.runAsync(
                        () ->
                                new TransactionTemplate(new DataSourceTransactionManager(source))
                                        .executeWithoutResult(
                                                tx -> {
                                                    jdbc.queryForObject(
                                                            "SELECT scope_key FROM ma.session WHERE"
                                                                    + " scope_key=? FOR UPDATE",
                                                            String.class,
                                                            scope.messageGroup());
                                                    status("succeeded");
                                                    locked.countDown();
                                                    try {
                                                        assertTrue(
                                                                commit.await(3, TimeUnit.SECONDS));
                                                    } catch (InterruptedException failure) {
                                                        throw new AssertionError(failure);
                                                    }
                                                }));
        CompletableFuture<Void> capture = null;
        try {
            assertTrue(locked.await(3, TimeUnit.SECONDS));
            capture =
                    CompletableFuture.runAsync(
                            () -> {
                                capturing.countDown();
                                owner.suspended(command, receipt("late"), Duration.ofSeconds(60));
                            });
            assertTrue(capturing.await(3, TimeUnit.SECONDS));
            var pending = capture;
            assertThrows(TimeoutException.class, () -> pending.get(100, TimeUnit.MILLISECONDS));
        } finally {
            commit.countDown();
        }
        terminal.get(3, TimeUnit.SECONDS);
        if (capture != null) capture.get(3, TimeUnit.SECONDS);
        assertEquals(1, owner.retry(10));
    }
}
