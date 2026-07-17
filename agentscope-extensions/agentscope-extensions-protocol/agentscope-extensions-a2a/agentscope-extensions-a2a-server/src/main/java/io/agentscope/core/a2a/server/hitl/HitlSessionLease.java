/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

import java.time.Duration;

/** Serializes normal and resume turns for one logical AgentScope session. */
public interface HitlSessionLease {

    HitlLeaseHandle acquire(HitlExecutionKey executionKey, Duration ttl);

    HitlDurabilityCapability durabilityCapability();
}
