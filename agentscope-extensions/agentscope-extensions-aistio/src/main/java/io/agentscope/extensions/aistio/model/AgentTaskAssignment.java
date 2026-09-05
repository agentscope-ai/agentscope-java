/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.aistio.model;

/** Immutable ASDP execution envelope for one durable AgentTask. */
public record AgentTaskAssignment(
        String attemptId,
        String agentTaskId,
        String runId,
        String nodeId,
        long generation,
        String command,
        String contextUrl,
        String taskToken,
        String attemptToken,
        String sessionId,
        byte[] payload,
        long timestamp) {

    public AgentTaskAssignment {
        payload = payload == null ? new byte[0] : payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
