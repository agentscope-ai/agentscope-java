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
package io.agentscope.harness.agent.subagent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Pure agent factory and invoker — knows how to create agents from registered factories and invoke
 * them with a prompt.
 *
 * <p>This is the <em>agent-internal</em> layer. It has <strong>no</strong> session registry, no lane
 * management, no run tracking. The
 * agent-internal {@link AgentSpawnTool} uses this directly for
 * lightweight subagent invocation.
 */
public final class DefaultAgentManager {

    private final Map<String, SubagentFactory> agentFactories;
    private final WorkspaceManager workspaceManager;

    public DefaultAgentManager(
            Map<String, SubagentFactory> agentFactories, WorkspaceManager workspaceManager) {
        this.agentFactories = Map.copyOf(agentFactories);
        this.workspaceManager = workspaceManager;
    }

    /** Whether a factory is registered for the given agent id. */
    public boolean hasAgent(String agentId) {
        return agentId != null && agentFactories.containsKey(agentId);
    }

    /** Immutable view of registered subagent factories keyed by {@code agent_id}. */
    public Map<String, SubagentFactory> getAgentFactories() {
        return agentFactories;
    }

    /**
     * Creates a new agent instance from the registered factory.
     *
     * @throws IllegalArgumentException if no factory is registered for the given id
     */
    public Agent createAgent(String agentId) {
        SubagentFactory factory = agentFactories.get(agentId);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown agent_id: " + agentId);
        }
        return factory.create();
    }

    /**
     * Invokes an agent with a user prompt. Handles both plain {@link Agent} and {@link
     * HarnessAgent} (injects {@link RuntimeContext} for the latter).
     */
    public Mono<Msg> invokeAgent(Agent agent, String sessionId, String prompt) {
        if (agent instanceof HarnessAgent harness) {
            RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();
            return harness.call(userMessage(prompt), ctx);
        }
        return agent.call(List.of(userMessage(prompt)));
    }

    public WorkspaceManager getWorkspaceManager() {
        return workspaceManager;
    }

    private static Msg userMessage(String prompt) {
        return Msg.builder().role(MsgRole.USER).textContent(prompt).build();
    }
}
