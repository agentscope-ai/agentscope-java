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
package io.agentscope.core.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpClientBuilderTest {

    @Test
    void testCreate_WithValidName() {
        McpClientBuilder builder = McpClientBuilder.create("test-client");
        assertNotNull(builder);
    }

    @Test
    void testCreate_WithNullName() {
        assertThrows(IllegalArgumentException.class, () -> McpClientBuilder.create(null));
    }

    @Test
    void testCreate_WithEmptyName() {
        assertThrows(IllegalArgumentException.class, () -> McpClientBuilder.create(""));
    }

    @Test
    void testCreate_WithWhitespaceName() {
        assertThrows(IllegalArgumentException.class, () -> McpClientBuilder.create("   "));
    }

    @Test
    void testStdioTransport_BasicCommand() {
        McpClientBuilder builder =
                McpClientBuilder.create("stdio-client")
                        .stdioTransport("python", "-m", "mcp_server_time");

        assertNotNull(builder);
    }

    @Test
    void testStdioTransport_WithNoArgs() {
        McpClientBuilder builder = McpClientBuilder.create("stdio-client").stdioTransport("node");

        assertNotNull(builder);
    }

    @Test
    void testStdioTransport_WithEnvironmentVariables() {
        Map<String, String> env = new HashMap<>();
        env.put("DEBUG", "true");
        env.put("LOG_LEVEL", "info");

        McpClientBuilder builder =
                McpClientBuilder.create("stdio-client")
                        .stdioTransport("python", List.of("-m", "mcp_server_time"), env);

        assertNotNull(builder);
    }

    @Test
    void testSseTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("sse-client").sseTransport("https://mcp.example.com/sse");

        assertNotNull(builder);
    }

    @Test
    void testStreamableHttpTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("http-client")
                        .streamableHttpTransport("https://mcp.example.com/http");

        assertNotNull(builder);
    }

    @Test
    void testHeader_OnHttpTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("http-client")
                        .sseTransport("https://mcp.example.com/sse")
                        .header("Authorization", "Bearer token123")
                        .header("X-Custom-Header", "custom-value");

        assertNotNull(builder);
    }

    @Test
    void testHeader_OnStdioTransport() {
        // Adding headers to stdio transport should not cause errors (just ignored)
        McpClientBuilder builder =
                McpClientBuilder.create("stdio-client")
                        .stdioTransport("python", "-m", "mcp_server_time")
                        .header("Authorization", "Bearer token123");

        assertNotNull(builder);
    }

    @Test
    void testHeaders_MultipleHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer token123");
        headers.put("X-API-Key", "api-key-456");
        headers.put("Content-Type", "application/json");

        McpClientBuilder builder =
                McpClientBuilder.create("http-client")
                        .sseTransport("https://mcp.example.com/sse")
                        .headers(headers);

        assertNotNull(builder);
    }

    @Test
    void testTimeout() {
        McpClientBuilder builder =
                McpClientBuilder.create("client").timeout(Duration.ofSeconds(90));

        assertNotNull(builder);
    }

    @Test
    void testInitializationTimeout() {
        McpClientBuilder builder =
                McpClientBuilder.create("client").initializationTimeout(Duration.ofSeconds(45));

        assertNotNull(builder);
    }

    @Test
    void testFluentApi_ChainedCalls() {
        McpClientBuilder builder =
                McpClientBuilder.create("test-client")
                        .sseTransport("https://mcp.example.com/sse")
                        .header("Authorization", "Bearer token")
                        .timeout(Duration.ofSeconds(60))
                        .initializationTimeout(Duration.ofSeconds(30));

        assertNotNull(builder);
    }

    @Test
    void testBuildAsync_WithoutTransport() {
        McpClientBuilder builder = McpClientBuilder.create("client");

        Exception exception =
                assertThrows(IllegalStateException.class, () -> builder.buildAsync().block());

        assertTrue(exception.getMessage().contains("Transport must be configured"));
    }

    @Test
    void testBuildSync_WithoutTransport() {
        McpClientBuilder builder = McpClientBuilder.create("client");

        assertThrows(
                IllegalStateException.class,
                () -> builder.buildSync(),
                "Transport must be configured");
    }

    @Test
    void testBuildAsync_ReturnsWrapper() {
        McpClientBuilder builder =
                McpClientBuilder.create("test-client").stdioTransport("echo", "hello");

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertEquals("test-client", wrapper.getName());
        assertFalse(wrapper.isInitialized());
        assertTrue(wrapper instanceof McpAsyncClientWrapper);
    }

    @Test
    void testBuildSync_ReturnsWrapper() {
        McpClientBuilder builder =
                McpClientBuilder.create("test-sync-client").stdioTransport("echo", "hello");

        McpClientWrapper wrapper = builder.buildSync();

        assertNotNull(wrapper);
        assertEquals("test-sync-client", wrapper.getName());
        assertFalse(wrapper.isInitialized());
        assertTrue(wrapper instanceof McpSyncClientWrapper);
    }

    @Test
    void testBuildAsync_WithStdioTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("stdio-client")
                        .stdioTransport("python", "-m", "mcp_server_time")
                        .timeout(Duration.ofSeconds(60))
                        .initializationTimeout(Duration.ofSeconds(30));

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertTrue(wrapper instanceof McpAsyncClientWrapper);
    }

    @Test
    void testBuildAsync_WithSseTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("sse-client")
                        .sseTransport("https://mcp.example.com/sse")
                        .header("Authorization", "Bearer token")
                        .timeout(Duration.ofSeconds(120));

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertEquals("sse-client", wrapper.getName());
        assertTrue(wrapper instanceof McpAsyncClientWrapper);
    }

    @Test
    void testBuildAsync_WithStreamableHttpTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("http-client")
                        .streamableHttpTransport("https://mcp.example.com/http")
                        .header("X-API-Key", "key123")
                        .timeout(Duration.ofSeconds(90))
                        .initializationTimeout(Duration.ofSeconds(20));

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertTrue(wrapper instanceof McpAsyncClientWrapper);
    }

    @Test
    void testBuildSync_WithStdioTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("stdio-sync-client")
                        .stdioTransport("node", "server.js")
                        .timeout(Duration.ofSeconds(45));

        McpClientWrapper wrapper = builder.buildSync();

        assertNotNull(wrapper);
        assertTrue(wrapper instanceof McpSyncClientWrapper);
        assertEquals("stdio-sync-client", wrapper.getName());
    }

    @Test
    void testBuildSync_WithSseTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("sse-sync-client")
                        .sseTransport("https://mcp.example.com/sse");

        McpClientWrapper wrapper = builder.buildSync();

        assertNotNull(wrapper);
        assertTrue(wrapper instanceof McpSyncClientWrapper);
    }

    @Test
    void testBuildSync_WithStreamableHttpTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("http-sync-client")
                        .streamableHttpTransport("https://mcp.example.com/http");

        McpClientWrapper wrapper = builder.buildSync();

        assertNotNull(wrapper);
        assertTrue(wrapper instanceof McpSyncClientWrapper);
    }

    @Test
    void testMultipleBuilds_FromSameBuilder() {
        McpClientBuilder builder =
                McpClientBuilder.create("multi-client").stdioTransport("echo", "test");

        // Build async
        McpClientWrapper asyncWrapper = builder.buildAsync().block();
        assertNotNull(asyncWrapper);

        // Build sync (reusing same builder)
        McpClientWrapper syncWrapper = builder.buildSync();
        assertNotNull(syncWrapper);
    }

    @Test
    void testStdioTransport_EmptyArgs() {
        McpClientBuilder builder = McpClientBuilder.create("client").stdioTransport("command");

        assertNotNull(builder);
    }

    @Test
    void testStdioTransport_WithEmptyEnv() {
        McpClientBuilder builder =
                McpClientBuilder.create("client")
                        .stdioTransport("python", List.of("-m", "server"), new HashMap<>());

        assertNotNull(builder);
    }

    @Test
    void testHeaders_OnStdioTransport() {
        Map<String, String> headers = new HashMap<>();
        headers.put("key", "value");

        McpClientBuilder builder =
                McpClientBuilder.create("client")
                        .stdioTransport("python", "server.py")
                        .headers(headers);

        // Should not throw, just ignored for stdio transport
        assertNotNull(builder);
    }

    @Test
    void testHeaders_OverwritePreviousHeaders() {
        Map<String, String> headers1 = new HashMap<>();
        headers1.put("key1", "value1");

        Map<String, String> headers2 = new HashMap<>();
        headers2.put("key2", "value2");

        McpClientBuilder builder =
                McpClientBuilder.create("client")
                        .sseTransport("https://example.com")
                        .headers(headers1)
                        .headers(headers2); // Should overwrite

        assertNotNull(builder);
    }

    @Test
    void testHeader_AddMultipleTimes() {
        McpClientBuilder builder =
                McpClientBuilder.create("client")
                        .sseTransport("https://example.com")
                        .header("key1", "value1")
                        .header("key2", "value2")
                        .header("key3", "value3");

        assertNotNull(builder);
    }

    @Test
    void testComplexConfiguration() {
        Map<String, String> env = new HashMap<>();
        env.put("DEBUG", "true");

        McpClientBuilder builder =
                McpClientBuilder.create("complex-client")
                        .stdioTransport(
                                "uvx", List.of("mcp-server-time", "--local-timezone=UTC"), env)
                        .timeout(Duration.ofSeconds(120))
                        .initializationTimeout(Duration.ofSeconds(45));

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertEquals("complex-client", wrapper.getName());
    }

    @Test
    void testSseTransport_WithCompleteConfiguration() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer token");
        headers.put("X-Client-Version", "1.0.2-SNAPSHOT");

        McpClientBuilder builder =
                McpClientBuilder.create("full-sse-client")
                        .sseTransport("https://mcp.higress.ai/sse")
                        .headers(headers)
                        .timeout(Duration.ofSeconds(90))
                        .initializationTimeout(Duration.ofSeconds(30));

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertEquals("full-sse-client", wrapper.getName());
    }

    @Test
    void testStreamableHttpTransport_WithCompleteConfiguration() {
        McpClientBuilder builder =
                McpClientBuilder.create("full-http-client")
                        .streamableHttpTransport("https://mcp.higress.ai/http")
                        .header("X-API-Key", "secret-key")
                        .header("X-Request-ID", "request-123")
                        .timeout(Duration.ofMinutes(2))
                        .initializationTimeout(Duration.ofSeconds(45));

        McpClientWrapper wrapper = builder.buildSync();

        assertNotNull(wrapper);
        assertEquals("full-http-client", wrapper.getName());
        assertFalse(wrapper.isInitialized());
    }

    @Test
    void testDefaultTimeouts() {
        // Test that builder works with default timeouts (not explicitly set)
        McpClientBuilder builder =
                McpClientBuilder.create("default-timeouts").stdioTransport("echo", "test");

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
    }

    @Test
    void testBuildAsync_CreatesNewInstanceEachTime() {
        McpClientBuilder builder =
                McpClientBuilder.create("multi-instance").stdioTransport("echo", "test");

        McpClientWrapper wrapper1 = builder.buildAsync().block();
        McpClientWrapper wrapper2 = builder.buildAsync().block();

        assertNotNull(wrapper1);
        assertNotNull(wrapper2);
        // Each build should create a new instance
        assertTrue(wrapper1 != wrapper2);
    }

    @Test
    void testBuildSync_CreatesNewInstanceEachTime() {
        McpClientBuilder builder =
                McpClientBuilder.create("multi-instance-sync").stdioTransport("echo", "test");

        McpClientWrapper wrapper1 = builder.buildSync();
        McpClientWrapper wrapper2 = builder.buildSync();

        assertNotNull(wrapper1);
        assertNotNull(wrapper2);
        // Each build should create a new instance
        assertTrue(wrapper1 != wrapper2);
    }

    // ==================== Query Parameter Preservation Tests ====================

    /**
     * Helper method to invoke the private extractEndpoint method using reflection.
     */
    private String invokeExtractEndpoint(String url) throws Exception {
        Method method = McpClientBuilder.class.getDeclaredMethod("extractEndpoint", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, url);
    }

    @Test
    void testExtractEndpoint_WithSingleQueryParameter() throws Exception {
        String url = "https://mcp.example.com/sse?token=abc123";
        String endpoint = invokeExtractEndpoint(url);
        assertEquals("/sse?token=abc123", endpoint);
    }

    @Test
    void testExtractEndpoint_WithMultipleQueryParameters() throws Exception {
        String url = "https://mcp.example.com/sse?token=abc123&user=test&version=1.0";
        String endpoint = invokeExtractEndpoint(url);
        assertEquals("/sse?token=abc123&user=test&version=1.0", endpoint);
    }

    @Test
    void testExtractEndpoint_WithoutQueryParameters() throws Exception {
        String url = "https://mcp.example.com/sse";
        String endpoint = invokeExtractEndpoint(url);
        assertEquals("/sse", endpoint);
    }

    @Test
    void testExtractEndpoint_WithRootPath() throws Exception {
        String url = "https://mcp.example.com/?key=value";
        String endpoint = invokeExtractEndpoint(url);
        assertEquals("/?key=value", endpoint);
    }

    @Test
    void testExtractEndpoint_WithEncodedQueryParameters() throws Exception {
        // Note: URI.getQuery() automatically decodes query parameters
        // This is expected behavior as HTTP clients will re-encode when sending requests
        String url = "https://mcp.example.com/api?name=John%20Doe&email=test%40example.com";
        String endpoint = invokeExtractEndpoint(url);
        // The decoded query string is returned (spaces and @ are decoded)
        assertEquals("/api?name=John Doe&email=test@example.com", endpoint);
    }

    @Test
    void testExtractEndpoint_WithComplexPath() throws Exception {
        String url = "https://mcp.example.com/api/v1/sse?token=secret";
        String endpoint = invokeExtractEndpoint(url);
        assertEquals("/api/v1/sse?token=secret", endpoint);
    }

    @Test
    void testExtractEndpoint_WithFragment_ShouldIgnore() throws Exception {
        // Fragment (#section) should not be included in the endpoint
        String url = "https://mcp.example.com/sse?token=abc#section";
        String endpoint = invokeExtractEndpoint(url);
        assertEquals("/sse?token=abc", endpoint);
    }
}
