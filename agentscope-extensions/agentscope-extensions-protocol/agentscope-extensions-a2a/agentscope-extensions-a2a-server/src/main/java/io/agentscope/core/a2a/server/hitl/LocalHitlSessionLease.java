/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local lease used by the backward-compatible local HITL mode. */
public final class LocalHitlSessionLease implements HitlSessionLease {

    private final Map<String, String> owners = new ConcurrentHashMap<>();

    @Override
    public HitlLeaseHandle acquire(HitlExecutionKey executionKey, Duration ttl) {
        if (executionKey == null) {
            throw new IllegalArgumentException("executionKey must not be null");
        }
        String sessionKey = executionKey.sessionKey();
        String owner = UUID.randomUUID().toString();
        if (owners.putIfAbsent(sessionKey, owner) != null) {
            throw new HitlResumeRejectedException(
                    "Another A2A turn is already executing for this logical session");
        }
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                owners.remove(sessionKey, owner);
            }
        };
    }

    @Override
    public HitlDurabilityCapability durabilityCapability() {
        return HitlDurabilityCapability.LOCAL;
    }
}
