/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

import java.util.Optional;

/** Atomic handoff admission and lifecycle store. */
public interface HitlResumeCoordinator {

    HitlHandoffRecord open(HitlOpenRequest request);

    /** Verify a claim credential and coordinates without consuming the open handoff. */
    HitlHandoffRecord validateClaim(HitlClaimRequest request);

    HitlHandoffRecord claim(HitlClaimRequest request);

    HitlHandoffRecord cancel(HitlCancelRequest request);

    HitlHandoffRecord transition(
            String handoffId, HitlHandoffStatus expected, HitlHandoffStatus target);

    Optional<HitlHandoffRecord> get(String handoffId);

    boolean hasOpenHandoff(HitlExecutionKey executionKey);

    HitlDurabilityCapability durabilityCapability();
}
