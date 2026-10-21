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
package io.agentscope.builder.portal.api;

import io.agentscope.builder.portal.session.PortalRuntimeHosts;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Task-scoped CLI transport. Human resource CRUD remains in the control plane. */
@RestController
@RequestMapping("/api/v1")
public class PortalTaskCollaborationController {
    private final PortalRuntimeHosts transport;

    public PortalTaskCollaborationController(PortalRuntimeHosts transport) {
        this.transport = transport;
    }

    @GetMapping({
        "/agent-tasks/{taskId:[0-9a-fA-F-]{36}}",
        "/agent-tasks/{taskId:[0-9a-fA-F-]{36}}/{resource:context|run}",
        "/agent-tasks/{taskId:[0-9a-fA-F-]{36}}/run/{resource:graph|artifacts}",
        "/issues/{issueId:[0-9a-fA-F-]{36}}",
        "/issues/{issueId:[0-9a-fA-F-]{36}}/comments",
        "/teams/{teamId:[0-9a-fA-F-]{36}}"
    })
    public Mono<ResponseEntity<Map<String, Object>>> read(
            @RequestHeader(value = "X-Agent-Task-Token", required = false) String credential,
            ServerHttpRequest request) {
        return transport.task(HttpMethod.GET, credential, null, path(request));
    }

    @PostMapping({
        "/agent-tasks/{taskId:[0-9a-fA-F-]{36}}/{action:progress|respond|children|complete|fail}",
        "/agent-tasks/{taskId:[0-9a-fA-F-]{36}}/run/node/{action:complete|fail}",
        "/agent-tasks/{taskId:[0-9a-fA-F-]{36}}/run/replan",
        "/agent-tasks/{taskId:[0-9a-fA-F-]{36}}/run/signals/{name}",
        "/issues/{issueId:[0-9a-fA-F-]{36}}/comments"
    })
    public Mono<ResponseEntity<Map<String, Object>>> write(
            @RequestHeader(value = "X-Agent-Task-Token", required = false) String credential,
            @RequestBody Map<String, Object> body,
            ServerHttpRequest request) {
        return transport.task(HttpMethod.POST, credential, body, path(request));
    }

    private String[] path(ServerHttpRequest request) {
        return request.getPath()
                .pathWithinApplication()
                .value()
                .substring("/api/v1/".length())
                .split("/");
    }
}
