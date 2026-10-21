/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.portal.api;

import io.agentscope.builder.portal.session.ManagedSessionTurnsApi;
import io.agentscope.builder.portal.session.SessionApi;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/v1/sessions")
public class SessionApiController {
    private final SessionApi sessions;
    private final ManagedSessionTurnsApi execution;

    public SessionApiController(SessionApi sessions, ManagedSessionTurnsApi execution) {
        this.sessions = sessions;
        this.execution = execution;
    }

    @PostMapping
    public Mono<Map<String, Object>> create(
            @RequestBody Map<String, Object> body, @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> sessions.create(body, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping
    public Mono<Map<String, Object>> list(
            @RequestParam MultiValueMap<String, String> query, @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> sessions.list(query, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    public Mono<Map<String, Object>> get(
            @PathVariable String id, @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> sessions.get(id, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{id}")
    public Mono<Map<String, Object>> update(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> sessions.update(id, body, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{id}/archive")
    public Mono<Map<String, Object>> archive(
            @PathVariable String id, @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> sessions.lifecycle(id, "archived", headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> delete(
            @PathVariable String id, @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> sessions.lifecycle(id, "deleted", headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{id}/restore")
    public Mono<Map<String, Object>> restore(
            @PathVariable String id, @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> sessions.lifecycle(id, "active", headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/lifecycle/{command}")
    public Mono<Map<String, Object>> lifecycle(
            @PathVariable String id,
            @PathVariable String command,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> sessions.lifecycleReceipt(id, command, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{id}/events")
    public Mono<Map<String, Object>> sendEvents(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            @RequestHeader HttpHeaders headers,
            ServerWebExchange exchange) {
        if (body.get("events") instanceof List<?> values
                && values.stream()
                        .anyMatch(
                                value ->
                                        value instanceof Map<?, ?> event
                                                && event.containsKey("agentscope_service")))
            exchange.getAttributes().put("agentscope_service.explicit", true);
        return Mono.fromCallable(() -> execution.sendEvents(id, body, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/turns/{turn}/output")
    public Mono<Map<String, Object>> output(
            @PathVariable String id,
            @PathVariable String turn,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> execution.output(id, turn, after, limit, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/turns/{turn}")
    public Mono<Map<String, Object>> turn(
            @PathVariable String id,
            @PathVariable String turn,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> execution.turn(id, turn, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/turns")
    public Mono<Map<String, Object>> turns(
            @PathVariable String id,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(required = false) String status,
            @RequestParam(name = "input_event_id", required = false) String eventId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(
                        () -> execution.turns(id, after, order, status, limit, headers, eventId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/turns/{turn}/inputs")
    public Mono<Map<String, Object>> inputs(
            @PathVariable String id,
            @PathVariable String turn,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) String status,
            @RequestParam(name = "event_id", required = false) String eventId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(
                        () -> execution.inputs(id, turn, after, limit, headers, status, eventId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/turns/{turn}/inputs/{input}")
    public Mono<Map<String, Object>> input(
            @PathVariable String id,
            @PathVariable String turn,
            @PathVariable String input,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> execution.input(id, turn, input, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/events")
    public Mono<Map<String, Object>> events(
            @PathVariable String id,
            @RequestParam MultiValueMap<String, String> query,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> execution.history(id, query, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/events/stream")
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String id,
            @RequestParam MultiValueMap<String, String> query,
            @RequestHeader HttpHeaders headers,
            ServerHttpResponse response) {
        response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
        if (!query.isEmpty() && !(query.size() == 1 && "true".equals(query.getFirst("beta"))))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "unsupported_capability: SSE query options are not supported");
        String after = headers.getFirst("Last-Event-ID");
        if (after == null) after = query.getFirst("after");
        return execution.stream(id, after, headers);
    }

    @GetMapping("/{id}/snapshot")
    public Mono<Map<String, Object>> snapshot(
            @PathVariable String id,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> execution.snapshot(id, limit, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/capabilities")
    public Mono<Map<String, Object>> capabilities(
            @PathVariable String id, @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> execution.capabilities(id, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/turns/{turn}/snapshot")
    public Mono<Map<String, Object>> snapshot(
            @PathVariable String id,
            @PathVariable String turn,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> execution.snapshot(id, turn, limit, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/usage")
    public Mono<Map<String, Object>> usage(
            @PathVariable String id, @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> execution.usage(id, null, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/turns/{turn}/usage")
    public Mono<Map<String, Object>> usage(
            @PathVariable String id,
            @PathVariable String turn,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> execution.usage(id, turn, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/tool-tasks")
    public Mono<Map<String, Object>> tools(
            @PathVariable String id,
            @RequestParam String turn,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> execution.tools(id, turn, after, limit, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/turns/{turn}/capabilities")
    public Mono<Map<String, Object>> capabilities(
            @PathVariable String id,
            @PathVariable String turn,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> execution.capabilities(id, turn, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/resources/{kind}")
    public Mono<Map<String, Object>> resources(
            @PathVariable String id,
            @PathVariable String kind,
            @RequestParam("snapshot_id") String snapshot,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(
                        () -> execution.resources(id, snapshot, kind, after, limit, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
