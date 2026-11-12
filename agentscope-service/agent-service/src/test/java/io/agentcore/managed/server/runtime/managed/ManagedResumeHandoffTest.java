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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.Suspension;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.state.AgentState;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class ManagedResumeHandoffTest {
    @Test
    void wrappedPreparationFailureRetainsSafeStateCodeAndRootType() {
        var failure =
                new ExecutionException(
                        new ManagedStateException(
                                "mcp_connection_failed",
                                "Required server unavailable",
                                new TimeoutException("private endpoint and credentials")));
        assertEquals("mcp_connection_failed", ManagedResumeExecutions.failureCode(failure));
        assertEquals("TimeoutException", ManagedResumeExecutions.rootCauseType(failure));
        assertNull(
                ManagedResumeExecutions.failureCode(
                        new ExecutionException(new TimeoutException())));
    }

    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    final ManagedScope scope = new ManagedScope("t", "n", "claim-" + UUID.randomUUID());
    final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
    final InMemoryManagedLeaseStore leases = new InMemoryManagedLeaseStore(clock::get);
    final List<AutoCloseable> resources = new ArrayList<>();
    final Duration ttl = Duration.ofSeconds(60);
    final Duration interval = Duration.ofSeconds(30);
    final ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor();
    ManagedAdmissionStore admission;
    ManagedJournalStore journal;
    ManagedRuntimeWriter writer;
    ManagedSuspensions.Resume resume;
    JdbcTemplate jdbc;
    String turn;
    String task;

    protected DataSource database() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:claim-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @BeforeEach
    void setup() throws Exception {
        var source = database();
        ManagedAdmissionStore.initialize(source);
        jdbc = new JdbcTemplate(source);
        admission = ProjectionTestSupport.admission(source, mapper);
        journal =
                ProjectionTestSupport.journal(
                        source, mapper, new ManagedCheckpointObjects(mapper, root));
        writer = new ManagedRuntimeWriter(journal, new SessionCommitTransactions(journal));
        admission.createSession(scope, "{}");
        admission.queue().enable(scope);
        turn = admission.acceptTurn(scope, "user", "one", "{}").id();
        admission.activate(scope, turn, 1, "initial");
        commit("tool", 0, List.of(new ToolIntent("call", "exec", "{}")), null);
        task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "call");
        journal.claimTool(scope, task, "hands");
        commit("suspend", 1, List.of(), Suspension.tools("s1", List.of(task)));
        ManagedPushTestSupport.commitToolResult(
                journal, new ManagedToolResult(scope, task, task, "succeeded", "{}"));
        resume = journal.suspensions().pending(10, scope.messageGroup()).get(0);
        journal.suspensions().published(resume, "original");
    }

    @AfterEach
    void cleanup() throws Exception {
        for (var resource : resources) resource.close();
        renewals.shutdownNow();
        renewals.awaitTermination(5, TimeUnit.SECONDS);
        jdbc.update("DELETE FROM ma.turn_resume WHERE scope_key=?", scope.messageGroup());
    }

    void commit(String id, long sequence, List<ToolIntent> tools, Suspension suspension)
            throws Exception {
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
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        new ManagedRuntimeBatch(
                                scope,
                                turn,
                                1,
                                "initial",
                                "writer",
                                id,
                                sequence,
                                List.of(event),
                                tools,
                                List.of(),
                                null,
                                suspension)));
    }

    ManagedResumeExecutions manager(Semaphore permits, ManualExecutor workers) {
        return manager(ManagedExecutionCapacity.bounded(permits), workers);
    }

    ManagedResumeExecutions manager(ManagedExecutionCapacity permits, ExecutorService workers) {
        var manager =
                new ManagedResumeExecutions(
                        leases,
                        admission,
                        journal,
                        writer,
                        renewals,
                        ttl,
                        Duration.ofSeconds(15),
                        permits,
                        workers);
        resources.add(manager);
        return manager;
    }

    ManagedResumeRecovery recovery() {
        return new ManagedResumeRecovery(journal.suspensions(), leases, interval, clock::get);
    }

    void installHttpLease(ManagedScope ownerScope, String ownerTurn, long epoch, String token) {
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS ma.harness_lease (scope_key VARCHAR(128) PRIMARY KEY,"
                        + " turn_id VARCHAR(256) NOT NULL,epoch BIGINT NOT NULL,execution_token"
                        + " VARCHAR(256) NOT NULL,owner TEXT NOT NULL,expires_at BIGINT NOT NULL)");
        jdbc.update(
                "INSERT INTO ma.harness_lease VALUES(?,?,?,?,?,?)",
                ownerScope.messageGroup(),
                ownerTurn,
                epoch,
                token,
                "{}",
                new ManagedWorkerLeaseStore(jdbc.getDataSource()).now() + 60_000);
        resources.add(
                () ->
                        jdbc.update(
                                "DELETE FROM ma.harness_lease WHERE scope_key=?",
                                ownerScope.messageGroup()));
    }

    @Test
    void initialRepairPreservesRenewedHttpOwnerAndRecoversOnlyAfterExpiry() {
        var initialScope = new ManagedScope("t", "n", "http-initial-" + UUID.randomUUID());
        admission.createSession(initialScope, "{}");
        admission.queue().enable(initialScope);
        var initial = admission.acceptTurn(initialScope, "u", "first", "{}");
        long epoch = admission.activate(initialScope, initial.id(), 1, "http-owner");
        jdbc.update(
                "UPDATE ma.turn SET resume_claimed_at=1 WHERE scope_key=?",
                initialScope.messageGroup());
        installHttpLease(initialScope, initial.id(), epoch, "http-owner");
        var repair =
                new ManagedInitialReadyRecovery(
                        jdbc.getDataSource(), mapper, leases, Duration.ofMillis(1));
        assertEquals(0, repair.repair());
        assertEquals(epoch, admission.session(initialScope).executionEpoch());
        assertEquals("http-owner", admission.turn(initialScope, initial.id()).executionToken());
        jdbc.update(
                "UPDATE ma.harness_lease SET expires_at=0 WHERE scope_key=?",
                initialScope.messageGroup());
        assertEquals(1, repair.repair());
        assertNull(admission.turn(initialScope, initial.id()).executionToken());
        assertEquals(epoch + 1, admission.session(initialScope).executionEpoch());
    }

    @Test
    void resumeRepairPreservesHttpOwnerEvenForPreviouslySelectedClaim() {
        long epoch = admission.activateResume(resume, "http-resume");
        jdbc.update(
                "UPDATE ma.turn SET resume_claimed_at=1 WHERE scope_key=?", scope.messageGroup());
        var repair = recovery();
        var selected = repair.candidates(Long.MAX_VALUE, null, 256).get(0);
        installHttpLease(scope, turn, epoch, "http-resume");
        assertFalse(repair.recover(selected));
        assertEquals(0, repair.repair());
        assertEquals(epoch, admission.session(scope).executionEpoch());
        assertEquals("http-resume", admission.turn(scope, turn).executionToken());
        jdbc.update(
                "UPDATE ma.harness_lease SET expires_at=0 WHERE scope_key=?", scope.messageGroup());
        assertTrue(repair.recover(selected));
        assertEquals(epoch + 1, admission.session(scope).executionEpoch());
    }

    @Test
    void initialRepairRechecksHttpLeaseUnderSessionLockAndDoesNotCacheMissingSchema() {
        var expiry = new ManagedClaimExpiry(jdbc.getDataSource());
        assertFalse(expiry.httpLeaseCurrent(scope, turn, "late-http-owner"));
        installHttpLease(scope, turn, 1, "late-http-owner");
        var calls = new AtomicInteger();
        assertFalse(
                expiry.withoutCurrentHttpLease(
                        scope,
                        turn,
                        "late-http-owner",
                        () -> {
                            calls.incrementAndGet();
                            return true;
                        }));
        assertEquals(0, calls.get());
        jdbc.update(
                "UPDATE ma.harness_lease SET expires_at=0 WHERE scope_key=?", scope.messageGroup());
        assertTrue(expiry.withoutCurrentHttpLease(scope, turn, "late-http-owner", () -> true));
    }

    void finish(ManagedTurnExecution execution) {
        execution.toolCompleted(task);
        execution.closeInputs();
        execution.finish("succeeded");
    }

    Long claimedAt() {
        return jdbc.queryForObject(
                "SELECT resume_claimed_at FROM ma.turn WHERE scope_key=? AND turn_id=?",
                Long.class,
                scope.messageGroup(),
                turn);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void callbackReturnsBeforeWorkAndGuardSurvivesReceiptRemoval(int concurrency) throws Exception {
        var bus = mock(ManagedMessageBus.class);
        when(bus.active(any())).thenReturn(true);
        var runtime =
                new ManagedHarnessRuntimeTurns(
                        bus,
                        leases,
                        admission,
                        journal,
                        writer,
                        mapper,
                        renewals,
                        ttl,
                        Duration.ofSeconds(15),
                        concurrency);
        resources.add(runtime);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var error = new AtomicReference<Throwable>();
        var calls = new AtomicInteger();
        var delivery =
                new ManagedPushDelivery(
                        "receipt",
                        "client",
                        Channel.TURN_RESUMES,
                        "topic",
                        "group",
                        "broker",
                        1,
                        new ManagedMessageBus.Message(
                                resume.resumeId(),
                                scope.messageGroup(),
                                mapper.writeValueAsBytes(resume)));
        runtime.consume(
                delivery,
                execution -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        execution.guard().check();
                        finish(execution);
                    } catch (Throwable failure) {
                        error.set(failure);
                    } finally {
                        finished.countDown();
                    }
                });
        assertEquals(1L, entered.getCount(), "Preparation must still wait for confirmed ACK");
        delivery.handoff().confirm(); // The broker confirms only after the callback returns.
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertNotNull(claimedAt());
        assertNotEquals("succeeded", admission.turn(scope, turn).status());
        // Lost ACK redelivery is a no-op while the same durable claim is alive.
        runtime.consume(delivery, execution -> fail("Duplicate runner"));
        when(bus.active(any())).thenReturn(false);
        release.countDown();
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertNull(error.get(), () -> String.valueOf(error.get()));
        assertEquals(1, calls.get());
        assertEquals("succeeded", admission.turn(scope, turn).status());
        assertNull(claimedAt());
    }

    @Test
    void guardInterruptionDoesNotPoisonPooledClaimCleanup() throws Exception {
        var config = new HikariConfig();
        config.setDataSource(jdbc.getDataSource());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(2000);
        var pool = new HikariDataSource(config);
        resources.add(pool);
        var pooledAdmission = new ManagedAdmissionStore(pool, mapper);
        var workers = new ManualExecutor();
        var permits = new Semaphore(1);
        var manager =
                new ManagedResumeExecutions(
                        leases,
                        pooledAdmission,
                        journal,
                        writer,
                        renewals,
                        ttl,
                        Duration.ofSeconds(15),
                        permits,
                        workers);
        resources.add(manager);
        var cleanupError = new AtomicReference<Throwable>();
        manager.consume(
                resume,
                admission.turn(scope, turn),
                execution -> {
                    var occupied = pool.getConnection();
                    renewals.schedule(
                            () -> {
                                try {
                                    occupied.close();
                                } catch (Exception error) {
                                    cleanupError.set(error);
                                }
                            },
                            150,
                            TimeUnit.MILLISECONDS);
                    execution.guard().invalidate("simulated guard loss", null);
                    assertTrue(Thread.currentThread().isInterrupted());
                    execution.guard().check();
                });
        workers.runNext();
        assertFalse(Thread.currentThread().isInterrupted());
        assertNull(cleanupError.get());
        assertEquals(1, permits.availablePermits());
        assertNull(admission.turn(scope, turn).executionToken());
        assertEquals("preparing", admission.turn(scope, turn).status());
        assertTrue(journal.suspensions().pending(resume));
        assertTrue(admission.turn(scope, turn).executionEpoch() > 1);
    }

    @Test
    void capacityRejectionDoesNotWriteClaimOrAdvanceEpoch() {
        var manager = manager(new Semaphore(0), new ManualExecutor());
        assertThrows(
                ManagedDeliverySuspended.class,
                () -> manager.consume(resume, admission.turn(scope, turn), this::finish));
        assertNull(claimedAt());
        assertEquals(1, admission.session(scope).executionEpoch());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void foreignOwnerConflictDoesNotAckOrWriteClaim(int concurrency) {
        var other = leases.acquire(scope.key("execution"), ttl).orElseThrow();
        try {
            var manager = manager(ManagedExecutionCapacity.of(concurrency), new ManualExecutor());
            assertThrows(
                    ManagedDeliverySuspended.class,
                    () -> manager.consume(resume, admission.turn(scope, turn), this::finish));
            assertNull(claimedAt());
        } finally {
            leases.release(other);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void rejectedExecutorLeavesRecoverableClaimAndReleasesCapacity(int concurrency) {
        var workers = new ManualExecutor();
        workers.shutdown();
        var permits = ManagedExecutionCapacity.of(concurrency);
        var manager = manager(permits, workers);
        assertThrows(
                RejectedExecutionException.class,
                () -> manager.consume(resume, admission.turn(scope, turn), this::finish));
        assertNotNull(claimedAt());
        assertEquals(0, permits.active());
        clock.addAndGet(60000);
        assertEquals(1, recovery().repair());
        assertTrue(journal.suspensions().pending(resume));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void crashAfterClaimAndAckBeforeWorkerStartsRepublishesSameResume(int concurrency)
            throws Exception {
        var workers = new ManualExecutor();
        var permits = ManagedExecutionCapacity.of(concurrency);
        var manager = manager(permits, workers);
        manager.consume(resume, admission.turn(scope, turn), this::finish);
        long oldEpoch = admission.turn(scope, turn).executionEpoch();
        assertEquals(1, permits.active());
        manager.close(); // No worker ran; receipt was already accepted, no SQL cleanup is required.
        assertEquals(0, permits.active());
        clock.addAndGet(60000);
        assertEquals(1, recovery().repair());
        assertThrows(
                ManagedStateException.class, () -> admission.requireEpoch(scope, turn, oldEpoch));
        assertEquals(resume, journal.suspensions().pending(10, scope.messageGroup()).get(0));
        var bus = mock(ManagedMessageBus.class);
        when(bus.publish(any(), any())).thenReturn(new ManagedMessageBus.Published("recovered"));
        assertEquals(
                1,
                new ManagedResumePublisher(journal.suspensions(), bus, leases, mapper).retry(10));
        verify(bus)
                .publish(eq(Channel.TURN_RESUMES), argThat(m -> m.id().equals(resume.resumeId())));
        var nextWorkers = new ManualExecutor();
        var next = manager(ManagedExecutionCapacity.of(concurrency), nextWorkers);
        next.consume(resume, admission.turn(scope, turn), this::finish);
        nextWorkers.runNext();
        assertEquals("succeeded", admission.turn(scope, turn).status());
        assertNull(claimedAt());
        assertEquals(0, recovery().repair());
    }

    @Test
    void liveLeaseDoesNotCauseSqlHeartbeatOrRequeue() throws Exception {
        var manager = manager(new Semaphore(1), new ManualExecutor());
        manager.consume(resume, admission.turn(scope, turn), this::finish);
        var claimed = claimedAt();
        var epoch = admission.session(scope).executionEpoch();
        // Old claim timestamp alone is not a lease expiry.
        jdbc.update(
                "UPDATE ma.turn SET resume_claimed_at=? WHERE scope_key=?",
                claimed - 60000,
                scope.messageGroup());
        assertEquals(0, recovery().repair());
        assertEquals(claimed - 60000, claimedAt());
        assertEquals(epoch, admission.session(scope).executionEpoch());
        assertFalse(journal.suspensions().pending(resume));
    }

    @Test
    void sessionQueueCannotBypassMqForClaimedOrRecoveringResume() throws Exception {
        var manager = manager(new Semaphore(1), new ManualExecutor());
        manager.consume(resume, admission.turn(scope, turn), this::finish);
        assertNull(admission.queue().candidate(scope));
        assertFalse(admission.queue().due(1000).contains(scope));
        assertThrows(
                ManagedStateException.class, () -> admission.activate(scope, turn, 1, "bypass"));
        manager.close();
        clock.addAndGet(60000);
        recovery().repair();
        assertNull(admission.queue().candidate(scope));
        assertThrows(
                ManagedStateException.class, () -> admission.activate(scope, turn, 1, "bypass2"));
    }

    @Test
    void staleRepairSnapshotCannotRequeueNewOwnerOrCompletedBoundary() throws Exception {
        var workers = new ManualExecutor();
        var first = manager(new Semaphore(1), workers);
        first.consume(resume, admission.turn(scope, turn), this::finish);
        var stale =
                recovery().candidates(Long.MAX_VALUE, null, 10).stream()
                        .filter(c -> c.resume().equals(resume))
                        .findFirst()
                        .orElseThrow();
        first.close();
        var nextWorkers = new ManualExecutor();
        var next = manager(new Semaphore(1), nextWorkers);
        next.consume(resume, admission.turn(scope, turn), this::finish);
        assertFalse(recovery().recover(stale));
        nextWorkers.runNext();
        assertFalse(recovery().recover(stale));
        assertEquals("succeeded", admission.turn(scope, turn).status());
    }

    @Test
    void delayedPublisherCannotEraseRecoveryResponsibility() throws Exception {
        var first = manager(new Semaphore(1), new ManualExecutor());
        first.consume(resume, admission.turn(scope, turn), this::finish);
        first.close();
        clock.addAndGet(60000);
        assertEquals(1, recovery().repair());
        journal.suspensions().published(resume, "late-old-publisher");
        assertFalse(journal.suspensions().pending(resume));
        clock.addAndGet(60000);
        assertEquals(1, recovery().repair());
        assertTrue(journal.suspensions().pending(resume));
    }

    @Test
    void failedClaimTransactionDoesNotAckOrLeavePartialActivation() {
        String constraint = "claim_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                "ALTER TABLE ma.turn ADD CONSTRAINT "
                        + constraint
                        + " CHECK(resume_claimed_at IS NULL OR scope_key<>'"
                        + scope.messageGroup()
                        + "')");
        var permits = new Semaphore(1);
        var workers = new ManualExecutor();
        try {
            var manager = manager(permits, workers);
            assertThrows(
                    DataIntegrityViolationException.class,
                    () -> manager.consume(resume, admission.turn(scope, turn), this::finish));
            assertEquals(1, admission.session(scope).executionEpoch());
            assertNull(admission.turn(scope, turn).resumeId());
            assertNull(claimedAt());
            assertEquals(1, permits.availablePermits());
            assertEquals(
                    "ready",
                    jdbc.queryForObject(
                            "SELECT status FROM ma.turn_suspension"
                                    + " WHERE scope_key=? AND suspension_id=?",
                            String.class,
                            scope.messageGroup(),
                            resume.suspensionId()));
        } finally {
            jdbc.execute("ALTER TABLE ma.turn DROP CONSTRAINT " + constraint);
        }
    }

    @Test
    void expiredOwnerCannotRunOrCommitAfterRecoveryFencesItsEpoch() throws Exception {
        var oldWorkers = new ManualExecutor();
        var old = manager(new Semaphore(1), oldWorkers);
        var oldCalls = new AtomicInteger();
        old.consume(resume, admission.turn(scope, turn), e -> oldCalls.incrementAndGet());
        long oldEpoch = admission.session(scope).executionEpoch();
        clock.addAndGet(61000); // Simulates a process that no longer renews its Redis lease.
        assertEquals(1, recovery().repair());
        assertThrows(
                ManagedStateException.class, () -> admission.requireEpoch(scope, turn, oldEpoch));
        oldWorkers.runNext();
        assertEquals(0, oldCalls.get());
        var workers = new ManualExecutor();
        var next = manager(new Semaphore(1), workers);
        next.consume(resume, admission.turn(scope, turn), this::finish);
        workers.runNext();
        assertEquals("succeeded", admission.turn(scope, turn).status());
    }

    @Test
    void postAckFailureRemainsRetryableAndNextSuspensionRetiresClaim() throws Exception {
        var workers = new ManualExecutor();
        var manager = manager(new Semaphore(1), workers);
        manager.consume(
                resume,
                admission.turn(scope, turn),
                e -> {
                    throw new IllegalStateException("transient");
                });
        workers.runNext();
        assertNotEquals("failed", admission.turn(scope, turn).status());
        assertNotNull(claimedAt());
        clock.addAndGet(60000);
        assertEquals(1, recovery().repair());
        manager.consume(
                resume,
                admission.turn(scope, turn),
                e -> {
                    var state =
                            new SessionEvent(
                                    1,
                                    "resumed-state",
                                    e.head().sequence() + 1,
                                    System.currentTimeMillis(),
                                    "state/checkpoint",
                                    "run",
                                    turn,
                                    true,
                                    mapper.writeValueAsString(
                                            Map.of(
                                                    "stateJson",
                                                    AgentState.builder().build().toJson())));
                    e.toolCompleted(task);
                    e.boundary(
                            "resumed-state",
                            List.of(state),
                            List.of(new ToolIntent("call2", "exec", "{}")),
                            List.of(),
                            null);
                    e.suspend(
                            "tool_"
                                    + ManagedScope.fingerprint(
                                            scope.messageGroup(), turn, "call2"));
                });
        workers.runNext();
        assertEquals("waiting_tool", admission.turn(scope, turn).status());
        assertNull(claimedAt());
        assertTrue(journal.suspensions().settled(resume));
        assertEquals(0, recovery().repair());
    }

    @Test
    void redisOutageDoesNotRevokeOrRequeueAClaim() throws Exception {
        var manager = manager(new Semaphore(1), new ManualExecutor());
        manager.consume(resume, admission.turn(scope, turn), this::finish);
        var before = claimedAt();
        var epoch = admission.session(scope).executionEpoch();
        var unavailable = mock(ManagedLeaseStore.class);
        when(unavailable.owns(any())).thenThrow(new ManagedTransportException("offline"));
        clock.addAndGet(60000);
        var repair =
                new ManagedResumeRecovery(journal.suspensions(), unavailable, interval, clock::get);
        assertEquals(0, repair.repair());
        assertEquals(before, claimedAt());
        assertEquals(epoch, admission.session(scope).executionEpoch());
        assertFalse(journal.suspensions().pending(resume));
    }

    @Test
    void competingRepairersFenceAndRequeueOnlyOneAttempt() throws Exception {
        var manager = manager(new Semaphore(1), new ManualExecutor());
        manager.consume(resume, admission.turn(scope, turn), this::finish);
        var claim =
                recovery().candidates(Long.MAX_VALUE, null, 256).stream()
                        .filter(c -> c.resume().equals(resume))
                        .findFirst()
                        .orElseThrow();
        // Simulate process loss without graceful abandonment of the claim.
        leases.release(
                new ManagedLeaseStore.Lease(
                        scope.key("execution"), admission.turn(scope, turn).executionToken()));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a =
                    pool.submit(
                            () -> {
                                start.await();
                                return recovery().recover(claim);
                            });
            var b =
                    pool.submit(
                            () -> {
                                start.await();
                                return recovery().recover(claim);
                            });
            start.countDown();
            int changes =
                    (a.get(5, TimeUnit.SECONDS) ? 1 : 0) + (b.get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, changes);
            assertEquals(claim.epoch() + 1, admission.session(scope).executionEpoch());
            assertTrue(journal.suspensions().pending(resume));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void publisherRepairRepublishesWithoutExecutingHarness() throws Exception {
        var manager = manager(new Semaphore(1), new ManualExecutor());
        manager.consume(resume, admission.turn(scope, turn), e -> fail("No worker should run"));
        manager.close();
        jdbc.update(
                "UPDATE ma.turn SET resume_claimed_at=? WHERE scope_key=?",
                System.currentTimeMillis() - 1000,
                scope.messageGroup());
        var bus = mock(ManagedMessageBus.class);
        var published = new CountDownLatch(1);
        when(bus.publish(any(), any()))
                .thenAnswer(
                        call -> {
                            ManagedMessageBus.Message message = call.getArgument(1);
                            assertEquals(resume.resumeId(), message.id());
                            published.countDown();
                            return new ManagedMessageBus.Published("repair");
                        });
        try (var publisher =
                new ManagedResumePublisher(
                        journal.suspensions(), bus, leases, mapper, Duration.ofMillis(50))) {
            publisher.start();
            assertTrue(published.await(5, TimeUnit.SECONDS));
            assertEquals("preparing", admission.turn(scope, turn).status());
            assertNotNull(claimedAt());
        }
    }

    @Test
    void acknowledgedGateIsRequiredBeforePreparationAndCapacitySurvivesAck() throws Exception {
        var workers = new ManualExecutor();
        var permits = new Semaphore(1);
        var gate = new ManagedReceiveCapacity(permits, 1).reserve(1).get(0);
        var manager = manager(permits, workers);
        var calls = new AtomicInteger();
        manager.consume(
                resume,
                admission.turn(scope, turn),
                e -> {
                    calls.incrementAndGet();
                    finish(e);
                },
                gate);
        assertNotNull(claimedAt());
        assertEquals(0, permits.availablePermits());
        var thread = Executors.newSingleThreadExecutor();
        try {
            var work = thread.submit(workers::runNext);
            assertThrows(TimeoutException.class, () -> work.get(100, TimeUnit.MILLISECONDS));
            assertEquals(0, calls.get());
            gate.confirm();
            work.get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
            assertEquals(1, permits.availablePermits());
        } finally {
            gate.close();
            thread.shutdownNow();
        }
    }

    @Test
    void crashBeforeAckAndLostConfirmationNeverStartPreparationAndRecoverSameIdentity()
            throws Exception {
        var workers = new ManualExecutor();
        var permits = new Semaphore(1);
        var gate = new ManagedReceiveCapacity(permits, 1).reserve(1).get(0);
        var manager = manager(permits, workers);
        manager.consume(
                resume, admission.turn(scope, turn), e -> fail("ACK was never confirmed"), gate);
        // Broker may or may not have accepted ACK. Both cases retain the same durable identity.
        gate.close();
        workers.runNext();
        assertEquals(1, permits.availablePermits());
        clock.addAndGet(60000);
        assertEquals(1, recovery().repair());
        assertEquals(resume, journal.suspensions().pending(10, scope.messageGroup()).get(0));
    }

    @Test
    void unavailablePreparationAfterAckIsRecoverableAndNotPermanentFailure() throws Exception {
        var workers = new ManualExecutor();
        var manager = manager(new Semaphore(1), workers);
        var gate = new ManagedDeliveryHandoff();
        manager.consume(
                resume,
                admission.turn(scope, turn),
                e -> {
                    throw new TimeoutException("catalog preparation");
                },
                gate);
        gate.confirm();
        workers.runNext();
        assertEquals("preparing", admission.turn(scope, turn).status());
        clock.addAndGet(60000);
        assertEquals(1, recovery().repair());
        assertEquals(resume, journal.suspensions().pending(10, scope.messageGroup()).get(0));
    }

    @Test
    void
            contentionReturnsSuccessfullyOnlyAfterDurableSameResumeRetryAndDelayedPublisherCannotEraseIt()
                    throws Exception {
        var bus = mock(ManagedMessageBus.class);
        when(bus.active(any())).thenReturn(true);
        var runtime =
                new ManagedHarnessRuntimeTurns(
                        bus,
                        leases,
                        admission,
                        journal,
                        writer,
                        mapper,
                        renewals,
                        ttl,
                        Duration.ofSeconds(15),
                        1);
        resources.add(runtime);
        var other = leases.acquire(scope.key("execution"), ttl).orElseThrow();
        long version = journal.suspensions().publicationVersion(resume);
        try {
            var gate = runtime.receiveCapacity().reserve(1).get(0);
            var delivery =
                    new ManagedPushDelivery(
                            "retry",
                            "client",
                            Channel.TURN_RESUMES,
                            "topic",
                            "group",
                            "broker",
                            1,
                            new ManagedMessageBus.Message(
                                    resume.resumeId(),
                                    scope.messageGroup(),
                                    mapper.writeValueAsBytes(resume)),
                            gate);
            runtime.consume(delivery, e -> fail("Foreign owner"));
            assertNull(claimedAt());
            assertEquals(
                    "pending",
                    jdbc.queryForObject(
                            "SELECT delivery_status FROM ma.turn_resume WHERE scope_key=?",
                            String.class,
                            scope.messageGroup()));
            assertTrue(
                    journal.suspensions().pending(10, scope.messageGroup()).isEmpty(),
                    "Backoff must survive restart");
            assertFalse(journal.suspensions().published(resume, "late", version));
            gate.confirm();
            assertEquals(1, runtime.receiveCapacity().availableExecutions());
            assertEquals(1, runtime.receiveCapacity().availableHandoffs());
            jdbc.update(
                    "UPDATE ma.turn_resume SET retry_at=0 WHERE scope_key=?", scope.messageGroup());
            assertEquals(resume, journal.suspensions().pending(10, scope.messageGroup()).get(0));
        } finally {
            leases.release(other);
        }
    }

    @Test
    void mismatchedGenerationCannotBeConvertedIntoSuccessfulRetryAck() {
        var forged =
                new ManagedSuspensions.Resume(
                        scope,
                        resume.resumeId(),
                        turn,
                        resume.suspensionId(),
                        resume.suspensionEpoch() + 1,
                        resume.publicationSequence());
        assertThrows(
                ManagedStateException.class,
                () -> journal.suspensions().defer(forged, Duration.ofSeconds(1)));
        assertEquals(0, journal.suspensions().publicationVersion(resume));
    }

    @Test
    void healthyOwnerWithLostAckConfirmationTimesOutAndCannotBeRevivedByLateAck() throws Exception {
        var workers = new ManualExecutor();
        var permits = new Semaphore(1);
        var manager =
                new ManagedResumeExecutions(
                        leases,
                        admission,
                        journal,
                        writer,
                        renewals,
                        Duration.ofMillis(100),
                        Duration.ofMillis(20),
                        permits,
                        workers);
        resources.add(manager);
        var gate = new ManagedDeliveryHandoff();
        manager.consume(resume, admission.turn(scope, turn), e -> fail("Unconfirmed ACK"), gate);
        long epoch = admission.turn(scope, turn).executionEpoch();
        // Duplicate ACK does not open the original attempt's preparation gate.
        var duplicate = new ManagedDeliveryHandoff();
        manager.consume(resume, admission.turn(scope, turn), e -> fail("Duplicate"), duplicate);
        duplicate.confirm();
        assertEquals(epoch, admission.turn(scope, turn).executionEpoch());
        workers.runNext(); // bounded ACK timeout while the process/renewal scheduler is healthy
        assertEquals(1, permits.availablePermits());
        gate.confirm(); // a late response cannot revive the finished worker
        assertEquals("preparing", admission.turn(scope, turn).status());
        clock.addAndGet(60000);
        assertEquals(1, recovery().repair());
    }

    @Test
    void failedRetryTransactionKeepsOriginalReceiptUnacknowledged() throws Exception {
        String constraint = "retry_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                "ALTER TABLE ma.turn_resume ADD CONSTRAINT "
                        + constraint
                        + " CHECK(retry_at=0 OR scope_key<>'"
                        + scope.messageGroup()
                        + "')");
        var bus = mock(ManagedMessageBus.class);
        when(bus.active(any())).thenReturn(true);
        var runtime =
                new ManagedHarnessRuntimeTurns(
                        bus,
                        leases,
                        admission,
                        journal,
                        writer,
                        mapper,
                        renewals,
                        ttl,
                        Duration.ofSeconds(15),
                        1);
        resources.add(runtime);
        var other = leases.acquire(scope.key("execution"), ttl).orElseThrow();
        var gate = runtime.receiveCapacity().reserve(1).get(0);
        try {
            var delivery =
                    new ManagedPushDelivery(
                            "retry",
                            "client",
                            Channel.TURN_RESUMES,
                            "topic",
                            "group",
                            "broker",
                            1,
                            new ManagedMessageBus.Message(
                                    resume.resumeId(),
                                    scope.messageGroup(),
                                    mapper.writeValueAsBytes(resume)),
                            gate);
            assertThrows(
                    DataIntegrityViolationException.class,
                    () -> runtime.consume(delivery, this::finish));
            assertThrows(TimeoutException.class, () -> gate.await(Duration.ofMillis(1)));
            assertEquals(0, journal.suspensions().publicationVersion(resume));
            assertEquals(
                    "queued",
                    jdbc.queryForObject(
                            "SELECT delivery_status FROM ma.turn_resume WHERE scope_key=?",
                            String.class,
                            scope.messageGroup()));
        } finally {
            gate.close();
            leases.release(other);
            jdbc.execute("ALTER TABLE ma.turn_resume DROP CONSTRAINT " + constraint);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void initialReadyClaimsBeforeAckAndPreparesOnlyAfterConfirmedAck(int concurrency)
            throws Exception {
        var initialScope = new ManagedScope("t", "n", "initial-" + UUID.randomUUID());
        admission.createSession(initialScope, "{}");
        admission.queue().enable(initialScope);
        var initial = admission.acceptTurn(initialScope, "u", "first", "{}");
        var workers = new ManualExecutor();
        var permits = ManagedExecutionCapacity.of(concurrency);
        var manager = manager(permits, workers);
        var handoff = new ManagedDeliveryHandoff();
        var calls = new AtomicInteger();
        manager.consumeInitial(
                initialScope,
                initial,
                execution -> {
                    calls.incrementAndGet();
                    execution.boundary(
                            "initial-checkpoint",
                            List.of(
                                    new SessionEvent(
                                            1,
                                            "initial-checkpoint",
                                            1,
                                            System.currentTimeMillis(),
                                            "state/checkpoint",
                                            "run",
                                            initial.id(),
                                            true,
                                            mapper.writeValueAsString(
                                                    Map.of(
                                                            "stateJson",
                                                            AgentState.builder()
                                                                    .build()
                                                                    .toJson())))),
                            List.of(),
                            List.of(),
                            null);
                    execution.closeInputs();
                    execution.finish("succeeded");
                },
                handoff);
        assertEquals("preparing", admission.turn(initialScope, initial.id()).status());
        assertEquals(0, calls.get());
        manager.consumeInitial(
                initialScope,
                admission.turn(initialScope, initial.id()),
                e -> fail("duplicate initial"),
                new ManagedDeliveryHandoff());
        handoff.confirm();
        workers.runNext();
        assertEquals(1, calls.get());
        assertEquals("succeeded", admission.turn(initialScope, initial.id()).status());
        assertEquals(0, permits.active());
    }

    @Test
    void initialCapacityFailureDoesNotClaimAndCrashRepairOnlyRepublishesReady() throws Exception {
        var initialScope = new ManagedScope("t", "n", "crash-initial-" + UUID.randomUUID());
        admission.createSession(initialScope, "{}");
        admission.queue().enable(initialScope);
        var initial = admission.acceptTurn(initialScope, "u", "first", "{}");
        var none = manager(new Semaphore(0), new ManualExecutor());
        assertThrows(
                ManagedDeliverySuspended.class,
                () ->
                        none.consumeInitial(
                                initialScope, initial, e -> fail(), new ManagedDeliveryHandoff()));
        assertNull(admission.turn(initialScope, initial.id()).executionToken());
        var manager = manager(new Semaphore(1), new ManualExecutor());
        var handoff = new ManagedDeliveryHandoff();
        manager.consumeInitial(
                initialScope, initial, e -> fail("repair must not execute"), handoff);
        handoff.confirm();
        var claim = admission.turn(initialScope, initial.id());
        leases.release(
                new ManagedLeaseStore.Lease(initialScope.key("execution"), claim.executionToken()));
        jdbc.update(
                "UPDATE ma.turn SET resume_claimed_at=1 WHERE scope_key=?",
                initialScope.messageGroup());
        var repair =
                new ManagedInitialReadyRecovery(
                        jdbc.getDataSource(), mapper, leases, Duration.ofMillis(1));
        assertEquals(1, repair.repair());
        assertEquals(0, repair.repair());
        assertNotNull(admission.queue().notification(initialScope));
        assertNull(admission.turn(initialScope, initial.id()).executionToken());
    }

    @Test
    void asyncAckAndModelWaitReleaseTheWorkerButKeepClaimAndCapacity() throws Exception {
        var workers = Executors.newSingleThreadExecutor();
        var permits = new Semaphore(1);
        var manager = manager(ManagedExecutionCapacity.bounded(permits), workers);
        var handoff = new ManagedDeliveryHandoff();
        var entered = new CountDownLatch(1);
        var model = Sinks.<Void>one();
        var execution = new AtomicReference<ManagedTurnExecution>();
        var calls = new AtomicInteger();
        ManagedHarnessRuntimeTurns.AsyncRunner runner =
                (e, scheduler, stop) -> {
                    execution.set(e);
                    calls.incrementAndGet();
                    entered.countDown();
                    return model.asMono()
                            .publishOn(scheduler)
                            .then(Mono.fromRunnable(() -> finish(e)));
                };
        manager.consume(resume, admission.turn(scope, turn), runner, handoff);
        // A single worker must remain available while ACK has not been confirmed.
        workers.submit(() -> {}).get(2, TimeUnit.SECONDS);
        assertEquals(1, entered.getCount());
        assertEquals(0, permits.availablePermits());
        handoff.confirm();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        workers.submit(() -> {}).get(2, TimeUnit.SECONDS);
        assertTrue(
                leases.owns(
                        new ManagedLeaseStore.Lease(
                                scope.key("execution"), execution.get().guard().token())));
        manager.consume(resume, admission.turn(scope, turn), runner);
        assertEquals(1, calls.get(), "A duplicate receipt must not start another model");
        assertEquals(0, permits.availablePermits());
        model.tryEmitEmpty();
        awaitPermit(permits);
        assertEquals("succeeded", admission.turn(scope, turn).status());
        assertTrue(journal.suspensions().settled(resume));
    }

    @Test
    void asyncShutdownCancelsRunnerBeforeReleasingDurableClaim() throws Exception {
        var workers = Executors.newSingleThreadExecutor();
        var permits = new Semaphore(1);
        var manager = manager(ManagedExecutionCapacity.bounded(permits), workers);
        var entered = new CountDownLatch(1);
        var cleaned = new CountDownLatch(1);
        ManagedHarnessRuntimeTurns.AsyncRunner runner =
                (e, scheduler, stop) -> {
                    entered.countDown();
                    return stop.flatMap(
                            error ->
                                    Mono.<Void>fromRunnable(
                                                    () -> {
                                                        e.guard()
                                                                .check(); // Lease stays valid until
                                                        // cooperative cleanup
                                                        // returns.
                                                        cleaned.countDown();
                                                    })
                                            .subscribeOn(scheduler)
                                            .then(Mono.error(error)));
                };
        manager.consume(resume, admission.turn(scope, turn), runner);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        manager.close();
        assertEquals(0, cleaned.getCount());
        assertEquals(1, permits.availablePermits());
        assertNull(admission.turn(scope, turn).executionToken());
        assertFalse(journal.suspensions().settled(resume));
        assertNotEquals("failed", admission.turn(scope, turn).status());
    }

    @Test
    void asyncUnconfirmedReceiptCannotStartAfterShutdown() throws Exception {
        var workers = Executors.newSingleThreadExecutor();
        var permits = new Semaphore(1);
        var manager = manager(ManagedExecutionCapacity.bounded(permits), workers);
        var handoff = new ManagedDeliveryHandoff();
        var calls = new AtomicInteger();
        ManagedHarnessRuntimeTurns.AsyncRunner runner =
                (e, scheduler, stop) -> {
                    calls.incrementAndGet();
                    return Mono.empty();
                };
        manager.consume(resume, admission.turn(scope, turn), runner, handoff);
        workers.submit(() -> {}).get(2, TimeUnit.SECONDS);
        manager.close();
        handoff.confirm();
        assertEquals(0, calls.get());
        assertEquals(1, permits.availablePermits());
        assertNull(admission.turn(scope, turn).executionToken());
    }

    @Test
    void asyncFailureRepairsTheSameResumeThroughMq() throws Exception {
        var workers = Executors.newSingleThreadExecutor();
        var permits = new Semaphore(1);
        var manager = manager(ManagedExecutionCapacity.bounded(permits), workers);
        ManagedHarnessRuntimeTurns.AsyncRunner runner =
                (e, scheduler, stop) -> Mono.error(new TimeoutException("preparation"));
        manager.consume(resume, admission.turn(scope, turn), runner);
        awaitPermit(permits);
        assertEquals("preparing", admission.turn(scope, turn).status());
        clock.addAndGet(60000);
        assertEquals(1, recovery().repair());
        assertEquals(resume, journal.suspensions().pending(10, scope.messageGroup()).get(0));
    }

    @Test
    void nonCooperativeAsyncProviderIsFencedAtShutdownDeadline() throws Exception {
        var workers = Executors.newSingleThreadExecutor();
        var permits = new Semaphore(1);
        var manager = manager(ManagedExecutionCapacity.bounded(permits), workers);
        var entered = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var execution = new AtomicReference<ManagedTurnExecution>();
        ManagedHarnessRuntimeTurns.AsyncRunner runner =
                (e, scheduler, stop) -> {
                    execution.set(e);
                    entered.countDown();
                    return Mono.<Void>never().doOnCancel(cancelled::countDown);
                };
        manager.consume(resume, admission.turn(scope, turn), runner);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        manager.close();
        assertEquals(0, cancelled.getCount());
        assertEquals(1, permits.availablePermits());
        assertNull(admission.turn(scope, turn).executionToken());
        assertThrows(ManagedTransportException.class, () -> execution.get().guard().check());
    }

    @Test
    void asyncLeaseLossStopsExecutionAndLeavesTheSameResumeRecoverable() throws Exception {
        var workers = Executors.newSingleThreadExecutor();
        var permits = new Semaphore(1);
        var manager = manager(ManagedExecutionCapacity.bounded(permits), workers);
        var entered = new CountDownLatch(1);
        var execution = new AtomicReference<ManagedTurnExecution>();
        var notified = new CountDownLatch(1);
        ManagedHarnessRuntimeTurns.AsyncRunner runner =
                (e, scheduler, stop) -> {
                    execution.set(e);
                    entered.countDown();
                    return stop.doOnNext(error -> notified.countDown()).flatMap(Mono::error);
                };
        manager.consume(resume, admission.turn(scope, turn), runner);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var guard = execution.get().guard();
        leases.release(new ManagedLeaseStore.Lease(scope.key("execution"), guard.token()));
        assertThrows(ManagedTransportException.class, guard::renew);
        assertTrue(notified.await(2, TimeUnit.SECONDS));
        awaitPermit(permits);
        assertFalse(
                workers.submit(() -> Thread.currentThread().isInterrupted())
                        .get(2, TimeUnit.SECONDS));
        assertNotEquals("failed", admission.turn(scope, turn).status());
        clock.addAndGet(60000);
        assertEquals(1, recovery().repair());
        assertEquals(resume, journal.suspensions().pending(10, scope.messageGroup()).get(0));
    }

    private void awaitPermit(Semaphore permits) throws InterruptedException {
        assertTrue(
                permits.tryAcquire(5, TimeUnit.SECONDS), "Async cleanup did not release capacity");
        permits.release();
    }

    static final class ManualExecutor extends AbstractExecutorService {
        private final List<Runnable> queue = new ArrayList<>();
        private boolean stopped;

        @Override
        public void execute(Runnable task) {
            if (stopped) throw new RejectedExecutionException("fixture");
            queue.add(task);
        }

        void runNext() {
            queue.remove(0).run();
        }

        @Override
        public void shutdown() {
            stopped = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            stopped = true;
            var pending = List.copyOf(queue);
            queue.clear();
            return pending;
        }

        @Override
        public boolean isShutdown() {
            return stopped;
        }

        @Override
        public boolean isTerminated() {
            return stopped && queue.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }
}
