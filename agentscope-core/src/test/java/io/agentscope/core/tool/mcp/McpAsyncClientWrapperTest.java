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
import static org.mockito.Mockito.mock;
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
                McpSchema.Implementation.builder("TestServer", Version.VERSION)
                        .title("Test Server")
                        .build();
        McpSchema.InitializeResult initResult =
                new McpSchema.InitializeResult(
                        "1.0",
                        McpSchema.ServerCapabilities.builder().build(),
                        serverInfo,
                        null,
                        null);

        McpSchema.Tool tool1 =
                McpSchema.Tool.builder("tool1", Map.of("type", "object"))
                        .description("First tool")
                        .build();
        McpSchema.Tool tool2 =
                McpSchema.Tool.builder("tool2", Map.of("type", "object"))
                        .description("Second tool")
                        .build();
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
                McpSchema.Implementation.builder("TestServer", Version.VERSION)
                        .title("Test Server")
                        .build();
        McpSchema.InitializeResult initResult =
                new McpSchema.InitializeResult(
                        "1.0",
                        McpSchema.ServerCapabilities.builder().build(),
                        serverInfo,
                        null,
                        null);
        McpSchema.ListToolsResult toolsResult =
                McpSchema.ListToolsResult.builder(List.of()).build();

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
                McpSchema.Tool.builder("test-tool", Map.of("type", "object"))
                        .description("Test tool")
                        .build();
        McpSchema.ListToolsResult toolsResult =
                McpSchema.ListToolsResult.builder(List.of(tool)).build();

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
                McpSchema.TextContent.builder("Tool executed successfully").build();
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
    void testClose_GracefulAttemptTimesOutForcesClosure() {
        // An unresponsive server keeps the transport open and never completes closeGracefully().
        // Without a ceiling, block() parks the caller forever and the forceful fallback is
        // unreachable - so close() must return within the configured bound and still shut the
        // client down.
        setupSuccessfulInitialization();
        wrapper.initialize().block();
        wrapper.setCloseTimeout(Duration.ofMillis(200));
        when(mockClient.closeGracefully()).thenReturn(Mono.never());

        long startedAt = System.nanoTime();
        wrapper.close();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertTrue(
                elapsedMillis < 5_000,
                "close() did not return within a bounded time: " + elapsedMillis + " ms");
        assertFalse(wrapper.isInitialized());
        assertTrue(wrapper.cachedTools.isEmpty());
        verify(mockClient, times(1)).closeGracefully();
        verify(mockClient, times(1)).close();
    }

    @Test
    void testCloseTimeoutIsBoundedByDefaultAndRejectsUnboundedValues() {
        assertEquals(McpAsyncClientWrapper.DEFAULT_CLOSE_TIMEOUT, wrapper.getCloseTimeout());
        assertThrows(IllegalArgumentException.class, () -> wrapper.setCloseTimeout(null));
        assertThrows(IllegalArgumentException.class, () -> wrapper.setCloseTimeout(Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> wrapper.setCloseTimeout(Duration.ofSeconds(-1)));
        // Rejected calls must not have changed the live ceiling.
        assertEquals(McpAsyncClientWrapper.DEFAULT_CLOSE_TIMEOUT, wrapper.getCloseTimeout());
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
                McpSchema.Implementation.builder("TestServer", Version.VERSION)
                        .title("Test Server")
                        .build();
        McpSchema.InitializeResult initResult =
                new McpSchema.InitializeResult(
                        "1.0",
                        McpSchema.ServerCapabilities.builder().build(),
                        serverInfo,
                        null,
                        null);

        McpSchema.Tool tool1 =
                McpSchema.Tool.builder("tool1", Map.of("type", "object"))
                        .description("First tool")
                        .build();
        McpSchema.ListToolsResult toolsResult =
                McpSchema.ListToolsResult.builder(List.of(tool1)).build();

        when(mockClient.initialize()).thenReturn(Mono.just(initResult));
        when(mockClient.listTools()).thenReturn(Mono.just(toolsResult));
    }
}
