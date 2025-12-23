/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.aigateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.extensions.aigateway.config.AIGatewayConfig;
import io.agentscope.extensions.aigateway.config.MCPAuthConfig;
import io.agentscope.extensions.aigateway.exception.GatewayException;
import io.agentscope.extensions.aigateway.model.McpServerDetail;
import io.agentscope.extensions.aigateway.model.SearchedTool;
import java.lang.reflect.Method;
import java.net.http.HttpRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AIGatewayClientTest {

    @Test
    void testCreateClientWithNullConfig() {
        GatewayException exception =
                assertThrows(GatewayException.class, () -> new AIGatewayClient(null));
        assertEquals("INVALID_CONFIG", exception.getErrorCode());
    }

    @Test
    void testCreateClientWithValidConfig() {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .accessKeyId("test-key-id")
                        .accessKeySecret("test-key-secret")
                        .gatewayId("gw-test")
                        .gatewayEndpoint("http://example.com")
                        .mcpServerAuthConfig(
                                "union-tools-search", MCPAuthConfig.bearer("test-token"))
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            assertNotNull(client);
        }
    }

    @Test
    void testCreateClientWithBearerAuth() {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://example.com")
                        .mcpServerAuthConfig(
                                "union-tools-search", MCPAuthConfig.bearer("test-token"))
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            assertNotNull(client);
        }
    }

    @Test
    void testCreateClientWithHeaderAuth() {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://example.com")
                        .mcpServerAuthConfig(
                                "union-tools-search",
                                MCPAuthConfig.header("X-Client-ID", "my-client-id"))
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            assertNotNull(client);
        }
    }

    @Test
    void testCreateClientWithQueryAuth() {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://example.com")
                        .mcpServerAuthConfig(
                                "union-tools-search", MCPAuthConfig.query("apiKey", "123456abc"))
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            assertNotNull(client);
        }
    }

    @Test
    void testListSearchedToolsWithEmptyQuery() {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://example.com").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(GatewayException.class, () -> client.listSearchedTools("", 5));
            assertEquals("INVALID_QUERY", exception.getErrorCode());
        }
    }

    @Test
    void testListSearchedToolsWithNullQuery() {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://example.com").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(GatewayException.class, () -> client.listSearchedTools(null, 5));
            assertEquals("INVALID_QUERY", exception.getErrorCode());
        }
    }

    @Test
    void testListSearchedToolsWithWhitespaceQuery() {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://example.com").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(GatewayException.class, () -> client.listSearchedTools("   ", 5));
            assertEquals("INVALID_QUERY", exception.getErrorCode());
        }
    }

    @Test
    void testListSearchedToolsWithoutEndpoint() {
        AIGatewayConfig config = AIGatewayConfig.builder().build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(
                            GatewayException.class, () -> client.listSearchedTools("weather", 5));
            assertEquals("MISSING_ENDPOINT", exception.getErrorCode());
        }
    }

    @Test
    void testListSearchedToolsWithEmptyEndpoint() {
        AIGatewayConfig config = AIGatewayConfig.builder().gatewayEndpoint("").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(
                            GatewayException.class, () -> client.listSearchedTools("weather", 5));
            assertEquals("MISSING_ENDPOINT", exception.getErrorCode());
        }
    }

    @Test
    void testListSearchedToolsWithWhitespaceEndpoint() {
        AIGatewayConfig config = AIGatewayConfig.builder().gatewayEndpoint("   ").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(
                            GatewayException.class, () -> client.listSearchedTools("weather", 5));
            assertEquals("MISSING_ENDPOINT", exception.getErrorCode());
        }
    }

    @Test
    void testListAllMcpClientsWithoutGatewayId() {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .accessKeyId("test-key-id")
                        .accessKeySecret("test-key-secret")
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(GatewayException.class, client::listAllMcpClients);
            assertEquals("MISSING_GATEWAY_ID", exception.getErrorCode());
        }
    }

    @Test
    void testListAllMcpClientsWithEmptyGatewayId() {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .accessKeyId("test-key-id")
                        .accessKeySecret("test-key-secret")
                        .gatewayId("")
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(GatewayException.class, client::listAllMcpClients);
            assertEquals("MISSING_GATEWAY_ID", exception.getErrorCode());
        }
    }

    @Test
    void testListAllMcpClientsWithWhitespaceGatewayId() {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .accessKeyId("test-key-id")
                        .accessKeySecret("test-key-secret")
                        .gatewayId("   ")
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(GatewayException.class, client::listAllMcpClients);
            assertEquals("MISSING_GATEWAY_ID", exception.getErrorCode());
        }
    }

    @Test
    void testListAllMcpClientsWithoutCredentials() {
        AIGatewayConfig config = AIGatewayConfig.builder().gatewayId("gw-test").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(GatewayException.class, client::listAllMcpClients);
            assertEquals("MISSING_CREDENTIALS", exception.getErrorCode());
        }
    }

    @Test
    void testListAllMcpClientsWithoutAccessKeyId() {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayId("gw-test").accessKeySecret("secret").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(GatewayException.class, client::listAllMcpClients);
            assertEquals("MISSING_CREDENTIALS", exception.getErrorCode());
        }
    }

    @Test
    void testListAllMcpClientsWithoutAccessKeySecret() {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayId("gw-test").accessKeyId("keyId").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(GatewayException.class, client::listAllMcpClients);
            assertEquals("MISSING_CREDENTIALS", exception.getErrorCode());
        }
    }

    @Test
    void testCreateClientWithMinimalConfig() {
        AIGatewayConfig config = AIGatewayConfig.builder().build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            assertNotNull(client);
        }
    }

    @Test
    void testCreateClientWithNoAuth() {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://example.com").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            assertNotNull(client);
        }
    }

    @Test
    void testListSearchedToolsWithAuth() {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://invalid-endpoint.local")
                        .mcpServerAuthConfig("union-tools-search", MCPAuthConfig.bearer("token"))
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(GatewayException.class, () -> client.listSearchedTools("test", 5));
            assertNotNull(exception.getErrorCode());
        }
    }

    @Test
    void testListSearchedToolsNegativeTopK() {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://invalid-endpoint.local")
                        .mcpServerAuthConfig("union-tools-search", MCPAuthConfig.bearer("token"))
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(
                            GatewayException.class, () -> client.listSearchedTools("test", -1));
            assertNotNull(exception.getErrorCode());
        }
    }

    @Test
    void testListSearchedToolsZeroTopK() {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://invalid-endpoint.local")
                        .mcpServerAuthConfig("union-tools-search", MCPAuthConfig.bearer("token"))
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(GatewayException.class, () -> client.listSearchedTools("test", 0));
            assertNotNull(exception.getErrorCode());
        }
    }

    @Test
    void testCloseClientTwice() {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://example.com").build();

        AIGatewayClient client = new AIGatewayClient(config);
        client.close();
        client.close();
    }

    @Test
    void testAutoCloseableInterface() {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://example.com").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            assertNotNull(client);
        }
    }

    @Test
    void testClientWithAllAuthTypes() {
        // Test Bearer
        AIGatewayConfig bearerConfig =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://example.com")
                        .mcpServerAuthConfig("union-tools-search", MCPAuthConfig.bearer("token"))
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(bearerConfig)) {
            assertNotNull(client);
        }

        // Test Header
        AIGatewayConfig headerConfig =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://example.com")
                        .mcpServerAuthConfig(
                                "union-tools-search", MCPAuthConfig.header("X-Key", "value"))
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(headerConfig)) {
            assertNotNull(client);
        }

        // Test Query
        AIGatewayConfig queryConfig =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://example.com")
                        .mcpServerAuthConfig(
                                "union-tools-search", MCPAuthConfig.query("key", "value"))
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(queryConfig)) {
            assertNotNull(client);
        }
    }

    @Test
    void testGatewayExceptionContainsContext() {
        AIGatewayConfig config = AIGatewayConfig.builder().build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(
                            GatewayException.class, () -> client.listSearchedTools("weather", 5));
            assertEquals("MISSING_ENDPOINT", exception.getErrorCode());
            assertTrue(exception.getMessage().contains("endpoint"));
        }
    }

    @Test
    void testListAllMcpServersExceptionContainsGatewayId() {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayId("gw-test-id")
                        .accessKeyId("key")
                        .accessKeySecret("secret")
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(GatewayException.class, client::listAllMcpClients);
            assertNotNull(exception.getContext());
        }
    }

    @Test
    void testClientWithRegionId() {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .accessKeyId("key")
                        .accessKeySecret("secret")
                        .gatewayId("gw-test")
                        .regionId("cn-shanghai")
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            assertNotNull(client);
        }
    }

    @Test
    void testQueryAuthWithSpecialCharacters() {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://invalid.local")
                        .mcpServerAuthConfig(
                                "union-tools-search",
                                MCPAuthConfig.query("api+key", "value=with&special"))
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(GatewayException.class, () -> client.listSearchedTools("test", 5));
            assertNotNull(exception);
        }
    }

    @Test
    void testMultipleServerAuthConfigs() {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayId("gw-test")
                        .gatewayEndpoint("http://gateway.example.com")
                        .mcpServerAuthConfig("union-tools-search", MCPAuthConfig.bearer("token1"))
                        .mcpServerAuthConfig("map", MCPAuthConfig.bearer("token2"))
                        .mcpServerAuthConfig(
                                "weather", MCPAuthConfig.header("X-API-Key", "weather-key"))
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            assertNotNull(client);
            assertEquals(3, config.getMcpServerAuthConfigs().size());
        }
    }

    @Test
    void testToolSearchEndpointIsComputed() {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://gateway.example.com").build();

        assertEquals(
                "http://gateway.example.com/mcp-servers/union-tools-search",
                config.getToolSearchEndpoint());
    }

    // ==================== parseToolSearchResponse tests ====================

    @Test
    void testParseToolSearchResponseWithStructuredContent() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://example.com").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            String responseBody =
                    """
                    {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "result": {
                            "structuredContent": {
                                "tools": [
                                    {
                                        "name": "weather_tool",
                                        "title": "Weather Tool",
                                        "description": "Get weather info",
                                        "inputSchema": {"type": "object"}
                                    }
                                ]
                            }
                        }
                    }
                    """;

            Method method =
                    AIGatewayClient.class.getDeclaredMethod(
                            "parseToolSearchResponse", String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<SearchedTool> tools = (List<SearchedTool>) method.invoke(client, responseBody);

            assertEquals(1, tools.size());
            assertEquals("weather_tool", tools.get(0).getName());
            assertEquals("Weather Tool", tools.get(0).getTitle());
            assertEquals("Get weather info", tools.get(0).getDescription());
        }
    }

    @Test
    void testParseToolSearchResponseWithContentText() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://example.com").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            String responseBody =
                    """
                    {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "result": {
                            "content": [
                                {
                                    "type": "text",
                                    "text": "{\\"tools\\": [{\\"name\\": \\"map_tool\\", \\"title\\": \\"Map\\"}]}"
                                }
                            ]
                        }
                    }
                    """;

            Method method =
                    AIGatewayClient.class.getDeclaredMethod(
                            "parseToolSearchResponse", String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<SearchedTool> tools = (List<SearchedTool>) method.invoke(client, responseBody);

            assertEquals(1, tools.size());
            assertEquals("map_tool", tools.get(0).getName());
        }
    }

    @Test
    void testParseToolSearchResponseWithError() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://example.com").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            String responseBody =
                    """
                    {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "error": {
                            "code": -32600,
                            "message": "Invalid Request"
                        }
                    }
                    """;

            Method method =
                    AIGatewayClient.class.getDeclaredMethod(
                            "parseToolSearchResponse", String.class);
            method.setAccessible(true);

            try {
                method.invoke(client, responseBody);
            } catch (Exception e) {
                assertTrue(e.getCause() instanceof GatewayException);
                assertEquals(
                        "TOOL_SEARCH_RPC_ERROR", ((GatewayException) e.getCause()).getErrorCode());
            }
        }
    }

    @Test
    void testParseToolSearchResponseWithMissingResult() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://example.com").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            String responseBody =
                    """
                    {"jsonrpc": "2.0", "id": 1}
                    """;

            Method method =
                    AIGatewayClient.class.getDeclaredMethod(
                            "parseToolSearchResponse", String.class);
            method.setAccessible(true);

            try {
                method.invoke(client, responseBody);
            } catch (Exception e) {
                assertTrue(e.getCause() instanceof GatewayException);
                assertEquals(
                        "TOOL_SEARCH_PARSE_ERROR",
                        ((GatewayException) e.getCause()).getErrorCode());
            }
        }
    }

    @Test
    void testParseToolSearchResponseEmptyTools() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://example.com").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            String responseBody =
                    """
                    {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "result": {
                            "structuredContent": {
                                "tools": []
                            }
                        }
                    }
                    """;

            Method method =
                    AIGatewayClient.class.getDeclaredMethod(
                            "parseToolSearchResponse", String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<SearchedTool> tools = (List<SearchedTool>) method.invoke(client, responseBody);

            assertTrue(tools.isEmpty());
        }
    }

    // ==================== applyHeaderAuth tests ====================

    @Test
    void testApplyHeaderAuthWithBearerToken() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://example.com")
                        .mcpServerAuthConfig("union-tools-search", MCPAuthConfig.bearer("my-token"))
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            HttpRequest.Builder requestBuilder =
                    HttpRequest.newBuilder().uri(java.net.URI.create("http://example.com"));

            Method method =
                    AIGatewayClient.class.getDeclaredMethod(
                            "applyHeaderAuth", HttpRequest.Builder.class);
            method.setAccessible(true);
            method.invoke(client, requestBuilder);

            HttpRequest request = requestBuilder.GET().build();
            assertTrue(request.headers().firstValue("Authorization").isPresent());
            assertEquals("Bearer my-token", request.headers().firstValue("Authorization").get());
        }
    }

    @Test
    void testApplyHeaderAuthWithCustomHeader() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://example.com")
                        .mcpServerAuthConfig(
                                "union-tools-search",
                                MCPAuthConfig.header("X-Custom-Key", "custom-value"))
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            HttpRequest.Builder requestBuilder =
                    HttpRequest.newBuilder().uri(java.net.URI.create("http://example.com"));

            Method method =
                    AIGatewayClient.class.getDeclaredMethod(
                            "applyHeaderAuth", HttpRequest.Builder.class);
            method.setAccessible(true);
            method.invoke(client, requestBuilder);

            HttpRequest request = requestBuilder.GET().build();
            assertTrue(request.headers().firstValue("X-Custom-Key").isPresent());
            assertEquals("custom-value", request.headers().firstValue("X-Custom-Key").get());
        }
    }

    @Test
    void testApplyHeaderAuthWithNoAuth() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://example.com").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            HttpRequest.Builder requestBuilder =
                    HttpRequest.newBuilder().uri(java.net.URI.create("http://example.com"));

            Method method =
                    AIGatewayClient.class.getDeclaredMethod(
                            "applyHeaderAuth", HttpRequest.Builder.class);
            method.setAccessible(true);
            method.invoke(client, requestBuilder);

            HttpRequest request = requestBuilder.GET().build();
            assertFalse(request.headers().firstValue("Authorization").isPresent());
        }
    }

    // ==================== applyQueryAuth tests ====================

    @Test
    void testApplyQueryAuthWithQueryParam() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://example.com")
                        .mcpServerAuthConfig(
                                "union-tools-search", MCPAuthConfig.query("apiKey", "secret123"))
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            Method method = AIGatewayClient.class.getDeclaredMethod("applyQueryAuth", String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(client, "http://example.com/api");
            assertEquals("http://example.com/api?apiKey=secret123", result);
        }
    }

    @Test
    void testApplyQueryAuthWithExistingQueryParam() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://example.com")
                        .mcpServerAuthConfig(
                                "union-tools-search", MCPAuthConfig.query("apiKey", "secret123"))
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            Method method = AIGatewayClient.class.getDeclaredMethod("applyQueryAuth", String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(client, "http://example.com/api?foo=bar");
            assertEquals("http://example.com/api?foo=bar&apiKey=secret123", result);
        }
    }

    @Test
    void testApplyQueryAuthWithBearerAuth() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://example.com")
                        .mcpServerAuthConfig("union-tools-search", MCPAuthConfig.bearer("token"))
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            Method method = AIGatewayClient.class.getDeclaredMethod("applyQueryAuth", String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(client, "http://example.com/api");
            // Should return unchanged since it's Bearer auth, not Query
            assertEquals("http://example.com/api", result);
        }
    }

    // ==================== buildMcpEndpoint tests ====================

    @Test
    void testBuildMcpEndpointWithGatewayEndpoint() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://gateway.example.com")
                        .gatewayId("gw-test")
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            McpServerDetail server = new McpServerDetail();
            server.setName("test-server");
            server.setMcpServerPath("/mcp-servers/test");

            Method method =
                    AIGatewayClient.class.getDeclaredMethod(
                            "buildMcpEndpoint", McpServerDetail.class);
            method.setAccessible(true);

            String result = (String) method.invoke(client, server);
            assertEquals("http://gateway.example.com/mcp-servers/test", result);
        }
    }

    @Test
    void testBuildMcpEndpointWithTrailingSlash() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://gateway.example.com/")
                        .gatewayId("gw-test")
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            McpServerDetail server = new McpServerDetail();
            server.setName("test-server");
            server.setMcpServerPath("/mcp-servers/test");

            Method method =
                    AIGatewayClient.class.getDeclaredMethod(
                            "buildMcpEndpoint", McpServerDetail.class);
            method.setAccessible(true);

            String result = (String) method.invoke(client, server);
            assertEquals("http://gateway.example.com/mcp-servers/test", result);
        }
    }

    @Test
    void testBuildMcpEndpointWithoutLeadingSlash() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://gateway.example.com")
                        .gatewayId("gw-test")
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            McpServerDetail server = new McpServerDetail();
            server.setName("test-server");
            server.setMcpServerPath("mcp-servers/test");

            Method method =
                    AIGatewayClient.class.getDeclaredMethod(
                            "buildMcpEndpoint", McpServerDetail.class);
            method.setAccessible(true);

            String result = (String) method.invoke(client, server);
            assertEquals("http://gateway.example.com/mcp-servers/test", result);
        }
    }

    @Test
    void testBuildMcpEndpointWithNoPath() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://gateway.example.com")
                        .gatewayId("gw-test")
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            McpServerDetail server = new McpServerDetail();
            server.setName("test-server");
            server.setMcpServerPath(null);

            Method method =
                    AIGatewayClient.class.getDeclaredMethod(
                            "buildMcpEndpoint", McpServerDetail.class);
            method.setAccessible(true);

            String result = (String) method.invoke(client, server);
            assertNull(result);
        }
    }

    @Test
    void testBuildMcpEndpointWithEmptyPath() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://gateway.example.com")
                        .gatewayId("gw-test")
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            McpServerDetail server = new McpServerDetail();
            server.setName("test-server");
            server.setMcpServerPath("");

            Method method =
                    AIGatewayClient.class.getDeclaredMethod(
                            "buildMcpEndpoint", McpServerDetail.class);
            method.setAccessible(true);

            String result = (String) method.invoke(client, server);
            assertNull(result);
        }
    }

    @Test
    void testBuildMcpEndpointFallbackToHttpEndpoint() throws Exception {
        AIGatewayConfig config = AIGatewayConfig.builder().gatewayId("gw-test").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            McpServerDetail server = new McpServerDetail();
            server.setName("test-server");
            server.setMcpServerPath("/mcp-servers/test");

            // Setup domain info for HTTP endpoint
            McpServerDetail.DomainInfo domainInfo = new McpServerDetail.DomainInfo();
            domainInfo.setName("api.example.com");
            domainInfo.setProtocol("HTTP");
            server.setDomainInfos(List.of(domainInfo));

            Method method =
                    AIGatewayClient.class.getDeclaredMethod(
                            "buildMcpEndpoint", McpServerDetail.class);
            method.setAccessible(true);

            String result = (String) method.invoke(client, server);
            // Should use domain-based endpoint (domain + mcpServerPath)
            assertEquals("http://api.example.com/mcp-servers/test", result);
        }
    }

    @Test
    void testBuildMcpEndpointWithWildcardDomain() throws Exception {
        AIGatewayConfig config = AIGatewayConfig.builder().gatewayId("gw-test").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            McpServerDetail server = new McpServerDetail();
            server.setName("test-server");
            server.setMcpServerPath(null);

            // Domain with wildcard - should be rejected
            McpServerDetail.DomainInfo domainInfo = new McpServerDetail.DomainInfo();
            domainInfo.setName("*.example.com");
            domainInfo.setProtocol("HTTP");
            server.setDomainInfos(List.of(domainInfo));
            server.setExposedUriPath("/mcp");

            Method method =
                    AIGatewayClient.class.getDeclaredMethod(
                            "buildMcpEndpoint", McpServerDetail.class);
            method.setAccessible(true);

            String result = (String) method.invoke(client, server);
            assertNull(result);
        }
    }

    // ==================== toAgentTool tests ====================

    @Test
    void testToAgentToolBasicProperties() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://example.com").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            SearchedTool searchedTool = new SearchedTool();
            searchedTool.setName("test_tool");
            searchedTool.setTitle("Test Tool Title");
            searchedTool.setDescription("Test tool description");

            Map<String, Object> inputSchema = new HashMap<>();
            inputSchema.put("type", "object");
            searchedTool.setInputSchema(inputSchema);

            Method method =
                    AIGatewayClient.class.getDeclaredMethod("toAgentTool", SearchedTool.class);
            method.setAccessible(true);

            AgentTool agentTool = (AgentTool) method.invoke(client, searchedTool);

            assertEquals("test_tool", agentTool.getName());
            assertEquals("Test tool description", agentTool.getDescription());
            assertNotNull(agentTool.getParameters());
            assertEquals("object", agentTool.getParameters().get("type"));
        }
    }

    @Test
    void testToAgentToolWithNullDescription() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://example.com").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            SearchedTool searchedTool = new SearchedTool();
            searchedTool.setName("test_tool");
            searchedTool.setTitle("Test Tool Title");
            searchedTool.setDescription(null);

            Method method =
                    AIGatewayClient.class.getDeclaredMethod("toAgentTool", SearchedTool.class);
            method.setAccessible(true);

            AgentTool agentTool = (AgentTool) method.invoke(client, searchedTool);

            // Should fallback to title when description is null
            assertEquals("Test Tool Title", agentTool.getDescription());
        }
    }

    @Test
    void testToAgentToolWithNullInputSchema() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://example.com").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            SearchedTool searchedTool = new SearchedTool();
            searchedTool.setName("test_tool");
            searchedTool.setInputSchema(null);

            Method method =
                    AIGatewayClient.class.getDeclaredMethod("toAgentTool", SearchedTool.class);
            method.setAccessible(true);

            AgentTool agentTool = (AgentTool) method.invoke(client, searchedTool);

            assertNotNull(agentTool.getParameters());
            assertTrue(agentTool.getParameters().isEmpty());
        }
    }

    // ==================== parseToolNode tests ====================

    @Test
    void testParseToolNodeWithAllFields() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://example.com").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String json =
                    """
                    {
                        "name": "weather_api",
                        "title": "Weather API",
                        "description": "Get weather data",
                        "inputSchema": {"type": "object", "properties": {}},
                        "outputSchema": {"type": "string"}
                    }
                    """;
            com.fasterxml.jackson.databind.JsonNode toolNode = mapper.readTree(json);

            Method method =
                    AIGatewayClient.class.getDeclaredMethod(
                            "parseToolNode", com.fasterxml.jackson.databind.JsonNode.class);
            method.setAccessible(true);

            SearchedTool tool = (SearchedTool) method.invoke(client, toolNode);

            assertEquals("weather_api", tool.getName());
            assertEquals("Weather API", tool.getTitle());
            assertEquals("Get weather data", tool.getDescription());
            assertNotNull(tool.getInputSchema());
            assertNotNull(tool.getOutputSchema());
        }
    }

    @Test
    void testParseToolNodeWithMinimalFields() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://example.com").build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String json =
                    """
                    {"name": "simple_tool"}
                    """;
            com.fasterxml.jackson.databind.JsonNode toolNode = mapper.readTree(json);

            Method method =
                    AIGatewayClient.class.getDeclaredMethod(
                            "parseToolNode", com.fasterxml.jackson.databind.JsonNode.class);
            method.setAccessible(true);

            SearchedTool tool = (SearchedTool) method.invoke(client, toolNode);

            assertEquals("simple_tool", tool.getName());
            assertNull(tool.getTitle());
            assertNull(tool.getDescription());
            assertNull(tool.getInputSchema());
            assertNull(tool.getOutputSchema());
        }
    }

    // ==================== getToolExecutionEndpoint tests ====================

    @Test
    void testGetToolExecutionEndpoint() throws Exception {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://gateway.example.com")
                        .mcpServerAuthConfig(
                                "union-tools-search", MCPAuthConfig.query("key", "value"))
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            Method method = AIGatewayClient.class.getDeclaredMethod("getToolExecutionEndpoint");
            method.setAccessible(true);

            String result = (String) method.invoke(client);
            assertEquals(
                    "http://gateway.example.com/mcp-servers/union-tools-search?key=value", result);
        }
    }

    // ==================== Additional edge case tests ====================

    @Test
    void testListSearchedToolsWithConnectionError() {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://nonexistent.invalid.local")
                        .build();

        try (AIGatewayClient client = new AIGatewayClient(config)) {
            GatewayException exception =
                    assertThrows(
                            GatewayException.class, () -> client.listSearchedTools("weather", 5));
            assertEquals("TOOL_SEARCH_ERROR", exception.getErrorCode());
        }
    }

    @Test
    void testClientConfigGetters() {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://example.com")
                        .gatewayId("gw-123")
                        .accessKeyId("ak-123")
                        .accessKeySecret("sk-123")
                        .regionId("cn-beijing")
                        .build();

        assertEquals("http://example.com", config.getGatewayEndpoint());
        assertEquals("gw-123", config.getGatewayId());
        assertEquals("ak-123", config.getAccessKeyId());
        assertEquals("sk-123", config.getAccessKeySecret());
        assertEquals("cn-beijing", config.getRegionId());
    }

    @Test
    void testToolSearchEndpointWithTrailingSlash() {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://gateway.example.com/").build();

        assertEquals(
                "http://gateway.example.com/mcp-servers/union-tools-search",
                config.getToolSearchEndpoint());
    }

    @Test
    void testToolSearchEndpointWithNullGateway() {
        AIGatewayConfig config = AIGatewayConfig.builder().build();
        assertNull(config.getToolSearchEndpoint());
    }

    @Test
    void testToolSearchEndpointWithEmptyGateway() {
        AIGatewayConfig config = AIGatewayConfig.builder().gatewayEndpoint("").build();
        assertNull(config.getToolSearchEndpoint());
    }

    @Test
    void testToolSearchEndpointWithWhitespaceGateway() {
        AIGatewayConfig config = AIGatewayConfig.builder().gatewayEndpoint("   ").build();
        assertNull(config.getToolSearchEndpoint());
    }

    @Test
    void testMcpAuthConfigEquality() {
        MCPAuthConfig bearer1 = MCPAuthConfig.bearer("token");
        MCPAuthConfig bearer2 = MCPAuthConfig.bearer("token");
        MCPAuthConfig header = MCPAuthConfig.header("X-Key", "value");
        MCPAuthConfig query = MCPAuthConfig.query("key", "value");

        assertEquals(MCPAuthConfig.AuthType.BEARER, bearer1.getType());
        assertEquals(MCPAuthConfig.AuthType.HEADER, header.getType());
        assertEquals(MCPAuthConfig.AuthType.QUERY, query.getType());

        assertEquals("token", bearer1.getToken());
        assertEquals("X-Key", header.getHeaderName());
        assertEquals("value", header.getHeaderValue());
        assertEquals("key", query.getQueryName());
        assertEquals("value", query.getQueryValue());
    }

    @Test
    void testMcpAuthConfigValidity() {
        MCPAuthConfig validBearer = MCPAuthConfig.bearer("token");
        MCPAuthConfig invalidBearer = MCPAuthConfig.bearer("");
        MCPAuthConfig nullBearer = MCPAuthConfig.bearer(null);

        assertTrue(validBearer.isValid());
        assertFalse(invalidBearer.isValid());
        assertFalse(nullBearer.isValid());

        MCPAuthConfig validHeader = MCPAuthConfig.header("X-Key", "value");
        MCPAuthConfig invalidHeader1 = MCPAuthConfig.header("", "value");
        MCPAuthConfig invalidHeader2 = MCPAuthConfig.header("X-Key", "");

        assertTrue(validHeader.isValid());
        assertFalse(invalidHeader1.isValid());
        assertFalse(invalidHeader2.isValid());

        MCPAuthConfig validQuery = MCPAuthConfig.query("key", "value");
        MCPAuthConfig invalidQuery1 = MCPAuthConfig.query("", "value");
        MCPAuthConfig invalidQuery2 = MCPAuthConfig.query("key", "");

        assertTrue(validQuery.isValid());
        assertFalse(invalidQuery1.isValid());
        assertFalse(invalidQuery2.isValid());
    }

    @Test
    void testSearchedToolSettersAndGetters() {
        SearchedTool tool = new SearchedTool();
        tool.setName("test");
        tool.setTitle("Test Title");
        tool.setDescription("Test Description");

        Map<String, Object> inputSchema = new HashMap<>();
        inputSchema.put("type", "object");
        tool.setInputSchema(inputSchema);

        Map<String, Object> outputSchema = new HashMap<>();
        outputSchema.put("type", "string");
        tool.setOutputSchema(outputSchema);

        assertEquals("test", tool.getName());
        assertEquals("Test Title", tool.getTitle());
        assertEquals("Test Description", tool.getDescription());
        assertEquals(inputSchema, tool.getInputSchema());
        assertEquals(outputSchema, tool.getOutputSchema());
    }

    @Test
    void testMcpServerDetailSettersAndGetters() {
        McpServerDetail detail = new McpServerDetail();
        detail.setMcpServerId("server-123");
        detail.setName("test-server");
        detail.setDescription("Test server description");
        detail.setType("MCP");
        detail.setProtocol("HTTP");
        detail.setMcpServerPath("/mcp-servers/test");
        detail.setExposedUriPath("/test/sse");
        detail.setGatewayId("gw-123");
        detail.setEnvironmentId("env-123");
        detail.setRouteId("route-123");
        detail.setDeployStatus("Deployed");

        assertEquals("server-123", detail.getMcpServerId());
        assertEquals("test-server", detail.getName());
        assertEquals("Test server description", detail.getDescription());
        assertEquals("MCP", detail.getType());
        assertEquals("HTTP", detail.getProtocol());
        assertEquals("/mcp-servers/test", detail.getMcpServerPath());
        assertEquals("/test/sse", detail.getExposedUriPath());
        assertEquals("gw-123", detail.getGatewayId());
        assertEquals("env-123", detail.getEnvironmentId());
        assertEquals("route-123", detail.getRouteId());
        assertEquals("Deployed", detail.getDeployStatus());
    }
}
