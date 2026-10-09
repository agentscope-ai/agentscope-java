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
import io.agentscope.builder.web.managed.AgentSessionViewStore;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.SessionEventCursor;
import io.agentscope.builder.web.managed.SessionNativeLogService;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/agent-sessions/{session}/subagents/{child}")
public final class AgentSessionChildrenController {
    private final DataSessionService sessions;
    private final SessionNativeLogService logs;
    private final SessionEventLog events;
    private final AgentSessionViewStore views;

    public AgentSessionChildrenController(
            DataSessionService sessions,
            SessionNativeLogService logs,
            SessionEventLog events,
            AgentSessionViewStore views) {
        this.sessions = sessions;
        this.logs = logs;
        this.events = events;
        this.views = views;
    }

    private void authorize(String session, String child, Authentication auth) {
        logs.refreshDescendant(sessions.get((String) auth.getPrincipal(), session), child);
    }

    @GetMapping({"", "/snapshot"})
    public Mono<Map<String, Object>> snapshot(
            @PathVariable String session, @PathVariable String child, Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            authorize(session, child, auth);
                            Map<String, Object> result = new LinkedHashMap<>(views.read(child));
                            result.put(
                                    "session", Map.of("id", child, "parent_session_id", session));
                            return result;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{resource:items|tools|turns|runs|required-actions|usage|subagents}")
    public Mono<Map<String, Object>> resource(
            @PathVariable String session,
            @PathVariable String child,
            @PathVariable String resource,
            Authentication auth) {
        return snapshot(session, child, auth)
                .map(
                        view ->
                                Map.of(
                                        "data",
                                        view.get(resource.replace('-', '_')),
                                        "as_of",
                                        view.get("as_of")));
    }

    @GetMapping("/events")
    public Mono<Map<String, Object>> events(
            @PathVariable String session,
            @PathVariable String child,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit,
            Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            authorize(session, child, auth);
                            if (limit < 1 || limit > 1000)
                                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                            long next = SessionEventCursor.decode(child, after),
                                    through = events.highWatermark(child);
                            if (next > through)
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT, "Cursor exceeds history");
                            var result = new ArrayList<Map<String, Object>>();
                            while (next < through && result.size() < limit) {
                                var batch =
                                        events.page(
                                                child,
                                                next,
                                                through,
                                                Math.min(256, limit - result.size()));
                                if (batch.isEmpty()) {
                                    next = through;
                                    break;
                                }
                                for (var event : batch) {
                                    next = event.seq();
                                    if (AgentSessionView.isPublic(event))
                                        result.add(AgentSessionView.envelope(event));
                                }
                            }
                            return Map.<String, Object>of(
                                    "data",
                                    result,
                                    "next_cursor",
                                    SessionEventCursor.encode(child, next),
                                    "has_more",
                                    next < through);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String session,
            @PathVariable String child,
            @RequestParam(required = false) String after,
            @RequestHeader(value = "Last-Event-ID", required = false) String last,
            Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            authorize(session, child, auth);
                            long seq =
                                    Math.max(
                                            SessionEventCursor.decode(child, after),
                                            SessionEventCursor.decode(child, last));
                            if (seq > events.highWatermark(child))
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT, "Cursor exceeds history");
                            return seq;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(
                        seq ->
                                Flux.merge(
                                        events.subscribe(child, seq)
                                                .filter(AgentSessionView::isPublic)
                                                .map(
                                                        event ->
                                                                ServerSentEvent.builder(
                                                                                JsonUtils
                                                                                        .getJsonCodec()
                                                                                        .toJson(
                                                                                                AgentSessionView
                                                                                                        .envelope(
                                                                                                                event)))
                                                                        .id(
                                                                                SessionEventCursor
                                                                                        .encode(
                                                                                                child,
                                                                                                event
                                                                                                        .seq()))
                                                                        .event(event.type())
                                                                        .build()),
                                        Flux.interval(Duration.ofSeconds(15))
                                                .map(
                                                        tick ->
                                                                ServerSentEvent.<String>builder()
                                                                        .comment("keepalive")
                                                                        .build())));
    }
}
