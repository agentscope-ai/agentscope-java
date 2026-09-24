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
import io.agentscope.builder.web.toolbus.ToolConfirmationCoordinator;
import io.agentscope.builder.web.toolbus.ToolConfirmationCoordinator.DecisionResult;
import io.agentscope.builder.web.toolbus.ToolConfirmationCoordinator.ManagedDecision;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Internal control-plane callback that resolves one fully fenced Managed AgentTask HITL ticket. */
@RestController
@RequestMapping("/api/internal/sessions")
public class InternalToolConfirmationController {

    private final ToolConfirmationCoordinator coordinator;

    public InternalToolConfirmationController(ToolConfirmationCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping("/{sessionId}/tool-confirmations/{toolUseId}/decision")
    public Mono<ResponseEntity<Void>> decide(
            @PathVariable String sessionId,
            @PathVariable String toolUseId,
            @RequestBody DecisionRequest request,
            Authentication authentication) {
        if (authentication == null
                || authentication.getAuthorities().stream()
                        .noneMatch(
                                authority ->
                                        InternalTokenAuthFilter.ROLE_INTERNAL.equals(
                                                authority.getAuthority()))) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Internal control-plane authentication is required");
        }
        validate(request);
        boolean allow = "approved".equals(request.status());
        if (allow != request.allow()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "status and allow must describe the same decision");
        }
        ManagedDecision decision =
                new ManagedDecision(
                        sessionId,
                        toolUseId,
                        request.approvalId(),
                        request.agentTaskId(),
                        request.attemptId(),
                        request.dispatchGeneration(),
                        request.turnId(),
                        request.status(),
                        request.decisionVersion(),
                        allow,
                        request.denyMessage());
        return Mono.<ResponseEntity<Void>>fromCallable(
                        () -> {
                            DecisionResult result = coordinator.resolveManaged(decision);
                            return switch (result) {
                                case RESOLVED, IDEMPOTENT ->
                                        ResponseEntity.<Void>noContent().build();
                                case NOT_FOUND ->
                                        throw new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Tool confirmation ticket not found");
                                case EXPIRED ->
                                        throw new ResponseStatusException(
                                                HttpStatus.GONE,
                                                "Tool confirmation ticket has expired");
                                case CONTINUATION_LOST ->
                                        throw new ResponseStatusException(
                                                HttpStatus.GONE,
                                                "The managed turn continuation is no longer"
                                                        + " available");
                                case OWNER_UNAVAILABLE ->
                                        throw new ResponseStatusException(
                                                HttpStatus.SERVICE_UNAVAILABLE,
                                                "The managed turn continuation owner has not"
                                                        + " acknowledged the decision yet");
                                default ->
                                        throw new ResponseStatusException(
                                                HttpStatus.CONFLICT,
                                                "Tool confirmation fence is stale or conflicts"
                                                        + " with the recorded decision");
                            };
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static void validate(DecisionRequest request) {
        if (request == null
                || !hasText(request.approvalId())
                || request.decisionVersion() <= 0
                || !hasText(request.agentTaskId())
                || !hasText(request.attemptId())
                || request.dispatchGeneration() <= 0
                || !hasText(request.turnId())
                || (!("approved".equals(request.status()))
                        && !("rejected".equals(request.status()))
                        && !("cancelled".equals(request.status())))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "approvalId, positive decisionVersion, agentTaskId, attemptId, positive"
                            + " dispatchGeneration, turnId, and status are required");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Versioned approval decision emitted from the control-plane outbox. */
    public record DecisionRequest(
            String approvalId,
            long decisionVersion,
            String status,
            boolean allow,
            String denyMessage,
            String agentTaskId,
            String attemptId,
            long dispatchGeneration,
            String turnId) {}
}
