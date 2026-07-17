/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

import java.time.Duration;

/** Request-scoped durable handoff services supplied to the AgentEvent encoder. */
public record HitlEncodingContext(
        HitlResumeCoordinator coordinator,
        HitlExecutionKey executionKey,
        String nextResumeToken,
        Duration handoffTtl,
        String claimedHandoffId) {

    public boolean canOpenDurableHandoff() {
        return coordinator != null
                && executionKey != null
                && nextResumeToken != null
                && !nextResumeToken.isBlank();
    }

    @Override
    public String toString() {
        return "HitlEncodingContext[coordinator="
                + (coordinator == null ? "null" : coordinator.getClass().getName())
                + ", executionKey="
                + executionKey
                + ", nextResumeToken=<redacted>, handoffTtl="
                + handoffTtl
                + ", claimedHandoffId="
                + claimedHandoffId
                + ']';
    }
}
