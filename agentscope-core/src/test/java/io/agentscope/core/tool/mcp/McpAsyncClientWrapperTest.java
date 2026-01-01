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
                new McpSchema.Implementation("TestServer", "Test Server", "1.0.6-SNAPSHOT");
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
                new McpSchema.Implementation("TestServer", "Test Server", "1.0.6-SNAPSHOT");
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

    private void setupSuccessfulInitialization() {
        McpSchema.Implementation serverInfo =
                new McpSchema.Implementation("TestServer", "Test Server", "1.0.6-SNAPSHOT");
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
