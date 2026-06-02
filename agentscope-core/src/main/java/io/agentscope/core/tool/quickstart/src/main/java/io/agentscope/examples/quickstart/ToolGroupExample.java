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
package io.agentscope.examples.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * ToolGroupExample - Demonstrates agent autonomously managing tool groups with meta-tool.
 */
public class ToolGroupExample {

    public static void main(String[] args) throws Exception {
        // Print welcome message
        ExampleUtils.printWelcome(
                "Tool Group Example - Meta Tool Demo",
                "This example demonstrates agent autonomously managing tool groups.\n"
                        + "The agent can activate tool groups using the reset_equipped_tools"
                        + " meta-tool.");

        // Get API key
        String apiKey = ExampleUtils.getDashScopeApiKey();

        // Configure tool groups
        Toolkit toolkit = configureToolGroups();

        System.out.println("\n=== Meta Tool Registered ===");
        System.out.println(
                "The agent now has access to 'reset_equipped_tools' meta-tool to autonomously");
        System.out.println("activate tool groups based on task requirements.\n");

        // Create agent with meta-tool awareness
        ReActAgent agent =
                ReActAgent.builder()
                        .name("SmartAgent")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .enableMetaTool(true)
                        .memory(new InMemoryMemory())
                        .build();

        // Print example prompts
        printExamplePrompts();

        // Start interactive chat
        ExampleUtils.startChat(agent);
    }

    private static Toolkit configureToolGroups() {
        Toolkit toolkit = new Toolkit();

        // Create file operations group (initially inactive)
        toolkit.createToolGroup("file_ops", "File system operations (read, write, list)", false);
        toolkit.registration().tool(new FileTools()).group("file_ops").apply();

        // Create math operations group (initially inactive)
        toolkit.createToolGroup(
                "math_ops", "Mathematical calculations (factorial, is_prime)", false);
        toolkit.registration().tool(new MathTools()).group("math_ops").apply();

        // Create network operations group (initially inactive)
        toolkit.createToolGroup("network_ops", "Network operations (ping, dns_lookup)", false);
        toolkit.registration().tool(new NetworkTools()).group("network_ops").apply();

        System.out.println("=== Tool Groups Created ===");
        System.out.println("All tool groups start as INACTIVE.");
        System.out.println("The agent will activate them as needed using reset_equipped_tools.\n");
        System.out.println("Available groups:");
        System.out.println("  - file_ops: File operations");
        System.out.println("  - math_ops: Math calculations");
        System.out.println("  - network_ops: Network tools\n");

        return toolkit;
    }

    private static void printExamplePrompts() {
        System.out.println("=== Try These Example Prompts ===\n");

        System.out.println("1. Single tool group activation:");
        System.out.println("   > Calculate the factorial of 5");
        System.out.println("   (Agent will activate math_ops, then use factorial tool)\n");

        System.out.println("2. Different tool group:");
        System.out.println("   > Ping google.com");
        System.out.println("   (Agent will activate network_ops, then use ping tool)\n");

        System.out.println("3. Another tool group:");
        System.out.println("   > List files in /tmp");
        System.out.println("   (Agent will activate file_ops, then use list_files tool)\n");

        System.out.println("4. Multiple tool groups in one task:");
        System.out.println("   > Calculate factorial of 7 and then ping github.com");
        System.out.println("   (Agent will activate both math_ops and network_ops)\n");

        System.out.println("5. Complex task requiring multiple operations:");
        System.out.println("   > Check if 17 is prime, then list files in /tmp");
        System.out.println("   (Agent will activate math_ops and file_ops)\n");

        System.out.println("Watch as the agent:");
        System.out.println("  1. Calls reset_equipped_tools to activate needed tool groups");
        System.out.println("  2. Then calls the specific tools from activated groups");
        System.out.println("==================================\n");
    }

    /** File operation tools. */
    public static class FileTools {

        @Tool(name = "read_file", description = "Read contents of a file")
        public String readFile(
                @ToolParam(name = "path", description = "File path to read") String path) {
            try {
                Path filePath = Paths.get(path);
                if (!Files.exists(filePath)) {
                    return "Error: File not found: " + path;
                }
                return Files.readString(filePath);
            } catch (Exception e) {
                return "Error reading file: " + e.getMessage();
            }
        }

        @Tool(name = "write_file", description = "Write content to a file")
        public String writeFile(
                @ToolParam(name = "path", description = "File path to write") String path,
                @ToolParam(name = "content", description = "Content to write") String content) {
            try {
                Files.writeString(Paths.get(path), content);
                return "Successfully wrote to " + path;
            } catch (Exception e) {
                return "Error writing file: " + e.getMessage();
            }
        }

        @Tool(name = "list_files", description = "List files in a directory")
        public String listFiles(
                @ToolParam(name = "directory", description = "Directory path") String directory) {
            try {
                Path dir = Paths.get(directory);
                if (!Files.isDirectory(dir)) {
                    return "Error: Not a directory: " + directory;
                }

                StringBuilder result = new StringBuilder("Files in " + directory + ":\n");
                Files.list(dir)
                        .forEach(
                                path ->
                                        result.append(
                                                "  - " + path.getFileName().toString() + "\n"));
                return result.toString();
            } catch (Exception e) {
                return "Error listing directory: " + e.getMessage();
            }
        }
    }

    /** Mathematical operation tools. */
    public static class MathTools {

        @Tool(name = "factorial", description = "Calculate factorial of a number")
        public String factorial(
                @ToolParam(name = "n", description = "Number to calculate factorial") Integer n) {
            if (n < 0) {
                return "Error: Factorial not defined for negative numbers";
            }
            if (n > 20) {
                return "Error: Number too large (max 20)";
            }

            long result = 1;
            for (int i = 2; i <= n; i++) {
                result *= i;
            }
            return String.format("factorial(%d) = %d", n, result);
        }

        @Tool(name = "is_prime", description = "Check if a number is prime")
        public String isPrime(@ToolParam(name = "n", description = "Number to check") Integer n) {
            if (n < 2) {
                return n + " is not a prime number";
            }

            for (int i = 2; i <= Math.sqrt(n); i++) {
                if (n % i == 0) {
                    return n + " is not a prime number";
                }
            }
            return n + " is a prime number";
        }
    }

    /** Network operation tools. */
    public static class NetworkTools {

        @Tool(name = "ping", description = "Ping a host (simulated)")
        public String ping(@ToolParam(name = "host", description = "Host to ping") String host) {
            // Simulated ping
            int latency = (int) (Math.random() * 100) + 10;
            return String.format("Pinging %s: latency = %dms (simulated)", host, latency);
        }

        @Tool(name = "dns_lookup", description = "Look up DNS record (simulated)")
        public String dnsLookup(
                @ToolParam(name = "domain", description = "Domain name") String domain) {
            // Simulated DNS lookup
            return String.format("DNS lookup for %s:\n  A record: 192.168.1.1 (simulated)", domain);
        }
    }
}
