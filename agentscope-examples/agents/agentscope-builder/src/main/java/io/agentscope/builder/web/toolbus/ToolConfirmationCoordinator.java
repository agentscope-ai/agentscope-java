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
package io.agentscope.builder.web.toolbus;

import io.agentscope.builder.web.managed.ManagedSessionService;
import io.agentscope.builder.web.managed.SessionEventLog;
import io.agentscope.builder.web.persistence.jpa.ManagedSessionEntityRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Coordinates human-in-the-loop tool confirmations keyed by tool-use id. Publishes
 * {@code session.requires_action} events and resolves pending futures when the user responds.
 */
@Component
public class ToolConfirmationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ToolConfirmationCoordinator.class);

    private final SessionEventLog eventLog;
    private final ManagedSessionService sessionService;
    private final ManagedSessionEntityRepository sessionRepository;
    private final long timeoutMs;
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pending =
            new ConcurrentHashMap<>();

    public ToolConfirmationCoordinator(
            SessionEventLog eventLog,
            ManagedSessionService sessionService,
            ManagedSessionEntityRepository sessionRepository,
            @Value("${builder.tool-confirmation.timeout-ms:3600000}") long timeoutMs) {
        this.eventLog = eventLog;
        this.sessionService = sessionService;
        this.sessionRepository = sessionRepository;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Requests user confirmation for a tool call. Returns a future that completes with {@code
     * true} when allowed or {@code false} when denied or timed out.
     */
    public CompletableFuture<Boolean> requestConfirmation(
            String sessionId, String toolUseId, String toolName, Map<String, Object> input) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(toolUseId, future);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolUseId", toolUseId);
        payload.put("toolName", toolName);
        if (input != null) {
            payload.put("input", input);
        }
        eventLog.append(sessionId, "session.requires_action", payload);
        sessionRepository
                .findBySessionId(sessionId)
                .ifPresent(
                        session ->
                                sessionService.updateStatus(
                                        session.getOwnerId(),
                                        sessionId,
                                        ManagedSessionService.STATUS_REQUIRES_ACTION,
                                        payload));
        future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete(
                        (allowed, error) -> {
                            pending.remove(toolUseId);
                            if (error instanceof TimeoutException) {
                                log.info(
                                        "Tool confirmation timed out: session={}, toolUseId={}",
                                        sessionId,
                                        toolUseId);
                            }
                        });
        return future;
    }

    /**
     * Resolves a pending confirmation.
     *
     * @param toolUseId tool-use identifier from the agent
     * @param allow whether execution is allowed
     * @param denyMessage optional denial reason
     * @return {@code true} when a pending future was found and completed
     */
    public boolean resolve(String toolUseId, boolean allow, String denyMessage) {
        CompletableFuture<Boolean> future = pending.remove(toolUseId);
        if (future == null) {
            return false;
        }
        if (!allow && denyMessage != null) {
            log.debug("Tool confirmation denied for {}: {}", toolUseId, denyMessage);
        }
        future.complete(allow);
        return true;
    }

    /** Waits for confirmation up to the configured timeout. */
    public boolean awaitConfirmation(
            String sessionId, String toolUseId, String toolName, Map<String, Object> input) {
        try {
            return requestConfirmation(sessionId, toolUseId, toolName, input)
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            pending.remove(toolUseId);
            log.debug("Tool confirmation failed for {}: {}", toolUseId, ex.getMessage());
            return false;
        }
    }
}
