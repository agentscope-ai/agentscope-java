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
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
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
 * HookExample - Demonstrates event-driven Hook system for monitoring agent execution.
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
                        .hooks(List.of(monitoringHook))
                        .build();

        System.out.println("Try asking: 'Process the customer dataset'\n");

        // Start interactive chat
        ExampleUtils.startChat(agent);
    }

    /**
     * Monitoring hook that logs all agent execution events.
     *
     * <p>This hook demonstrates the event-driven approach using pattern matching on different event
     * types.
     */
    static class MonitoringHook implements Hook {

        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            if (event instanceof PreCallEvent preCall) {
                System.out.println(
                        "\n[HOOK] PreCallEvent - Agent started: " + preCall.getAgent().getName());

            } else if (event instanceof ReasoningChunkEvent reasoningChunk) {
                // Print streaming reasoning content as it arrives (incremental chunks)
                Msg chunk = reasoningChunk.getIncrementalChunk();
                String text = MsgUtils.getTextContent(chunk);
                if (text != null && !text.isEmpty()) {
                    System.out.print(text);
                }

            } else if (event instanceof PreActingEvent preActing) {
                System.out.println(
                        "\n[HOOK] PreActingEvent - Tool: "
                                + preActing.getToolUse().getName()
                                + ", Input: "
                                + preActing.getToolUse().getInput());

            } else if (event instanceof ActingChunkEvent actingChunk) {
                // Receive progress updates from ToolEmitter
                ToolResultBlock chunk = actingChunk.getChunk();
                String output =
                        chunk.getOutput().isEmpty() ? "" : chunk.getOutput().get(0).toString();
                System.out.println(
                        "[HOOK] ActingChunkEvent - Tool: "
                                + actingChunk.getToolUse().getName()
                                + ", Progress: "
                                + output);

            } else if (event instanceof PostActingEvent postActing) {
                ToolResultBlock result = postActing.getToolResult();
                String output =
                        result.getOutput().isEmpty() ? "" : result.getOutput().get(0).toString();
                System.out.println(
                        "[HOOK] PostActingEvent - Tool: "
                                + postActing.getToolUse().getName()
                                + ", Result: "
                                + output);

            } else if (event instanceof PostCallEvent) {
                System.out.println("[HOOK] PostCallEvent - Agent execution finished\n");
            }

            // Return the event unchanged
            return Mono.just(event);
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
