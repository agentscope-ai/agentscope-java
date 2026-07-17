/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

/** Durable handoff lifecycle. */
public enum HitlHandoffStatus {
    OPEN,
    CLAIMED,
    COMPLETED,
    SUPERSEDED,
    FAILED,
    CANCELED,
    EXPIRED,
    RECOVERY_REQUIRED
}
