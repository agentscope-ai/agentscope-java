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

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.spring.boot.admin.properties.AdminProperties;
import io.agentscope.spring.boot.admin.service.AgentInventory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * {@code GET /actuator/agentscope-doctor}: opinionated self-check.
 *
 * <p>Each check has an outcome ({@code ok}, {@code warn}, {@code error}) plus a short message.
 * Designed to complement Spring Boot's own {@code /actuator/health} — health says "is the process
 * up", doctor says "is the AgentScope deployment in a sensible state".
 */
@Endpoint(id = "agentscope-doctor")
public class AgentscopeDoctorEndpoint {

    private final AdminProperties properties;
    private final AgentInventory inventory;
    private final ObjectProvider<AgentStateStore> sessions;

    public AgentscopeDoctorEndpoint(
            AdminProperties properties,
            AgentInventory inventory,
            ObjectProvider<AgentStateStore> sessions) {
        this.properties = properties;
        this.inventory = inventory;
        this.sessions = sessions;
    }

    @ReadOperation
    public Map<String, Object> diagnose() {
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(
                check(
                        "admin.enabled",
                        properties.isEnabled(),
                        "agentscope.admin.enabled=true",
                        "admin starter is registered but disabled — endpoints would not load"));
        checks.add(
                check(
                        "admin.write_token",
                        !properties.isWriteEnabled() || !properties.getWriteToken().isBlank(),
                        "write token configured or writes disabled",
                        "write operations are enabled without a token; consider setting "
                                + "agentscope.admin.write-token in production"));
        AgentStateStore stateStore = sessions.getIfAvailable();
        checks.add(
                check(
                        "stateStore.bean",
                        stateStore != null,
                        "found AgentStateStore bean: "
                                + (stateStore == null
                                        ? "<none>"
                                        : stateStore.getClass().getSimpleName()),
                        "no AgentStateStore bean found — /sessions listing will be empty"));
        checks.add(
                check(
                        "agents.registered",
                        inventory.agents().size() > 0,
                        inventory.agents().size() + " agent(s) registered",
                        "no agents registered with AgentRegistry — admin operations will return"
                                + " 404"));
        checks.add(
                check(
                        "toolkits.bean",
                        !inventory.toolsByBean().isEmpty(),
                        inventory.toolsByBean().size() + " Toolkit bean(s)",
                        "no Toolkit beans found — /tools endpoint will be empty"));
        checks.add(
                check(
                        "models.bean",
                        !inventory.modelsByBean().isEmpty(),
                        inventory.modelsByBean().size() + " Model bean(s)",
                        "no Model beans found — agents will not be able to call any LLM"));

        boolean anyError = checks.stream().anyMatch(c -> "error".equals(c.get("outcome")));
        boolean anyWarn = checks.stream().anyMatch(c -> "warn".equals(c.get("outcome")));
        String overall = anyError ? "error" : anyWarn ? "warn" : "ok";

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("overall", overall);
        report.put("checks", checks);
        return report;
    }

    private static Map<String, Object> check(String id, boolean ok, String okMsg, String warnMsg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("outcome", ok ? "ok" : "warn");
        m.put("message", ok ? okMsg : warnMsg);
        return m;
    }
}
