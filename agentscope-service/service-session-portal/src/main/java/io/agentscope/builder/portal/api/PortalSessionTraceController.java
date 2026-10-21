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

import io.agentscope.builder.portal.session.PortalSessionTrace;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/agent-sessions/{session}/trace")
public final class PortalSessionTraceController {
    private final PortalSessionTrace traces;

    public PortalSessionTraceController(PortalSessionTrace traces) {
        this.traces = traces;
    }

    @GetMapping
    public Mono<Map<String, Object>> get(
            @PathVariable String session,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(required = false) Long through,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers,
            ServerHttpResponse response) {
        response.getHeaders().setCacheControl("no-store");
        response.getHeaders().set("X-Content-Type-Options", "nosniff");
        return traces.get(session, after, through, limit, headers);
    }
}
