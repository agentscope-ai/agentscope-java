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
package io.agentscope.core.agui.registry;

import io.agentscope.core.agent.Agent;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Registry for managing agents accessible via the AG-UI protocol.
 *
 * <p>This registry supports two types of agent registration:
 * <ul>
 *   <li>Singleton agents - A single shared instance</li>
 *   <li>Factory agents - A new instance created per request</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * AguiAgentRegistry registry = new AguiAgentRegistry();
 *
 * // Register a singleton agent
 * registry.register("assistant", myAgent);
 *
 * // Register a factory for per-request agents
 * registry.registerFactory("chat", () -> createNewAgent());
 *
 * // Get an agent
 * Optional<Agent> agent = registry.getAgent("assistant");
 * }</pre>
 */
public class AguiAgentRegistry {

    private final Map<String, Agent> singletonAgents = new ConcurrentHashMap<>();
    private final Map<String, Supplier<Agent>> agentFactories = new ConcurrentHashMap<>();

    /**
     * Register a singleton agent with the given ID.
     *
     * <p>The same agent instance will be returned for all requests with this ID.
     *
     * @param agentId The agent ID
     * @param agent The agent instance
     */
    public void register(String agentId, Agent agent) {
        if (agentId == null || agentId.isEmpty()) {
            throw new IllegalArgumentException("Agent ID cannot be null or empty");
        }
        if (agent == null) {
            throw new IllegalArgumentException("Agent cannot be null");
        }
        singletonAgents.put(agentId, agent);
    }

    /**
     * Register an agent factory with the given ID.
     *
     * <p>A new agent instance will be created for each request with this ID.
     * This is useful for stateful agents that should not be shared.
     *
     * @param agentId The agent ID
     * @param factory The factory that creates new agent instances
     */
    public void registerFactory(String agentId, Supplier<Agent> factory) {
        if (agentId == null || agentId.isEmpty()) {
            throw new IllegalArgumentException("Agent ID cannot be null or empty");
        }
        if (factory == null) {
            throw new IllegalArgumentException("Factory cannot be null");
        }
        agentFactories.put(agentId, factory);
    }

    /**
     * Get an agent by ID.
     *
     * <p>The agent is resolved in the following order:
     * <ol>
     *   <li>Check for a registered factory and create a new instance</li>
     *   <li>Return the singleton agent if registered</li>
     * </ol>
     *
     * @param agentId The agent ID
     * @return An Optional containing the agent, or empty if not found
     */
    public Optional<Agent> getAgent(String agentId) {
        // First check for a factory
        Supplier<Agent> factory = agentFactories.get(agentId);
        if (factory != null) {
            return Optional.of(factory.get());
        }

        // Fall back to singleton
        return Optional.ofNullable(singletonAgents.get(agentId));
    }

    /**
     * Check if an agent is registered with the given ID.
     *
     * @param agentId The agent ID
     * @return true if an agent is registered
     */
    public boolean hasAgent(String agentId) {
        return agentFactories.containsKey(agentId) || singletonAgents.containsKey(agentId);
    }

    /**
     * Unregister an agent by ID.
     *
     * @param agentId The agent ID
     * @return true if an agent was unregistered
     */
    public boolean unregister(String agentId) {
        boolean removedFactory = agentFactories.remove(agentId) != null;
        boolean removedSingleton = singletonAgents.remove(agentId) != null;
        return removedFactory || removedSingleton;
    }

    /**
     * Clear all registered agents.
     */
    public void clear() {
        agentFactories.clear();
        singletonAgents.clear();
    }

    /**
     * Get the number of registered agents (both factories and singletons).
     *
     * @return The total count of registered agents
     */
    public int size() {
        return agentFactories.size() + singletonAgents.size();
    }
}
