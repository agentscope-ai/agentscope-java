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
package io.agentscope.builder.web.managed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

class SessionEventNotifierTest {

    @Test
    void rejectedRemoteWakeupDoesNotInterruptCommitCallbacks() {
        DataSource notifications = mock(DataSource.class);
        SessionEventNotifier notifier = new SessionEventNotifier(notifications, 100L);
        ReflectionTestUtils.setField(notifier, "postgres", true);
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.schedule(any(Runnable.class)))
                .thenThrow(new RejectedExecutionException("notification queue full"));
        AtomicInteger localWakeups = new AtomicInteger();
        AtomicBoolean acknowledged = new AtomicBoolean();
        Disposable subscription =
                notifier.wakeups("session").subscribe(ignored -> localWakeups.incrementAndGet());
        EmbeddedDatabase database =
                new EmbeddedDatabaseBuilder()
                        .generateUniqueName(true)
                        .setType(EmbeddedDatabaseType.H2)
                        .build();
        try (MockedStatic<Schedulers> schedulers = mockStatic(Schedulers.class)) {
            schedulers.when(Schedulers::boundedElastic).thenReturn(scheduler);
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.execute("CREATE TABLE committed_events (event_id VARCHAR(64) PRIMARY KEY)");
            TransactionTemplate transactions =
                    new TransactionTemplate(new DataSourceTransactionManager(database));

            assertThatCode(
                            () ->
                                    transactions.executeWithoutResult(
                                            status -> {
                                                jdbc.update(
                                                        "INSERT INTO committed_events VALUES (?)",
                                                        "event");
                                                TransactionSynchronizationManager
                                                        .registerSynchronization(
                                                                new TransactionSynchronization() {
                                                                    @Override
                                                                    public void afterCommit() {
                                                                        notifier.publish("session");
                                                                    }
                                                                });
                                                TransactionSynchronizationManager
                                                        .registerSynchronization(
                                                                new TransactionSynchronization() {
                                                                    @Override
                                                                    public void afterCommit() {
                                                                        acknowledged.set(true);
                                                                    }
                                                                });
                                            }))
                    .doesNotThrowAnyException();

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM committed_events", Integer.class))
                    .isEqualTo(1);
            assertThat(localWakeups).hasValue(1);
            assertThat(acknowledged).isTrue();
            verify(scheduler).schedule(any(Runnable.class));
            verifyNoInteractions(notifications);
        } finally {
            subscription.dispose();
            database.shutdown();
        }
    }
}
