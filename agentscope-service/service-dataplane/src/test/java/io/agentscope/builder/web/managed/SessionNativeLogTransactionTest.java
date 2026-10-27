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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class SessionNativeLogTransactionTest {
    private final SessionEventLog events = mock(SessionEventLog.class);
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
        var sources = mock(SessionExportSourceRepository.class);
        var source = new SessionExportSourceEntity();
        source.sessionId = "session";
        source.userId = "user";
        source.agentId = "agent";
        when(sources.findById("session")).thenReturn(Optional.of(source));
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
}
