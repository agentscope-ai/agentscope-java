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

import io.agentscope.builder.web.managed.EnvironmentDto;
import io.agentscope.builder.web.managed.EnvironmentService;
import io.agentscope.builder.web.managed.EnvironmentService.CreateEnvironmentRequest;
import io.agentscope.builder.web.managed.ResourceShareDto;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import io.agentscope.builder.web.share.ShareRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** REST controller for execution environment templates. */
@RestController
@RequestMapping("/api/environments")
public class EnvironmentController {

    private final EnvironmentService environmentService;

    public EnvironmentController(EnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    /** Lists environments owned by the authenticated user. */
    @GetMapping
    public Mono<List<EnvironmentDto>> list(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> environmentService.list(userId));
    }

    /** Returns a single environment. */
    @GetMapping("/{id}")
    public Mono<EnvironmentDto> get(@PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> environmentService.get(userId, id));
    }

    /** Creates an environment template. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<EnvironmentDto> create(
            @RequestBody CreateEnvironmentRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> environmentService.create(userId, req));
    }

    /** Archives an environment. */
    @PostMapping("/{id}/archive")
    public Mono<EnvironmentDto> archive(@PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> environmentService.archive(userId, id));
    }

    /** Deletes an environment when no active sessions reference it. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(() -> environmentService.delete(userId, id));
    }

    /** Rotates the environment worker API key; returns the new key once. */
    @PostMapping("/{id}/keys/rotate")
    public Mono<EnvironmentDto> rotateKey(@PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> environmentService.rotateKey(userId, id));
    }

    /** Lists share grants on an environment. Owner-only. */
    @GetMapping("/{id}/shares")
    public Mono<List<ResourceShareDto>> listShares(
            @PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> environmentService.listShares(userId, id));
    }

    /** Shares an environment with a user or the whole workspace. Owner-only. */
    @PostMapping("/{id}/shares")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ResourceShareDto> share(
            @PathVariable("id") String id, @RequestBody ShareRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () ->
                        environmentService.share(
                                userId,
                                id,
                                req.granteeType(),
                                req.granteeId(),
                                Tier.valueOf(req.tier())));
    }

    /** Revokes a share grant on an environment. Owner-only. */
    @DeleteMapping("/{id}/shares/{shareId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> unshare(
            @PathVariable("id") String id,
            @PathVariable("shareId") String shareId,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(() -> environmentService.unshare(userId, id, shareId));
    }
}
