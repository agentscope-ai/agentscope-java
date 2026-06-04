/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.examples.documentation2.mcp;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.examples.documentation2.common.ExampleUtils;

/**
 * McpStreamableHttpExample - MCP integration via Streamable HTTP transport.
 *
 * <p>Streamable HTTP is a newer transport that uses a single HTTP endpoint with streaming
 * responses. Unlike SSE, it does not require a persistent connection and works well with
 * standard HTTP infrastructure (proxies, load balancers, API gateways).
 *
 * <p><b>Configuration:</b>
 * <pre>
 *   export MCP_HTTP_URL=http://localhost:3000/mcp
 *   export MCP_HTTP_API_KEY=optional_api_key
 * </pre>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation2 \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.mcp.McpStreamableHttpExample
 * </pre>
 */
public class McpStreamableHttpExample {

    /**
     * Runs the streamable HTTP MCP example.
     *
     * @param args command-line arguments (ignored)
     * @throws Exception if the MCP connection fails
     */
    public static void main(String[] args) throws Exception {
        ExampleUtils.printWelcome(
                "MCP Streamable HTTP Example",
                "Connects to an MCP server via the Streamable HTTP transport.\n"
                        + "Set MCP_HTTP_URL to the server endpoint before running.");

        String apiKey = ExampleUtils.getDashScopeApiKey();

        String httpUrl = System.getenv("MCP_HTTP_URL");
        if (httpUrl == null || httpUrl.isBlank()) {
            httpUrl = "http://localhost:3000/mcp";
            System.out.println("MCP_HTTP_URL not set — using default: " + httpUrl);
        }

        // ── Build MCP client with streamable HTTP transport ───────────────────────────
        //
        // streamableHttpTransport(url) — connects to the HTTP streaming endpoint.
        // header(name, value) — adds an HTTP request header (e.g. X-API-Key).
        McpClientBuilder builder =
                McpClientBuilder.create("http-server").streamableHttpTransport(httpUrl);

        String httpApiKey = System.getenv("MCP_HTTP_API_KEY");
        if (httpApiKey != null && !httpApiKey.isBlank()) {
            builder.header("X-API-Key", httpApiKey);
            System.out.println("X-API-Key header added.");
        }

        System.out.print("Connecting to streamable HTTP MCP server at " + httpUrl + " ...");
        McpClientWrapper mcpClient = builder.buildAsync().block();
        System.out.println(" Connected!\n");

        Toolkit toolkit = new Toolkit();
        System.out.print("Registering MCP tools ...");
        toolkit.registerMcpClient(mcpClient).block();
        System.out.println(" Done (registered: " + toolkit.getToolNames() + ")\n");

        ReActAgent agent =
                ReActAgent.builder()
                        .name("HttpMcpAgent")
                        .sysPrompt(
                                "You are a helpful assistant with access to tools via MCP over"
                                        + " HTTP.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .build();

        ExampleUtils.startChat(agent);
    }
}
