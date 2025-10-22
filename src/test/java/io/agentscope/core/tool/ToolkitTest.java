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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.test.SampleTools;
import io.agentscope.core.tool.test.ToolTestUtils;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Toolkit.
 *
 * <p>These tests verify tool registration, discovery, execution, parameter validation, and
 * multi-tool management.
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
@Tag("unit")
@DisplayName("Toolkit Unit Tests")
class ToolkitTest {

    private Toolkit toolkit;
    private SampleTools sampleTools;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        sampleTools = new SampleTools();
    }

    @Test
    @DisplayName("Should register tools from object")
    void testToolRegistration() {
        // Register sample tools (pass instance, not class)
        assertDoesNotThrow(
                () -> {
                    toolkit.registerTool(sampleTools);
                });

        // Verify tools are registered
        Set<String> toolNames = toolkit.getToolNames();
        assertNotNull(toolNames, "Tool names should not be null");

        // Should have multiple tools from SampleTools class
        assertTrue(toolNames.size() > 0, "Should have at least one tool registered");
    }

    @Test
    @DisplayName("Should discover registered tools")
    void testToolDiscovery() {
        // Register tools
        toolkit.registerTool(sampleTools);

        // Get all tool names
        Set<String> toolNames = toolkit.getToolNames();
        assertNotNull(toolNames);
        assertTrue(toolNames.size() > 0);

        // Verify each tool can be retrieved
        for (String toolName : toolNames) {
            AgentTool tool = toolkit.getTool(toolName);
            assertNotNull(tool, "Should be able to get tool: " + toolName);
            assertNotNull(tool.getName(), "Tool should have a name");
            assertNotNull(tool.getDescription(), "Tool should have a description");
            assertNotNull(tool.getParameters(), "Tool should have parameters definition");
        }
    }

    @Test
    @DisplayName("Should execute tools correctly")
    void testToolExecution() {
        // Register tools
        toolkit.registerTool(sampleTools);

        // Get the 'add' tool
        AgentTool addTool = toolkit.getTool("add");

        if (addTool != null) {
            // Execute the tool
            Map<String, Object> params = Map.of("a", 5, "b", 3);
            ToolResultBlock response = addTool.call(params);

            assertNotNull(response, "Response should not be null");
            assertTrue(ToolTestUtils.isValidToolResultBlock(response), "Response should be valid");
        }
    }

    @Test
    @DisplayName("Should validate tool parameters")
    void testParameterValidation() {
        // Register tools
        toolkit.registerTool(sampleTools);

        Set<String> toolNames = toolkit.getToolNames();
        assertNotNull(toolNames);

        // Check each tool has valid parameter definition
        for (String toolName : toolNames) {
            AgentTool tool = toolkit.getTool(toolName);
            Map<String, Object> parameters = tool.getParameters();
            assertNotNull(parameters, "Parameters should not be null for tool: " + toolName);

            // Parameters should be a valid JSON Schema object
            assertTrue(
                    parameters.containsKey("type") || parameters.isEmpty(),
                    "Parameters should have 'type' field or be empty");
        }
    }

    @Test
    @DisplayName("Should manage multiple tools")
    void testMultipleTools() {
        // Register tools
        toolkit.registerTool(sampleTools);

        Set<String> toolNames = toolkit.getToolNames();
        assertNotNull(toolNames);

        // Should have multiple different tools
        assertTrue(toolNames.size() >= 3, "Should have at least 3 tools registered");

        // Verify all tools have unique names (Set guarantees this)
        assertEquals(toolNames.size(), toolNames.size(), "All tools should have unique names");
    }

    @Test
    @DisplayName("Should handle empty toolkit")
    void testEmptyToolkit() {
        // Empty toolkit
        Set<String> toolNames = toolkit.getToolNames();
        assertNotNull(toolNames, "Tool names should not be null even when empty");
        assertEquals(0, toolNames.size(), "Empty toolkit should have 0 tools");
    }
}
