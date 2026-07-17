/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

/** Single-use token cancellation of an open handoff. */
public record HitlCancelRequest(
        String taskId, String contextId, String handoffId, String resumeToken) {

    @Override
    public String toString() {
        return "HitlCancelRequest[taskId="
                + taskId
                + ", contextId="
                + contextId
                + ", handoffId="
                + handoffId
                + ", resumeToken=<redacted>]";
    }
}
