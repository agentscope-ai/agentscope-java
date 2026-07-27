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
package io.agentscope.builder.web.api;

import io.agentscope.builder.web.managed.DeploymentDto;
import io.agentscope.builder.web.managed.DeploymentService;
import io.agentscope.builder.web.managed.DeploymentService.CreateDeploymentRequest;
import io.agentscope.builder.web.managed.DeploymentService.UpdateDeploymentRequest;
import io.agentscope.builder.web.share.AgentAccessGuard;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller for deployments (agent + version + environment + trigger).
 *
 * <ul>
 *   <li>{@code GET/POST /api/deployments} — list / create, authenticated.
 *   <li>{@code GET/PATCH/DELETE /api/deployments/{id}} — read, update (enable/disable, cron),
 *       delete; authenticated.
 *   <li>{@code POST /api/deployments/{id}/archive} — soft-delete and disable.
 *   <li>{@code POST /api/deployments/{id}/run} — manual trigger; authenticated.
 *   <li>{@code POST /api/deployments/webhook/{token}} — webhook trigger; public, token-gated (see
 *       {@code SecurityConfig} for the permitAll exemption).
 * </ul>
 */
@RestController
@RequestMapping("/api/deployments")
public class DeploymentController {

    private final DeploymentService deploymentService;
    private final AgentAccessGuard guard;

    public DeploymentController(DeploymentService deploymentService, AgentAccessGuard guard) {
        this.deploymentService = deploymentService;
        this.guard = guard;
    }

    /** Lists deployments for the authenticated user. */
    @GetMapping
    public Mono<List<DeploymentDto>> list(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> deploymentService.list(userId));
    }

    /** Creates a deployment. Requires RUN tier on the referenced agent. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<DeploymentDto> create(
            @RequestBody CreateDeploymentRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, req.agentId(), Tier.RUN);
                    return deploymentService.create(userId, req);
                });
    }

    /** Returns a single deployment. */
    @GetMapping("/{id}")
    public Mono<DeploymentDto> get(@PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> deploymentService.get(userId, id));
    }

    /** Updates name, enabled flag, cron expression, environment, or pinned version. */
    @PatchMapping("/{id}")
    public Mono<DeploymentDto> update(
            @PathVariable("id") String id,
            @RequestBody UpdateDeploymentRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> deploymentService.update(userId, id, req));
    }

    /** Archives a deployment (soft delete; disables further scheduled firing). */
    @PostMapping("/{id}/archive")
    public Mono<DeploymentDto> archive(@PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> deploymentService.archive(userId, id));
    }

    /** Hard-deletes a deployment. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(() -> deploymentService.delete(userId, id));
    }

    /** Manually fires a deployment: creates a fresh managed session and runs one turn. */
    @PostMapping("/{id}/run")
    public Mono<DeploymentDto> run(
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> deploymentService.run(userId, id, body));
    }

    /**
     * Fires a deployment via its webhook token. No JWT is required — the token itself is the
     * credential — so this path is exempted from authentication in {@code SecurityConfig}.
     */
    @PostMapping("/webhook/{token}")
    public Mono<DeploymentDto> webhook(
            @PathVariable("token") String token,
            @RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> deploymentService.runByWebhookToken(token, body));
    }
}
