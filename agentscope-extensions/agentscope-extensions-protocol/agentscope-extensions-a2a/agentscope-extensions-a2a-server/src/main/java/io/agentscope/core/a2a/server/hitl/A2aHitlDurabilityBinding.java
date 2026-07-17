/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

import org.a2aproject.sdk.server.tasks.TaskStore;

/**
 * One coherent durable backend for the A2A HITL control plane.
 *
 * <p>The business Agent remains solely responsible for its AgentStateStore. This binding persists
 * A2A task, handoff, admission, and lease state only.
 */
public interface A2aHitlDurabilityBinding extends AutoCloseable {

    TaskStore taskStore();

    HitlResumeCoordinator resumeCoordinator();

    HitlSessionLease sessionLease();

    /** Performs provider-specific reachability and topology checks. */
    HitlDurabilityVerification verify();

    /** Starts provider-specific background reconciliation after verification succeeds. */
    default void start() {}

    @Override
    default void close() {}
}
