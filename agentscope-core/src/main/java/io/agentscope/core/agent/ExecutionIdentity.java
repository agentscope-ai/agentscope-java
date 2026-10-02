package io.agentscope.core.agent;

import java.util.Objects;

/** Immutable correlation shared by a live execution and its committed session facts. */
public record ExecutionIdentity(String agentId, String sessionId, String turnId, String runId) {
    public static final String CONTEXT_KEY = "agentscope.execution.identity";

    public ExecutionIdentity {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(runId, "runId");
        if (agentId.isBlank() || sessionId.isBlank() || turnId.isBlank() || runId.isBlank())
            throw new IllegalArgumentException("Execution identity must not be blank");
    }
}
