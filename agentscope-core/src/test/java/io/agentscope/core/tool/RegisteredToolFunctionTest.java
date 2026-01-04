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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class RegisteredToolFunctionTest {

    private AgentTool mockTool;

    @BeforeEach
    void setUp() {
        mockTool = createMockTool();
    }

    private AgentTool createMockTool() {
        return new AgentTool() {
            @Override
            public String getName() {
                return "testTool";
            }

            @Override
            public String getDescription() {
                return "Test description";
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");

                Map<String, Object> properties = new HashMap<>();
                Map<String, Object> param1 = new HashMap<>();
                param1.put("type", "string");
                properties.put("param1", param1);

                schema.put("properties", properties);
                schema.put("required", List.of("param1"));
                return schema;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text("result"));
            }
        };
    }

    @Test
    void testConstructorWithAllParameters() {
        // Arrange
        ExtendedModel extendedModel = new SimpleExtendedModel(Map.of(), List.of());

        // Act
        RegisteredToolFunction registered =
                new RegisteredToolFunction(mockTool, "group1", extendedModel, "mcpClient1");

        // Assert
        assertEquals(mockTool, registered.getTool());
        assertEquals("group1", registered.getGroupName());
        assertEquals(extendedModel, registered.getExtendedModel());
        assertEquals("mcpClient1", registered.getMcpClientName());
    }

    @Test
    void testConstructorWithNullOptionalParameters() {
        // Act
        RegisteredToolFunction registered = new RegisteredToolFunction(mockTool, null, null, null);

        // Assert
        assertEquals(mockTool, registered.getTool());
        assertNull(registered.getGroupName());
        assertNull(registered.getExtendedModel());
        assertNull(registered.getMcpClientName());
    }

    @Test
    void testGetExtendedParametersWithoutExtendedModel() {
        // Arrange
        RegisteredToolFunction registered = new RegisteredToolFunction(mockTool, null, null, null);

        // Act
        Map<String, Object> params = registered.getExtendedParameters();

        // Assert
        assertEquals(mockTool.getParameters(), params);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) params.get("properties");
        assertTrue(properties.containsKey("param1"));
    }

    @Test
    void testGetExtendedParametersWithExtendedModel() {
        // Arrange
        Map<String, Object> additionalProps = new HashMap<>();
        Map<String, Object> param2Schema = new HashMap<>();
        param2Schema.put("type", "integer");
        additionalProps.put("param2", param2Schema);

        ExtendedModel extendedModel = new SimpleExtendedModel(additionalProps, List.of("param2"));

        RegisteredToolFunction registered =
                new RegisteredToolFunction(mockTool, "group1", extendedModel, null);

        // Act
        Map<String, Object> merged = registered.getExtendedParameters();

        // Assert
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) merged.get("properties");
        assertTrue(properties.containsKey("param1"), "Should contain base property");
        assertTrue(properties.containsKey("param2"), "Should contain extended property");

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) merged.get("required");
        assertTrue(required.contains("param1"), "Should contain base required");
        assertTrue(required.contains("param2"), "Should contain extended required");
    }

    @Test
    void testExtendedModelConflictDetection() {
        // Arrange - create extended model with conflicting property
        Map<String, Object> conflictingProps = new HashMap<>();
        Map<String, Object> param1Schema = new HashMap<>();
        param1Schema.put("type", "integer"); // Conflicts with base param1
        conflictingProps.put("param1", param1Schema);

        ExtendedModel extendedModel = new SimpleExtendedModel(conflictingProps, List.of());

        RegisteredToolFunction registered =
                new RegisteredToolFunction(mockTool, "group1", extendedModel, null);

        // Act & Assert
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> registered.getExtendedParameters());

        assertTrue(exception.getMessage().contains("conflicting properties"));
        assertTrue(exception.getMessage().contains("param1"));
    }

    @Test
    void testSimpleExtendedModelGetters() {
        // Arrange
        Map<String, Object> props = Map.of("key", "value");
        List<String> required = List.of("field1", "field2");

        // Act
        SimpleExtendedModel extendedModel = new SimpleExtendedModel(props, required);

        // Assert
        assertEquals(props, extendedModel.getAdditionalProperties());
        assertEquals(required, extendedModel.getAdditionalRequired());
    }

    @Test
    void testMergeWithBaseSchemaEmptyExtension() {
        // Arrange
        ExtendedModel extendedModel = new SimpleExtendedModel(Map.of(), List.of());

        // Act
        Map<String, Object> merged = extendedModel.mergeWithBaseSchema(mockTool.getParameters());

        // Assert
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) merged.get("properties");
        assertEquals(1, properties.size());
        assertTrue(properties.containsKey("param1"));
    }

    @Test
    void testMergeWithBaseSchemaEmptyBase() {
        // Arrange
        Map<String, Object> additionalProps = new HashMap<>();
        Map<String, Object> param2Schema = new HashMap<>();
        param2Schema.put("type", "string");
        additionalProps.put("param2", param2Schema);

        ExtendedModel extendedModel = new SimpleExtendedModel(additionalProps, List.of("param2"));

        Map<String, Object> emptyBase = new HashMap<>();
        emptyBase.put("type", "object");

        // Act
        Map<String, Object> merged = extendedModel.mergeWithBaseSchema(emptyBase);

        // Assert
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) merged.get("properties");
        assertEquals(1, properties.size());
        assertTrue(properties.containsKey("param2"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) merged.get("required");
        assertTrue(required.contains("param2"));
    }

    @Test
    void testMergeWithBaseSchemaNoConflict() {
        // Arrange
        Map<String, Object> additionalProps = new HashMap<>();
        Map<String, Object> param2Schema = new HashMap<>();
        param2Schema.put("type", "integer");
        additionalProps.put("param2", param2Schema);

        Map<String, Object> param3Schema = new HashMap<>();
        param3Schema.put("type", "boolean");
        additionalProps.put("param3", param3Schema);

        ExtendedModel extendedModel =
                new SimpleExtendedModel(additionalProps, List.of("param2", "param3"));

        // Act
        Map<String, Object> merged = extendedModel.mergeWithBaseSchema(mockTool.getParameters());

        // Assert
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) merged.get("properties");
        assertEquals(3, properties.size());
        assertTrue(properties.containsKey("param1"));
        assertTrue(properties.containsKey("param2"));
        assertTrue(properties.containsKey("param3"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) merged.get("required");
        assertEquals(3, required.size());
        assertTrue(required.contains("param1"));
        assertTrue(required.contains("param2"));
        assertTrue(required.contains("param3"));
    }

    @Test
    void testMergeWithMultipleConflicts() {
        // Arrange
        Map<String, Object> conflictingProps = new HashMap<>();
        Map<String, Object> param1Schema = new HashMap<>();
        param1Schema.put("type", "integer");
        conflictingProps.put("param1", param1Schema);

        // Add param2 to base schema for additional conflict
        AgentTool toolWithMultipleParams =
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "testTool";
                    }

                    @Override
                    public String getDescription() {
                        return "Test";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        Map<String, Object> schema = new HashMap<>();
                        Map<String, Object> properties = new HashMap<>();
                        properties.put("param1", Map.of("type", "string"));
                        properties.put("param2", Map.of("type", "string"));
                        schema.put("properties", properties);
                        return schema;
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam input) {
                        return Mono.just(ToolResultBlock.text("result"));
                    }
                };

        conflictingProps.put("param2", Map.of("type", "integer"));

        ExtendedModel extendedModel = new SimpleExtendedModel(conflictingProps, List.of());

        // Act & Assert
        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                extendedModel.mergeWithBaseSchema(
                                        toolWithMultipleParams.getParameters()));

        assertTrue(exception.getMessage().contains("param1"));
        assertTrue(exception.getMessage().contains("param2"));
    }

    @Test
    void testGroupedTool() {
        // Act
        RegisteredToolFunction registered =
                new RegisteredToolFunction(mockTool, "analytics", null, null);

        // Assert
        assertEquals("analytics", registered.getGroupName());
        assertNull(registered.getMcpClientName());
    }

    @Test
    void testMcpTool() {
        // Act
        RegisteredToolFunction registered =
                new RegisteredToolFunction(mockTool, "group1", null, "filesystem");

        // Assert
        assertEquals("group1", registered.getGroupName());
        assertEquals("filesystem", registered.getMcpClientName());
    }

    @Test
    void testUngroupedNonMcpTool() {
        // Act
        RegisteredToolFunction registered = new RegisteredToolFunction(mockTool, null, null, null);

        // Assert
        assertNull(registered.getGroupName());
        assertNull(registered.getMcpClientName());
    }

    @Test
    void testMergePreservesOtherBaseSchemaFields() {
        // Arrange
        Map<String, Object> baseSchema = new HashMap<>(mockTool.getParameters());
        baseSchema.put("additionalProperties", false);
        baseSchema.put("title", "Base Schema");

        ExtendedModel extendedModel =
                new SimpleExtendedModel(Map.of("param2", Map.of("type", "string")), List.of());

        // Act
        Map<String, Object> merged = extendedModel.mergeWithBaseSchema(baseSchema);

        // Assert
        assertEquals(false, merged.get("additionalProperties"));
        assertEquals("Base Schema", merged.get("title"));
    }
}
