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
import io.agentscope.builder.web.managed.HandsMetrics;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.ManagedSessionService;
import io.agentscope.builder.web.managed.ManagedSessionService.CreateSessionRequest;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.managed.SessionEventLog;
import io.agentscope.builder.web.managed.SessionEventPreviewBus;
import io.agentscope.builder.web.managed.SessionEventTypes;
import io.agentscope.builder.web.managed.SessionTurnRunner;
import io.agentscope.builder.web.share.AgentAccessGuard;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import io.agentscope.builder.web.toolbus.ToolConfirmationCoordinator;
import io.agentscope.core.message.ToolResultBlock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** REST controller for managed agent sessions and their event log. */
@RestController
@RequestMapping("/api/sessions")
public class ManagedSessionApiController {

    private final ManagedSessionService sessionService;
    private final SessionEventLog eventLog;
    private final SessionEventPreviewBus previewBus;
    private final AgentAccessGuard guard;
    private final ToolConfirmationCoordinator confirmationCoordinator;
    private final SessionTurnRunner turnRunner;
    private final ObjectMapper objectMapper;
    private final HandsMetrics handsMetrics;

    public ManagedSessionApiController(
            ManagedSessionService sessionService,
            SessionEventLog eventLog,
            SessionEventPreviewBus previewBus,
            AgentAccessGuard guard,
            ToolConfirmationCoordinator confirmationCoordinator,
            SessionTurnRunner turnRunner,
            ObjectMapper objectMapper,
            HandsMetrics handsMetrics) {
        this.sessionService = sessionService;
        this.eventLog = eventLog;
        this.previewBus = previewBus;
        this.guard = guard;
        this.confirmationCoordinator = confirmationCoordinator;
        this.turnRunner = turnRunner;
        this.objectMapper = objectMapper;
        this.handsMetrics = handsMetrics;
    }

    /** Creates a managed session. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ManagedSessionDto> create(
            @RequestBody CreateSessionRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    String agentId = resolveAgentId(req.agent());
                    guard.require(userId, agentId, Tier.RUN);
                    return sessionService.create(userId, req);
                });
    }

    /** Lists sessions for the authenticated user. */
    @GetMapping
    public Mono<List<ManagedSessionDto>> list(
            @RequestParam(value = "agentId", required = false) String agentId,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> sessionService.list(userId, agentId));
    }

    /** Returns a single session. */
    @GetMapping("/{id}")
    public Mono<ManagedSessionDto> get(@PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> sessionService.get(userId, id));
    }

    /** Archives a session. */
    @PostMapping("/{id}/archive")
    public Mono<ManagedSessionDto> archive(@PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> sessionService.archive(userId, id));
    }

    /** Deletes a session and its events. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(() -> sessionService.delete(userId, id));
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
                    ManagedSessionDto session = sessionService.get(userId, id);
                    guard.require(userId, session.agentId(), Tier.RUN);
                    if (body.events() == null || body.events().isEmpty()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "events is required");
                    }
                    List<SessionEventDto> recorded = new java.util.ArrayList<>();
                    for (InboundEvent event : body.events()) {
                        recorded.add(handleInboundEvent(userId, id, event));
                    }
                    return recorded;
                });
    }

    /** Lists persisted session events, optionally after a sequence cursor. */
    @GetMapping("/{id}/events")
    public Mono<List<SessionEventDto>> listEvents(
            @PathVariable("id") String id,
            @RequestParam(value = "after", required = false) Long after,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    ManagedSessionDto session = sessionService.get(userId, id);
                    guard.require(userId, session.agentId(), Tier.RUN);
                    if (after == null) {
                        return eventLog.list(id);
                    }
                    return eventLog.listAfter(id, after);
                });
    }

    /** Returns hands (sandbox lease) acquire/release/timeout counters for this session. */
    @GetMapping("/{id}/hands-stats")
    public Mono<HandsMetrics.Snapshot> handsStats(
            @PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    ManagedSessionDto session = sessionService.get(userId, id);
                    guard.require(userId, session.agentId(), Tier.RUN);
                    return handsMetrics.snapshot(id);
                });
    }

    /** Streams session events over SSE, optionally merging stream-only preview deltas. */
    @GetMapping(value = "/{id}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamEvents(
            @PathVariable("id") String id,
            @RequestParam(value = "event_deltas", required = false) List<String> eventDeltas,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        ManagedSessionDto session = sessionService.get(userId, id);
        guard.require(userId, session.agentId(), Tier.RUN);

        Flux<SessionEventDto> persisted = eventLog.subscribe(id);
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
                                    Object targetType = dto.payload().get("type");
                                    return targetType != null
                                            && deltaTypes.contains(String.valueOf(targetType));
                                });
        return Flux.merge(persisted, previews).map(this::toSse);
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
                SessionEventDto recorded = eventLog.append(sessionId, type, payload);
                sessionService.runTurn(userId, sessionId, payload);
                yield recorded;
            }
            case SessionEventTypes.USER_INTERRUPT -> {
                turnRunner.interrupt(sessionId);
                sessionService.updateStatus(
                        userId, sessionId, ManagedSessionService.STATUS_IDLE, payload);
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
                        userId, sessionId, ManagedSessionService.STATUS_RUNNING, null);
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

    @SuppressWarnings("unchecked")
    private static String resolveAgentId(Object agent) {
        if (agent instanceof String agentId) {
            return agentId;
        }
        if (agent instanceof Map<?, ?> map) {
            Object id = ((Map<String, Object>) map).get("id");
            if (id != null) {
                return String.valueOf(id);
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agent id is required");
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
