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
        headers.put("X-Client-Version", "1.0.6-SNAPSHOT");

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

    // ==================== extractEndpoint Tests ====================

    /**
     * Helper method to invoke the extractEndpoint method in HttpTransportConfig using reflection.
     * Creates a builder with the specified URL and query params, then extracts the endpoint.
     */
    private String invokeExtractEndpoint(String url, Map<String, String> queryParams)
            throws Exception {
        McpClientBuilder builder = McpClientBuilder.create("test").sseTransport(url);

        if (queryParams != null) {
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                builder.queryParam(entry.getKey(), entry.getValue());
            }
        }

        java.lang.reflect.Field transportConfigField =
                McpClientBuilder.class.getDeclaredField("transportConfig");
        transportConfigField.setAccessible(true);
        Object transportConfig = transportConfigField.get(builder);

        Method method =
                transportConfig.getClass().getSuperclass().getDeclaredMethod("extractEndpoint");
        method.setAccessible(true);
        return (String) method.invoke(transportConfig);
    }

    private String invokeExtractEndpoint(String url) throws Exception {
        return invokeExtractEndpoint(url, null);
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
        // Note: The order of parameters may vary due to HashMap
        assertTrue(endpoint.startsWith("/sse?"));
        assertTrue(endpoint.contains("token=abc123"));
        assertTrue(endpoint.contains("user=test"));
        assertTrue(endpoint.contains("version=1.0"));
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
        // The new implementation URL-encodes the query parameters
        String url = "https://mcp.example.com/api?name=John%20Doe&email=test%40example.com";
        String endpoint = invokeExtractEndpoint(url);
        // Parameters are URL-encoded in the result
        assertTrue(endpoint.startsWith("/api?"));
        assertTrue(endpoint.contains("name=John") || endpoint.contains("name=John+Doe"));
        assertTrue(endpoint.contains("email=test%40example.com"));
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

    // ==================== Query Parameter API Tests ====================

    @Test
    void testQueryParam_OnHttpTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("http-client")
                        .sseTransport("https://mcp.example.com/sse")
                        .queryParam("token", "abc123")
                        .queryParam("tenant", "my-tenant");

        assertNotNull(builder);
    }

    @Test
    void testQueryParam_OnStdioTransport() {
        // Adding query params to stdio transport should not cause errors (just ignored)
        McpClientBuilder builder =
                McpClientBuilder.create("stdio-client")
                        .stdioTransport("python", "-m", "mcp_server_time")
                        .queryParam("token", "abc123");

        assertNotNull(builder);
    }

    @Test
    void testQueryParams_MultipleParams() {
        Map<String, String> params = new HashMap<>();
        params.put("token", "abc123");
        params.put("tenant", "my-tenant");
        params.put("version", "v1");

        McpClientBuilder builder =
                McpClientBuilder.create("http-client")
                        .sseTransport("https://mcp.example.com/sse")
                        .queryParams(params);

        assertNotNull(builder);
    }

    @Test
    void testQueryParams_OverwritePreviousParams() {
        Map<String, String> params1 = new HashMap<>();
        params1.put("key1", "value1");

        Map<String, String> params2 = new HashMap<>();
        params2.put("key2", "value2");

        McpClientBuilder builder =
                McpClientBuilder.create("client")
                        .sseTransport("https://example.com")
                        .queryParams(params1)
                        .queryParams(params2); // Should overwrite

        assertNotNull(builder);
    }

    @Test
    void testQueryParam_AddMultipleTimes() {
        McpClientBuilder builder =
                McpClientBuilder.create("client")
                        .sseTransport("https://example.com")
                        .queryParam("key1", "value1")
                        .queryParam("key2", "value2")
                        .queryParam("key3", "value3");

        assertNotNull(builder);
    }

    @Test
    void testQueryParam_WithHeadersCombined() {
        McpClientBuilder builder =
                McpClientBuilder.create("client")
                        .sseTransport("https://mcp.example.com/sse")
                        .header("Authorization", "Bearer token")
                        .queryParam("tenant", "my-tenant")
                        .header("X-Client-Version", "1.0")
                        .queryParam("version", "v1");

        assertNotNull(builder);
    }

    @Test
    void testQueryParams_OnStdioTransport() {
        Map<String, String> params = new HashMap<>();
        params.put("key", "value");

        McpClientBuilder builder =
                McpClientBuilder.create("client")
                        .stdioTransport("python", "server.py")
                        .queryParams(params);

        // Should not throw, just ignored for stdio transport
        assertNotNull(builder);
    }

    @Test
    void testBuildAsync_WithQueryParams() {
        McpClientBuilder builder =
                McpClientBuilder.create("query-client")
                        .sseTransport("https://mcp.example.com/sse")
                        .queryParam("token", "secret123")
                        .queryParam("env", "production");

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertEquals("query-client", wrapper.getName());
    }

    @Test
    void testBuildSync_WithQueryParams() {
        McpClientBuilder builder =
                McpClientBuilder.create("query-sync-client")
                        .streamableHttpTransport("https://mcp.example.com/http")
                        .queryParams(Map.of("token", "abc", "user", "test"));

        McpClientWrapper wrapper = builder.buildSync();
        assertNotNull(wrapper);
        assertEquals("query-sync-client", wrapper.getName());
    }

    @Test
    void testFluentApi_WithQueryParamsAndHeaders() {
        McpClientBuilder builder =
                McpClientBuilder.create("full-client")
                        .sseTransport("https://mcp.example.com/sse?existing=param")
                        .header("Authorization", "Bearer token")
                        .queryParam("token", "abc123")
                        .queryParam("tenant", "my-tenant")
                        .timeout(Duration.ofSeconds(60))
                        .initializationTimeout(Duration.ofSeconds(30));

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
    }

    // ==================== extractEndpoint with Query Params Tests ====================

    @Test
    void testExtractEndpoint_NoAdditionalParams() throws Exception {
        String endpoint = invokeExtractEndpoint("https://example.com/sse", new HashMap<>());
        assertEquals("/sse", endpoint);
    }

    @Test
    void testExtractEndpoint_OnlyAdditionalParams() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("token", "abc123");

        String endpoint = invokeExtractEndpoint("https://example.com/sse", params);
        assertEquals("/sse?token=abc123", endpoint);
    }

    @Test
    void testExtractEndpoint_MergeWithUrlParams() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("newParam", "newValue");

        String endpoint =
                invokeExtractEndpoint(
                        "https://example.com/sse?existingParam=existingValue", params);

        // Should contain both parameters
        assertTrue(endpoint.startsWith("/sse?"));
        assertTrue(endpoint.contains("existingParam=existingValue"));
        assertTrue(endpoint.contains("newParam=newValue"));
    }

    @Test
    void testExtractEndpoint_AdditionalParamOverridesUrlParam() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("token", "newToken");

        String endpoint = invokeExtractEndpoint("https://example.com/sse?token=oldToken", params);

        // Additional param should override
        assertTrue(endpoint.contains("token=newToken"));
        assertFalse(endpoint.contains("oldToken"));
    }

    @Test
    void testExtractEndpoint_MultipleAdditionalParams() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("token", "abc");
        params.put("tenant", "xyz");

        String endpoint = invokeExtractEndpoint("https://example.com/api/sse", params);

        assertTrue(endpoint.startsWith("/api/sse?"));
        assertTrue(endpoint.contains("token=abc"));
        assertTrue(endpoint.contains("tenant=xyz"));
    }

    @Test
    void testExtractEndpoint_UrlEncodesSpecialChars() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("name", "John Doe");
        params.put("email", "test@example.com");

        String endpoint = invokeExtractEndpoint("https://example.com/sse", params);

        // Should be URL encoded
        assertTrue(endpoint.contains("name=John+Doe") || endpoint.contains("name=John%20Doe"));
        assertTrue(endpoint.contains("email=test%40example.com"));
    }

    @Test
    void testExtractEndpoint_ComplexPathWithMergedParams() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("version", "v1");

        String endpoint =
                invokeExtractEndpoint("https://example.com/api/v2/mcp/sse?token=secret", params);

        assertTrue(endpoint.startsWith("/api/v2/mcp/sse?"));
        assertTrue(endpoint.contains("token=secret"));
        assertTrue(endpoint.contains("version=v1"));
    }

    // ==================== Null Validation Tests ====================

    @Test
    void testQueryParam_WithNullKey() {
        McpClientBuilder builder =
                McpClientBuilder.create("client").sseTransport("https://example.com/sse");

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class, () -> builder.queryParam(null, "value"));

        assertEquals("Query parameter key cannot be null", exception.getMessage());
    }

    @Test
    void testQueryParam_WithNullValue() {
        McpClientBuilder builder =
                McpClientBuilder.create("client").sseTransport("https://example.com/sse");

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> builder.queryParam("key", null));

        assertEquals("Query parameter value cannot be null", exception.getMessage());
    }

    @Test
    void testQueryParam_WithBothNull() {
        McpClientBuilder builder =
                McpClientBuilder.create("client").sseTransport("https://example.com/sse");

        // Should throw for null key first
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> builder.queryParam(null, null));

        assertEquals("Query parameter key cannot be null", exception.getMessage());
    }

    @Test
    void testQueryParams_WithNullMap() {
        McpClientBuilder builder =
                McpClientBuilder.create("client").sseTransport("https://example.com/sse");

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> builder.queryParams(null));

        assertEquals("Query parameters map cannot be null", exception.getMessage());
    }

    @Test
    void testHeader_WithNullKey() {
        // Null header key is allowed (stored in map as-is)
        McpClientBuilder builder =
                McpClientBuilder.create("client").sseTransport("https://example.com/sse");

        // Should not throw - null key is accepted
        assertNotNull(builder.header(null, "value"));
    }

    @Test
    void testQueryParam_NullOnStdioTransport_ShouldNotThrow() {
        // For StdIO transport, query params are ignored, so null should not throw
        McpClientBuilder builder =
                McpClientBuilder.create("client").stdioTransport("python", "server.py");

        // Should not throw because the method simply returns without calling addQueryParam
        assertNotNull(builder.queryParam(null, "value"));
        assertNotNull(builder.queryParam("key", null));
    }

    @Test
    void testQueryParams_NullOnStdioTransport_ShouldNotThrow() {
        // For StdIO transport, query params are ignored, so null should not throw
        McpClientBuilder builder =
                McpClientBuilder.create("client").stdioTransport("python", "server.py");

        // Should not throw because the method simply returns without calling setQueryParams
        assertNotNull(builder.queryParams(null));
    }

    // ==================== extractEndpoint Edge Cases Tests ====================

    @Test
    void testExtractEndpoint_WithEmptyPath_ShouldDefaultToSlash() throws Exception {
        // URL without path should default to "/"
        String url = "https://example.com?token=abc";
        String endpoint = invokeExtractEndpoint(url);
        assertTrue(endpoint.startsWith("/?") || endpoint.equals("/"));
        assertTrue(endpoint.contains("token=abc"));
    }

    @Test
    void testExtractEndpoint_WithOnlyHost_ShouldDefaultToSlash() throws Exception {
        // URL with only host, no path, no query
        String url = "https://example.com";
        String endpoint = invokeExtractEndpoint(url);
        assertEquals("/", endpoint);
    }

    @Test
    void testExtractEndpoint_WithOnlyHostAndQueryParams() throws Exception {
        // URL with host and additional query params, no path
        Map<String, String> params = new HashMap<>();
        params.put("token", "secret");

        String endpoint = invokeExtractEndpoint("https://example.com", params);
        assertEquals("/?token=secret", endpoint);
    }

    @Test
    void testExtractEndpoint_InvalidUrl_ShouldThrowIllegalArgumentException() {
        // Invalid URL should throw IllegalArgumentException
        McpClientBuilder builder = McpClientBuilder.create("test").sseTransport("not a valid url");

        Exception exception =
                assertThrows(
                        Exception.class,
                        () -> {
                            java.lang.reflect.Field transportConfigField =
                                    McpClientBuilder.class.getDeclaredField("transportConfig");
                            transportConfigField.setAccessible(true);
                            Object transportConfig = transportConfigField.get(builder);

                            java.lang.reflect.Method method =
                                    transportConfig
                                            .getClass()
                                            .getSuperclass()
                                            .getDeclaredMethod("extractEndpoint");
                            method.setAccessible(true);
                            method.invoke(transportConfig);
                        });

        // The cause should be IllegalArgumentException
        Throwable cause = exception.getCause();
        assertTrue(
                cause instanceof IllegalArgumentException,
                "Expected IllegalArgumentException but got: " + cause.getClass().getName());
        assertTrue(cause.getMessage().contains("Invalid URL format"));
    }

    @Test
    void testExtractEndpoint_WithEmptyQueryValue() throws Exception {
        // URL with parameter that has empty value: ?key=
        String url = "https://example.com/sse?emptyKey=";
        String endpoint = invokeExtractEndpoint(url);
        assertTrue(endpoint.contains("emptyKey="));
    }

    @Test
    void testExtractEndpoint_WithParameterWithoutValue() throws Exception {
        // URL with parameter that has no value at all: ?flag
        String url = "https://example.com/sse?flag";
        String endpoint = invokeExtractEndpoint(url);
        assertTrue(endpoint.contains("flag="));
    }

    @Test
    void testExtractEndpoint_WithConsecutiveAmpersands() throws Exception {
        // URL with consecutive && should skip empty parameters
        String url = "https://example.com/sse?key1=value1&&key2=value2";
        String endpoint = invokeExtractEndpoint(url);
        assertTrue(endpoint.contains("key1=value1"));
        assertTrue(endpoint.contains("key2=value2"));
    }

    @Test
    void testExtractEndpoint_WithSpecialCharactersInValue() throws Exception {
        // URL with special characters that need encoding
        Map<String, String> params = new HashMap<>();
        params.put("message", "Hello World!");
        params.put("symbol", "@#$%");

        String endpoint = invokeExtractEndpoint("https://example.com/api", params);
        assertTrue(endpoint.startsWith("/api?"));
        // Values should be URL encoded
        assertTrue(
                endpoint.contains("message=Hello+World%21")
                        || endpoint.contains("message=Hello%20World%21"));
    }

    @Test
    void testExtractEndpoint_WithChineseCharacters() throws Exception {
        // URL with Chinese characters
        Map<String, String> params = new HashMap<>();
        params.put("name", "测试");

        String endpoint = invokeExtractEndpoint("https://example.com/api", params);
        assertTrue(endpoint.startsWith("/api?"));
        // Chinese characters should be URL encoded
        assertTrue(endpoint.contains("name=%E6%B5%8B%E8%AF%95"));
    }

    @Test
    void testExtractEndpoint_PreservesPathStructure() throws Exception {
        // Complex nested path should be preserved
        String url = "https://example.com/api/v1/mcp/sse/endpoint";
        String endpoint = invokeExtractEndpoint(url);
        assertEquals("/api/v1/mcp/sse/endpoint", endpoint);
    }

    @Test
    void testExtractEndpoint_WithTrailingSlash() throws Exception {
        // Path with trailing slash should be preserved
        String url = "https://example.com/api/";
        String endpoint = invokeExtractEndpoint(url);
        assertEquals("/api/", endpoint);
    }
}
