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

import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class ChatController {
    private final ChatSessions sessions;

    public ChatController(ChatSessions sessions) {
        this.sessions = sessions;
    }

    private Mono<String> json(Supplier<?> read) {
        return Mono.fromCallable(() -> JsonUtils.getJsonCodec().toJson(read.get()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/sessions")
    public Mono<String> list() {
        return json(() -> Map.of("sessions", sessions.list(), "model", sessions.modelName()));
    }

    @GetMapping("/sessions/{id}")
    public Mono<String> snapshot(@PathVariable String id) {
        return json(() -> sessions.snapshot(id));
    }

    @PostMapping("/sessions/{id}/turns")
    public Mono<String> submit(@PathVariable String id, @RequestBody Map<String, String> body) {
        return json(() -> sessions.submit(id, body.get("request_id"), body.get("message")));
    }

    @PostMapping("/sessions/{id}/steer")
    public Mono<String> steer(@PathVariable String id, @RequestBody Map<String, String> body) {
        return json(() -> sessions.steer(id, body.get("message")));
    }

    @PostMapping("/sessions/{id}/inject")
    public Mono<String> inject(@PathVariable String id, @RequestBody Map<String, String> body) {
        return json(() -> sessions.inject(id, body.get("message")));
    }

    @PostMapping("/sessions/{id}/turns/{turn}/resume")
    public Mono<String> resume(@PathVariable String id, @PathVariable String turn) {
        return json(() -> sessions.resume(id, turn));
    }

    @PostMapping("/sessions/{id}/answers")
    public Mono<String> answer(@PathVariable String id, @RequestBody Map<String, String> body) {
        return json(() -> sessions.answer(id, body.get("request_id"), body.get("output")));
    }

    @PostMapping("/sessions/{id}/interrupt")
    public Mono<String> interrupt(@PathVariable String id, @RequestBody Map<String, String> body) {
        return json(() -> Map.of("requested", sessions.interrupt(id, body.get("run_id"))));
    }

    @GetMapping("/sessions/{id}/events")
    public Mono<String> events(
            @PathVariable String id,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit) {
        return json(() -> sessions.events(id, ChatHistory.position(id, after), limit));
    }

    @GetMapping(value = "/sessions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String id,
            @RequestParam(required = false) String after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return Mono.fromCallable(
                        () -> {
                            long position =
                                    Math.max(
                                            ChatHistory.position(id, after),
                                            ChatHistory.position(id, lastEventId));
                            sessions.events(
                                    id, position, 1); // Validate before sending response headers.
                            return new AtomicLong(position);
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
                                            .flatMapIterable(batch -> batch)
                                            .map(
                                                    event -> {
                                                        cursor.set(event.seq());
                                                        return ServerSentEvent.<String>builder(
                                                                        JsonUtils.getJsonCodec()
                                                                                .toJson(
                                                                                        ChatHistory
                                                                                                .frame(
                                                                                                        event)))
                                                                .event("committed")
                                                                .id(
                                                                        ChatHistory.cursor(
                                                                                id, event.seq()))
                                                                .build();
                                                    });
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
                        });
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException error) {
        return Map.of("error", error.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(IllegalStateException error) {
        return Map.of("error", error.getMessage());
    }
}
