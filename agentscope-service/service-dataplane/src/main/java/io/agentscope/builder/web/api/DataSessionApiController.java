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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.api.error.ApiException;
import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.managed.SessionEventPreviewBus;
import io.agentscope.builder.web.managed.SessionEventTypes;
import io.agentscope.builder.web.managed.SessionTurnRunner;
import io.agentscope.builder.web.managed.service.HandsMetrics;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.builder.web.toolbus.ToolConfirmationCoordinator;
import io.agentscope.core.message.ToolResultBlock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Data-plane session API: inbound user events (message / interrupt / tool confirmation / tool
 * results), the persisted event log, SSE streaming and hands metrics.
 *
 * <p>Session lifecycle (create / list / get / archive / delete) is owned by the control plane
 * under the same {@code /api/sessions} path prefix; the gateway routes those calls there.
 *
 * <p>Authorization is <b>session ownership</b> via {@link DataSessionService#get}: the control
 * plane resolve payload's {@code ownerId} must match the JWT user. Product agents now live in
 * aistiod's schema, so a dataplane JPA {@code AgentAccessGuard} lookup would 404 even for valid
 * sessions; agent RUN/EDIT was already enforced when the session was created.
 */
@RestController
@RequestMapping("/api/sessions")
public class DataSessionApiController {

    /** Hosts blocking CP resolve off the Netty event loop. */
    private static final Scheduler BLOCKING = Schedulers.boundedElastic();

    private final DataSessionService sessionService;
    private final SessionEventLog eventLog;
    private final SessionEventPreviewBus previewBus;
    private final ToolConfirmationCoordinator confirmationCoordinator;
    private final SessionTurnRunner turnRunner;
    private final ObjectMapper objectMapper;
    private final HandsMetrics handsMetrics;

    public DataSessionApiController(
            DataSessionService sessionService,
            SessionEventLog eventLog,
            SessionEventPreviewBus previewBus,
            ToolConfirmationCoordinator confirmationCoordinator,
            SessionTurnRunner turnRunner,
            ObjectMapper objectMapper,
            HandsMetrics handsMetrics) {
        this.sessionService = sessionService;
        this.eventLog = eventLog;
        this.previewBus = previewBus;
        this.confirmationCoordinator = confirmationCoordinator;
        this.turnRunner = turnRunner;
        this.objectMapper = objectMapper;
        this.handsMetrics = handsMetrics;
    }

    /** Posts inbound user events (message, interrupt, tool confirmation). */
    @PostMapping("/{id}/events")
    public Mono<List<SessionEventDto>> postEvents(
            @PathVariable("id") String id,
            @RequestBody PostEventsRequest body,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            sessionService.get(userId, id);
                            if (body.events() == null || body.events().isEmpty()) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "events is required");
                            }
                            List<SessionEventDto> recorded = new java.util.ArrayList<>();
                            for (InboundEvent event : body.events()) {
                                recorded.add(handleInboundEvent(userId, id, event));
                            }
                            return recorded;
                        })
                .subscribeOn(BLOCKING);
    }

    /**
     * Lists persisted session events, optionally after a sequence cursor and/or filtered by {@code
     * types} (repeatable query param, Claude Managed Agents {@code types[]} equivalent).
     */
    @GetMapping("/{id}/events")
    public Mono<List<SessionEventDto>> listEvents(
            @PathVariable("id") String id,
            @RequestParam(value = "after", required = false) Long after,
            @RequestParam(value = "types", required = false) List<String> types,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            sessionService.get(userId, id);
                            if (after == null) {
                                return eventLog.list(id, types);
                            }
                            return eventLog.listAfter(id, after, types);
                        })
                .subscribeOn(BLOCKING);
    }

    /**
     * Deletes all persisted events for a session. Used by the control plane after product session
     * DELETE (best-effort cascade). Internal-token callers may delete without a live session row;
     * user JWT callers must still own a resolvable session.
     *
     * <p>Only the internal call also releases the session's in-process runtime state, because only
     * then is the session itself gone. A user clearing their own transcript keeps a live session
     * that must retain its team routing and cached agent.
     */
    @DeleteMapping("/{id}/events")
    public Mono<Void> deleteEvents(@PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        boolean internal =
                auth.getAuthorities().stream()
                        .anyMatch(
                                a ->
                                        InternalTokenAuthFilter.ROLE_INTERNAL.equals(
                                                a.getAuthority()));
        return Mono.fromRunnable(
                        () -> {
                            if (internal) {
                                eventLog.purgeDeletedSession(id);
                                turnRunner.releaseSession(id, userId);
                            } else {
                                sessionService.get(userId, id);
                                eventLog.deleteBySessionId(id);
                            }
                        })
                .subscribeOn(BLOCKING)
                .then();
    }

    /** Returns hands (sandbox lease) acquire/release/timeout counters for this session. */
    @GetMapping("/{id}/hands-stats")
    public Mono<HandsMetrics.Snapshot> handsStats(
            @PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            sessionService.get(userId, id);
                            return handsMetrics.snapshot(id);
                        })
                .subscribeOn(BLOCKING);
    }

    /** Opt-in preview targets for {@code event_deltas=} (Claude: message + thinking; we also allow tool_use). */
    private static final Set<String> ALLOWED_EVENT_DELTAS =
            Set.of(
                    SessionEventTypes.AGENT_MESSAGE,
                    SessionEventTypes.AGENT_THINKING,
                    SessionEventTypes.AGENT_TOOL_USE);

    /** Streams session events over SSE, optionally merging stream-only preview deltas. */
    @GetMapping(value = "/{id}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamEvents(
            @PathVariable("id") String id,
            @RequestParam(value = "after", required = false) Long after,
            @RequestParam(value = "event_deltas", required = false) List<String> eventDeltas,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        if (eventDeltas != null) {
            if (eventDeltas.size() > 100) {
                return Flux.error(
                        new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "event_deltas accepts at most 100 values"));
            }
            for (String t : eventDeltas) {
                if (!ALLOWED_EVENT_DELTAS.contains(t)) {
                    return Flux.error(
                            new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "unsupported event_deltas value: "
                                            + t
                                            + " (allowed: agent.message, agent.thinking,"
                                            + " agent.tool_use)"));
                }
            }
        }

        long afterSeq = after != null ? after : 0L;
        return Mono.fromCallable(
                        () -> {
                            sessionService.get(userId, id);
                            return Boolean.TRUE;
                        })
                .subscribeOn(BLOCKING)
                .flatMapMany(
                        ignored -> {
                            Flux<SessionEventDto> persisted = eventLog.subscribe(id, afterSeq);
                            if (eventDeltas == null || eventDeltas.isEmpty()) {
                                return persisted.map(this::toSse);
                            }
                            Set<String> deltaTypes = new HashSet<>(eventDeltas);
                            Flux<SessionEventDto> previews =
                                    previewBus
                                            .subscribe(id)
                                            .filter(
                                                    dto -> {
                                                        if (dto.payload() == null) {
                                                            return false;
                                                        }
                                                        Object targetType =
                                                                dto.payload().get("type");
                                                        return targetType != null
                                                                && deltaTypes.contains(
                                                                        String.valueOf(targetType));
                                                    });
                            return Flux.merge(persisted, previews).map(this::toSse);
                        });
    }

    private SessionEventDto handleInboundEvent(
            String userId, String sessionId, InboundEvent event) {
        String type = event.type();
        if (type == null || type.isBlank()) {
            throw ApiException.invalidRequest(
                    "missing_event_type", "event.type is required", "events[].type");
        }
        Map<String, Object> payload = event.payload() != null ? event.payload() : Map.of();
        return switch (type) {
            case SessionEventTypes.USER_MESSAGE -> {
                // Recorded only once a turn has been admitted. A team wake that arrives
                // while the session is mid-turn is rejected and retried by the control
                // plane, and recording each rejection would fill the transcript with
                // copies of a message no turn ever read.
                AtomicReference<SessionEventDto> admitted = new AtomicReference<>();
                sessionService.runTurn(
                        userId,
                        sessionId,
                        payload,
                        () -> admitted.set(eventLog.append(sessionId, type, payload)));
                SessionEventDto recorded = admitted.get();
                yield recorded != null ? recorded : eventLog.append(sessionId, type, payload);
            }
            case SessionEventTypes.USER_INTERRUPT -> {
                turnRunner.interrupt(sessionId);
                sessionService.updateStatus(
                        userId, sessionId, DataSessionService.STATUS_IDLE, payload);
                yield eventLog.append(sessionId, type, payload);
            }
            case SessionEventTypes.USER_TOOL_CONFIRMATION -> {
                String toolUseId = stringValue(payload.get("tool_use_id"));
                if (toolUseId == null) {
                    toolUseId = stringValue(payload.get("toolUseId"));
                }
                if (toolUseId == null) {
                    throw ApiException.invalidRequest(
                            "missing_tool_use_id",
                            "tool_use_id is required for user.tool_confirmation",
                            "events[].payload.tool_use_id");
                }
                boolean allow = Boolean.TRUE.equals(payload.get("allow"));
                String denyMessage = stringValue(payload.get("denyMessage"));
                confirmationCoordinator.resolve(toolUseId, allow, denyMessage);
                sessionService.updateStatus(
                        userId, sessionId, DataSessionService.STATUS_RUNNING, null);
                yield eventLog.append(sessionId, type, payload);
            }
            case SessionEventTypes.USER_CUSTOM_TOOL_RESULT -> {
                SessionEventDto recorded = eventLog.append(sessionId, type, payload);
                ToolResultBlock block = SessionTurnRunner.toolResultFromPayload(payload);
                ManagedSessionDto session = sessionService.get(userId, sessionId);
                turnRunner.resumeWithToolResults(session, List.of(block));
                yield recorded;
            }
            case SessionEventTypes.USER_TOOL_RESULT -> {
                SessionEventDto recorded = eventLog.append(sessionId, type, payload);
                ToolResultBlock block = SessionTurnRunner.toolResultFromPayload(payload);
                ManagedSessionDto session = sessionService.get(userId, sessionId);
                turnRunner.resumeWithToolResults(session, List.of(block));
                yield recorded;
            }
            case SessionEventTypes.USER_DEFINE_OUTCOME -> eventLog.append(sessionId, type, payload);
            case SessionEventTypes.SYSTEM_MESSAGE -> {
                String text = extractSystemText(payload);
                if (text != null && !text.isBlank()) {
                    sessionService.mergeAgentOverrides(userId, sessionId, Map.of("system", text));
                }
                yield eventLog.append(sessionId, type, payload);
            }
            default ->
                    throw ApiException.invalidRequest(
                            "unknown_event_type",
                            "Unknown inbound event type: " + type,
                            "events[].type");
        };
    }

    private static String extractSystemText(Map<String, Object> payload) {
        for (String key : List.of("text", "message", "content", "system")) {
            String value = stringValue(payload.get(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private ServerSentEvent<String> toSse(SessionEventDto dto) {
        try {
            String json = objectMapper.writeValueAsString(dto);
            return ServerSentEvent.<String>builder().event(dto.type()).data(json).build();
        } catch (JsonProcessingException ex) {
            return ServerSentEvent.<String>builder().event("error").data("{}").build();
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** Batch inbound event envelope. */
    public record PostEventsRequest(List<InboundEvent> events) {}

    /** Single inbound event with type and free-form payload fields. */
    public record InboundEvent(String type, Map<String, Object> payload) {
        /** Merges explicit payload with any additional JSON fields on the event object. */
        public InboundEvent {
            payload = payload != null ? payload : new LinkedHashMap<>();
        }
    }
}
