/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedSessionProtocol.Acceptance;
import io.agentcore.managed.server.runtime.managed.ManagedSessionProtocol.Command;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real row-lock probes, concurrent appends, late lifecycle checks and receipt atomicity. */
@EnabledIfEnvironmentVariable(named = "MANAGED_DATABASE_TEST_URI", matches = ".+")
@Timeout(30)
class ManagedPostgresNewTurnAdmissionIntegrationTest {
    private DriverManagerDataSource source;
    private ManagedAdmissionStore admission;
    private ManagedSessionProtocol protocol;
    private JdbcTemplate jdbc;
    private ManagedScope scope;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        source =
                new DriverManagerDataSource(
                        System.getenv("MANAGED_DATABASE_TEST_URI"),
                        "managed",
                        "managed-local-test");
        ManagedAdmissionStore.initialize(source);
        admission = new ManagedAdmissionStore(source, mapper);
        protocol = admission.protocol();
        jdbc = new JdbcTemplate(source);
        scope = new ManagedScope("new-turn", "test", UUID.randomUUID().toString());
        admission.createSession(scope, "{}");
    }

    private Command message(String text) {
        return new Command(
                "user.message",
                List.of(Map.of("type", "text", "text", text)),
                "new_turn",
                null,
                true);
    }

    @SuppressWarnings("unchecked")
    private String turn(Acceptance receipt) {
        var event = (Map<String, Object>) ((List<?>) receipt.receipt().get("data")).get(0);
        return (String) ((Map<String, Object>) event.get("managed_agents")).get("turn_id");
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pausedValidationOrRateCheckDoesNotBlockInjectionIntoTheCurrentTurn(boolean rate)
            throws Exception {
        var pauseRate = new AtomicBoolean();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        admission =
                new ManagedAdmissionStore(
                        source,
                        mapper,
                        new ManagedAdmissionStore.Limits(1000, 128, 128, 1000),
                        (group, limit) -> {
                            if (rate && pauseRate.compareAndSet(true, false)) {
                                started.countDown();
                                await(release);
                            }
                            return true;
                        });
        protocol = admission.protocol();
        String first =
                turn(protocol.accept(scope, "actor", "first", List.of(message("first")), () -> {}));
        admission.reservePublication(scope, first);
        admission.confirmPublication(scope, first, "broker");
        admission.activate(scope, first, 1, "brain");
        pauseRate.set(true);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var append =
                    pool.submit(
                            () ->
                                    protocol.accept(
                                            scope,
                                            "actor",
                                            "next",
                                            List.of(message("next")),
                                            () -> {
                                                if (!rate) {
                                                    started.countDown();
                                                    await(release);
                                                }
                                            }));
            await(started);
            var injection =
                    pool.submit(
                            () ->
                                    protocol.accept(
                                            scope,
                                            "actor",
                                            "inject",
                                            List.of(
                                                    new Command(
                                                            "user.message",
                                                            List.of(
                                                                    Map.of(
                                                                            "type",
                                                                            "text",
                                                                            "text",
                                                                            "injected")),
                                                            "inject",
                                                            first,
                                                            true)),
                                            () -> {}));
            assertFalse(injection.get(3, TimeUnit.SECONDS).replayed());
            assertFalse(append.isDone());
            release.countDown();
            String next = turn(append.get(5, TimeUnit.SECONDS));
            assertEquals("accepted", admission.turn(scope, next).status());
            assertEquals(first, admission.session(scope).currentTurnId());
            assertEquals(2, admission.turnHead(scope));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void lifecycleClosureDuringPreflightPreventsTheAppend() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        // Pause after preflight already read an active Session, before the final locked check.
        admission =
                new ManagedAdmissionStore(
                        source,
                        mapper,
                        new ManagedAdmissionStore.Limits(1000, 128, 128, 1000),
                        (group, limit) -> {
                            started.countDown();
                            await(release);
                            return true;
                        });
        protocol = admission.protocol();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var append =
                    pool.submit(
                            () ->
                                    protocol.accept(
                                            scope,
                                            "actor",
                                            "late",
                                            List.of(message("late")),
                                            () -> {}));
            await(started);
            pool.submit(
                            () ->
                                    jdbc.update(
                                            "UPDATE ma.session SET lifecycle='closed' WHERE"
                                                    + " scope_key=?",
                                            scope.messageGroup()))
                    .get(3, TimeUnit.SECONDS);
            release.countDown();
            var failure =
                    assertThrows(ExecutionException.class, () -> append.get(5, TimeUnit.SECONDS));
            assertEquals(
                    "session_inactive",
                    assertInstanceOf(ManagedStateException.class, failure.getCause()).code());
            assertEquals(0, admission.turnHead(scope));
            assertEquals(0, requestCount());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void distinctConcurrentRequestsGetUniqueQueuedTurnsAndSequences() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        var barrier = new CyclicBarrier(8);
        try {
            var futures = new ArrayList<Future<Acceptance>>();
            for (int i = 0; i < 8; i++) {
                int index = i;
                futures.add(
                        pool.submit(
                                () ->
                                        protocol.accept(
                                                scope,
                                                "actor",
                                                "key-" + index,
                                                List.of(message("text-" + index)),
                                                () -> await(barrier))));
            }
            var ids = new HashSet<String>();
            for (var future : futures) ids.add(turn(future.get(10, TimeUnit.SECONDS)));
            assertEquals(8, ids.size());
            assertEquals(8, admission.turnHead(scope));
            assertEquals(
                    List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L),
                    jdbc.queryForList(
                            "SELECT admission_sequence FROM ma.turn WHERE scope_key=? ORDER BY"
                                    + " admission_sequence",
                            Long.class,
                            scope.messageGroup()));
            assertEquals(8, requestCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentSameKeyReplaysOrRejectsConflictingContentWithoutAnotherTurn(boolean conflict)
            throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            var first = pool.submit(() -> outcome("same", message("first"), barrier));
            var second =
                    pool.submit(
                            () -> outcome("same", message(conflict ? "second" : "first"), barrier));
            var results =
                    List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("accepted"::equals).count());
            assertEquals(
                    1,
                    results.stream()
                            .filter((conflict ? "idempotency_conflict" : "replayed")::equals)
                            .count());
            assertEquals(1, admission.turnHead(scope));
            assertEquals(1, requestCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCommittedReceiptWinsOverAStalePreflightRejection() throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var rejected = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(
                                                    new DataSourceTransactionManager(source))
                                            .execute(
                                                    ignored -> {
                                                        var receipt =
                                                                protocol.accept(
                                                                        scope,
                                                                        "actor",
                                                                        "winner",
                                                                        List.of(message("same")),
                                                                        () -> {});
                                                        held.countDown();
                                                        await(release);
                                                        return receipt;
                                                    }));
            await(held);
            var retry =
                    pool.submit(
                            () ->
                                    protocol.accept(
                                            scope,
                                            "actor",
                                            "winner",
                                            List.of(message("same")),
                                            () -> {
                                                rejected.countDown();
                                                throw new ManagedStateException(
                                                        "admission_limited", "stale quota");
                                            }));
            await(rejected);
            release.countDown();
            var original = first.get(5, TimeUnit.SECONDS);
            var replay = retry.get(5, TimeUnit.SECONDS);
            assertTrue(replay.replayed());
            assertEquals(original.receipt(), replay.receipt());
            assertEquals(1, admission.turnHead(scope));
            assertEquals(1, requestCount());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private String outcome(String key, Command command, CyclicBarrier barrier) {
        try {
            return protocol.accept(scope, "actor", key, List.of(command), () -> await(barrier))
                            .replayed()
                    ? "replayed"
                    : "accepted";
        } catch (ManagedStateException e) {
            return e.code();
        }
    }

    @Test
    void sessionQueueCapacityIsRecheckedAfterConcurrentPreflights() throws Exception {
        admission =
                new ManagedAdmissionStore(
                        source, mapper, new ManagedAdmissionStore.Limits(1000, 1, 128, 1000));
        protocol = admission.protocol();
        var pool = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            var first = pool.submit(() -> outcome("first", message("first"), barrier));
            var second = pool.submit(() -> outcome("second", message("second"), barrier));
            var results =
                    List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertTrue(results.contains("accepted"));
            assertTrue(results.contains("admission_limited"));
            assertEquals(1, admission.turnHead(scope));
            assertEquals(1, requestCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void explicitBatchCapacityFailureRollsBackEveryTurnAndReceipt() {
        admission =
                new ManagedAdmissionStore(
                        source, mapper, new ManagedAdmissionStore.Limits(1000, 1, 128, 1000));
        protocol = admission.protocol();
        assertEquals(
                "admission_limited",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        protocol.accept(
                                                scope,
                                                "actor",
                                                "batch",
                                                List.of(message("one"), message("two")),
                                                () -> {}))
                        .code());
        assertEquals(0, admission.turnHead(scope));
        assertEquals(0, requestCount());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM ma.turn WHERE scope_key=?",
                        Integer.class,
                        scope.messageGroup()));
        assertFalse(
                protocol.accept(scope, "actor", "batch", List.of(message("one")), () -> {})
                        .replayed());
        assertEquals(1, admission.turnHead(scope));
        assertEquals(1, requestCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"inject", "cancel", "message", "mixed"})
    void interactionAndOrdinaryMessageValidationStillHoldsTheSessionLock(String intent)
            throws Exception {
        String target =
                "message".equals(intent)
                        ? null
                        : turn(
                                protocol.accept(
                                        scope,
                                        "actor",
                                        "first",
                                        List.of(message("first")),
                                        () -> {}));
        var commands =
                switch (intent) {
                    case "message" ->
                            List.of(
                                    new Command(
                                            "user.message",
                                            List.of(Map.of("type", "text", "text", "ordinary")),
                                            "message",
                                            null,
                                            false));
                    case "cancel" ->
                            List.of(
                                    new Command(
                                            "user.interrupt", List.of(), "cancel", target, true));
                    case "mixed" ->
                            List.of(
                                    message("new"),
                                    new Command(
                                            "user.message",
                                            List.of(Map.of("type", "text", "text", "injected")),
                                            "inject",
                                            target,
                                            true));
                    default ->
                            List.of(
                                    new Command(
                                            "user.message",
                                            List.of(Map.of("type", "text", "text", "injected")),
                                            "inject",
                                            target,
                                            true));
                };
        var pool = Executors.newSingleThreadExecutor();
        try {
            protocol.accept(
                    scope,
                    "actor",
                    "control",
                    commands,
                    () -> {
                        var probe =
                                pool.submit(
                                        () ->
                                                new TransactionTemplate(
                                                                new DataSourceTransactionManager(
                                                                        source))
                                                        .execute(
                                                                ignored -> {
                                                                    jdbc.execute(
                                                                            "SET LOCAL"
                                                                                + " lock_timeout='150ms'");
                                                                    return jdbc.queryForObject(
                                                                            "SELECT scope_key FROM"
                                                                                + " ma.session"
                                                                                + " WHERE"
                                                                                + " scope_key=? FOR"
                                                                                + " UPDATE",
                                                                            String.class,
                                                                            scope.messageGroup());
                                                                }));
                        var error =
                                assertThrows(
                                        ExecutionException.class,
                                        () -> probe.get(3, TimeUnit.SECONDS));
                        var sql =
                                assertInstanceOf(
                                        SQLException.class,
                                        assertInstanceOf(
                                                        DataAccessException.class, error.getCause())
                                                .getMostSpecificCause());
                        assertEquals("55P03", sql.getSQLState());
                    });
        } finally {
            pool.shutdownNow();
        }
    }

    private int requestCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ma.protocol_request WHERE scope_key=?",
                Integer.class,
                scope.messageGroup());
    }
}
