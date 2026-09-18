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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for #3178: the child's {@link ToolsConfig} must be derived from the <em>resolved
 * </em> parent config so that a parent whose MCP servers come from workspace {@code tools.json}
 * still propagates them, and an empty allow-list is a true MCP opt-out.
 *
 * <p>These are pure unit tests — they exercise {@link HarnessAgentBuilderSupport#childToolsConfig}
 * directly, with no MCP server, model or workspace involved.
 */
class SubagentToolsConfigPropagationTest {

    private static ToolsConfig parentWithMcp() {
        ToolsConfig parent = new ToolsConfig();
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        McpServerConfig cfg = new McpServerConfig();
        cfg.setTransport("http");
        cfg.setUrl("https://example.invalid/mcp");
        servers.put("industry-data", cfg);
        parent.setMcpServers(servers);
        return parent;
    }

    @Test
    void emptyAllowListIsATrueOptOutAndDoesNotInheritMcp() {
        ToolsConfig child = HarnessAgentBuilderSupport.childToolsConfig(parentWithMcp(), List.of());

        assertNotNull(child, "an empty allow-list must still yield an explicit child config");
        assertTrue(
                child.getMcpServers() == null || child.getMcpServers().isEmpty(),
                "an empty allow-list must not inherit the parent's MCP servers (#3178)");
    }

    @Test
    void nonEmptyAllowListKeepsMcpServersSoTheChildCanReRegisterThem() {
        ToolsConfig child =
                HarnessAgentBuilderSupport.childToolsConfig(parentWithMcp(), List.of("query_data"));

        assertNotNull(child);
        assertNotNull(child.getMcpServers(), "parent MCP servers must be propagated");
        assertTrue(
                child.getMcpServers().containsKey("industry-data"),
                "MCP servers are copied wholesale: the map is keyed by server name, not tool name,"
                        + " so filtering it by the tool allow-list would drop every server");
        assertTrue(child.getAllow().contains("query_data"), "allowed entry must be kept");
    }

    @Test
    void unknownAllowListEntriesAreDroppedAndDoNotPropagate() {
        ToolsConfig parent = parentWithMcp();
        // Restrict the parent so that an unrelated name is genuinely not available from it.
        parent.setStrictAllow(true);
        parent.setAllow(List.of("query_data"));

        ToolsConfig child =
                HarnessAgentBuilderSupport.childToolsConfig(
                        parent, List.of("query_data", "no_such_tool"));

        assertNotNull(child.getAllow());
        assertTrue(child.getAllow().contains("query_data"));
        assertFalse(
                child.getAllow().contains("no_such_tool"),
                "allow-list entries that the parent does not offer must be dropped");
    }

    @Test
    void nullParentIsToleratedForBothEmptyAndNonEmptyAllowLists() {
        assertNotNull(HarnessAgentBuilderSupport.childToolsConfig(null, List.of("query_data")));
        assertNotNull(HarnessAgentBuilderSupport.childToolsConfig(null, List.of()));
    }
}
