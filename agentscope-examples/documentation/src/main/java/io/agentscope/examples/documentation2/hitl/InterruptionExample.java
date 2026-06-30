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
package io.agentscope.examples.documentation2.hitl;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * InterruptionExample - Demonstrates user-initiated agent interruption.
 *
 * <p>Migration notes (from documentation/quickstart):
 * <ul>
 *   <li>Replaced all {@code legacy.hook.*} events with a {@code MonitoringMiddleware} that
 *       extends {@code MiddlewareBase}.</li>
 *   <li>{@code PreCallEvent} / {@code PostCallEvent} → {@code onAgent()} before/after
 *       {@code next.apply()}.</li>
 *   <li>{@code PreActingEvent} / {@code PostActingEvent} → {@code onActing()} before/after
 *       {@code next.apply()}.</li>
 *   <li>{@code ActingChunkEvent} → tap {@code ToolResultTextDeltaEvent} in acting stream.</li>
 *   <li>{@code ErrorEvent} → {@code doOnError()} on the agent stream.</li>
 *   <li>{@code agent.getMemory().getMessages()} → {@code agent.getAgentState().getContext()}.</li>
 *   <li>Removed {@code .memory(new InMemoryMemory())}.</li>
 *   <li>{@code .hooks(List)} → {@code .middleware(...)}.</li>
 *   <li>{@code agent.interrupt(Msg)} is still the public API — no change needed.</li>
 * </ul>
 */
public class InterruptionExample {

    /**
     * Runs the interruption example.
     *
     * @param args command-line arguments (ignored)
     * @throws Exception if an I/O error occurs
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Interruption Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "This example demonstrates user-initiated interruption of agent execution.\n"
                        + "The agent will start a long-running task and be interrupted after 2"
                        + " seconds.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new LongRunningTools());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("DataAgent")
                        .sysPrompt(
                                "You are a data processing assistant."
                                        + " Use the process_large_dataset tool to process"
                                        + " datasets.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(false)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .middleware(new MonitoringMiddleware())
                        .maxIters(10)
                        .build();

        Msg userMsg =
                new UserMessage(
                        "User",
                        "Please process the 'customer_data' dataset with 'analyze' operation.");

        System.out.println("\nUser: " + userMsg.getTextContent());
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
                                            "\n[Agent Response] " + response.getTextContent());
                                }
                            } catch (Exception e) {
                                System.err.println("[Error] " + e.getMessage());
                            }
                        });

        agentThread.start();

        try {
            Thread.sleep(2000);
            System.out.println("\n>>> USER INTERRUPTS AGENT <<<\n");

            Msg interruptMsg = new UserMessage("User", "Stop! I need to change the dataset name.");

            // Interrupt the agent — still the public API in 2.0
            agent.interrupt(interruptMsg);
            agentThread.join();

            System.out.println("\n=== Interruption Demo Completed ===");
            System.out.println("What happened:");
            System.out.println("1. Agent started processing with the process_large_dataset tool");
            System.out.println("2. User interrupted the agent after 2 seconds");
            System.out.println("3. Agent generated fake tool results for interrupted calls");
            System.out.println("4. Middleware was notified about the interrupted acting phase");
            System.out.println("5. Agent returned a user-friendly recovery message");
            System.out.println(
                    "\nContext contains "
                            + agent.getAgentState().getContext().size()
                            + " messages including fake results and recovery.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Main thread interrupted: " + e.getMessage());
        }
    }

    /**
     * Monitoring middleware that logs all lifecycle events:
     * agent start/end, tool calls, progress chunks, and errors.
     */
    static class MonitoringMiddleware implements MiddlewareBase {

        @Override
        public Flux<AgentEvent> onAgent(
                Agent agent,
                RuntimeContext ctx,
                AgentInput input,
                Function<AgentInput, Flux<AgentEvent>> next) {
            System.out.println("[Middleware] Agent preCall: " + agent.getName());
            return next.apply(input)
                    .doOnComplete(
                            () -> System.out.println("[Middleware] Agent execution completed"))
                    .doOnError(e -> System.err.println("[Middleware] Error: " + e.getMessage()));
        }

        @Override
        public Flux<AgentEvent> onActing(
                Agent agent,
                RuntimeContext ctx,
                ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            input.toolCalls()
                    .forEach(
                            tc ->
                                    System.out.println(
                                            "[Middleware] Tool call: "
                                                    + tc.getName()
                                                    + ", input: "
                                                    + tc.getInput()));

            return next.apply(input)
                    .doOnNext(
                            event -> {
                                if (event instanceof ToolResultTextDeltaEvent delta) {
                                    System.out.println(
                                            "[Middleware] Tool progress: " + delta.getDelta());
                                }
                            })
                    .doOnComplete(() -> System.out.println("[Middleware] Tool result completed"));
        }
    }

    /** Long-running tool for demonstrating interruption. */
    public static class LongRunningTools {

        /**
         * Processes a large dataset — simulates a long-running operation with progress updates.
         *
         * @param datasetName name of the dataset
         * @param operation   operation to perform
         * @param toolEmitter emitter for streaming progress updates
         * @return processing result
         */
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

            for (int i = 1; i <= 10; i++) {
                try {
                    Thread.sleep(500); // 500ms per step = 5 seconds total
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "Processing interrupted";
                }
                toolEmitter.emit(ToolResultBlock.text("Processed " + (i * 10) + "%"));
            }
            return "Dataset '"
                    + datasetName
                    + "' processed successfully."
                    + " Total records: 50000. Operation: "
                    + operation;
        }
    }
}
