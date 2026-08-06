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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Append-only session event log. Sequence numbers are allocated with conflict retry so control and
 * data planes can append concurrently against the shared {@code (session_id, seq)} unique
 * constraint. Live SSE fan-out polls the database by cursor so events written by any plane /
 * replica are visible to subscribers.
 */
@Service
public class SessionEventLog {

    private static final Logger log = LoggerFactory.getLogger(SessionEventLog.class);
    private static final int MAX_SEQ_RETRIES = 16;

    private final SessionEventEntityRepository repository;
    private final ManagedJsonHelper jsonHelper;
    private final TransactionTemplate transactionTemplate;
    private final DeletedSessionRegistry deletedSessions;
    private final long pollIntervalMs;

    public SessionEventLog(
            SessionEventEntityRepository repository,
            ManagedJsonHelper jsonHelper,
            TransactionTemplate transactionTemplate,
            DeletedSessionRegistry deletedSessions,
            @Value("${builder.session-event.poll-interval-ms:500}") long pollIntervalMs) {
        this.repository = repository;
        this.jsonHelper = jsonHelper;
        this.transactionTemplate = transactionTemplate;
        this.deletedSessions = deletedSessions;
        this.pollIntervalMs = Math.max(50L, pollIntervalMs);
    }

    /**
     * Appends an event with the next monotonic sequence number. Retries on unique-constraint
     * conflicts caused by concurrent appends from another process. Each attempt runs in its own
     * transaction so a constraint failure does not poison the outer transaction.
     */
    public SessionEventDto append(String sessionId, String type, Map<String, Object> payload) {
        return append(sessionId, type, payload, null);
    }

    /**
     * Appends an event, optionally reusing a pre-allocated {@code eventId} so stream previews can
     * reconcile with the persisted row.
     */
    public SessionEventDto append(
            String sessionId, String type, Map<String, Object> payload, String eventId) {
        if (deletedSessions.isDeleted(sessionId)) {
            log.debug("Dropping {} event for deleted session {}", type, sessionId);
            return droppedEvent(sessionId, type, payload, eventId);
        }
        RuntimeException lastConflict = null;
        for (int attempt = 0; attempt < MAX_SEQ_RETRIES; attempt++) {
            try {
                return transactionTemplate.execute(
                        status -> appendOnce(sessionId, type, payload, eventId));
            } catch (RuntimeException ex) {
                if (!isSeqConflict(ex)) {
                    throw ex;
                }
                lastConflict = ex;
                log.debug(
                        "Session event seq conflict for {} (attempt {}/{}): {}",
                        sessionId,
                        attempt + 1,
                        MAX_SEQ_RETRIES,
                        ex.getMessage());
            }
        }
        throw new IllegalStateException(
                "Failed to append session event after "
                        + MAX_SEQ_RETRIES
                        + " seq retries: "
                        + sessionId,
                lastConflict);
    }

    private SessionEventDto appendOnce(
            String sessionId, String type, Map<String, Object> payload, String eventId) {
        long now = System.currentTimeMillis();
        long seq = repository.maxSeq(sessionId) + 1;
        SessionEventEntity entity = new SessionEventEntity();
        entity.setEventId(
                eventId != null && !eventId.isBlank()
                        ? eventId
                        : "evt_" + UUID.randomUUID().toString().replace("-", ""));
        entity.setSessionId(sessionId);
        entity.setSeq(seq);
        entity.setEventType(type);
        entity.setPayloadJson(jsonHelper.writeJson(payload));
        entity.setProcessedAt(now);
        entity.setCreatedAt(now);
        return toDto(repository.saveAndFlush(entity));
    }

    private static boolean isSeqConflict(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            if (cur instanceof DataIntegrityViolationException) {
                return true;
            }
            String name = cur.getClass().getName();
            if (name.contains("ConstraintViolation") || name.contains("SQLIntegrityConstraint")) {
                return true;
            }
            String message = cur.getMessage();
            if (message != null
                    && (message.contains("ux_builder_session_event_seq")
                            || message.contains("Unique index")
                            || message.contains("unique constraint"))) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
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

    /** Looks up a single event by its public identifier. */
    @Transactional(readOnly = true)
    public SessionEventDto getByEventId(String eventId) {
        return repository
                .findByEventId(eventId)
                .map(this::toDto)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Event not found: " + eventId));
    }

    /** Returns a live flux of events for SSE streaming, starting after sequence 0. */
    public Flux<SessionEventDto> subscribe(String sessionId) {
        return subscribe(sessionId, 0L);
    }

    /**
     * Polls the database for events with sequence strictly greater than {@code afterSeq}. Works
     * across control/data planes and data-plane replicas without process-local sinks.
     *
     * <p>Each poll runs inside {@link TransactionTemplate}: PostgreSQL {@code @Lob} CLOB/OID
     * payload reads require a transaction, and calling {@link #listAfter} via {@code this.} would
     * bypass the Spring {@code @Transactional} proxy.
     */
    public Flux<SessionEventDto> subscribe(String sessionId, long afterSeq) {
        AtomicLong cursor = new AtomicLong(Math.max(0L, afterSeq));
        return Flux.interval(Duration.ofMillis(pollIntervalMs))
                .concatMap(
                        tick ->
                                Mono.fromCallable(
                                                () ->
                                                        transactionTemplate.execute(
                                                                status ->
                                                                        listAfterUnchecked(
                                                                                sessionId,
                                                                                cursor.get())))
                                        .subscribeOn(Schedulers.boundedElastic()))
                .concatMapIterable(
                        list -> {
                            if (list != null && !list.isEmpty()) {
                                cursor.set(list.get(list.size() - 1).seq());
                            }
                            return list != null ? list : List.of();
                        });
    }

    /** Repository read used by transactional entry points and {@link #subscribe} polls. */
    private List<SessionEventDto> listAfterUnchecked(String sessionId, long afterSeq) {
        return repository
                .findBySessionIdAndSeqGreaterThanOrderBySeqAsc(sessionId, afterSeq)
                .stream()
                .map(this::toDto)
                .toList();
    }

    /** Deletes all persisted events for a session that keeps running (transcript clear). */
    @Transactional
    public void deleteBySessionId(String sessionId) {
        repository.deleteBySessionId(sessionId);
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
        transactionTemplate.executeWithoutResult(status -> repository.deleteBySessionId(sessionId));
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
