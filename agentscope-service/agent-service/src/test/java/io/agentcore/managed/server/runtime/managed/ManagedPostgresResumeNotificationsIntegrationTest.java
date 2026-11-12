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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "MANAGED_DATABASE_TEST_URI", matches = ".+")
class ManagedPostgresResumeNotificationsIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void remoteTransactionWakesPublisherOnlyAfterCommitWithoutOutboxPolling(boolean delayed)
            throws Exception {
        String url = System.getenv("MANAGED_DATABASE_TEST_URI");
        var listenerSource = new DriverManagerDataSource(url, "managed", "managed-local-test");
        var writerSource = new DriverManagerDataSource(url, "managed", "managed-local-test");
        var jdbc = new JdbcTemplate(writerSource);
        var store = mock(ManagedSuspensions.class);
        var bus = mock(ManagedMessageBus.class);
        var scope = new ManagedScope("notify", "test", UUID.randomUUID().toString());
        var resume = new ManagedSuspensions.Resume(scope, "r", "t", "s", 1, 1);
        var pending = new AtomicBoolean(true);
        var due = new AtomicLong();
        when(store.dataSource()).thenReturn(listenerSource);
        when(store.pending(64, scope.messageGroup()))
                .thenAnswer(call -> pending.get() ? List.of(resume) : List.of());
        when(store.publication(resume))
                .thenAnswer(
                        call ->
                                pending.get() && System.currentTimeMillis() >= due.get()
                                        ? OptionalLong.of(0)
                                        : OptionalLong.empty());
        when(bus.publish(eq(ManagedMessageBus.Channel.TURN_RESUMES), any()))
                .thenReturn(new ManagedMessageBus.Published("broker"));
        doAnswer(
                        call -> {
                            pending.set(false);
                            return true;
                        })
                .when(store)
                .published(resume, "broker", 0);
        var leases = new InMemoryManagedLeaseStore(() -> System.nanoTime() / 1_000_000);
        try (var publisher =
                new ManagedResumePublisher(
                        store, bus, leases, new ObjectMapper(), Duration.ofHours(1))) {
            publisher.start();
            // Startup repair plus the post-LISTEN recovery pass: listener is now registered.
            verify(store, timeout(5000).times(2)).pending(256, null, 0);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(writerSource));
            tx.executeWithoutResult(
                    status -> {
                        ManagedResumeSignals.committed(jdbc, scope.messageGroup());
                        verifyNoInteractions(bus);
                        status.setRollbackOnly();
                    });
            Thread.sleep(150);
            verifyNoInteractions(bus);
            due.set(System.currentTimeMillis() + (delayed ? 600 : 0));
            tx.executeWithoutResult(
                    status -> {
                        ManagedResumeSignals.committed(
                                jdbc, scope.messageGroup() + (delayed ? ":" + due.get() : ""));
                        verifyNoInteractions(bus);
                    });
            if (delayed) {
                Thread.sleep(150);
                verifyNoInteractions(bus);
            }
            verify(store, timeout(3000)).published(resume, "broker", 0);
            verify(store, times(2)).pending(256, null, 0);
        }
    }
}
