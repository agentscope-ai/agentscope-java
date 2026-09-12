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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class MetaToolFactoryTest {

    private ToolRegistry registry;
    private ToolGroupManager groupManager;
    private MetaToolFactory metaToolFactory;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        groupManager = new ToolGroupManager();
        metaToolFactory = new MetaToolFactory(groupManager, registry);
    }

    private AgentTool createMockTool(String name, String description) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");
                return schema;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text("result"));
            }
        };
    }

    /** Invokes the meta tool with a per-session runtime context + tool context state. */
    private ToolResultBlock callTool(AgentTool tool, Map<String, Object> input) {
        return callTool(tool, input, ToolContextState.builder().build());
    }

    /** Invokes the meta tool against the given per-session tool context state. */
    private ToolResultBlock callTool(
            AgentTool tool, Map<String, Object> input, ToolContextState tcs) {
        return callTool(tool, input, tcs != null ? runtimeContext(tcs) : null);
    }

    private ToolResultBlock callTool(
            AgentTool tool, Map<String, Object> input, RuntimeContext runtimeContext) {
        ToolUseBlock toolUseBlock = new ToolUseBlock("test-id", tool.getName(), input);
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(toolUseBlock)
                        .input(input)
                        .runtimeContext(runtimeContext)
                        .build();
        return tool.callAsync(param).block();
    }

    private static RuntimeContext runtimeContext(ToolContextState tcs) {
        return RuntimeContext.builder()
                .agentState(AgentState.builder().toolContext(tcs).build())
                .build();
    }

    private static String text(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    @Test
    void testCreateResetEquippedToolsAgentTool() {
        // Act
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        // Assert
        assertNotNull(metaTool);
        assertEquals("reset_equipped_tools", metaTool.getName());
        assertNotNull(metaTool.getDescription());
        assertNotNull(metaTool.getParameters());
    }

    @Test
    void testMetaToolNameAndDescription() {
        // Act
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        // Assert
        assertEquals("reset_equipped_tools", metaTool.getName());
        assertTrue(
                metaTool.getDescription()
                        .contains("Reset your equipped tools based on your current task"));
        assertTrue(
                metaTool.getDescription()
                        .contains("The input list is the FINAL set of active tool groups"));
    }

    @Test
    void testMetaToolDescriptionWithNoGroups() {
        // Act
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        String description = metaTool.getDescription();

        // Assert
        assertTrue(description.contains("No tool groups"));
    }

    @Test
    void testMetaToolDescriptionWithGroups() {
        // Arrange
        groupManager.createToolGroup("analytics", "Analytics tools", true);
        groupManager.createToolGroup("search", "Search tools", true);

        // Act
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        String description = metaTool.getDescription();

        // Assert
        assertTrue(description.contains("Activated tool groups"));
        assertTrue(description.contains("analytics"));
        assertTrue(description.contains("search"));
    }

    @Test
    void testMetaToolParametersStructure() {
        // Act
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        Map<String, Object> parameters = metaTool.getParameters();

        // Assert
        assertEquals("object", parameters.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        assertTrue(properties.containsKey("to_activate"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) parameters.get("required");
        assertTrue(required.contains("to_activate"));
    }

    @Test
    void testMetaToolParametersWithNoGroups() {
        // Act
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        Map<String, Object> parameters = metaTool.getParameters();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> toActivate = (Map<String, Object>) properties.get("to_activate");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) toActivate.get("items");

        // Assert
        assertFalse(items.containsKey("enum"), "Should not have enum when no groups exist");
    }

    @Test
    void testMetaToolParametersWithGroups() {
        // Arrange
        groupManager.createToolGroup("analytics", "Analytics tools", true);
        groupManager.createToolGroup("search", "Search tools", true);

        // Act
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        Map<String, Object> parameters = metaTool.getParameters();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> toActivate = (Map<String, Object>) properties.get("to_activate");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) toActivate.get("items");
        @SuppressWarnings("unchecked")
        List<String> enumValues = (List<String>) items.get("enum");

        // Assert
        assertNotNull(enumValues);
        assertEquals(2, enumValues.size());
        assertTrue(enumValues.contains("analytics"));
        assertTrue(enumValues.contains("search"));
    }

    @Test
    void testMetaToolCallAsyncActivateGroups() {
        // Arrange
        groupManager.createToolGroup("analytics", "Analytics tools", false);
        groupManager.createToolGroup("search", "Search tools", false);

        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        ToolContextState tcs = ToolContextState.builder().build();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("analytics", "search"));

        // Act
        ToolResultBlock result = callTool(metaTool, input, tcs);

        // Assert
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String resultText = text(result);
        assertTrue(resultText.contains("The currently activated tool group(s):"));
        assertTrue(resultText.contains("analytics"));
        assertTrue(resultText.contains("search"));
        assertEquals(List.of("analytics", "search"), tcs.getActivatedGroups());
    }

    @Test
    void testMetaToolCallAsyncWithToolsInGroup() {
        // Arrange
        groupManager.createToolGroup("search", "Search tools", false);

        AgentTool searchTool = createMockTool("search_tool", "Search function");
        RegisteredToolFunction searchRegistered =
                new RegisteredToolFunction(searchTool, null, null);
        registry.registerTool("search_tool", searchTool, searchRegistered);
        groupManager.addToolToGroup("search", "search_tool");

        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("search"));

        // Act
        ToolResultBlock result = callTool(metaTool, input);

        // Assert
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String resultText = text(result);
        assertTrue(resultText.contains("The currently activated tool group(s): search"));
        assertTrue(resultText.contains("Search tools"));
    }

    @Test
    void testMetaToolCallAsyncMissingParameter() {
        // Arrange
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        Map<String, Object> input = new HashMap<>();

        // Act
        ToolResultBlock result = callTool(metaTool, input);

        // Assert
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String resultText = text(result);
        assertTrue(resultText.contains("Error:"));
        assertTrue(resultText.contains("Missing required parameter: to_activate"));
    }

    @Test
    void testMetaToolCallAsyncInvalidGroupName() {
        // Arrange
        groupManager.createToolGroup("analytics", "Analytics tools", false);
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("nonexistent"));

        // Act
        ToolResultBlock result = callTool(metaTool, input);

        // Assert
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String resultText = text(result);
        assertTrue(resultText.contains("Error:"));
        assertTrue(resultText.contains("does not exist"));
    }

    @Test
    void testMetaToolCallAsyncPartialInvalidGroups() {
        // Arrange
        groupManager.createToolGroup("analytics", "Analytics tools", false);
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("analytics", "nonexistent"));

        // Act
        ToolResultBlock result = callTool(metaTool, input);

        // Assert
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String resultText = text(result);
        assertTrue(resultText.contains("Error:"));
        assertTrue(resultText.contains("does not exist"));
    }

    @Test
    void testMetaToolCallAsyncEmptyListDeactivatesAll() {
        // Arrange
        groupManager.createToolGroup("analytics", "Analytics tools", true);
        groupManager.createToolGroup("search", "Search tools", true);
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        ToolContextState tcs =
                ToolContextState.builder()
                        .addActivatedGroup("analytics")
                        .addActivatedGroup("search")
                        .build();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of());

        // Act
        ToolResultBlock result = callTool(metaTool, input, tcs);

        // Assert - empty list deactivates all META groups in the per-session state
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String resultText = text(result);
        assertTrue(resultText.contains("All META tool groups are currently deactivated"));
        assertEquals(List.of(), tcs.getActivatedGroups());
    }

    @Test
    void testMetaToolReplacesActiveGroups() {
        // Arrange
        groupManager.createToolGroup("group1", "Group 1", true);
        groupManager.createToolGroup("group2", "Group 2", true);
        groupManager.createToolGroup("group3", "Group 3", false);

        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        ToolContextState tcs =
                ToolContextState.builder()
                        .addActivatedGroup("group1")
                        .addActivatedGroup("group2")
                        .build();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("group3"));

        // Act
        callTool(metaTool, input, tcs);

        // Assert - replacement semantics: group1 and group2 replaced by group3
        assertEquals(List.of("group3"), tcs.getActivatedGroups());
    }

    @Test
    void testMetaToolCallAsyncReactivateAlreadyActive() {
        // Arrange
        groupManager.createToolGroup("analytics", "Analytics tools", true);
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("analytics"));

        ToolContextState tcs = ToolContextState.builder().addActivatedGroup("analytics").build();

        // Act
        ToolResultBlock result = callTool(metaTool, input, tcs);

        // Assert
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String resultText = text(result);
        assertTrue(resultText.contains("The currently activated tool group(s):"));
        assertEquals(List.of("analytics"), tcs.getActivatedGroups());
    }

    @Test
    void testMetaToolParametersDescriptionField() {
        // Act
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        Map<String, Object> parameters = metaTool.getParameters();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> toActivate = (Map<String, Object>) properties.get("to_activate");

        // Assert
        String description = (String) toActivate.get("description");
        assertTrue(description.contains("FINAL list"));
        assertTrue(description.contains("deactivated"));
    }

    @Test
    void testMetaToolParametersArrayType() {
        // Act
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        Map<String, Object> parameters = metaTool.getParameters();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> toActivate = (Map<String, Object>) properties.get("to_activate");

        // Assert
        assertEquals("array", toActivate.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) toActivate.get("items");
        assertEquals("string", items.get("type"));
    }

    @Test
    void testMetaToolIgnoresExternalGroups() {
        // Arrange - create META and EXTERNAL groups
        groupManager.createToolGroup("meta_group", "META group", true, ToolGroupScope.META);
        groupManager.createToolGroup(
                "external_group", "EXTERNAL group", true, ToolGroupScope.EXTERNAL);

        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        ToolContextState tcs =
                ToolContextState.builder().addActivatedGroup("external_group").build();

        // Act - activate only meta_group (replacement semantics)
        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("meta_group"));
        callTool(metaTool, input, tcs);

        // Assert - EXTERNAL group preserved, META group activated
        assertEquals(List.of("external_group", "meta_group"), tcs.getActivatedGroups());
    }

    @Test
    void testMetaToolRejectsExternalGroupInInput() {
        // Arrange
        groupManager.createToolGroup(
                "external_group", "EXTERNAL group", false, ToolGroupScope.EXTERNAL);

        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("external_group"));

        // Act
        ToolResultBlock result = callTool(metaTool, input);

        // Assert
        String resultText = text(result);
        assertTrue(resultText.contains("Error:"));
        assertTrue(resultText.contains("not manageable"));
    }

    @Test
    void testMetaToolParametersOnlyListsMetaGroups() {
        // Arrange
        groupManager.createToolGroup("meta_group", "META group", false, ToolGroupScope.META);
        groupManager.createToolGroup(
                "external_group", "EXTERNAL group", true, ToolGroupScope.EXTERNAL);

        // Act
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        Map<String, Object> parameters = metaTool.getParameters();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> toActivate = (Map<String, Object>) properties.get("to_activate");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) toActivate.get("items");
        @SuppressWarnings("unchecked")
        List<String> enumValues = (List<String>) items.get("enum");

        // Assert - only META groups in enum
        assertNotNull(enumValues);
        assertEquals(1, enumValues.size());
        assertTrue(enumValues.contains("meta_group"));
        assertFalse(enumValues.contains("external_group"));
    }

    @Test
    void testMetaToolReplacementPreservesExternalDuringEmptyActivation() {
        // Arrange - both META and EXTERNAL groups in the per-session state
        groupManager.createToolGroup("meta1", "META 1", true, ToolGroupScope.META);
        groupManager.createToolGroup("meta2", "META 2", true, ToolGroupScope.META);
        groupManager.createToolGroup("ext1", "EXTERNAL 1", true, ToolGroupScope.EXTERNAL);

        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        ToolContextState tcs =
                ToolContextState.builder()
                        .addActivatedGroup("meta1")
                        .addActivatedGroup("meta2")
                        .addActivatedGroup("ext1")
                        .build();

        // Act - deactivate all (empty list)
        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of());
        callTool(metaTool, input, tcs);

        // Assert - META groups deactivated, EXTERNAL preserved
        assertEquals(List.of("ext1"), tcs.getActivatedGroups());
    }

    @Test
    void testMetaToolWithMultipleToolsInMultipleGroups() {
        // Arrange
        groupManager.createToolGroup("group1", "Group 1", false);
        groupManager.createToolGroup("group2", "Group 2", false);

        AgentTool tool1 = createMockTool("tool1", "Tool 1");
        AgentTool tool2 = createMockTool("tool2", "Tool 2");
        AgentTool tool3 = createMockTool("tool3", "Tool 3");

        registry.registerTool("tool1", tool1, new RegisteredToolFunction(tool1, null, null));
        registry.registerTool("tool2", tool2, new RegisteredToolFunction(tool2, null, null));
        registry.registerTool("tool3", tool3, new RegisteredToolFunction(tool3, null, null));

        groupManager.addToolToGroup("group1", "tool1");
        groupManager.addToolToGroup("group1", "tool2");
        groupManager.addToolToGroup("group2", "tool3");

        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("group1", "group2"));

        // Act

        ToolResultBlock result = callTool(metaTool, input);

        // Assert
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String resultText = text(result);
        assertTrue(resultText.contains("The currently activated tool group(s):"));
        assertTrue(resultText.contains("Group 1"));
        assertTrue(resultText.contains("Group 2"));
    }

    @Test
    void testMetaToolSkillToolGroupDescriptionInNotes() {
        // Arrange
        groupManager.createSkillToolGroup("code_tools", "Code execution tools", false, "coding");

        // Act
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        String description = metaTool.getDescription();

        // Assert - notes should include the skill activation reminder
        assertTrue(description.contains("code_tools"));
        assertTrue(description.contains("MUST be activated"));
        assertTrue(description.contains("coding"));
    }

    @Test
    void testMetaToolSkillToolGroupInToolInstructions() {
        // Arrange
        groupManager.createSkillToolGroup("code_tools", "Code execution tools", false, "coding");

        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("code_tools"));

        // Act
        ToolResultBlock result = callTool(metaTool, input);

        // Assert - tool-instructions should include the enhanced description
        String resultText = text(result);
        assertTrue(resultText.contains("tool-instructions"));
        assertTrue(resultText.contains("MUST be activated"));
        assertTrue(resultText.contains("coding"));
    }

    @Test
    void testMetaToolCallAsyncWithoutRuntimeContextFailsWithoutMutatingGroupManager() {
        // Arrange
        groupManager.createToolGroup("analytics", "Analytics tools", false);
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("analytics"));

        // Act - no runtime context (rc == null → no per-session tool context)
        ToolResultBlock result = callTool(metaTool, input, (RuntimeContext) null);

        // Assert - fast fail, never mutate the shared group manager
        assertNotNull(result);
        String resultText = text(result);
        assertTrue(resultText.contains("Error:"));
        assertTrue(resultText.contains("requires a per-session runtime context"));
        assertFalse(groupManager.getToolGroup("analytics").isActive());
    }
}
