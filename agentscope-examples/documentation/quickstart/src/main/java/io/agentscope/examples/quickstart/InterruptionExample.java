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
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
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
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.quickstart.util.MsgUtils;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
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
                                System.out.print("Agent>....... ");
                                try {
                                    // Try to use stream() first for real-time output
                                    AtomicBoolean hasPrintedThinkingHeader =
                                            new AtomicBoolean(false);
                                    AtomicBoolean hasPrintedTextHeader = new AtomicBoolean(false);
                                    AtomicBoolean hasPrintedTextSeparator =
                                            new AtomicBoolean(false);
                                    AtomicReference<String> lastThinkingContent =
                                            new AtomicReference<>("");
                                    AtomicReference<String> lastTextContent =
                                            new AtomicReference<>("");

                                    StreamOptions streamOptions =
                                            StreamOptions.builder()
                                                    .eventTypes(
                                                            EventType.REASONING,
                                                            EventType.TOOL_RESULT)
                                                    .incremental(true)
                                                    .includeReasoningResult(false)
                                                    .build();

                                    agent.stream(userMsg, streamOptions)
                                            .doOnNext(
                                                    event -> {
                                                        Msg msg = event.getMessage();
                                                        for (ContentBlock block :
                                                                msg.getContent()) {
                                                            if (block instanceof ThinkingBlock) {
                                                                printStreamContent(
                                                                        ((ThinkingBlock) block)
                                                                                .getThinking(),
                                                                        lastThinkingContent,
                                                                        hasPrintedThinkingHeader,
                                                                        "> Thinking: ",
                                                                        null);
                                                            } else if (block instanceof TextBlock) {
                                                                printStreamContent(
                                                                        ((TextBlock) block)
                                                                                .getText(),
                                                                        lastTextContent,
                                                                        hasPrintedTextHeader,
                                                                        "Text: ",
                                                                        () -> {
                                                                            if (hasPrintedThinkingHeader
                                                                                            .get()
                                                                                    && !hasPrintedTextSeparator
                                                                                            .get()) {
                                                                                System.out.print(
                                                                                        "\n\n");
                                                                                hasPrintedTextSeparator
                                                                                        .set(true);
                                                                            }
                                                                        });
                                                            }
                                                        }
                                                    })
                                            .blockLast();
                                } catch (Exception e) {
                                    // Fallback to call() if streaming is not supported or fails
                                    if (e instanceof UnsupportedOperationException) {
                                        System.err.println(
                                                "\n"
                                                        + "[Info] Streaming not supported by this"
                                                        + " agent. Falling back to call().");
                                    } else {
                                        System.err.println(
                                                "\n[Warning] Exception during streaming: "
                                                        + e.getMessage());
                                        e.printStackTrace();
                                        System.err.println("[Info] Falling back to call().");
                                    }

                                    Msg response = agent.call(userMsg).block();
                                    if (response != null) {
                                        // Extract thinking and text separately to match streaming
                                        // format
                                        String thinking =
                                                response.getContent().stream()
                                                        .filter(
                                                                block ->
                                                                        block
                                                                                instanceof
                                                                                ThinkingBlock)
                                                        .map(
                                                                block ->
                                                                        ((ThinkingBlock) block)
                                                                                .getThinking())
                                                        .collect(Collectors.joining("\n"));

                                        String text =
                                                response.getContent().stream()
                                                        .filter(block -> block instanceof TextBlock)
                                                        .map(block -> ((TextBlock) block).getText())
                                                        .collect(Collectors.joining("\n"));

                                        boolean hasContent = false;
                                        if (!thinking.isEmpty()) {
                                            System.out.print("> Thinking: " + thinking);
                                            hasContent = true;
                                        }
                                        if (!text.isEmpty()) {
                                            if (hasContent) {
                                                System.out.print("\n\n");
                                            }
                                            System.out.print("Text: " + text);
                                            hasContent = true;
                                        }
                                        if (!hasContent) {
                                            System.out.print("[No response]");
                                        }
                                    }
                                }

                                System.out.println("\n");

                            } catch (Exception e) {
                                System.err.println("\nError: " + e.getMessage());
                                e.printStackTrace();
                            }
                        });

        agentThread.start();

        // Wait 2 seconds then interrupt
        try {
            Thread.sleep(5000);
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

    private static void printStreamContent(
            String content,
            AtomicReference<String> lastContentRef,
            AtomicBoolean hasPrintedHeaderRef,
            String header,
            Runnable prePrintAction) {
        String lastContent = lastContentRef.get();
        String toPrint;

        // Detect if cumulative or incremental
        if (content.startsWith(lastContent)) {
            // Cumulative: print only new part
            toPrint = content.substring(lastContent.length());
            lastContentRef.set(content);
        } else {
            // Incremental: print as-is and append
            toPrint = content;
            lastContentRef.set(lastContent + content);
        }

        if (!toPrint.isEmpty()) {
            if (prePrintAction != null) {
                prePrintAction.run();
            }

            if (!hasPrintedHeaderRef.get()) {
                System.out.print(header);
                hasPrintedHeaderRef.set(true);
            }
            System.out.print(toPrint);
            System.out.flush();
        }
    }
}
