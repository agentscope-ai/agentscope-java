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
package io.agentscope.builder.web.managed;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Process-local stream-only preview bus for {@code event_start} / {@code event_delta}. Never
 * persisted; best-effort and sticky to the turn-owner JVM (see DATA_PLANE_CONTRACT §3).
 */
@Component
public class SessionEventPreviewBus {

    private final ConcurrentHashMap<String, Sinks.Many<SessionEventDto>> sinks =
            new ConcurrentHashMap<>();

    /** Emits an {@code event_start} frame for a forthcoming persisted type. */
    public void emitStart(String sessionId, String targetType, String eventId) {
        emit(
                sessionId,
                SessionEventTypes.EVENT_START,
                Map.of("event_id", eventId, "type", targetType));
    }

    /** Emits an {@code event_delta} frame. */
    public void emitDelta(String sessionId, String targetType, String eventId, String delta) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", eventId);
        payload.put("type", targetType);
        payload.put("delta", delta != null ? delta : "");
        emit(sessionId, SessionEventTypes.EVENT_DELTA, payload);
    }

    /** Publishes a mapper preview frame when the subscriber opted into {@code targetType}. */
    public void emitFrame(
            String sessionId, SessionEventMapper.PreviewFrame frame, Set<String> enabledTypes) {
        if (frame == null || enabledTypes == null || !enabledTypes.contains(frame.targetType())) {
            return;
        }
        if (SessionEventTypes.EVENT_START.equals(frame.streamType())) {
            emitStart(sessionId, frame.targetType(), frame.eventId());
        } else {
            emitDelta(sessionId, frame.targetType(), frame.eventId(), frame.delta());
        }
    }

    public Flux<SessionEventDto> subscribe(String sessionId) {
        return sinkFor(sessionId).asFlux();
    }

    private void emit(String sessionId, String type, Map<String, Object> payload) {
        SessionEventDto dto =
                new SessionEventDto(
                        null, sessionId, -1L, type, payload, null, System.currentTimeMillis());
        sinkFor(sessionId).tryEmitNext(dto);
    }

    private Sinks.Many<SessionEventDto> sinkFor(String sessionId) {
        return sinks.computeIfAbsent(
                sessionId, ignored -> Sinks.many().multicast().onBackpressureBuffer(512, false));
    }
}
