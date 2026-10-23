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

import io.agentscope.core.agent.Agent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Single-node, in-memory {@link AgentRegistry} suitable as the starter default. */
public final class InMemoryAgentRegistry implements AgentRegistry {

    private final ConcurrentMap<String, Agent> agents = new ConcurrentHashMap<>();

    @Override
    public void register(Agent agent) {
        Objects.requireNonNull(agent, "agent");
        agents.put(agent.getAgentId(), agent);
    }

    @Override
    public void unregister(String agentId) {
        if (agentId != null) {
            agents.remove(agentId);
        }
    }

    @Override
    public Optional<Agent> find(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    @Override
    public List<Agent> list() {
        return new ArrayList<>(agents.values());
    }

    @Override
    public int size() {
        return agents.size();
    }
}
