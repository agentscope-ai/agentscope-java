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

import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import io.agentscope.builder.web.managed.SessionNativeLogService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Terminal projection barrier used by the authenticated control plane. */
@RestController
@RequestMapping("/api/internal/sessions")
public class InternalSessionMirrorController {
    private final SessionNativeLogService logs;

    public InternalSessionMirrorController(SessionNativeLogService logs) {
        this.logs = logs;
    }

    @GetMapping("/{sessionId}/event-mirror-status")
    public Mono<Map<String, Object>> status(
            @PathVariable String sessionId,
            @RequestParam("attempt_id") String attempt,
            Authentication authentication) {
        if (authentication == null
                || authentication.getAuthorities().stream()
                        .noneMatch(
                                authority ->
                                        InternalTokenAuthFilter.ROLE_INTERNAL.equals(
                                                authority.getAuthority())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal token required");
        if (attempt == null || attempt.isBlank() || attempt.length() > 64)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "attempt_id is required");
        return Mono.fromCallable(() -> logs.mirrorStatus(sessionId, attempt))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
