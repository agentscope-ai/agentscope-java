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
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * McpSseExample - MCP (Model Context Protocol) integration via SSE (Server-Sent Events).
 *
 * <p>SSE transport connects to a running MCP server over HTTP using the
 * Server-Sent Events protocol. This transport is suited for remote or shared MCP servers
 * accessible via an HTTP endpoint.
 *
 * <p><b>Configuration:</b>
 * Set the {@code MCP_SSE_URL} environment variable to the server endpoint, e.g.:
 * <pre>
 *   export MCP_SSE_URL=http://localhost:3000/sse
 *   export MCP_SSE_TOKEN=optional_bearer_token
 * </pre>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation2 \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.mcp.McpSseExample
 * </pre>
 */
public class McpSseExample {

    /**
     * Runs the SSE MCP example.
     *
     * @param args command-line arguments (ignored)
     * @throws Exception if the MCP connection fails
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("MCP SSE Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Connects to a remote MCP server via Server-Sent Events (SSE).\n"
                        + "Set MCP_SSE_URL to the server endpoint before running.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        String sseUrl = System.getenv("MCP_SSE_URL");
        if (sseUrl == null || sseUrl.isBlank()) {
            sseUrl = "http://localhost:3000/sse";
            System.out.println("MCP_SSE_URL not set — using default: " + sseUrl);
        }

        // ── Build MCP client with SSE transport ───────────────────────────────────────
        //
        // sseTransport(url) — connects to the SSE endpoint at the given URL.
        // header(name, value) — adds an HTTP request header (e.g. Authorization).
        McpClientBuilder builder = McpClientBuilder.create("remote-server").sseTransport(sseUrl);

        String token = System.getenv("MCP_SSE_TOKEN");
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
            System.out.println("Authorization header added.");
        }

        System.out.print("Connecting to SSE MCP server at " + sseUrl + " ...");
        McpClientWrapper mcpClient = builder.buildAsync().block();
        System.out.println(" Connected!\n");

        Toolkit toolkit = new Toolkit();
        System.out.print("Registering MCP tools ...");
        toolkit.registerMcpClient(mcpClient).block();
        System.out.println(" Done (registered: " + toolkit.getToolNames() + ")\n");

        ReActAgent agent =
                ReActAgent.builder()
                        .name("SseAgent")
                        .sysPrompt(
                                "You are a helpful assistant with access to remote tools via MCP.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .build();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Chat started. Type 'exit' to quit.\n");

        while (true) {
            System.out.print("You: ");
            String input = reader.readLine();
            if (input == null || input.trim().equalsIgnoreCase("exit")) {
                System.out.println("\nGoodbye!");
                break;
            }
            if (input.isBlank()) {
                continue;
            }
            Msg userMsg = new UserMessage(input.trim());
            System.out.print("\nAgent: ");
            agent.streamEvents(userMsg)
                    .doOnNext(
                            event -> {
                                if (event instanceof TextBlockDeltaEvent e) {
                                    System.out.print(e.getDelta());
                                }
                            })
                    .blockLast();
            System.out.println("\n");
        }
    }
}
