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
import java.util.List;
import java.util.Optional;

/**
 * SPI for tracking live agents the admin surface can inspect or act on.
 *
 * <p>AgentScope-Java exposes {@code ReActAgent} as a prototype-scoped bean by default, so a
 * Spring {@code Map<String, Agent>} scan will only find singleton agents (or none). To make the
 * inventory complete, application code should call {@link #register(Agent)} after constructing a
 * long-lived agent — typically in a {@code @PostConstruct} on a Service that owns it, or wrapped
 * inside the agent factory.
 *
 * <p>The default {@code InMemoryAgentRegistry} is fine for single-node deployments; for
 * cluster-aware deployments, swap in an implementation backed by Nacos, Redis, or another shared
 * store.
 */
public interface AgentRegistry {

    void register(Agent agent);

    void unregister(String agentId);

    Optional<Agent> find(String agentId);

    /** All registered agents in insertion order. */
    List<Agent> list();

    int size();
}
