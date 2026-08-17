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

    /**
     * Atomically consumes a pending interrupt for {@code sessionId}, returning the reason when one
     * was present.
     */
    Optional<String> consumeTurnInterrupt(String sessionId);

    // ---- Deployment cron fire lease ----

    /** One-shot fire window lease; returns true if this instance won the window. */
    boolean tryAcquireFireLease(
            String deploymentId, String fireWindow, String instanceId, Duration ttl);

    // ---- HITL tickets ----

    void putHitlTicket(HitlTicket ticket);

    Optional<HitlTicket> getHitlTicket(String toolUseId);

    /** Sets allow/deny; returns updated ticket or empty if missing. */
    Optional<HitlTicket> resolveHitlTicket(String toolUseId, boolean allow, String denyMessage);

    void deleteHitlTicket(String toolUseId);

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

    /** HITL confirmation ticket shared across Brain replicas. */
    record HitlTicket(
            String toolUseId,
            String sessionId,
            String ownerId,
            String toolName,
            String inputJson,
            Boolean resolvedAllow,
            String denyMessage,
            long createdAt,
            long expiresAt) {}

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
