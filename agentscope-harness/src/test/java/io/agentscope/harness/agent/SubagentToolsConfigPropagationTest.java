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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    // ---------------------------------------------------------------------
    //  resolveEffectiveToolsConfig: the #3178 root cause lives here
    // ---------------------------------------------------------------------

    private static void writeToolsJson(Path workspace, String json) throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("tools.json"), json, StandardCharsets.UTF_8);
    }

    @Test
    void resolvesMcpServersFromWorkspaceToolsJsonAndHandsThemToTheDeclaredChild(
            @TempDir Path workspace) throws Exception {
        writeToolsJson(
                workspace,
                """
                { "mcpServers": { "industry-data": { "transport": "http",
                  "url": "https://example.invalid/mcp" } } }
                """);

        try (WorkspaceManager wm = new WorkspaceManager(workspace)) {
            HarnessAgent.Builder b = HarnessAgent.builder();
            ToolsConfig effective = HarnessAgentBuilderSupport.resolveEffectiveToolsConfig(b, wm);

            assertNotNull(
                    effective, "a workspace tools.json must be resolved before factories build");
            assertNotNull(effective.getMcpServers(), "the resolved config must carry mcpServers");
            assertTrue(
                    effective.getMcpServers().containsKey("industry-data"),
                    "mcpServers from tools.json must survive resolution");

            // The whole #3178 chain, end to end at unit level: a workspace-configured parent now
            // hands its MCP servers to a declared child that allow-lists an MCP tool.
            ToolsConfig child =
                    HarnessAgentBuilderSupport.childToolsConfig(effective, List.of("query_data"));
            assertNotNull(
                    child.getMcpServers(), "the child must be able to re-register the server");
            assertTrue(child.getMcpServers().containsKey("industry-data"));
        }
    }

    @Test
    void builderOverrideWinsOverWorkspaceToolsJson(@TempDir Path workspace) throws Exception {
        writeToolsJson(
                workspace,
                """
                { "mcpServers": { "from-json": { "transport": "http",
                  "url": "https://example.invalid/a" } } }
                """);

        ToolsConfig override = new ToolsConfig();
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        McpServerConfig cfg = new McpServerConfig();
        cfg.setTransport("http");
        cfg.setUrl("https://example.invalid/b");
        servers.put("from-builder", cfg);
        override.setMcpServers(servers);

        try (WorkspaceManager wm = new WorkspaceManager(workspace)) {
            ToolsConfig effective =
                    HarnessAgentBuilderSupport.resolveEffectiveToolsConfig(
                            HarnessAgent.builder().toolsConfig(override), wm);

            assertNotNull(effective);
            assertTrue(effective.getMcpServers().containsKey("from-builder"));
            assertFalse(
                    effective.getMcpServers().containsKey("from-json"),
                    "the builder override must take precedence over tools.json");
        }
    }

    @Test
    void noOverrideAndNoToolsJsonResolvesToNull(@TempDir Path workspace) throws Exception {
        try (WorkspaceManager wm = new WorkspaceManager(workspace)) {
            assertNull(
                    HarnessAgentBuilderSupport.resolveEffectiveToolsConfig(
                            HarnessAgent.builder(), wm));
        }
        assertNull(
                HarnessAgentBuilderSupport.resolveEffectiveToolsConfig(
                        HarnessAgent.builder(), null));
    }
}
