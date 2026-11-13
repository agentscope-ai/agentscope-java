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
package io.agentscope.builder.web.managed.service;

import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntity;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntityRepository;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Append-only session event log. A transactional per-session counter serializes writers across
 * replicas, including the first append to an empty log. Live SSE fan-out is notification-driven
 * and always reads from the durable sequence
 * cursor, so events written by any plane or replica are visible without treating notifications as
 * data.
 */
@Service
public class SessionEventLog {

    private static final Logger log = LoggerFactory.getLogger(SessionEventLog.class);
    private final SessionEventEntityRepository repository;
    private final ManagedJsonHelper jsonHelper;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate writeTransactionTemplate;
    private final DeletedSessionRegistry deletedSessions;
    private final SessionEventNotifier notifier;
    private final List<SessionEventMirror> mirrors;
    private final long recoveryIntervalMs;

    @Autowired
    public SessionEventLog(
            SessionEventEntityRepository repository,
            ManagedJsonHelper jsonHelper,
            TransactionTemplate transactionTemplate,
            DeletedSessionRegistry deletedSessions,
            SessionEventNotifier notifier,
            List<SessionEventMirror> mirrors,
            @Value("${builder.session-event.recovery-interval-ms:30000}") long recoveryIntervalMs) {
        this.repository = repository;
        this.jsonHelper = jsonHelper;
        this.transactionTemplate = transactionTemplate;
        this.writeTransactionTemplate =
                new TransactionTemplate(
                        transactionTemplate.getTransactionManager(), transactionTemplate);
        this.writeTransactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRED);
        this.writeTransactionTemplate.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.deletedSessions = deletedSessions;
        this.notifier = notifier;
        this.mirrors = mirrors != null ? List.copyOf(mirrors) : List.of();
        this.recoveryIntervalMs = Math.max(1_000L, recoveryIntervalMs);
    }

    /** Constructor retained for focused tests and embedders without event mirrors. */
    public SessionEventLog(
            SessionEventEntityRepository repository,
            ManagedJsonHelper jsonHelper,
            TransactionTemplate transactionTemplate,
            DeletedSessionRegistry deletedSessions,
            SessionEventNotifier notifier,
            long recoveryIntervalMs) {
        this(
                repository,
                jsonHelper,
                transactionTemplate,
                deletedSessions,
                notifier,
                List.of(),
                recoveryIntervalMs);
    }

    /**
     * Appends an event with the next monotonic sequence number. The counter, event and mirror
     * commit together, joining a caller transaction when present. Readers are notified after commit.
     */
    public SessionEventDto append(String sessionId, String type, Map<String, Object> payload) {
        return append(sessionId, type, payload, null);
    }

    /** Appends child-session events without control-plane delivery. */
    public SessionEventDto appendLocal(
            String sessionId, String type, Map<String, Object> payload, String eventId) {
        return appendInternal(sessionId, type, payload, eventId, false, null);
    }

    /**
     * Appends an event, optionally reusing a pre-allocated {@code eventId} so stream previews can
     * reconcile with the persisted row.
     */
    public SessionEventDto append(
            String sessionId, String type, Map<String, Object> payload, String eventId) {
        return appendInternal(sessionId, type, payload, eventId, true, null);
    }

    public SessionEventDto appendScoped(
            String sessionId,
            String type,
            Map<String, Object> payload,
            String eventId,
            SessionEventScope scope) {
        return appendInternal(sessionId, type, payload, eventId, true, scope);
    }

    private SessionEventDto appendInternal(
            String sessionId,
            String type,
            Map<String, Object> payload,
            String eventId,
            boolean mirror,
            SessionEventScope scope) {
        if (deletedSessions.isDeleted(sessionId)) {
            log.debug("Dropping {} event for deleted session {}", type, sessionId);
            return droppedEvent(sessionId, type, payload, eventId);
        }
        return writeTransactionTemplate.execute(
                status -> {
                    var event = appendOnce(sessionId, type, payload, eventId);
                    if (mirror) enqueueMirrors(event, scope);
                    notifyAfterCommit(sessionId);
                    return event;
                });
    }

    /**
     * Appends an event once using a caller-owned stable id. Replays return and re-mirror the
     * existing event, which is useful for reliable cross-plane delivery without duplicating the
     * local transcript.
     */
    public SessionEventDto appendIdempotent(
            String sessionId, String type, Map<String, Object> payload, String eventId) {
        return appendIdempotent(sessionId, type, payload, eventId, true, null);
    }

    /** Appends/replays locally without an unscoped generic mirror. */
    public SessionEventDto appendIdempotentLocal(
            String sessionId, String type, Map<String, Object> payload, String eventId) {
        return appendIdempotent(sessionId, type, payload, eventId, false, null);
    }

    public SessionEventDto appendIdempotentScoped(
            String sessionId,
            String type,
            Map<String, Object> payload,
            String eventId,
            SessionEventScope scope) {
        return appendIdempotent(sessionId, type, payload, eventId, true, scope);
    }

    private SessionEventDto appendIdempotent(
            String sessionId,
            String type,
            Map<String, Object> payload,
            String eventId,
            boolean mirror,
            SessionEventScope scope) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required for idempotent append");
        }
        if (deletedSessions.isDeleted(sessionId)) {
            return droppedEvent(sessionId, type, payload, eventId);
        }
        return writeTransactionTemplate.execute(
                status -> {
                    var candidate = newEvent(sessionId, type, payload, eventId);
                    var result = repository.appendIdempotentEvent(candidate);
                    var event = toDto(result.event());
                    if (!result.inserted()) validateReplay(event, candidate);
                    if (mirror) enqueueMirrors(event, scope);
                    if (result.inserted()) notifyAfterCommit(sessionId);
                    return event;
                });
    }

    private void validateReplay(SessionEventDto existing, SessionEventEntity candidate) {
        if (!candidate.getSessionId().equals(existing.sessionId())
                || !candidate.getEventType().equals(existing.type())) {
            throw new IllegalStateException(
                    "eventId already belongs to another session/event type: "
                            + candidate.getEventId());
        }
        if (!Objects.equals(existing.payload(), jsonHelper.readMap(candidate.getPayloadJson()))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "eventId reused with different payload");
        }
    }

    /** Command admission and its public fact commit together. Publish only after commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public SessionEventDto appendCommand(
            String sessionId, String type, Map<String, Object> payload, String eventId) {
        var event = appendOnce(sessionId, type, payload, eventId);
        enqueueMirrors(event, null);
        notifyAfterCommit(sessionId);
        return event;
    }

    private void notifyAfterCommit(String sessionId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            notifier.publish(sessionId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        notifier.publish(sessionId);
                    }
                });
    }

    private void enqueueMirrors(SessionEventDto event, SessionEventScope scope) {
        if (event == null || event.seq() <= 0) return;
        for (SessionEventMirror mirror : mirrors) {
            mirror.mirror(event, scope == null ? SessionEventScope.NONE : scope);
        }
    }

    private SessionEventDto appendOnce(
            String sessionId, String type, Map<String, Object> payload, String eventId) {
        return toDto(repository.appendEvent(newEvent(sessionId, type, payload, eventId)));
    }

    private SessionEventEntity newEvent(
            String sessionId, String type, Map<String, Object> payload, String eventId) {
        long now = System.currentTimeMillis();
        SessionEventEntity entity = new SessionEventEntity();
        entity.setEventId(
                eventId != null && !eventId.isBlank()
                        ? eventId
                        : "evt_" + UUID.randomUUID().toString().replace("-", ""));
        entity.setSessionId(sessionId);
        entity.setEventType(type);
        entity.setPayloadJson(jsonHelper.writeJson(payload));
        entity.setProcessedAt(now);
        entity.setCreatedAt(now);
        return entity;
    }

    /** Lists all events for a session in sequence order. */
    @Transactional(readOnly = true)
    public List<SessionEventDto> list(String sessionId) {
        return list(sessionId, null);
    }

    /**
     * Lists events for a session, optionally restricted to {@code types}. When {@code types} is
     * null or empty, all types are returned.
     */
    @Transactional(readOnly = true)
    public List<SessionEventDto> list(String sessionId, Collection<String> types) {
        if (types == null || types.isEmpty()) {
            return repository.findBySessionIdOrderBySeqAsc(sessionId).stream()
                    .map(this::toDto)
                    .toList();
        }
        return repository.findBySessionIdAndEventTypeInOrderBySeqAsc(sessionId, types).stream()
                .map(this::toDto)
                .toList();
    }

    /** Lists events with sequence strictly greater than {@code afterSeq}. */
    @Transactional(readOnly = true)
    public List<SessionEventDto> listAfter(String sessionId, long afterSeq) {
        return listAfter(sessionId, afterSeq, null);
    }

    /**
     * Lists events after {@code afterSeq}, optionally restricted to {@code types}.
     */
    @Transactional(readOnly = true)
    public List<SessionEventDto> listAfter(
            String sessionId, long afterSeq, Collection<String> types) {
        if (types == null || types.isEmpty()) {
            return listAfterUnchecked(sessionId, afterSeq);
        }
        return repository
                .findBySessionIdAndEventTypeInAndSeqGreaterThanOrderBySeqAsc(
                        sessionId, types, afterSeq)
                .stream()
                .map(this::toDto)
                .toList();
    }

    /** Bounded database read over a fixed committed prefix. */
    @Transactional(readOnly = true)
    public List<SessionEventDto> page(String sessionId, long after, long through, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        return repository
                .findBySessionIdAndSeqGreaterThanAndSeqLessThanEqualOrderBySeqAsc(
                        sessionId, after, through, PageRequest.of(0, limit))
                .stream()
                .map(this::toDto)
                .toList();
    }

    /** Looks up a single event by its public identifier. */
    @Transactional(readOnly = true)
    public Optional<SessionEventDto> findByEventId(String eventId) {
        return repository.findByEventId(eventId).map(this::toDto);
    }

    /** Looks up a single event by its public identifier, requiring it to exist. */
    @Transactional(readOnly = true)
    public SessionEventDto getByEventId(String eventId) {
        return findByEventId(eventId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Event not found: " + eventId));
    }

    @Transactional(readOnly = true)
    public long highWatermark(String sessionId) {
        return repository.maxSeq(sessionId);
    }

    /** Returns a live flux of events for SSE streaming, starting after sequence 0. */
    public Flux<SessionEventDto> subscribe(String sessionId) {
        return subscribe(sessionId, 0L);
    }

    /**
     * Reads events with sequence strictly greater than {@code afterSeq} after an immediate,
     * in-process, or PostgreSQL cross-replica notification. A low-frequency recovery tick protects
     * against notification loss and databases without LISTEN/NOTIFY support.
     *
     * <p>Each poll runs inside {@link TransactionTemplate}: PostgreSQL {@code @Lob} CLOB/OID
     * payload reads require a transaction, and calling {@link #listAfter} via {@code this.} would
     * bypass the Spring {@code @Transactional} proxy.
     */
    public Flux<SessionEventDto> subscribe(String sessionId, long afterSeq) {
        AtomicLong cursor = new AtomicLong(Math.max(0L, afterSeq));
        Flux<Long> recovery = Flux.interval(Duration.ofMillis(recoveryIntervalMs));
        Flux<Long> wakeups =
                Flux.merge(Flux.just(0L), recovery, notifier.wakeups(sessionId).map(ignored -> 0L))
                        .onBackpressureLatest();
        return wakeups.concatMap(
                ignored ->
                        Mono.fromCallable(
                                        () ->
                                                transactionTemplate.execute(
                                                        status -> repository.maxSeq(sessionId)))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMapMany(
                                        through ->
                                                readCommittedPrefix(sessionId, cursor, through)));
    }

    private Flux<SessionEventDto> readCommittedPrefix(
            String sessionId, AtomicLong cursor, long through) {
        return Flux.defer(
                        () ->
                                Mono.fromCallable(
                                                () ->
                                                        transactionTemplate.execute(
                                                                status ->
                                                                        page(
                                                                                sessionId,
                                                                                cursor.get(),
                                                                                through,
                                                                                256)))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .flatMapMany(
                                                batch -> {
                                                    if (batch == null || batch.isEmpty()) {
                                                        cursor.set(through);
                                                        return Flux.empty();
                                                    }
                                                    cursor.set(batch.get(batch.size() - 1).seq());
                                                    return Flux.fromIterable(batch);
                                                }))
                .repeat(() -> cursor.get() < through);
    }

    /** Repository read used by transactional entry points and resumable subscriptions. */
    private List<SessionEventDto> listAfterUnchecked(String sessionId, long afterSeq) {
        return repository
                .findBySessionIdAndSeqGreaterThanOrderBySeqAsc(sessionId, afterSeq)
                .stream()
                .map(this::toDto)
                .toList();
    }

    /** Deletes all persisted events for a session that keeps running (transcript clear). */
    public void deleteBySessionId(String sessionId) {
        writeTransactionTemplate.executeWithoutResult(
                status -> repository.deleteBySessionId(sessionId));
    }

    /**
     * Deletes a session's events and refuses any later append for it. Use this instead of {@link
     * #deleteBySessionId} once the session row itself is gone, so an unwinding turn cannot leave
     * orphan rows behind.
     */
    public void purgeDeletedSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        deletedSessions.markDeleted(sessionId);
        deleteBySessionId(sessionId);
    }

    /**
     * Stands in for an event that was not written because the session is gone. Callers echo the
     * result to HTTP/SSE, so a {@code seq} of {@code -1} marks it as never persisted.
     */
    private SessionEventDto droppedEvent(
            String sessionId, String type, Map<String, Object> payload, String eventId) {
        long now = System.currentTimeMillis();
        String id =
                eventId != null && !eventId.isBlank()
                        ? eventId
                        : "evt_" + UUID.randomUUID().toString().replace("-", "");
        return new SessionEventDto(id, sessionId, -1L, type, payload, now, now);
    }

    private SessionEventDto toDto(SessionEventEntity entity) {
        return new SessionEventDto(
                entity.getEventId(),
                entity.getSessionId(),
                entity.getSeq(),
                entity.getEventType(),
                jsonHelper.readMap(entity.getPayloadJson()),
                entity.getProcessedAt(),
                entity.getCreatedAt());
    }
}
