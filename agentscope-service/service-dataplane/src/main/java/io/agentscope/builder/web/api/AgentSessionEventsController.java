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
import io.agentscope.builder.web.managed.SessionBudgetService;
import io.agentscope.builder.web.managed.SessionEventCursor;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.managed.SessionEventPreviewBus;
import io.agentscope.builder.web.managed.SessionNativeLogService;
import io.agentscope.builder.web.managed.SessionUsageService;
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

/** Versioned Agent-as-a-Service read API, projected exclusively from durable public facts. */
@RestController
@RequestMapping("/api/v1/agent-sessions")
public final class AgentSessionEventsController {
    private final DataSessionService sessions;
    private final SessionEventLog events;
    private final SessionEventPreviewBus previews;
    private final SessionNativeLogService nativeLogs;
    private final AgentSessionViewStore views;
    private final SessionBudgetService budgets;
    private final SessionUsageService usage;

    public AgentSessionEventsController(
            DataSessionService sessions,
            SessionEventLog events,
            SessionEventPreviewBus previews,
            SessionNativeLogService nativeLogs,
            AgentSessionViewStore views,
            SessionBudgetService budgets,
            SessionUsageService usage) {
        this.sessions = sessions;
        this.events = events;
        this.previews = previews;
        this.nativeLogs = nativeLogs;
        this.views = views;
        this.budgets = budgets;
        this.usage = usage;
    }

    private static boolean publicEvent(SessionEventDto event) {
        return AgentSessionView.isPublic(event);
    }

    private static Map<String, Object> envelope(SessionEventDto event) {
        return AgentSessionView.envelope(event);
    }

    private static long cursor(String id, String value) {
        try {
            return SessionEventCursor.decode(id, value);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
    }

    @GetMapping("/{id}/events/{eventId}")
    public Mono<Map<String, Object>> event(
            @PathVariable String id, @PathVariable String eventId, Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            sessions.get((String) auth.getPrincipal(), id);
                            var event = events.getByEventId(eventId);
                            if (!id.equals(event.sessionId()) || !publicEvent(event))
                                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                            return envelope(event);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/events")
    public Mono<Map<String, Object>> list(
            @PathVariable String id,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit,
            Authentication auth) {
        if (limit < 1 || limit > 1000)
            return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1..1000"));
        long seq = cursor(id, after);
        return Mono.fromCallable(
                        () -> {
                            sessions.get((String) auth.getPrincipal(), id);
                            nativeLogs.refresh(id);
                            if (seq > events.highWatermark(id))
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "Cursor is ahead of retained session history; refresh"
                                                + " snapshot");
                            long through = events.highWatermark(id);
                            var page = new ArrayList<Map<String, Object>>();
                            long next = seq;
                            while (next < through && page.size() < limit) {
                                var batch =
                                        events.page(
                                                id,
                                                next,
                                                through,
                                                Math.min(256, limit - page.size()));
                                if (batch.isEmpty()) {
                                    next = through;
                                    break;
                                }
                                for (var event : batch) {
                                    next = event.seq();
                                    if (publicEvent(event)) page.add(envelope(event));
                                }
                            }
                            return Map.<String, Object>of(
                                    "data",
                                    page,
                                    "next_cursor",
                                    SessionEventCursor.encode(id, next),
                                    "has_more",
                                    next < through);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    static boolean logicalTurnStatus(String type) {
        return switch (type) {
            case "turn.accepted",
                    "turn.queued",
                    "turn.running",
                    "turn.completed",
                    "turn.failed",
                    "turn.cancelled",
                    "turn.interrupted",
                    "turn.requires_action",
                    "turn.cancel_requested" ->
                    true;
            default -> false;
        };
    }

    @GetMapping("/{id}/snapshot")
    public Mono<Map<String, Object>> snapshot(@PathVariable String id, Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            var session = sessions.get((String) auth.getPrincipal(), id);
                            nativeLogs.refresh(id);
                            Map<String, Object> result = new LinkedHashMap<>(views.read(id));
                            result.put("session", session);
                            result.put(
                                    "usage",
                                    budgets.priceUsage((Map<String, Object>) result.get("usage")));
                            return result;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/resources/{resource}")
    public Mono<Map<String, Object>> resourcePage(
            @PathVariable String id,
            @PathVariable String resource,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit,
            Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            sessions.get((String) auth.getPrincipal(), id);
                            nativeLogs.refresh(id);
                            return views.page(id, resource.replace('-', '_'), after, limit);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/tools")
    public Mono<Map<String, Object>> tools(@PathVariable String id, Authentication auth) {
        return resource(id, auth, "tools");
    }

    @GetMapping("/{id}/inputs")
    public Mono<Map<String, Object>> inputs(@PathVariable String id, Authentication auth) {
        return resource(id, auth, "inputs");
    }

    @GetMapping("/{id}/subagents")
    public Mono<Map<String, Object>> subagents(@PathVariable String id, Authentication auth) {
        return resource(id, auth, "subagents");
    }

    private Mono<Map<String, Object>> resource(String id, Authentication auth, String resource) {
        return snapshot(id, auth)
                .map(view -> Map.of("data", view.get(resource), "as_of", view.get("as_of")));
    }

    @GetMapping("/{id}/artifacts")
    public Mono<Map<String, Object>> artifacts(@PathVariable String id, Authentication auth) {
        return snapshot(id, auth)
                .map(view -> Map.of("data", view.get("artifacts"), "as_of", view.get("as_of")));
    }

    @GetMapping("/{id}/usage")
    public Mono<Map<String, Object>> usage(
            @PathVariable String id,
            @RequestParam(defaultValue = "false", name = "include_children")
                    boolean includeChildren,
            Authentication auth) {
        return Mono.fromCallable(
                        () ->
                                usage.read(
                                        sessions.get((String) auth.getPrincipal(), id),
                                        includeChildren))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/items")
    public Mono<Map<String, Object>> items(@PathVariable String id, Authentication auth) {
        return snapshot(id, auth)
                .map(view -> Map.of("data", view.get("items"), "as_of", view.get("as_of")));
    }

    @GetMapping("/{id}/required-actions")
    public Mono<Map<String, Object>> requiredActions(@PathVariable String id, Authentication auth) {
        return snapshot(id, auth)
                .map(
                        view ->
                                Map.of(
                                        "data",
                                        view.get("required_actions"),
                                        "as_of",
                                        view.get("as_of")));
    }

    @GetMapping(value = "/{id}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String id,
            @RequestParam(required = false) String after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(defaultValue = "false") boolean preview,
            Authentication auth) {
        long seq = Math.max(cursor(id, after), cursor(id, lastEventId));
        return Mono.fromCallable(
                        () -> {
                            var session = sessions.get((String) auth.getPrincipal(), id);
                            nativeLogs.refresh(id);
                            if (seq > events.highWatermark(id))
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "Cursor is ahead of retained session history; refresh"
                                                + " snapshot");
                            return session;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(
                        ignored -> {
                            Flux<ServerSentEvent<String>> durable =
                                    events.subscribe(id, seq)
                                            .filter(AgentSessionEventsController::publicEvent)
                                            .map(
                                                    event ->
                                                            ServerSentEvent.<String>builder(
                                                                            JsonUtils.getJsonCodec()
                                                                                    .toJson(
                                                                                            envelope(
                                                                                                    event)))
                                                                    .id(
                                                                            SessionEventCursor
                                                                                    .encode(
                                                                                            id,
                                                                                            event
                                                                                                    .seq()))
                                                                    .event(event.type())
                                                                    .build());
                            Flux<ServerSentEvent<String>> heartbeat =
                                    Flux.interval(Duration.ofSeconds(15))
                                            .map(
                                                    tick ->
                                                            ServerSentEvent.<String>builder()
                                                                    .comment("keepalive")
                                                                    .build());
                            return Flux.merge(durable, heartbeat);
                        });
    }
}
