/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.util.List;

/** Request to persist a newly-open durable handoff. */
public record HitlOpenRequest(
        String taskId,
        String contextId,
        HitlExecutionKey executionKey,
        A2aHandoffType type,
        List<ToolUseBlock> pendingTools,
        String nextResumeToken,
        Duration ttl,
        String claimedHandoffId) {

    public HitlOpenRequest {
        pendingTools = pendingTools == null ? List.of() : List.copyOf(pendingTools);
    }

    @Override
    public String toString() {
        return "HitlOpenRequest[taskId="
                + taskId
                + ", contextId="
                + contextId
                + ", executionKey="
                + executionKey
                + ", type="
                + type
                + ", pendingTools="
                + pendingTools.size()
                + ", nextResumeToken=<redacted>, ttl="
                + ttl
                + ", claimedHandoffId="
                + claimedHandoffId
                + ']';
    }
}
