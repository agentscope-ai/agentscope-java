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
package io.agentscope.examples.documentation2.tool;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * ToolGroupExample - Demonstrates agent autonomously managing tool groups with a meta-tool.
 *
 * <p>Migration notes:
 * <ul>
 *   <li>Removed {@code .memory(new InMemoryMemory())} — not required in 2.0.</li>
 * </ul>
 */
public class ToolGroupExample {

    /**
     * Runs the tool group example.
     *
     * @param args command-line arguments (ignored)
     * @throws Exception if an I/O error occurs
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Tool Group Example - Meta Tool Demo");
        System.out.println("=".repeat(60));
        System.out.println(
                "This example demonstrates agent autonomously managing tool groups.\n"
                        + "The agent can activate tool groups using the reset_equipped_tools"
                        + " meta-tool.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        Toolkit toolkit = configureToolGroups();

        System.out.println("\n=== Meta Tool Registered ===");
        System.out.println(
                "The agent now has access to 'reset_equipped_tools' to activate tool groups.\n");

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
                        .middleware(new AgentTraceMiddleware())
                        .build();

        printExamplePrompts();
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

    private static Toolkit configureToolGroups() {
        Toolkit toolkit = new Toolkit();

        toolkit.createToolGroup("file_ops", "File system operations (read, write, list)", false);
        toolkit.registration().tool(new FileTools()).group("file_ops").apply();

        toolkit.createToolGroup(
                "math_ops", "Mathematical calculations (factorial, is_prime)", false);
        toolkit.registration().tool(new MathTools()).group("math_ops").apply();

        toolkit.createToolGroup("network_ops", "Network operations (ping, dns_lookup)", false);
        toolkit.registration().tool(new NetworkTools()).group("network_ops").apply();

        System.out.println("=== Tool Groups Created ===");
        System.out.println("All tool groups start as INACTIVE.");
        System.out.println("The agent activates them as needed via reset_equipped_tools.\n");
        System.out.println("Available groups:");
        System.out.println("  - file_ops: File operations");
        System.out.println("  - math_ops: Math calculations");
        System.out.println("  - network_ops: Network tools\n");

        return toolkit;
    }

    private static void printExamplePrompts() {
        System.out.println("=== Try These Example Prompts ===\n");
        System.out.println("1. > Calculate the factorial of 5");
        System.out.println("   (Agent activates math_ops, then calls factorial)\n");
        System.out.println("2. > Ping google.com");
        System.out.println("   (Agent activates network_ops, then calls ping)\n");
        System.out.println("3. > List files in /tmp");
        System.out.println("   (Agent activates file_ops, then calls list_files)\n");
        System.out.println("4. > Calculate factorial of 7 and then ping github.com");
        System.out.println("   (Agent activates both math_ops and network_ops)\n");
        System.out.println("==================================\n");
    }

    /** File operation tools for the file_ops group. */
    public static class FileTools {

        /**
         * Reads the contents of a file.
         *
         * @param path file path to read
         * @return file contents or error message
         */
        @Tool(name = "read_file", description = "Read contents of a file")
        public String readFile(@ToolParam(name = "path", description = "File path") String path) {
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

        /**
         * Writes content to a file.
         *
         * @param path    file path to write
         * @param content content to write
         * @return success or error message
         */
        @Tool(name = "write_file", description = "Write content to a file")
        public String writeFile(
                @ToolParam(name = "path", description = "File path") String path,
                @ToolParam(name = "content", description = "Content to write") String content) {
            try {
                Files.writeString(Paths.get(path), content);
                return "Successfully wrote to " + path;
            } catch (Exception e) {
                return "Error writing file: " + e.getMessage();
            }
        }

        /**
         * Lists files in a directory.
         *
         * @param directory directory path
         * @return listing or error message
         */
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
                        .forEach(p -> result.append("  - ").append(p.getFileName()).append("\n"));
                return result.toString();
            } catch (Exception e) {
                return "Error listing directory: " + e.getMessage();
            }
        }
    }

    /** Mathematical operation tools for the math_ops group. */
    public static class MathTools {

        /**
         * Calculates the factorial of a non-negative integer.
         *
         * @param n number (0–20)
         * @return factorial result string
         */
        @Tool(name = "factorial", description = "Calculate factorial of a number")
        public String factorial(@ToolParam(name = "n", description = "Number (0-20)") Integer n) {
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

        /**
         * Checks whether a number is prime.
         *
         * @param n the number to check
         * @return primality result string
         */
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

    /** Network operation tools for the network_ops group. */
    public static class NetworkTools {

        /**
         * Simulates a ping to a host.
         *
         * @param host host to ping
         * @return simulated ping result
         */
        @Tool(name = "ping", description = "Ping a host (simulated)")
        public String ping(@ToolParam(name = "host", description = "Host to ping") String host) {
            int latency = (int) (Math.random() * 100) + 10;
            return String.format("Pinging %s: latency = %dms (simulated)", host, latency);
        }

        /**
         * Simulates a DNS lookup for a domain.
         *
         * @param domain domain name
         * @return simulated DNS record
         */
        @Tool(name = "dns_lookup", description = "Look up DNS record (simulated)")
        public String dnsLookup(
                @ToolParam(name = "domain", description = "Domain name") String domain) {
            return String.format("DNS lookup for %s:\n  A record: 192.168.1.1 (simulated)", domain);
        }
    }
}
