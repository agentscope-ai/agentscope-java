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

/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.builder.web.managed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntity;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntityRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class SessionEventLogMirrorTest {

    @Test
    void concurrentImmutableEventIsReusedWithoutSequenceRetryStorm() {
        var repository = mock(SessionEventEntityRepository.class);
        var winner = new SessionEventEntity();
        winner.setSessionId("session");
        winner.setEventId("event");
        winner.setEventType("item.delta");
        winner.setPayloadJson("{\"text\":\"x\"}");
        winner.setSeq(1);
        when(repository.findByEventId("event")).thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.saveAndFlush(any(SessionEventEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate immutable event"));
        var transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any()))
                .thenAnswer(
                        call ->
                                ((TransactionCallback<?>) call.getArgument(0))
                                        .doInTransaction(null));
        var log =
                new SessionEventLog(
                        repository,
                        new ManagedJsonHelper(new ObjectMapper()),
                        transactions,
                        new DeletedSessionRegistry(),
                        mock(SessionEventNotifier.class),
                        30000);
        assertThat(
                        log.appendIdempotentLocal(
                                        "session", "item.delta", Map.of("text", "x"), "event")
                                .seq())
                .isEqualTo(1);
        verify(repository, times(1)).saveAndFlush(any(SessionEventEntity.class));
    }

    @Test
    void stagesMirrorBeforeSourceTransactionCompletes() {
        SessionEventEntityRepository repository = mock(SessionEventEntityRepository.class);
        when(repository.maxSeq("session-1")).thenReturn(0L);
        when(repository.saveAndFlush(any(SessionEventEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AtomicBoolean insideTransaction = new AtomicBoolean();
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any()))
                .thenAnswer(
                        invocation -> {
                            TransactionCallback<?> callback = invocation.getArgument(0);
                            insideTransaction.set(true);
                            try {
                                return callback.doInTransaction(null);
                            } finally {
                                insideTransaction.set(false);
                            }
                        });
        List<SessionEventDto> mirrored = new ArrayList<>();
        SessionEventLog log =
                new SessionEventLog(
                        repository,
                        new ManagedJsonHelper(new ObjectMapper()),
                        transactions,
                        new DeletedSessionRegistry(),
                        mock(SessionEventNotifier.class),
                        List.of(
                                (event, scope) -> {
                                    assertThat(insideTransaction).isTrue();
                                    mirrored.add(event);
                                }),
                        30_000L);

        SessionEventDto event = log.append("session-1", "agent.message", Map.of("text", "ready"));

        assertThat(event.seq()).isEqualTo(1L);
        assertThat(mirrored).containsExactly(event);
    }

    @Test
    void failedOutboxStagingAbortsAppendAndDoesNotNotifyReaders() {
        var repository = mock(SessionEventEntityRepository.class);
        when(repository.maxSeq("session-1")).thenReturn(0L);
        when(repository.saveAndFlush(any(SessionEventEntity.class)))
                .thenAnswer(call -> call.getArgument(0));
        var transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any()))
                .thenAnswer(
                        call ->
                                ((TransactionCallback<?>) call.getArgument(0))
                                        .doInTransaction(null));
        var notifier = mock(SessionEventNotifier.class);
        var log =
                new SessionEventLog(
                        repository,
                        new ManagedJsonHelper(new ObjectMapper()),
                        transactions,
                        new DeletedSessionRegistry(),
                        notifier,
                        List.of(
                                (event, scope) -> {
                                    throw new IllegalStateException("outbox unavailable");
                                }),
                        30000);
        assertThatThrownBy(() -> log.append("session-1", "run.started", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox unavailable");
        verifyNoInteractions(notifier);
    }
}
