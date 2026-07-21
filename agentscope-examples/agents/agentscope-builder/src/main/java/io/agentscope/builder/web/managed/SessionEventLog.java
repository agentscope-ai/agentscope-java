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

import io.agentscope.builder.web.persistence.jpa.SessionEventEntity;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntityRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** Append-only session event log with live SSE fan-out. */
@Service
@Transactional
public class SessionEventLog {

    private final SessionEventEntityRepository repository;
    private final ManagedJsonHelper jsonHelper;
    private final ConcurrentHashMap<String, Sinks.Many<SessionEventDto>> liveSinks =
            new ConcurrentHashMap<>();

    public SessionEventLog(SessionEventEntityRepository repository, ManagedJsonHelper jsonHelper) {
        this.repository = repository;
        this.jsonHelper = jsonHelper;
    }

    /** Appends an event with the next monotonic sequence number and marks it processed. */
    public SessionEventDto append(String sessionId, String type, Map<String, Object> payload) {
        long now = System.currentTimeMillis();
        long seq = repository.maxSeq(sessionId) + 1;
        SessionEventEntity entity = new SessionEventEntity();
        entity.setEventId("evt_" + UUID.randomUUID().toString().replace("-", ""));
        entity.setSessionId(sessionId);
        entity.setSeq(seq);
        entity.setEventType(type);
        entity.setPayloadJson(jsonHelper.writeJson(payload));
        entity.setProcessedAt(now);
        entity.setCreatedAt(now);
        SessionEventDto dto = toDto(repository.save(entity));
        emitLive(sessionId, dto);
        return dto;
    }

    /** Lists all events for a session in sequence order. */
    @Transactional(readOnly = true)
    public List<SessionEventDto> list(String sessionId) {
        return repository.findBySessionIdOrderBySeqAsc(sessionId).stream()
                .map(this::toDto)
                .toList();
    }

    /** Lists events with sequence strictly greater than {@code afterSeq}. */
    @Transactional(readOnly = true)
    public List<SessionEventDto> listAfter(String sessionId, long afterSeq) {
        return repository
                .findBySessionIdAndSeqGreaterThanOrderBySeqAsc(sessionId, afterSeq)
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

    /** Returns a live flux of events for SSE streaming. */
    public Flux<SessionEventDto> subscribe(String sessionId) {
        Sinks.Many<SessionEventDto> sink =
                liveSinks.computeIfAbsent(
                        sessionId,
                        ignored -> Sinks.many().multicast().onBackpressureBuffer(256, false));
        return sink.asFlux();
    }

    /** Deletes all persisted events for a session. */
    public void deleteBySessionId(String sessionId) {
        repository.deleteBySessionId(sessionId);
        liveSinks.remove(sessionId);
    }

    private void emitLive(String sessionId, SessionEventDto dto) {
        Sinks.Many<SessionEventDto> sink = liveSinks.get(sessionId);
        if (sink != null) {
            sink.tryEmitNext(dto);
        }
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
