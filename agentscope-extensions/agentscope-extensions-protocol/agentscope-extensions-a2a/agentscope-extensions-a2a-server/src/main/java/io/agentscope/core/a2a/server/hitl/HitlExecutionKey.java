/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

/** Stable AgentScope execution coordinates captured when a handoff is opened. */
public record HitlExecutionKey(String userId, String logicalAgentId, String contextId) {

    public HitlExecutionKey {
        userId = normalize(userId);
        logicalAgentId = normalize(logicalAgentId);
        contextId = normalize(contextId);
        if (logicalAgentId.isEmpty()) {
            throw new IllegalArgumentException("logicalAgentId must not be blank");
        }
        if (contextId.isEmpty()) {
            throw new IllegalArgumentException("contextId must not be blank");
        }
    }

    public String sessionKey() {
        return userId + '\u001f' + logicalAgentId + '\u001f' + contextId;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
