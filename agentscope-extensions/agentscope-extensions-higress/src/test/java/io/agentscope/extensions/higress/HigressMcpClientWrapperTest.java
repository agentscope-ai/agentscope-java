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
package io.agentscope.extensions.higress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class HigressMcpClientWrapperTest {

    private McpClientWrapper mockDelegateClient;

    @BeforeEach
    void setUp() {
        mockDelegateClient = mock(McpClientWrapper.class);
    }

    private HigressMcpClientWrapper createWrapper(
            boolean enableToolSearch, String query, int topK) {
        return new HigressMcpClientWrapper(
                "test-client", mockDelegateClient, enableToolSearch, query, topK);
    }

    @Test
    void testGetName() {
        HigressMcpClientWrapper wrapper = createWrapper(false, null, 10);
        assertEquals("test-client", wrapper.getName());
    }

    @Test
    void testIsToolSearchEnabled_True() {
        HigressMcpClientWrapper wrapper = createWrapper(true, "query", 10);
        assertTrue(wrapper.isToolSearchEnabled());
    }

    @Test
    void testIsToolSearchEnabled_False() {
        HigressMcpClientWrapper wrapper = createWrapper(false, null, 10);
        assertFalse(wrapper.isToolSearchEnabled());
    }

    @Test
    void testToolSearchName_Constant() {
        assertEquals("x_higress_tool_search", HigressMcpClientWrapper.TOOL_SEARCH_NAME);
    }

    @Test
    void testInitialize_Success() {
        when(mockDelegateClient.initialize()).thenReturn(Mono.empty());

        HigressMcpClientWrapper wrapper = createWrapper(false, null, 10);
        assertFalse(wrapper.isInitialized());

        wrapper.initialize().block();

        assertTrue(wrapper.isInitialized());
        verify(mockDelegateClient, times(1)).initialize();
    }

    @Test
    void testInitialize_AlreadyInitialized() {
        when(mockDelegateClient.initialize()).thenReturn(Mono.empty());

        HigressMcpClientWrapper wrapper = createWrapper(false, null, 10);
        wrapper.initialize().block();

        // Call initialize again
        wrapper.initialize().block();

        // Should only call delegate once
        verify(mockDelegateClient, times(1)).initialize();
    }

    @Test
    void testInitialize_Error() {
        RuntimeException error = new RuntimeException("Connection failed");
        when(mockDelegateClient.initialize()).thenReturn(Mono.error(error));

        HigressMcpClientWrapper wrapper = createWrapper(false, null, 10);

        assertThrows(RuntimeException.class, () -> wrapper.initialize().block());
        assertFalse(wrapper.isInitialized());
    }

    @Test
    void testListTools_WithoutToolSearch() {
        McpSchema.Tool tool1 =
                new McpSchema.Tool("tool1", "Title 1", "Desc 1", null, null, null, null);
        McpSchema.Tool tool2 =
                new McpSchema.Tool("tool2", "Title 2", "Desc 2", null, null, null, null);
        List<McpSchema.Tool> tools = List.of(tool1, tool2);

        when(mockDelegateClient.listTools()).thenReturn(Mono.just(tools));

        HigressMcpClientWrapper wrapper = createWrapper(false, null, 10);
        List<McpSchema.Tool> result = wrapper.listTools().block();

        assertNotNull(result);
        assertEquals(2, result.size());
        verify(mockDelegateClient, times(1)).listTools();
        verify(mockDelegateClient, never()).callTool(anyString(), anyMap());
    }

    @Test
    void testListTools_WithToolSearch() {
        // Mock the tool search response
        Map<String, Object> tool = new HashMap<>();
        tool.put("name", "searched_tool");
        tool.put("description", "Searched description");
        tool.put("title", "Searched Title");
        tool.put(
                "inputSchema",
                Map.of(
                        "type", "object",
                        "properties", Map.of("param", Map.of("type", "string")),
                        "required", List.of("param")));

        Map<String, Object> structuredContent = Map.of("tools", List.of(tool));
        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(Collections.emptyList(), false, structuredContent);

        when(mockDelegateClient.callTool(eq("x_higress_tool_search"), any()))
                .thenReturn(Mono.just(callToolResult));

        HigressMcpClientWrapper wrapper = createWrapper(true, "test query", 5);
        List<McpSchema.Tool> result = wrapper.listTools().block();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("searched_tool", result.get(0).name());

        verify(mockDelegateClient, times(1)).callTool(eq("x_higress_tool_search"), any());
        verify(mockDelegateClient, never()).listTools();
    }

    @Test
    void testListTools_WithToolSearch_Error() {
        McpSchema.CallToolResult errorResult =
                new McpSchema.CallToolResult(Collections.emptyList(), true, null);

        when(mockDelegateClient.callTool(eq("x_higress_tool_search"), any()))
                .thenReturn(Mono.just(errorResult));

        HigressMcpClientWrapper wrapper = createWrapper(true, "test query", 5);

        assertThrows(RuntimeException.class, () -> wrapper.listTools().block());
    }

    @Test
    void testCallTool_Success() {
        Map<String, Object> args = Map.of("param1", "value1");
        McpSchema.TextContent content = new McpSchema.TextContent(null, null, "result");
        McpSchema.CallToolResult expectedResult =
                new McpSchema.CallToolResult(List.of(content), false, null);

        when(mockDelegateClient.callTool(eq("my_tool"), eq(args)))
                .thenReturn(Mono.just(expectedResult));

        HigressMcpClientWrapper wrapper = createWrapper(false, null, 10);
        McpSchema.CallToolResult result = wrapper.callTool("my_tool", args).block();

        assertNotNull(result);
        assertFalse(result.isError());
        verify(mockDelegateClient, times(1)).callTool("my_tool", args);
    }

    @Test
    void testCallTool_Error() {
        Map<String, Object> args = Map.of("param1", "value1");
        McpSchema.CallToolResult errorResult =
                new McpSchema.CallToolResult(Collections.emptyList(), true, null);

        when(mockDelegateClient.callTool(eq("my_tool"), eq(args)))
                .thenReturn(Mono.just(errorResult));

        HigressMcpClientWrapper wrapper = createWrapper(false, null, 10);
        McpSchema.CallToolResult result = wrapper.callTool("my_tool", args).block();

        assertNotNull(result);
        assertTrue(result.isError());
    }

    @Test
    void testCallTool_Exception() {
        RuntimeException error = new RuntimeException("Tool call failed");
        when(mockDelegateClient.callTool(anyString(), any())).thenReturn(Mono.error(error));

        HigressMcpClientWrapper wrapper = createWrapper(false, null, 10);

        assertThrows(
                RuntimeException.class,
                () -> wrapper.callTool("my_tool", Collections.emptyMap()).block());
    }

    @Test
    void testSearchTools_WithQuery() {
        Map<String, Object> tool = Map.of("name", "tool1", "description", "Desc", "title", "Title");
        Map<String, Object> structuredContent = Map.of("tools", List.of(tool));
        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(Collections.emptyList(), false, structuredContent);

        when(mockDelegateClient.callTool(eq("x_higress_tool_search"), any()))
                .thenReturn(Mono.just(callToolResult));

        HigressMcpClientWrapper wrapper = createWrapper(false, null, 10);
        HigressToolSearchResult result = wrapper.searchTools("test query").block();

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(1, result.getTools().size());
    }

    @Test
    void testSearchTools_WithQueryAndTopK() {
        Map<String, Object> tool = Map.of("name", "tool1", "description", "Desc", "title", "Title");
        Map<String, Object> structuredContent = Map.of("tools", List.of(tool));
        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(Collections.emptyList(), false, structuredContent);

        when(mockDelegateClient.callTool(eq("x_higress_tool_search"), any()))
                .thenAnswer(
                        invocation -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> args = invocation.getArgument(1);
                            assertEquals("test query", args.get("query"));
                            assertEquals(3, args.get("topK"));
                            return Mono.just(callToolResult);
                        });

        HigressMcpClientWrapper wrapper = createWrapper(false, null, 10);
        HigressToolSearchResult result = wrapper.searchTools("test query", 3).block();

        assertNotNull(result);
        assertTrue(result.isSuccess());
    }

    @Test
    void testClose() {
        when(mockDelegateClient.initialize()).thenReturn(Mono.empty());

        HigressMcpClientWrapper wrapper = createWrapper(false, null, 10);
        wrapper.initialize().block();
        assertTrue(wrapper.isInitialized());

        wrapper.close();

        assertFalse(wrapper.isInitialized());
        verify(mockDelegateClient, times(1)).close();
    }

    @Test
    void testClose_WithError() {
        when(mockDelegateClient.initialize()).thenReturn(Mono.empty());
        RuntimeException error = new RuntimeException("Close failed");
        // Mockito will throw the error when close() is called
        org.mockito.Mockito.doThrow(error).when(mockDelegateClient).close();

        HigressMcpClientWrapper wrapper = createWrapper(false, null, 10);
        wrapper.initialize().block();

        // Should not throw, error is logged
        wrapper.close();
        assertFalse(wrapper.isInitialized());
    }

    @Test
    void testListTools_WithToolSearch_CachesTools() {
        Map<String, Object> tool =
                Map.of(
                        "name", "cached_tool",
                        "description", "Cached",
                        "title", "Cached Tool",
                        "inputSchema", Map.of("type", "object"));
        Map<String, Object> structuredContent = Map.of("tools", List.of(tool));
        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(Collections.emptyList(), false, structuredContent);

        when(mockDelegateClient.callTool(eq("x_higress_tool_search"), any()))
                .thenReturn(Mono.just(callToolResult));

        HigressMcpClientWrapper wrapper = createWrapper(true, "query", 5);
        List<McpSchema.Tool> result = wrapper.listTools().block();

        assertNotNull(result);
        assertEquals(1, result.size());

        // Check that tool is cached
        McpSchema.Tool cachedTool = wrapper.getCachedTool("cached_tool");
        assertNotNull(cachedTool);
        assertEquals("cached_tool", cachedTool.name());
    }

    @Test
    void testListTools_WithoutToolSearch_CachesTools() {
        McpSchema.Tool tool =
                new McpSchema.Tool("delegate_tool", "Title", "Desc", null, null, null, null);
        when(mockDelegateClient.listTools()).thenReturn(Mono.just(List.of(tool)));

        HigressMcpClientWrapper wrapper = createWrapper(false, null, 10);
        wrapper.listTools().block();

        McpSchema.Tool cachedTool = wrapper.getCachedTool("delegate_tool");
        assertNotNull(cachedTool);
    }

    @Test
    void testConvertToMcpTool_WithInputSchema() {
        Map<String, Object> inputSchema = new HashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put(
                "properties",
                Map.of(
                        "city", Map.of("type", "string", "description", "City name"),
                        "unit", Map.of("type", "string")));
        inputSchema.put("required", List.of("city"));

        Map<String, Object> tool = new HashMap<>();
        tool.put("name", "weather_tool");
        tool.put("description", "Get weather info");
        tool.put("title", "Weather");
        tool.put("inputSchema", inputSchema);

        Map<String, Object> structuredContent = Map.of("tools", List.of(tool));
        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(Collections.emptyList(), false, structuredContent);

        when(mockDelegateClient.callTool(eq("x_higress_tool_search"), any()))
                .thenReturn(Mono.just(callToolResult));

        HigressMcpClientWrapper wrapper = createWrapper(true, "weather", 5);
        List<McpSchema.Tool> result = wrapper.listTools().block();

        assertNotNull(result);
        assertEquals(1, result.size());

        McpSchema.Tool mcpTool = result.get(0);
        assertEquals("weather_tool", mcpTool.name());
        assertEquals("Weather", mcpTool.title());
        assertEquals("Get weather info", mcpTool.description());
        assertNotNull(mcpTool.inputSchema());
        assertEquals("object", mcpTool.inputSchema().type());
        assertEquals(List.of("city"), mcpTool.inputSchema().required());
    }

    @Test
    void testConvertToMcpTool_WithoutInputSchema() {
        Map<String, Object> tool = new HashMap<>();
        tool.put("name", "simple_tool");
        tool.put("description", "Simple tool");
        tool.put("title", "Simple");
        // No inputSchema

        Map<String, Object> structuredContent = Map.of("tools", List.of(tool));
        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(Collections.emptyList(), false, structuredContent);

        when(mockDelegateClient.callTool(eq("x_higress_tool_search"), any()))
                .thenReturn(Mono.just(callToolResult));

        HigressMcpClientWrapper wrapper = createWrapper(true, "simple", 5);
        List<McpSchema.Tool> result = wrapper.listTools().block();

        assertNotNull(result);
        McpSchema.Tool mcpTool = result.get(0);
        assertEquals("simple_tool", mcpTool.name());
        // inputSchema should be null when not provided
    }

    @Test
    void testConvertToMcpTool_WithEmptyProperties() {
        Map<String, Object> inputSchema = new HashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", Collections.emptyMap());
        // No required field

        Map<String, Object> tool = new HashMap<>();
        tool.put("name", "no_params_tool");
        tool.put("description", "No params");
        tool.put("title", "No Params");
        tool.put("inputSchema", inputSchema);

        Map<String, Object> structuredContent = Map.of("tools", List.of(tool));
        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(Collections.emptyList(), false, structuredContent);

        when(mockDelegateClient.callTool(eq("x_higress_tool_search"), any()))
                .thenReturn(Mono.just(callToolResult));

        HigressMcpClientWrapper wrapper = createWrapper(true, "no params", 5);
        List<McpSchema.Tool> result = wrapper.listTools().block();

        assertNotNull(result);
        McpSchema.Tool mcpTool = result.get(0);
        assertNotNull(mcpTool.inputSchema());
        assertTrue(mcpTool.inputSchema().properties().isEmpty());
    }
}
