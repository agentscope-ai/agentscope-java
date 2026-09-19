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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.tools.ToolsConfigLoader;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

/**
 * Regression tests for #3178: the child's {@link ToolsConfig} must be derived from the <em>resolved
 * </em> parent config so that a parent whose MCP servers come from workspace {@code tools.json}
 * still propagates them, and an empty allow-list is a true MCP opt-out.
 *
 * <p>Includes config-resolution unit tests and spawned-child regressions using temporary
 * workspaces and mocked models, without connecting to MCP servers.
 */
class SubagentToolsConfigPropagationTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fileDeclarationPreservesAbsentVersusEmptyTools(
            boolean toolsDeclared, @TempDir Path workspace) throws Exception {
        Path spec = workspace.resolve("reader.md");
        Files.writeString(
                spec,
                "---\ndescription: Read only\n"
                        + (toolsDeclared ? "tools: []\n" : "")
                        + "---\nRead the workspace.\n",
                StandardCharsets.UTF_8);
        SubagentDeclaration declaration = AgentSpecLoader.loadFromFile(spec, workspace);
        assertNotNull(declaration);
        assertEquals(toolsDeclared, declaration.isToolsDeclared());
        assertTrue(declaration.getTools().isEmpty());

        ToolsConfig parent = parentWithMcp();
        ToolsConfig child =
                HarnessAgentBuilderSupport.childToolsConfig(
                        parent, declaration.getTools(), declaration.isToolsDeclared());
        assertNotNull(child);
        assertNotSame(parent, child);
        assertEquals(!toolsDeclared, child.getMcpServers().containsKey("industry-data"));
    }

    @ParameterizedTest
    @CsvSource({"reader,true", "reader,false", "general-purpose,true", "general-purpose,false"})
    void spawnedSharedChildHonoursParentToolsConfigSwitch(
            String agentName, boolean disabled, @TempDir Path workspace) throws Exception {
        writeToolsJson(workspace, "{\"deny\":[\"read_file\"]}");
        var parentBuilder =
                HarnessAgent.builder()
                        .model(Mockito.mock(Model.class))
                        .workspace(workspace)
                        .disableDynamicSubagents()
                        .subagent(
                                SubagentDeclaration.builder()
                                        .name("reader")
                                        .description("read only")
                                        .inlineAgentsBody("read only")
                                        .workspaceMode(WorkspaceMode.SHARED)
                                        .build());
        if (disabled) parentBuilder.disableToolsConfig();

        try (var loader = Mockito.mockStatic(ToolsConfigLoader.class, Mockito.CALLS_REAL_METHODS);
                var parent = parentBuilder.build();
                var child =
                        (HarnessAgent)
                                parent.getSubagentAgentManager()
                                        .createAgentIfPresent(agentName, RuntimeContext.empty())
                                        .orElseThrow()) {
            assertEquals(disabled, child.getToolkit().getToolNames().contains("read_file"));
            // Enabled: only the parent reads the file. Disabled: neither agent reads it,
            // so environment substitution and MCP credentials cannot escape the switch.
            loader.verify(
                    () -> ToolsConfigLoader.load(Mockito.any(WorkspaceManager.class)),
                    Mockito.times(disabled ? 0 : 1));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "empty", "read_file"})
    void disabledParentOverrideDoesNotBypassDeclarationFilters(
            String tools, @TempDir Path workspace) throws Exception {
        writeToolsJson(workspace, "{\"deny\":[\"read_file\"]}");
        var ignoredOverride = new ToolsConfig();
        ignoredOverride.setDeny(List.of("read_file"));
        var parent =
                HarnessAgent.builder()
                        .model(Mockito.mock(Model.class))
                        .workspace(workspace)
                        .toolsConfig(ignoredOverride)
                        .disableToolsConfig();
        var declaration =
                SubagentDeclaration.builder()
                        .name("reader")
                        .description("read only")
                        .inlineAgentsBody("read only")
                        .workspaceMode(WorkspaceMode.SHARED);
        if (!tools.equals("absent")) {
            declaration.tools(tools.equals("empty") ? List.of() : List.of("read_file"));
        }
        try (var loader = Mockito.mockStatic(ToolsConfigLoader.class, Mockito.CALLS_REAL_METHODS);
                var child =
                        (HarnessAgent)
                                HarnessAgentBuilderSupport.buildDeclaredFactory(
                                                parent, declaration.build(), workspace, null)
                                        .create(RuntimeContext.empty())) {
            assertTrue(child.getToolkit().getToolNames().contains("read_file"));
            if (tools.equals("read_file")) {
                assertEquals(java.util.Set.of("read_file"), child.getToolkit().getToolNames());
            }
            loader.verifyNoInteractions();
        }
    }

    @Test
    void disabledParentStillAllowsIsolatedChildToLoadItsOwnConfig(@TempDir Path workspace)
            throws Exception {
        Path childWorkspace = workspace.resolve("agents/reader/workspace");
        writeToolsJson(workspace, "{\"deny\":[\"read_file\"]}");
        writeToolsJson(childWorkspace, "{\"deny\":[\"execute\"]}");
        var parent =
                HarnessAgent.builder()
                        .model(Mockito.mock(Model.class))
                        .workspace(workspace)
                        .disableToolsConfig();
        var declaration =
                SubagentDeclaration.builder()
                        .name("reader")
                        .description("read only")
                        .inlineAgentsBody("read only")
                        .workspaceMode(WorkspaceMode.ISOLATED)
                        .build();
        try (var loader = Mockito.mockStatic(ToolsConfigLoader.class, Mockito.CALLS_REAL_METHODS);
                var child =
                        (HarnessAgent)
                                HarnessAgentBuilderSupport.buildDeclaredFactory(
                                                parent, declaration, workspace, null)
                                        .create(RuntimeContext.empty())) {
            assertTrue(child.getToolkit().getToolNames().contains("read_file"));
            assertFalse(child.getToolkit().getToolNames().contains("execute"));
            loader.verify(() -> ToolsConfigLoader.load(child.getWorkspaceManager()));
            loader.verify(() -> ToolsConfigLoader.load(Mockito.any(WorkspaceManager.class)));
        }
    }

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
    void explicitEmptyAllowListIsATrueOptOutAndDoesNotInheritMcp() {
        ToolsConfig child =
                HarnessAgentBuilderSupport.childToolsConfig(parentWithMcp(), List.of(), true);

        assertNotNull(child, "an explicit empty allow-list must yield an explicit child config");
        assertTrue(
                child.getMcpServers() == null || child.getMcpServers().isEmpty(),
                "an explicit empty allow-list must not inherit the parent's MCP servers (#3178)");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentAllowListInheritsValuesWithoutSharingMutableContainers(boolean strict) {
        ToolsConfig parent = parentWithMcp();
        parent.setAllow(new ArrayList<>(List.of("query_data")));
        parent.setDeny(new ArrayList<>(List.of("execute")));
        parent.setStrictAllow(strict);
        parent.setDefaultToolsEnabled(!strict);
        ToolsConfig child = HarnessAgentBuilderSupport.childToolsConfig(parent, List.of(), false);
        ToolsConfig sibling = HarnessAgentBuilderSupport.childToolsConfig(parent, List.of(), false);

        assertNotSame(parent, child);
        assertNotSame(child, sibling);
        assertNotSame(parent.getAllow(), child.getAllow());
        assertNotSame(parent.getDeny(), child.getDeny());
        assertNotSame(parent.getMcpServers(), child.getMcpServers());
        assertEquals(parent.getAllow(), child.getAllow());
        assertEquals(parent.getDeny(), child.getDeny());
        assertEquals(parent.getMcpServers(), child.getMcpServers());
        assertEquals(strict, child.isStrictAllow());
        assertEquals(!strict, child.isDefaultToolsEnabled());

        parent.getAllow().clear();
        parent.getDeny().clear();
        assertEquals(List.of("query_data"), child.getAllow());
        assertEquals(List.of("execute"), child.getDeny());
        child.setAllow(List.of("child_only"));
        child.setDeny(List.of("child_denied"));
        child.setStrictAllow(!strict);
        child.setDefaultToolsEnabled(strict);
        child.getMcpServers().clear();
        assertTrue(parent.getMcpServers().containsKey("industry-data"));
        assertTrue(sibling.getMcpServers().containsKey("industry-data"));
        assertEquals(List.of("query_data"), sibling.getAllow());
        assertEquals(List.of("execute"), sibling.getDeny());
        assertEquals(strict, parent.isStrictAllow());
        assertEquals(strict, sibling.isStrictAllow());
        assertEquals(!strict, parent.isDefaultToolsEnabled());
        assertEquals(!strict, sibling.isDefaultToolsEnabled());

        assertNull(
                HarnessAgentBuilderSupport.childToolsConfig(null, List.of(), false),
                "an absent tools list with no parent config must stay null so the child can load"
                        + " its own workspace tools.json");
    }

    @Test
    void absentAllowListPreservesNullFieldsAndDefaultFlags() {
        ToolsConfig parent = new ToolsConfig();
        ToolsConfig child = HarnessAgentBuilderSupport.childToolsConfig(parent, null, false);
        assertNotSame(parent, child);
        assertNull(child.getAllow());
        assertNull(child.getDeny());
        assertNull(child.getMcpServers());
        assertFalse(child.isStrictAllow());
        assertTrue(child.isDefaultToolsEnabled());
    }

    @Test
    void nonEmptyAllowListKeepsMcpServersSoTheChildCanReRegisterThem() {
        ToolsConfig child =
                HarnessAgentBuilderSupport.childToolsConfig(
                        parentWithMcp(), List.of("query_data"), true);

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
                        parent, List.of("query_data", "no_such_tool"), true);

        assertNotNull(child.getAllow());
        assertTrue(child.getAllow().contains("query_data"));
        assertFalse(
                child.getAllow().contains("no_such_tool"),
                "allow-list entries that the parent does not offer must be dropped");
    }

    @Test
    void nullParentIsToleratedForDeclaredAndAbsentAllowLists() {
        assertNotNull(
                HarnessAgentBuilderSupport.childToolsConfig(null, List.of("query_data"), true));
        assertNotNull(HarnessAgentBuilderSupport.childToolsConfig(null, List.of(), true));
        assertNull(HarnessAgentBuilderSupport.childToolsConfig(null, null, false));
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
                    HarnessAgentBuilderSupport.childToolsConfig(
                            effective, List.of("query_data"), true);
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

    @Test
    void disableToolsConfigKeepsWorkspaceToolsJsonAwayFromSubagents(@TempDir Path workspace)
            throws Exception {
        writeToolsJson(
                workspace,
                """
                { "mcpServers": { "industry-data": { "transport": "http",
                  "url": "https://example.invalid/mcp" } } }
                """);

        try (WorkspaceManager wm = new WorkspaceManager(workspace)) {
            assertNotNull(
                    HarnessAgentBuilderSupport.resolveEffectiveToolsConfig(
                            HarnessAgent.builder(), wm),
                    "sanity: the workspace config is otherwise resolvable");
            assertNull(
                    HarnessAgentBuilderSupport.resolveEffectiveToolsConfig(
                            HarnessAgent.builder().disableToolsConfig(), wm),
                    "disableToolsConfig() must also keep tools.json (and the ${ENV} credentials it"
                            + " carries) away from subagents");
        }
    }

    // ---------------------------------------------------------------------
    //  Empty-allow-list branch coverage and the backwards-compatible
    //  overloads that the public builder entry points still funnel through.
    // ---------------------------------------------------------------------

    @Test
    void declaredEmptyAllowListToleratesAParentWithoutAnyMcpServers() {
        ToolsConfig parentWithoutServers = new ToolsConfig();
        parentWithoutServers.setMcpServers(null);

        ToolsConfig fromDeclaredEmpty =
                HarnessAgentBuilderSupport.childToolsConfig(parentWithoutServers, List.of(), true);
        assertNotNull(fromDeclaredEmpty);
        assertTrue(
                fromDeclaredEmpty.getMcpServers() == null
                        || fromDeclaredEmpty.getMcpServers().isEmpty(),
                "an explicit empty allow-list must not inherit the parent's MCP servers");

        ToolsConfig parentWithEmptyServers = new ToolsConfig();
        parentWithEmptyServers.setMcpServers(Map.of());
        ToolsConfig fromNullAllow =
                HarnessAgentBuilderSupport.childToolsConfig(parentWithEmptyServers, null, true);
        assertTrue(fromNullAllow.getMcpServers().isEmpty());
    }

    @Test
    void compatBuildersFallBackToTheBuilderOverride(@TempDir Path workspace) {
        HarnessAgent.Builder b = HarnessAgent.builder().toolsConfig(parentWithMcp());

        assertNotNull(
                HarnessAgentBuilderSupport.buildGeneralPurposeFactory(b, workspace, null),
                "the 3-arg general-purpose builder must keep serving existing callers");

        List<SubagentEntry> staticEntries =
                HarnessAgentBuilderSupport.buildStaticSubagentEntries(b, workspace, null);
        assertFalse(staticEntries.isEmpty(), "static entries must still include general-purpose");
    }

    @Test
    void staticEntriesBuildTheDeclaredSubagentFromTheEffectiveConfig(@TempDir Path workspace) {
        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("reader")
                        .description("read only")
                        .inlineAgentsBody("read only")
                        .tools(List.of("read_file"))
                        .build();
        HarnessAgent.Builder b = HarnessAgent.builder().subagent(decl);

        List<SubagentEntry> entries =
                HarnessAgentBuilderSupport.buildStaticSubagentEntries(
                        b, workspace, null, parentWithMcp());

        assertTrue(
                entries.stream().anyMatch(e -> "reader".equals(e.name())),
                "the declared subagent must be present when built from an effective config");
    }
}
