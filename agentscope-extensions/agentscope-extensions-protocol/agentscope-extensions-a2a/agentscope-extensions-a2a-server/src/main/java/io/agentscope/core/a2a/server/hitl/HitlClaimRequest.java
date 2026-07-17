/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

/** Single-use token claim of an open handoff. */
public record HitlClaimRequest(
        String taskId, String contextId, String handoffId, String resumeToken) {

    @Override
    public String toString() {
        return "HitlClaimRequest[taskId="
                + taskId
                + ", contextId="
                + contextId
                + ", handoffId="
                + handoffId
                + ", resumeToken=<redacted>]";
    }
}
