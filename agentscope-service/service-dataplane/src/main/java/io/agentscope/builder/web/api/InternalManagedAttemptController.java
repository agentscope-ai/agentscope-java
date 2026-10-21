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
import io.agentscope.builder.web.managed.SessionTurnRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Fenced control-plane abort delivery for one physical Managed AgentTask Attempt. */
@RestController
@RequestMapping("/api/internal/sessions")
public class InternalManagedAttemptController {

    private final SessionTurnRunner turnRunner;

    public InternalManagedAttemptController(SessionTurnRunner turnRunner) {
        this.turnRunner = turnRunner;
    }

    @PostMapping("/{sessionId}/managed-attempts/{attemptId}/abort")
    public ResponseEntity<Void> abort(
            @PathVariable String sessionId,
            @PathVariable String attemptId,
            @RequestBody AbortRequest request,
            Authentication authentication) {
        requireInternal(authentication);
        if (request == null
                || !hasText(request.agentTaskId())
                || !hasText(request.attemptId())
                || !attemptId.equals(request.attemptId())
                || request.dispatchGeneration() <= 0
                || !hasText(request.turnId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "agentTaskId, matching attemptId, positive dispatchGeneration, and turnId are"
                            + " required");
        }
        turnRunner.abortManagedAttempt(
                sessionId,
                request.agentTaskId(),
                request.attemptId(),
                request.dispatchGeneration(),
                request.turnId(),
                request.reason());
        // Both local delivery and a safely queued cross-replica/stale request are idempotent.
        return ResponseEntity.noContent().build();
    }

    private static void requireInternal(Authentication authentication) {
        if (authentication == null
                || authentication.getAuthorities().stream()
                        .noneMatch(
                                authority ->
                                        InternalTokenAuthFilter.ROLE_INTERNAL.equals(
                                                authority.getAuthority()))) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Internal control-plane authentication is required");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record AbortRequest(
            String agentTaskId,
            String attemptId,
            long dispatchGeneration,
            String turnId,
            String reason) {}
}
