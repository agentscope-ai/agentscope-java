/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManagedAgentProjectionTest {
    @Test
    void projectsActualDisabledNativeAndEnabledMcpPoliciesWithoutCredentials() {
        var snapshot =
                snapshot(
                        Map.of(
                                "enabled",
                                true,
                                "permissionPolicy",
                                Map.of("type", "always_allow")));
        ManagedSessionProtocol.validateAgent(Map.of("agentSnapshot", snapshot));
        var tools = ManagedSessionProtocol.agentToolsets(snapshot);
        assertEquals("agent_toolset_20260401", tools.get(0).get("type"));
        assertEquals(
                Map.of("enabled", false, "permission_policy", Map.of("type", "always_allow")),
                tools.get(0).get("default_config"));
        assertEquals(
                Map.of("enabled", true, "permission_policy", Map.of("type", "always_allow")),
                tools.get(1).get("default_config"));
        assertEquals(List.of(), tools.get(1).get("configs"));
        assertFalse(tools.toString().contains("private-header"));
    }

    @Test
    void rejectsImplicitMcpConfirmationAndUnmappedSkillsAndAllowsDisabledConfirmation() {
        assertThrows(
                ManagedStateException.class,
                () ->
                        ManagedSessionProtocol.validateAgent(
                                Map.of("agentSnapshot", snapshot(Map.of()))));
        assertThrows(
                ManagedStateException.class,
                () ->
                        ManagedSessionProtocol.validateAgent(
                                Map.of(
                                        "agentSnapshot",
                                        Map.of(
                                                "skills",
                                                List.of(
                                                        Map.of(
                                                                "type",
                                                                "workspace",
                                                                "name",
                                                                "skill"))))));
        assertDoesNotThrow(
                () ->
                        ManagedSessionProtocol.validateAgent(
                                Map.of("agentSnapshot", snapshot(Map.of("enabled", false)))));
    }

    private Map<String, Object> snapshot(Map<String, Object> defaults) {
        return Map.of(
                "tools",
                List.of(
                        Map.of("type", "agent_toolset", "defaultConfig", Map.of("enabled", false)),
                        Map.of(
                                "type",
                                "mcp_toolset",
                                "mcpServerName",
                                "longwait",
                                "defaultConfig",
                                defaults)),
                "mcpServers",
                List.of(
                        Map.of(
                                "name",
                                "longwait",
                                "url",
                                "http://fixture/mcp",
                                "headers",
                                Map.of("Authorization", "private-header"))));
    }
}
