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
package io.agentscope.builder.web.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.builder.control.ManagedEventOutbox;
import io.agentscope.builder.web.managed.service.DeletedSessionRegistry;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.builder.web.managed.service.SessionEventScope;
import io.agentscope.builder.web.persistence.jpa.SessionExportSourceEntity;
import io.agentscope.builder.web.persistence.jpa.SessionExportSourceRepository;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class SessionNativeLogTransactionTest {
    private final SessionEventLog events = mock(SessionEventLog.class);
    private final SessionExportSourceRepository sources = mock(SessionExportSourceRepository.class);
    private SessionNativeLogService service;
    private SessionLog log;
    private String sink;
    private final TransactionTemplate tx =
            new TransactionTemplate(
                    new AbstractPlatformTransactionManager() {
                        @Override
                        protected Object doGetTransaction() {
                            return new Object();
                        }

                        @Override
                        protected void doBegin(
                                Object transaction, TransactionDefinition definition) {}

                        @Override
                        protected void doCommit(DefaultTransactionStatus status) {}

                        @Override
                        protected void doRollback(DefaultTransactionStatus status) {}
                    });

    @BeforeEach
    void prepareNativeFact() {
        var store = new InMemorySessionLogStore();
        log = store.open(new SessionKey("user", "agent", "session"), null);
        var writer = log.acquire("writer", Duration.ofMinutes(1));
        log.commit(
                writer,
                "commit",
                0,
                List.of(
                        new SessionEvent(
                                1, "native-event", 1, 1L, "run/start", null, "turn", true, "{}")));
        log.release(writer);
        var source = new SessionExportSourceEntity();
        source.sessionId = "session";
        source.userId = "user";
        source.agentId = "agent";
        when(sources.findById("session")).thenReturn(Optional.of(source));
        when(sources.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(source)));
        service =
                new SessionNativeLogService(
                        store,
                        events,
                        sources,
                        new DeletedSessionRegistry(),
                        mock(ManagedEventOutbox.class));
        sink = service.sink("session").name();
    }

    @Test
    void exportCursorWaitsForTheEnclosingCommit() {
        tx.executeWithoutResult(
                status -> {
                    service.refresh("session");
                    assertThat(log.exportCursor(sink)).isZero();
                    service.refresh("session");
                    assertThat(log.exportCursor(sink)).isZero();
                });
        assertThat(log.exportCursor(sink)).isEqualTo(1L);
    }

    @Test
    void rolledBackExportsRemainAvailableForRepair() {
        tx.executeWithoutResult(
                status -> {
                    service.refresh("session");
                    status.setRollbackOnly();
                });
        assertThat(log.exportCursor(sink)).isZero();
        service.refresh("session");
        assertThat(log.exportCursor(sink)).isEqualTo(1L);
        verify(events, times(2))
                .appendIdempotentScoped(
                        eq("session"),
                        eq("run.started"),
                        anyMap(),
                        eq("native-event"),
                        eq(SessionEventScope.NONE));
    }

    @Test
    void failedPostCommitCursorDoesNotFailTheCommandOrSkipEventsBeforeRepair() {
        var writer = log.acquire("writer", Duration.ofMinutes(1));
        log.commit(
                writer,
                "second-commit",
                1,
                List.of(
                        new SessionEvent(
                                1,
                                "second-native-event",
                                2,
                                2L,
                                "run/start",
                                null,
                                "turn",
                                true,
                                "{}")));
        log.release(writer);
        var cursorAttempts = new AtomicInteger();
        SessionLog failingLog = mock(SessionLog.class, delegatesTo(log));
        doAnswer(
                        invocation -> {
                            if (cursorAttempts.incrementAndGet() == 1) {
                                throw new SessionLogException("transient native storage outage");
                            }
                            log.advanceExportCursor(
                                    invocation.getArgument(0),
                                    invocation.getArgument(1),
                                    invocation.getArgument(2));
                            return null;
                        })
                .when(failingLog)
                .advanceExportCursor(anyString(), anyLong(), anyLong());
        var failingService =
                new SessionNativeLogService(
                        (key, context) -> failingLog,
                        events,
                        sources,
                        new DeletedSessionRegistry(),
                        mock(ManagedEventOutbox.class));
        var database =
                new EmbeddedDatabaseBuilder()
                        .generateUniqueName(true)
                        .setType(EmbeddedDatabaseType.H2)
                        .build();
        try {
            var jdbc = new JdbcTemplate(database);
            jdbc.execute("CREATE TABLE commands (id INT PRIMARY KEY, status VARCHAR(32))");
            jdbc.execute("CREATE TABLE exported_events (event_id VARCHAR(64) PRIMARY KEY)");
            when(events.appendIdempotentScoped(
                            eq("session"),
                            anyString(),
                            anyMap(),
                            anyString(),
                            eq(SessionEventScope.NONE)))
                    .thenAnswer(
                            invocation -> {
                                jdbc.update(
                                        "MERGE INTO exported_events (event_id) KEY (event_id)"
                                                + " VALUES (?)",
                                        invocation.<String>getArgument(3));
                                return null;
                            });
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
            var laterCallback = new AtomicBoolean();

            assertThatCode(
                            () ->
                                    transactions.executeWithoutResult(
                                            status -> {
                                                jdbc.update(
                                                        "INSERT INTO commands VALUES (1,"
                                                                + " 'cancelled')");
                                                failingService.refresh("session");
                                                TransactionSynchronizationManager
                                                        .registerSynchronization(
                                                                new TransactionSynchronization() {
                                                                    @Override
                                                                    public void afterCommit() {
                                                                        laterCallback.set(true);
                                                                    }
                                                                });
                                            }))
                    .doesNotThrowAnyException();

            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM commands WHERE id = 1", String.class))
                    .isEqualTo("cancelled");
            assertThat(jdbc.queryForList("SELECT event_id FROM exported_events", String.class))
                    .containsExactlyInAnyOrder("native-event", "second-native-event");
            assertThat(laterCallback).isTrue();
            assertThat(cursorAttempts).hasValue(2);
            // The second ACK must not jump over the first failed ACK even after storage recovers.
            assertThat(log.exportCursor(sink)).isZero();

            failingService.repair();

            assertThat(log.exportCursor(sink)).isEqualTo(2L);
            assertThat(cursorAttempts).hasValue(4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exported_events", Integer.class))
                    .isEqualTo(2);
            for (String eventId : List.of("native-event", "second-native-event")) {
                verify(events, times(2))
                        .appendIdempotentScoped(
                                eq("session"),
                                eq("run.started"),
                                anyMap(),
                                eq(eventId),
                                eq(SessionEventScope.NONE));
            }
        } finally {
            database.shutdown();
        }
    }
}
