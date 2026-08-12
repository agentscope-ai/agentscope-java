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

import io.agentscope.builder.web.coord.CoordinationStore;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Coordinates human-in-the-loop tool confirmations. Tickets are stored in {@link
 * CoordinationStore} so any Brain replica can resolve allow/deny; local futures complete when the
 * shared ticket is resolved (polled while awaiting).
 */
@Component
public class ToolConfirmationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ToolConfirmationCoordinator.class);

    private final SessionEventLog eventLog;
    private final DataSessionService sessionService;
    private final CoordinationStore coordinationStore;
    private final long timeoutMs;
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> localWaiters =
            new ConcurrentHashMap<>();

    public ToolConfirmationCoordinator(
            SessionEventLog eventLog,
            DataSessionService sessionService,
            CoordinationStore coordinationStore,
            @Value("${builder.tool-confirmation.timeout-ms:3600000}") long timeoutMs) {
        this.eventLog = eventLog;
        this.sessionService = sessionService;
        this.coordinationStore = coordinationStore;
        this.timeoutMs = timeoutMs;
    }

    public CompletableFuture<Boolean> requestConfirmation(
            String sessionId, String toolUseId, String toolName, Map<String, Object> input) {
        long now = System.currentTimeMillis();
        String inputJson = null;
        if (input != null) {
            try {
                inputJson = JsonUtils.getJsonCodec().toJson(input);
            } catch (Exception ex) {
                inputJson = String.valueOf(input);
            }
        }
        String ownerId = null;
        try {
            ManagedSessionDto session = sessionService.requireById(sessionId);
            ownerId = session.ownerId();
        } catch (Exception ex) {
            log.debug(
                    "Could not resolve session owner for HITL ticket {}: {}",
                    sessionId,
                    ex.getMessage());
        }
        coordinationStore.putHitlTicket(
                new CoordinationStore.HitlTicket(
                        toolUseId,
                        sessionId,
                        ownerId,
                        toolName,
                        inputJson,
                        null,
                        null,
                        now,
                        now + timeoutMs));

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        localWaiters.put(toolUseId, future);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolUseId", toolUseId);
        payload.put("toolName", toolName);
        if (input != null) {
            payload.put("input", input);
        }
        eventLog.append(sessionId, "session.requires_action", payload);
        if (ownerId != null) {
            sessionService.updateStatus(
                    ownerId, sessionId, DataSessionService.STATUS_REQUIRES_ACTION, payload);
        }

        // Poll shared ticket so resolution on another replica wakes this waiter.
        Thread poller =
                new Thread(() -> pollUntilResolved(toolUseId, future), "hitl-poll-" + toolUseId);
        poller.setDaemon(true);
        poller.start();

        future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete(
                        (allowed, error) -> {
                            localWaiters.remove(toolUseId);
                            if (error instanceof TimeoutException) {
                                log.info(
                                        "Tool confirmation timed out: session={}, toolUseId={}",
                                        sessionId,
                                        toolUseId);
                                coordinationStore.resolveHitlTicket(toolUseId, false, "timed_out");
                            }
                        });
        return future;
    }

    private void pollUntilResolved(String toolUseId, CompletableFuture<Boolean> future) {
        while (!future.isDone()) {
            Optional<CoordinationStore.HitlTicket> ticket =
                    coordinationStore.getHitlTicket(toolUseId);
            if (ticket.isPresent() && ticket.get().resolvedAllow() != null) {
                future.complete(Boolean.TRUE.equals(ticket.get().resolvedAllow()));
                return;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public boolean resolve(String toolUseId, boolean allow, String denyMessage) {
        Optional<CoordinationStore.HitlTicket> updated =
                coordinationStore.resolveHitlTicket(toolUseId, allow, denyMessage);
        if (updated.isEmpty()) {
            return false;
        }
        CompletableFuture<Boolean> local = localWaiters.remove(toolUseId);
        if (local != null) {
            local.complete(allow);
        }
        if (!allow && denyMessage != null) {
            log.debug("Tool confirmation denied for {}: {}", toolUseId, denyMessage);
        }
        return true;
    }

    public boolean awaitConfirmation(
            String sessionId, String toolUseId, String toolName, Map<String, Object> input) {
        try {
            return requestConfirmation(sessionId, toolUseId, toolName, input)
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            localWaiters.remove(toolUseId);
            log.debug("Tool confirmation failed for {}: {}", toolUseId, ex.getMessage());
            return false;
        }
    }
}
