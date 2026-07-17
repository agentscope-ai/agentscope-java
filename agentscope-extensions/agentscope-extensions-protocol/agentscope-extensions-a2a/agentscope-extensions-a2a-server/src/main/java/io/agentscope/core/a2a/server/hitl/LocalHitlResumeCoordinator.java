/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local coordinator used by the backward-compatible local durability mode. */
public class LocalHitlResumeCoordinator implements HitlResumeCoordinator {

    private final Clock clock;
    private final Map<String, HitlHandoffRecord> records = new ConcurrentHashMap<>();
    private final Map<String, String> openBySession = new ConcurrentHashMap<>();

    public LocalHitlResumeCoordinator() {
        this(Clock.systemUTC());
    }

    LocalHitlResumeCoordinator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized HitlHandoffRecord open(HitlOpenRequest request) {
        requireOpenRequest(request);
        if (request.claimedHandoffId() != null) {
            transition(
                    request.claimedHandoffId(),
                    HitlHandoffStatus.CLAIMED,
                    HitlHandoffStatus.SUPERSEDED);
        }
        String sessionKey = request.executionKey().sessionKey();
        if (openBySession.containsKey(sessionKey)) {
            throw new HitlResumeRejectedException("Session already has an open HITL handoff");
        }
        String handoffId = UUID.randomUUID().toString();
        String fingerprint = HitlTokenDigests.pendingFingerprint(request.pendingTools());
        String digest =
                HitlTokenDigests.boundTokenDigest(
                        request.taskId(),
                        request.contextId(),
                        handoffId,
                        request.executionKey(),
                        fingerprint,
                        request.nextResumeToken());
        Duration ttl = request.ttl() == null ? Duration.ofDays(7) : request.ttl();
        HitlHandoffRecord record =
                new HitlHandoffRecord(
                        handoffId,
                        request.taskId(),
                        request.contextId(),
                        request.executionKey(),
                        request.type(),
                        request.pendingTools(),
                        fingerprint,
                        digest,
                        Instant.now(clock).plus(ttl),
                        HitlHandoffStatus.OPEN);
        records.put(handoffId, record);
        openBySession.put(sessionKey, handoffId);
        return record;
    }

    @Override
    public synchronized HitlHandoffRecord validateClaim(HitlClaimRequest request) {
        HitlHandoffRecord record = requireOpen(request.handoffId());
        verifyBinding(record, request.taskId(), request.contextId(), request.resumeToken());
        return record;
    }

    @Override
    public synchronized HitlHandoffRecord claim(HitlClaimRequest request) {
        HitlHandoffRecord record = validateClaim(request);
        HitlHandoffRecord claimed = record.withStatus(HitlHandoffStatus.CLAIMED);
        records.put(record.handoffId(), claimed);
        openBySession.remove(record.executionKey().sessionKey(), record.handoffId());
        return claimed;
    }

    @Override
    public synchronized HitlHandoffRecord cancel(HitlCancelRequest request) {
        HitlHandoffRecord record = requireOpen(request.handoffId());
        verifyBinding(record, request.taskId(), request.contextId(), request.resumeToken());
        HitlHandoffRecord canceled = record.withStatus(HitlHandoffStatus.CANCELED);
        records.put(record.handoffId(), canceled);
        openBySession.remove(record.executionKey().sessionKey(), record.handoffId());
        return canceled;
    }

    @Override
    public synchronized HitlHandoffRecord transition(
            String handoffId, HitlHandoffStatus expected, HitlHandoffStatus target) {
        HitlHandoffRecord record = records.get(handoffId);
        if (record == null || record.status() != expected) {
            throw new HitlResumeRejectedException("HITL handoff state conflict for " + handoffId);
        }
        HitlHandoffRecord updated = record.withStatus(target);
        records.put(handoffId, updated);
        if (expected == HitlHandoffStatus.OPEN) {
            openBySession.remove(record.executionKey().sessionKey(), handoffId);
        }
        return updated;
    }

    @Override
    public synchronized Optional<HitlHandoffRecord> get(String handoffId) {
        HitlHandoffRecord record = records.get(handoffId);
        if (record != null
                && record.status() == HitlHandoffStatus.OPEN
                && !record.expiresAt().isAfter(Instant.now(clock))) {
            record = record.withStatus(HitlHandoffStatus.EXPIRED);
            records.put(handoffId, record);
            openBySession.remove(record.executionKey().sessionKey(), handoffId);
        }
        return Optional.ofNullable(record);
    }

    @Override
    public synchronized boolean hasOpenHandoff(HitlExecutionKey executionKey) {
        if (executionKey == null) {
            return false;
        }
        String handoffId = openBySession.get(executionKey.sessionKey());
        return handoffId != null
                && get(handoffId)
                        .map(record -> record.status() == HitlHandoffStatus.OPEN)
                        .orElse(false);
    }

    @Override
    public HitlDurabilityCapability durabilityCapability() {
        return HitlDurabilityCapability.LOCAL;
    }

    private HitlHandoffRecord requireOpen(String handoffId) {
        HitlHandoffRecord record = records.get(handoffId);
        if (record == null) {
            throw new HitlResumeRejectedException("Unknown HITL handoff");
        }
        if (record.status() != HitlHandoffStatus.OPEN) {
            throw new HitlResumeRejectedException("HITL handoff is no longer open");
        }
        if (!record.expiresAt().isAfter(Instant.now(clock))) {
            HitlHandoffRecord expired = record.withStatus(HitlHandoffStatus.EXPIRED);
            records.put(record.handoffId(), expired);
            openBySession.remove(record.executionKey().sessionKey(), record.handoffId());
            throw new HitlResumeRejectedException("HITL handoff has expired");
        }
        return record;
    }

    private void verifyBinding(
            HitlHandoffRecord record, String taskId, String contextId, String token) {
        if (!record.taskId().equals(taskId) || !record.contextId().equals(contextId)) {
            throw new HitlResumeRejectedException("HITL handoff coordinates do not match");
        }
        String actual =
                HitlTokenDigests.boundTokenDigest(
                        taskId,
                        contextId,
                        record.handoffId(),
                        record.executionKey(),
                        record.pendingFingerprint(),
                        token);
        if (!HitlTokenDigests.constantTimeEquals(record.tokenDigest(), actual)) {
            throw new HitlResumeRejectedException("HITL resume credential was rejected");
        }
    }

    private void requireOpenRequest(HitlOpenRequest request) {
        if (request == null
                || request.taskId() == null
                || request.taskId().isBlank()
                || request.contextId() == null
                || request.contextId().isBlank()
                || request.executionKey() == null
                || request.type() == null
                || request.pendingTools().isEmpty()
                || request.nextResumeToken() == null
                || request.nextResumeToken().isBlank()) {
            throw new IllegalArgumentException("Incomplete HITL open request");
        }
    }
}
