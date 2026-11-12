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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class ManagedResumePublisherTest {
    final ManagedSuspensions store = mock(ManagedSuspensions.class);
    final ManagedMessageBus bus = mock(ManagedMessageBus.class);
    final ManagedScope scope = new ManagedScope("t", "n", "s");
    final ManagedSuspensions.Resume resume =
            new ManagedSuspensions.Resume(scope, "resume", "turn", "suspension", 1, 1);
    final InMemoryManagedLeaseStore leases =
            new InMemoryManagedLeaseStore(() -> System.nanoTime() / 1_000_000);
    final DriverManagerDataSource source =
            new DriverManagerDataSource(
                    "jdbc:h2:mem:resume-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");

    ManagedResumePublisher publisher(Duration interval) {
        when(store.dataSource()).thenReturn(source);
        return new ManagedResumePublisher(store, bus, leases, new ObjectMapper(), interval);
    }

    @Test
    void signalsOnlyWakeAfterCommitAndNeverAfterRollback() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        try (var subscription = ManagedResumeSignals.subscribe(source, received::add)) {
            tx.executeWithoutResult(
                    status -> {
                        ManagedResumeSignals.committed(new JdbcTemplate(source), "rollback");
                        assertTrue(received.isEmpty());
                        status.setRollbackOnly();
                    });
            assertTrue(received.isEmpty());
            tx.executeWithoutResult(
                    status -> {
                        ManagedResumeSignals.committed(new JdbcTemplate(source), "commit");
                        assertTrue(received.isEmpty());
                    });
            assertEquals(List.of("commit"), received);
        }
        ManagedResumeSignals.committed(new JdbcTemplate(source), "closed");
        assertEquals(List.of("commit"), received);
    }

    @Test
    void committedHintPublishesOnlyThatSessionWithoutWaitingForRepair() throws Exception {
        var pending = new AtomicBoolean(true);
        when(store.pending(64, scope.messageGroup()))
                .thenAnswer(call -> pending.get() ? List.of(resume) : List.of());
        when(store.publication(resume))
                .thenAnswer(call -> pending.get() ? OptionalLong.of(0) : OptionalLong.empty());
        when(bus.publish(eq(ManagedMessageBus.Channel.TURN_RESUMES), any()))
                .thenReturn(new ManagedMessageBus.Published("broker"));
        doAnswer(
                        call -> {
                            pending.set(false);
                            return true;
                        })
                .when(store)
                .published(resume, "broker", 0);
        try (var publisher = publisher(Duration.ofHours(1))) {
            publisher.start();
            verify(store, timeout(2000)).pending(256, null, 0);
            ManagedResumeSignals.committed(new JdbcTemplate(source), scope.messageGroup());
            verify(store, timeout(2000)).published(resume, "broker", 0);
            assertFalse(pending.get());
            verify(store, times(1)).pending(256, null, 0);
            verify(bus, times(1)).publish(eq(ManagedMessageBus.Channel.TURN_RESUMES), any());
        }
    }

    @Test
    void missedHintIsRepairedAndAmbiguousSendKeepsTheStableIdentity() throws Exception {
        var pending = new AtomicBoolean(false);
        when(store.pending(256, null, 0))
                .thenAnswer(call -> pending.get() ? List.of(resume) : List.of());
        when(store.publication(resume))
                .thenAnswer(call -> pending.get() ? OptionalLong.of(0) : OptionalLong.empty());
        var identities = new CopyOnWriteArrayList<String>();
        when(bus.publish(eq(ManagedMessageBus.Channel.TURN_RESUMES), any()))
                .thenAnswer(
                        call -> {
                            ManagedMessageBus.Message message = call.getArgument(1);
                            identities.add(message.id());
                            if (identities.size() == 1)
                                throw new ManagedTransportException("Ambiguous send");
                            return new ManagedMessageBus.Published("broker");
                        });
        doAnswer(
                        call -> {
                            pending.set(false);
                            return true;
                        })
                .when(store)
                .published(resume, "broker", 0);
        try (var publisher = publisher(Duration.ofMillis(100))) {
            publisher.start();
            verify(store, timeout(2000)).pending(256, null, 0);
            pending.set(true); // No commit hint: simulates a disconnected publisher.
            verify(store, timeout(3000)).published(resume, "broker", 0);
            assertEquals(List.of("resume", "resume"), identities);
        }
    }

    @Test
    void staleOutboxSnapshotCannotRepublishAnAlreadyPublishedResume() {
        when(store.pending(8)).thenReturn(List.of(resume));
        when(store.publication(resume)).thenReturn(OptionalLong.empty());
        try (var publisher = publisher(Duration.ofSeconds(30))) {
            assertEquals(0, publisher.retry(8));
            verifyNoInteractions(bus);
        }
    }

    @Test
    void suspendBackoffGrowsEvenWhenBrokerAttemptStaysConstantAndResetsAfterAck() {
        var backoff = new ManagedResumeBackoff();
        assertEquals(Duration.ofMillis(500), backoff.next("resume", Duration.ofMillis(500)));
        for (int i = 1; i < 10; i++) {
            long ceiling = Math.min(10000, 500L << Math.min(i, 5));
            long delay = backoff.next("resume", Duration.ofMillis(500)).toMillis();
            assertTrue(delay >= ceiling / 2 && delay <= ceiling);
        }
        assertEquals(Duration.ofSeconds(60), backoff.next("resume", Duration.ofSeconds(60)));
        backoff.clear("resume");
        assertEquals(Duration.ofMillis(500), backoff.next("resume", Duration.ofMillis(500)));
    }
}
