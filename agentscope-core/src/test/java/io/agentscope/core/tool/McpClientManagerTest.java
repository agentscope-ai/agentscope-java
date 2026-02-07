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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class McpClientManagerTest {

    private McpClientManager manager;
    private Method shouldRegisterToolMethod;

    @BeforeEach
    void setUp() throws Exception {
        ToolRegistry toolRegistry = new ToolRegistry();
        ToolGroupManager groupManager = new ToolGroupManager();

        // Create manager with a no-op callback
        manager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            // no-op callback for testing
                        });

        // Get the private method using reflection
        shouldRegisterToolMethod =
                McpClientManager.class.getDeclaredMethod(
                        "shouldRegisterTool", String.class, List.class, List.class);
        shouldRegisterToolMethod.setAccessible(true);
    }

    private boolean invokeShouldRegisterTool(
            String toolName, List<String> enableTools, List<String> disableTools) throws Exception {
        return (boolean)
                shouldRegisterToolMethod.invoke(manager, toolName, enableTools, disableTools);
    }

    // ==================== Tests for null/empty lists ====================

    @Test
    void testShouldRegisterTool_BothListsNull_ReturnsTrue() throws Exception {
        // When both lists are null, all tools should be registered
        assertTrue(invokeShouldRegisterTool("anyTool", null, null));
    }

    @Test
    void testShouldRegisterTool_BothListsEmpty_ReturnsTrue() throws Exception {
        // When both lists are empty, all tools should be registered
        assertTrue(
                invokeShouldRegisterTool(
                        "anyTool", Collections.emptyList(), Collections.emptyList()));
    }

    // ==================== Tests for disableTools only ====================

    @Test
    void testShouldRegisterTool_DisableToolsContainsTool_ReturnsFalse() throws Exception {
        // When tool is in disableTools, it should not be registered
        List<String> disableTools = Arrays.asList("tool1", "tool2", "tool3");
        assertFalse(invokeShouldRegisterTool("tool2", null, disableTools));
    }

    @Test
    void testShouldRegisterTool_DisableToolsDoesNotContainTool_ReturnsTrue() throws Exception {
        // When tool is NOT in disableTools, it should be registered
        List<String> disableTools = Arrays.asList("tool1", "tool2", "tool3");
        assertTrue(invokeShouldRegisterTool("tool4", null, disableTools));
    }

    // ==================== Tests for enableTools only ====================

    @Test
    void testShouldRegisterTool_EnableToolsContainsTool_ReturnsTrue() throws Exception {
        // When tool is in enableTools, it should be registered
        List<String> enableTools = Arrays.asList("tool1", "tool2", "tool3");
        assertTrue(invokeShouldRegisterTool("tool2", enableTools, null));
    }

    @Test
    void testShouldRegisterTool_EnableToolsDoesNotContainTool_ReturnsFalse() throws Exception {
        // When tool is NOT in enableTools, it should not be registered
        List<String> enableTools = Arrays.asList("tool1", "tool2", "tool3");
        assertFalse(invokeShouldRegisterTool("tool4", enableTools, null));
    }

    // ==================== Tests for both lists specified ====================

    @Test
    void testShouldRegisterTool_BothListsSpecified_EnableToolsTakesPrecedence() throws Exception {
        // enableTools is checked last, so it takes precedence
        List<String> enableTools = Arrays.asList("tool1", "tool2");
        List<String> disableTools = Arrays.asList("tool2", "tool3");

        // tool1: not in disableTools, in enableTools -> true
        assertTrue(invokeShouldRegisterTool("tool1", enableTools, disableTools));

        // tool2: in disableTools (would be false), but in enableTools -> true (enableTools wins)
        assertTrue(invokeShouldRegisterTool("tool2", enableTools, disableTools));

        // tool3: in disableTools, not in enableTools -> false
        assertFalse(invokeShouldRegisterTool("tool3", enableTools, disableTools));

        // tool4: not in either list, but enableTools is specified -> false
        assertFalse(invokeShouldRegisterTool("tool4", enableTools, disableTools));
    }

    // Test for preset parameters functionality in McpClientManager
    @Test
    void testRegisterMcpClient_WithPresetParametersMapping() {
        // Create mocks
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolGroupManager groupManager = mock(ToolGroupManager.class);
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);

        // Create a manager with a tracking callback
        boolean[] callbackCalled = {false};
        McpClientManager manager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            callbackCalled[0] = true;
                            // Verify that presetParams is passed correctly
                            assertNotNull(presetParams);
                            assertTrue(presetParams.containsKey("param3"));
                            assertEquals("preset_value", presetParams.get("param3"));
                        });

        // Setup client wrapper
        when(clientWrapper.getName()).thenReturn("test-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());

        // Create a mock MCP tool
        McpSchema.Tool mockMcpTool = mock(McpSchema.Tool.class);
        when(mockMcpTool.name()).thenReturn("test-tool");
        when(mockMcpTool.description()).thenReturn("Test tool description");

        // Create schema with properties
        Map<String, Object> properties = new HashMap<>();
        properties.put("param1", Map.of("type", "string"));
        properties.put("param2", Map.of("type", "number"));
        List<String> required = List.of("param1");

        McpSchema.JsonSchema schema =
                new McpSchema.JsonSchema("object", properties, required, null, null, null);
        when(mockMcpTool.inputSchema()).thenReturn(schema);

        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(mockMcpTool)));

        // Define preset parameters mapping
        Map<String, Map<String, Object>> presetParamsMapping = new HashMap<>();
        Map<String, Object> toolPresetParams = new HashMap<>();
        toolPresetParams.put("param3", "preset_value");
        toolPresetParams.put("param4", 42);
        presetParamsMapping.put("test-tool", toolPresetParams);

        // Execute registration
        manager.registerMcpClient(clientWrapper, null, null, null, presetParamsMapping).block();

        // Verify interactions
        verify(clientWrapper).initialize();
        verify(clientWrapper).listTools();
        assertTrue(callbackCalled[0]);
    }

    @Test
    void testRegisterMcpClient_WithPresetParametersKeySetExclusion() {
        // Create mocks
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolGroupManager groupManager = mock(ToolGroupManager.class);
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);

        // Create a manager with a tracking callback
        boolean[] callbackCalled = {false};
        McpClientManager manager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            callbackCalled[0] = true;
                            // Verify that presetParams is passed correctly
                            assertNotNull(presetParams);
                            assertTrue(presetParams.containsKey("units"));
                            assertEquals("celsius", presetParams.get("units"));
                        });

        // Setup client wrapper
        when(clientWrapper.getName()).thenReturn("test-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());

        // Create a mock MCP tool with schema that has some parameters
        McpSchema.Tool mockMcpTool = mock(McpSchema.Tool.class);
        when(mockMcpTool.name()).thenReturn("weather-tool");
        when(mockMcpTool.description()).thenReturn("Weather tool");

        // Create schema with multiple parameters
        Map<String, Object> properties = new HashMap<>();
        properties.put("city", Map.of("type", "string", "description", "City name"));
        properties.put("units", Map.of("type", "string", "description", "Temperature units"));
        properties.put("forecast_days", Map.of("type", "number", "description", "Number of days"));

        List<String> required = List.of("city");

        McpSchema.JsonSchema schema =
                new McpSchema.JsonSchema("object", properties, required, null, null, null);
        when(mockMcpTool.inputSchema()).thenReturn(schema);

        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(mockMcpTool)));

        // Define preset parameters that should be excluded from schema
        Map<String, Map<String, Object>> presetParamsMapping = new HashMap<>();
        Map<String, Object> toolPresetParams = new HashMap<>();
        toolPresetParams.put("units", "celsius"); // This should be excluded from schema
        toolPresetParams.put("forecast_days", 5); // This should be excluded from schema
        presetParamsMapping.put("weather-tool", toolPresetParams);

        // Execute registration - this should exercise the keySet exclusion logic
        manager.registerMcpClient(clientWrapper, null, null, null, presetParamsMapping).block();

        // Verify interactions occurred
        verify(clientWrapper).initialize();
        verify(clientWrapper).listTools();
        assertTrue(callbackCalled[0]);
    }

    @Test
    void testRegisterMcpClient_WithEmptyPresetParameters() {
        // Create mocks
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolGroupManager groupManager = mock(ToolGroupManager.class);
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);

        // Create a manager with a tracking callback
        boolean[] callbackCalled = {false};
        McpClientManager manager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            callbackCalled[0] = true;
                        });

        // Setup client wrapper
        when(clientWrapper.getName()).thenReturn("test-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());

        // Create a mock MCP tool
        McpSchema.Tool mockMcpTool = mock(McpSchema.Tool.class);
        when(mockMcpTool.name()).thenReturn("simple-tool");
        when(mockMcpTool.description()).thenReturn("Simple tool");

        when(mockMcpTool.inputSchema())
                .thenReturn(
                        new McpSchema.JsonSchema(
                                "object",
                                new HashMap<>(),
                                new java.util.ArrayList<>(),
                                null,
                                null,
                                null));

        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(mockMcpTool)));

        // Use empty preset parameters mapping
        Map<String, Map<String, Object>> presetParamsMapping = new HashMap<>();
        presetParamsMapping.put("simple-tool", new HashMap<>()); // Empty preset params

        // Execute registration
        manager.registerMcpClient(clientWrapper, null, null, null, presetParamsMapping).block();

        verify(clientWrapper).initialize();
        verify(clientWrapper).listTools();
        assert callbackCalled[0];
    }

    @Test
    void testRegisterMcpClient_WithNullPresetParametersForTool() {
        // Create mocks
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolGroupManager groupManager = mock(ToolGroupManager.class);
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);

        // Create a manager with a tracking callback
        boolean[] callbackCalled = {false};
        McpClientManager manager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            callbackCalled[0] = true;
                        });

        // Setup client wrapper
        when(clientWrapper.getName()).thenReturn("test-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());

        // Create a mock MCP tool
        McpSchema.Tool mockMcpTool = mock(McpSchema.Tool.class);
        when(mockMcpTool.name()).thenReturn("null-param-tool");
        when(mockMcpTool.description()).thenReturn("Tool with null preset params");

        when(mockMcpTool.inputSchema())
                .thenReturn(
                        new McpSchema.JsonSchema(
                                "object",
                                new HashMap<>(),
                                new java.util.ArrayList<>(),
                                null,
                                null,
                                null));

        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(mockMcpTool)));

        // Use null preset parameters mapping entirely
        // This exercises the null check in the presetParametersMapping != null condition
        manager.registerMcpClient(clientWrapper, null, null, null, null).block();

        verify(clientWrapper).initialize();
        verify(clientWrapper).listTools();
        assertTrue(callbackCalled[0]);
    }
}
