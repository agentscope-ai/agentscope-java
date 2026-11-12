/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.agentservice.sessionservice.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentcore.managed.server.agentservice.sessionservice.store.SessionServiceCoordination;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Internal token-protected control operations; private ACK handles never enter public APIs. */
@RestController
@RequestMapping("/internal/session-service/v0/prepared/coordination")
public final class SessionCoordinationApi {
    private final SessionServiceCoordination coordination;

    public SessionCoordinationApi(SessionServiceCoordination coordination) {
        this.coordination = coordination;
    }

    @PostMapping
    public Mono<JsonNode> coordinate(@RequestBody JsonNode request) {
        return coordination.invoke(request);
    }

    @ExceptionHandler(ManagedStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(ManagedStateException failure) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        error(
                                failure.code(),
                                Set.of(
                                                "execution_busy",
                                                "capacity_exceeded",
                                                "temporarily_unavailable")
                                        .contains(failure.code())));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> unavailable(DataAccessException failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error("temporarily_unavailable", true));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(error("invalid_request", false));
    }

    private Map<String, Object> error(String code, boolean retryable) {
        return Map.of(
                "protocolVersion", "0.1.0", "code", code, "message", code, "retryable", retryable);
    }
}
