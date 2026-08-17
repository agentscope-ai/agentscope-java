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
package io.agentscope.examples.documentation2.streaming;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.util.List;
import java.util.Scanner;

/**
 * StreamingConsoleExample - Demonstrates {@link ReActAgent#stream} for console-based real-time output.
 *
 * <p>{@code agent.stream()} returns a {@link reactor.core.publisher.Flux}&lt;{@link Event}&gt;.
 * Each event carries a {@link EventType} and a {@link Msg}. Subscribing to the Flux lets
 * you process tokens as they arrive instead of waiting for the complete response.
 *
 * <p><b>Event types:</b>
 * <ul>
 *   <li>{@code REASONING} — assistant text/thinking chunks and final reasoning message</li>
 *   <li>{@code TOOL_RESULT} — result of a tool call</li>
 *   <li>{@code HINT} — injected context (memory, RAG, planner)</li>
 * </ul>
 *
 * <p>Each event has {@code isLast()} to distinguish incremental chunks from the final message
 * for that phase.
 *
 * <p><b>Contrast with {@code agent.call()}:</b>
 * <ul>
 *   <li>{@code call()} — blocks until full response; simpler but higher latency perception</li>
 *   <li>{@code stream()} — delivers tokens incrementally; better UX for long responses</li>
 * </ul>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation2 \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.streaming.StreamingConsoleExample
 * </pre>
 */
public class StreamingConsoleExample {

    /**
     * Runs the streaming console example.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Streaming Console Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates agent.stream() with real-time token printing.\n"
                        + "Reasoning tokens are printed incrementally; tool results are\n"
                        + "printed when the tool completes.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SimpleCalcTools());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("StreamingAgent")
                        .sysPrompt("You are a helpful assistant with math tools.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .build();

        // ── Configure stream options ───────────────────────────────────────────────────
        //
        // eventTypes — which event categories to receive (REASONING, TOOL_RESULT, HINT, or ALL)
        // incremental — when true each event carries only the new delta; false = cumulative
        // includeReasoningChunk — receive partial reasoning tokens (true = live streaming)
        StreamOptions streamOptions =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
                        .incremental(true)
                        .includeReasoningChunk(true)
                        .build();

        Scanner scanner = new Scanner(System.in);
        System.out.println("Type a question and press Enter. Type 'exit' to quit.\n");

        while (scanner.hasNextLine()) {
            String userInput = scanner.nextLine().trim();
            if ("exit".equalsIgnoreCase(userInput)) {
                break;
            }
            if (userInput.isBlank()) {
                continue;
            }

            Msg userMsg = new UserMessage("user", userInput);

            System.out.print("\nAgent: ");

            // ── Subscribe to the event stream ──────────────────────────────────────────
            //
            // stream() returns Flux<Event> — each element is one streaming event.
            // blockLast() drives the Flux to completion on the current thread.
            agent.stream(List.of(userMsg), streamOptions, (RuntimeContext) null)
                    .doOnNext(event -> printEvent(event))
                    .blockLast();

            System.out.println("\n");
        }
    }

    /**
     * Prints an event to the console based on its type.
     *
     * <p>Incremental REASONING chunks are printed without a newline to create a
     * live-typing effect. TOOL_RESULT events are printed on a separate line with
     * a label.
     *
     * @param event the event emitted by the stream
     */
    private static void printEvent(Event event) {
        if (event.getType() == EventType.REASONING) {
            String text = event.getMessage() != null ? event.getMessage().getTextContent() : "";
            if (text != null && !text.isBlank()) {
                if (event.isLast()) {
                    // Final reasoning message — already printed incrementally, so skip
                } else {
                    // Incremental chunk — print without newline
                    System.out.print(text);
                }
            }
        } else if (event.getType() == EventType.TOOL_RESULT) {
            if (event.isLast()) {
                String text = event.getMessage() != null ? event.getMessage().getTextContent() : "";
                System.out.println("\n[tool result] " + text);
            }
        }
    }

    /** Simple arithmetic tools for demonstration. */
    public static class SimpleCalcTools {

        /**
         * Adds two numbers.
         *
         * @param a first operand
         * @param b second operand
         * @return sum as a string
         */
        @Tool(name = "add", description = "Add two numbers")
        public String add(
                @ToolParam(name = "a", description = "First number") double a,
                @ToolParam(name = "b", description = "Second number") double b) {
            return String.valueOf(a + b);
        }

        /**
         * Multiplies two numbers.
         *
         * @param a first operand
         * @param b second operand
         * @return product as a string
         */
        @Tool(name = "multiply", description = "Multiply two numbers")
        public String multiply(
                @ToolParam(name = "a", description = "First number") double a,
                @ToolParam(name = "b", description = "Second number") double b) {
            return String.valueOf(a * b);
        }
    }
}
