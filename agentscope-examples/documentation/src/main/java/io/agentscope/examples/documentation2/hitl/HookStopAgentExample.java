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
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * HookStopAgentExample - Human-in-the-loop tool confirmation via {@link MiddlewareBase}.
 *
 * <p>Migration notes (from documentation/quickstart):
 * <ul>
 *   <li>{@code PostReasoningEvent.stopAgent()} replaced by emitting {@link RequestStopEvent}
 *       from {@link MiddlewareBase#onActing}.</li>
 *   <li>The pending tool calls are preserved in {@code AgentState.context} automatically;
 *       caller resumes execution by issuing a second {@code agent.call(...)}.</li>
 *   <li>The {@code hasPendingToolUse()} check is replaced by inspecting
 *       {@code response.getGenerateReason() == GenerateReason.MIDDLEWARE_STOP_REQUESTED}.</li>
 *   <li>Removed {@code .memory(new InMemoryMemory())}.</li>
 *   <li>{@code .hooks(List)} → {@code .middleware(...)}.</li>
 * </ul>
 */
public class HookStopAgentExample {

    private static final Set<String> DANGEROUS_TOOLS = Set.of("delete_file", "send_email");

    /**
     * Runs the hook stop-agent example.
     *
     * @param args command-line arguments (ignored)
     * @throws Exception if an I/O error occurs
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Middleware Stop-Agent Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates human-in-the-loop tool confirmation.\n"
                        + "Sensitive tools (delete_file, send_email) require explicit approval\n"
                        + "before execution. Safe tools (search_web) run without interruption.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SensitiveTools());

        System.out.println("Registered tools:");
        System.out.println("  - delete_file : Delete a file (requires confirmation)");
        System.out.println("  - send_email  : Send an email (requires confirmation)");
        System.out.println("  - search_web  : Search the web (runs without confirmation)\n");

        ReActAgent agent =
                ReActAgent.builder()
                        .name("SafeAgent")
                        .sysPrompt(
                                "You are a helpful assistant with access to file and email tools."
                                        + " Always use the appropriate tool when asked to delete"
                                        + " files or send emails.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .middleware(new ToolConfirmationMiddleware(DANGEROUS_TOOLS))
                        .build();

        System.out.println("Try these commands:");
        System.out.println("  - 'Delete the file temp.txt'");
        System.out.println("  - 'Send an email to john@example.com saying Hello'");
        System.out.println("  - 'Search for weather forecast'\n");

        startChatWithConfirmation(agent);
    }

    /**
     * Interactive chat loop that handles tool confirmation.
     *
     * @param agent the agent to chat with
     */
    static void startChatWithConfirmation(ReActAgent agent) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\nYou: ");
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                System.out.println("Goodbye!");
                break;
            }

            if (input.isEmpty()) {
                continue;
            }

            Msg userMsg = new UserMessage("user", input);

            Msg response = agent.call(userMsg).block();

            // Resume loop: middleware emitted RequestStopEvent for dangerous tool confirmation
            while (response != null
                    && response.getGenerateReason() == GenerateReason.MIDDLEWARE_STOP_REQUESTED) {

                System.out.println("\n⚠  Agent paused — dangerous tool requires confirmation.");
                displayPendingContext(response);

                System.out.print("Confirm execution? (yes/no): ");
                String confirmation = scanner.nextLine().trim().toLowerCase();

                if (confirmation.equals("yes") || confirmation.equals("y")) {
                    System.out.println("Resuming execution...\n");
                    // Resume: pending tool calls are stored in AgentState; call with empty input
                    response = agent.call().block();
                } else {
                    System.out.println("Operation cancelled by user.\n");
                    break;
                }
            }

            if (response != null) {
                System.out.println("\nAgent: " + response.getTextContent());
            }
        }
    }

    private static void displayPendingContext(Msg response) {
        if (response != null) {
            System.out.println("Agent reply so far: " + response.getTextContent());
        }
    }

    /**
     * Middleware that intercepts tool execution and pauses the agent when a
     * dangerous tool is about to run.
     */
    static class ToolConfirmationMiddleware implements MiddlewareBase {

        private final Set<String> dangerousTools;

        ToolConfirmationMiddleware(Set<String> dangerousTools) {
            this.dangerousTools = dangerousTools;
        }

        @Override
        public Flux<AgentEvent> onActing(
                Agent agent,
                RuntimeContext ctx,
                ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            boolean hasDangerousTool =
                    input.toolCalls().stream()
                            .map(ToolUseBlock::getName)
                            .anyMatch(dangerousTools::contains);

            if (hasDangerousTool) {
                // Emit RequestStopEvent to pause the agent before executing the tool.
                // The pending tool call state is saved in AgentState automatically.
                // The caller can resume with agent.call(List.of()).
                System.out.println(
                        "\n[MIDDLEWARE] Dangerous tool detected — requesting stop for"
                                + " confirmation.");
                return Flux.just(new RequestStopEvent("Dangerous tool requires user confirmation"));
            }

            return next.apply(input);
        }
    }

    /** Simulated sensitive tools — no real side effects in this example. */
    public static class SensitiveTools {

        /**
         * Simulates deleting a file.
         *
         * @param filename the name of the file to delete
         * @return result message
         */
        @Tool(name = "delete_file", description = "Delete a file from the filesystem")
        public String deleteFile(
                @ToolParam(name = "filename", description = "Path to the file to delete")
                        String filename) {
            System.out.println("[TOOL] Deleting file: " + filename);
            return "File '" + filename + "' deleted successfully.";
        }

        /**
         * Simulates sending an email.
         *
         * @param to      recipient address
         * @param subject subject line
         * @param body    email body
         * @return result message
         */
        @Tool(name = "send_email", description = "Send an email to a recipient")
        public String sendEmail(
                @ToolParam(name = "to", description = "Recipient email address") String to,
                @ToolParam(name = "subject", description = "Email subject") String subject,
                @ToolParam(name = "body", description = "Email body") String body) {
            System.out.println("[TOOL] Sending email to " + to + " — subject: " + subject);
            return "Email sent to '" + to + "'.";
        }

        /**
         * Simulates a web search (no confirmation required).
         *
         * @param query the search query
         * @return simulated search result
         */
        @Tool(name = "search_web", description = "Search the web for information")
        public String searchWeb(
                @ToolParam(name = "query", description = "Search query") String query) {
            System.out.println("[TOOL] Searching web for: " + query);
            return "Search results for '" + query + "': [Simulated results]";
        }
    }
}
