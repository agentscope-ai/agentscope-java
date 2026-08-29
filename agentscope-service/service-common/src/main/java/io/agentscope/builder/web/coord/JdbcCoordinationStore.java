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
                leaseRepository.findByLeaseKindAndLeaseKey(CoordLeaseEntity.KIND_TURN, sessionId);
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
        Optional<CoordLeaseEntity> existing =
                leaseRepository.findByLeaseKindAndLeaseKey(CoordLeaseEntity.KIND_TURN, sessionId);
        if (existing.isEmpty()) {
            return false;
        }
        CoordLeaseEntity row = existing.get();
        if (!instanceId.equals(row.getInstanceId())) {
            return false;
        }
        row.setExpiresAt(System.currentTimeMillis() + ttl.toMillis());
        leaseRepository.save(row);
        return true;
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
        long now = System.currentTimeMillis();
        // Keep interrupt tickets long enough for a slow heartbeat cycle to pick them up.
        long expires = now + Duration.ofMinutes(5).toMillis();
        Optional<CoordLeaseEntity> existing =
                leaseRepository.findByLeaseKindAndLeaseKey(
                        CoordLeaseEntity.KIND_INTERRUPT, sessionId);
        if (existing.isPresent()) {
            CoordLeaseEntity row = existing.get();
            row.setOwnerId(reason != null ? reason : "interrupt");
            row.setInstanceId("pending");
            row.setAcquiredAt(now);
            row.setExpiresAt(expires);
            leaseRepository.save(row);
            return;
        }
        CoordLeaseEntity created = new CoordLeaseEntity();
        created.setLeaseKind(CoordLeaseEntity.KIND_INTERRUPT);
        created.setLeaseKey(sessionId);
        created.setOwnerId(reason != null ? reason : "interrupt");
        created.setInstanceId("pending");
        created.setAcquiredAt(now);
        created.setExpiresAt(expires);
        try {
            leaseRepository.saveAndFlush(created);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent insert — refresh the winner's reason.
            leaseRepository
                    .findByLeaseKindAndLeaseKey(CoordLeaseEntity.KIND_INTERRUPT, sessionId)
                    .ifPresent(
                            row -> {
                                row.setOwnerId(reason != null ? reason : "interrupt");
                                row.setAcquiredAt(now);
                                row.setExpiresAt(expires);
                                leaseRepository.save(row);
                            });
        }
    }

    @Override
    @Transactional
    public Optional<String> consumeTurnInterrupt(String sessionId) {
        Optional<CoordLeaseEntity> existing =
                leaseRepository.findByLeaseKindAndLeaseKey(
                        CoordLeaseEntity.KIND_INTERRUPT, sessionId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        CoordLeaseEntity row = existing.get();
        if (row.getExpiresAt() <= System.currentTimeMillis()) {
            leaseRepository.delete(row);
            return Optional.empty();
        }
        String reason = row.getOwnerId() != null ? row.getOwnerId() : "interrupt";
        leaseRepository.delete(row);
        return Optional.of(reason);
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
    public void putHitlTicket(HitlTicket ticket) {
        CoordHitlTicketEntity entity =
                hitlRepository
                        .findByToolUseId(ticket.toolUseId())
                        .orElseGet(CoordHitlTicketEntity::new);
        entity.setToolUseId(ticket.toolUseId());
        entity.setSessionId(ticket.sessionId());
        entity.setOwnerId(ticket.ownerId());
        entity.setToolName(ticket.toolName());
        entity.setInputJson(ticket.inputJson());
        entity.setResolvedAllow(ticket.resolvedAllow());
        entity.setDenyMessage(ticket.denyMessage());
        entity.setCreatedAt(ticket.createdAt());
        entity.setExpiresAt(ticket.expiresAt());
        hitlRepository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HitlTicket> getHitlTicket(String toolUseId) {
        return hitlRepository.findByToolUseId(toolUseId).map(this::toHitl);
    }

    @Override
    @Transactional
    public Optional<HitlTicket> resolveHitlTicket(
            String toolUseId, boolean allow, String denyMessage) {
        Optional<CoordHitlTicketEntity> existing = hitlRepository.findByToolUseId(toolUseId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        CoordHitlTicketEntity entity = existing.get();
        entity.setResolvedAllow(allow);
        entity.setDenyMessage(denyMessage);
        hitlRepository.save(entity);
        return Optional.of(toHitl(entity));
    }

    @Override
    @Transactional
    public void deleteHitlTicket(String toolUseId) {
        hitlRepository.deleteByToolUseId(toolUseId);
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
                e.getToolName(),
                e.getInputJson(),
                e.getResolvedAllow(),
                e.getDenyMessage(),
                e.getCreatedAt(),
                e.getExpiresAt());
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
