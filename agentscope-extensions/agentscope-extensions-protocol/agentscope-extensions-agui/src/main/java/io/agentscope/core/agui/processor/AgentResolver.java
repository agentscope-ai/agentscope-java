/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.processor;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.AguiException;

/**
 * Interface for resolving agents from various sources.
 *
 * <p>This interface abstracts the agent resolution logic, allowing different
 * implementations for different scenarios (e.g., simple registry lookup,
 * session-based resolution with memory management).
 */
public interface AgentResolver {

    /**
     * Resolve an agent by its ID and thread ID.
     *
     * @param agentId The agent ID to resolve
     * @param threadId The thread ID for session management
     * @return The resolved agent
     * @throws AguiException.AgentNotFoundException if the agent is not found
     */
    Agent resolveAgent(String agentId, String threadId);

    /**
     * Resolve an agent by its ID, thread ID and user ID.
     *
     * <p>The default implementation ignores {@code userId}. Session-aware resolvers should override
     * this so tenants with the same thread id do not share an agent instance.
     *
     * @param agentId The agent ID to resolve
     * @param threadId The thread ID for session management
     * @param userId The user ID, may be {@code null} for anonymous
     * @return The resolved agent
     * @throws AguiException.AgentNotFoundException if the agent is not found
     */
    default Agent resolveAgent(String agentId, String threadId, String userId) {
        return resolveAgent(agentId, threadId);
    }

    /**
     * Check if a thread has existing memory/conversation history.
     *
     * <p>The built-in AG-UI pipeline no longer invokes this method: incoming messages are always
     * forwarded in full and deduplicated against the persisted AgentState context by the {@code
     * onAgentStateBound} callback registered in {@code AguiAgentAdapter#buildRuntimeContext}. It
     * is retained for custom resolvers that still want an existence probe.
     *
     * @param runtimeContext The runtime context identifying the thread and user
     * @return true if the thread has existing memory
     */
    boolean hasMemory(RuntimeContext runtimeContext);
}
