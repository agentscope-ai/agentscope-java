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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@HarnessQuiescence
class HarnessAgentProgressiveToolLoadingTest {

    @TempDir Path workspace;

    @Test
    void progressiveLoadingDisabledByDefault() {
        try (HarnessAgent agent = buildAgent(HarnessAgent.builder())) {
            Toolkit toolkit = agent.getToolkit();

            assertTrue(toolNames(toolkit).contains("read_file"));
            assertTrue(toolNames(toolkit).contains("web_search"));
            assertNull(toolkit.getToolGroup("workspace_files"));
            assertNull(toolkit.getTool("reset_equipped_tools"));
        }
    }

    @Test
    void progressiveLoadingGroupsAndInitiallyHidesOptionalBuiltins() {
        try (HarnessAgent agent =
                buildAgent(HarnessAgent.builder().enableProgressiveToolLoading())) {
            Toolkit toolkit = agent.getToolkit();
            Set<String> visible = toolNames(toolkit);

            assertNotNull(toolkit.getToolGroup("workspace_files"));
            assertNotNull(toolkit.getToolGroup("subagents"));
            assertNotNull(toolkit.getToolGroup("session_history"));
            assertNotNull(toolkit.getToolGroup("web"));
            assertFalse(visible.contains("read_file"));
            assertFalse(visible.contains("agent_spawn"));
            assertFalse(visible.contains("session_search"));
            assertFalse(visible.contains("web_search"));
            assertTrue(visible.contains("memory_search"));
            assertTrue(visible.contains("reset_equipped_tools"));

            assertTrue(toolkit.getToolGroup("workspace_files").getTools().contains("read_file"));
            assertTrue(toolkit.getToolGroup("subagents").getTools().contains("agent_spawn"));
            assertTrue(
                    toolkit.getToolGroup("session_history").getTools().contains("session_search"));
            assertEquals(Set.of("web_fetch", "web_search"), toolkit.getToolGroup("web").getTools());
        }
    }

    @Test
    void activeGroupMakesItsToolsVisible() {
        try (HarnessAgent agent =
                buildAgent(HarnessAgent.builder().enableProgressiveToolLoading())) {
            Toolkit toolkit = agent.getToolkit();

            Set<String> visibleWithWeb = toolNames(toolkit, List.of("web"));

            assertTrue(visibleWithWeb.contains("web_fetch"));
            assertTrue(visibleWithWeb.contains("web_search"));
            assertFalse(visibleWithWeb.contains("read_file"));
        }
    }

    @Test
    void disabledAndDeniedFamiliesDoNotCreateEmptyGroups() {
        ToolsConfig config = new ToolsConfig();
        config.setDeny(List.of("web_fetch", "web_search", "wait_async_results"));

        try (HarnessAgent agent =
                buildAgent(
                        HarnessAgent.builder()
                                .enableProgressiveToolLoading()
                                .disableFilesystemTools()
                                .disableSubagents()
                                .toolsConfig(config))) {
            Toolkit toolkit = agent.getToolkit();

            assertNull(toolkit.getToolGroup("workspace_files"));
            assertNull(toolkit.getToolGroup("subagents"));
            assertNull(toolkit.getToolGroup("web"));
            assertNotNull(toolkit.getToolGroup("session_history"));
            assertEquals(
                    Set.of("session_history"),
                    metaGroupEnum(toolkit.getTool("reset_equipped_tools").getParameters()));
        }
    }

    @Test
    void booleanOverloadCanKeepLegacyVisibility() {
        try (HarnessAgent agent =
                buildAgent(HarnessAgent.builder().enableProgressiveToolLoading(false))) {
            Toolkit toolkit = agent.getToolkit();

            assertTrue(toolNames(toolkit).contains("read_file"));
            assertTrue(toolNames(toolkit).contains("web_search"));
            assertNull(toolkit.getToolGroup("workspace_files"));
            assertNull(toolkit.getToolGroup("subagents"));
            assertNull(toolkit.getToolGroup("session_history"));
            assertNull(toolkit.getToolGroup("web"));
            assertNull(toolkit.getTool("reset_equipped_tools"));
        }
    }

    @Test
    void sameNamedCustomToolIsNotGroupedWhenBuiltinFamilyIsDisabled() {
        Toolkit customToolkit = new Toolkit();
        AgentTool customWebSearch = customTool("web_search");
        customToolkit.registerAgentTool(customWebSearch);

        try (HarnessAgent agent =
                buildAgent(
                        HarnessAgent.builder()
                                .toolkit(customToolkit)
                                .disableWebTools()
                                .enableProgressiveToolLoading())) {
            Toolkit toolkit = agent.getToolkit();

            assertEquals(customWebSearch, toolkit.getTool("web_search"));
            assertTrue(toolNames(toolkit).contains("web_search"));
            assertNull(toolkit.getToolGroup("web"));
        }
    }

    @Test
    void replacementToolIsNotGroupedAsTheBuiltinItOverrode() {
        Toolkit toolkit = new Toolkit();
        Map<String, AgentTool> builtins = new LinkedHashMap<>();
        HarnessBuiltinToolGroups.registerBuiltin(toolkit, builtins, customTool("web_search"));
        HarnessBuiltinToolGroups.registerBuiltin(toolkit, builtins, customTool("web_fetch"));
        AgentTool replacementWebSearch = customTool("web_search");
        toolkit.registerAgentTool(replacementWebSearch);

        HarnessBuiltinToolGroups.apply(toolkit, builtins);

        assertEquals(replacementWebSearch, toolkit.getTool("web_search"));
        assertTrue(toolNames(toolkit).contains("web_search"));
        assertEquals(Set.of("web_fetch"), toolkit.getToolGroup("web").getTools());
    }

    @Test
    void progressiveBuiltinCannotRetainPreexistingGroupMembership() {
        Toolkit customToolkit = new Toolkit();
        customToolkit.registerAgentTool(customTool("read_file"));
        customToolkit.createToolGroup("custom_files", "Custom file tools", true);
        customToolkit.addToolToGroup("custom_files", "read_file");

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                buildAgent(
                                                HarnessAgent.builder()
                                                        .toolkit(customToolkit)
                                                        .enableProgressiveToolLoading())
                                        .close());

        assertTrue(error.getMessage().contains("read_file"));
        assertTrue(error.getMessage().contains("already belongs to a tool group"));
    }

    @Test
    void progressiveGroupNamesAreReserved() {
        Toolkit customToolkit = new Toolkit();
        customToolkit.createToolGroup("web", "Application web tools", true);

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                buildAgent(
                                                HarnessAgent.builder()
                                                        .toolkit(customToolkit)
                                                        .disableWebTools()
                                                        .enableProgressiveToolLoading())
                                        .close());

        assertTrue(error.getMessage().contains("Tool group 'web'"));
        assertTrue(error.getMessage().contains("reserved"));
    }

    private HarnessAgent buildAgent(HarnessAgent.Builder builder) {
        return builder.name("progressive-tools-test")
                .model(stubModel())
                .workspace(workspace)
                .abstractFilesystem(new LocalFilesystem(workspace))
                .build();
    }

    private static Set<String> toolNames(Toolkit toolkit) {
        return toolNames(toolkit, List.of());
    }

    private static Set<String> toolNames(Toolkit toolkit, List<String> activeGroups) {
        return toolkit.getToolSchemas(activeGroups).stream()
                .map(ToolSchema::getName)
                .collect(java.util.stream.Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> metaGroupEnum(Map<String, Object> parameters) {
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        Map<String, Object> toActivate = (Map<String, Object>) properties.get("to_activate");
        Map<String, Object> items = (Map<String, Object>) toActivate.get("items");
        return Set.copyOf((List<String>) items.get("enum"));
    }

    private static Model stubModel() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text("ok").build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }

    private static AgentTool customTool(String name) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "Custom tool";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.empty();
            }
        };
    }
}
