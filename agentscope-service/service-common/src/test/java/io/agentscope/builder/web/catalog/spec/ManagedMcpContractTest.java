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
package io.agentscope.builder.web.catalog.spec;

import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.AgentToolset;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.McpServerSpec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.PermissionPolicy;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.ToolConfigEntry;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.ToolDefaultConfig;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ManagedMcpContractTest {
    private McpServerSpec server(String name) {
        return new McpServerSpec(
                name,
                "url",
                "https://example.test/mcp",
                "http",
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void compilesMcpDenyByDefaultAndServerScopedPolicies() {
        var toolset =
                new AgentToolset(
                        "mcp_toolset",
                        new ToolDefaultConfig(false, null),
                        List.of(
                                new ToolConfigEntry("read", true, null),
                                new ToolConfigEntry("write", false, PermissionPolicy.of("deny"))),
                        "crm");
        var config = AgentSpecCodec.toToolsConfig(List.of(toolset), List.of(server("crm")));
        var mcp = config.getMcpServers().get("crm");
        Assertions.assertFalse(mcp.isDefaultToolsEnabled());
        Assertions.assertEquals(List.of("read"), mcp.getEnableTools());
        Assertions.assertEquals(List.of("write"), mcp.getDisableTools());
        Assertions.assertTrue(mcp.isPrefixToolNames());
        Assertions.assertEquals(
                "deny", AgentSpecCodec.toPermissionPolicyMap(List.of(toolset)).get("crm__*"));
        Assertions.assertEquals(
                "always_ask",
                AgentSpecCodec.toPermissionPolicyMap(List.of(toolset)).get("crm__read"));
        Assertions.assertEquals(
                "deny", AgentSpecCodec.toPermissionPolicyMap(List.of(toolset)).get("crm__write"));
    }

    @Test
    void rejectsDuplicateAndDanglingServerReferences() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                        AgentSpecCodec.toToolsConfig(
                                List.of(), List.of(server("crm"), server("crm"))));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                        AgentSpecCodec.toToolsConfig(
                                List.of(new AgentToolset("mcp_toolset", null, null, "missing")),
                                List.of(server("crm"))));
    }

    @Test
    void builtinOverridesDoNotNarrowDefaultEnabledTools() {
        var config =
                AgentSpecCodec.toToolsConfig(
                        List.of(
                                new AgentToolset(
                                        "agent_toolset",
                                        new ToolDefaultConfig(true, null),
                                        List.of(new ToolConfigEntry("read", true, null)),
                                        null)),
                        List.of());
        Assertions.assertTrue(
                io.agentscope.harness.agent.tools.ToolFilter.isAllowed("execute", config));
    }
}
