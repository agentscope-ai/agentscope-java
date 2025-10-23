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
package io.agentscope.examples;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * McpToolExample - Demonstrates MCP (Model Context Protocol) tool integration.
 *
 * <p>This example shows:
 *
 * <ul>
 *   <li>Interactive MCP server configuration
 *   <li>Connecting to MCP servers (StdIO, SSE, HTTP)
 *   <li>Using external tools provided by MCP servers
 * </ul>
 *
 * <p>Run:
 *
 * <pre>
 * mvn exec:java -Dexec.mainClass="io.agentscope.examples.McpToolExample"
 * </pre>
 *
 * <p>Requirements:
 *
 * <ul>
 *   <li>MCP server installed (e.g., npm install -g @modelcontextprotocol/server-filesystem)
 * </ul>
 */
public class McpToolExample {

    private static final BufferedReader reader =
            new BufferedReader(new InputStreamReader(System.in));

    public static void main(String[] args) throws Exception {
        // Print welcome message
        ExampleUtils.printWelcome(
                "MCP Tool Example",
                "This example demonstrates MCP (Model Context Protocol) integration.\n"
                        + "MCP allows agents to use external tool servers like filesystem, git,"
                        + " databases, etc.");

        // Get API key
        String apiKey = ExampleUtils.getDashScopeApiKey();

        // Interactive MCP configuration
        McpClientWrapper mcpClient = configureMcp();

        // Register MCP tools
        Toolkit toolkit = new Toolkit();
        System.out.print("Registering MCP tools...");
        toolkit.registerMcpClient(mcpClient).block();
        System.out.println(" ✓ Done\n");

        // Create Agent
        ReActAgent agent =
                ReActAgent.builder()
                        .name("McpAgent")
                        .sysPrompt(
                                "You are a helpful assistant with access to MCP tools. "
                                        + "Use the available tools to help users with their"
                                        + " requests.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(false)
                                        .defaultOptions(new GenerateOptions())
                                        .build())
                        .formatter(new DashScopeChatFormatter())
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .build();

        // Start chat
        ExampleUtils.startChat(agent);
    }

    private static McpClientWrapper configureMcp() throws Exception {
        System.out.println("Choose MCP transport type:");
        System.out.println("  1) StdIO - Local process (recommended for testing)");
        System.out.println("  2) SSE - HTTP Server-Sent Events");
        System.out.println("  3) HTTP - Streamable HTTP");
        System.out.print("\nChoice [1-3]: ");

        String choice = reader.readLine().trim();
        if (choice.isEmpty()) {
            choice = "1";
        }

        switch (choice) {
            case "1":
                return configureStdioMcp();
            case "2":
                return configureSseMcp();
            case "3":
                return configureHttpMcp();
            default:
                System.out.println("Invalid choice, using StdIO");
                return configureStdioMcp();
        }
    }

    private static McpClientWrapper configureStdioMcp() throws Exception {
        System.out.println("\n--- StdIO Configuration ---\n");

        System.out.print("Command (default: npx): ");
        String command = reader.readLine().trim();
        if (command.isEmpty()) {
            command = "npx";
        }

        System.out.println("\nCommon MCP servers:");
        System.out.println("  1) Filesystem - Access local files");
        System.out.println("  2) Git - Git operations");
        System.out.println("  3) Custom - Enter manually");
        System.out.print("\nChoice [1-3]: ");

        String serverChoice = reader.readLine().trim();
        String[] args;

        switch (serverChoice) {
            case "1":
                System.out.print("Directory path (default: /tmp): ");
                String path = reader.readLine().trim();
                if (path.isEmpty()) {
                    path = "/tmp";
                }
                args = new String[] {"-y", "@modelcontextprotocol/server-filesystem", path};
                break;

            case "2":
                args = new String[] {"-y", "@modelcontextprotocol/server-git"};
                break;

            default:
                System.out.print("Arguments (comma-separated): ");
                String argsStr = reader.readLine().trim();
                if (argsStr.isEmpty()) {
                    args = new String[] {"-y", "@modelcontextprotocol/server-filesystem", "/tmp"};
                } else {
                    args = argsStr.split(",");
                }
        }

        System.out.print("\nConnecting to MCP server...");

        try {
            McpClientWrapper client =
                    McpClientBuilder.create("mcp")
                            .stdioTransport(command, args)
                            .buildAsync()
                            .block();

            System.out.println(" ✓ Connected!\n");
            return client;

        } catch (Exception e) {
            System.err.println(" ✗ Failed to connect");
            System.err.println("Error: " + e.getMessage());
            System.err.println(
                    "\nMake sure the MCP server is installed. For filesystem server, run:");
            System.err.println("  npm install -g @modelcontextprotocol/server-filesystem");
            throw e;
        }
    }

    private static McpClientWrapper configureSseMcp() throws Exception {
        System.out.println("\n--- SSE Configuration ---\n");

        System.out.print("Server URL: ");
        String url = reader.readLine().trim();

        if (url.isEmpty()) {
            System.err.println("Error: URL required for SSE transport");
            return configureStdioMcp();
        }

        McpClientBuilder builder = McpClientBuilder.create("mcp").sseTransport(url);

        System.out.print("Add Authorization header? (y/n): ");
        if (reader.readLine().trim().equalsIgnoreCase("y")) {
            System.out.print("Token: ");
            String token = reader.readLine().trim();
            builder.header("Authorization", "Bearer " + token);
        }

        System.out.print("\nConnecting to MCP server...");

        try {
            McpClientWrapper client = builder.buildAsync().block();
            System.out.println(" ✓ Connected!\n");
            return client;

        } catch (Exception e) {
            System.err.println(" ✗ Failed to connect");
            System.err.println("Error: " + e.getMessage());
            throw e;
        }
    }

    private static McpClientWrapper configureHttpMcp() throws Exception {
        System.out.println("\n--- HTTP Configuration ---\n");

        System.out.print("Server URL: ");
        String url = reader.readLine().trim();

        if (url.isEmpty()) {
            System.err.println("Error: URL required for HTTP transport");
            return configureStdioMcp();
        }

        System.out.print("\nConnecting to MCP server...");

        try {
            McpClientWrapper client =
                    McpClientBuilder.create("mcp")
                            .streamableHttpTransport(url)
                            .buildAsync()
                            .block();

            System.out.println(" ✓ Connected!\n");
            return client;

        } catch (Exception e) {
            System.err.println(" ✗ Failed to connect");
            System.err.println("Error: " + e.getMessage());
            throw e;
        }
    }
}
