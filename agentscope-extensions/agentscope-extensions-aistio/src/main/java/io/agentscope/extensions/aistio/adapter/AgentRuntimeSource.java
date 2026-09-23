/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.aistio.adapter;

import io.agentscope.core.agent.Agent;
import io.agentscope.extensions.aistio.model.Inventory;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Optional harness-/app-specific hooks kept out of {@code agentscope-extensions-aistio} so the
 * extension stays free of an {@code agentscope-harness} dependency. Wire from the host app (e.g.
 * paw) after the {@link AgentScopeAdapter} bean is created.
 */
public interface AgentRuntimeSource {

    default List<Inventory.SubagentInfo> listSubagents(Agent agent) {
        return List.of();
    }

    default List<Inventory.WorkspaceInfo> listWorkspaces(Agent agent) {
        return List.of();
    }

    default List<Map<String, Object>> listSubagentTasks(
            Agent agent, String sessionId, String userId) {
        return List.of();
    }

    default boolean cancelSubagentTask(
            Agent agent, String sessionId, String userId, String taskId) {
        return false;
    }

    /** Enrich the Definition snapshot ({@code GET /agentscope/info} → {@code agentConfig}). */
    default void enrichAgentConfig(Agent agent, Map<String, Object> agentConfig) {}

    /** Optional plan-file excerpt for Context {@code frameworkState}. */
    default Optional<String> readPlanExcerpt(
            Agent agent, String sessionId, String userId, String planFile) {
        return Optional.empty();
    }

    /**
     * Enter/exit plan mode for a session. Return {@code true} when handled (including no-op exit
     * when already inactive).
     */
    default boolean setPlanMode(Agent agent, String sessionId, String userId, boolean active) {
        return false;
    }
}
