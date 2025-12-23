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
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.aigateway.AIGatewayClient;
import io.agentscope.extensions.aigateway.config.AIGatewayConfig;
import java.util.List;

/**
 * AIGatewaySearchToolExample - Demonstrates AI Gateway tool search and agent integration.
 *
 * <p>This example shows how to:
 * <ul>
 *   <li>Use AI Gateway tool search to find relevant tools based on a semantic query</li>
 *   <li>Connect to the AI Gateway MCP endpoint to execute tools</li>
 *   <li>Register searched tools with a ReActAgent</li>
 *   <li>Enable the agent to automatically use the searched tools</li>
 * </ul>
 *
 * <p>The tool search endpoint is automatically constructed as:
 * gatewayEndpoint + /mcp-servers/union-tools-search
 */
public class AIGatewaySearchToolExample {

    // ==================== Configuration - Please modify these values ====================

    /**
     * AI Gateway base endpoint URL.
     * Tool search endpoint will be: gatewayEndpoint + /mcp-servers/union-tools-search
     */
    private static final String GATEWAY_ENDPOINT = "replace your ai gateway endpoint here";

    public static void main(String[] args) throws Exception {
        System.out.println("=== AI Gateway Tool Search Example ===\n");

        // Step 1: Configure AI Gateway
        // Tool search endpoint is automatically constructed from gatewayEndpoint
        AIGatewayConfig gatewayConfig =
                AIGatewayConfig.builder()
                        .gatewayEndpoint(GATEWAY_ENDPOINT)
                        // mcpServerAuthConfig("union-tools-search",
                        // MCPAuthConfig.query("test","12345678"))
                        // mcpServerAuthConfig("union-tools-search", MCPAuthConfig.bearer("token1"))
                        // mcpServerAuthConfig("union-tools-search",
                        // MCPAuthConfig.header("test","12345678"))
                        .build();

        String agentDescription = "replace your agent description here";

        // Step 2: Search for relevant tools (returns executable AgentTools directly)
        try (AIGatewayClient gatewayClient = new AIGatewayClient(gatewayConfig)) {
            // Step 2: Search for relevant tools (returns executable AgentTools directly)
            List<AgentTool> tools = gatewayClient.listSearchedTools(agentDescription, 5);

            if (tools.isEmpty()) {
                System.out.println("No tools found matching your query.");
            } else {
                System.out.println("Found " + tools.size() + " tools:\n");
            }

            // Step 3: Register tools with toolkit
            Toolkit toolkit = new Toolkit();
            for (AgentTool tool : tools) {
                toolkit.registration().agentTool(tool).apply();
            }
            System.out.println("Registered " + tools.size() + " tools with toolkit.\n");

            // Step 4: Create ReActAgent with the toolkit

            String apiKey = ExampleUtils.getDashScopeApiKey();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("AIGatewayAgent")
                            .description(agentDescription)
                            .sysPrompt("You are a helpful AI assistant. Be friendly and concise")
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

            ExampleUtils.startChat(agent);
        }
    }
}
