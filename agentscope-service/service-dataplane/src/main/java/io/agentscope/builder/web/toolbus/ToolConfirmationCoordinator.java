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

import io.agentscope.builder.control.ControlPlaneClient;
import io.agentscope.builder.control.ControlPlaneClient.ManagedExecutionScope;
import io.agentscope.builder.web.coord.CoordinationStore;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.ManagedTurnContext;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.managed.SessionEventTypes;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.core.util.JsonUtils;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Coordinates human-in-the-loop tool confirmations.
 *
 * <p>The durable ticket is the decision authority. A Managed AgentTask ticket captures the full
 * Attempt fence and can only be resolved by the control-plane callback carrying that same fence.
 * Personal chat confirmations remain user-addressable, but are also scoped by session id. Local
 * futures are merely continuations and poll the shared ticket so any replica can deliver a
 * decision.
 */
@Component
public class ToolConfirmationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ToolConfirmationCoordinator.class);
    private static final long UPLOAD_RETRY_MS = 1_000L;
    private static final UUID UUID_NAMESPACE_URL =
            UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    private final SessionEventLog eventLog;
    private final DataSessionService sessionService;
    private final CoordinationStore coordinationStore;
    private final ControlPlaneClient controlPlaneClient;
    private final long timeoutMs;
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> localWaiters =
            new ConcurrentHashMap<>();

    public ToolConfirmationCoordinator(
            SessionEventLog eventLog,
            DataSessionService sessionService,
            CoordinationStore coordinationStore,
            ControlPlaneClient controlPlaneClient,
            @Value("${builder.tool-confirmation.timeout-ms:3600000}") long timeoutMs) {
        this.eventLog = eventLog;
        this.sessionService = sessionService;
        this.coordinationStore = coordinationStore;
        this.controlPlaneClient = controlPlaneClient;
        this.timeoutMs = timeoutMs;
    }

    public CompletableFuture<Boolean> requestConfirmation(
            String sessionId, String toolUseId, String toolName, Map<String, Object> input) {
        return requestConfirmation(sessionId, toolUseId, toolName, input, null);
    }

    /**
     * Creates a confirmation from the immutable turn context carried by RuntimeContext. A managed
     * request must never rediscover its scope from mutable session-global state.
     */
    public CompletableFuture<Boolean> requestConfirmation(
            String sessionId,
            String toolUseId,
            String toolName,
            Map<String, Object> input,
            ManagedTurnContext turnContext) {
        long now = System.currentTimeMillis();
        long expiresAt = now + timeoutMs;
        String ownerId = resolveOwner(sessionId);
        ManagedExecutionScope scope = turnContext != null ? turnContext.scope() : null;
        boolean managed = turnContext != null;
        if (managed && !completeManagedScope(scope)) {
            throw new IllegalStateException(
                    "Managed tool confirmation requires a complete immutable execution scope");
        }
        String continuationLeaseId = managed ? turnContext.continuationLeaseId() : null;
        if (managed) {
            if (continuationLeaseId == null || continuationLeaseId.isBlank()) {
                throw new IllegalStateException(
                        "Managed tool confirmation requires a captured continuation lease");
            }
            String capturedLeaseId = continuationLeaseId;
            boolean exactLeaseActive =
                    coordinationStore
                            .getTurnLease(sessionId)
                            .map(CoordinationStore.LeaseHandle::instanceId)
                            .filter(capturedLeaseId::equals)
                            .isPresent();
            if (!exactLeaseActive) {
                throw new IllegalStateException(
                        "Managed tool confirmation continuation lease is no longer active");
            }
        }
        CoordinationStore.HitlTicket requestedTicket =
                new CoordinationStore.HitlTicket(
                        toolUseId,
                        sessionId,
                        ownerId,
                        managed
                                ? managedApprovalId(scope, sessionId, toolUseId)
                                : UUID.randomUUID().toString(),
                        managed ? scope.agentTaskId() : null,
                        managed ? scope.attemptId() : null,
                        managed ? scope.dispatchGeneration() : 0L,
                        managed ? scope.turnId() : null,
                        continuationLeaseId,
                        toolName,
                        serializeInput(input),
                        null,
                        0L,
                        null,
                        null,
                        now,
                        expiresAt,
                        null,
                        false);
        CoordinationStore.HitlTicket ticket = coordinationStore.putHitlTicket(requestedTicket);

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        localWaiters.put(waiterKey(ticket), future);

        Map<String, Object> payload = ticketPayload(ticket);
        if (input != null) {
            payload.put("inputPreview", redactPreview(input, 0));
        }
        if (ticket.inputJson() != null) {
            payload.put("inputSha256", sha256(ticket.inputJson()));
        }
        SessionEventDto requestEvent =
                managed
                        ? eventLog.appendLocal(
                                sessionId, SessionEventTypes.SESSION_REQUIRES_ACTION, payload, null)
                        : eventLog.append(
                                sessionId, SessionEventTypes.SESSION_REQUIRES_ACTION, payload);
        if (managed) {
            startReliableUpload(requestEvent, scope, future);
        }

        Thread poller =
                new Thread(() -> pollUntilResolved(ticket, future), "hitl-poll-" + toolUseId);
        poller.setDaemon(true);
        poller.start();

        Thread expiry =
                new Thread(() -> expireAtDeadline(ticket, future), "hitl-expiry-" + toolUseId);
        expiry.setDaemon(true);
        expiry.start();
        if (ownerId != null) {
            try {
                sessionService.updateStatus(
                        ownerId,
                        sessionId,
                        DataSessionService.STATUS_REQUIRES_ACTION,
                        payload,
                        scope);
            } catch (RuntimeException ex) {
                // The durable request uploader/projector remains authoritative. Do not fail open
                // after the ticket and request event already exist.
                log.warn(
                        "Could not project HITL requires_action status: session={}, error={}",
                        sessionId,
                        ex.getMessage());
            }
        }
        return future;
    }

    /** Removes all raw confirmation inputs when their owning session is deleted. */
    public void deleteSessionTickets(String sessionId) {
        cancelSession(sessionId, "session_deleted");
    }

    /** Purges durable tickets and releases waiters owned by an invalidated/interrupted turn. */
    public void cancelSession(String sessionId, String reason) {
        try {
            // A session has at most one active physical turn. Once that turn is invalidated no
            // ticket from it may accept a late callback or collide with a fresh Attempt reusing a
            // toolUseId. Persisted session events remain the audit record; raw tool input does not.
            coordinationStore.deleteHitlTicketsBySession(sessionId);
        } catch (RuntimeException ex) {
            // Still unblock the local turn. resolveManaged additionally requires an active shared
            // turn lease, so a retained row cannot authorize execution after cancellation.
            log.warn(
                    "Could not purge invalidated HITL tickets: session={}, error={}",
                    sessionId,
                    ex.getMessage());
        }
        String prefix = sessionId + "\u0000";
        localWaiters.forEach(
                (key, future) -> {
                    if (key.startsWith(prefix) && localWaiters.remove(key, future)) {
                        future.complete(false);
                    }
                });
        log.debug("Cancelled local HITL waiters for session {}: {}", sessionId, reason);
    }

    /** Resolves a personal-chat confirmation after the HTTP layer authenticated session owner. */
    public DecisionResult resolvePersonal(
            String sessionId, String toolUseId, boolean allow, String denyMessage) {
        Optional<CoordinationStore.HitlTicket> found =
                coordinationStore.getHitlTicket(sessionId, toolUseId);
        if (found.isEmpty()) {
            return DecisionResult.NOT_FOUND;
        }
        CoordinationStore.HitlTicket ticket = found.get();
        if (ticket.managedTask()) {
            return DecisionResult.CONTROL_PLANE_REQUIRED;
        }
        return resolve(
                ticket,
                allow ? "approved" : "rejected",
                0L,
                allow,
                denyMessage,
                "personal_chat",
                System.currentTimeMillis());
    }

    /** Resolves a Managed AgentTask confirmation from the control plane with full fencing. */
    public DecisionResult resolveManaged(ManagedDecision decision) {
        Optional<CoordinationStore.HitlTicket> found =
                coordinationStore.getHitlTicket(fence(decision));
        if (found.isEmpty()) {
            found = coordinationStore.getHitlTicket(decision.sessionId(), decision.toolUseId());
            if (found.isEmpty()) {
                return DecisionResult.NOT_FOUND;
            }
        }
        CoordinationStore.HitlTicket ticket = found.get();
        if (!ticket.managedTask() || !matches(ticket, decision)) {
            return DecisionResult.FENCE_MISMATCH;
        }
        // A durable ticket can outlive the JVM continuation that created it. Before accepting a
        // first delivery (or retrying side effects that never reached continuationReady), require
        // the shared turn lease to still be alive. A fully released decision remains safely
        // idempotent after the turn has completed and its lease has been removed.
        if (!ticket.continuationReady()) {
            Optional<CoordinationStore.LeaseHandle> currentLease =
                    coordinationStore.getTurnLease(ticket.sessionId());
            if (currentLease.isEmpty()
                    || !same(ticket.continuationLeaseId(), currentLease.get().instanceId())) {
                return DecisionResult.CONTINUATION_LOST;
            }
        }
        Optional<CoordinationStore.HitlResolution> resolved =
                coordinationStore.resolveHitlTicket(
                        fence(ticket),
                        decision.status(),
                        decision.decisionVersion(),
                        decision.allow(),
                        decision.reason(),
                        System.currentTimeMillis());
        if (resolved.isEmpty()) {
            Optional<CoordinationStore.HitlTicket> current =
                    coordinationStore.getHitlTicket(fence(ticket));
            return current.isPresent() && current.get().resolvedAllow() != null
                    ? DecisionResult.CONFLICT
                    : DecisionResult.FENCE_MISMATCH;
        }
        CoordinationStore.HitlResolution resolution = resolved.get();
        if (resolution.ticket().continuationReady()) {
            completeLocal(
                    resolution.ticket(), Boolean.TRUE.equals(resolution.ticket().resolvedAllow()));
            return resolution.changed() ? DecisionResult.RESOLVED : DecisionResult.IDEMPOTENT;
        }
        CompletableFuture<Boolean> ownerWaiter = localWaiters.get(waiterKey(resolution.ticket()));
        if (ownerWaiter == null) {
            // The callback may land on any replica. Persist the decision here, but only the JVM
            // holding the actual continuation may ACK/release it. Its poller will finish the
            // side-effects; the CP outbox retries until continuationReady becomes visible.
            return DecisionResult.OWNER_UNAVAILABLE;
        }
        DecisionResult ownerResult = finishManagedOnOwner(resolution.ticket(), ownerWaiter);
        if (ownerResult != null) {
            return ownerResult;
        }
        return resolution.changed() ? DecisionResult.RESOLVED : DecisionResult.IDEMPOTENT;
    }

    /** Returns the stable persisted decision event after a successful resolve. */
    public Optional<SessionEventDto> resolutionEvent(String sessionId, String toolUseId) {
        return coordinationStore
                .getHitlTicket(sessionId, toolUseId)
                .filter(ticket -> ticket.resolvedAllow() != null)
                .map(
                        ticket ->
                                eventLog.appendIdempotent(
                                        sessionId,
                                        SessionEventTypes.USER_TOOL_CONFIRMATION,
                                        resolutionPayload(
                                                ticket,
                                                ticket.managedTask()
                                                        ? "control_plane"
                                                        : "personal_chat"),
                                        resolutionEventId(ticket)));
    }

    /** Expires one pending ticket and resumes a live continuation as a denied tool call. */
    public DecisionResult expire(String sessionId, String toolUseId, long now) {
        Optional<CoordinationStore.HitlTicket> found =
                coordinationStore.getHitlTicket(sessionId, toolUseId);
        if (found.isEmpty()) {
            return DecisionResult.NOT_FOUND;
        }
        // Managed AgentTask timeout/cancellation is an Approval state transition owned by CP.
        // Local clocks never race a still-pending human decision into a different audit fact.
        if (found.get().managedTask()) {
            return DecisionResult.CONTROL_PLANE_REQUIRED;
        }
        Optional<CoordinationStore.HitlResolution> resolved =
                coordinationStore.expireHitlTicket(sessionId, toolUseId, now);
        if (resolved.isEmpty()) {
            return coordinationStore.getHitlTicket(sessionId, toolUseId).isPresent()
                    ? DecisionResult.NOT_YET_EXPIRED
                    : DecisionResult.NOT_FOUND;
        }
        CoordinationStore.HitlResolution resolution = resolved.get();
        finishResolution(resolution.ticket(), "timeout");
        return resolution.changed() ? DecisionResult.RESOLVED : DecisionResult.IDEMPOTENT;
    }

    public boolean awaitConfirmation(
            String sessionId, String toolUseId, String toolName, Map<String, Object> input) {
        return awaitConfirmation(sessionId, toolUseId, toolName, input, null);
    }

    public boolean awaitConfirmation(
            String sessionId,
            String toolUseId,
            String toolName,
            Map<String, Object> input,
            ManagedTurnContext turnContext) {
        CompletableFuture<Boolean> future = null;
        try {
            future = requestConfirmation(sessionId, toolUseId, toolName, input, turnContext);
            return future.get();
        } catch (Exception ex) {
            if (future != null) {
                future.cancel(true);
            }
            CompletableFuture<Boolean> failedFuture = future;
            localWaiters.entrySet().removeIf(entry -> entry.getValue() == failedFuture);
            log.debug("Tool confirmation failed for {}: {}", toolUseId, ex.getMessage());
            return false;
        }
    }

    /** Waits for a decision and preserves the human rejection reason for the agent context. */
    public ConfirmationDecision awaitDecision(
            String sessionId, String toolUseId, String toolName, Map<String, Object> input) {
        return awaitDecision(sessionId, toolUseId, toolName, input, null);
    }

    public ConfirmationDecision awaitDecision(
            String sessionId,
            String toolUseId,
            String toolName,
            Map<String, Object> input,
            ManagedTurnContext turnContext) {
        boolean allow = awaitConfirmation(sessionId, toolUseId, toolName, input, turnContext);
        String reason =
                allow
                        ? null
                        : findTicket(sessionId, toolUseId, turnContext)
                                .map(CoordinationStore.HitlTicket::denyMessage)
                                .filter(value -> !value.isBlank())
                                .map(ToolConfirmationCoordinator::safeReason)
                                .orElse(null);
        return new ConfirmationDecision(allow, reason);
    }

    private DecisionResult resolve(
            CoordinationStore.HitlTicket ticket,
            String resolutionStatus,
            long decisionVersion,
            boolean allow,
            String denyMessage,
            String source,
            long now) {
        Optional<CoordinationStore.HitlResolution> resolved =
                coordinationStore.resolveHitlTicket(
                        fence(ticket), resolutionStatus, decisionVersion, allow, denyMessage, now);
        if (resolved.isEmpty()) {
            Optional<CoordinationStore.HitlTicket> current =
                    ticket.managedTask()
                            ? coordinationStore.getHitlTicket(fence(ticket))
                            : coordinationStore.getHitlTicket(
                                    ticket.sessionId(), ticket.toolUseId());
            if (current.isEmpty()) {
                return DecisionResult.NOT_FOUND;
            }
            if (current.get().resolvedAllow() != null) {
                return DecisionResult.CONFLICT;
            }
            return current.get().expiresAt() <= now
                    ? DecisionResult.EXPIRED
                    : DecisionResult.FENCE_MISMATCH;
        }
        CoordinationStore.HitlResolution resolution = resolved.get();
        finishResolution(resolution.ticket(), source);
        return resolution.changed() ? DecisionResult.RESOLVED : DecisionResult.IDEMPOTENT;
    }

    private void finishResolution(CoordinationStore.HitlTicket ticket, String source) {
        Map<String, Object> payload = resolutionPayload(ticket, source);
        SessionEventDto resolutionEvent =
                ticket.managedTask()
                        ? eventLog.appendIdempotentLocal(
                                ticket.sessionId(),
                                SessionEventTypes.USER_TOOL_CONFIRMATION,
                                payload,
                                resolutionEventId(ticket))
                        : eventLog.appendIdempotent(
                                ticket.sessionId(),
                                SessionEventTypes.USER_TOOL_CONFIRMATION,
                                payload,
                                resolutionEventId(ticket));
        if (ticket.managedTask()) {
            controlPlaneClient.appendSessionEvent(resolutionEvent, managedScope(ticket));
        }
        if (ticket.ownerId() != null) {
            sessionService.updateStatus(
                    ticket.ownerId(),
                    ticket.sessionId(),
                    DataSessionService.STATUS_RUNNING,
                    null,
                    ticket.managedTask() ? managedScope(ticket) : null);
        }
        if (!coordinationStore.markHitlContinuationReady(fence(ticket))) {
            throw new IllegalStateException("HITL ticket changed before continuation release");
        }
        completeLocal(ticket, Boolean.TRUE.equals(ticket.resolvedAllow()));
    }

    private void pollUntilResolved(
            CoordinationStore.HitlTicket expected, CompletableFuture<Boolean> future) {
        while (!future.isDone()) {
            Optional<CoordinationStore.HitlTicket> ticket =
                    expected.managedTask()
                            ? coordinationStore.getHitlTicket(fence(expected))
                            : coordinationStore.getHitlTicket(
                                    expected.sessionId(), expected.toolUseId());
            if (ticket.isPresent() && ticket.get().resolvedAllow() != null) {
                if (ticket.get().continuationReady()) {
                    future.complete(Boolean.TRUE.equals(ticket.get().resolvedAllow()));
                    return;
                }
                if (ticket.get().managedTask()) {
                    try {
                        finishManagedOnOwner(ticket.get(), future);
                    } catch (RuntimeException ex) {
                        log.warn(
                                "Managed HITL owner could not finish decision; retrying:"
                                        + " session={}, approvalId={}, error={}",
                                ticket.get().sessionId(),
                                ticket.get().approvalId(),
                                ex.getMessage());
                    }
                }
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** @return a retry/stale result, or {@code null} after the exact continuation was released. */
    private DecisionResult finishManagedOnOwner(
            CoordinationStore.HitlTicket ticket, CompletableFuture<Boolean> ownerWaiter) {
        synchronized (ownerWaiter) {
            if (ownerWaiter.isDone() || localWaiters.get(waiterKey(ticket)) != ownerWaiter) {
                return DecisionResult.OWNER_UNAVAILABLE;
            }
            Optional<CoordinationStore.LeaseHandle> lease =
                    coordinationStore.getTurnLease(ticket.sessionId());
            if (lease.isEmpty() || !same(ticket.continuationLeaseId(), lease.get().instanceId())) {
                return DecisionResult.CONTINUATION_LOST;
            }
            Optional<CoordinationStore.HitlTicket> current =
                    coordinationStore.getHitlTicket(fence(ticket));
            if (current.isEmpty() || current.get().resolvedAllow() == null) {
                return DecisionResult.OWNER_UNAVAILABLE;
            }
            if (!current.get().continuationReady()) {
                finishResolution(current.get(), "control_plane");
            } else {
                completeLocal(current.get(), Boolean.TRUE.equals(current.get().resolvedAllow()));
            }
            return null;
        }
    }

    private void startReliableUpload(
            SessionEventDto event, ManagedExecutionScope scope, CompletableFuture<Boolean> future) {
        Thread uploader =
                new Thread(
                        () -> {
                            // Continue past expiresAt. If CP was unavailable for the whole human
                            // window it still needs this same idempotent request so its
                            // authoritative
                            // expiry sweep can create-and-cancel the Approval and release the turn.
                            while (!future.isDone()) {
                                try {
                                    // The ordinary mirror may already have succeeded. CP's source
                                    // key makes this explicit retry idempotent.
                                    controlPlaneClient.appendSessionEvent(event, scope);
                                    return;
                                } catch (RuntimeException ex) {
                                    log.warn(
                                            "HITL request upload failed; retrying: session={},"
                                                    + " eventId={}, error={}",
                                            event.sessionId(),
                                            event.id(),
                                            ex.getMessage());
                                }
                                try {
                                    Thread.sleep(UPLOAD_RETRY_MS);
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                        },
                        "hitl-upload-" + event.id());
        uploader.setDaemon(true);
        uploader.start();
    }

    private void expireAtDeadline(
            CoordinationStore.HitlTicket ticket, CompletableFuture<Boolean> future) {
        long remaining = ticket.expiresAt() - System.currentTimeMillis();
        if (remaining > 0) {
            try {
                Thread.sleep(remaining);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!future.isDone()) {
            try {
                if (!ticket.managedTask()) {
                    expire(ticket.sessionId(), ticket.toolUseId(), System.currentTimeMillis());
                }
            } catch (RuntimeException ex) {
                // The durable reconciler retries this path. Never release the continuation before
                // the fenced resolution event has reached the control plane.
                log.warn(
                        "HITL expiry delivery failed: session={}, toolUseId={}, error={}",
                        ticket.sessionId(),
                        ticket.toolUseId(),
                        ex.getMessage());
            }
        }
    }

    private String resolveOwner(String sessionId) {
        try {
            ManagedSessionDto session = sessionService.requireById(sessionId);
            return session.ownerId();
        } catch (Exception ex) {
            log.debug(
                    "Could not resolve session owner for HITL ticket {}: {}",
                    sessionId,
                    ex.getMessage());
            return null;
        }
    }

    private static String serializeInput(Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        try {
            return JsonUtils.getJsonCodec().toJson(input);
        } catch (Exception ex) {
            return String.valueOf(input);
        }
    }

    private static Map<String, Object> ticketPayload(CoordinationStore.HitlTicket ticket) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "tool_confirmation");
        payload.put("schemaVersion", 1);
        payload.put("sessionId", ticket.sessionId());
        payload.put("toolUseId", ticket.toolUseId());
        payload.put("toolName", ticket.toolName());
        payload.put("approvalId", ticket.approvalId());
        payload.put("requestedAt", ticket.createdAt());
        payload.put("expiresAt", ticket.expiresAt());
        if (ticket.managedTask()) {
            payload.put("agentTaskId", ticket.agentTaskId());
            payload.put("attemptId", ticket.attemptId());
            payload.put("dispatchGeneration", ticket.dispatchGeneration());
            payload.put("turnId", ticket.turnId());
        }
        return payload;
    }

    private static Map<String, Object> resolutionPayload(
            CoordinationStore.HitlTicket ticket, String source) {
        Map<String, Object> payload = ticketPayload(ticket);
        payload.put("allow", Boolean.TRUE.equals(ticket.resolvedAllow()));
        payload.put("status", ticket.resolutionStatus());
        payload.put("decisionVersion", ticket.decisionVersion());
        payload.put("source", source);
        if (ticket.denyMessage() != null && !ticket.denyMessage().isBlank()) {
            payload.put("reason", ticket.denyMessage());
            payload.put("denyMessage", ticket.denyMessage());
        }
        return payload;
    }

    private static String resolutionEventId(CoordinationStore.HitlTicket ticket) {
        return "evt_hitl_decision_" + ticket.approvalId();
    }

    private static CoordinationStore.HitlDecisionFence fence(CoordinationStore.HitlTicket ticket) {
        return new CoordinationStore.HitlDecisionFence(
                ticket.sessionId(),
                ticket.toolUseId(),
                ticket.approvalId(),
                ticket.agentTaskId(),
                ticket.attemptId(),
                ticket.dispatchGeneration(),
                ticket.turnId());
    }

    private static CoordinationStore.HitlDecisionFence fence(ManagedDecision decision) {
        return new CoordinationStore.HitlDecisionFence(
                decision.sessionId(),
                decision.toolUseId(),
                decision.approvalId(),
                decision.agentTaskId(),
                decision.attemptId(),
                decision.dispatchGeneration(),
                decision.turnId());
    }

    private static ManagedExecutionScope managedScope(CoordinationStore.HitlTicket ticket) {
        return ticket.managedTask()
                ? new ManagedExecutionScope(
                        null,
                        ticket.agentTaskId(),
                        ticket.attemptId(),
                        ticket.dispatchGeneration(),
                        ticket.turnId())
                : null;
    }

    private static String managedApprovalId(
            ManagedExecutionScope scope, String sessionId, String toolUseId) {
        String name =
                "aistio:managed-hitl:v1:"
                        + scope.tenant()
                        + ":"
                        + sessionId
                        + ":"
                        + scope.attemptId()
                        + ":"
                        + scope.dispatchGeneration()
                        + ":"
                        + scope.turnId()
                        + ":"
                        + toolUseId;
        return uuidV5(UUID_NAMESPACE_URL, name).toString();
    }

    private static UUID uuidV5(UUID namespace, String name) {
        try {
            ByteBuffer namespaceBytes = ByteBuffer.allocate(16);
            namespaceBytes.putLong(namespace.getMostSignificantBits());
            namespaceBytes.putLong(namespace.getLeastSignificantBits());
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(namespaceBytes.array());
            byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer uuidBytes = ByteBuffer.wrap(hash);
            return new UUID(uuidBytes.getLong(), uuidBytes.getLong());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 is unavailable", ex);
        }
    }

    private static boolean matches(CoordinationStore.HitlTicket ticket, ManagedDecision decision) {
        return same(ticket.approvalId(), decision.approvalId())
                && same(ticket.agentTaskId(), decision.agentTaskId())
                && same(ticket.attemptId(), decision.attemptId())
                && ticket.dispatchGeneration() == decision.dispatchGeneration()
                && same(ticket.turnId(), decision.turnId());
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean completeManagedScope(ManagedExecutionScope scope) {
        return hasText(scope.tenant())
                && hasText(scope.agentTaskId())
                && hasText(scope.attemptId())
                && scope.dispatchGeneration() > 0
                && hasText(scope.turnId());
    }

    private static Object redactPreview(Object value, int depth) {
        if (value == null) {
            return value;
        }
        if (depth >= 5) {
            return "[TRUNCATED]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= 50) {
                    out.put("_truncated", true);
                    break;
                }
                String key = String.valueOf(entry.getKey());
                out.put(
                        key,
                        sensitiveKey(key)
                                ? "[REDACTED]"
                                : redactPreview(entry.getValue(), depth + 1));
            }
            return out;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> out = new ArrayList<>();
            for (Object item : iterable) {
                if (out.size() >= 50) {
                    out.add("[TRUNCATED]");
                    break;
                }
                out.add(redactPreview(item, depth + 1));
            }
            return out;
        }
        if (value instanceof String text && text.length() > 512) {
            return text.substring(0, 512) + "…";
        }
        return value;
    }

    private static boolean sensitiveKey(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT).replace("-", "_");
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("credential")
                || normalized.contains("api_key")
                || normalized.contains("apikey")
                || normalized.contains("private_key");
    }

    private static String sha256(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void completeLocal(CoordinationStore.HitlTicket ticket, boolean allow) {
        CompletableFuture<Boolean> local = localWaiters.remove(waiterKey(ticket));
        if (local != null) {
            local.complete(allow);
        }
    }

    private Optional<CoordinationStore.HitlTicket> findTicket(
            String sessionId, String toolUseId, ManagedTurnContext turnContext) {
        if (turnContext == null || turnContext.scope() == null) {
            return coordinationStore.getHitlTicket(sessionId, toolUseId);
        }
        ManagedExecutionScope scope = turnContext.scope();
        return coordinationStore.getHitlTicket(
                new CoordinationStore.HitlDecisionFence(
                        sessionId,
                        toolUseId,
                        managedApprovalId(scope, sessionId, toolUseId),
                        scope.agentTaskId(),
                        scope.attemptId(),
                        scope.dispatchGeneration(),
                        scope.turnId()));
    }

    private static String waiterKey(CoordinationStore.HitlTicket ticket) {
        return ticket.sessionId()
                + "\u0000"
                + (ticket.managedTask() ? ticket.approvalId() : ticket.toolUseId());
    }

    private static String safeReason(String reason) {
        return reason.length() <= 512 ? reason : reason.substring(0, 512) + "…";
    }

    public record ConfirmationDecision(boolean allow, String reason) {}

    /** Fully fenced control-plane decision callback. */
    public record ManagedDecision(
            String sessionId,
            String toolUseId,
            String approvalId,
            String agentTaskId,
            String attemptId,
            long dispatchGeneration,
            String turnId,
            String status,
            long decisionVersion,
            boolean allow,
            String reason) {}

    /** Stable outcome used by both HTTP APIs to distinguish stale and replayed decisions. */
    public enum DecisionResult {
        RESOLVED,
        IDEMPOTENT,
        NOT_FOUND,
        FENCE_MISMATCH,
        EXPIRED,
        CONFLICT,
        CONTINUATION_LOST,
        OWNER_UNAVAILABLE,
        CONTROL_PLANE_REQUIRED,
        NOT_YET_EXPIRED
    }
}
