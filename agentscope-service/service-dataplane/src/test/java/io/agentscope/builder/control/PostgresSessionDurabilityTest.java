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
package io.agentscope.builder.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.builder.web.persistence.jpa.SessionEventFenceEntity;
import io.agentscope.builder.web.persistence.jpa.SessionEventFenceRepository;
import io.agentscope.builder.web.persistence.jpa.SessionEventOutboxEntity;
import io.agentscope.builder.web.persistence.jpa.SessionEventOutboxRepository;
import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.store.JdbcStore;
import io.agentscope.harness.agent.session.StoreSessionStorage;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs only against an explicitly supplied disposable PostgreSQL regression database. */
@EnabledIfEnvironmentVariable(named = "AGENTSCOPE_TEST_POSTGRES_URL", matches = ".+")
class PostgresSessionDurabilityTest {
    private final String url = System.getenv("AGENTSCOPE_TEST_POSTGRES_URL");
    private final String user = System.getenv("AGENTSCOPE_TEST_POSTGRES_USER");
    private final String password = System.getenv("AGENTSCOPE_TEST_POSTGRES_PASSWORD");
    private final String schema = "regression_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        execute("CREATE SCHEMA " + schema);
    }

    @AfterEach
    void removeOnlyThisTestsSchema() throws Exception {
        execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void independentJdbcInstancesFenceNativeWritersAndRecoverOnlyCommittedEvents() {
        var dataSource = new DriverManagerDataSource(scopedUrl(), user, password);
        var firstStore =
                JdbcStore.builder(dataSource)
                        .dialect(AbstractJdbcDialect.from(dataSource).build())
                        .build();
        var secondStore =
                JdbcStore.builder(dataSource)
                        .dialect(AbstractJdbcDialect.from(dataSource).build())
                        .build();
        var first =
                new JournalSessionLog(
                        new StoreSessionStorage(firstStore, List.of("tenant-a")), "session");
        var second =
                new JournalSessionLog(
                        new StoreSessionStorage(secondStore, List.of("tenant-a")), "session");
        var writer = first.acquire("run-a", Duration.ofMinutes(1));
        assertThatThrownBy(() -> second.acquire("run-b", Duration.ofMinutes(1)))
                .isInstanceOf(SessionLogException.class);
        var event = new SessionEvent(1, "event", 1, 1, "run/start", "run-a", "turn", true, "{}");
        first.commit(writer, "batch", 0, List.of(event));
        first.commit(writer, "batch", 0, List.of(event));
        assertThat(second.readAfter(0, 100)).containsExactly(event);
        second.sealWriter("run-a");
        assertThatThrownBy(
                        () ->
                                first.commit(
                                        writer,
                                        "late",
                                        1,
                                        List.of(
                                                new SessionEvent(
                                                        1, "late", 2, 2, "run/end", "run-a", "turn",
                                                        true, "{}"))))
                .isInstanceOf(SessionLogException.class);
        assertThatThrownBy(() -> first.acquire("run-a", Duration.ofMinutes(1)))
                .isInstanceOf(SessionLogException.class);
        var next = second.acquire("run-b", Duration.ofMinutes(1));
        second.release(next);
        assertThat(
                        new JournalSessionLog(
                                        new StoreSessionStorage(secondStore, List.of("tenant-b")),
                                        "session")
                                .head()
                                .seq())
                .isZero();
    }

    @Test
    void outboxRollbackFifoAndAttemptFenceSerializeAcrossDatabaseConnections() throws Exception {
        try (var factory =
                new Configuration()
                        .addAnnotatedClass(SessionEventOutboxEntity.class)
                        .addAnnotatedClass(SessionEventFenceEntity.class)
                        .setProperty("hibernate.connection.url", scopedUrl())
                        .setProperty("hibernate.connection.username", user)
                        .setProperty("hibernate.connection.password", password)
                        .setProperty("hibernate.hbm2ddl.auto", "create")
                        .buildSessionFactory()) {
            var repositories =
                    new JpaRepositoryFactory(
                            SharedEntityManagerCreator.createSharedEntityManager(factory));
            var events = repositories.getRepository(SessionEventOutboxRepository.class);
            var fences = repositories.getRepository(SessionEventFenceRepository.class);
            var tx = new TransactionTemplate(new JpaTransactionManager(factory));
            tx.executeWithoutResult(
                    status -> {
                        var fence = new SessionEventFenceEntity();
                        fence.attemptId = "attempt";
                        fence.sessionId = "session";
                        fences.saveAndFlush(fence);
                    });
            assertThatThrownBy(
                            () ->
                                    tx.executeWithoutResult(
                                            status -> {
                                                events.saveAndFlush(row("rolled-back", 1));
                                                throw new IllegalStateException(
                                                        "lost source transaction");
                                            }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(events.findAll()).isEmpty();
            tx.executeWithoutResult(
                    status -> {
                        events.saveAndFlush(row("first", 2));
                        events.saveAndFlush(row("second", 3));
                    });
            assertThat(events.findDeliverable(10, PageRequest.of(0, 10)))
                    .extracting(event -> event.eventId)
                    .containsExactly("first");
            var locked = new CountDownLatch(1);
            var unlock = new CountDownLatch(1);
            var closing =
                    CompletableFuture.runAsync(
                            () ->
                                    tx.executeWithoutResult(
                                            status -> {
                                                var fence =
                                                        fences.lockById("attempt").orElseThrow();
                                                locked.countDown();
                                                await(unlock);
                                                fence.sealed = true;
                                                fences.saveAndFlush(fence);
                                            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var readerStarted = new CountDownLatch(1);
            var lateDelivery =
                    CompletableFuture.supplyAsync(
                            () ->
                                    tx.execute(
                                            status -> {
                                                readerStarted.countDown();
                                                return fences.lockById("attempt")
                                                        .orElseThrow()
                                                        .sealed;
                                            }));
            try {
                assertThat(readerStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> lateDelivery.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                unlock.countDown();
            }
            closing.get(10, TimeUnit.SECONDS);
            assertThat(lateDelivery.get(10, TimeUnit.SECONDS)).isTrue();
            tx.executeWithoutResult(
                    status -> {
                        var first = events.lockById("first").orElseThrow();
                        first.deliveredAt = 9;
                        events.saveAndFlush(first);
                    });
            assertThat(events.findDeliverable(10, PageRequest.of(0, 10)))
                    .extracting(event -> event.eventId)
                    .containsExactly("second");
        }
    }

    private static SessionEventOutboxEntity row(String id, long seq) {
        var row = new SessionEventOutboxEntity();
        row.eventId = id;
        row.sessionId = "session";
        row.attemptId = "attempt";
        row.eventSeq = seq;
        row.reportJson = "{}";
        return row;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("latch timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private String scopedUrl() {
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    private void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
