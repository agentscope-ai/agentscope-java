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

import io.agentcore.managed.server.agentservice.session.AgentServiceRuntimeHosts;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/runtime-hosts")
public class AgentServiceRuntimeHostsController {
    private final AgentServiceRuntimeHosts hosts;

    public AgentServiceRuntimeHostsController(AgentServiceRuntimeHosts hosts) {
        this.hosts = hosts;
    }

    @PostMapping("/register")
    public Mono<ResponseEntity<Map<String, Object>>> register(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String credential,
            @RequestBody Map<String, Object> body) {
        return hosts.call(credential, body, "register", "register");
    }

    @PostMapping("/{hostId:[0-9a-fA-F-]{36}}/{operation:heartbeat|state|credentials}")
    public Mono<ResponseEntity<Map<String, Object>>> state(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String credential,
            @PathVariable String hostId,
            @PathVariable String operation,
            @RequestBody Map<String, Object> body) {
        return hosts.call(credential, body, operation, hostId, operation);
    }

    @PostMapping("/{hostId:[0-9a-fA-F-]{36}}/execution-attempts/claim")
    public Mono<ResponseEntity<Map<String, Object>>> claim(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String credential,
            @PathVariable String hostId,
            @RequestBody Map<String, Object> body) {
        return hosts.call(credential, body, "claim", hostId, "execution-attempts", "claim");
    }

    @PostMapping(
            "/{hostId:[0-9a-fA-F-]{36}}/execution-attempts/{attemptId:[0-9a-fA-F-]{36}}/{operation:renew|credentials|observe|preparing|running|checkpoint|events|complete|fail|cancelled|physical-stop}")
    public Mono<ResponseEntity<Map<String, Object>>> attempt(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String credential,
            @RequestHeader(value = "X-Execution-Attempt-Token", required = false)
                    String attemptCredential,
            @PathVariable String hostId,
            @PathVariable String attemptId,
            @PathVariable String operation,
            @RequestBody Map<String, Object> body) {
        return hosts.call(
                credential,
                attemptCredential,
                body,
                operation,
                hostId,
                "execution-attempts",
                attemptId,
                operation);
    }
}
