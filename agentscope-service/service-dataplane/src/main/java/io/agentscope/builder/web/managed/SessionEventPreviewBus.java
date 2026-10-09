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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Process-local stream-only public text preview bus. Never
 * persisted; best-effort and sticky to the turn-owner JVM (see DATA_PLANE_CONTRACT §3).
 */
@Component
public class SessionEventPreviewBus {

    private final ConcurrentHashMap<String, Sinks.Many<SessionEventDto>> sinks =
            new ConcurrentHashMap<>();

    public void emitPublic(String sessionId, Map<String, Object> payload) {
        emit(sessionId, "item.delta", payload);
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
