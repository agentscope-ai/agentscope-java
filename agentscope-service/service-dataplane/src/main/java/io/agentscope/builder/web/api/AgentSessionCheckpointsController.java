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
package io.agentscope.builder.web.api;

import io.agentscope.builder.web.managed.AgentSessionView;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.SessionCheckpointService;
import io.agentscope.builder.web.managed.SessionNativeLogService;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/agent-sessions/{session}")
public final class AgentSessionCheckpointsController {
    private final SessionCheckpointService checkpoints;
    private final DataSessionService sessions;
    private final SessionNativeLogService logs;
    private final SessionEventLog events;

    public AgentSessionCheckpointsController(
            SessionCheckpointService checkpoints,
            DataSessionService sessions,
            SessionNativeLogService logs,
            SessionEventLog events) {
        this.checkpoints = checkpoints;
        this.sessions = sessions;
        this.logs = logs;
        this.events = events;
    }

    @GetMapping("/checkpoints")
    public Mono<Map<String, Object>> list(
            @PathVariable String session,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit,
            Authentication auth) {
        return Mono.fromCallable(
                        () -> checkpoints.list((String) auth.getPrincipal(), session, after, limit))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public record Restore(String checkpoint_id, String reason) {}

    public record Fork(String target_session_id, String checkpoint_id, String reason) {}

    @PostMapping("/checkpoints/restore")
    public Mono<Map<String, Object>> restore(
            @PathVariable String session,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody Restore input,
            Authentication auth) {
        return Mono.fromCallable(
                        () ->
                                checkpoints.restore(
                                        (String) auth.getPrincipal(),
                                        session,
                                        session,
                                        input.checkpoint_id(),
                                        key,
                                        input.reason()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/fork")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> fork(
            @PathVariable String session,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody Fork input,
            Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            if (input.target_session_id() == null
                                    || input.target_session_id().equals(session))
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Create an empty target session before forking");
                            return checkpoints.restore(
                                    (String) auth.getPrincipal(),
                                    session,
                                    input.target_session_id(),
                                    input.checkpoint_id(),
                                    key,
                                    input.reason());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(value = "/export", produces = "application/x-ndjson")
    public Flux<String> export(
            @PathVariable String session, Authentication auth, ServerHttpResponse response) {
        return Mono.fromCallable(
                        () -> {
                            sessions.get((String) auth.getPrincipal(), session);
                            logs.refresh(session);
                            response.getHeaders()
                                    .set(
                                            HttpHeaders.CONTENT_DISPOSITION,
                                            "attachment; filename=\"session-events.jsonl\"");
                            return events.highWatermark(session);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(
                        through -> {
                            var cursor = new AtomicLong();
                            return Flux.<List<Map<String, Object>>>generate(
                                            sink -> {
                                                if (cursor.get() >= through) {
                                                    sink.complete();
                                                    return;
                                                }
                                                var batch =
                                                        events.page(
                                                                session,
                                                                cursor.get(),
                                                                through,
                                                                256);
                                                if (batch.isEmpty()) {
                                                    sink.complete();
                                                    return;
                                                }
                                                cursor.set(batch.get(batch.size() - 1).seq());
                                                sink.next(
                                                        batch.stream()
                                                                .filter(AgentSessionView::isPublic)
                                                                .map(AgentSessionView::envelope)
                                                                .toList());
                                            })
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .concatMapIterable(batch -> batch)
                                    .map(event -> JsonUtils.getJsonCodec().toJson(event) + "\n");
                        });
    }
}
