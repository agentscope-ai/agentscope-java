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

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for AI Gateway client.
 *
 * <p>This configuration supports:
 * <ul>
 *   <li>Aliyun SDK credentials for listing MCP servers</li>
 *   <li>Gateway endpoint for tool search and MCP connections</li>
 *   <li>Per-MCP-server authentication configuration</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * AIGatewayConfig config = AIGatewayConfig.builder()
 *     .accessKeyId("your-access-key-id")
 *     .accessKeySecret("your-access-key-secret")
 *     .gatewayId("gw-xxx")
 *     .gatewayEndpoint("http://env-xxx.alicloudapi.com")
 *     // Per-server authentication
 *     .mcpServerAuthConfig("union-tools-search", MCPAuthConfig.bearer("token1"))
 *     .mcpServerAuthConfig("map", MCPAuthConfig.bearer("token2"))
 *     .build();
 * }</pre>
 */
public class AIGatewayConfig {

    /** Aliyun AccessKey ID for SDK authentication. */
    private String accessKeyId;

    /** Aliyun AccessKey Secret for SDK authentication. */
    private String accessKeySecret;

    /** Gateway ID for identifying the target gateway. */
    private String gatewayId;

    /** Per-MCP-server authentication configurations, keyed by server name. */
    private Map<String, MCPAuthConfig> mcpServerAuthConfigs;

    /** Aliyun OpenAPI region for SDK calls (default: cn-hangzhou). */
    private String regionId;

    /**
     * Base endpoint URL for the gateway (e.g., http://env-xxx.alicloudapi.com). Used to construct
     * full MCP endpoint URLs for tool search and MCP client connections.
     *
     * <p>Tool search endpoint will be: gatewayEndpoint + /mcp-servers/union-tools-search
     */
    private String gatewayEndpoint;

    public AIGatewayConfig() {
        this.mcpServerAuthConfigs = new HashMap<>();
    }

    private AIGatewayConfig(Builder builder) {
        this.accessKeyId = builder.accessKeyId;
        this.accessKeySecret = builder.accessKeySecret;
        this.gatewayId = builder.gatewayId;
        this.mcpServerAuthConfigs =
                builder.mcpServerAuthConfigs != null
                        ? new HashMap<>(builder.mcpServerAuthConfigs)
                        : new HashMap<>();
        this.regionId = builder.regionId;
        this.gatewayEndpoint = builder.gatewayEndpoint;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getAccessKeySecret() {
        return accessKeySecret;
    }

    public void setAccessKeySecret(String accessKeySecret) {
        this.accessKeySecret = accessKeySecret;
    }

    public String getGatewayId() {
        return gatewayId;
    }

    public void setGatewayId(String gatewayId) {
        this.gatewayId = gatewayId;
    }

    /**
     * Gets all per-MCP-server authentication configurations.
     *
     * @return map of server name to auth config
     */
    public Map<String, MCPAuthConfig> getMcpServerAuthConfigs() {
        return mcpServerAuthConfigs;
    }

    /**
     * Gets the authentication config for a specific MCP server.
     *
     * @param serverName the MCP server name
     * @return the auth config for the server, or null if not configured
     */
    public MCPAuthConfig getAuthConfigForServer(String serverName) {
        if (mcpServerAuthConfigs != null) {
            return mcpServerAuthConfigs.get(serverName);
        }
        return null;
    }

    public String getRegionId() {
        return regionId;
    }

    public void setRegionId(String regionId) {
        this.regionId = regionId;
    }

    public String getGatewayEndpoint() {
        return gatewayEndpoint;
    }

    public void setGatewayEndpoint(String gatewayEndpoint) {
        this.gatewayEndpoint = gatewayEndpoint;
    }

    /**
     * Gets the tool search endpoint URL.
     *
     * <p>Constructed from: gatewayEndpoint + /mcp-servers/union-tools-search
     *
     * @return the tool search endpoint URL, or null if gatewayEndpoint is not configured
     */
    public String getToolSearchEndpoint() {
        if (gatewayEndpoint == null || gatewayEndpoint.trim().isEmpty()) {
            return null;
        }
        String baseUrl = gatewayEndpoint.trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/mcp-servers/union-tools-search";
    }

    /** Builder for AIGatewayConfig. */
    public static class Builder {
        private String accessKeyId;
        private String accessKeySecret;
        private String gatewayId;
        private Map<String, MCPAuthConfig> mcpServerAuthConfigs = new HashMap<>();
        private String regionId;
        private String gatewayEndpoint;

        public Builder accessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId;
            return this;
        }

        public Builder accessKeySecret(String accessKeySecret) {
            this.accessKeySecret = accessKeySecret;
            return this;
        }

        public Builder gatewayId(String gatewayId) {
            this.gatewayId = gatewayId;
            return this;
        }

        /**
         * Adds authentication configuration for a specific MCP server.
         *
         * @param serverName the MCP server name (as returned by listAllMcpClients)
         * @param authConfig the authentication config for this server
         * @return this builder
         */
        public Builder mcpServerAuthConfig(String serverName, MCPAuthConfig authConfig) {
            this.mcpServerAuthConfigs.put(serverName, authConfig);
            return this;
        }

        /**
         * Sets all per-MCP-server authentication configurations.
         *
         * @param mcpServerAuthConfigs map of server name to auth config
         * @return this builder
         */
        public Builder mcpServerAuthConfigs(Map<String, MCPAuthConfig> mcpServerAuthConfigs) {
            this.mcpServerAuthConfigs =
                    mcpServerAuthConfigs != null
                            ? new HashMap<>(mcpServerAuthConfigs)
                            : new HashMap<>();
            return this;
        }

        public Builder regionId(String regionId) {
            this.regionId = regionId;
            return this;
        }

        /**
         * Sets the gateway base endpoint URL.
         *
         * <p>This is used to construct:
         * <ul>
         *   <li>Tool search endpoint: gatewayEndpoint + /mcp-servers/union-tools-search</li>
         *   <li>MCP client endpoints: gatewayEndpoint + mcpServerPath</li>
         * </ul>
         *
         * <p>Example: {@code http://env-xxx.alicloudapi.com}
         *
         * @param gatewayEndpoint the gateway base endpoint URL
         * @return this builder
         */
        public Builder gatewayEndpoint(String gatewayEndpoint) {
            this.gatewayEndpoint = gatewayEndpoint;
            return this;
        }

        public AIGatewayConfig build() {
            return new AIGatewayConfig(this);
        }
    }
}
