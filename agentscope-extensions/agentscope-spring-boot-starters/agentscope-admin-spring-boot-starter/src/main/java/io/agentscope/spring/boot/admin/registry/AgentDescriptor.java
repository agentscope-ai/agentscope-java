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
package io.agentscope.spring.boot.admin.registry;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;

/**
 * Lightweight projection of an {@link Agent} for inventory views.
 *
 * <p>Holds only the data safe to serialize over an admin API — no model API keys, no in-memory
 * conversation buffers.
 */
public record AgentDescriptor(
        String agentId,
        String name,
        String description,
        String type,
        String modelName,
        String sessionId,
        Integer maxIters) {

    public static AgentDescriptor of(Agent agent) {
        String modelName = null;
        String sessionId = null;
        Integer maxIters = null;
        ReActAgent react = AgentResolver.unwrapReActAgent(agent);
        if (react != null) {
            try {
                if (react.getModel() != null) {
                    modelName = react.getModel().getModelName();
                }
            } catch (RuntimeException ignored) {
                // Some Model impls may lazily fail — never let an inventory call kill the admin
                // path.
            }
            try {
                if (react.getAgentState() != null) {
                    sessionId = react.getAgentState().getSessionId();
                }
            } catch (RuntimeException ignored) {
                // ditto
            }
            maxIters = react.getMaxIters();
        }
        return new AgentDescriptor(
                agent.getAgentId(),
                agent.getName(),
                agent.getDescription(),
                agent.getClass().getSimpleName(),
                modelName,
                sessionId,
                maxIters);
    }
}
