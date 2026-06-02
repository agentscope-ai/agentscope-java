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
package io.agentscope.examples.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.quickstart.util.MsgUtils;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * InterruptionExample - Demonstrates agent interruption mechanism.
 */
public class InterruptionExample {

    public static void main(String[] args) throws Exception {
        // Print welcome message
        ExampleUtils.printWelcome(
                "Interruption Example",
                "This example demonstrates user-initiated interruption of agent execution.\n"
                        + "The agent will start a long-running task and be interrupted after 2"
                        + " seconds.");

        // Get API key
        String apiKey = ExampleUtils.getDashScopeApiKey();

        // Create toolkit with long-running tool
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new LongRunningTools());

        // Create agent with monitoring hook
        ReActAgent agent =
                ReActAgent.builder()
                        .name("DataAgent")
                        .sysPrompt(
                                "You are a data processing assistant. "
                                        + "Use the process_large_dataset tool to process datasets.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(false)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .hooks(List.of(new MonitoringHook()))
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

        System.out.println("\nUser: " + MsgUtils.getTextContent(userMsg));
        System.out.println("\nStarting agent execution...");
        System.out.println(
                "The agent will be interrupted after 2 seconds to demonstrate interruption"
                        + " handling.\n");

        // Start agent in a separate thread
        Thread agentThread =
                new Thread(
                        () -> {
                            try {
                                Msg response = agent.call(userMsg).block();
                                if (response != null) {
                                    System.out.println(
                                            "\n[Agent Response] "
                                                    + MsgUtils.getTextContent(response));
                                }
                            } catch (Exception e) {
                                System.err.println("[Error] " + e.getMessage());
                            }
                        });

        agentThread.start();

        // Wait 2 seconds then interrupt
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

            System.out.println("\n=== Interruption Demo Completed ===");
            System.out.println("\nWhat happened:");
            System.out.println("1. Agent started processing with the process_large_dataset tool");
            System.out.println("2. User interrupted the agent after 2 seconds");
            System.out.println("3. Agent generated fake tool results for interrupted calls");
            System.out.println("4. Hooks were notified about the fake tool results");
            System.out.println("5. Agent returned a user-friendly recovery message");
            System.out.println(
                    "\nMemory contains "
                            + agent.getMemory().getMessages().size()
                            + " messages including fake results and recovery.");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Main thread interrupted: " + e.getMessage());
        }
    }

    /** Monitoring hook to track agent execution and interruption. */
    static class MonitoringHook implements Hook {

        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            if (event instanceof PreCallEvent preCall) {
                System.out.println("[Hook] Agent preCall: " + preCall.getAgent().getName());

            } else if (event instanceof PreActingEvent preActing) {
                System.out.println("[Hook] Tool call: " + preActing.getToolUse().getName());
                System.out.println("       Input: " + preActing.getToolUse().getInput());

            } else if (event instanceof ActingChunkEvent actingChunk) {
                ToolResultBlock chunk = actingChunk.getChunk();
                String output =
                        chunk.getOutput().isEmpty() ? "" : chunk.getOutput().get(0).toString();
                System.out.println("[Hook] Tool progress: " + output);

            } else if (event instanceof PostActingEvent postActing) {
                ToolResultBlock toolResult = postActing.getToolResult();
                String output = extractOutput(toolResult);
                if (output.contains("fake")) {
                    System.out.println("[Hook] Fake tool result detected: " + output);
                } else {
                    System.out.println("[Hook] Tool result: " + output);
                }

            } else if (event instanceof PostCallEvent) {
                System.out.println("[Hook] Agent execution completed");

            } else if (event instanceof ErrorEvent errorEvent) {
                System.err.println("[Hook] Error: " + errorEvent.getError().getMessage());
            }

            // Return the event unchanged
            return Mono.just(event);
        }

        private String extractOutput(ToolResultBlock toolResult) {
            List<ContentBlock> outputs = toolResult.getOutput();
            if (outputs.isEmpty()) return "";

            ContentBlock first = outputs.get(0);
            if (first instanceof TextBlock tb) {
                return tb.getText();
            }

            // Multiple blocks: concatenate text blocks
            StringBuilder sb = new StringBuilder();
            for (ContentBlock block : outputs) {
                if (block instanceof TextBlock tb) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(tb.getText());
                }
            }
            return sb.length() > 0 ? sb.toString() : outputs.toString();
        }
    }

    /** Long-running tool for demonstrating interruption. */
    public static class LongRunningTools {

        @Tool(
                name = "process_large_dataset",
                description = "Process a large dataset (simulated long operation)")
        public String processLargeDataset(
                @ToolParam(name = "dataset_name", description = "Name of the dataset")
                        String datasetName,
                @ToolParam(name = "operation", description = "Operation to perform")
                        String operation,
                ToolEmitter toolEmitter) {

            System.out.println(
                    "[Tool] Starting to process dataset: "
                            + datasetName
                            + " with operation: "
                            + operation);

            // Simulate long-running operation with progress updates
            for (int i = 1; i <= 10; i++) {
                try {
                    Thread.sleep(500); // 500ms per step = 5 seconds total
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "Processing interrupted";
                }

                // Emit progress
                toolEmitter.emit(ToolResultBlock.text("Processed " + (i * 10) + "%"));
            }

            return String.format(
                    "Successfully processed dataset '%s' with operation '%s'. "
                            + "Results: 1000 records analyzed.",
                    datasetName, operation);
        }
    }
}
