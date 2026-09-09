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

import io.agentscope.builder.web.persistence.jpa.CoordHitlTicketEntity;
import io.agentscope.builder.web.persistence.jpa.CoordHitlTicketEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordLeaseEntity;
import io.agentscope.builder.web.persistence.jpa.CoordLeaseEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordWorkItemEntity;
import io.agentscope.builder.web.persistence.jpa.CoordWorkItemEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordWorkerHeartbeatEntity;
import io.agentscope.builder.web.persistence.jpa.CoordWorkerHeartbeatEntityRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JDBC-backed {@link CoordinationStore} using the builder catalog DataSource / JPA schema.
 *
 * <p>Registered as the default bean from {@code BuilderConfig}; operators can replace it by
 * declaring another {@link CoordinationStore} bean (e.g. Redis).
 */
public class JdbcCoordinationStore implements CoordinationStore {

    private static final long CLAIM_POLL_MS = 100L;

    private final CoordLeaseEntityRepository leaseRepository;
    private final CoordHitlTicketEntityRepository hitlRepository;
    private final CoordWorkItemEntityRepository workRepository;
    private final CoordWorkerHeartbeatEntityRepository workerRepository;
    private final TransactionTemplate transactionTemplate;

    public JdbcCoordinationStore(
            CoordLeaseEntityRepository leaseRepository,
            CoordHitlTicketEntityRepository hitlRepository,
            CoordWorkItemEntityRepository workRepository,
            CoordWorkerHeartbeatEntityRepository workerRepository,
            TransactionTemplate transactionTemplate) {
        this.leaseRepository = leaseRepository;
        this.hitlRepository = hitlRepository;
        this.workRepository = workRepository;
        this.workerRepository = workerRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    @Transactional
    public Optional<LeaseHandle> tryAcquireTurnLease(
            String sessionId, String ownerId, String instanceId, Duration ttl) {
        long now = System.currentTimeMillis();
        long expires = now + ttl.toMillis();
        Optional<CoordLeaseEntity> existing =
                leaseRepository.findByKindAndKeyForUpdate(CoordLeaseEntity.KIND_TURN, sessionId);
        if (existing.isPresent()) {
            CoordLeaseEntity row = existing.get();
            if (row.getExpiresAt() > now && !instanceId.equals(row.getInstanceId())) {
                return Optional.empty();
            }
            // Expired or same instance — take over / refresh.
            row.setOwnerId(ownerId);
            row.setInstanceId(instanceId);
            row.setAcquiredAt(now);
            row.setExpiresAt(expires);
            leaseRepository.save(row);
            return Optional.of(toLease(row));
        }
        CoordLeaseEntity created = new CoordLeaseEntity();
        created.setLeaseKind(CoordLeaseEntity.KIND_TURN);
        created.setLeaseKey(sessionId);
        created.setOwnerId(ownerId);
        created.setInstanceId(instanceId);
        created.setAcquiredAt(now);
        created.setExpiresAt(expires);
        try {
            leaseRepository.saveAndFlush(created);
            return Optional.of(toLease(created));
        } catch (DataIntegrityViolationException ex) {
            // Concurrent insert — loser yields.
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public boolean heartbeatTurnLease(String sessionId, String instanceId, Duration ttl) {
        return leaseRepository.heartbeatIfOwned(
                        CoordLeaseEntity.KIND_TURN,
                        sessionId,
                        instanceId,
                        System.currentTimeMillis() + ttl.toMillis())
                > 0;
    }

    @Override
    @Transactional
    public boolean releaseTurnLease(String sessionId, String instanceId) {
        return leaseRepository.deleteByKindKeyAndInstance(
                        CoordLeaseEntity.KIND_TURN, sessionId, instanceId)
                > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LeaseHandle> getTurnLease(String sessionId) {
        return leaseRepository
                .findByLeaseKindAndLeaseKey(CoordLeaseEntity.KIND_TURN, sessionId)
                .filter(e -> e.getExpiresAt() > System.currentTimeMillis())
                .map(this::toLease);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LeaseHandle> listExpiredTurnLeases(long nowMillis) {
        return leaseRepository
                .findByLeaseKindAndExpiresAtLessThan(CoordLeaseEntity.KIND_TURN, nowMillis)
                .stream()
                .map(this::toLease)
                .toList();
    }

    @Override
    @Transactional
    public void requestTurnInterrupt(String sessionId, String reason) {
        requestTurnInterrupt(sessionId, reason, null);
    }

    @Override
    @Transactional
    public void requestFencedTurnInterrupt(String sessionId, String reason, String fenceToken) {
        if (fenceToken == null || fenceToken.isBlank()) {
            throw new IllegalArgumentException("fenceToken is required");
        }
        requestTurnInterrupt(sessionId, reason, fenceToken);
    }

    private void requestTurnInterrupt(String sessionId, String reason, String fenceToken) {
        long now = System.currentTimeMillis();
        // Keep interrupt tickets long enough for a slow heartbeat cycle to pick them up.
        long expires = now + Duration.ofMinutes(5).toMillis();
        Optional<CoordLeaseEntity> existing =
                leaseRepository.findByLeaseKindAndLeaseKey(
                        CoordLeaseEntity.KIND_INTERRUPT, interruptLeaseKey(sessionId, fenceToken));
        if (existing.isPresent()) {
            CoordLeaseEntity row = existing.get();
            row.setOwnerId(reason != null ? reason : "interrupt");
            row.setInstanceId(interruptMarker(fenceToken));
            row.setAcquiredAt(now);
            row.setExpiresAt(expires);
            leaseRepository.save(row);
            return;
        }
        CoordLeaseEntity created = new CoordLeaseEntity();
        created.setLeaseKind(CoordLeaseEntity.KIND_INTERRUPT);
        created.setLeaseKey(interruptLeaseKey(sessionId, fenceToken));
        created.setOwnerId(reason != null ? reason : "interrupt");
        created.setInstanceId(interruptMarker(fenceToken));
        created.setAcquiredAt(now);
        created.setExpiresAt(expires);
        try {
            leaseRepository.saveAndFlush(created);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent insert — refresh the winner's reason.
            leaseRepository
                    .findByLeaseKindAndLeaseKey(
                            CoordLeaseEntity.KIND_INTERRUPT,
                            interruptLeaseKey(sessionId, fenceToken))
                    .ifPresent(
                            row -> {
                                row.setOwnerId(reason != null ? reason : "interrupt");
                                row.setInstanceId(interruptMarker(fenceToken));
                                row.setAcquiredAt(now);
                                row.setExpiresAt(expires);
                                leaseRepository.save(row);
                            });
        }
    }

    @Override
    @Transactional
    public Optional<String> consumeTurnInterrupt(String sessionId) {
        return consumeTurnInterruptRequest(sessionId).map(TurnInterruptRequest::reason);
    }

    @Override
    @Transactional
    public Optional<TurnInterruptRequest> consumeTurnInterruptRequest(String sessionId) {
        Optional<CoordLeaseEntity> existing =
                leaseRepository.findByLeaseKindAndLeaseKey(
                        CoordLeaseEntity.KIND_INTERRUPT, sessionId);
        if (existing.isEmpty()) {
            existing =
                    leaseRepository
                            .findByLeaseKindAndLeaseKeyStartingWithOrderByAcquiredAtAsc(
                                    CoordLeaseEntity.KIND_INTERRUPT,
                                    fencedInterruptPrefix(sessionId))
                            .stream()
                            .findFirst();
        }
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        CoordLeaseEntity row = existing.get();
        if (row.getExpiresAt() <= System.currentTimeMillis()) {
            leaseRepository.delete(row);
            return Optional.empty();
        }
        String reason = row.getOwnerId() != null ? row.getOwnerId() : "interrupt";
        String marker = row.getInstanceId();
        String fenceToken =
                marker != null && marker.startsWith("fence:")
                        ? marker.substring("fence:".length())
                        : null;
        leaseRepository.delete(row);
        return Optional.of(new TurnInterruptRequest(reason, fenceToken));
    }

    private static String interruptMarker(String fenceToken) {
        return fenceToken == null || fenceToken.isBlank() ? "pending" : "fence:" + fenceToken;
    }

    private static String interruptLeaseKey(String sessionId, String fenceToken) {
        return fenceToken == null || fenceToken.isBlank()
                ? sessionId
                : fencedInterruptPrefix(sessionId) + fenceToken;
    }

    private static String fencedInterruptPrefix(String sessionId) {
        return "fi:" + sha256(sessionId) + ":";
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    @Override
    @Transactional
    public boolean tryAcquireFireLease(
            String deploymentId, String fireWindow, String instanceId, Duration ttl) {
        String key = deploymentId + ":" + fireWindow;
        long now = System.currentTimeMillis();
        Optional<CoordLeaseEntity> existing =
                leaseRepository.findByLeaseKindAndLeaseKey(CoordLeaseEntity.KIND_FIRE, key);
        if (existing.isPresent()) {
            CoordLeaseEntity row = existing.get();
            if (row.getExpiresAt() > now) {
                return instanceId.equals(row.getInstanceId());
            }
            row.setInstanceId(instanceId);
            row.setAcquiredAt(now);
            row.setExpiresAt(now + ttl.toMillis());
            leaseRepository.save(row);
            return true;
        }
        CoordLeaseEntity created = new CoordLeaseEntity();
        created.setLeaseKind(CoordLeaseEntity.KIND_FIRE);
        created.setLeaseKey(key);
        created.setInstanceId(instanceId);
        created.setAcquiredAt(now);
        created.setExpiresAt(now + ttl.toMillis());
        try {
            leaseRepository.saveAndFlush(created);
            return true;
        } catch (DataIntegrityViolationException ex) {
            return false;
        }
    }

    @Override
    @Transactional
    public HitlTicket putHitlTicket(HitlTicket ticket) {
        Optional<CoordHitlTicketEntity> existing =
                ticket.managedTask()
                        ? hitlRepository
                                .findBySessionIdAndAttemptIdAndDispatchGenerationAndTurnIdAndToolUseId(
                                        ticket.sessionId(),
                                        ticket.attemptId(),
                                        ticket.dispatchGeneration(),
                                        ticket.turnId(),
                                        ticket.toolUseId())
                        : hitlRepository.findBySessionIdAndToolUseIdAndAgentTaskIdIsNull(
                                ticket.sessionId(), ticket.toolUseId());
        if (existing.isPresent()) {
            HitlTicket stored = toHitl(existing.get());
            if (!sameImmutableTicket(stored, ticket)) {
                throw new IllegalStateException(
                        "HITL ticket identity already exists with different immutable data");
            }
            return stored;
        }
        CoordHitlTicketEntity entity = new CoordHitlTicketEntity();
        entity.setToolUseId(ticket.toolUseId());
        entity.setSessionId(ticket.sessionId());
        entity.setOwnerId(ticket.ownerId());
        entity.setApprovalId(ticket.approvalId());
        entity.setAgentTaskId(ticket.agentTaskId());
        entity.setAttemptId(ticket.attemptId());
        entity.setDispatchGeneration(ticket.dispatchGeneration());
        entity.setTurnId(ticket.turnId());
        entity.setContinuationLeaseId(ticket.continuationLeaseId());
        entity.setToolName(ticket.toolName());
        entity.setInputJson(ticket.inputJson());
        entity.setResolutionStatus(ticket.resolutionStatus());
        entity.setDecisionVersion(ticket.decisionVersion());
        entity.setResolvedAllow(ticket.resolvedAllow());
        entity.setDenyMessage(ticket.denyMessage());
        entity.setCreatedAt(ticket.createdAt());
        entity.setExpiresAt(ticket.expiresAt());
        entity.setResolvedAt(ticket.resolvedAt());
        entity.setContinuationReady(ticket.continuationReady());
        return toHitl(hitlRepository.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HitlTicket> getHitlTicket(String sessionId, String toolUseId) {
        return hitlRepository
                .findBySessionIdAndToolUseIdOrderByCreatedAtDesc(sessionId, toolUseId)
                .stream()
                .findFirst()
                .map(this::toHitl);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HitlTicket> getHitlTicket(HitlDecisionFence fence) {
        return hitlRepository
                .findBySessionIdAndAttemptIdAndDispatchGenerationAndTurnIdAndToolUseId(
                        fence.sessionId(),
                        fence.attemptId(),
                        fence.dispatchGeneration(),
                        fence.turnId(),
                        fence.toolUseId())
                .filter(entity -> matches(entity.getApprovalId(), fence.approvalId()))
                .filter(entity -> matches(entity.getAgentTaskId(), fence.agentTaskId()))
                .map(this::toHitl);
    }

    @Override
    @Transactional
    public Optional<HitlResolution> resolveHitlTicket(
            HitlDecisionFence fence,
            String resolutionStatus,
            long decisionVersion,
            boolean allow,
            String denyMessage,
            long resolvedAt) {
        Optional<CoordHitlTicketEntity> existing = findForUpdate(fence);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        CoordHitlTicketEntity entity = existing.get();
        if (!matches(entity.getApprovalId(), fence.approvalId())
                || !matches(entity.getAgentTaskId(), fence.agentTaskId())
                || !matches(entity.getAttemptId(), fence.attemptId())
                || entity.getDispatchGeneration() != fence.dispatchGeneration()
                || !matches(entity.getTurnId(), fence.turnId())) {
            return Optional.empty();
        }
        if (entity.getResolvedAllow() != null) {
            return entity.getResolvedAllow() == allow
                            && matches(entity.getResolutionStatus(), resolutionStatus)
                            && entity.getDecisionVersion() == decisionVersion
                    ? Optional.of(new HitlResolution(toHitl(entity), false))
                    : Optional.empty();
        }
        // CP is the expiry/decision CAS authority for Managed AgentTasks. Its outbox may deliver a
        // decision after the DP-local wall clock passed expiresAt; the full fence + version still
        // make that committed decision valid. Personal chat continues to use the local deadline.
        if ((entity.getAgentTaskId() == null || entity.getAgentTaskId().isBlank())
                && entity.getExpiresAt() <= resolvedAt) {
            return Optional.empty();
        }
        entity.setResolvedAllow(allow);
        entity.setResolutionStatus(resolutionStatus);
        entity.setDecisionVersion(decisionVersion);
        entity.setDenyMessage(denyMessage);
        entity.setResolvedAt(resolvedAt);
        hitlRepository.save(entity);
        return Optional.of(new HitlResolution(toHitl(entity), true));
    }

    @Override
    @Transactional
    public Optional<HitlResolution> expireHitlTicket(
            String sessionId, String toolUseId, long expiredAt) {
        Optional<CoordHitlTicketEntity> existing =
                hitlRepository.findPersonalForUpdate(sessionId, toolUseId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        CoordHitlTicketEntity entity = existing.get();
        if (entity.getResolvedAllow() != null) {
            return Optional.of(new HitlResolution(toHitl(entity), false));
        }
        if (entity.getExpiresAt() > expiredAt) {
            return Optional.empty();
        }
        entity.setResolvedAllow(false);
        entity.setResolutionStatus("cancelled");
        entity.setDecisionVersion(0L);
        entity.setDenyMessage("timed_out");
        entity.setResolvedAt(expiredAt);
        hitlRepository.save(entity);
        return Optional.of(new HitlResolution(toHitl(entity), true));
    }

    @Override
    @Transactional
    public boolean markHitlContinuationReady(HitlDecisionFence fence) {
        Optional<CoordHitlTicketEntity> existing = findForUpdate(fence);
        if (existing.isEmpty()) {
            return false;
        }
        CoordHitlTicketEntity entity = existing.get();
        if (!matches(entity.getApprovalId(), fence.approvalId())
                || !matches(entity.getAgentTaskId(), fence.agentTaskId())
                || !matches(entity.getAttemptId(), fence.attemptId())
                || entity.getDispatchGeneration() != fence.dispatchGeneration()
                || !matches(entity.getTurnId(), fence.turnId())
                || entity.getResolvedAllow() == null) {
            return false;
        }
        entity.setContinuationReady(true);
        hitlRepository.save(entity);
        return true;
    }

    @Override
    @Transactional
    public void deleteHitlTicket(String sessionId, String toolUseId) {
        hitlRepository.deleteBySessionIdAndToolUseId(sessionId, toolUseId);
    }

    @Override
    @Transactional
    public void deleteHitlTicketsBySession(String sessionId) {
        hitlRepository.deleteBySessionId(sessionId);
    }

    @Override
    @Transactional
    public long deleteResolvedHitlTicketsBefore(long expiresAtCutoff) {
        return hitlRepository.deleteByResolvedAllowIsNotNullAndExpiresAtLessThan(expiresAtCutoff);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HitlTicket> listExpiredHitlTickets(long nowMillis) {
        return hitlRepository.findByExpiresAtLessThanAndResolvedAllowIsNull(nowMillis).stream()
                .map(this::toHitl)
                .toList();
    }

    @Override
    @Transactional
    public WorkItemRecord enqueueWork(String sessionId, String environmentId, String ownerId) {
        long now = System.currentTimeMillis();
        CoordWorkItemEntity entity = new CoordWorkItemEntity();
        entity.setLeaseId(UUID.randomUUID().toString());
        entity.setSessionId(sessionId);
        entity.setEnvironmentId(environmentId);
        entity.setOwnerId(ownerId);
        entity.setStatus(CoordWorkItemEntity.STATUS_QUEUED);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        workRepository.save(entity);
        return toWork(entity);
    }

    @Override
    public Optional<WorkItemRecord> claimWork(String environmentId, String workerId, long timeoutMs)
            throws InterruptedException {
        if (workerId != null) {
            workerHeartbeat(workerId, null);
        }
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        do {
            Optional<WorkItemRecord> claimed =
                    transactionTemplate.execute(status -> doPollOnce(environmentId, workerId));
            if (claimed != null && claimed.isPresent()) {
                return claimed;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            Thread.sleep(Math.min(CLAIM_POLL_MS, remaining));
        } while (System.currentTimeMillis() < deadline);
        return Optional.empty();
    }

    private Optional<WorkItemRecord> doPollOnce(String environmentId, String workerId) {
        long staleBefore = System.currentTimeMillis() - WORK_STALE_THRESHOLD.toMillis();
        List<CoordWorkItemEntity> claimable =
                workRepository.findClaimableForPoll(
                        environmentId,
                        CoordWorkItemEntity.STATUS_QUEUED,
                        List.of(
                                CoordWorkItemEntity.STATUS_STARTING,
                                CoordWorkItemEntity.STATUS_ACTIVE),
                        staleBefore);
        if (claimable.isEmpty()) {
            return Optional.empty();
        }
        CoordWorkItemEntity entity = claimable.get(0);
        entity.setStatus(CoordWorkItemEntity.STATUS_STARTING);
        entity.setClaimedBy(workerId);
        entity.setUpdatedAt(System.currentTimeMillis());
        workRepository.save(entity);
        return Optional.of(toWork(entity));
    }

    @Override
    @Transactional
    public void ackWork(String leaseId, String workerId, String workDir) {
        workRepository
                .findByLeaseId(leaseId)
                .ifPresent(
                        entity -> {
                            entity.setStatus(CoordWorkItemEntity.STATUS_ACTIVE);
                            if (workerId != null) {
                                entity.setClaimedBy(workerId);
                            }
                            if (workDir != null) {
                                entity.setWorkDir(workDir);
                            }
                            entity.setUpdatedAt(System.currentTimeMillis());
                            workRepository.save(entity);
                        });
    }

    @Override
    @Transactional
    public void stopWork(String leaseId) {
        workRepository
                .findByLeaseId(leaseId)
                .ifPresent(
                        entity -> {
                            entity.setStatus(CoordWorkItemEntity.STATUS_STOPPED);
                            entity.setUpdatedAt(System.currentTimeMillis());
                            workRepository.save(entity);
                        });
    }

    @Override
    @Transactional
    public void heartbeatWork(String leaseId) {
        workRepository
                .findByLeaseId(leaseId)
                .ifPresent(
                        entity -> {
                            entity.setUpdatedAt(System.currentTimeMillis());
                            workRepository.save(entity);
                        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkItemRecord> getWork(String leaseId) {
        return workRepository.findByLeaseId(leaseId).map(this::toWork);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkItemRecord> listWork(String environmentId, String statusFilter) {
        List<CoordWorkItemEntity> rows =
                statusFilter == null || statusFilter.isBlank()
                        ? workRepository.findByEnvironmentIdOrderByCreatedAtAsc(environmentId)
                        : workRepository.findByEnvironmentIdAndStatusOrderByCreatedAtAsc(
                                environmentId, statusFilter);
        return rows.stream().map(this::toWork).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public WorkStats workStats(String environmentId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String status :
                List.of(
                        CoordWorkItemEntity.STATUS_QUEUED,
                        CoordWorkItemEntity.STATUS_STARTING,
                        CoordWorkItemEntity.STATUS_ACTIVE,
                        CoordWorkItemEntity.STATUS_STOPPING,
                        CoordWorkItemEntity.STATUS_STOPPED)) {
            counts.put(status, workRepository.countByEnvironmentIdAndStatus(environmentId, status));
        }
        Long oldestQueued =
                workRepository
                        .findOldestCreatedAtByEnvironmentIdAndStatus(
                                environmentId, CoordWorkItemEntity.STATUS_QUEUED)
                        .orElse(null);
        Long oldestQueuedAgeMs =
                oldestQueued == null ? null : System.currentTimeMillis() - oldestQueued;
        return new WorkStats(counts, oldestQueuedAgeMs);
    }

    @Override
    @Transactional
    public void workerHeartbeat(String workerId, String capabilitiesJson) {
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        CoordWorkerHeartbeatEntity entity =
                workerRepository
                        .findByWorkerId(workerId)
                        .orElseGet(CoordWorkerHeartbeatEntity::new);
        entity.setWorkerId(workerId);
        entity.setLastSeenAt(System.currentTimeMillis());
        if (capabilitiesJson != null) {
            entity.setCapabilitiesJson(capabilitiesJson);
        }
        workerRepository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> workerHeartbeats() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (CoordWorkerHeartbeatEntity e : workerRepository.findAll()) {
            out.put(e.getWorkerId(), e.getLastSeenAt());
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public int pendingWorkCount() {
        return (int) workRepository.countByStatus(CoordWorkItemEntity.STATUS_QUEUED);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SandboxReadyMeta> getSandboxReady(String sessionId) {
        return workRepository
                .findFirstBySessionIdAndStatusInOrderByCreatedAtDesc(
                        sessionId,
                        List.of(
                                CoordWorkItemEntity.STATUS_ACTIVE,
                                CoordWorkItemEntity.STATUS_STARTING))
                .filter(e -> CoordWorkItemEntity.STATUS_ACTIVE.equals(e.getStatus()))
                .map(
                        e ->
                                new SandboxReadyMeta(
                                        e.getSessionId(),
                                        e.getClaimedBy(),
                                        e.getWorkDir(),
                                        e.getLeaseId(),
                                        e.getUpdatedAt()));
    }

    @Override
    @Transactional
    public void clearSandboxReady(String sessionId) {
        // no-op: rows stay stopped; ready lookup filters by status
    }

    private LeaseHandle toLease(CoordLeaseEntity e) {
        return new LeaseHandle(
                e.getLeaseKey(),
                e.getOwnerId(),
                e.getInstanceId(),
                e.getAcquiredAt(),
                e.getExpiresAt());
    }

    private HitlTicket toHitl(CoordHitlTicketEntity e) {
        return new HitlTicket(
                e.getToolUseId(),
                e.getSessionId(),
                e.getOwnerId(),
                e.getApprovalId(),
                e.getAgentTaskId(),
                e.getAttemptId(),
                e.getDispatchGeneration(),
                e.getTurnId(),
                e.getContinuationLeaseId(),
                e.getToolName(),
                e.getInputJson(),
                e.getResolutionStatus(),
                e.getDecisionVersion(),
                e.getResolvedAllow(),
                e.getDenyMessage(),
                e.getCreatedAt(),
                e.getExpiresAt(),
                e.getResolvedAt(),
                Boolean.TRUE.equals(e.getContinuationReady()));
    }

    private static boolean matches(String stored, String expected) {
        return stored == null ? expected == null : stored.equals(expected);
    }

    private Optional<CoordHitlTicketEntity> findForUpdate(HitlDecisionFence fence) {
        if (fence.agentTaskId() == null || fence.agentTaskId().isBlank()) {
            return hitlRepository.findPersonalForUpdate(fence.sessionId(), fence.toolUseId());
        }
        return hitlRepository.findManagedForUpdate(
                fence.sessionId(),
                fence.attemptId(),
                fence.dispatchGeneration(),
                fence.turnId(),
                fence.toolUseId());
    }

    private static boolean sameImmutableTicket(HitlTicket left, HitlTicket right) {
        return matches(left.sessionId(), right.sessionId())
                && matches(left.toolUseId(), right.toolUseId())
                && matches(left.ownerId(), right.ownerId())
                && matches(left.approvalId(), right.approvalId())
                && matches(left.agentTaskId(), right.agentTaskId())
                && matches(left.attemptId(), right.attemptId())
                && left.dispatchGeneration() == right.dispatchGeneration()
                && matches(left.turnId(), right.turnId())
                && matches(left.continuationLeaseId(), right.continuationLeaseId())
                && matches(left.toolName(), right.toolName())
                && matches(left.inputJson(), right.inputJson())
                && left.createdAt() == right.createdAt()
                && left.expiresAt() == right.expiresAt();
    }

    private WorkItemRecord toWork(CoordWorkItemEntity e) {
        return new WorkItemRecord(
                e.getLeaseId(),
                e.getSessionId(),
                e.getEnvironmentId(),
                e.getOwnerId(),
                e.getStatus(),
                e.getClaimedBy(),
                e.getWorkDir(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
