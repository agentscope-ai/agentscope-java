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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.persistence.jpa.SessionEventAppendOperations.AppendResult;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntity;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntityRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class SessionEventLogMirrorTest {

    @Test
    void idempotentAppendReusesSerializedPayloadAndDecodedWinner() {
        var repository = mock(SessionEventEntityRepository.class);
        var saved = new AtomicReference<SessionEventEntity>();
        when(repository.appendIdempotentEvent(any(SessionEventEntity.class)))
                .thenAnswer(
                        call -> {
                            SessionEventEntity candidate = call.getArgument(0);
                            candidate.setSeq(1);
                            boolean inserted = saved.compareAndSet(null, candidate);
                            return new AppendResult(saved.get(), inserted);
                        });
        var json = spy(new ManagedJsonHelper(new ObjectMapper()));
        var log =
                new SessionEventLog(
                        repository,
                        json,
                        transactions(new AtomicBoolean()),
                        new DeletedSessionRegistry(),
                        mock(SessionEventNotifier.class),
                        30000);

        var first =
                log.appendIdempotentLocal("session", "item.delta", Map.of("count", 1L), "event");

        assertThat(first.payload()).containsEntry("count", 1);
        verify(json, times(1)).writeJson(any());
        verify(json, times(1)).readMap(anyString());
        clearInvocations(json);

        var replay =
                log.appendIdempotentLocal("session", "item.delta", Map.of("count", 1), "event");

        assertThat(replay).isEqualTo(first);
        verify(json, times(1)).writeJson(any());
        verify(json, times(2)).readMap(anyString());
    }

    @Test
    void immutableEventWinnerIsReusedWithoutAllocatingAnotherSequence() {
        var repository = mock(SessionEventEntityRepository.class);
        var winner = new SessionEventEntity();
        winner.setSessionId("session");
        winner.setEventId("event");
        winner.setEventType("item.delta");
        winner.setPayloadJson("{\"text\":\"x\"}");
        winner.setSeq(1);
        when(repository.appendIdempotentEvent(any(SessionEventEntity.class)))
                .thenReturn(new AppendResult(winner, false));
        var transactions = transactions(new AtomicBoolean());
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
        verify(repository, times(1)).appendIdempotentEvent(any(SessionEventEntity.class));
    }

    @Test
    void stagesMirrorBeforeSourceTransactionCompletes() {
        SessionEventEntityRepository repository = mock(SessionEventEntityRepository.class);
        when(repository.appendEvent(any(SessionEventEntity.class)))
                .thenAnswer(
                        invocation -> {
                            SessionEventEntity event = invocation.getArgument(0);
                            event.setSeq(1);
                            return event;
                        });
        AtomicBoolean insideTransaction = new AtomicBoolean();
        TransactionTemplate transactions = transactions(insideTransaction);
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
        when(repository.appendEvent(any(SessionEventEntity.class)))
                .thenAnswer(
                        call -> {
                            SessionEventEntity event = call.getArgument(0);
                            event.setSeq(1);
                            return event;
                        });
        var transactions = transactions(new AtomicBoolean());
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

    private static TransactionTemplate transactions(AtomicBoolean insideTransaction) {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any()))
                .thenAnswer(
                        call -> {
                            TransactionDefinition definition = call.getArgument(0);
                            assertThat(definition.getPropagationBehavior())
                                    .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
                            assertThat(definition.getIsolationLevel())
                                    .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
                            insideTransaction.set(true);
                            return mock(TransactionStatus.class);
                        });
        doAnswer(
                        call -> {
                            insideTransaction.set(false);
                            return null;
                        })
                .when(manager)
                .commit(any());
        doAnswer(
                        call -> {
                            insideTransaction.set(false);
                            return null;
                        })
                .when(manager)
                .rollback(any());
        return new TransactionTemplate(manager);
    }
}
