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
package io.agentscope.examples.documentation2.middleware;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

/**
 * HookExample - Demonstrates lifecycle monitoring via {@link MiddlewareBase}.
 *
 * <p>Migration notes (from documentation/quickstart):
 * <ul>
 *   <li>Replaced all {@code legacy.hook.*} events with {@code MiddlewareBase} override methods.</li>
 *   <li>{@code PreCallEvent} / {@code PostCallEvent} → {@code onAgent()} before/after {@code next.apply()}.</li>
 *   <li>{@code PreActingEvent} / {@code PostActingEvent} → {@code onActing()} before/after {@code next.apply()}.</li>
 *   <li>{@code ActingChunkEvent} → tap {@code ToolResultTextDeltaEvent} inside the {@code onActing()} stream.</li>
 *   <li>Reasoning streaming is observable via {@code onReasoning()} stream events.</li>
 *   <li>{@code JsonlTraceExporter} removed; replaced by custom file-logging middleware pattern.</li>
 *   <li>{@code .hooks(List)} → {@code .middlewares(List)}.</li>
 *   <li>Removed {@code .memory(new InMemoryMemory())}.</li>
 * </ul>
 */
public class CustomizedMiddlewareExample {

    /**
     * Runs the hook / middleware monitoring example.
     *
     * @param args command-line arguments (ignored)
     * @throws Exception if an I/O error occurs
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Middleware Monitoring Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates lifecycle monitoring via MiddlewareBase.\n"
                        + "You'll see detailed logs of agent activities including reasoning and"
                        + " tool calls.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ProgressTools());

        System.out.println("Registered tools:");
        System.out.println("  - process_data: Simulate data processing with progress updates\n");

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
                        .middleware(new MonitoringMiddleware())
                        .build();

        System.out.println("Try asking: 'Process the customer dataset'\n");
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

    /**
     * Middleware that logs all lifecycle events: agent start/end, reasoning,
     * tool call start/end, and tool progress chunks.
     */
    static class MonitoringMiddleware implements MiddlewareBase {

        @Override
        public Flux<AgentEvent> onAgent(
                Agent agent,
                RuntimeContext ctx,
                AgentInput input,
                Function<AgentInput, Flux<AgentEvent>> next) {
            System.out.println(
                    "\n[MIDDLEWARE] onAgent START — agent: "
                            + agent.getName()
                            + ", messages: "
                            + input.msgs().size());
            return next.apply(input)
                    .doOnComplete(() -> System.out.println("[MIDDLEWARE] onAgent END — completed"));
        }

        @Override
        public Flux<AgentEvent> onReasoning(
                Agent agent,
                RuntimeContext ctx,
                ReasoningInput input,
                Function<ReasoningInput, Flux<AgentEvent>> next) {
            int msgCount = input.messages() != null ? input.messages().size() : 0;
            System.out.println("[MIDDLEWARE] onReasoning START — context size: " + msgCount);
            return next.apply(input)
                    .doOnComplete(() -> System.out.println("[MIDDLEWARE] onReasoning END\n"));
        }

        @Override
        public Flux<AgentEvent> onActing(
                Agent agent,
                RuntimeContext ctx,
                ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            String toolNames =
                    input.toolCalls().stream()
                            .map(ToolUseBlock::getName)
                            .collect(Collectors.joining(", "));
            System.out.println("\n[MIDDLEWARE] onActing START — tools: " + toolNames);

            return next.apply(input)
                    .doOnNext(
                            event -> {
                                if (event instanceof ToolResultTextDeltaEvent delta) {
                                    System.out.println(
                                            "[MIDDLEWARE] tool progress chunk: "
                                                    + delta.getDelta());
                                }
                            })
                    .doOnComplete(
                            () -> System.out.println("[MIDDLEWARE] onActing END — " + toolNames));
        }
    }

    /** Tools that use {@link ToolEmitter} to emit progress chunks during execution. */
    public static class ProgressTools {

        /**
         * Processes a named dataset and emits progress chunks.
         *
         * @param datasetName name of the dataset
         * @param emitter     emitter for streaming progress updates
         * @return final processing summary
         */
        @Tool(name = "process_data", description = "Process a dataset and report progress")
        public String processData(
                @ToolParam(name = "dataset_name", description = "Name of the dataset")
                        String datasetName,
                ToolEmitter emitter) {
            System.out.println("[TOOL] Starting dataset processing: " + datasetName);
            try {
                for (int i = 1; i <= 5; i++) {
                    Thread.sleep(500);
                    int pct = i * 20;
                    emitter.emit(
                            ToolResultBlock.text(
                                    String.format("Processed %d%% of %s", pct, datasetName)));
                }
                return String.format(
                        "Successfully processed '%s'. Total: 1000 records analyzed.", datasetName);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Processing interrupted";
            }
        }
    }
}
