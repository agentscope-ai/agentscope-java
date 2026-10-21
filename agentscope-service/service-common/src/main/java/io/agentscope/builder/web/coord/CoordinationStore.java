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
package io.agentscope.builder.web.coord;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared coordination plane for multi-replica Brain / Hands workers.
 *
 * <p>Authoritative business state stays in JPA session/event tables. This store holds short-lived
 * leases, work-queue rows, HITL tickets, and sandbox-ready metadata. Default implementation is
 * JDBC; operators may replace the bean with a Redis-backed store.
 */
public interface CoordinationStore {

    /** Stale threshold for reclaiming starting/active work items during poll. */
    Duration WORK_STALE_THRESHOLD = Duration.ofSeconds(60);

    // ---- Turn lease (in-flight turn mutex per session) ----

    /**
     * Attempts to acquire the turn lease for {@code sessionId}. Returns empty when another owner
     * holds a non-expired lease.
     */
    Optional<LeaseHandle> tryAcquireTurnLease(
            String sessionId, String ownerId, String instanceId, Duration ttl);

    /** Extends TTL when {@code instanceId} still owns the lease. */
    boolean heartbeatTurnLease(String sessionId, String instanceId, Duration ttl);

    /** Releases the lease when owned by {@code instanceId}. */
    boolean releaseTurnLease(String sessionId, String instanceId);

    Optional<LeaseHandle> getTurnLease(String sessionId);

    /** Lists turn leases whose {@code expiresAt} is before {@code nowMillis}. */
    List<LeaseHandle> listExpiredTurnLeases(long nowMillis);

    // ---- Turn interrupt (cross-replica) ----

    /**
     * Records an interrupt request for {@code sessionId}. The instance that holds the turn lease
     * consumes it on the next heartbeat and cancels the local turn.
     */
    void requestTurnInterrupt(String sessionId, String reason);

    /** Records a managed-attempt interrupt that only the matching turn may consume. */
    void requestFencedTurnInterrupt(String sessionId, String reason, String fenceToken);

    /**
     * Atomically consumes a pending interrupt for {@code sessionId}, returning the reason when one
     * was present.
     */
    Optional<String> consumeTurnInterrupt(String sessionId);

    Optional<TurnInterruptRequest> consumeTurnInterruptRequest(String sessionId);

    // ---- Deployment cron fire lease ----

    /** One-shot fire window lease; returns true if this instance won the window. */
    boolean tryAcquireFireLease(
            String deploymentId, String fireWindow, String instanceId, Duration ttl);

    // ---- HITL tickets ----

    /** Inserts an immutable ticket or returns the identical existing ticket. */
    HitlTicket putHitlTicket(HitlTicket ticket);

    /** Finds the newest ticket for direct-session routing; managed resolve uses fenced overload. */
    Optional<HitlTicket> getHitlTicket(String sessionId, String toolUseId);

    Optional<HitlTicket> getHitlTicket(HitlDecisionFence fence);

    /**
     * Sets allow/deny after validating the complete immutable execution fence. Returns the current
     * ticket when it was resolved (including an idempotent replay), or empty when the ticket/fence
     * did not match, expired, or a conflicting decision already won.
     */
    Optional<HitlResolution> resolveHitlTicket(
            HitlDecisionFence fence,
            String resolutionStatus,
            long decisionVersion,
            boolean allow,
            String denyMessage,
            long resolvedAt);

    /** Atomically denies a still-pending ticket once its deadline has passed. */
    Optional<HitlResolution> expireHitlTicket(String sessionId, String toolUseId, long expiredAt);

    /** Marks side effects complete so a polling continuation may safely resume. */
    boolean markHitlContinuationReady(HitlDecisionFence fence);

    void deleteHitlTicket(String sessionId, String toolUseId);

    void deleteHitlTicketsBySession(String sessionId);

    long deleteResolvedHitlTicketsBefore(long expiresAtCutoff);

    List<HitlTicket> listExpiredHitlTickets(long nowMillis);

    // ---- Hands work queue ----

    WorkItemRecord enqueueWork(String sessionId, String environmentId, String ownerId);

    Optional<WorkItemRecord> claimWork(String environmentId, String workerId, long timeoutMs)
            throws InterruptedException;

    void ackWork(String leaseId, String workerId, String workDir);

    void stopWork(String leaseId);

    void heartbeatWork(String leaseId);

    Optional<WorkItemRecord> getWork(String leaseId);

    List<WorkItemRecord> listWork(String environmentId, String statusFilter);

    WorkStats workStats(String environmentId);

    void workerHeartbeat(String workerId, String capabilitiesJson);

    Map<String, Long> workerHeartbeats();

    int pendingWorkCount();

    Optional<SandboxReadyMeta> getSandboxReady(String sessionId);

    void clearSandboxReady(String sessionId);

    /** Opaque turn-lease snapshot. */
    record LeaseHandle(
            String sessionId, String ownerId, String instanceId, long acquiredAt, long expiresAt) {}

    record TurnInterruptRequest(String reason, String fenceToken) {}

    /** HITL confirmation ticket shared across Brain replicas. */
    record HitlTicket(
            String toolUseId,
            String sessionId,
            String ownerId,
            String approvalId,
            String agentTaskId,
            String attemptId,
            long dispatchGeneration,
            String turnId,
            String continuationLeaseId,
            String toolName,
            String inputJson,
            String resolutionStatus,
            long decisionVersion,
            Boolean resolvedAllow,
            String denyMessage,
            long createdAt,
            long expiresAt,
            Long resolvedAt,
            boolean continuationReady) {

        public boolean managedTask() {
            return agentTaskId != null && !agentTaskId.isBlank();
        }
    }

    /** Full correlation fence required to decide a managed AgentTask confirmation. */
    record HitlDecisionFence(
            String sessionId,
            String toolUseId,
            String approvalId,
            String agentTaskId,
            String attemptId,
            long dispatchGeneration,
            String turnId) {}

    /** Atomic decision result; {@code changed=false} identifies a safe idempotent replay. */
    record HitlResolution(HitlTicket ticket, boolean changed) {}

    /** Durable hands work-queue row. */
    record WorkItemRecord(
            String leaseId,
            String sessionId,
            String environmentId,
            String ownerId,
            String status,
            String claimedBy,
            String workDir,
            long createdAt,
            long updatedAt) {}

    /** Per-environment work-queue counters. */
    record WorkStats(Map<String, Long> countsByStatus, Long oldestQueuedAgeMs) {}

    /** Metadata that a worker has a sandbox ready for a session (object stays local). */
    record SandboxReadyMeta(
            String sessionId, String workerId, String workDir, String leaseId, long readyAt) {}
}
