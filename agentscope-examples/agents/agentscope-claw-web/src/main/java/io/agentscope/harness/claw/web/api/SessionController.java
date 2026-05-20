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
package io.agentscope.harness.claw.web.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.harness.claw.web.runtime.ClawRuntimeClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Admin read-only session view proxy for the admin console.
 *
 * <ul>
 *   <li>{@code GET /api/admin/sessions} — all active sessions (delegated to claw runtime)
 *   <li>{@code GET /api/admin/sessions/{key}} — single session detail
 *   <li>{@code GET /api/admin/sessions/{key}/tree} — recursive sub-agent tree
 * </ul>
 *
 * <p>Requires {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping("/api/admin/sessions")
public class SessionController {

    private final ClawRuntimeClient clawClient;

    public SessionController(ClawRuntimeClient clawClient) {
        this.clawClient = clawClient;
    }

    @GetMapping
    public Mono<JsonNode> sessions(
            @RequestParam(defaultValue = "100") int limit, ServerWebExchange exchange) {
        String bearer = ClawRuntimeClient.extractBearer(exchange);
        return Mono.fromCallable(
                () -> clawClient.get("/api/admin/runtime/sessions?limit=" + limit, bearer));
    }

    @GetMapping("/{sessionKey}")
    public Mono<JsonNode> sessionDetail(
            @PathVariable String sessionKey, ServerWebExchange exchange) {
        String bearer = ClawRuntimeClient.extractBearer(exchange);
        return Mono.fromCallable(
                () -> clawClient.get("/api/admin/runtime/sessions/" + encode(sessionKey), bearer));
    }

    @GetMapping("/{sessionKey}/tree")
    public Mono<JsonNode> sessionTree(@PathVariable String sessionKey, ServerWebExchange exchange) {
        String bearer = ClawRuntimeClient.extractBearer(exchange);
        return Mono.fromCallable(
                () ->
                        clawClient.get(
                                "/api/admin/runtime/sessions/" + encode(sessionKey) + "/tree",
                                bearer));
    }

    @GetMapping("/{sessionKey}/workspace/events")
    public Mono<JsonNode> workspaceEvents(
            @PathVariable String sessionKey,
            @RequestParam(required = false) Long since,
            @RequestParam(defaultValue = "200") int limit,
            ServerWebExchange exchange) {
        String bearer = ClawRuntimeClient.extractBearer(exchange);
        StringBuilder q = new StringBuilder("?limit=").append(limit);
        if (since != null) q.append("&since=").append(since);
        return Mono.fromCallable(
                () ->
                        clawClient.get(
                                "/api/admin/runtime/sessions/"
                                        + encode(sessionKey)
                                        + "/workspace/events"
                                        + q,
                                bearer));
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
