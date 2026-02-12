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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpSyncClientWrapperTest {

    private McpSyncClient mockClient;
    private McpSyncClientWrapper wrapper;

    @BeforeEach
    void setUp() {
        mockClient = mock(McpSyncClient.class);
        wrapper = new McpSyncClientWrapper("test-sync-client", mockClient);
    }

    @Test
    void testConstructor() {
        assertNotNull(wrapper);
        assertEquals("test-sync-client", wrapper.getName());
        assertFalse(wrapper.isInitialized());
    }

    @Test
    void testInitialize_Success() {
        // Mock initialization with correct constructors
        McpSchema.Implementation serverInfo =
                new McpSchema.Implementation("TestServer", "Test Server", "1.0.9-SNAPSHOT");
        McpSchema.InitializeResult initResult =
                new McpSchema.InitializeResult(
                        "1.0",
                        McpSchema.ServerCapabilities.builder().build(),
                        serverInfo,
                        null,
                        null);

        McpSchema.Tool tool1 =
                new McpSchema.Tool(
                        "tool1",
                        null,
                        "First tool",
                        new McpSchema.JsonSchema("object", null, null, null, null, null),
                        null,
                        null,
                        null);
        McpSchema.Tool tool2 =
                new McpSchema.Tool(
                        "tool2",
                        null,
                        "Second tool",
                        new McpSchema.JsonSchema("object", null, null, null, null, null),
                        null,
                        null,
                        null);
        McpSchema.ListToolsResult toolsResult =
                new McpSchema.ListToolsResult(List.of(tool1, tool2), null);

        when(mockClient.initialize()).thenReturn(initResult);
        when(mockClient.listTools()).thenReturn(toolsResult);

        // Execute
        wrapper.initialize().block();

        // Verify
        assertTrue(wrapper.isInitialized());
        assertEquals(2, wrapper.cachedTools.size());
        assertNotNull(wrapper.getCachedTool("tool1"));
        assertNotNull(wrapper.getCachedTool("tool2"));
    }

    @Test
    void testInitialize_AlreadyInitialized() {
        McpSchema.Implementation serverInfo =
                new McpSchema.Implementation("TestServer", "Test Server", "1.0.9-SNAPSHOT");
        McpSchema.InitializeResult initResult =
                new McpSchema.InitializeResult(
                        "1.0",
                        McpSchema.ServerCapabilities.builder().build(),
                        serverInfo,
                        null,
                        null);
        McpSchema.ListToolsResult toolsResult = new McpSchema.ListToolsResult(List.of(), null);

        when(mockClient.initialize()).thenReturn(initResult);
        when(mockClient.listTools()).thenReturn(toolsResult);

        wrapper.initialize().block();
        assertTrue(wrapper.isInitialized());

        // Second initialization should complete without calling client
        wrapper.initialize().block();

        verify(mockClient, times(1)).initialize();
        verify(mockClient, times(1)).listTools();
    }

    @Test
    void testInitialize_Failure() {
        // Mock initialization failure
        when(mockClient.initialize()).thenThrow(new RuntimeException("Connection failed"));

        // Execute and expect error
        Exception exception =
                assertThrows(RuntimeException.class, () -> wrapper.initialize().block());

        assertEquals("Connection failed", exception.getMessage());
        assertFalse(wrapper.isInitialized());
    }

    @Test
    void testListTools_Success() {
        setupSuccessfulInitialization();
        wrapper.initialize().block();

        McpSchema.Tool tool =
                new McpSchema.Tool(
                        "test-tool",
                        null,
                        "Test tool",
                        new McpSchema.JsonSchema("object", null, null, null, null, null),
                        null,
                        null,
                        null);
        McpSchema.ListToolsResult toolsResult = new McpSchema.ListToolsResult(List.of(tool), null);

        when(mockClient.listTools()).thenReturn(toolsResult);

        List<McpSchema.Tool> tools = wrapper.listTools().block();
        assertNotNull(tools);
        assertEquals(1, tools.size());
        assertEquals("test-tool", tools.get(0).name());
    }

    @Test
    void testListTools_NotInitialized() {
        Exception exception =
                assertThrows(IllegalStateException.class, () -> wrapper.listTools().block());

        assertTrue(exception.getMessage().contains("not initialized"));
    }

    @Test
    void testCallTool_Success() {
        setupSuccessfulInitialization();
        wrapper.initialize().block();

        Map<String, Object> args = new HashMap<>();
        args.put("param1", "value1");

        McpSchema.TextContent resultContent =
                new McpSchema.TextContent("Tool executed successfully");
        McpSchema.CallToolResult callResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(callResult);

        McpSchema.CallToolResult result = wrapper.callTool("test-tool", args).block();
        assertNotNull(result);
        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertEquals(1, result.content().size());
    }

    @Test
    void testCallTool_ReturnsError() {
        setupSuccessfulInitialization();
        wrapper.initialize().block();

        McpSchema.TextContent errorContent = new McpSchema.TextContent("Tool execution failed");
        McpSchema.CallToolResult callResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(errorContent))
                        .isError(true)
                        .build();

        when(mockClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(callResult);

        McpSchema.CallToolResult result = wrapper.callTool("test-tool", new HashMap<>()).block();
        assertNotNull(result);
        assertTrue(Boolean.TRUE.equals(result.isError()));
    }

    @Test
    void testCallTool_NotInitialized() {
        Exception exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> wrapper.callTool("test-tool", new HashMap<>()).block());

        assertTrue(exception.getMessage().contains("not initialized"));
    }

    @Test
    void testCallTool_ThrowsException() {
        setupSuccessfulInitialization();
        wrapper.initialize().block();

        when(mockClient.callTool(any(McpSchema.CallToolRequest.class)))
                .thenThrow(new RuntimeException("Network error"));

        Exception exception =
                assertThrows(
                        RuntimeException.class,
                        () -> wrapper.callTool("test-tool", new HashMap<>()).block());

        assertEquals("Network error", exception.getMessage());
    }

    @Test
    void testGetCachedTool() {
        setupSuccessfulInitialization();
        wrapper.initialize().block();

        McpSchema.Tool cached = wrapper.getCachedTool("tool1");
        assertNotNull(cached);
        assertEquals("tool1", cached.name());

        assertNull(wrapper.getCachedTool("non-existent"));
    }

    @Test
    void testClose_Success() {
        setupSuccessfulInitialization();
        wrapper.initialize().block();
        assertTrue(wrapper.isInitialized());
        assertFalse(wrapper.cachedTools.isEmpty());

        wrapper.close();

        assertFalse(wrapper.isInitialized());
        assertTrue(wrapper.cachedTools.isEmpty());
        verify(mockClient, times(1)).closeGracefully();
    }

    @Test
    void testClose_GracefulCloseFails() {
        setupSuccessfulInitialization();
        wrapper.initialize().block();

        doThrow(new RuntimeException("Close failed")).when(mockClient).closeGracefully();

        wrapper.close();

        assertFalse(wrapper.isInitialized());
        assertTrue(wrapper.cachedTools.isEmpty());
        verify(mockClient, times(1)).closeGracefully();
        verify(mockClient, times(1)).close();
    }

    @Test
    void testClose_MultipleCallsSafe() {
        setupSuccessfulInitialization();
        wrapper.initialize().block();

        wrapper.close();
        wrapper.close();
        wrapper.close();

        assertFalse(wrapper.isInitialized());
        assertTrue(wrapper.cachedTools.isEmpty());
    }

    @Test
    void testCallTool_WithNullArguments() {
        setupSuccessfulInitialization();
        wrapper.initialize().block();

        McpSchema.TextContent resultContent = new McpSchema.TextContent("Success");
        McpSchema.CallToolResult callResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(callResult);

        McpSchema.CallToolResult result = wrapper.callTool("test-tool", null).block();
        assertNotNull(result);
        assertFalse(Boolean.TRUE.equals(result.isError()));
    }

    // ==================== updateCachedTools Tests ====================

    @Test
    void testUpdateCachedTools_AddsNewTools() {
        // Initially cache is empty
        assertTrue(wrapper.cachedTools.isEmpty());

        // Create mock tools
        McpSchema.Tool tool1 =
                new McpSchema.Tool(
                        "new-tool-1",
                        null,
                        "New tool 1",
                        new McpSchema.JsonSchema("object", null, null, null, null, null),
                        null,
                        null,
                        null);
        McpSchema.Tool tool2 =
                new McpSchema.Tool(
                        "new-tool-2",
                        null,
                        "New tool 2",
                        new McpSchema.JsonSchema("object", null, null, null, null, null),
                        null,
                        null,
                        null);

        // Call updateCachedTools (package-private, use reflection)
        invokeUpdateCachedTools(List.of(tool1, tool2));

        // Verify cache was updated
        assertEquals(2, wrapper.cachedTools.size());
        assertNotNull(wrapper.getCachedTool("new-tool-1"));
        assertNotNull(wrapper.getCachedTool("new-tool-2"));
        assertEquals("New tool 1", wrapper.getCachedTool("new-tool-1").description());
        assertEquals("New tool 2", wrapper.getCachedTool("new-tool-2").description());
    }

    @Test
    void testUpdateCachedTools_EmptyListClearsCache() {
        // First add some tools to cache
        setupSuccessfulInitialization();
        wrapper.initialize().block();
        assertEquals(1, wrapper.cachedTools.size());

        // Then simulate server sending empty tool list (all tools removed)
        invokeUpdateCachedTools(List.of());

        // Verify cache was cleared
        assertTrue(wrapper.cachedTools.isEmpty());
        assertNull(wrapper.getCachedTool("tool1"));
    }

    @Test
    void testUpdateCachedTools_NullDoesNothing() {
        // Add some tools to cache first
        setupSuccessfulInitialization();
        wrapper.initialize().block();
        int initialSize = wrapper.cachedTools.size();

        // Call with null should not modify cache
        invokeUpdateCachedTools(null);

        // Verify cache unchanged
        assertEquals(initialSize, wrapper.cachedTools.size());
        assertNotNull(wrapper.getCachedTool("tool1"));
    }

    @Test
    void testUpdateCachedTools_ReplacesExistingTools() {
        // Initialize with existing tools
        setupSuccessfulInitialization();
        wrapper.initialize().block();
        assertEquals(1, wrapper.cachedTools.size());

        // Simulate server sending updated tool list
        McpSchema.Tool updatedTool =
                new McpSchema.Tool(
                        "tool1",
                        null,
                        "Updated description",
                        new McpSchema.JsonSchema("string", null, null, null, null, null),
                        null,
                        null,
                        null);
        McpSchema.Tool newTool =
                new McpSchema.Tool(
                        "new-tool",
                        null,
                        "Brand new tool",
                        new McpSchema.JsonSchema("object", null, null, null, null, null),
                        null,
                        null,
                        null);

        invokeUpdateCachedTools(List.of(updatedTool, newTool));

        // Verify cache was updated (not appended)
        assertEquals(2, wrapper.cachedTools.size());
        assertNotNull(wrapper.getCachedTool("tool1"));
        assertEquals("Updated description", wrapper.getCachedTool("tool1").description());
        assertNotNull(wrapper.getCachedTool("new-tool"));
        assertEquals("Brand new tool", wrapper.getCachedTool("new-tool").description());
    }

    @Test
    void testUpdateCachedTools_MultipleTimes() {
        // First update
        McpSchema.Tool tool1 =
                new McpSchema.Tool(
                        "tool-a",
                        null,
                        "Tool A",
                        new McpSchema.JsonSchema("object", null, null, null, null, null),
                        null,
                        null,
                        null);
        invokeUpdateCachedTools(List.of(tool1));
        assertEquals(1, wrapper.cachedTools.size());

        // Second update - replace
        McpSchema.Tool tool2 =
                new McpSchema.Tool(
                        "tool-b",
                        null,
                        "Tool B",
                        new McpSchema.JsonSchema("object", null, null, null, null, null),
                        null,
                        null,
                        null);
        invokeUpdateCachedTools(List.of(tool2));

        // Verify only latest tools exist
        assertEquals(1, wrapper.cachedTools.size());
        assertNull(wrapper.getCachedTool("tool-a"));
        assertNotNull(wrapper.getCachedTool("tool-b"));
    }

    // ==================== close() Idempotency Tests ====================

    @Test
    void testClose_Idempotent() {
        setupSuccessfulInitialization();
        wrapper.initialize().block();
        assertTrue(wrapper.isInitialized());

        // Close multiple times
        wrapper.close();
        wrapper.close();
        wrapper.close();

        // Verify closeGracefully was called only once
        verify(mockClient, times(1)).closeGracefully();
        assertFalse(wrapper.isInitialized());
    }

    @Test
    void testClose_WithoutInitialize() {
        // Close without ever initializing
        wrapper.close();

        // Should not throw, client should be nullified
        assertFalse(wrapper.isInitialized());
        assertTrue(wrapper.cachedTools.isEmpty());
    }

    // ==================== Null Client Error Path Tests ====================

    // Removed: client is now final and cannot be null after construction

    // ==================== Helper Methods ====================

    /**
     * Invokes the package-private updateCachedTools method using reflection.
     *
     * @param tools the list of tools to update
     */
    private void invokeUpdateCachedTools(List<McpSchema.Tool> tools) {
        try {
            java.lang.reflect.Method method =
                    McpSyncClientWrapper.class.getDeclaredMethod("updateCachedTools", List.class);
            method.setAccessible(true);
            method.invoke(wrapper, tools);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke updateCachedTools", e);
        }
    }

    private void setupSuccessfulInitialization() {
        McpSchema.Implementation serverInfo =
                new McpSchema.Implementation("TestServer", "Test Server", "1.0.9-SNAPSHOT");
        McpSchema.InitializeResult initResult =
                new McpSchema.InitializeResult(
                        "1.0",
                        McpSchema.ServerCapabilities.builder().build(),
                        serverInfo,
                        null,
                        null);

        McpSchema.Tool tool1 =
                new McpSchema.Tool(
                        "tool1",
                        null,
                        "First tool",
                        new McpSchema.JsonSchema("object", null, null, null, null, null),
                        null,
                        null,
                        null);
        McpSchema.ListToolsResult toolsResult = new McpSchema.ListToolsResult(List.of(tool1), null);

        when(mockClient.initialize()).thenReturn(initResult);
        when(mockClient.listTools()).thenReturn(toolsResult);
    }
}
