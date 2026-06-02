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
 * McpStdioExample - MCP (Model Context Protocol) integration via stdio subprocess.
 *
 * <p>Stdio transport spawns a local process and communicates over its stdin/stdout.
 * It is the most common transport for local MCP servers such as the official
 * {@code @modelcontextprotocol/server-filesystem} and {@code @modelcontextprotocol/server-git}.
 *
 * <p><b>Prerequisites:</b>
 * <pre>
 *   npm install -g @modelcontextprotocol/server-filesystem
 * </pre>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation2 \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.mcp.McpStdioExample
 * </pre>
 */
public class McpStdioExample {

    /**
     * Runs the stdio MCP example.
     *
     * @param args command-line arguments (ignored)
     * @throws Exception if MCP server startup fails
     */
    public static void main(String[] args) throws Exception {
        ExampleUtils.printWelcome(
                "MCP Stdio Example",
                "Connects to the official MCP filesystem server via stdio.\n"
                        + "The agent can list files, read content, and navigate directories.");

        String apiKey = ExampleUtils.getDashScopeApiKey();

        // ── Build MCP client with stdio transport ─────────────────────────────────────
        //
        // McpClientBuilder.create(name) — name identifies the server in toolkit registration.
        // stdioTransport(command, args...) — spawns: "npx -y
        // @modelcontextprotocol/server-filesystem /tmp"
        System.out.print("Starting MCP filesystem server ...");
        McpClientWrapper mcpClient =
                McpClientBuilder.create("filesystem")
                        .stdioTransport(
                                "npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
                        .buildAsync()
                        .block();
        System.out.println(" Connected!\n");

        Toolkit toolkit = new Toolkit();
        System.out.print("Registering MCP tools ...");
        toolkit.registerMcpClient(mcpClient).block();
        System.out.println(" Done (registered: " + toolkit.getToolNames() + ")\n");

        ReActAgent agent =
                ReActAgent.builder()
                        .name("FilesystemAgent")
                        .sysPrompt(
                                "You are a filesystem assistant. Use the available MCP tools to "
                                        + "help the user navigate and read files under /tmp.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .build();

        System.out.println("Try: 'List files in /tmp' or 'What is in /tmp/test.txt?'\n");
        ExampleUtils.startChat(agent);
    }
}
