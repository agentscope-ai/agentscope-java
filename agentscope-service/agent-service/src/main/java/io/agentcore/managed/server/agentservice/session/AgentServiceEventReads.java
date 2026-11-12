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
package io.agentcore.managed.server.agentservice.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceEventEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceEventRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceTurnRepository;
import io.agentcore.managed.server.web.managed.SessionEventCursor;
import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Durable cursor reads; notifications are hints, and each connection retains at most one page. */
@Service
public class AgentServiceEventReads {
    private final AgentServiceEventRepository events;
    private final AgentServiceTurnRepository turns;
    private final AgentServiceSessionAccess access;
    private final SessionEventNotifier notifier;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final long recoveryMs;
    private final long authorizationMs;
    private final Semaphore connections;
    private final Semaphore exports;
    private final long maxReplayEvents;
    private final Duration slowConsumerTimeout;

    public AgentServiceEventReads(
            AgentServiceEventRepository events,
            AgentServiceTurnRepository turns,
            AgentServiceSessionAccess access,
            SessionEventNotifier notifier,
            ObjectMapper mapper,
            TransactionTemplate tx,
            @Value("${managed-agents.agent-service.sse-recovery-ms:15000}") long recoveryMs,
            @Value("${managed-agents.agent-service.sse-authorization-ms:15000}")
                    long authorizationMs,
            @Value("${managed-agents.agent-service.max-sse-connections:10000}") int maxConnections,
            @Value("${managed-agents.agent-service.max-sse-replay-events:50000}")
                    long maxReplayEvents,
            @Value("${managed-agents.agent-service.sse-slow-consumer-seconds:30}")
                    long slowConsumerSeconds,
            @Value("${managed-agents.agent-service.event-export-slots:16}") int exportSlots) {
        if (recoveryMs < 100
                || authorizationMs < 100
                || maxConnections < 1
                || maxReplayEvents < 1
                || slowConsumerSeconds < 1
                || exportSlots < 1
                || exportSlots > 256)
            throw new IllegalArgumentException("Agent Service SSE limits must be positive");
        this.events = events;
        this.turns = turns;
        this.access = access;
        this.notifier = notifier;
        this.mapper = mapper;
        this.tx = tx;
        this.recoveryMs = recoveryMs;
        this.authorizationMs = authorizationMs;
        this.connections = new Semaphore(maxConnections);
        this.exports = new Semaphore(exportSlots);
        this.maxReplayEvents = maxReplayEvents;
        this.slowConsumerTimeout = Duration.ofSeconds(slowConsumerSeconds);
    }

    public Map<String, Object> get(String session, String id, HttpHeaders headers) {
        return get(session, null, id, headers);
    }

    public Map<String, Object> get(String session, String turn, String id, HttpHeaders headers) {
        access.authorize(session, headers, "read");
        requireTurn(session, turn);
        return envelope(
                events.findBySessionIdAndId(session, id)
                        .filter(event -> turn == null || turn.equals(event.turnId))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    public Map<String, Object> page(String session, String after, int limit, HttpHeaders headers) {
        return page(session, null, after, limit, headers);
    }

    public Map<String, Object> page(
            String session, String turn, String after, int limit, HttpHeaders headers) {
        if (limit < 1 || limit > 1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1..1000");
        access.authorize(session, headers, "read");
        requireTurn(session, turn);
        long cursor = cursor(session, after);
        return tx.execute(
                ignored -> {
                    long through = events.highWatermark(session);
                    validatePosition(cursor, through);
                    var rows = page(session, turn, cursor, through, limit + 1);
                    boolean more = rows.size() > limit;
                    var batch = rows.subList(0, Math.min(rows.size(), limit));
                    long next = more ? batch.get(batch.size() - 1).seq : through;
                    return Map.of(
                            "data",
                            batch.stream().map(this::envelope).toList(),
                            "next_cursor",
                            SessionEventCursor.encode(session, next),
                            "has_more",
                            more);
                });
    }

    public Flux<ServerSentEvent<String>> stream(
            String session, String after, String lastEventId, HttpHeaders headers) {
        return stream(session, null, after, lastEventId, headers);
    }

    /** A finite public NDJSON window; callers resume with the same through cursor. */
    public Mono<Void> export(
            String session,
            String turn,
            String after,
            String through,
            int limit,
            HttpHeaders headers,
            ServerHttpResponse response) {
        return Mono.defer(
                () -> {
                    if (!exports.tryAcquire())
                        return Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.TOO_MANY_REQUESTS,
                                        "Event export capacity reached"));
                    return Mono.fromCallable(
                                    () -> {
                                        if (limit < 1 || limit > 1000)
                                            throw new ResponseStatusException(
                                                    HttpStatus.BAD_REQUEST,
                                                    "limit must be 1..1000");
                                        access.authorize(session, headers, "read");
                                        requireTurn(session, turn);
                                        long start = cursor(session, after);
                                        return tx.execute(
                                                ignored -> {
                                                    long latest = events.highWatermark(session);
                                                    long end =
                                                            through == null || through.isBlank()
                                                                    ? latest
                                                                    : cursor(session, through);
                                                    validatePosition(end, latest);
                                                    validatePosition(start, end);
                                                    var positions =
                                                            turn == null
                                                                    ? events.exportSequences(
                                                                            session,
                                                                            start,
                                                                            end,
                                                                            PageRequest.of(
                                                                                    0, limit + 1))
                                                                    : events.exportTurnSequences(
                                                                            session,
                                                                            turn,
                                                                            start,
                                                                            end,
                                                                            PageRequest.of(
                                                                                    0, limit + 1));
                                                    boolean more = positions.size() > limit;
                                                    long next =
                                                            more ? positions.get(limit - 1) : end;
                                                    return new ExportWindow(
                                                            start,
                                                            end,
                                                            next,
                                                            more,
                                                            Math.min(limit, positions.size()));
                                                });
                                    })
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(
                                    window -> {
                                        response.getHeaders()
                                                .setContentType(MediaType.APPLICATION_NDJSON);
                                        response.getHeaders().setCacheControl("no-store");
                                        response.getHeaders()
                                                .set(
                                                        "X-Export-Through",
                                                        SessionEventCursor.encode(
                                                                session, window.through()));
                                        response.getHeaders()
                                                .set(
                                                        "X-Next-Cursor",
                                                        SessionEventCursor.encode(
                                                                session, window.next()));
                                        response.getHeaders()
                                                .set("X-Has-More", Boolean.toString(window.more()));
                                        response.getHeaders()
                                                .set(
                                                        "X-Export-Count",
                                                        Integer.toString(window.count()));
                                        return response.writeWith(
                                                prefix(
                                                                session,
                                                                turn,
                                                                new AtomicLong(window.start()),
                                                                window.next(),
                                                                headers,
                                                                false)
                                                        .limitRate(1)
                                                        .map(
                                                                event ->
                                                                        response.bufferFactory()
                                                                                .wrap(
                                                                                        (json(
                                                                                                                envelope(
                                                                                                                        event))
                                                                                                        + "\n")
                                                                                                .getBytes(
                                                                                                        StandardCharsets
                                                                                                                .UTF_8))));
                                    })
                            .timeout(Duration.ofSeconds(60))
                            .doFinally(ignored -> exports.release());
                });
    }

    private record ExportWindow(long start, long through, long next, boolean more, int count) {}

    public Flux<ServerSentEvent<String>> stream(
            String session, String turn, String after, String lastEventId, HttpHeaders headers) {
        return Flux.defer(
                () -> {
                    long position = Math.max(cursor(session, after), cursor(session, lastEventId));
                    return Mono.fromCallable(
                                    () -> {
                                        access.authorize(session, headers, "read");
                                        requireTurn(session, turn);
                                        validatePosition(position, events.highWatermark(session));
                                        return position;
                                    })
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMapMany(start -> live(session, turn, start, headers));
                });
    }

    private Flux<ServerSentEvent<String>> live(
            String session, String turn, long start, HttpHeaders headers) {
        if (!connections.tryAcquire())
            return Flux.error(
                    new ResponseStatusException(
                            HttpStatus.TOO_MANY_REQUESTS,
                            "Agent Service SSE connection capacity reached"));
        var current = new AtomicLong(start);
        var nextAuthorization = new AtomicLong(System.currentTimeMillis() + authorizationMs);
        Flux<Long> ticks =
                Flux.interval(Duration.ofMillis(Math.min(recoveryMs, authorizationMs)))
                        .onBackpressureDrop();
        Flux<Long> wakeups =
                Flux.merge(Flux.just(0L), ticks, notifier.wakeups(session).map(ignored -> 0L))
                        .onBackpressureLatest();
        Flux<ServerSentEvent<String>> durable =
                wakeups.concatMap(
                                ignored ->
                                        watermark(session, headers, nextAuthorization)
                                                .flatMapMany(
                                                        through ->
                                                                prefix(
                                                                        session, turn, current,
                                                                        through)),
                                1)
                        .map(this::frame);
        Flux<ServerSentEvent<String>> heartbeat =
                Flux.interval(Duration.ofSeconds(15))
                        .onBackpressureDrop()
                        .map(
                                ignored ->
                                        ServerSentEvent.<String>builder()
                                                .comment("keepalive")
                                                .build());
        return Flux.merge(1, durable, heartbeat)
                .limitRate(32)
                .doFinally(ignored -> connections.release());
    }

    private Mono<Long> watermark(
            String session, HttpHeaders headers, AtomicLong nextAuthorization) {
        return Mono.fromCallable(
                        () -> {
                            long now = System.currentTimeMillis();
                            if (now >= nextAuthorization.get()) {
                                access.authorize(session, headers, "read");
                                nextAuthorization.set(now + authorizationMs);
                            }
                            return events.highWatermark(session);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ServerSentEvent<String> frame(AgentServiceEventEntity event) {
        return ServerSentEvent.<String>builder(json(envelope(event)))
                .id(SessionEventCursor.encode(event.sessionId, event.seq))
                .event(event.type)
                .build();
    }

    private Flux<AgentServiceEventEntity> prefix(
            String session, String turn, AtomicLong cursor, long through) {
        return prefix(session, turn, cursor, through, null, true);
    }

    private Flux<AgentServiceEventEntity> prefix(
            String session,
            String turn,
            AtomicLong cursor,
            long through,
            HttpHeaders headers,
            boolean checkReplay) {
        var replayChecked = new AtomicBoolean();
        return Flux.defer(
                        () ->
                                Mono.fromCallable(
                                                () ->
                                                        tx.execute(
                                                                ignored -> {
                                                                    if (headers != null)
                                                                        access.authorize(
                                                                                session, headers,
                                                                                "read");
                                                                    if (checkReplay
                                                                            && !replayChecked
                                                                                    .getAndSet(
                                                                                            true)) {
                                                                        long replay =
                                                                                turn == null
                                                                                        ? through
                                                                                                - cursor
                                                                                                        .get()
                                                                                        : events
                                                                                                .countBySessionIdAndTurnIdAndSeqGreaterThanAndSeqLessThanEqual(
                                                                                                        session,
                                                                                                        turn,
                                                                                                        cursor
                                                                                                                .get(),
                                                                                                        through);
                                                                        if (replay
                                                                                > maxReplayEvents)
                                                                            throw new ResponseStatusException(
                                                                                    HttpStatus
                                                                                            .CONFLICT,
                                                                                    "SSE replay"
                                                                                        + " limit"
                                                                                        + " exceeded;"
                                                                                        + " refresh"
                                                                                        + " snapshot");
                                                                    }
                                                                    return page(
                                                                            session,
                                                                            turn,
                                                                            cursor.get(),
                                                                            through,
                                                                            headers == null
                                                                                    ? 256
                                                                                    : 16);
                                                                }))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .flatMapMany(
                                                batch -> {
                                                    if (batch == null || batch.isEmpty()) {
                                                        if (turn != null) {
                                                            cursor.set(through);
                                                            return Flux.empty();
                                                        }
                                                        if (cursor.get() < through)
                                                            return Flux.error(
                                                                    new ResponseStatusException(
                                                                            HttpStatus.CONFLICT,
                                                                            "Cursor history is"
                                                                                + " unavailable;"
                                                                                + " refresh"
                                                                                + " snapshot"));
                                                        return Flux.empty();
                                                    }
                                                    cursor.set(batch.get(batch.size() - 1).seq);
                                                    return Flux.fromIterable(batch);
                                                }))
                .repeat(() -> cursor.get() < through)
                .timeout(slowConsumerTimeout);
    }

    private List<AgentServiceEventEntity> page(
            String session, String turn, long after, long through, int limit) {
        if (turn != null)
            return events.findBySessionIdAndTurnIdAndSeqGreaterThanAndSeqLessThanEqualOrderBySeqAsc(
                    session, turn, after, through, PageRequest.of(0, limit));
        return events.findBySessionIdAndSeqGreaterThanAndSeqLessThanEqualOrderBySeqAsc(
                session, after, through, PageRequest.of(0, limit));
    }

    private void requireTurn(String session, String turn) {
        if (turn != null && !turns.existsByIdAndSessionId(turn, session))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private Map<String, Object> envelope(AgentServiceEventEntity row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("schema_version", 1);
        result.put("id", row.id);
        result.put("type", row.type);
        result.put("session_id", row.sessionId);
        result.put("created_at", Instant.ofEpochMilli(row.createdAt).toString());
        result.put("cursor", SessionEventCursor.encode(row.sessionId, row.seq));
        try {
            result.put(
                    "data",
                    mapper.readValue(row.data, new TypeReference<Map<String, Object>>() {}));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot decode public event", error);
        }
        return result;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode public event", error);
        }
    }

    private static long cursor(String session, String value) {
        if (value != null && value.length() > 1024)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cursor exceeds size limit");
        try {
            return SessionEventCursor.decode(session, value);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
    }

    private static void validatePosition(long cursor, long through) {
        if (cursor > through)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Cursor is ahead of session history; refresh snapshot");
    }
}
