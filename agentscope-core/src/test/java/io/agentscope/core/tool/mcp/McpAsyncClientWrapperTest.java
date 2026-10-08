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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.Version;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
                new McpSchema.Implementation("TestServer", "Test Server", Version.VERSION);
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
                new McpSchema.Implementation("TestServer", "Test Server", Version.VERSION);
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
        // A successful graceful close must not trigger the forceful fallback.
        verify(mockClient, never()).close();
    }

    @Test
    void testClose_GracefulCloseFails_FallsBackToForceClose() {
        setupSuccessfulInitialization();
        wrapper.initialize().block();

        when(mockClient.closeGracefully())
                .thenReturn(Mono.error(new IllegalStateException("graceful close rejected")));

        wrapper.close();

        assertFalse(wrapper.isInitialized());
        assertTrue(wrapper.cachedTools.isEmpty());
        verify(mockClient, times(1)).closeGracefully();
        verify(mockClient, times(1)).close();
    }

    @Test
    void testClose_TimesOutAndFallsBackToForceClose() {
        // Short timeout so the test does not have to wait for the default close timeout.
        McpAsyncClientWrapper shortTimeoutWrapper =
                new McpAsyncClientWrapper("test-async-client", mockClient, Duration.ofMillis(50));
        setupSuccessfulInitialization();
        shortTimeoutWrapper.initialize().block();
        assertTrue(shortTimeoutWrapper.isInitialized());

        // Graceful close never completes -> the bounded wait must give up,
        // then fall back to forceful close instead of blocking forever.
        when(mockClient.closeGracefully()).thenReturn(Mono.never());

        shortTimeoutWrapper.close();

        assertFalse(shortTimeoutWrapper.isInitialized());
        assertTrue(shortTimeoutWrapper.cachedTools.isEmpty());
        verify(mockClient, times(1)).closeGracefully();
        verify(mockClient, times(1)).close();
    }

    @Test
    void testClose_TimeoutCancelsPendingGracefulClose() {
        McpAsyncClientWrapper shortTimeoutWrapper =
                new McpAsyncClientWrapper("test-async-client", mockClient, Duration.ofMillis(50));
        AtomicBoolean cancelled = new AtomicBoolean(false);
        when(mockClient.closeGracefully())
                .thenReturn(Mono.<Void>never().doOnCancel(() -> cancelled.set(true)));

        shortTimeoutWrapper.close();

        // The pending graceful-close chain must be cancelled rather than left dangling.
        assertTrue(cancelled.get());
        verify(mockClient, times(1)).close();
    }

    private void setupSuccessfulInitialization() {
        McpSchema.Implementation serverInfo =
                new McpSchema.Implementation("TestServer", "Test Server", Version.VERSION);
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
