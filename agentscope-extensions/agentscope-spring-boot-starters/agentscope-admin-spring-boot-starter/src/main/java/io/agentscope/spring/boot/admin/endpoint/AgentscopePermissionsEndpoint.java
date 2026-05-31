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
package io.agentscope.spring.boot.admin.endpoint;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.spring.boot.admin.registry.AgentRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

/**
 * {@code GET /actuator/agentscope-permissions}: enumerate the permission posture of every
 * registered {@link ReActAgent}.
 *
 * <p>Read-only — mutating rules requires changes in agentscope-core to support live rule edits,
 * which is tracked in {@code ADMIN_OPS_API_PLAN.md} for Phase 3.
 */
@Endpoint(id = "agentscope-permissions")
public class AgentscopePermissionsEndpoint {

    private final AgentRegistry registry;

    public AgentscopePermissionsEndpoint(AgentRegistry registry) {
        this.registry = registry;
    }

    @ReadOperation
    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Agent agent : registry.list()) {
            if (agent instanceof ReActAgent react) {
                Map<String, Object> entry = describe(react);
                if (entry != null) out.add(entry);
            }
        }
        return out;
    }

    @ReadOperation
    public Map<String, Object> getOne(@Selector String agentId) {
        return registry.find(agentId)
                .filter(a -> a instanceof ReActAgent)
                .map(a -> (ReActAgent) a)
                .map(this::describe)
                .orElseGet(
                        () -> Map.of(
                                "error", "no ReActAgent registered with id=" + agentId));
    }

    private Map<String, Object> describe(ReActAgent react) {
        PermissionEngine engine;
        try {
            engine = react.getPermissionEngine();
        } catch (RuntimeException e) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agent_id", react.getAgentId());
        out.put("agent_name", react.getName());
        if (engine == null) {
            out.put("mode", "NONE");
            return out;
        }
        if (engine.getContext() != null && engine.getContext().getMode() != null) {
            out.put("mode", engine.getContext().getMode().name());
        } else {
            out.put("mode", "UNKNOWN");
        }
        out.put("allow", flatten(engine.getAllowRules()));
        out.put("deny", flatten(engine.getDenyRules()));
        out.put("ask", flatten(engine.getAskRules()));
        return out;
    }

    private static Map<String, List<Map<String, Object>>> flatten(
            Map<String, List<PermissionRule>> raw) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (Map.Entry<String, List<PermissionRule>> e : raw.entrySet()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (PermissionRule rule : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rule", rule.toString());
                items.add(m);
            }
            out.put(e.getKey(), items);
        }
        return out;
    }
}
