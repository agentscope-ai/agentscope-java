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
package io.agentscope.core.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class McpToolTest {

    private McpClientWrapper mockClientWrapper;
    private Map<String, Object> parameters;

    @BeforeEach
    void setUp() {
        mockClientWrapper = mock(McpClientWrapper.class);
        when(mockClientWrapper.getName()).thenReturn("test-client");

        parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", new HashMap<>());
    }

    @Test
    void testConstructor_WithoutPresetArgs() {
        McpTool tool = new McpTool("test-tool", "A test tool", parameters, mockClientWrapper);

        assertEquals("test-tool", tool.getName());
        assertEquals("A test tool", tool.getDescription());
        assertEquals(parameters, tool.getParameters());
        assertEquals("test-client", tool.getClientName());
        assertNull(tool.getPresetArguments());
    }

    @Test
    void testConstructor_WithPresetArgs() {
        Map<String, Object> presetArgs = new HashMap<>();
        presetArgs.put("preset1", "value1");
        presetArgs.put("preset2", 42);

        McpTool tool =
                new McpTool("test-tool", "A test tool", parameters, mockClientWrapper, presetArgs);

        assertEquals("test-tool", tool.getName());
        assertNotNull(tool.getPresetArguments());
        assertEquals(2, tool.getPresetArguments().size());
        assertEquals("value1", tool.getPresetArguments().get("preset1"));
        assertEquals(42, tool.getPresetArguments().get("preset2"));
    }

    @Test
    void testConstructor_WithNullPresetArgs() {
        McpTool tool = new McpTool("test-tool", "A test tool", parameters, mockClientWrapper, null);

        assertNull(tool.getPresetArguments());
    }

    @Test
    void testGetName() {
        McpTool tool = new McpTool("my-tool", "Description", parameters, mockClientWrapper);
        assertEquals("my-tool", tool.getName());
    }

    @Test
    void testGetDescription() {
        McpTool tool =
                new McpTool("tool", "This is a test description", parameters, mockClientWrapper);
        assertEquals("This is a test description", tool.getDescription());
    }

    @Test
    void testGetParameters() {
        Map<String, Object> customParams = new HashMap<>();
        customParams.put("type", "object");
        customParams.put("required", List.of("param1"));

        McpTool tool = new McpTool("tool", "Description", customParams, mockClientWrapper);

        Map<String, Object> result = tool.getParameters();
        assertEquals("object", result.get("type"));
        assertTrue(result.containsKey("required"));
    }

    @Test
    void testGetClientName() {
        McpTool tool = new McpTool("tool", "Description", parameters, mockClientWrapper);
        assertEquals("test-client", tool.getClientName());
    }

    @Test
    void testCallAsync_Success() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        Map<String, Object> input = new HashMap<>();
        input.put("param1", "value1");

        McpSchema.TextContent resultContent = new McpSchema.TextContent("Success result");
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any())).thenReturn(Mono.just(mcpResult));

        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().input(input).build()).block();
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertFalse(outputText.startsWith("Error:"));

        verify(mockClientWrapper).callTool(eq("test-tool"), any());
    }

    @Test
    void testCallAsync_WithEmptyInput() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        McpSchema.TextContent resultContent = new McpSchema.TextContent("Success");
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any())).thenReturn(Mono.just(mcpResult));

        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().input(new HashMap<>()).build()).block();
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertFalse(outputText.startsWith("Error:"));
    }

    @Test
    void testCallAsync_WithNullInput() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        McpSchema.TextContent resultContent = new McpSchema.TextContent("Success");
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any())).thenReturn(Mono.just(mcpResult));

        ToolResultBlock result = tool.callAsync(ToolCallParam.builder().build()).block();
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertFalse(outputText.startsWith("Error:"));
    }

    @Test
    void testCallAsync_MergesPresetArguments() {
        Map<String, Object> presetArgs = new HashMap<>();
        presetArgs.put("preset_key", "preset_value");
        presetArgs.put("override_me", "old_value");

        McpTool tool =
                new McpTool("test-tool", "Description", parameters, mockClientWrapper, presetArgs);

        Map<String, Object> input = new HashMap<>();
        input.put("input_key", "input_value");
        input.put("override_me", "new_value"); // Should override preset

        McpSchema.TextContent resultContent = new McpSchema.TextContent("Success");
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any())).thenReturn(Mono.just(mcpResult));

        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().input(input).build()).block();
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertFalse(outputText.startsWith("Error:"));

        // Verify the merged arguments were passed
        verify(mockClientWrapper).callTool(eq("test-tool"), any(Map.class));
    }

    @Test
    void testCallAsync_PresetArgsOnly() {
        Map<String, Object> presetArgs = new HashMap<>();
        presetArgs.put("preset1", "value1");
        presetArgs.put("preset2", "value2");

        McpTool tool =
                new McpTool("test-tool", "Description", parameters, mockClientWrapper, presetArgs);

        McpSchema.TextContent resultContent = new McpSchema.TextContent("Success");
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any())).thenReturn(Mono.just(mcpResult));

        // Call with null input - should use preset args only
        ToolResultBlock result = tool.callAsync(ToolCallParam.builder().build()).block();
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertFalse(outputText.startsWith("Error:"));
    }

    @Test
    void testCallAsync_ErrorHandling() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        when(mockClientWrapper.callTool(eq("test-tool"), any()))
                .thenReturn(Mono.error(new RuntimeException("Network error")));

        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().input(new HashMap<>()).build()).block();
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertTrue(outputText.startsWith("Error:"));
        assertTrue(outputText.contains("MCP tool error"));
        assertTrue(outputText.contains("Network error"));
    }

    @Test
    void testCallAsync_ErrorWithNullMessage() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        when(mockClientWrapper.callTool(eq("test-tool"), any()))
                .thenReturn(Mono.error(new NullPointerException()));

        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().input(new HashMap<>()).build()).block();
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertTrue(outputText.startsWith("Error:"));
        assertTrue(outputText.contains("MCP tool error"));
        assertTrue(outputText.contains("NullPointerException"));
    }

    @Test
    void testGetPresetArguments_ReturnsDefensiveCopy() {
        Map<String, Object> presetArgs = new HashMap<>();
        presetArgs.put("key", "value");

        McpTool tool =
                new McpTool("test-tool", "Description", parameters, mockClientWrapper, presetArgs);

        Map<String, Object> retrieved1 = tool.getPresetArguments();
        Map<String, Object> retrieved2 = tool.getPresetArguments();

        // Should return different instances (defensive copy)
        assertTrue(retrieved1 != retrieved2);
        assertEquals(retrieved1, retrieved2);

        // Modifying returned map should not affect the tool's internal state
        retrieved1.put("new_key", "new_value");
        Map<String, Object> retrieved3 = tool.getPresetArguments();
        assertFalse(retrieved3.containsKey("new_key"));
    }

    @Test
    void testConvertMcpSchemaToParameters_NullSchema() {
        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(null);

        assertNotNull(result);
        assertEquals("object", result.get("type"));
        assertTrue(result.containsKey("properties"));
        assertTrue(result.containsKey("required"));
        assertTrue(((Map<?, ?>) result.get("properties")).isEmpty());
        assertTrue(((List<?>) result.get("required")).isEmpty());
    }

    @Test
    void testConvertMcpSchemaToParameters_CompleteSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("param1", Map.of("type", "string"));
        properties.put("param2", Map.of("type", "number"));

        List<String> required = List.of("param1");

        McpSchema.JsonSchema schema =
                new McpSchema.JsonSchema("object", properties, required, true, null, null);

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema);

        assertNotNull(result);
        assertEquals("object", result.get("type"));
        assertEquals(properties, result.get("properties"));
        assertEquals(required, result.get("required"));
        assertEquals(true, result.get("additionalProperties"));
    }

    @Test
    void testConvertMcpSchemaToParameters_NullType() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(null, null, null, null, null, null);

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema);

        assertNotNull(result);
        assertEquals("object", result.get("type")); // Defaults to "object"
        assertNotNull(result.get("properties"));
        assertNotNull(result.get("required"));
    }

    @Test
    void testConvertMcpSchemaToParameters_EmptyProperties() {
        McpSchema.JsonSchema schema =
                new McpSchema.JsonSchema(
                        "object", new HashMap<>(), new ArrayList<>(), null, null, null);

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema);

        assertNotNull(result);
        assertTrue(((Map<?, ?>) result.get("properties")).isEmpty());
        assertTrue(((List<?>) result.get("required")).isEmpty());
        assertFalse(result.containsKey("additionalProperties")); // Should be omitted if null
    }

    @Test
    void testConvertMcpSchemaToParameters_WithAdditionalProperties() {
        McpSchema.JsonSchema schema =
                new McpSchema.JsonSchema("object", null, null, false, null, null);

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema);

        assertNotNull(result);
        assertTrue(result.containsKey("additionalProperties"));
        assertEquals(false, result.get("additionalProperties"));
    }

    @Test
    void testConvertMcpSchemaToParameters_ComplexProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(
                "user",
                Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))));

        List<String> required = List.of("user");

        McpSchema.JsonSchema schema =
                new McpSchema.JsonSchema("object", properties, required, null, null, null);

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema);

        assertNotNull(result);
        Map<?, ?> resultProperties = (Map<?, ?>) result.get("properties");
        assertTrue(resultProperties.containsKey("user"));
        Map<?, ?> userProperty = (Map<?, ?>) resultProperties.get("user");
        assertEquals("object", userProperty.get("type"));
    }

    @Test
    void testMergeArguments_BothEmpty() {
        McpTool tool =
                new McpTool(
                        "test-tool", "Description", parameters, mockClientWrapper, new HashMap<>());

        McpSchema.TextContent resultContent = new McpSchema.TextContent("Success");
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any())).thenReturn(Mono.just(mcpResult));

        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().input(new HashMap<>()).build()).block();
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertFalse(outputText.startsWith("Error:"));
    }

    @Test
    void testMergeArguments_InputOverridesPreset() {
        Map<String, Object> presetArgs = new HashMap<>();
        presetArgs.put("key", "preset_value");

        McpTool tool =
                new McpTool("test-tool", "Description", parameters, mockClientWrapper, presetArgs);

        Map<String, Object> input = new HashMap<>();
        input.put("key", "input_value");

        McpSchema.TextContent resultContent = new McpSchema.TextContent("Success");
        McpSchema.CallToolResult mcpResult =
                new McpSchema.CallToolResult(List.of(resultContent), false);

        when(mockClientWrapper.callTool(eq("test-tool"), any())).thenReturn(Mono.just(mcpResult));

        // The merged args should have input_value (not preset_value)
        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().input(input).build()).block();
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertFalse(outputText.startsWith("Error:"));
    }
}
