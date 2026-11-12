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

import io.agentcore.managed.server.agentservice.session.AgentServiceSessionProvisioning;
import io.agentcore.managed.server.runtime.protocol.RuntimeSessionAdmission;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/internal/runtime/sessions")
public class SessionProvisioningController {
    private final AgentServiceSessionProvisioning sessions;

    public SessionProvisioningController(AgentServiceSessionProvisioning sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/provision")
    public Mono<Map<String, Object>> provision(@RequestBody RuntimeSessionAdmission admission) {
        return Mono.fromCallable(() -> sessions.provision(admission))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/provisioning")
    public Mono<Map<String, Object>> status(@PathVariable String id) {
        return Mono.fromCallable(() -> sessions.status(id))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
