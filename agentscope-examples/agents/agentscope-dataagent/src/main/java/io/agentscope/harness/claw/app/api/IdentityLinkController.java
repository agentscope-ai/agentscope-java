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
package io.agentscope.harness.claw.app.api;

import io.agentscope.harness.claw.app.identity.IdentityLinkStore;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * REST endpoints for identity-link management.
 *
 * <ul>
 *   <li>{@code GET    /api/user/identity-links} — list the caller's links
 *   <li>{@code POST   /api/user/identity-links} — add or update a link
 *   <li>{@code DELETE /api/user/identity-links/{channelId}} — remove a link
 * </ul>
 */
@RestController
public class IdentityLinkController {

    private final IdentityLinkStore store;

    public IdentityLinkController(IdentityLinkStore store) {
        this.store = store;
    }

    @GetMapping("/api/user/identity-links")
    public Mono<Map<String, String>> list(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> store.linksFor(userId));
    }

    @PostMapping("/api/user/identity-links")
    public Mono<Map<String, String>> add(@RequestBody LinkRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    validate(req);
                    store.link(userId, req.channelId(), req.externalId());
                    return store.linksFor(userId);
                });
    }

    @DeleteMapping("/api/user/identity-links/{channelId}")
    public Mono<Map<String, Object>> remove(@PathVariable String channelId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    boolean removed = store.unlink(userId, channelId);
                    return Map.of("removed", removed);
                });
    }

    private static void validate(LinkRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body required");
        }
        if (req.channelId() == null || req.channelId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channelId is required");
        }
        if (req.externalId() == null || req.externalId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "externalId is required");
        }
    }

    public record LinkRequest(String channelId, String externalId) {}
}
