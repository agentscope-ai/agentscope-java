/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.portal.api;

import io.agentscope.builder.portal.session.PortalCheckpointWrites;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public final class PortalCheckpointWritesController {
    private final PortalCheckpointWrites checkpoints;

    public PortalCheckpointWritesController(PortalCheckpointWrites checkpoints) {
        this.checkpoints = checkpoints;
    }

    @PostMapping("/v1/sessions/{session}/checkpoints/restore")
    public Mono<ResponseEntity<Map<String, Object>>> restore(
            @PathVariable String session,
            @RequestBody Map<String, Object> body,
            @RequestHeader HttpHeaders headers) {
        if (body.containsKey("target_session_id"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return submit(session, session, body, headers);
    }

    @PostMapping("/v1/sessions/{session}/fork")
    public Mono<ResponseEntity<Map<String, Object>>> fork(
            @PathVariable String session,
            @RequestBody Map<String, Object> body,
            @RequestHeader HttpHeaders headers) {
        if (!(body.get("target_session_id") instanceof String target)
                || target.isBlank()
                || target.equals(session))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A different target Session is required");
        return submit(session, target, body, headers);
    }

    @GetMapping("/v1/sessions/{session}/checkpoint-operations/{operation}")
    public Mono<Map<String, Object>> get(
            @PathVariable String session,
            @PathVariable String operation,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> checkpoints.get(session, operation, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/v1/sessions/{session}/checkpoint-operations/{operation}/cancel")
    public Mono<Map<String, Object>> cancel(
            @PathVariable String session,
            @PathVariable String operation,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> checkpoints.cancel(session, operation, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<ResponseEntity<Map<String, Object>>> submit(
            String source, String target, Map<String, Object> body, HttpHeaders headers) {
        return Mono.fromCallable(
                        () ->
                                ResponseEntity.accepted()
                                        .body(checkpoints.stage(source, target, body, headers)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
