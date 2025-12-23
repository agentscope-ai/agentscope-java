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
package io.agentscope.examples.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.aigateway.AIGatewayClient;
import io.agentscope.extensions.aigateway.config.AIGatewayConfig;
import io.agentscope.extensions.aigateway.config.MCPAuthConfig;
import java.util.List;

// Import ExampleUtils

/**
 * AIGatewayMcpServersExample - List all MCP servers from AI Gateway and register with agent.
 *
 * <p>This example demonstrates:
 * <ul>
 *   <li>Using Aliyun SDK to list all MCP servers on the gateway</li>
 *   <li>Automatically connecting to each deployed MCP server</li>
 *   <li>Configuring per-MCP-server authentication</li>
 *   <li>Registering all MCP clients with a ReActAgent</li>
 * </ul>
 *
 * <p>Authentication Configuration:
 * <ul>
 *   <li>union-tools-search: Bearer token "12345678"</li>
 *   <li>map: Bearer token "87654321"</li>
 * </ul>
 */
public class AIGatewayMcpServersExample {

    // ========================================================

    public static void main(String[] args) throws Exception {

        System.out.println("=== AI Gateway MCP Servers Example ===\n");

        // Get configuration from environment variables
        String accessKeyId = System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID");
        if (accessKeyId == null || accessKeyId.isEmpty()) {
            System.err.println(
                    "Error: ALIBABA_CLOUD_ACCESS_KEY_ID environment variable is required.");
            System.exit(1);
        }

        String accessKeySecret = System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET");
        if (accessKeySecret == null || accessKeySecret.isEmpty()) {
            System.err.println(
                    "Error: ALIBABA_CLOUD_ACCESS_KEY_SECRET environment variable is required.");
            System.exit(1);
        }

        // Gateway endpoint is required to construct MCP endpoint URLs
        // Format: gatewayEndpoint + mcpServerPath
        // Example: http://env-xxx.alicloudapi.com + /mcp-servers/map
        String gatewayEndpoint = System.getenv("AI_GATEWAY_ENDPOINT");
        if (gatewayEndpoint == null || gatewayEndpoint.isEmpty()) {
            gatewayEndpoint = "http://env-d4t3msem1hkpf1bddfeg-cn-hangzhou.alicloudapi.com";
        }

        // Build configuration with per-server authentication (no default auth)
        // Each MCP server has its own authentication token
        AIGatewayConfig config =
                AIGatewayConfig.builder()
                        .accessKeyId(accessKeyId)
                        .accessKeySecret(accessKeySecret)
                        .gatewayId("gw-d4t3m8em1hkqs3u1fhj0")
                        .regionId("cn-hangzhou")
                        .gatewayEndpoint(gatewayEndpoint)
                        // mcp server auth
                        .mcpServerAuthConfig("union-tools-search", MCPAuthConfig.bearer("12345678"))
                        .build();

        try (AIGatewayClient gatewayClient = new AIGatewayClient(config)) {
            // Step 1: Get all MCP clients (automatically connects to deployed servers)
            System.out.println("Fetching and connecting to MCP servers...\n");
            List<McpClientWrapper> mcpClients = gatewayClient.listAllMcpClients();

            if (mcpClients.isEmpty()) {
                System.out.println("No MCP servers connected. Exiting.");
                return;
            }

            System.out.println("\nConnecting to " + mcpClients.size() + " MCP servers:");
            for (McpClientWrapper client : mcpClients) {
                System.out.println("  - " + client.getName());
            }
            System.out.println();

            // Step 2: Register all MCP clients with toolkit
            Toolkit toolkit = new Toolkit();
            for (McpClientWrapper client : mcpClients) {
                toolkit.registerMcpClient(client).block();
            }
            System.out.println("Registered " + mcpClients.size() + " MCP clients with toolkit.\n");

            // Step 3: Create and run agent
            String apiKey = ExampleUtils.getDashScopeApiKey();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("GatewayMcpAgent")
                            .description("Agent with all MCP servers from gateway")
                            .sysPrompt("You are a helpful AI assistant. Use tools to help users.")
                            .model(
                                    DashScopeChatModel.builder()
                                            .apiKey(apiKey)
                                            .modelName("qwen-max")
                                            .stream(true)
                                            .enableThinking(false)
                                            .formatter(new DashScopeChatFormatter())
                                            .build())
                            .toolkit(toolkit)
                            .memory(new InMemoryMemory())
                            .build();

            System.out.println("=== Agent Ready ===\n");
            ExampleUtils.startChat(agent);
        }
    }
}
