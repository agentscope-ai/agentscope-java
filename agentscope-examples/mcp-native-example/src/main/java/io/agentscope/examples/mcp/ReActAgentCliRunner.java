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
package io.agentscope.examples.mcp;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.Scanner;

/**
 * CLI-based ReActAgent runner using calculator tools.
 *
 * <p>This agent reads user input from stdin and processes it using a ReActAgent
 * with DashScope or OpenAI LLM and calculator tools.
 *
 * <p>Usage with DashScope:
 * <pre>
 *   java -jar target/mcp-native-example.jar \
 *     io.agentscope.examples.mcp.ReActAgentCliRunner
 * </pre>
 *
 * <p>Usage with OpenAI GPT-4:
 * <pre>
 *   java -jar target/mcp-native-example.jar \
 *     io.agentscope.examples.mcp.ReActAgentCliRunner
 * </pre>
 *
 * <p>The agent will automatically detect which API key is available and use the
 * corresponding model. Set either DASHSCOPE_API_KEY or OPENAI_API_KEY environment
 * variable (or both).
 *
 * <p>Example interactions:
 * <pre>
 *   User: What is 5 plus 3?
 *   Agent: [Thinks] The user wants me to add 5 and 3...
 *   Agent: [Calls calculator] ...
 *   Agent: The result of 5 plus 3 is 8.
 * </pre>
 */
public class ReActAgentCliRunner {

    public static void main(String[] args) throws Exception {
        // Detect available API keys from environment variables
        String dashscopeKey = System.getenv("DASHSCOPE_API_KEY");
        String openaiKey = System.getenv("OPENAI_API_KEY");

        // Choose model based on available API keys
        Model model;
        String modelProvider;

        if (dashscopeKey != null && !dashscopeKey.isBlank()) {
            // Use DashScope
            model =
                    DashScopeChatModel.builder()
                            .apiKey(dashscopeKey)
                            .modelName("qwen-plus")
                            .build();
            modelProvider = "DashScope (qwen-plus)";
        } else if (openaiKey != null && !openaiKey.isBlank()) {
            // Use OpenAI GPT model
            model =
                    OpenAIChatModel.builder()
                            .apiKey(openaiKey)
                            .modelName("gpt-5.4-mini-2026-03-17")
                            .build();
            modelProvider = "OpenAI (gpt-5.4-mini-2026-03-17)";
        } else {
            System.err.println("ERROR: No API key configured.");
            System.err.println("Please set either DASHSCOPE_API_KEY or OPENAI_API_KEY.");
            System.exit(1);
            return;
        }

        System.out.println("=== ReActAgent CLI with Calculator Tool ===");
        System.out.println("Model: " + modelProvider);
        System.out.println("Available tools: compute (add, subtract, multiply, divide)");
        System.out.println("Type 'exit' to quit.\n");

        // Create toolkit with calculator adapter
        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(new CalculatorToolAdapter()).apply();

        // Create agent
        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt(
                                "You are a helpful assistant with access to a calculator tool."
                                        + " When asked to perform calculations, use the compute"
                                        + " tool. Always show your reasoning before and after tool"
                                        + " calls.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .maxIters(5)
                        .build();

        // Interactive loop
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.print("User: ");
            while (scanner.hasNextLine()) {
                String userInput = scanner.nextLine().trim();

                if ("exit".equalsIgnoreCase(userInput)) {
                    System.out.println("Goodbye!");
                    break;
                }

                if (userInput.isEmpty()) {
                    System.out.print("User: ");
                    continue;
                }

                try {
                    // Create user message
                    Msg userMsg =
                            Msg.builder()
                                    .name("User")
                                    .role(MsgRole.USER)
                                    .content(TextBlock.builder().text(userInput).build())
                                    .build();

                    System.out.println("\nAgent (thinking...)");

                    // Get agent response (blocking with timeout)
                    Msg response = agent.call(userMsg).timeout(Duration.ofSeconds(30)).block();

                    // Display response
                    if (response != null) {
                        ContentBlock content = response.getFirstContentBlock();
                        if (content instanceof TextBlock) {
                            String text = ((TextBlock) content).getText();
                            System.out.println("Agent: " + text);
                        } else {
                            System.out.println("Agent: " + content);
                        }
                    } else {
                        System.out.println("Agent: (No response)");
                    }
                } catch (Exception e) {
                    System.err.println("Error: " + e.getMessage());
                    e.printStackTrace();
                }

                System.out.print("\nUser: ");
            }
        }
    }
}
