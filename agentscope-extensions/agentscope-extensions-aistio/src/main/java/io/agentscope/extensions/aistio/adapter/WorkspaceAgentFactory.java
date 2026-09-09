/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.aistio.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.extensions.aistio.model.AgentTaskAssignment;
import io.agentscope.harness.agent.HarnessAgent;

/** Explicit application opt-in to consume the immutable control-plane definition per attempt. */
@FunctionalInterface
public interface WorkspaceAgentFactory {
    /** Creates a fresh attempt-owned agent, including its tools and credential policy. */
    HarnessAgent create(JsonNode definition, AgentTaskAssignment assignment);
}
