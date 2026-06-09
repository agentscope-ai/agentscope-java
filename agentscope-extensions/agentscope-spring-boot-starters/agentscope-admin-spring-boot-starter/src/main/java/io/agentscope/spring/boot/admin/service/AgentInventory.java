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
package io.agentscope.spring.boot.admin.service;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.Model;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.ShutdownState;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.spring.boot.admin.registry.AgentDescriptor;
import io.agentscope.spring.boot.admin.registry.AgentRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Aggregates inventory information across {@link AgentRegistry}, Spring-managed {@link Toolkit}
 * and {@link Model} beans, and the {@link GracefulShutdownManager}.
 *
 * <p>Read-only by design; consumed by every Actuator endpoint in this starter.
 */
public final class AgentInventory {

    private final AgentRegistry agentRegistry;
    private final ObjectProvider<Map<String, Toolkit>> toolkitBeans;
    private final ObjectProvider<Map<String, Model>> modelBeans;

    public AgentInventory(
            AgentRegistry agentRegistry,
            ObjectProvider<Map<String, Toolkit>> toolkitBeans,
            ObjectProvider<Map<String, Model>> modelBeans) {
        this.agentRegistry = agentRegistry;
        this.toolkitBeans = toolkitBeans;
        this.modelBeans = modelBeans;
    }

    public List<AgentDescriptor> agents() {
        List<Agent> agents = agentRegistry.list();
        List<AgentDescriptor> out = new ArrayList<>(agents.size());
        for (Agent agent : agents) {
            try {
                out.add(AgentDescriptor.of(agent));
            } catch (RuntimeException ignored) {
                // Skip pathological agents rather than fail the whole inventory.
            }
        }
        return out;
    }

    /** Returns {@code beanName -> [toolName...]}. */
    public Map<String, List<String>> toolsByBean() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Map<String, Toolkit> beans = toolkitBeans.getIfAvailable();
        if (beans == null) return out;
        for (Map.Entry<String, Toolkit> e : beans.entrySet()) {
            try {
                Set<String> names = e.getValue().getToolNames();
                List<String> sorted = new ArrayList<>(names);
                sorted.sort(String::compareTo);
                out.put(e.getKey(), sorted);
            } catch (RuntimeException ex) {
                out.put(e.getKey(), List.of());
            }
        }
        return out;
    }

    /** Returns {@code beanName -> modelName}. */
    public Map<String, String> modelsByBean() {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Model> beans = modelBeans.getIfAvailable();
        if (beans == null) return out;
        for (Map.Entry<String, Model> e : beans.entrySet()) {
            try {
                out.put(e.getKey(), e.getValue().getModelName());
            } catch (RuntimeException ex) {
                out.put(e.getKey(), "unknown");
            }
        }
        return out;
    }

    /** Process-wide status fields derived from {@link GracefulShutdownManager}. */
    public Map<String, Object> processStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        GracefulShutdownManager m = GracefulShutdownManager.getInstance();
        ShutdownState state = m.getState();
        out.put("shutdown_state", state == null ? "UNKNOWN" : state.name());
        out.put("accepting_requests", m.isAcceptingRequests());
        out.put("active_requests", m.getActiveRequestCount());
        out.put("registered_agents", agentRegistry.size());
        return out;
    }
}
