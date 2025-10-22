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
package io.agentscope.examples.interrupt;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;

/**
 * Example demonstrating user-initiated interruption of agent execution.
 *
 * <p>This example shows:
 * <ul>
 *   <li>How to interrupt a long-running agent from another thread</li>
 *   <li>How the agent generates fake tool results for interrupted tool calls</li>
 *   <li>How the agent returns a user-friendly recovery message</li>
 *   <li>How hooks are notified about fake tool results</li>
 * </ul>
 *
 * <p>Usage:
 * Set the DASHSCOPE_API_KEY environment variable before running:
 * <pre>
 * export DASHSCOPE_API_KEY=your_api_key_here
 * mvn exec:java -Dexec.mainClass="io.agentscope.examples.interrupt.UserInterruptExample"
 * </pre>
 */
public class UserInterruptExample {

    /**
     * A simulated long-running tool that processes data.
     * This tool will be interrupted before it completes.
     */
    public static class DataProcessor {
        @Tool(name = "process_large_dataset", description = "Process a large dataset (simulated)")
        public String processDataset(
                @ToolParam(name = "dataset_name", description = "Name of the dataset")
                        String datasetName,
                @ToolParam(name = "operation", description = "Operation to perform")
                        String operation) {
            // Simulate long-running operation
            System.out.println(
                    "[Tool] Starting to process dataset: "
                            + datasetName
                            + " with operation: "
                            + operation);

            try {
                // Simulate processing time
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Processing interrupted";
            }

            return "Successfully processed "
                    + datasetName
                    + " with operation: "
                    + operation
                    + ". Results: 1000 records updated.";
        }
    }

    /**
     * Hook to monitor agent execution and tool results.
     */
    static class MonitoringHook implements Hook {
        @Override
        public Mono<Void> onStart(io.agentscope.core.agent.Agent agent) {
            System.out.println("\n[Hook] Agent started: " + agent.getName());
            return Mono.empty();
        }

        @Override
        public Mono<ToolUseBlock> onToolCall(
                io.agentscope.core.agent.Agent agent, ToolUseBlock toolUse) {
            System.out.println("[Hook] Tool call: " + toolUse.getName());
            System.out.println("       Input: " + toolUse.getInput());
            return Mono.just(toolUse);
        }

        @Override
        public Mono<ToolResultBlock> onToolResult(
                io.agentscope.core.agent.Agent agent,
                ToolUseBlock toolUse,
                ToolResultBlock toolResult) {
            System.out.println("[Hook] Tool result for: " + toolUse.getName());
            System.out.println("       Output: " + extractOutput(toolResult));
            return Mono.just(toolResult);
        }

        @Override
        public Mono<Msg> onComplete(io.agentscope.core.agent.Agent agent, Msg finalMsg) {
            System.out.println("[Hook] Agent completed");
            System.out.println("       Final message: " + extractText(finalMsg));
            return Mono.just(finalMsg);
        }

        @Override
        public Mono<Void> onError(io.agentscope.core.agent.Agent agent, Throwable error) {
            System.out.println("[Hook] Error occurred: " + error.getMessage());
            return Mono.empty();
        }

        private String extractOutput(ToolResultBlock toolResult) {
            if (toolResult.getOutput() instanceof TextBlock tb) {
                return tb.getText();
            }
            return toolResult.getOutput().toString();
        }

        private String extractText(Msg msg) {
            if (msg.getContent() instanceof TextBlock tb) {
                return tb.getText();
            }
            return msg.getContent().toString();
        }
    }

    public static void main(String[] args) {
        // Check for API key
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println(
                    "Error: DASHSCOPE_API_KEY environment variable not set.\n"
                            + "Please set it with: export DASHSCOPE_API_KEY=your_api_key_here");
            System.exit(1);
        }

        System.out.println("=== User Interrupt Example ===\n");

        // Create model
        DashScopeChatModel model =
                DashScopeChatModel.builder()
                        .apiKey(apiKey)
                        .modelName("qwen-plus")
                        .stream(false)
                        .build();

        // Create toolkit and register the long-running tool
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DataProcessor());

        // Create agent with monitoring hook
        ReActAgent agent =
                ReActAgent.builder()
                        .name("DataAgent")
                        .sysPrompt(
                                "You are a data processing assistant. Use the"
                                        + " process_large_dataset tool to process datasets.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .hook(new MonitoringHook())
                        .maxIters(10)
                        .build();

        // Create user message
        Msg userMsg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "Please process the 'customer_data' dataset with"
                                                        + " 'analyze' operation.")
                                        .build())
                        .build();

        System.out.println("User: " + ((TextBlock) userMsg.getContent()).getText());
        System.out.println("\nStarting agent execution...");
        System.out.println(
                "Note: Agent will be interrupted after 2 seconds to demonstrate interruption"
                        + " handling.\n");

        // Start agent in a separate thread
        Thread agentThread =
                new Thread(
                        () -> {
                            try {
                                Msg response = agent.call(userMsg).block();
                                System.out.println(
                                        "\n[Main] Agent response: "
                                                + ((TextBlock) response.getContent()).getText());
                            } catch (Exception e) {
                                System.out.println("[Main] Exception: " + e.getMessage());
                            }
                        });
        agentThread.start();

        // Wait 2 seconds then interrupt the agent
        try {
            Thread.sleep(2000);
            System.out.println("\n>>> USER INTERRUPTS AGENT <<<\n");

            // Create interrupt message
            Msg interruptMsg =
                    Msg.builder()
                            .name("User")
                            .role(MsgRole.USER)
                            .content(
                                    TextBlock.builder()
                                            .text("Stop! I need to change the dataset name.")
                                            .build())
                            .build();

            // Interrupt the agent
            agent.interrupt(interruptMsg);

            // Wait for agent thread to complete
            agentThread.join();

            System.out.println("\n=== Interrupt Demo Completed ===");
            System.out.println(
                    "\nWhat happened:");
            System.out.println(
                    "1. Agent started processing with the process_large_dataset tool");
            System.out.println("2. User interrupted the agent after 2 seconds");
            System.out.println(
                    "3. Agent generated fake tool results for interrupted tool calls");
            System.out.println("4. Hooks were notified about the fake tool results");
            System.out.println("5. Agent returned a user-friendly recovery message");
            System.out.println(
                    "\nCheck the memory to see fake tool results and recovery message:");
            System.out.println("Memory size: " + agent.getMemory().getMessages().size());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Main thread interrupted: " + e.getMessage());
        }
    }
}
