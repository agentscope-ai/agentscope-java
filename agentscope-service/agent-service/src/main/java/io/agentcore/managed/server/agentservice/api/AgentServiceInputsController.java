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

import io.agentcore.managed.server.agentservice.session.AgentServiceInputAdmission;
import io.agentcore.managed.server.agentservice.session.AgentServiceInputDelivery;
import io.agentcore.managed.server.web.managed.AgentSessionInput;
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
public class AgentServiceInputsController {
    private final AgentServiceInputAdmission inputs;
    private final AgentServiceInputDelivery delivery;

    public AgentServiceInputsController(
            AgentServiceInputAdmission inputs, AgentServiceInputDelivery delivery) {
        this.inputs = inputs;
        this.delivery = delivery;
    }

    @PostMapping("/inputs/inject")
    public Mono<ResponseEntity<Map<String, Object>>> inject(
            @PathVariable String session,
            @RequestBody AgentSessionInput input,
            @RequestHeader HttpHeaders headers) {
        return accept(session, null, "inject", input, headers);
    }

    @PostMapping("/turns/{turn}/steer")
    public Mono<ResponseEntity<Map<String, Object>>> steer(
            @PathVariable String session,
            @PathVariable String turn,
            @RequestBody AgentSessionInput input,
            @RequestHeader HttpHeaders headers) {
        return accept(session, turn, "steer", input, headers);
    }

    @GetMapping("/inputs/{id}")
    public Mono<Map<String, Object>> get(
            @PathVariable String session,
            @PathVariable String id,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> inputs.get(session, id, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<ResponseEntity<Map<String, Object>>> accept(
            String session,
            String turn,
            String kind,
            AgentSessionInput input,
            HttpHeaders headers) {
        return Mono.fromCallable(
                        () -> {
                            var row = inputs.stage(session, turn, kind, input, headers);
                            return ResponseEntity.accepted().body(delivery.submit(row.id));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
