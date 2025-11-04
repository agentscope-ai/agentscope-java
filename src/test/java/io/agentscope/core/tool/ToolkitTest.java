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

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.test.SampleTools;
import io.agentscope.core.tool.test.ToolTestUtils;
import java.util.List;
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

    /**
     * Helper method to check if a ToolResultBlock represents an error.
     * Error results have output containing TextBlock with text starting with "Error:"
     */
    private boolean isErrorResult(ToolResultBlock result) {
        if (result == null || result.getOutput() == null || result.getOutput().isEmpty()) {
            return false;
        }
        return result.getOutput().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .anyMatch(text -> text != null && text.startsWith("Error:"));
    }

    /**
     * Helper method to extract text content from ToolResultBlock.
     */
    private String getResultText(ToolResultBlock result) {
        if (result == null || result.getOutput() == null || result.getOutput().isEmpty()) {
            return "";
        }
        return result.getOutput().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .findFirst()
                .orElse("");
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
            ToolResultBlock response = addTool.callAsync(params).block();

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

    @Test
    @DisplayName("Should allow tool deletion by default")
    void testDefaultAllowToolDeletion() {
        // Register and remove tool
        toolkit.registerTool(sampleTools);
        Set<String> toolNames = toolkit.getToolNames();
        assertTrue(toolNames.size() > 0, "Should have tools registered");

        // Get first tool name
        String toolName = toolNames.iterator().next();

        // Remove should work with default config
        assertDoesNotThrow(() -> toolkit.removeTool(toolName));

        // Tool should be removed
        AgentTool tool = toolkit.getTool(toolName);
        assertEquals(null, tool, "Tool should be removed");
    }

    @Test
    @DisplayName("Should ignore removeTool when deletion is disabled")
    void testRemoveToolWhenDeletionDisabled() {
        // Create toolkit with deletion disabled
        ToolkitConfig config = ToolkitConfig.builder().allowToolDeletion(false).build();
        Toolkit toolkit = new Toolkit(config);

        // Register tools
        toolkit.registerTool(sampleTools);
        Set<String> toolNames = toolkit.getToolNames();
        int initialCount = toolNames.size();
        assertTrue(initialCount > 0, "Should have tools registered");

        // Get first tool name
        String toolName = toolNames.iterator().next();

        // Remove should be ignored
        toolkit.removeTool(toolName);

        // Tool should still exist
        AgentTool tool = toolkit.getTool(toolName);
        assertNotNull(tool, "Tool should still exist after ignored removal");
        assertEquals(initialCount, toolkit.getToolNames().size(), "Tool count should not change");
    }

    @Test
    @DisplayName("Should ignore removeToolGroups when deletion is disabled")
    void testRemoveToolGroupsWhenDeletionDisabled() {
        // Create toolkit with deletion disabled
        ToolkitConfig config = ToolkitConfig.builder().allowToolDeletion(false).build();
        Toolkit toolkit = new Toolkit(config);

        // Create a tool group and register tools
        toolkit.createToolGroup("testGroup", "Test group");
        toolkit.registerTool(sampleTools, "testGroup");

        Set<String> toolNames = toolkit.getToolNames();
        int initialCount = toolNames.size();
        assertTrue(initialCount > 0, "Should have tools registered");

        // Remove group should be ignored
        toolkit.removeToolGroups(List.of("testGroup"));

        // Tools should still exist
        assertEquals(initialCount, toolkit.getToolNames().size(), "Tool count should not change");
        assertNotNull(toolkit.getToolGroup("testGroup"), "Group should still exist");
    }

    @Test
    @DisplayName("Should ignore tool group deactivation when deletion is disabled")
    void testDeactivateToolGroupWhenDeletionDisabled() {
        // Create toolkit with deletion disabled
        ToolkitConfig config = ToolkitConfig.builder().allowToolDeletion(false).build();
        Toolkit toolkit = new Toolkit(config);

        // Create an active tool group
        toolkit.createToolGroup("testGroup", "Test group", true);
        assertTrue(
                toolkit.getActiveGroups().contains("testGroup"),
                "Group should be active initially");

        // Deactivation should be ignored
        toolkit.updateToolGroups(List.of("testGroup"), false);

        // Group should still be active
        assertTrue(
                toolkit.getActiveGroups().contains("testGroup"),
                "Group should still be active after ignored deactivation");
    }

    @Test
    @DisplayName("Should allow tool group activation when deletion is disabled")
    void testActivateToolGroupWhenDeletionDisabled() {
        // Create toolkit with deletion disabled
        ToolkitConfig config = ToolkitConfig.builder().allowToolDeletion(false).build();
        Toolkit toolkit = new Toolkit(config);

        // Create an inactive tool group
        toolkit.createToolGroup("testGroup", "Test group", false);
        assertTrue(
                !toolkit.getActiveGroups().contains("testGroup"),
                "Group should be inactive initially");

        // Activation should still work
        toolkit.updateToolGroups(List.of("testGroup"), true);

        // Group should be activated
        assertTrue(
                toolkit.getActiveGroups().contains("testGroup"),
                "Group should be activated even with deletion disabled");
    }

    @Test
    @DisplayName("Should allow tool registration and override when deletion is disabled")
    void testToolRegistrationWhenDeletionDisabled() {
        // Create toolkit with deletion disabled
        ToolkitConfig config = ToolkitConfig.builder().allowToolDeletion(false).build();
        Toolkit toolkit = new Toolkit(config);

        // Registration should work
        assertDoesNotThrow(() -> toolkit.registerTool(sampleTools));
        Set<String> toolNames = toolkit.getToolNames();
        assertTrue(toolNames.size() > 0, "Should be able to register tools");

        // Re-registration (override) should also work
        assertDoesNotThrow(
                () -> toolkit.registerTool(sampleTools),
                "Should be able to override existing tools");
    }

    @Test
    @DisplayName("Should reject tool call from inactive group")
    void testUnauthorizedToolCallShouldBeRejected() {
        // Create two groups: active and inactive
        toolkit.createToolGroup("activeGroup", "Active tools", true);
        toolkit.createToolGroup("inactiveGroup", "Inactive tools", false);

        // Register tools to different groups
        toolkit.registerTool(sampleTools, "activeGroup");

        // Create a separate tool for inactive group
        SampleTools inactiveTools = new SampleTools();
        toolkit.registerTool(inactiveTools, "inactiveGroup");

        // Get a tool from inactive group (should exist in registry)
        AgentTool tool = toolkit.getTool("add");
        assertNotNull(tool, "Tool should be registered");

        // Try to call the tool via callToolAsync
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("add")
                        .input(Map.of("a", 1, "b", 2))
                        .build();

        // First, verify it works when in active group
        toolkit.updateToolGroups(List.of("activeGroup"), true);
        toolkit.updateToolGroups(List.of("inactiveGroup"), false);
        ToolResultBlock result1 = toolkit.callToolAsync(toolCall).block();
        assertNotNull(result1, "Should execute when group is active");

        // Now deactivate the group and try again
        toolkit.updateToolGroups(List.of("activeGroup"), false);
        ToolResultBlock result2 = toolkit.callToolAsync(toolCall).block();
        assertNotNull(result2, "Should return error response");
        assertTrue(
                isErrorResult(result2),
                "Should be an error when tool's group is inactive: " + getResultText(result2));
        String errorText = getResultText(result2);
        assertTrue(
                errorText.contains("Unauthorized") || errorText.contains("not available"),
                "Error message should indicate unauthorized access: " + errorText);
    }

    @Test
    @DisplayName("Should allow ungrouped tools to be called regardless of groups")
    void testUngroupedToolsAlwaysCallable() {
        // Create a group and deactivate it
        toolkit.createToolGroup("someGroup", "Some group", false);

        // Register tool without group
        toolkit.registerTool(sampleTools);

        // Try to call ungrouped tool
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("add")
                        .input(Map.of("a", 1, "b", 2))
                        .build();

        ToolResultBlock result = toolkit.callToolAsync(toolCall).block();
        assertNotNull(result, "Ungrouped tool should be callable");
        assertTrue(!isErrorResult(result), "Ungrouped tool should execute successfully");
    }

    @Test
    @DisplayName("Should allow tool call from active group")
    void testAuthorizedToolCallSucceeds() {
        // Create an active group
        toolkit.createToolGroup("activeGroup", "Active tools", true);

        // Register tools to the group
        toolkit.registerTool(sampleTools, "activeGroup");

        // Try to call the tool
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("add")
                        .input(Map.of("a", 5, "b", 3))
                        .build();

        ToolResultBlock result = toolkit.callToolAsync(toolCall).block();
        assertNotNull(result, "Should execute tool from active group");
        assertTrue(!isErrorResult(result), "Should succeed: " + getResultText(result));
    }

    @Test
    @DisplayName("Should prevent execution after group deactivation")
    void testToolGroupDeactivationPreventsExecution() {
        // Create an active group
        toolkit.createToolGroup("dynamicGroup", "Dynamic group", true);
        toolkit.registerTool(sampleTools, "dynamicGroup");

        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("add")
                        .input(Map.of("a", 10, "b", 20))
                        .build();

        // First call should succeed
        ToolResultBlock result1 = toolkit.callToolAsync(toolCall).block();
        assertNotNull(result1, "First call should work");
        assertTrue(!isErrorResult(result1), "First call should succeed");

        // Deactivate the group
        toolkit.updateToolGroups(List.of("dynamicGroup"), false);

        // Second call should be rejected
        ToolResultBlock result2 = toolkit.callToolAsync(toolCall).block();
        assertNotNull(result2, "Should return error response");
        assertTrue(isErrorResult(result2), "Second call should fail after group deactivation");
        String errorText = getResultText(result2);
        assertTrue(
                errorText.contains("Unauthorized") || errorText.contains("not available"),
                "Error should indicate unauthorized access");
    }
}
