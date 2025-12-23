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
package io.agentscope.extensions.aigateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AIGatewayConfigTest {

    @Test
    void testDefaultConstructor() {
        AIGatewayConfig config = new AIGatewayConfig();

        assertNull(config.getAccessKeyId());
        assertNull(config.getAccessKeySecret());
        assertNull(config.getGatewayId());
        assertNull(config.getToolSearchEndpoint());
        assertNull(config.getRegionId());
        assertNull(config.getGatewayEndpoint());
        assertNotNull(config.getMcpServerAuthConfigs());
        assertTrue(config.getMcpServerAuthConfigs().isEmpty());
    }

    @Test
    void testBuilderWithAllFields() {
        MCPAuthConfig mapAuth = MCPAuthConfig.bearer("map-token");

        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .accessKeyId("test-key-id")
                        .accessKeySecret("test-key-secret")
                        .gatewayId("gw-test-123")
                        .gatewayEndpoint("http://gateway.example.com")
                        .mcpServerAuthConfig("map", mapAuth)
                        .regionId("cn-shanghai")
                        .build();

        assertEquals("test-key-id", config.getAccessKeyId());
        assertEquals("test-key-secret", config.getAccessKeySecret());
        assertEquals("gw-test-123", config.getGatewayId());
        assertEquals("cn-shanghai", config.getRegionId());
        assertEquals("http://gateway.example.com", config.getGatewayEndpoint());
        assertEquals(
                "http://gateway.example.com/mcp-servers/union-tools-search",
                config.getToolSearchEndpoint());
        assertEquals(mapAuth, config.getAuthConfigForServer("map"));
    }

    @Test
    void testToolSearchEndpointComputed() {
        AIGatewayConfig config =
                AIGatewayConfig.builder().gatewayEndpoint("http://env-xxx.alicloudapi.com").build();

        assertEquals(
                "http://env-xxx.alicloudapi.com/mcp-servers/union-tools-search",
                config.getToolSearchEndpoint());
    }

    @Test
    void testToolSearchEndpointWithTrailingSlash() {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayEndpoint("http://env-xxx.alicloudapi.com/")
                        .build();

        assertEquals(
                "http://env-xxx.alicloudapi.com/mcp-servers/union-tools-search",
                config.getToolSearchEndpoint());
    }

    @Test
    void testToolSearchEndpointWithoutGatewayEndpoint() {
        AIGatewayConfig config = AIGatewayConfig.builder().build();

        assertNull(config.getToolSearchEndpoint());
    }

    @Test
    void testBuilderWithMcpServerAuthConfig() {
        MCPAuthConfig mapAuth = MCPAuthConfig.bearer("map-token");
        MCPAuthConfig weatherAuth = MCPAuthConfig.header("X-API-Key", "weather-key");

        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .gatewayId("gw-test")
                        .mcpServerAuthConfig("map", mapAuth)
                        .mcpServerAuthConfig("weather", weatherAuth)
                        .build();

        assertEquals(2, config.getMcpServerAuthConfigs().size());
        assertEquals(mapAuth, config.getMcpServerAuthConfigs().get("map"));
        assertEquals(weatherAuth, config.getMcpServerAuthConfigs().get("weather"));
    }

    @Test
    void testGetAuthConfigForServerWithConfig() {
        MCPAuthConfig mapAuth = MCPAuthConfig.bearer("map-token");

        AIGatewayConfig config =
                AIGatewayConfig.builder().mcpServerAuthConfig("map", mapAuth).build();

        assertEquals(mapAuth, config.getAuthConfigForServer("map"));
    }

    @Test
    void testGetAuthConfigForServerWithNoConfig() {
        AIGatewayConfig config = AIGatewayConfig.builder().build();

        assertNull(config.getAuthConfigForServer("any-server"));
    }

    @Test
    void testGetAuthConfigForServerWithDifferentServer() {
        MCPAuthConfig mapAuth = MCPAuthConfig.bearer("map-token");

        AIGatewayConfig config =
                AIGatewayConfig.builder().mcpServerAuthConfig("map", mapAuth).build();

        // Should return null for unconfigured server
        assertNull(config.getAuthConfigForServer("weather"));
    }

    @Test
    void testSettersAndGetters() {
        AIGatewayConfig config = new AIGatewayConfig();

        config.setAccessKeyId("set-key-id");
        config.setAccessKeySecret("set-key-secret");
        config.setGatewayId("gw-set-123");
        config.setRegionId("cn-beijing");
        config.setGatewayEndpoint("http://set-gateway.example.com");

        assertEquals("set-key-id", config.getAccessKeyId());
        assertEquals("set-key-secret", config.getAccessKeySecret());
        assertEquals("gw-set-123", config.getGatewayId());
        assertEquals("cn-beijing", config.getRegionId());
        assertEquals("http://set-gateway.example.com", config.getGatewayEndpoint());
        assertEquals(
                "http://set-gateway.example.com/mcp-servers/union-tools-search",
                config.getToolSearchEndpoint());
    }

    @Test
    void testBuilderCreatesNewInstance() {
        AIGatewayConfig.Builder builder = AIGatewayConfig.builder();
        assertNotNull(builder);
    }

    @Test
    void testBuilderChaining() {
        AIGatewayConfig.Builder builder =
                AIGatewayConfig.builder()
                        .accessKeyId("key1")
                        .accessKeySecret("secret1")
                        .gatewayId("gw1")
                        .gatewayEndpoint("http://gateway.example.com")
                        .regionId("cn-hangzhou");

        assertNotNull(builder);

        AIGatewayConfig config = builder.build();
        assertEquals("key1", config.getAccessKeyId());
    }

    @Test
    void testPartialConfiguration() {
        AIGatewayConfig config = AIGatewayConfig.builder().gatewayId("gw-only").build();

        assertEquals("gw-only", config.getGatewayId());
        assertNull(config.getAccessKeyId());
        assertNull(config.getAccessKeySecret());
        assertNull(config.getToolSearchEndpoint());
        assertNull(config.getRegionId());
    }

    @Test
    void testEmptyBuilder() {
        AIGatewayConfig config = AIGatewayConfig.builder().build();

        assertNotNull(config);
        assertNull(config.getAccessKeyId());
        assertNull(config.getAccessKeySecret());
        assertNull(config.getGatewayId());
        assertNull(config.getToolSearchEndpoint());
        assertNull(config.getRegionId());
    }

    @Test
    void testMultipleBuilds() {
        AIGatewayConfig.Builder builder =
                AIGatewayConfig.builder().gatewayId("gw-1").accessKeyId("key-1");

        AIGatewayConfig config1 = builder.build();
        AIGatewayConfig config2 = builder.accessKeyId("key-2").build();

        assertEquals("key-1", config1.getAccessKeyId());
        assertEquals("key-2", config2.getAccessKeyId());
    }

    @Test
    void testMultipleServerAuthConfigs() {
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .mcpServerAuthConfig(
                                "union-tools-search", MCPAuthConfig.bearer("search-token"))
                        .mcpServerAuthConfig("map", MCPAuthConfig.bearer("map-token"))
                        .mcpServerAuthConfig(
                                "weather", MCPAuthConfig.header("X-API-Key", "weather-key"))
                        .build();

        assertEquals(3, config.getMcpServerAuthConfigs().size());
        assertEquals(
                "search-token", config.getAuthConfigForServer("union-tools-search").getToken());
        assertEquals("map-token", config.getAuthConfigForServer("map").getToken());
        assertEquals("weather-key", config.getAuthConfigForServer("weather").getHeaderValue());
    }
}
