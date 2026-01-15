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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class McpAsyncClientWrapperTest {

    private McpAsyncClient mockClient;
    private McpAsyncClientWrapper wrapper;

    @BeforeEach
    void setUp() {
        mockClient = mock(McpAsyncClient.class);
        wrapper = new McpAsyncClientWrapper("test-async-client", mockClient);
    }

    @Test
    void testConstructor() {
        assertNotNull(wrapper);
        assertEquals("test-async-client", wrapper.getName());
        assertFalse(wrapper.isInitialized());
    }

    @Test
    void testInitialize_Success() {
        // Mock initialization
        McpSchema.Implementation serverInfo =
                new McpSchema.Implementation("TestServer", "Test Server", "1.0.8-SNAPSHOT");
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

        when(mockClient.initialize()).thenReturn(Mono.just(initResult));
        when(mockClient.listTools()).thenReturn(Mono.just(toolsResult));

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
                new McpSchema.Implementation("TestServer", "Test Server", "1.0.8-SNAPSHOT");
        McpSchema.InitializeResult initResult =
                new McpSchema.InitializeResult(
                        "1.0",
                        McpSchema.ServerCapabilities.builder().build(),
                        serverInfo,
                        null,
                        null);
        McpSchema.ListToolsResult toolsResult = new McpSchema.ListToolsResult(List.of(), null);

        when(mockClient.initialize()).thenReturn(Mono.just(initResult));
        when(mockClient.listTools()).thenReturn(Mono.just(toolsResult));

        wrapper.initialize().block();
        assertTrue(wrapper.isInitialized());

        // Second initialization should complete without calling client
        wrapper.initialize().block();

        verify(mockClient, times(1)).initialize();
        verify(mockClient, times(1)).listTools();
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

        when(mockClient.listTools()).thenReturn(Mono.just(toolsResult));

        List<McpSchema.Tool> tools = wrapper.listTools().block();
        assertNotNull(tools);
        assertEquals(1, tools.size());
        assertEquals("test-tool", tools.get(0).name());
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

        when(mockClient.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(Mono.just(callResult));

        McpSchema.CallToolResult result = wrapper.callTool("test-tool", args).block();
        assertNotNull(result);
        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertEquals(1, result.content().size());
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

        when(mockClient.closeGracefully()).thenReturn(Mono.empty());

        wrapper.close();

        assertFalse(wrapper.isInitialized());
        assertTrue(wrapper.cachedTools.isEmpty());
        verify(mockClient, times(1)).closeGracefully();
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

    // ==================== setClient Tests ====================

    @Test
    void testSetClient_ReplacesClient() {
        // Wrapper created with original client
        assertNotNull(wrapper);
        assertEquals("test-async-client", wrapper.getName());

        // Create a new client
        McpAsyncClient newClient = mock(McpAsyncClient.class);
        assertNotSame(mockClient, newClient);

        // Replace client using reflection
        invokeSetClient(newClient);

        // Verify by testing initialization with new client
        McpSchema.Implementation serverInfo =
                new McpSchema.Implementation("NewServer", "New Server", "1.0");
        McpSchema.InitializeResult initResult =
                new McpSchema.InitializeResult(
                        "1.0",
                        McpSchema.ServerCapabilities.builder().build(),
                        serverInfo,
                        null,
                        null);

        when(newClient.initialize()).thenReturn(Mono.just(initResult));
        when(newClient.listTools())
                .thenReturn(Mono.just(new McpSchema.ListToolsResult(List.of(), null)));

        wrapper.initialize().block();

        // Verify new client was used
        verify(newClient, times(1)).initialize();
        verify(mockClient, times(0)).initialize(); // Original client never called
    }

    // ==================== close() Idempotency Tests ====================

    @Test
    void testClose_Idempotent() {
        setupSuccessfulInitialization();
        wrapper.initialize().block();
        assertTrue(wrapper.isInitialized());

        when(mockClient.closeGracefully()).thenReturn(Mono.empty());

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
        when(mockClient.closeGracefully()).thenReturn(Mono.empty());

        wrapper.close();

        // Should not throw, client should be nullified
        assertFalse(wrapper.isInitialized());
        assertTrue(wrapper.cachedTools.isEmpty());
    }

    @Test
    void testClose_AfterSetClientToNull() {
        // First initialize normally
        setupSuccessfulInitialization();
        wrapper.initialize().block();
        assertTrue(wrapper.isInitialized());

        // Manually set client to null via reflection
        invokeSetClient(null);

        // Close should handle gracefully
        wrapper.close();

        assertFalse(wrapper.isInitialized());
        assertTrue(wrapper.cachedTools.isEmpty());
    }

    // ==================== Helper Methods ====================

    /**
     * Invokes the package-private updateCachedTools method using reflection.
     *
     * @param tools the list of tools to update
     */
    private void invokeUpdateCachedTools(List<McpSchema.Tool> tools) {
        try {
            java.lang.reflect.Method method =
                    McpAsyncClientWrapper.class.getDeclaredMethod("updateCachedTools", List.class);
            method.setAccessible(true);
            method.invoke(wrapper, tools);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke updateCachedTools", e);
        }
    }

    /**
     * Invokes the package-private setClient method using reflection.
     *
     * @param client the MCP async client to set
     */
    private void invokeSetClient(McpAsyncClient client) {
        try {
            java.lang.reflect.Method method =
                    McpAsyncClientWrapper.class.getDeclaredMethod(
                            "setClient", McpAsyncClient.class);
            method.setAccessible(true);
            method.invoke(wrapper, client);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke setClient", e);
        }
    }

    private void setupSuccessfulInitialization() {
        McpSchema.Implementation serverInfo =
                new McpSchema.Implementation("TestServer", "Test Server", "1.0.8-SNAPSHOT");
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

        when(mockClient.initialize()).thenReturn(Mono.just(initResult));
        when(mockClient.listTools()).thenReturn(Mono.just(toolsResult));
    }
}
