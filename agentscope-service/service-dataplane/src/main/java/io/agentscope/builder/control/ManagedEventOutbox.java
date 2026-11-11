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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.managed.service.SessionEventScope;
import io.agentscope.builder.web.persistence.jpa.SessionEventFenceEntity;
import io.agentscope.builder.web.persistence.jpa.SessionEventFenceRepository;
import io.agentscope.builder.web.persistence.jpa.SessionEventOutboxEntity;
import io.agentscope.builder.web.persistence.jpa.SessionEventOutboxRepository;
import io.agentscope.builder.web.persistence.jpa.SessionExecutionScopeEntity;
import io.agentscope.builder.web.persistence.jpa.SessionExecutionScopeRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.scheduler.Schedulers;

/** Durable, ordered, at-least-once delivery using the original execution fence. */
@Service
public class ManagedEventOutbox {
    private static final Logger LOG = LoggerFactory.getLogger(ManagedEventOutbox.class);
    private final SessionEventOutboxRepository events;
    private final SessionExecutionScopeRepository scopes;
    private final SessionEventFenceRepository fences;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ControlPlaneClient client;
    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();

    public ManagedEventOutbox(
            SessionEventOutboxRepository events,
            SessionExecutionScopeRepository scopes,
            SessionEventFenceRepository fences,
            TransactionTemplate tx,
            ObjectMapper mapper,
            ControlPlaneClient client) {
        this.events = events;
        this.scopes = scopes;
        this.fences = fences;
        this.tx = tx;
        this.mapper = mapper;
        this.client = client;
    }

    /** Called inside the source-event transaction. Failure must roll it back. */
    public void enqueue(SessionEventDto event, SessionEventScope scope) {
        if (scope == null || !scope.managed()) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Mirror outbox requires the source event transaction");
        String report = json(client.sessionEventReport(event, scope));
        var old = events.findById(event.id());
        if (old.isPresent()) {
            if (!old.get().sessionId.equals(event.sessionId())
                    || !old.get().attemptId.equals(scope.attemptId())
                    || !tree(old.get().reportJson).equals(tree(report)))
                throw new IllegalStateException(
                        "Mirror event identity or fence changed: " + event.id());
            return;
        }
        var fence = lockFence(event.sessionId(), scope.attemptId());
        if (fence.sealed || (fence.closing && !scope.nativeFact()))
            throw new IllegalStateException(
                    "Managed Attempt events are permanently sealed: " + scope.attemptId());
        var row = new SessionEventOutboxEntity();
        row.eventId = event.id();
        row.sessionId = event.sessionId();
        row.attemptId = scope.attemptId();
        row.eventSeq = event.seq();
        row.reportJson = report;
        row.createdAt = System.currentTimeMillis();
        events.saveAndFlush(row);
    }

    public void beginAdmission(String session, String admission, SessionEventScope scope) {
        SessionEventScope captured = scope == null ? SessionEventScope.NONE : scope;
        tx.executeWithoutResult(
                status -> {
                    if (captured.managed()) {
                        var fence = lockFence(session, captured.attemptId());
                        if (fence.closing || fence.sealed)
                            throw new IllegalStateException(
                                    "Managed Attempt admission is permanently sealed");
                    }
                    var row =
                            scopes.findById(admission).orElseGet(SessionExecutionScopeEntity::new);
                    if (row.admissionId != null
                            && (!row.sessionId.equals(session)
                                    || !tree(row.scopeJson).equals(tree(json(captured)))))
                        throw new IllegalStateException("Admission fence cannot change");
                    row.admissionId = admission;
                    row.sessionId = session;
                    row.attemptId = captured.attemptId();
                    row.scopeJson = json(captured);
                    row.leaseUntil = System.currentTimeMillis() + 60000;
                    scopes.saveAndFlush(row);
                });
    }

    public void bindRun(String admission, String run, long nativeStartSeq) {
        tx.executeWithoutResult(
                status -> {
                    requireAdmissionOpen(scopes.findById(admission).orElseThrow());
                    var row = scopes.lockById(admission).orElseThrow();
                    if (row.finished || row.leaseUntil <= System.currentTimeMillis())
                        throw new IllegalStateException("Admission expired before execution began");
                    if (row.runId != null && !row.runId.equals(run))
                        throw new IllegalStateException("Admission already bound to another run");
                    row.runId = run;
                    row.nativeStartSeq = nativeStartSeq;
                    scopes.save(row);
                });
    }

    public SessionEventScope scopeForRun(String session, String run) {
        var row =
                scopes.findByRunId(run)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Missing immutable mirror fence for native run "
                                                        + run));
        if (!row.sessionId.equals(session))
            throw new IllegalStateException("Run belongs to another session");
        try {
            return mapper.readValue(row.scopeJson, SessionEventScope.class);
        } catch (Exception error) {
            throw new IllegalStateException("Invalid saved mirror fence", error);
        }
    }

    public void renewAdmission(String admission) {
        tx.executeWithoutResult(
                status ->
                        scopes.findById(admission)
                                .ifPresent(
                                        saved -> {
                                            // Attempt fence precedes the admission row lock.
                                            requireAdmissionOpen(saved);
                                            var row = scopes.lockById(admission).orElseThrow();
                                            if (row.finished
                                                    || row.leaseUntil <= System.currentTimeMillis())
                                                throw new IllegalStateException(
                                                        "Managed event admission expired");
                                            row.leaseUntil = System.currentTimeMillis() + 60000;
                                            scopes.save(row);
                                        }));
    }

    private void requireAdmissionOpen(SessionExecutionScopeEntity row) {
        if (row.attemptId == null) return;
        var fence = lockFence(row.sessionId, row.attemptId);
        if (fence.closing || fence.sealed)
            throw new IllegalStateException("Managed Attempt admission is permanently sealed");
    }

    public void finishAdmission(String admission) {
        tx.executeWithoutResult(
                status ->
                        scopes.lockById(admission)
                                .ifPresent(
                                        row -> {
                                            row.finished = true;
                                            row.leaseUntil = 0;
                                            scopes.save(row);
                                        }));
    }

    /** Freeze native writers under the admission lock; export must happen after this transaction. */
    public boolean freezeAttemptIfFinished(
            String session,
            String attempt,
            Predicate<List<SessionExecutionScopeEntity>> freezeNative) {
        return Boolean.TRUE.equals(
                tx.execute(
                        status -> {
                            var fence = lockFence(session, attempt);
                            if (fence.closing || fence.sealed) return true;
                            if (!freezeNative.test(
                                    scopes.findBySessionIdAndAttemptId(session, attempt)))
                                return false;
                            fence.closing = true;
                            fences.saveAndFlush(fence);
                            return true;
                        }));
    }

    /** After native export committed in its own transactions, close the final delivery barrier. */
    public void sealAttempt(String session, String attempt) {
        tx.executeWithoutResult(
                status -> {
                    var fence = lockFence(session, attempt);
                    if (!fence.closing)
                        throw new IllegalStateException(
                                "Native writers must be frozen before mirror sealing");
                    fence.sealed = true;
                    fences.saveAndFlush(fence);
                });
    }

    private SessionEventFenceEntity lockFence(String session, String attempt) {
        var fence =
                fences.lockById(attempt)
                        .orElseGet(
                                () -> {
                                    var created = new SessionEventFenceEntity();
                                    created.attemptId = attempt;
                                    created.sessionId = session;
                                    return fences.saveAndFlush(created);
                                });
        if (!session.equals(fence.sessionId))
            throw new IllegalStateException("Attempt belongs to another session");
        return fence;
    }

    public record DeliveryStatus(long pending, long lastEnqueuedSeq, long lastDeliveredSeq) {}

    public DeliveryStatus status(String session, String attempt) {
        long pending = 0, enqueued = 0, delivered = 0;
        for (var event : events.findBySessionIdAndAttemptIdOrderByEventSeqAsc(session, attempt)) {
            enqueued = Math.max(enqueued, event.eventSeq);
            if (event.deliveredAt == 0) pending++;
            else delivered = Math.max(delivered, event.eventSeq);
        }
        return new DeliveryStatus(pending, enqueued, delivered);
    }

    @Scheduled(fixedDelayString = "${builder.session-event.mirror-poll-ms:1000}")
    public void deliver() {
        if (activeSessions.size() >= 32) return;
        for (var candidate :
                events.findDeliverable(System.currentTimeMillis(), PageRequest.of(0, 32))) {
            if (!activeSessions.add(candidate.sessionId)) continue;
            Schedulers.boundedElastic()
                    .schedule(
                            () -> {
                                try {
                                    for (int batch = 0; batch < 128; batch++) {
                                        var next =
                                                events
                                                        .findFirstBySessionIdAndDeliveredAtOrderByEventSeqAsc(
                                                                candidate.sessionId, 0);
                                        if (next.isEmpty() || !deliver(next.get().eventId)) return;
                                    }
                                } catch (RuntimeException error) {
                                    LOG.warn(
                                            "Managed outbox scan pending retry: {}",
                                            candidate.sessionId,
                                            error);
                                } finally {
                                    activeSessions.remove(candidate.sessionId);
                                }
                            });
        }
    }

    boolean deliver(String id) {
        String worker = UUID.randomUUID().toString();
        var claimed =
                tx.execute(
                        status -> {
                            var row = events.lockById(id).orElseThrow();
                            long now = System.currentTimeMillis();
                            if (row.deliveredAt != 0
                                    || row.leaseUntil > now
                                    || row.nextAttemptAt > now) return null;
                            row.workerId = worker;
                            row.leaseUntil = now + 30000;
                            row.attempts++;
                            return events.save(row);
                        });
        if (claimed == null) return false;
        String failure = null;
        try {
            client.sendSessionEventReport(
                    claimed.sessionId, mapper.readValue(claimed.reportJson, Map.class));
        } catch (Exception error) {
            failure = error.toString();
            LOG.warn(
                    "Managed event delivery pending: session={}, event={}, attempt={}, error={}",
                    claimed.sessionId,
                    id,
                    claimed.attemptId,
                    failure);
        }
        String error = failure;
        tx.executeWithoutResult(
                status ->
                        events.lockById(id)
                                .ifPresent(
                                        row -> {
                                            if (!Objects.equals(row.workerId, worker)) return;
                                            row.workerId = null;
                                            row.leaseUntil = 0;
                                            if (error == null) {
                                                row.deliveredAt = System.currentTimeMillis();
                                                row.lastError = null;
                                            } else {
                                                row.lastError =
                                                        error.substring(
                                                                0, Math.min(1024, error.length()));
                                                row.nextAttemptAt =
                                                        System.currentTimeMillis()
                                                                + Math.min(
                                                                        30000,
                                                                        1000L
                                                                                << Math.min(
                                                                                        row.attempts,
                                                                                        5));
                                            }
                                            events.save(row);
                                        }));
        return error == null;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot freeze mirror report", error);
        }
    }

    private Object tree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception error) {
            throw new IllegalStateException("Invalid mirror report", error);
        }
    }
}
