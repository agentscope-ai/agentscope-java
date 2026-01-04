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

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
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

    private ToolResultBlock callTool(AgentTool tool, Map<String, Object> input) {
        ToolUseBlock toolUseBlock = new ToolUseBlock("test-id", tool.getName(), input);
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();
        return tool.callAsync(param).block();
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
                        .contains("Reset the equipped tools by activating specified tool groups"));
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

        AgentTool searchTool = createMockTool("search_tool", "Search function");
        RegisteredToolFunction searchRegistered =
                new RegisteredToolFunction(searchTool, "search", null, null);
        registry.registerTool("search_tool", searchTool, searchRegistered);
        groupManager.addToolToGroup("search", "search_tool");

        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("analytics", "search"));

        // Act
        ToolResultBlock result = callTool(metaTool, input);

        // Assert
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        assertTrue(result.getOutput().get(0) instanceof TextBlock);
        String resultText = ((TextBlock) result.getOutput().get(0)).getText();
        assertTrue(resultText.contains("Successfully activated tool groups"));
        assertTrue(resultText.contains("analytics"));
        assertTrue(resultText.contains("search"));
        assertTrue(groupManager.getToolGroup("analytics").isActive());
        assertTrue(groupManager.getToolGroup("search").isActive());
    }

    @Test
    void testMetaToolCallAsyncWithToolsInGroup() {
        // Arrange
        groupManager.createToolGroup("search", "Search tools", false);

        AgentTool searchTool = createMockTool("search_tool", "Search function");
        RegisteredToolFunction searchRegistered =
                new RegisteredToolFunction(searchTool, "search", null, null);
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
        String resultText = ((TextBlock) result.getOutput().get(0)).getText();
        assertTrue(resultText.contains("search_tool"));
        assertTrue(resultText.contains("Search function"));
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
        String resultText = ((TextBlock) result.getOutput().get(0)).getText();
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
        String resultText = ((TextBlock) result.getOutput().get(0)).getText();
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
        String resultText = ((TextBlock) result.getOutput().get(0)).getText();
        assertTrue(resultText.contains("Error:"));
        assertTrue(resultText.contains("does not exist"));
    }

    @Test
    void testMetaToolCallAsyncEmptyList() {
        // Arrange
        groupManager.createToolGroup("analytics", "Analytics tools", false);
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of());

        // Act

        ToolResultBlock result = callTool(metaTool, input);

        // Assert
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String resultText = ((TextBlock) result.getOutput().get(0)).getText();
        assertTrue(resultText.contains("Successfully activated tool groups"));
    }

    @Test
    void testMetaToolOnlyActivatesDoesNotDeactivate() {
        // Arrange
        groupManager.createToolGroup("group1", "Group 1", true);
        groupManager.createToolGroup("group2", "Group 2", true);
        groupManager.createToolGroup("group3", "Group 3", false);

        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("group3"));

        // Act

        ToolResultBlock result = callTool(metaTool, input);

        // Assert - group3 should be activated, but group1 and group2 should remain active
        assertTrue(groupManager.getToolGroup("group1").isActive());
        assertTrue(groupManager.getToolGroup("group2").isActive());
        assertTrue(groupManager.getToolGroup("group3").isActive());
    }

    @Test
    void testMetaToolCallAsyncReactivateAlreadyActive() {
        // Arrange
        groupManager.createToolGroup("analytics", "Analytics tools", true);
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("analytics"));

        // Act

        ToolResultBlock result = callTool(metaTool, input);

        // Assert
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String resultText = ((TextBlock) result.getOutput().get(0)).getText();
        assertTrue(resultText.contains("Successfully activated"));
        assertTrue(groupManager.getToolGroup("analytics").isActive());
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
        assertEquals("The list of tool group names to activate.", toActivate.get("description"));
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
    void testMetaToolWithMultipleToolsInMultipleGroups() {
        // Arrange
        groupManager.createToolGroup("group1", "Group 1", false);
        groupManager.createToolGroup("group2", "Group 2", false);

        AgentTool tool1 = createMockTool("tool1", "Tool 1");
        AgentTool tool2 = createMockTool("tool2", "Tool 2");
        AgentTool tool3 = createMockTool("tool3", "Tool 3");

        registry.registerTool(
                "tool1", tool1, new RegisteredToolFunction(tool1, "group1", null, null));
        registry.registerTool(
                "tool2", tool2, new RegisteredToolFunction(tool2, "group1", null, null));
        registry.registerTool(
                "tool3", tool3, new RegisteredToolFunction(tool3, "group2", null, null));

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
        String resultText = ((TextBlock) result.getOutput().get(0)).getText();
        assertTrue(resultText.contains("tool1"));
        assertTrue(resultText.contains("tool2"));
        assertTrue(resultText.contains("tool3"));
        assertTrue(resultText.contains("Group 1"));
        assertTrue(resultText.contains("Group 2"));
    }
}
