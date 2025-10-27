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
import io.agentscope.core.agent.Agent;
import io.agentscope.core.formatter.DashScopeChatFormatter;
import io.agentscope.core.hook.ChunkMode;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;

/**
 * HookExample - Demonstrates Hook system for monitoring agent execution.
 *
 * <p>This example shows:
 *
 * <ul>
 *   <li>Complete Hook lifecycle callbacks
 *   <li>Streaming output with onReasoningChunk
 *   <li>Tool execution monitoring with onToolCall/onToolResult
 *   <li>ToolEmitter for progress reporting
 *   <li>ChunkMode: INCREMENTAL vs CUMULATIVE
 * </ul>
 *
 * <p>Run:
 *
 * <pre>
 * mvn exec:java -Dexec.mainClass="io.agentscope.examples.HookExample"
 * </pre>
 */
public class HookExample {

    public static void main(String[] args) throws Exception {
        // Print welcome message
        ExampleUtils.printWelcome(
                "Hook Example",
                "This example demonstrates the Hook system for monitoring agent execution.\n"
                        + "You'll see detailed logs of all agent activities including reasoning and"
                        + " tool calls.");

        // Get API key
        String apiKey = ExampleUtils.getDashScopeApiKey();

        // Create monitoring hook
        Hook monitoringHook = new MonitoringHook();

        // Create toolkit with a tool that emits progress
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ProgressTools());

        System.out.println("Registered tools:");
        System.out.println("  - process_data: Simulate data processing with progress updates\n");

        // Create Agent with hook
        ReActAgent agent =
                ReActAgent.builder()
                        .name("HookAgent")
                        .sysPrompt(
                                "You are a helpful assistant. When processing data, use the"
                                        + " process_data tool.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(true)
                                        .enableThinking(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .hook(monitoringHook)
                        .build();

        System.out.println("Try asking: 'Process the customer dataset'\n");

        // Start interactive chat
        ExampleUtils.startChat(agent);
    }

    /**
     * Monitoring hook that logs all agent execution events.
     *
     * <p>This hook demonstrates the complete lifecycle of agent execution.
     */
    static class MonitoringHook implements Hook {

        @Override
        public Mono<Void> preCall(Agent agent) {
            System.out.println("\n[HOOK] preCall - Agent started: " + agent.getName());
            return Mono.empty();
        }

        @Override
        public ChunkMode reasoningChunkMode() {
            // Use INCREMENTAL to receive only new content in each chunk
            return ChunkMode.INCREMENTAL;
        }

        @Override
        public Mono<Void> onReasoningChunk(Agent agent, Msg chunk) {
            // Print streaming reasoning content as it arrives
            String text = io.agentscope.examples.util.MsgUtils.getTextContent(chunk);
            if (text != null && !text.isEmpty()) {
                System.out.print(text);
            }
            return Mono.empty();
        }

        @Override
        public Mono<Msg> postReasoning(Agent agent, Msg msg) {
            // Called with complete reasoning message
            return Mono.just(msg);
        }

        @Override
        public Mono<ToolUseBlock> preActing(Agent agent, ToolUseBlock toolUse) {
            System.out.println(
                    "\n[HOOK] preActing - Tool: "
                            + toolUse.getName()
                            + ", Input: "
                            + toolUse.getInput());
            return Mono.just(toolUse);
        }

        @Override
        public Mono<Void> onActingChunk(Agent agent, ToolUseBlock toolUse, ToolResultBlock chunk) {
            // Receive progress updates from ToolEmitter
            String output =
                    chunk.getOutput().isEmpty() ? "" : chunk.getOutput().get(0).toString();
            System.out.println(
                    "[HOOK] onActingChunk - Tool: "
                            + toolUse.getName()
                            + ", Progress: "
                            + output);
            return Mono.empty();
        }

        @Override
        public Mono<ToolResultBlock> postActing(
                Agent agent, ToolUseBlock toolUse, ToolResultBlock result) {
            String output =
                    result.getOutput().isEmpty() ? "" : result.getOutput().get(0).toString();
            System.out.println(
                    "[HOOK] onToolResult - Tool: "
                            + toolUse.getName()
                            + ", Result: "
                            + output);
            return Mono.just(result);
        }

        @Override
        public Mono<Msg> postCall(Agent agent, Msg finalMsg) {
            System.out.println("[HOOK] onComplete - Agent execution finished\n");
            return Mono.just(finalMsg);
        }

        @Override
        public Mono<Void> onError(Agent agent, Throwable error) {
            System.err.println("[HOOK] onError - Error occurred: " + error.getMessage());
            return Mono.empty();
        }
    }

    /** Tools that use ToolEmitter to report progress. */
    public static class ProgressTools {

        /**
         * Simulate data processing with progress updates.
         *
         * @param datasetName Name of the dataset to process
         * @param emitter Tool emitter for progress updates
         * @return Processing result
         */
        @Tool(name = "process_data", description = "Process a dataset and report progress")
        public String processData(
                @ToolParam(name = "dataset_name", description = "Name of the dataset to process")
                        String datasetName,
                ToolEmitter emitter) {

            System.out.println(
                    "[TOOL] Starting to process dataset: "
                            + datasetName
                            + " (this will take a few seconds)");

            try {
                // Simulate processing with progress updates
                for (int i = 1; i <= 5; i++) {
                    Thread.sleep(800);
                    int progress = i * 20;

                    // Emit progress chunk
                    emitter.emit(
                            ToolResultBlock.text(
                                    String.format("Processed %d%% of %s", progress, datasetName)));
                }

                return String.format(
                        "Successfully processed dataset '%s'. Total: 1000 records analyzed.",
                        datasetName);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Processing interrupted";
            }
        }
    }
}
