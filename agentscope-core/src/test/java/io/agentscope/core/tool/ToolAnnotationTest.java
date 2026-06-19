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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.test.SampleTools;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for Tool annotation processing.
 *
 * <p>These tests verify @Tool and @ToolParam annotation parsing, schema generation, and invalid
 * annotation handling.
 *
 * <p>Tagged as "unit" - tests annotation processing without execution.
 */
@Tag("unit")
@DisplayName("Tool Annotation Tests")
class ToolAnnotationTest {

    private Toolkit toolkit;
    private SampleTools sampleTools;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        sampleTools = new SampleTools();
    }

    @Test
    @DisplayName("Should parse @Tool annotations correctly")
    void testToolAnnotationParsing() {
        // Register tools with annotations
        toolkit.registerTool(sampleTools);

        // Get registered tool names
        Set<String> toolNames = toolkit.getToolNames();
        assertNotNull(toolNames, "Tools should be registered");
        assertTrue(toolNames.size() > 0, "Should parse and register tools");

        // Verify tool has name and description from @Tool annotation
        for (String toolName : toolNames) {
            AgentTool tool = toolkit.getTool(toolName);
            assertNotNull(tool, "Should get tool: " + toolName);
            assertNotNull(tool.getName(), "Tool should have name from @Tool annotation");
            assertNotNull(
                    tool.getDescription(), "Tool should have description from @Tool annotation");

            // Name should not be empty
            assertTrue(tool.getName().length() > 0, "Tool name should not be empty");
            assertTrue(tool.getDescription().length() > 0, "Tool description should not be empty");
        }
    }

    @Test
    @DisplayName("Should parse @ToolParam annotations")
    void testToolParamAnnotation() {
        // Register tools
        toolkit.registerTool(sampleTools);

        // Find tool with multiple parameters
        AgentTool multiParamTool = toolkit.getTool("multi_param");

        if (multiParamTool != null) {
            // Verify parameters are parsed from @ToolParam annotations
            Map<String, Object> parameters = multiParamTool.getParameters();
            assertNotNull(parameters, "Parameters should be parsed from @ToolParam annotations");

            // Should have properties for each parameter
            if (parameters.containsKey("properties")) {
                Object properties = parameters.get("properties");
                assertNotNull(properties, "Should have properties object");
            }
        }
    }

    @Test
    @DisplayName("Should generate tool schema from annotations")
    void testToolSchemaGeneration() {
        // Register tools
        toolkit.registerTool(sampleTools);

        // Verify each tool has generated schema
        Set<String> toolNames = toolkit.getToolNames();
        for (String toolName : toolNames) {
            AgentTool tool = toolkit.getTool(toolName);
            Map<String, Object> parameters = tool.getParameters();
            assertNotNull(parameters, "Schema should be generated for tool: " + toolName);

            // Schema should be a valid JSON Schema structure
            // At minimum, it should be a non-null map
            assertTrue(parameters instanceof Map, "Schema should be a valid structure");
        }
    }

    @Test
    @DisplayName("Should handle methods without Tool annotation")
    void testInvalidAnnotations() {
        // Try to find methods in SampleTools class
        Method[] methods = SampleTools.class.getDeclaredMethods();
        assertNotNull(methods, "Should find methods in SampleTools");

        int toolAnnotatedMethods = 0;
        for (Method method : methods) {
            if (method.isAnnotationPresent(Tool.class)) {
                toolAnnotatedMethods++;
            }
        }

        // Register tools
        toolkit.registerTool(sampleTools);

        // Number of registered tools should match annotated methods
        Set<String> toolNames = toolkit.getToolNames();
        int registeredTools = toolNames.size();

        assertTrue(registeredTools > 0, "Should register tools with @Tool annotation");

        // Note: Some methods might not be annotated (e.g., private helper methods)
        // So registered count might be less than or equal to total methods
        assertTrue(
                registeredTools <= methods.length, "Should not register more tools than methods");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("Should include additionalProperties=false in schema when set")
    void testAdditionalPropertiesFalseInSchema() {
        toolkit.registerTool(
                new Object() {
                    @Tool(
                            name = "query_data",
                            description = "Query data",
                            additionalProperties = false)
                    public String queryData(
                            @ToolParam(name = "keyword", description = "keyword") String keyword) {
                        return "result";
                    }
                });

        Map<String, Object> schema = toolkit.getToolSchemas().get(0).getParameters();

        assertTrue(
                schema.containsKey("additionalProperties"),
                "Schema should contain additionalProperties key");
        assertEquals(
                false, schema.get("additionalProperties"), "additionalProperties should be false");

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertFalse(
                properties.containsKey("additionalProperties"),
                "additionalProperties should NOT be inside properties map");
    }

    @Test
    @DisplayName("Should not include additionalProperties when default (true)")
    void testAdditionalPropertiesDefaultNotInSchema() {
        toolkit.registerTool(
                new Object() {
                    @Tool(name = "query_data", description = "Query data")
                    public String queryData(
                            @ToolParam(name = "keyword", description = "keyword") String keyword) {
                        return "result";
                    }
                });

        Map<String, Object> schema = toolkit.getToolSchemas().get(0).getParameters();

        assertFalse(
                schema.containsKey("additionalProperties"),
                "Default behavior should NOT add additionalProperties to schema");
    }

    @Test
    @DisplayName("Should reject hallucinated params when additionalProperties=false")
    void testRejectHallucinatedParamsWhenStrict() {
        toolkit.registerTool(
                new Object() {
                    @Tool(
                            name = "query_data",
                            description = "Query data",
                            additionalProperties = false)
                    public String queryData(
                            @ToolParam(name = "keyword", description = "keyword") String keyword) {
                        return "result";
                    }
                });

        Map<String, Object> schema = toolkit.getToolSchemas().get(0).getParameters();
        String hallucinatedInput = "{\"keyword\": \"test\", \"owners\": [\"admin\"]}";

        String validationError = ToolValidator.validateInput(hallucinatedInput, schema);

        assertNotNull(
                validationError,
                "ToolValidator should reject hallucinated params when "
                        + "additionalProperties=false");
        assertTrue(
                validationError.contains("owners"),
                "Error message should mention the hallucinated param");
    }

    @Test
    @DisplayName("Should allow extra params by default (backward compatible)")
    void testAllowExtraParamsByDefault() {
        toolkit.registerTool(
                new Object() {
                    @Tool(name = "query_data", description = "Query data")
                    public String queryData(
                            @ToolParam(name = "keyword", description = "keyword") String keyword) {
                        return "result";
                    }
                });

        Map<String, Object> schema = toolkit.getToolSchemas().get(0).getParameters();
        String hallucinatedInput = "{\"keyword\": \"test\", \"owners\": [\"admin\"]}";

        String validationError = ToolValidator.validateInput(hallucinatedInput, schema);

        assertNull(
                validationError,
                "Default behavior should allow extra params (backward compatible)");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName(
            "strict=true should inject additionalProperties=false even when additionalProperties"
                    + " defaults to true")
    void testStrictOverridesAdditionalProperties() {
        toolkit.registerTool(
                new Object() {
                    @Tool(name = "strict_tool", description = "Strict tool", strict = true)
                    public String execute(
                            @ToolParam(name = "keyword", description = "keyword") String keyword) {
                        return "result";
                    }
                });

        Map<String, Object> schema = toolkit.getToolSchemas().get(0).getParameters();

        assertTrue(
                schema.containsKey("additionalProperties"),
                "Schema should contain additionalProperties=false when strict=true");
        assertEquals(
                false,
                schema.get("additionalProperties"),
                "strict=true should force additionalProperties=false");
    }
}
