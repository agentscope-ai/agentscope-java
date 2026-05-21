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
 * Admin usage-statistics proxy. Delegates to agentscope-claw's {@code /api/admin/usage/*}
 * endpoints so the admin console can render dashboards without depending on claw's runtime
 * classes.
 *
 * <p>All endpoints require {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping("/api/admin/usage")
public class UsageController {

    private final ClawRuntimeClient clawClient;

    public UsageController(ClawRuntimeClient clawClient) {
        this.clawClient = clawClient;
    }

    @GetMapping("/summary")
    public Mono<JsonNode> summary(ServerWebExchange exchange) {
        String bearer = ClawRuntimeClient.extractBearer(exchange);
        return Mono.fromCallable(() -> clawClient.get("/api/admin/usage/summary", bearer));
    }

    @GetMapping("/hourly")
    public Mono<JsonNode> hourly(
            @RequestParam(defaultValue = "24") int hours, ServerWebExchange exchange) {
        String bearer = ClawRuntimeClient.extractBearer(exchange);
        return Mono.fromCallable(
                () -> clawClient.get("/api/admin/usage/hourly?hours=" + hours, bearer));
    }

    @GetMapping("/daily")
    public Mono<JsonNode> daily(
            @RequestParam(defaultValue = "30") int days, ServerWebExchange exchange) {
        String bearer = ClawRuntimeClient.extractBearer(exchange);
        return Mono.fromCallable(
                () -> clawClient.get("/api/admin/usage/daily?days=" + days, bearer));
    }

    @GetMapping("/top-users")
    public Mono<JsonNode> topUsers(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "10") int n,
            ServerWebExchange exchange) {
        String bearer = ClawRuntimeClient.extractBearer(exchange);
        return Mono.fromCallable(
                () ->
                        clawClient.get(
                                "/api/admin/usage/top-users?days=" + days + "&n=" + n, bearer));
    }

    @GetMapping("/top-agents")
    public Mono<JsonNode> topAgents(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "10") int n,
            ServerWebExchange exchange) {
        String bearer = ClawRuntimeClient.extractBearer(exchange);
        return Mono.fromCallable(
                () ->
                        clawClient.get(
                                "/api/admin/usage/top-agents?days=" + days + "&n=" + n, bearer));
    }

    @GetMapping("/users-rollup")
    public Mono<JsonNode> usersRollup(
            @RequestParam(defaultValue = "30") int days, ServerWebExchange exchange) {
        String bearer = ClawRuntimeClient.extractBearer(exchange);
        return Mono.fromCallable(
                () -> clawClient.get("/api/admin/usage/users-rollup?days=" + days, bearer));
    }

    @GetMapping("/agents-rollup")
    public Mono<JsonNode> agentsRollup(
            @RequestParam(defaultValue = "30") int days, ServerWebExchange exchange) {
        String bearer = ClawRuntimeClient.extractBearer(exchange);
        return Mono.fromCallable(
                () -> clawClient.get("/api/admin/usage/agents-rollup?days=" + days, bearer));
    }

    @GetMapping("/user/{userId}/daily")
    public Mono<JsonNode> userDaily(
            @PathVariable String userId,
            @RequestParam(defaultValue = "30") int days,
            ServerWebExchange exchange) {
        String bearer = ClawRuntimeClient.extractBearer(exchange);
        return Mono.fromCallable(
                () ->
                        clawClient.get(
                                "/api/admin/usage/user/"
                                        + URLEncoder.encode(userId, StandardCharsets.UTF_8)
                                        + "/daily?days="
                                        + days,
                                bearer));
    }
}
