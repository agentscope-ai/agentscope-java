/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.portal.api;

import io.agentscope.builder.portal.session.PortalRuntimeHosts;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Task-scoped machine callbacks; human answers use the public Turn actions API. */
@RestController
@RequestMapping("/api/v1/agent-tasks/{taskId:[0-9a-fA-F-]{36}}/runtime-approvals")
public class PortalRuntimeApprovalsController {
    private final PortalRuntimeHosts transport;

    public PortalRuntimeApprovalsController(PortalRuntimeHosts transport) {
        this.transport = transport;
    }

    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> create(
            @RequestHeader(value = "X-Agent-Task-Token", required = false) String credential,
            @PathVariable String taskId,
            @RequestBody Map<String, Object> body) {
        return transport.approval(HttpMethod.POST, credential, body, taskId, "runtime-approvals");
    }

    @GetMapping("/{approvalId:[0-9a-fA-F-]{36}}/decision")
    public Mono<ResponseEntity<Map<String, Object>>> decision(
            @RequestHeader(value = "X-Agent-Task-Token", required = false) String credential,
            @PathVariable String taskId,
            @PathVariable String approvalId) {
        return transport.approval(
                HttpMethod.GET,
                credential,
                null,
                taskId,
                "runtime-approvals",
                approvalId,
                "decision");
    }

    @PostMapping("/{approvalId:[0-9a-fA-F-]{36}}/ack")
    public Mono<ResponseEntity<Map<String, Object>>> acknowledge(
            @RequestHeader(value = "X-Agent-Task-Token", required = false) String credential,
            @PathVariable String taskId,
            @PathVariable String approvalId,
            @RequestBody Map<String, Object> body) {
        return transport.approval(
                HttpMethod.POST, credential, body, taskId, "runtime-approvals", approvalId, "ack");
    }
}
