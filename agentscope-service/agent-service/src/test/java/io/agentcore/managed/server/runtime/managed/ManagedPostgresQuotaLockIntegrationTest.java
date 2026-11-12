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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.web.managed.SessionUsagePricer;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL row locks, concurrent replay and durable eventual application accounting. */
@EnabledIfEnvironmentVariable(named = "MANAGED_DATABASE_TEST_URI", matches = ".+")
@Timeout(30)
class ManagedPostgresQuotaLockIntegrationTest {
    private ManagedAdmissionStore admission;
    private ManagedBudgetStore budgets;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private String configuration;
    private ManagedScope root;

    @BeforeEach
    void setup() {
        var source =
                new DriverManagerDataSource(
                        System.getenv("MANAGED_DATABASE_TEST_URI"),
                        "managed",
                        "managed-local-test");
        ManagedAdmissionStore.initialize(source);
        var mapper = new ObjectMapper();
        admission = new ManagedAdmissionStore(source, mapper);
        budgets =
                new ManagedBudgetStore(
                        source,
                        mapper,
                        (model, usage) -> new SessionUsagePricer.Quote(BigDecimal.ZERO, "USD"));
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(15);
        jdbc.execute(
                "TRUNCATE ma.application_usage_delta"); // Exclusively owned local test database.
        configuration = "{\"application_id\":\"" + UUID.randomUUID() + "\"}";
        root = scope();
        admission.createSession(root, configuration);
    }

    private ManagedTurnExecution execution(ManagedScope scope) {
        admission.createSession(scope, configuration);
        String turn = admission.acceptTurn(scope, "actor", "one", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "brain");
        var execution = mock(ManagedTurnExecution.class);
        when(execution.scope()).thenReturn(scope);
        when(execution.turnId()).thenReturn(turn);
        when(execution.epoch()).thenReturn(1L);
        when(execution.admission()).thenReturn(admission);
        return execution;
    }

    @Test
    void quotaWriterDoesNotBlockAdmissionReservationSettlementOrUnchangedPolicy() throws Exception {
        var execution = execution(scope());
        var next = scope();
        admission.createSession(next, configuration);
        admission.applicationPolicy(root, Map.of("version", 1, "tokenBudget", 1000));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var writer =
                    pool.submit(
                            () ->
                                    tx.executeWithoutResult(
                                            ignored -> {
                                                jdbc.queryForObject(
                                                        "SELECT quota_key FROM ma.admission_quota"
                                                            + " WHERE quota_key=(SELECT quota_key"
                                                            + " FROM ma.session WHERE scope_key=?)"
                                                            + " FOR NO KEY UPDATE",
                                                        String.class,
                                                        root.messageGroup());
                                                held.countDown();
                                                try {
                                                    assertTrue(release.await(10, TimeUnit.SECONDS));
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(e);
                                                }
                                            }));
            assertTrue(held.await(5, TimeUnit.SECONDS));
            tx.executeWithoutResult(
                    ignored -> {
                        jdbc.execute("SET LOCAL lock_timeout='400ms'");
                        admission.applicationPolicy(
                                root, Map.of("version", 1, "tokenBudget", 1000));
                        admission.acceptTurn(next, "actor", "next", "{}");
                        budgets.reserve(
                                execution.scope(),
                                execution.turnId(),
                                execution.epoch(),
                                "call",
                                "model",
                                execution.admission());
                        settle(execution.scope(), execution);
                    });
            assertEquals(0, applicationTokens());
            release.countDown();
            writer.get(5, TimeUnit.SECONDS);
            try (var aggregation = new ManagedApplicationUsage(jdbc.getDataSource())) {
                aggregation.flush(512);
            }
            assertEquals(20, applicationTokens());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void policyChangesDoNotWaitForOtherSessionsModelReservations() throws Exception {
        var execution = execution(scope());
        var reserved = new CyclicBarrier(2);
        var changed = new CyclicBarrier(2);
        parallel(
                index -> {
                    if (index == 0)
                        return tx.execute(
                                ignored -> {
                                    budgets.reserve(
                                            execution.scope(),
                                            execution.turnId(),
                                            execution.epoch(),
                                            "call",
                                            "model",
                                            execution.admission());
                                    await(reserved);
                                    await(changed);
                                    return "reserved";
                                });
                    await(reserved);
                    admission.applicationPolicy(
                            root, Map.of("version", 1, "tokenBudget", 1, "tokensUsed", 10));
                    await(changed);
                    return "changed";
                });
        assertEquals(
                "budget_exceeded",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        budgets.reserve(
                                                execution.scope(),
                                                execution.turnId(),
                                                execution.epoch(),
                                                "next",
                                                "model",
                                                execution.admission()))
                        .code());
    }

    @Test
    void concurrentAdmissionsMayExceedSoftApplicationCapWithoutSerializing() throws Exception {
        var sessions = List.of(root, scope());
        admission.createSession(sessions.get(1), configuration);
        admission.applicationPolicy(root, Map.of("version", 1, "maxConcurrent", 1));
        var beforeCommit = new CyclicBarrier(2);
        assertEquals(
                List.of("accepted", "accepted"),
                parallel(
                        index ->
                                tx.execute(
                                        ignored -> {
                                            admission.acceptTurn(
                                                    sessions.get(index), "actor", "one", "{}");
                                            await(beforeCommit);
                                            return "accepted";
                                        })));
        Thread.sleep(1100); // Observe committed work in the next soft quota window.
        var next = scope();
        admission.createSession(next, configuration);
        assertEquals(
                "admission_limited",
                assertThrows(
                                ManagedStateException.class,
                                () -> admission.acceptTurn(next, "actor", "one", "{}"))
                        .code());
    }

    @Test
    void concurrentSettlementReplayAndAggregatorsCountEachCallOnce() throws Exception {
        var executions = List.of(execution(root), execution(scope()));
        for (var e : executions)
            budgets.reserve(e.scope(), e.turnId(), e.epoch(), "call", "model", e.admission());
        var ready = new CyclicBarrier(2);
        parallel(
                index ->
                        tx.execute(
                                ignored -> {
                                    var e = executions.get(index);
                                    settle(e.scope(), e);
                                    settle(e.scope(), e);
                                    await(ready);
                                    return "committed";
                                }));
        assertEquals(0, applicationTokens());
        parallel(
                index -> {
                    try (var aggregator = new ManagedApplicationUsage(jdbc.getDataSource())) {
                        aggregator.flush(1);
                    }
                    return "flushed";
                });
        assertEquals(40, applicationTokens());
        // A newly created aggregator sees no pending deltas after a simulated process restart.
        try (var restarted = new ManagedApplicationUsage(jdbc.getDataSource())) {
            assertEquals(0, restarted.flush(512));
        }
        tx.executeWithoutResult(ignored -> settle(root, executions.get(0)));
        assertEquals(0, pending());
    }

    @Test
    void concurrentReplayOfTheSameCallCreatesOneDurableDelta() throws Exception {
        var e = execution(root);
        budgets.reserve(e.scope(), e.turnId(), e.epoch(), "call", "model", e.admission());
        parallel(
                index ->
                        tx.execute(
                                ignored -> {
                                    settle(root, e);
                                    return "committed";
                                }));
        assertEquals(1, pending());
        try (var aggregator = new ManagedApplicationUsage(jdbc.getDataSource())) {
            aggregator.flush(512);
        }
        assertEquals(20, applicationTokens());
    }

    @Test
    void nativeRollbackLeavesNoUsageDeltaOrCharge() {
        var e = execution(root);
        budgets.reserve(e.scope(), e.turnId(), e.epoch(), "call", "model", e.admission());
        tx.executeWithoutResult(
                status -> {
                    settle(root, e);
                    status.setRollbackOnly();
                });
        assertEquals(0, pending());
        assertEquals(0, applicationTokens());
        assertEquals(
                "reserved",
                jdbc.queryForObject(
                        "SELECT status FROM ma.model_call WHERE scope_key=? AND call_id='call'",
                        String.class,
                        root.messageGroup()));
        tx.executeWithoutResult(ignored -> settle(root, e));
        assertEquals(1, pending());
    }

    @Test
    void aggregationFailureDoesNotRollbackTheTurnAndRetainsDeltaForRetry() {
        var e = execution(root);
        budgets.reserve(e.scope(), e.turnId(), e.epoch(), "call", "model", e.admission());
        jdbc.update(
                "UPDATE ma.admission_quota SET managed_tokens_used=? WHERE quota_key=(SELECT"
                        + " quota_key FROM ma.session WHERE scope_key=?)",
                Long.MAX_VALUE,
                root.messageGroup());
        tx.executeWithoutResult(ignored -> settle(root, e));
        try (var aggregation = new ManagedApplicationUsage(jdbc.getDataSource())) {
            assertThrows(DataAccessException.class, () -> aggregation.flush(512));
            assertEquals(1, pending());
            assertEquals(
                    "completed",
                    jdbc.queryForObject(
                            "SELECT status FROM ma.model_call WHERE scope_key=? AND call_id='call'",
                            String.class,
                            root.messageGroup()));
            jdbc.update(
                    "UPDATE ma.admission_quota SET managed_tokens_used=0 WHERE quota_key=(SELECT"
                            + " quota_key FROM ma.session WHERE scope_key=?)",
                    root.messageGroup());
            assertEquals(1, aggregation.flush(512));
            assertEquals(0, aggregation.flush(512));
        }
        assertEquals(20, applicationTokens());
    }

    @Test
    void aggregatorSkipsLockedDeltaAndRecoversItAfterRelease() throws Exception {
        var executions = List.of(execution(root), execution(scope()));
        for (var e : executions) {
            budgets.reserve(e.scope(), e.turnId(), e.epoch(), "call", "model", e.admission());
            tx.executeWithoutResult(ignored -> settle(e.scope(), e));
        }
        var pool = Executors.newSingleThreadExecutor();
        try (var aggregation = new ManagedApplicationUsage(jdbc.getDataSource())) {
            tx.executeWithoutResult(
                    ignored -> {
                        jdbc.queryForObject(
                                "SELECT usage_id FROM ma.application_usage_delta WHERE usage_id=?"
                                        + " FOR UPDATE",
                                String.class,
                                ManagedScope.fingerprint(root.messageGroup(), "call"));
                        try {
                            assertEquals(
                                    1,
                                    pool.submit(() -> aggregation.flush(512))
                                            .get(5, TimeUnit.SECONDS));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
            assertEquals(1, aggregation.flush(512));
            assertEquals(40, applicationTokens());
        } finally {
            pool.shutdownNow();
        }
    }

    private int pending() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ma.application_usage_delta WHERE quota_key=(SELECT quota_key"
                        + " FROM ma.session WHERE scope_key=?)",
                Integer.class,
                root.messageGroup());
    }

    private long applicationTokens() {
        return jdbc.queryForObject(
                "SELECT managed_tokens_used FROM ma.admission_quota WHERE quota_key=(SELECT"
                        + " quota_key FROM ma.session WHERE scope_key=?)",
                Long.class,
                root.messageGroup());
    }

    private void settle(ManagedScope scope, ManagedTurnExecution execution) {
        var event = end(execution);
        budgets.committed(
                scope, event.turnId(), AgentScopeCommitAdapter.usage(event, new ObjectMapper()));
    }

    private SessionEvent end(ManagedTurnExecution execution) {
        return new SessionEvent(
                1,
                UUID.randomUUID().toString(),
                1,
                System.currentTimeMillis(),
                "model/end",
                "run",
                execution.turnId(),
                true,
                JsonUtils.getJsonCodec()
                        .toJson(
                                Map.of(
                                        "modelCallId",
                                        "call",
                                        "status",
                                        "completed",
                                        "usage",
                                        Map.of("inputTokens", 12, "outputTokens", 8))));
    }

    private static ManagedScope scope() {
        return new ManagedScope("quota-test", "default", UUID.randomUUID().toString());
    }

    private static void await(CyclicBarrier ready) {
        try {
            ready.await(5, TimeUnit.SECONDS);
        } catch (Exception failed) {
            throw new IllegalStateException(
                    "Concurrent transaction did not reach FK barrier", failed);
        }
    }

    private static List<String> parallel(IntFunction<String> work) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> work.apply(0));
            var second = pool.submit(() -> work.apply(1));
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
