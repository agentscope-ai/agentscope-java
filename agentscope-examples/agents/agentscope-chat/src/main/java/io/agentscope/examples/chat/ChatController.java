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
package io.agentscope.examples.chat;

import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Thin application HTTP routes over the Durable public contract. */
@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class ChatController {
    public record InputRequest(String requestId, String message) {}

    public record ControlRequest(String requestId, Long expectedRevision) {}

    public record AnswerRequest(String requestId, String interactionId, String output) {}

    private final ChatSessions sessions;

    public ChatController(ChatSessions sessions) {
        this.sessions = sessions;
    }

    private Mono<String> json(Supplier<?> read) {
        return Mono.fromCallable(() -> DurableJson.value(read.get()).toString())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/sessions")
    public Mono<String> list() {
        return json(() -> Map.of("sessions", sessions.list(), "model", sessions.modelName()));
    }

    @PostMapping("/sessions/{id}")
    public Mono<String> create(@PathVariable String id) {
        return json(() -> sessions.create(id));
    }

    @GetMapping("/sessions/{id}")
    public Mono<String> snapshot(@PathVariable String id) {
        return json(() -> sessions.snapshot(id));
    }

    @PostMapping("/sessions/{id}/turns")
    public Mono<String> submit(@PathVariable String id, @RequestBody InputRequest body) {
        return json(() -> sessions.submit(id, body.requestId(), body.message()));
    }

    @PostMapping("/sessions/{id}/turns/{turn}/steer")
    public Mono<String> steer(
            @PathVariable String id, @PathVariable String turn, @RequestBody InputRequest body) {
        return json(() -> sessions.steer(id, turn, body.requestId(), body.message()));
    }

    @PostMapping("/sessions/{id}/turns/{turn}/inject")
    public Mono<String> inject(
            @PathVariable String id, @PathVariable String turn, @RequestBody InputRequest body) {
        return json(() -> sessions.inject(id, turn, body.requestId(), body.message()));
    }

    @PostMapping("/sessions/{id}/turns/{turn}/pause")
    public Mono<String> pause(
            @PathVariable String id, @PathVariable String turn, @RequestBody ControlRequest body) {
        return json(() -> sessions.pause(id, turn, body.requestId()));
    }

    @PostMapping("/sessions/{id}/turns/{turn}/resume")
    public Mono<String> resume(
            @PathVariable String id, @PathVariable String turn, @RequestBody ControlRequest body) {
        return json(() -> sessions.resume(id, turn, body.requestId(), body.expectedRevision()));
    }

    @PostMapping("/sessions/{id}/turns/{turn}/cancel")
    public Mono<String> cancel(
            @PathVariable String id, @PathVariable String turn, @RequestBody ControlRequest body) {
        return json(() -> sessions.cancel(id, turn, body.requestId()));
    }

    @PostMapping("/sessions/{id}/turns/{turn}/answers")
    public Mono<String> answer(
            @PathVariable String id, @PathVariable String turn, @RequestBody AnswerRequest body) {
        return json(
                () ->
                        sessions.answer(
                                id, turn, body.requestId(), body.interactionId(), body.output()));
    }

    @GetMapping("/sessions/{id}/turns/{turn}/runs")
    public Mono<String> runs(
            @PathVariable String id,
            @PathVariable String turn,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit) {
        return json(() -> sessions.runs(id, turn, after, limit));
    }

    @GetMapping("/sessions/{id}/events")
    public Mono<String> events(
            @PathVariable String id,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit) {
        return json(() -> sessions.events(id, after, limit));
    }

    @GetMapping(value = "/sessions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String id,
            @RequestParam(required = false) String after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return Mono.fromCallable(
                        () -> {
                            // EventSource's reconnect position supersedes its original URL
                            // parameter.
                            // Both are opaque tokens; never compare or manufacture cursor strings.
                            String start =
                                    lastEventId == null || lastEventId.isBlank()
                                            ? after
                                            : lastEventId;
                            sessions.events(id, start, 1); // Validate before response headers.
                            return new AtomicReference<>(start);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(
                        cursor -> {
                            Flux<ServerSentEvent<String>> committed =
                                    Flux.interval(Duration.ZERO, Duration.ofMillis(300))
                                            .onBackpressureDrop()
                                            .concatMap(
                                                    tick ->
                                                            Mono.fromCallable(
                                                                            () ->
                                                                                    sessions.events(
                                                                                            id,
                                                                                            cursor
                                                                                                    .get(),
                                                                                            128))
                                                                    .subscribeOn(
                                                                            Schedulers
                                                                                    .boundedElastic()),
                                                    1)
                                            .concatMapIterable(
                                                    page -> {
                                                        cursor.set(page.cursor());
                                                        return page.events();
                                                    })
                                            .map(
                                                    event ->
                                                            ServerSentEvent.<String>builder(
                                                                            DurableJson.value(event)
                                                                                    .toString())
                                                                    .event("committed")
                                                                    .id(event.cursor())
                                                                    .build());
                            Flux<ServerSentEvent<String>> heartbeat =
                                    Flux.interval(Duration.ofSeconds(15))
                                            .map(
                                                    tick ->
                                                            ServerSentEvent.<String>builder()
                                                                    .comment("keepalive")
                                                                    .build());
                            return Flux.merge(committed, heartbeat)
                                    .startWith(
                                            ServerSentEvent.<String>builder()
                                                    .comment("connected")
                                                    .build());
                        })
                .onErrorResume(
                        DurableException.class,
                        error -> {
                            if (error.code() != DurableException.Code.CURSOR_EXPIRED)
                                return Flux.error(error);
                            return Mono.fromCallable(
                                            () -> {
                                                var view = sessions.snapshot(id);
                                                return ServerSentEvent.<String>builder(
                                                                DurableJson.value(view).toString())
                                                        .event("reset")
                                                        .id(view.cursor())
                                                        .build();
                                            })
                                    .subscribeOn(Schedulers.boundedElastic());
                        });
    }

    @ExceptionHandler(DurableException.class)
    public ResponseEntity<Map<String, String>> durable(DurableException error) {
        HttpStatus status =
                switch (error.code()) {
                    case NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case INVALID -> HttpStatus.BAD_REQUEST;
                    case CURSOR_EXPIRED -> HttpStatus.GONE;
                    case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                    default -> HttpStatus.CONFLICT;
                };
        return ResponseEntity.status(status)
                .body(Map.of("code", error.code().name(), "error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException error) {
        return Map.of("error", error.getMessage());
    }
}
