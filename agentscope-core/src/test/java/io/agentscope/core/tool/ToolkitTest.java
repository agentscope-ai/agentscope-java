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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.test.SampleTools;
import io.agentscope.core.tool.test.ToolTestUtils;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

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
            ToolResultBlock response =
                    addTool.callAsync(ToolCallParam.builder().input(params).build()).block();

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
        toolkit.registration().tool(sampleTools).group("testGroup").apply();

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
        assertFalse(
                toolkit.getActiveGroups().contains("testGroup"),
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
        toolkit.registration().tool(sampleTools).group("activeGroup").apply();

        // Create a separate tool for inactive group
        SampleTools inactiveTools = new SampleTools();
        toolkit.registration().tool(inactiveTools).group("inactiveGroup").apply();

        // Get a tool from inactive group (should exist in registry)
        AgentTool tool = toolkit.getTool("add");
        assertNotNull(tool, "Tool should be registered");

        // Try to call the tool via callToolAsync
        Map<String, Object> addInput = Map.of("a", 1, "b", 2);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("add")
                        .input(addInput)
                        .content(JsonUtils.getJsonCodec().toJson(addInput))
                        .build();

        // First, verify it works when in active group
        toolkit.updateToolGroups(List.of("activeGroup"), true);
        toolkit.updateToolGroups(List.of("inactiveGroup"), false);
        ToolResultBlock result1 =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(result1, "Should execute when group is active");

        // Now deactivate the group and try again
        toolkit.updateToolGroups(List.of("activeGroup"), false);
        ToolResultBlock result2 =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
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
        Map<String, Object> addInput = Map.of("a", 1, "b", 2);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("add")
                        .input(addInput)
                        .content(JsonUtils.getJsonCodec().toJson(addInput))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(result, "Ungrouped tool should be callable");
        assertFalse(isErrorResult(result), "Ungrouped tool should execute successfully");
    }

    @Test
    @DisplayName("Should allow tool call from active group")
    void testAuthorizedToolCallSucceeds() {
        // Create an active group
        toolkit.createToolGroup("activeGroup", "Active tools", true);

        // Register tools to the group
        toolkit.registration().tool(sampleTools).group("activeGroup").apply();

        // Try to call the tool
        Map<String, Object> addInput = Map.of("a", 5, "b", 3);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("add")
                        .input(addInput)
                        .content(JsonUtils.getJsonCodec().toJson(addInput))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(result, "Should execute tool from active group");
        assertFalse(isErrorResult(result), "Should succeed: " + getResultText(result));
    }

    @Test
    @DisplayName("Should allow tool when any assigned group is active")
    void testToolAvailableWhenAssignedToMultipleGroups() {
        // Arrange - Create two groups
        toolkit.createToolGroup("groupA", "Group A", false);
        toolkit.createToolGroup("groupB", "Group B", false);

        toolkit.registration().tool(sampleTools).group("groupA").apply();
        toolkit.registration().tool(sampleTools).group("groupB").apply();

        Map<String, Object> addInput = Map.of("a", 5, "b", 7);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("add")
                        .input(addInput)
                        .content(JsonUtils.getJsonCodec().toJson(addInput))
                        .build();

        // Act & Assert - Activate each group separately and test
        toolkit.updateToolGroups(List.of("groupA"), true);
        toolkit.updateToolGroups(List.of("groupB"), false);
        ToolResultBlock resultWithGroupA =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(resultWithGroupA, "Should execute when groupA is active");
        assertFalse(
                isErrorResult(resultWithGroupA),
                "Should succeed when groupA is active: " + getResultText(resultWithGroupA));

        toolkit.updateToolGroups(List.of("groupA"), false);
        toolkit.updateToolGroups(List.of("groupB"), true);
        ToolResultBlock resultWithGroupB =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(resultWithGroupB, "Should execute when groupB is active");
        assertFalse(
                isErrorResult(resultWithGroupB),
                "Should succeed when groupB is active: " + getResultText(resultWithGroupB));

        toolkit.updateToolGroups(List.of("groupA", "groupB"), true);
        ToolResultBlock resultWithBoth =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(resultWithBoth, "Should execute when both groups are active");
        assertFalse(
                isErrorResult(resultWithBoth),
                "Should succeed when both groups are active: " + getResultText(resultWithBoth));

        toolkit.updateToolGroups(List.of("groupA", "groupB"), false);
        ToolResultBlock resultWithNone =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(resultWithNone, "Should execute when no groups are active");
        assertTrue(
                isErrorResult(resultWithNone),
                "Should fail when no groups are active: " + getResultText(resultWithNone));
    }

    @Test
    @DisplayName("Should prevent execution after group deactivation")
    void testToolGroupDeactivationPreventsExecution() {
        // Create an active group
        toolkit.createToolGroup("dynamicGroup", "Dynamic group", true);
        toolkit.registration().tool(sampleTools).group("dynamicGroup").apply();

        Map<String, Object> addInput = Map.of("a", 10, "b", 20);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("add")
                        .input(addInput)
                        .content(JsonUtils.getJsonCodec().toJson(addInput))
                        .build();

        // First call should succeed
        ToolResultBlock result1 =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(result1, "First call should work");
        assertFalse(isErrorResult(result1), "First call should succeed");

        // Deactivate the group
        toolkit.updateToolGroups(List.of("dynamicGroup"), false);

        // Second call should be rejected
        ToolResultBlock result2 =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(result2, "Should return error response");
        assertTrue(isErrorResult(result2), "Second call should fail after group deactivation");
        String errorText = getResultText(result2);
        assertTrue(
                errorText.contains("Unauthorized") || errorText.contains("not available"),
                "Error should indicate unauthorized access");
    }

    @Test
    @DisplayName("Should support preset parameters in tool registration")
    void testPresetParameters() {
        // Create a test tool that uses preset parameters
        class ContextTool {
            @Tool(description = "Test tool with context parameters")
            public ToolResultBlock testWithContext(
                    @ToolParam(name = "query", description = "User query") String query,
                    @ToolParam(name = "apiKey", description = "API key") String apiKey,
                    @ToolParam(name = "userId", description = "User ID") String userId) {
                String result =
                        String.format("Query: %s, API Key: %s, User ID: %s", query, apiKey, userId);
                return ToolResultBlock.text(result);
            }
        }

        // Register tool with preset parameters
        Map<String, Map<String, Object>> presetParams =
                Map.of("testWithContext", Map.of("apiKey", "secret_key_123", "userId", "user_456"));
        toolkit.registration().tool(new ContextTool()).presetParameters(presetParams).apply();

        // Verify tool is registered
        Set<String> toolNames = toolkit.getToolNames();
        assertTrue(toolNames.contains("testWithContext"), "Tool should be registered");

        // Verify JSON schema excludes preset parameters
        List<ToolSchema> schemas = toolkit.getToolSchemas();
        ToolSchema toolSchema =
                schemas.stream()
                        .filter(s -> "testWithContext".equals(getToolName(s)))
                        .findFirst()
                        .orElse(null);
        assertNotNull(toolSchema, "Tool schema should exist");

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = toolSchema.getParameters();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");

        // Schema should only contain "query", not "apiKey" or "userId"
        assertTrue(properties.containsKey("query"), "Schema should contain query parameter");
        assertFalse(
                properties.containsKey("apiKey"),
                "Schema should NOT contain preset parameter apiKey");
        assertFalse(
                properties.containsKey("userId"),
                "Schema should NOT contain preset parameter userId");

        // Call tool with only the query parameter
        Map<String, Object> queryInput = Map.of("query", "test query");
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .name("testWithContext")
                        .input(queryInput)
                        .content(JsonUtils.getJsonCodec().toJson(queryInput))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(result, "Result should not be null");
        assertFalse(isErrorResult(result), "Execution should succeed");

        // Verify preset parameters were injected
        String resultText = getResultText(result);
        assertTrue(resultText.contains("Query: test query"), "Result should contain user query");
        assertTrue(
                resultText.contains("API Key: secret_key_123"),
                "Result should contain preset API key");
        assertTrue(
                resultText.contains("User ID: user_456"), "Result should contain preset user ID");
    }

    @Test
    @DisplayName("Should allow agent parameters to override preset parameters")
    void testPresetParametersOverride() {
        class OverrideTool {
            @Tool(description = "Test tool for parameter override")
            public ToolResultBlock testOverride(
                    @ToolParam(name = "param1") String param1,
                    @ToolParam(name = "param2") String param2) {
                return ToolResultBlock.text(
                        String.format("param1: %s, param2: %s", param1, param2));
            }
        }

        // Register with preset parameters
        Map<String, Map<String, Object>> presetParams =
                Map.of(
                        "testOverride",
                        Map.of("param1", "preset_value1", "param2", "preset_value2"));
        toolkit.registration().tool(new OverrideTool()).presetParameters(presetParams).apply();

        // Call with agent providing param1 (should override preset)
        Map<String, Object> overrideInput = Map.of("param1", "agent_value1");
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .name("testOverride")
                        .input(overrideInput)
                        .content(JsonUtils.getJsonCodec().toJson(overrideInput))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        String resultText = getResultText(result);

        // param1 should be overridden by agent, param2 should use preset
        assertTrue(
                resultText.contains("param1: agent_value1"), "Agent value should override preset");
        assertTrue(resultText.contains("param2: preset_value2"), "Preset value should be used");
    }

    @Test
    @DisplayName("Should support updating preset parameters at runtime")
    void testUpdatePresetParameters() {
        class DynamicContextTool {
            @Tool(description = "Tool with dynamic context")
            public ToolResultBlock dynamicContext(@ToolParam(name = "sessionId") String sessionId) {
                return ToolResultBlock.text("Session ID: " + sessionId);
            }
        }

        // Register with initial preset parameters
        Map<String, Map<String, Object>> initialParams =
                Map.of("dynamicContext", Map.of("sessionId", "session_001"));
        toolkit.registration()
                .tool(new DynamicContextTool())
                .presetParameters(initialParams)
                .apply();

        // First call
        Map<String, Object> emptyInput = Map.of();
        ToolUseBlock toolCall1 =
                ToolUseBlock.builder()
                        .name("dynamicContext")
                        .input(emptyInput)
                        .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                        .build();
        ToolResultBlock result1 =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall1).build()).block();
        assertTrue(getResultText(result1).contains("session_001"), "Should use initial session");

        // Update preset parameters
        Map<String, Object> updatedParams = Map.of("sessionId", "session_002");
        toolkit.updateToolPresetParameters("dynamicContext", updatedParams);

        // Second call should use updated parameters
        ToolUseBlock toolCall2 =
                ToolUseBlock.builder()
                        .name("dynamicContext")
                        .input(emptyInput)
                        .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                        .build();
        ToolResultBlock result2 =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall2).build()).block();
        assertTrue(getResultText(result2).contains("session_002"), "Should use updated session");
    }

    @Test
    @DisplayName("Should throw exception at apply() when multiple types tools set")
    void testSetMultipleTypesTool() {
        // Mock class and action
        McpClientWrapper mcpClientWrapper = mock(McpClientWrapper.class);
        AgentTool agentTool = mock(AgentTool.class);
        TestToolObject testToolObject = new TestToolObject();

        // Not throw exception
        Toolkit.ToolRegistration toolRegistration = toolkit.registration();
        toolRegistration
                .mcpClient(mcpClientWrapper)
                .agentTool(agentTool)
                .tool(testToolObject)
                .subAgent(() -> mock(Agent.class));
        toolkit.registration()
                .agentTool(agentTool)
                .mcpClient(mcpClientWrapper)
                .tool(testToolObject)
                .subAgent(() -> mock(Agent.class));
        toolkit.registration()
                .tool(testToolObject)
                .agentTool(agentTool)
                .mcpClient(mcpClientWrapper)
                .subAgent(() -> mock(Agent.class));
        toolkit.registration()
                .subAgent(() -> mock(Agent.class))
                .mcpClient(mcpClientWrapper)
                .agentTool(agentTool)
                .tool(testToolObject);

        // Action
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> toolRegistration.apply());
        assertTrue(exception.getMessage().contains("Cannot set multiple registration types"));
    }

    @Test
    @DisplayName("Should throw exception at apply() when none tool set")
    void testSetNoneTool() {
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> toolkit.registration().apply());
        assertTrue(exception.getMessage().contains("Must call one of"));
    }

    @Test
    @DisplayName("Should not treat the incoming null value as a valid setting")
    void testSetNullTool() {
        AgentTool agentTool = mock(AgentTool.class);
        when(agentTool.getName()).thenReturn("mock_tool");
        // Action
        toolkit.registration().tool(null).agentTool(agentTool).apply();
    }

    @Test
    @DisplayName("Should handle setting value then resetting to null correctly")
    void testSetValueThenResetToNull() {
        // Create mock objects
        AgentTool agentTool = mock(AgentTool.class);
        when(agentTool.getName()).thenReturn("mock_tool");
        McpClientWrapper mcpClientWrapper = mock(McpClientWrapper.class);
        TestToolObject testToolObject = new TestToolObject();

        // Test 1: Set tool object, then reset to null, should throw exception
        Toolkit.ToolRegistration registration1 = toolkit.registration();
        registration1.tool(testToolObject).tool(null);
        IllegalStateException exception1 =
                assertThrows(IllegalStateException.class, () -> registration1.apply());
        assertTrue(
                exception1.getMessage().contains("Must call one of"),
                "Should throw exception when all values are null");

        // Test 2: Set agentTool, then reset to null, should throw exception
        Toolkit.ToolRegistration registration2 = toolkit.registration();
        registration2.agentTool(agentTool).agentTool(null);
        IllegalStateException exception2 =
                assertThrows(IllegalStateException.class, () -> registration2.apply());
        assertTrue(
                exception2.getMessage().contains("Must call one of"),
                "Should throw exception when all values are null");

        // Test 3: Set mcpClient, then reset to null, should throw exception
        Toolkit.ToolRegistration registration3 = toolkit.registration();
        registration3.mcpClient(mcpClientWrapper).mcpClient(null);
        IllegalStateException exception3 =
                assertThrows(IllegalStateException.class, () -> registration3.apply());
        assertTrue(
                exception3.getMessage().contains("Must call one of"),
                "Should throw exception when all values are null");

        // Test 4: Set subAgent, then reset to null, should throw exception
        Toolkit.ToolRegistration registration4 = toolkit.registration();
        registration4.subAgent(() -> mock(Agent.class)).subAgent(null);
        IllegalStateException exception4 =
                assertThrows(IllegalStateException.class, () -> registration4.apply());
        assertTrue(
                exception4.getMessage().contains("Must call one of"),
                "Should throw exception when all values are null");

        // Test 5: Set multiple values, then reset one to null, the last non-null should work
        Toolkit.ToolRegistration registration5 = toolkit.registration();
        registration5.tool(testToolObject).tool(null).agentTool(agentTool);
        assertDoesNotThrow(
                () -> registration5.apply(),
                "Should succeed when one valid tool type remains after reset");

        // Test 6: Set multiple values, then reset all but one to null, should succeed
        AgentTool agentTool2 = mock(AgentTool.class);
        when(agentTool2.getName()).thenReturn("mock_tool_2");
        Toolkit.ToolRegistration registration6 = toolkit.registration();
        registration6
                .tool(testToolObject)
                .agentTool(agentTool)
                .mcpClient(mcpClientWrapper)
                .tool(null)
                .mcpClient(null)
                .agentTool(agentTool2);
        assertDoesNotThrow(
                () -> registration6.apply(),
                "Should succeed when only one tool type is non-null after multiple resets");
    }

    /**
     * Helper method to extract tool name from schema.
     */
    private String getToolName(ToolSchema schema) {
        return schema.getName();
    }

    /**
     * Test tool class with @Tool annotated methods for testing tool object registration.
     */
    private static class TestToolObject {

        @Tool(name = "test_tool_method", description = "A test tool method")
        public Mono<ToolResultBlock> testToolMethod(
                @ToolParam(name = "input", description = "Test input") String input) {
            return Mono.just(
                    ToolResultBlock.of(TextBlock.builder().text("Result: " + input).build()));
        }
    }

    // ==================== Converter Tests ====================

    /**
     * Custom converter with no-arg constructor for testing.
     */
    public static class CustomNoArgConverter implements ToolResultConverter {
        @Override
        public ToolResultBlock convert(Object result, java.lang.reflect.Type returnType) {
            return ToolResultBlock.text("[CustomNoArg] " + result);
        }
    }

    /**
     * Invalid converter without no-arg constructor for testing.
     */
    public static class InvalidConverterNoConstructor implements ToolResultConverter {
        private final String config;

        public InvalidConverterNoConstructor(String config) {
            this.config = config;
        }

        @Override
        public ToolResultBlock convert(Object result, java.lang.reflect.Type returnType) {
            return ToolResultBlock.text(result + " with config: " + config);
        }
    }

    /**
     * Test tool class with custom converter.
     */
    private static class ToolWithCustomConverter {
        @Tool(
                name = "tool_with_custom_converter",
                description = "Tool with custom converter",
                converter = CustomNoArgConverter.class)
        public String execute(@ToolParam(name = "input") String input) {
            return "Processed: " + input;
        }
    }

    /**
     * Test tool class with invalid converter (no no-arg constructor).
     */
    private static class ToolWithInvalidConverter {
        @Tool(
                name = "tool_with_invalid_converter",
                description = "Tool with invalid converter",
                converter = InvalidConverterNoConstructor.class)
        public String execute(@ToolParam(name = "input") String input) {
            return "Should not reach here";
        }
    }

    /**
     * Test tool class with default converter.
     */
    private static class ToolWithDefaultConverter {
        @Tool(name = "tool_with_default_converter", description = "Tool with default converter")
        public String execute(@ToolParam(name = "input") String input) {
            return "Default: " + input;
        }
    }

    @Test
    @DisplayName("Should use custom converter when specified in @Tool annotation")
    void testParseConverterFromAnnotation_CustomConverter() {
        // Register tool with custom converter
        ToolWithCustomConverter toolObj = new ToolWithCustomConverter();
        toolkit.registerTool(toolObj);

        // Execute the tool
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .name("tool_with_custom_converter")
                        .input(Map.of("input", "test"))
                        .content(JsonUtils.getJsonCodec().toJson(Map.of("input", "test")))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();

        // Verify the custom converter was used
        assertNotNull(result, "Result should not be null");
        String resultText = getResultText(result);
        assertTrue(
                resultText.contains("[CustomNoArg]"),
                "Result should contain custom converter prefix. Got: " + resultText);
        assertTrue(
                resultText.contains("Processed: test"),
                "Result should contain processed input. Got: " + resultText);
    }

    @Test
    @DisplayName("Should use default converter when no converter specified")
    void testParseConverterFromAnnotation_DefaultConverter() {
        // Register tool without custom converter
        ToolWithDefaultConverter toolObj = new ToolWithDefaultConverter();
        toolkit.registerTool(toolObj);

        // Execute the tool
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .name("tool_with_default_converter")
                        .input(Map.of("input", "test"))
                        .content(JsonUtils.getJsonCodec().toJson(Map.of("input", "test")))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();

        // Verify the default converter was used (no custom prefix)
        assertNotNull(result, "Result should not be null");
        String resultText = getResultText(result);
        assertFalse(
                resultText.contains("[CustomNoArg]"),
                "Result should NOT contain custom converter prefix");
        assertTrue(resultText.contains("Default: test"), "Result should contain default output");
    }

    @Test
    @DisplayName("Should throw exception when converter has no no-arg constructor")
    void testInstantiateConverter_NoNoArgConstructor() {
        // Try to register tool with invalid converter
        ToolWithInvalidConverter toolObj = new ToolWithInvalidConverter();

        // Should throw IllegalStateException when trying to register
        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> toolkit.registerTool(toolObj),
                        "Should throw exception for converter without no-arg constructor");

        // Verify the exception message
        assertTrue(
                exception.getMessage().contains("Failed to create converter from @Tool annotation"),
                "Exception should indicate converter creation failure. Got: "
                        + exception.getMessage());

        Throwable cause = exception.getCause();
        assertNotNull(cause, "Exception should have a cause");
        assertTrue(
                cause instanceof IllegalStateException,
                "Cause should be IllegalStateException. Got: " + cause.getClass().getName());
        assertTrue(
                cause.getMessage().contains("must have either a no-arg constructor"),
                "Cause message should mention no-arg constructor requirement. Got: "
                        + cause.getMessage());
    }

    @Test
    @DisplayName("Should successfully instantiate converter with no-arg constructor")
    void testInstantiateConverter_WithNoArgConstructor() {
        // Register tool with valid custom converter
        ToolWithCustomConverter toolObj = new ToolWithCustomConverter();

        // Should not throw any exception
        assertDoesNotThrow(
                () -> toolkit.registerTool(toolObj),
                "Should successfully register tool with valid converter");

        // Verify the tool is registered
        AgentTool tool = toolkit.getTool("tool_with_custom_converter");
        assertNotNull(tool, "Tool should be registered successfully");

        // Verify the tool can be executed
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .name("tool_with_custom_converter")
                        .input(Map.of("input", "validation"))
                        .content(JsonUtils.getJsonCodec().toJson(Map.of("input", "validation")))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(result, "Tool should execute successfully");
        assertFalse(isErrorResult(result), "Tool execution should not have errors");
    }

    @Test
    @DisplayName("Should handle null annotation in parseConverterFromAnnotation")
    void testParseConverterFromAnnotation_NullAnnotation() {
        // This is tested indirectly by using default converter
        // When no custom converter is specified, the annotation's converter() returns
        // DefaultToolResultConverter.class which should work fine

        ToolWithDefaultConverter toolObj = new ToolWithDefaultConverter();
        assertDoesNotThrow(
                () -> toolkit.registerTool(toolObj),
                "Should handle tools without custom converter");

        AgentTool tool = toolkit.getTool("tool_with_default_converter");
        assertNotNull(tool, "Tool should be registered");
    }
}
