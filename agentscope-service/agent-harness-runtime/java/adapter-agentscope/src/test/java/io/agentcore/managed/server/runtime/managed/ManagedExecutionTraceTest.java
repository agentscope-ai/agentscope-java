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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class ManagedExecutionTraceTest {
    private final ManagedScope scope = new ManagedScope("t", "n", "s");

    @Test
    void delayedTaskExcludesRequestedDelayButMeasuresOccupiedWorker() throws Exception {
        var executor = new ManagedObservedScheduler(Executors.newSingleThreadScheduledExecutor());
        try (var trace = new ManagedExecutionTrace(scope, "turn")) {
            executor.schedule(() -> {}, 150, TimeUnit.MILLISECONDS).get(2, TimeUnit.SECONDS);
            long first = (long) trace.intervals().get(0).get(2);
            assertTrue(first < 100_000, "Intentional delay must not be charged as queue wait");
            var entered = new java.util.concurrent.CountDownLatch(1);
            executor.execute(
                    () -> {
                        entered.countDown();
                        try {
                            Thread.sleep(150);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            executor.schedule(() -> {}, 0, TimeUnit.MILLISECONDS).get(2, TimeUnit.SECONDS);
            assertTrue(trace.intervals().stream().anyMatch(row -> (long) row.get(2) > 100_000));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void jdbcPreservesFailureAndRollbackAndContextDoesNotLeak() throws Exception {
        var raw = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(raw.prepareStatement("test")).thenReturn(statement);
        var failure = new SQLException("expected", "40001");
        when(statement.execute()).thenThrow(failure);
        try (var trace = new ManagedExecutionTrace(scope, "turn")) {
            var observed = ManagedObservationConfiguration.connection(raw);
            assertSame(
                    failure,
                    assertThrows(
                            SQLException.class, () -> observed.prepareStatement("test").execute()));
            observed.rollback();
            observed.close();
            verify(raw).rollback();
            verify(raw).close();
            assertTrue(
                    trace.intervals().stream().anyMatch(row -> row.get(0).equals("sql_execute")));
        }
        assertNull(ManagedExecutionTrace.current());
        assertSame(raw, ManagedObservationConfiguration.connection(raw));
    }

    @Test
    void traceSurvivesReactiveThreadSwitchAndCancellation() {
        ManagedExecutionTrace.configure(16);
        try (var trace = new ManagedExecutionTrace(scope, "turn")) {
            Mono.fromCallable(ManagedExecutionTrace::current)
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(value -> assertSame(trace, value))
                    .contextWrite(ManagedExecutionTrace::context)
                    .block(Duration.ofSeconds(2));
            ManagedExecutionTrace.mono(
                            "finalization_hooks", () -> Mono.delay(Duration.ofSeconds(1)).then())
                    .take(Duration.ofMillis(20))
                    .contextWrite(ManagedExecutionTrace::context)
                    .block(Duration.ofSeconds(2));
            assertTrue(
                    trace.intervals().stream()
                            .anyMatch(row -> row.get(0).equals("finalization_hooks")));
        } finally {
            ManagedExecutionTrace.configure(0);
        }
        assertNull(ManagedExecutionTrace.current());
    }

    @Test
    void asyncTraceAndObservationSupportOrdinaryExecutorsWithoutLeakingContext() {
        ManagedExecutionTrace.configure(1);
        var scheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor());
        try {
            var observed =
                    ManagedExecutionTrace.observe(
                            scope,
                            "async-turn",
                            () ->
                                    Mono.fromCallable(
                                                    () -> {
                                                        assertNotNull(
                                                                ManagedExecutionTrace.current());
                                                        return "done";
                                                    })
                                            .subscribeOn(scheduler));
            assertEquals("done", observed.block(Duration.ofSeconds(2)));
            assertNull(ManagedExecutionTrace.current());
            assertNull(
                    Mono.fromCallable(ManagedExecutionTrace::current)
                            .subscribeOn(scheduler)
                            .block(Duration.ofSeconds(2)));
        } finally {
            scheduler.dispose();
            ManagedExecutionTrace.configure(0);
        }
    }

    @Test
    void dataSourceProxyRetainsPoolManagementAndTransactionSemantics() throws Exception {
        var raw = new HikariDataSource();
        raw.setJdbcUrl("jdbc:h2:mem:observed-pool");
        var processor =
                ManagedObservationConfiguration.managedObservationSources(
                        new MockEnvironment()
                                .withProperty(
                                        "managed-agents.runtime.observation-sample-every", "16"));
        var observed =
                (HikariDataSource) processor.postProcessAfterInitialization(raw, "dataSource");
        try (var trace = new ManagedExecutionTrace(scope, "turn")) {
            try (var connection = observed.getConnection()) {
                connection.setAutoCommit(false);
                connection.createStatement().execute("select 1");
                connection.rollback();
            }
            assertSame(raw.getHikariPoolMXBean(), observed.getHikariPoolMXBean());
            assertTrue(
                    trace.intervals().stream().anyMatch(row -> row.get(0).equals("sql_acquire")));
            assertTrue(
                    trace.intervals().stream().anyMatch(row -> row.get(0).equals("sql_execute")));
        } finally {
            observed.close();
            ManagedExecutionTrace.configure(0);
        }
        assertTrue(raw.isClosed());
    }
}
