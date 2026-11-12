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
package io.agentcore.managed.server.agentservice.api;

import io.agentcore.managed.server.agentservice.session.AgentServiceCheckpointWrites;
import io.agentcore.managed.server.agentservice.session.AgentServiceInputDelivery;
import io.agentcore.managed.server.agentservice.session.AgentServiceTurnControl;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/agent-sessions/{session}")
public class AgentServiceTurnControlController {
    private final AgentServiceTurnControl controls;
    private final AgentServiceInputDelivery delivery;
    private final AgentServiceCheckpointWrites checkpoints;

    public AgentServiceTurnControlController(
            AgentServiceTurnControl controls,
            AgentServiceInputDelivery delivery,
            AgentServiceCheckpointWrites checkpoints) {
        this.controls = controls;
        this.delivery = delivery;
        this.checkpoints = checkpoints;
    }

    @GetMapping("/turns/{turn}/capabilities")
    public Mono<Map<String, Object>> capabilities(
            @PathVariable String session,
            @PathVariable String turn,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> controls.capabilities(session, turn, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/turns/{turn}/cancel")
    public Mono<ResponseEntity<Map<String, Object>>> cancel(
            @PathVariable String session,
            @PathVariable String turn,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(
                        () -> {
                            var command = controls.cancel(session, turn, headers);
                            return ResponseEntity.accepted().body(delivery.submit(command.id));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/turns/{turn}/resume")
    public Mono<ResponseEntity<Map<String, Object>>> resume(
            @PathVariable String session,
            @PathVariable String turn,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(
                        () -> {
                            var command = controls.resume(session, turn, headers);
                            return ResponseEntity.accepted().body(delivery.submit(command.id));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/turns/{turn}/actions")
    public Mono<ResponseEntity<Map<String, Object>>> actions(
            @PathVariable String session,
            @PathVariable String turn,
            @RequestBody ActionAnswers body,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(
                        () -> {
                            var command = controls.actions(session, turn, headers, body.answers());
                            return ResponseEntity.accepted().body(delivery.submit(command.id));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/turns/{turn}/tool-recovery")
    public Mono<ResponseEntity<Map<String, Object>>> retryTool(
            @PathVariable String session,
            @PathVariable String turn,
            @RequestBody Map<String, Object> body,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(
                        () ->
                                ResponseEntity.accepted()
                                        .body(
                                                delivery.submit(
                                                        controls.retryTool(
                                                                        session, turn, headers,
                                                                        body)
                                                                .id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/turns/{turn}/workspace-recovery")
    public Mono<ResponseEntity<Map<String, Object>>> recoverWorkspace(
            @PathVariable String session,
            @PathVariable String turn,
            @RequestBody Map<String, Object> body,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(
                        () ->
                                ResponseEntity.accepted()
                                        .body(
                                                delivery.submit(
                                                        controls.recoverWorkspace(
                                                                        session, turn, headers,
                                                                        body)
                                                                .id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public record ActionAnswers(List<Map<String, Object>> answers) {}

    @GetMapping("/commands/{id}")
    public Mono<Map<String, Object>> get(
            @PathVariable String session,
            @PathVariable String id,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(
                        () ->
                                checkpoints.owns(session, id)
                                        ? checkpoints.get(session, id, headers)
                                        : controls.get(session, id, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
