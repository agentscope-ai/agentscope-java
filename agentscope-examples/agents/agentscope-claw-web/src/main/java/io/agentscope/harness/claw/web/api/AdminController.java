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
import io.agentscope.harness.claw.web.auth.UserStore;
import io.agentscope.harness.claw.web.runtime.ClawRuntimeClient;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Admin console observability proxy — delegates to agentscope-claw's read-only runtime endpoints.
 *
 * <ul>
 *   <li>{@code GET /api/admin/overview} — platform overview
 *   <li>{@code GET /api/admin/instances} — registered agent instances
 *   <li>{@code GET /api/admin/sessions} — active sessions
 * </ul>
 *
 * <p>All endpoints require {@code ROLE_ADMIN} (enforced by SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ClawRuntimeClient clawClient;
    private final UserStore userStore;

    public AdminController(ClawRuntimeClient clawClient, UserStore userStore) {
        this.clawClient = clawClient;
        this.userStore = userStore;
    }

    @GetMapping("/overview")
    public Mono<JsonNode> overview(ServerWebExchange exchange) {
        String bearer = ClawRuntimeClient.extractBearer(exchange);
        return Mono.fromCallable(() -> clawClient.get("/api/admin/runtime/overview", bearer));
    }

    @GetMapping("/instances")
    public Mono<JsonNode> instances(ServerWebExchange exchange) {
        String bearer = ClawRuntimeClient.extractBearer(exchange);
        return Mono.fromCallable(() -> clawClient.get("/api/admin/runtime/instances", bearer));
    }

    /**
     * Registered agents declared in {@code agentscope.json}, with display metadata. Proxied to
     * claw's public {@code /api/agents} endpoint so the admin console can render the agent picker.
     */
    @GetMapping("/agents")
    public Mono<JsonNode> agents(ServerWebExchange exchange) {
        String bearer = ClawRuntimeClient.extractBearer(exchange);
        return Mono.fromCallable(() -> clawClient.get("/api/agents", bearer));
    }

    /** Health/connectivity check — tells the admin UI whether claw is reachable. */
    @GetMapping("/status")
    public Mono<StatusView> status() {
        return Mono.fromCallable(
                () -> {
                    boolean reachable = clawClient.isClawReachable();
                    return new StatusView(clawClient.clawUrl(), reachable);
                });
    }

    /** Read-only user listing from the shared users.json written by agentscope-claw. */
    @GetMapping("/users")
    public Mono<List<UserStore.UserRecord>> listUsers() {
        return Mono.fromCallable(userStore::listAll);
    }

    public record StatusView(String clawUrl, boolean reachable) {}
}
