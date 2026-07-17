/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Credential-free persistent handoff state. The token is represented only by its digest. */
public record HitlHandoffRecord(
        String handoffId,
        String taskId,
        String contextId,
        HitlExecutionKey executionKey,
        A2aHandoffType type,
        List<ToolUseBlock> pendingTools,
        String pendingFingerprint,
        String tokenDigest,
        Instant expiresAt,
        HitlHandoffStatus status) {

    public HitlHandoffRecord {
        pendingTools = pendingTools == null ? List.of() : List.copyOf(pendingTools);
        executionKey = Objects.requireNonNull(executionKey, "executionKey");
        type = Objects.requireNonNull(type, "type");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        status = Objects.requireNonNull(status, "status");
    }

    public HitlHandoffRecord withStatus(HitlHandoffStatus newStatus) {
        return new HitlHandoffRecord(
                handoffId,
                taskId,
                contextId,
                executionKey,
                type,
                pendingTools,
                pendingFingerprint,
                tokenDigest,
                expiresAt,
                newStatus);
    }

    @Override
    public String toString() {
        return "HitlHandoffRecord[handoffId="
                + handoffId
                + ", taskId="
                + taskId
                + ", contextId="
                + contextId
                + ", executionKey="
                + executionKey
                + ", type="
                + type
                + ", pendingTools="
                + pendingTools.size()
                + ", pendingFingerprint="
                + pendingFingerprint
                + ", tokenDigest=<redacted>, expiresAt="
                + expiresAt
                + ", status="
                + status
                + ']';
    }
}
