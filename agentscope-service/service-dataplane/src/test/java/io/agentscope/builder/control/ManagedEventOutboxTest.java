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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.managed.service.SessionEventScope;
import io.agentscope.builder.web.persistence.jpa.SessionEventFenceEntity;
import io.agentscope.builder.web.persistence.jpa.SessionEventFenceRepository;
import io.agentscope.builder.web.persistence.jpa.SessionEventOutboxEntity;
import io.agentscope.builder.web.persistence.jpa.SessionEventOutboxRepository;
import io.agentscope.builder.web.persistence.jpa.SessionExecutionScopeEntity;
import io.agentscope.builder.web.persistence.jpa.SessionExecutionScopeRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class ManagedEventOutboxTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SessionEventOutboxRepository events = mock(SessionEventOutboxRepository.class);
    private final SessionExecutionScopeRepository scopes =
            mock(SessionExecutionScopeRepository.class);
    private final SessionEventFenceRepository fences = mock(SessionEventFenceRepository.class);
    private final Map<String, SessionEventFenceEntity> savedFences = new HashMap<>();
    private final Map<String, SessionEventOutboxEntity> savedEvents = new HashMap<>();
    private final Map<String, SessionExecutionScopeEntity> savedScopes = new HashMap<>();
    private final TransactionTemplate tx =
            new TransactionTemplate(mock(PlatformTransactionManager.class));
    private final ControlPlaneClient client =
            spy(new ControlPlaneClient("http://unused.invalid", "test", mapper));
    private final SessionEventScope original =
            new SessionEventScope("tenant", "task", "attempt-a", 1, "turn-a");
    private final SessionEventScope replacement =
            new SessionEventScope("tenant", "task", "attempt-b", 2, "turn-b");
    private ManagedEventOutbox outbox;

    @BeforeEach
    void prepare() {
        when(fences.lockById(anyString()))
                .thenAnswer(call -> Optional.ofNullable(savedFences.get(call.getArgument(0))));
        when(fences.saveAndFlush(any()))
                .thenAnswer(
                        call -> {
                            SessionEventFenceEntity row = call.getArgument(0);
                            savedFences.put(row.attemptId, row);
                            return row;
                        });
        when(events.findById(anyString()))
                .thenAnswer(call -> Optional.ofNullable(savedEvents.get(call.getArgument(0))));
        when(events.lockById(anyString()))
                .thenAnswer(call -> Optional.ofNullable(savedEvents.get(call.getArgument(0))));
        when(events.saveAndFlush(any()))
                .thenAnswer(
                        call -> {
                            SessionEventOutboxEntity row = call.getArgument(0);
                            savedEvents.put(row.eventId, row);
                            return row;
                        });
        when(events.save(any())).thenAnswer(call -> call.getArgument(0));
        when(events.findBySessionIdAndAttemptIdOrderByEventSeqAsc(anyString(), anyString()))
                .thenAnswer(
                        call ->
                                savedEvents.values().stream()
                                        .filter(
                                                row ->
                                                        row.sessionId.equals(call.getArgument(0))
                                                                && row.attemptId.equals(
                                                                        call.getArgument(1)))
                                        .toList());
        when(scopes.findById(anyString()))
                .thenAnswer(call -> Optional.ofNullable(savedScopes.get(call.getArgument(0))));
        when(scopes.lockById(anyString()))
                .thenAnswer(call -> Optional.ofNullable(savedScopes.get(call.getArgument(0))));
        when(scopes.findByRunId(anyString()))
                .thenAnswer(
                        call ->
                                savedScopes.values().stream()
                                        .filter(row -> call.getArgument(0).equals(row.runId))
                                        .findFirst());
        when(scopes.saveAndFlush(any()))
                .thenAnswer(
                        call -> {
                            SessionExecutionScopeEntity row = call.getArgument(0);
                            savedScopes.put(row.admissionId, row);
                            return row;
                        });
        TransactionSynchronizationManager.setActualTransactionActive(true);
        outbox = new ManagedEventOutbox(events, scopes, fences, tx, mapper, client);
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void nativeReplayKeepsOriginalAttemptAfterReplacementAndRestart() {
        outbox.beginAdmission("session", "admission-a", original);
        outbox.bindRun("admission-a", "run-a", 0);
        outbox.finishAdmission("admission-a");
        outbox.beginAdmission("session", "admission-b", replacement);
        outbox.bindRun("admission-b", "run-b", 8);
        var restarted = new ManagedEventOutbox(events, scopes, fences, tx, mapper, client);
        assertThat(restarted.scopeForRun("session", "run-a")).isEqualTo(original);
        assertThat(restarted.scopeForRun("session", "run-b")).isEqualTo(replacement);
        assertThatThrownBy(() -> restarted.scopeForRun("different-session", "run-a"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> restarted.scopeForRun("session", "unknown-run"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deliveryFailureSurvivesRestartAndRetriesTheFrozenReport() {
        var event =
                new SessionEventDto(
                        "event", "session", 4, "run.ended", Map.of("run_id", "run-a"), null, 7);
        outbox.enqueue(event, original);
        outbox.enqueue(event, original);
        assertThat(savedEvents).hasSize(1);
        assertThatThrownBy(() -> outbox.enqueue(event, replacement))
                .isInstanceOf(IllegalStateException.class);
        doThrow(new IllegalStateException("CP unavailable"))
                .doNothing()
                .when(client)
                .sendSessionEventReport(anyString(), any());
        assertThat(outbox.deliver("event")).isFalse();
        assertThat(outbox.status("session", "attempt-a").pending()).isEqualTo(1);
        assertThat(savedEvents.get("event").lastError).contains("CP unavailable");
        savedEvents.get("event").nextAttemptAt = 0;
        var restarted = new ManagedEventOutbox(events, scopes, fences, tx, mapper, client);
        assertThat(restarted.deliver("event")).isTrue();
        assertThat(restarted.status("session", "attempt-a").pending()).isZero();
        assertThat(restarted.status("session", "attempt-a").lastDeliveredSeq()).isEqualTo(4);
        assertThat(restarted.deliver("event")).isFalse();
        ArgumentCaptor<Map<String, Object>> reports = ArgumentCaptor.forClass(Map.class);
        verify(client, times(2)).sendSessionEventReport(anyString(), reports.capture());
        assertThat(reports.getAllValues())
                .allSatisfy(
                        report ->
                                assertThat(report)
                                        .containsEntry("attemptId", "attempt-a")
                                        .containsEntry("turnId", "turn-a"));
    }

    @Test
    void expiredAdmissionCannotBeginANewNativeRun() {
        outbox.beginAdmission("session", "admission-a", original);
        savedScopes.get("admission-a").leaseUntil = 0;
        assertThatThrownBy(() -> outbox.bindRun("admission-a", "late-run", 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void closingFreezesServiceWritesWhileNativeExportCommitsBeforeFinalSeal() {
        outbox.beginAdmission("session", "admission-a", original);
        outbox.finishAdmission("admission-a");
        assertThat(outbox.freezeAttemptIfFinished("session", "attempt-a", admissions -> true))
                .isTrue();
        assertThatThrownBy(() -> outbox.beginAdmission("session", "late-admission", original))
                .isInstanceOf(IllegalStateException.class);
        var event =
                new SessionEventDto("native-tail", "session", 7, "run.ended", Map.of(), null, 7);
        assertThatThrownBy(() -> outbox.enqueue(event, original))
                .isInstanceOf(IllegalStateException.class);
        outbox.enqueue(event, original.forNativeFact());
        outbox.sealAttempt("session", "attempt-a");
        outbox.enqueue(event, original.forNativeFact()); // identical replay is still safe
        var late = new SessionEventDto("late", "session", 8, "run.ended", Map.of(), null, 8);
        assertThatThrownBy(() -> outbox.enqueue(late, original.forNativeFact()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(outbox.status("session", "attempt-a").pending()).isEqualTo(1);
        // Terminal-before-dispatch also creates a durable fence, not just an empty ready answer.
        outbox.freezeAttemptIfFinished("session", "attempt-b", admissions -> true);
        outbox.sealAttempt("session", "attempt-b");
        assertThatThrownBy(() -> outbox.beginAdmission("session", "never-started", replacement))
                .isInstanceOf(IllegalStateException.class);
    }
}
