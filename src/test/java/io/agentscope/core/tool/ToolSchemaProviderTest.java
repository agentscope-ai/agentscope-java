/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ToolSchemaProviderTest {

    private ToolRegistry registry;
    private ToolGroupManager groupManager;
    private ToolSchemaProvider schemaProvider;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        groupManager = new ToolGroupManager();
        schemaProvider = new ToolSchemaProvider(registry, groupManager);
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
                Map<String, Object> properties = new HashMap<>();
                Map<String, Object> param = new HashMap<>();
                param.put("type", "string");
                properties.put("input", param);
                schema.put("properties", properties);
                schema.put("required", List.of("input"));
                return schema;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(Map<String, Object> input) {
                return Mono.just(ToolResultBlock.text("result"));
            }

            @Override
            public void setCurrentToolUseBlock(ToolUseBlock toolUseBlock) {}
        };
    }

    @Test
    void testGetToolSchemasEmpty() {
        // Act
        List<Map<String, Object>> schemas = schemaProvider.getToolSchemas();

        // Assert
        assertNotNull(schemas);
        assertTrue(schemas.isEmpty());
    }

    @Test
    void testGetToolSchemasWithUngroupedTool() {
        // Arrange
        AgentTool tool = createMockTool("test_tool", "Test tool");
        RegisteredToolFunction registered = new RegisteredToolFunction(tool, null, null, null);
        registry.registerTool("test_tool", tool, registered);

        // Act
        List<Map<String, Object>> schemas = schemaProvider.getToolSchemas();

        // Assert
        assertEquals(1, schemas.size());
        Map<String, Object> schema = schemas.get(0);
        assertEquals("function", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) schema.get("function");
        assertEquals("test_tool", function.get("name"));
        assertEquals("Test tool", function.get("description"));
        assertNotNull(function.get("parameters"));
    }

    @Test
    void testGetToolSchemasWithActiveGroup() {
        // Arrange
        groupManager.createToolGroup("analytics", "Analytics tools", true);
        AgentTool tool = createMockTool("analyze", "Analyze data");
        RegisteredToolFunction registered =
                new RegisteredToolFunction(tool, "analytics", null, null);
        registry.registerTool("analyze", tool, registered);

        // Act
        List<Map<String, Object>> schemas = schemaProvider.getToolSchemas();

        // Assert
        assertEquals(1, schemas.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) schemas.get(0).get("function");
        assertEquals("analyze", function.get("name"));
    }

    @Test
    void testGetToolSchemasFilterInactiveGroup() {
        // Arrange
        groupManager.createToolGroup("admin", "Admin tools", false);
        AgentTool tool = createMockTool("admin_command", "Admin command");
        RegisteredToolFunction registered = new RegisteredToolFunction(tool, "admin", null, null);
        registry.registerTool("admin_command", tool, registered);

        // Act
        List<Map<String, Object>> schemas = schemaProvider.getToolSchemas();

        // Assert
        assertTrue(schemas.isEmpty(), "Inactive group tools should be filtered out");
    }

    @Test
    void testGetToolSchemasMixedActiveInactive() {
        // Arrange - Active group
        groupManager.createToolGroup("search", "Search tools", true);
        AgentTool searchTool = createMockTool("search", "Search function");
        RegisteredToolFunction searchRegistered =
                new RegisteredToolFunction(searchTool, "search", null, null);
        registry.registerTool("search", searchTool, searchRegistered);

        // Arrange - Inactive group
        groupManager.createToolGroup("admin", "Admin tools", false);
        AgentTool adminTool = createMockTool("admin", "Admin function");
        RegisteredToolFunction adminRegistered =
                new RegisteredToolFunction(adminTool, "admin", null, null);
        registry.registerTool("admin", adminTool, adminRegistered);

        // Arrange - Ungrouped tool
        AgentTool ungroupedTool = createMockTool("ungrouped", "Ungrouped function");
        RegisteredToolFunction ungroupedRegistered =
                new RegisteredToolFunction(ungroupedTool, null, null, null);
        registry.registerTool("ungrouped", ungroupedTool, ungroupedRegistered);

        // Act
        List<Map<String, Object>> schemas = schemaProvider.getToolSchemas();

        // Assert
        assertEquals(2, schemas.size(), "Should include active group and ungrouped tools");

        List<String> toolNames =
                schemas.stream()
                        .map(
                                schema -> {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> function =
                                            (Map<String, Object>) schema.get("function");
                                    return (String) function.get("name");
                                })
                        .toList();

        assertTrue(toolNames.contains("search"));
        assertTrue(toolNames.contains("ungrouped"));
        assertFalse(toolNames.contains("admin"));
    }

    @Test
    void testGetToolSchemasWithExtendedModel() {
        // Arrange
        AgentTool tool = createMockTool("extended_tool", "Tool with extended params");

        Map<String, Object> additionalProps = new HashMap<>();
        Map<String, Object> extraParam = new HashMap<>();
        extraParam.put("type", "integer");
        additionalProps.put("extra", extraParam);

        RegisteredToolFunction.ExtendedModel extendedModel =
                new RegisteredToolFunction.SimpleExtendedModel(additionalProps, List.of("extra"));

        RegisteredToolFunction registered =
                new RegisteredToolFunction(tool, null, extendedModel, null);
        registry.registerTool("extended_tool", tool, registered);

        // Act
        List<Map<String, Object>> schemas = schemaProvider.getToolSchemas();

        // Assert
        assertEquals(1, schemas.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) schemas.get(0).get("function");

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");

        assertTrue(properties.containsKey("input"), "Should have base parameter");
        assertTrue(properties.containsKey("extra"), "Should have extended parameter");
    }

    @Test
    void testGetToolSchemasForModelEmpty() {
        // Act
        List<ToolSchema> schemas = schemaProvider.getToolSchemasForModel();

        // Assert
        assertNotNull(schemas);
        assertTrue(schemas.isEmpty());
    }

    @Test
    void testGetToolSchemasForModelWithUngroupedTool() {
        // Arrange
        AgentTool tool = createMockTool("test_tool", "Test tool");
        RegisteredToolFunction registered = new RegisteredToolFunction(tool, null, null, null);
        registry.registerTool("test_tool", tool, registered);

        // Act
        List<ToolSchema> schemas = schemaProvider.getToolSchemasForModel();

        // Assert
        assertEquals(1, schemas.size());
        ToolSchema schema = schemas.get(0);
        assertEquals("test_tool", schema.getName());
        assertEquals("Test tool", schema.getDescription());
        assertNotNull(schema.getParameters());
    }

    @Test
    void testGetToolSchemasForModelWithActiveGroup() {
        // Arrange
        groupManager.createToolGroup("analytics", "Analytics tools", true);
        AgentTool tool = createMockTool("analyze", "Analyze data");
        RegisteredToolFunction registered =
                new RegisteredToolFunction(tool, "analytics", null, null);
        registry.registerTool("analyze", tool, registered);

        // Act
        List<ToolSchema> schemas = schemaProvider.getToolSchemasForModel();

        // Assert
        assertEquals(1, schemas.size());
        assertEquals("analyze", schemas.get(0).getName());
    }

    @Test
    void testGetToolSchemasForModelFilterInactiveGroup() {
        // Arrange
        groupManager.createToolGroup("admin", "Admin tools", false);
        AgentTool tool = createMockTool("admin_command", "Admin command");
        RegisteredToolFunction registered = new RegisteredToolFunction(tool, "admin", null, null);
        registry.registerTool("admin_command", tool, registered);

        // Act
        List<ToolSchema> schemas = schemaProvider.getToolSchemasForModel();

        // Assert
        assertTrue(schemas.isEmpty(), "Inactive group tools should be filtered out");
    }

    @Test
    void testGetToolSchemasForModelMixedActiveInactive() {
        // Arrange - Active group
        groupManager.createToolGroup("search", "Search tools", true);
        AgentTool searchTool = createMockTool("search", "Search function");
        RegisteredToolFunction searchRegistered =
                new RegisteredToolFunction(searchTool, "search", null, null);
        registry.registerTool("search", searchTool, searchRegistered);

        // Arrange - Inactive group
        groupManager.createToolGroup("admin", "Admin tools", false);
        AgentTool adminTool = createMockTool("admin", "Admin function");
        RegisteredToolFunction adminRegistered =
                new RegisteredToolFunction(adminTool, "admin", null, null);
        registry.registerTool("admin", adminTool, adminRegistered);

        // Arrange - Ungrouped tool
        AgentTool ungroupedTool = createMockTool("ungrouped", "Ungrouped function");
        RegisteredToolFunction ungroupedRegistered =
                new RegisteredToolFunction(ungroupedTool, null, null, null);
        registry.registerTool("ungrouped", ungroupedTool, ungroupedRegistered);

        // Act
        List<ToolSchema> schemas = schemaProvider.getToolSchemasForModel();

        // Assert
        assertEquals(2, schemas.size(), "Should include active group and ungrouped tools");

        List<String> toolNames = schemas.stream().map(ToolSchema::getName).toList();

        assertTrue(toolNames.contains("search"));
        assertTrue(toolNames.contains("ungrouped"));
        assertFalse(toolNames.contains("admin"));
    }

    @Test
    void testGetToolSchemasForModelWithExtendedModel() {
        // Arrange
        AgentTool tool = createMockTool("extended_tool", "Tool with extended params");

        Map<String, Object> additionalProps = new HashMap<>();
        Map<String, Object> extraParam = new HashMap<>();
        extraParam.put("type", "integer");
        additionalProps.put("extra", extraParam);

        RegisteredToolFunction.ExtendedModel extendedModel =
                new RegisteredToolFunction.SimpleExtendedModel(additionalProps, List.of("extra"));

        RegisteredToolFunction registered =
                new RegisteredToolFunction(tool, null, extendedModel, null);
        registry.registerTool("extended_tool", tool, registered);

        // Act
        List<ToolSchema> schemas = schemaProvider.getToolSchemasForModel();

        // Assert
        assertEquals(1, schemas.size());
        ToolSchema schema = schemas.get(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) schema.getParameters().get("properties");

        assertTrue(properties.containsKey("input"), "Should have base parameter");
        assertTrue(properties.containsKey("extra"), "Should have extended parameter");
    }

    @Test
    void testGetToolSchemasMultipleTools() {
        // Arrange
        AgentTool tool1 = createMockTool("tool1", "First tool");
        AgentTool tool2 = createMockTool("tool2", "Second tool");
        AgentTool tool3 = createMockTool("tool3", "Third tool");

        registry.registerTool("tool1", tool1, new RegisteredToolFunction(tool1, null, null, null));
        registry.registerTool("tool2", tool2, new RegisteredToolFunction(tool2, null, null, null));
        registry.registerTool("tool3", tool3, new RegisteredToolFunction(tool3, null, null, null));

        // Act
        List<Map<String, Object>> schemas = schemaProvider.getToolSchemas();

        // Assert
        assertEquals(3, schemas.size());

        List<String> toolNames =
                schemas.stream()
                        .map(
                                schema -> {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> function =
                                            (Map<String, Object>) schema.get("function");
                                    return (String) function.get("name");
                                })
                        .toList();

        assertTrue(toolNames.contains("tool1"));
        assertTrue(toolNames.contains("tool2"));
        assertTrue(toolNames.contains("tool3"));
    }

    @Test
    void testGetToolSchemasForModelMultipleTools() {
        // Arrange
        AgentTool tool1 = createMockTool("tool1", "First tool");
        AgentTool tool2 = createMockTool("tool2", "Second tool");
        AgentTool tool3 = createMockTool("tool3", "Third tool");

        registry.registerTool("tool1", tool1, new RegisteredToolFunction(tool1, null, null, null));
        registry.registerTool("tool2", tool2, new RegisteredToolFunction(tool2, null, null, null));
        registry.registerTool("tool3", tool3, new RegisteredToolFunction(tool3, null, null, null));

        // Act
        List<ToolSchema> schemas = schemaProvider.getToolSchemasForModel();

        // Assert
        assertEquals(3, schemas.size());

        List<String> toolNames = schemas.stream().map(ToolSchema::getName).toList();

        assertTrue(toolNames.contains("tool1"));
        assertTrue(toolNames.contains("tool2"));
        assertTrue(toolNames.contains("tool3"));
    }

    @Test
    void testDynamicGroupActivation() {
        // Arrange
        groupManager.createToolGroup("dynamic", "Dynamic group", false);
        AgentTool tool = createMockTool("dynamic_tool", "Dynamic tool");
        RegisteredToolFunction registered = new RegisteredToolFunction(tool, "dynamic", null, null);
        registry.registerTool("dynamic_tool", tool, registered);

        // Act - Initially inactive
        List<Map<String, Object>> schemas1 = schemaProvider.getToolSchemas();
        assertEquals(0, schemas1.size(), "Tool should be filtered when group is inactive");

        // Activate group
        groupManager.updateToolGroups(List.of("dynamic"), true);

        // Act - Now active
        List<Map<String, Object>> schemas2 = schemaProvider.getToolSchemas();
        assertEquals(1, schemas2.size(), "Tool should be included when group is active");
    }
}
