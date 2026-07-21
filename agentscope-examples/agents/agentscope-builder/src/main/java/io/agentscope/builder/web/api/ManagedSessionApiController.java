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
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.ManagedSessionService;
import io.agentscope.builder.web.managed.ManagedSessionService.CreateSessionRequest;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.managed.SessionEventLog;
import io.agentscope.builder.web.managed.SessionTurnRunner;
import io.agentscope.builder.web.share.AgentAccessGuard;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import io.agentscope.builder.web.toolbus.ToolConfirmationCoordinator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final AgentAccessGuard guard;
    private final ToolConfirmationCoordinator confirmationCoordinator;
    private final SessionTurnRunner turnRunner;
    private final ObjectMapper objectMapper;

    public ManagedSessionApiController(
            ManagedSessionService sessionService,
            SessionEventLog eventLog,
            AgentAccessGuard guard,
            ToolConfirmationCoordinator confirmationCoordinator,
            SessionTurnRunner turnRunner,
            ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.eventLog = eventLog;
        this.guard = guard;
        this.confirmationCoordinator = confirmationCoordinator;
        this.turnRunner = turnRunner;
        this.objectMapper = objectMapper;
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
                    sessionService.get(userId, id);
                    if (after == null) {
                        return eventLog.list(id);
                    }
                    return eventLog.listAfter(id, after);
                });
    }

    /** Streams session events over SSE. */
    @GetMapping(value = "/{id}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamEvents(
            @PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        sessionService.get(userId, id);
        return eventLog.subscribe(id).map(this::toSse);
    }

    private SessionEventDto handleInboundEvent(
            String userId, String sessionId, InboundEvent event) {
        String type = event.type();
        if (type == null || type.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "event.type is required");
        }
        Map<String, Object> payload = event.payload() != null ? event.payload() : Map.of();
        return switch (type) {
            case "user.message" -> {
                SessionEventDto recorded = eventLog.append(sessionId, type, payload);
                sessionService.runTurn(userId, sessionId, payload);
                yield recorded;
            }
            case "user.interrupt" -> {
                turnRunner.interrupt(sessionId);
                sessionService.updateStatus(
                        userId, sessionId, ManagedSessionService.STATUS_IDLE, payload);
                yield eventLog.append(sessionId, type, payload);
            }
            case "user.tool_confirmation" -> {
                String toolUseId = stringValue(payload.get("toolUseId"));
                if (toolUseId == null) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "toolUseId is required for user.tool_confirmation");
                }
                boolean allow = Boolean.TRUE.equals(payload.get("allow"));
                String denyMessage = stringValue(payload.get("denyMessage"));
                confirmationCoordinator.resolve(toolUseId, allow, denyMessage);
                sessionService.updateStatus(
                        userId, sessionId, ManagedSessionService.STATUS_RUNNING, null);
                yield eventLog.append(sessionId, type, payload);
            }
            default -> eventLog.append(sessionId, type, payload);
        };
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
