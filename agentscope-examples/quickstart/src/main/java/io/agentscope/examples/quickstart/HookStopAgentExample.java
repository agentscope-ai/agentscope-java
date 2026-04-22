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
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
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
import java.util.List;
import java.util.Scanner;
import reactor.core.publisher.Mono;

/**
 * HookStopAgentExample - Demonstrates human-in-the-loop with Hook stopAgent().
 *
 * <p>This example shows how to use the stopAgent() feature to implement human-in-the-loop
 * workflows where sensitive tool calls require user confirmation before execution.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li>Hook calls stopAgent() in PostReasoningEvent to pause before tool execution</li>
 *   <li>Agent returns the pending ToolUse message for user review</li>
 *   <li>User can confirm to resume or provide alternative input</li>
 *   <li>Check Msg content to determine if agent is waiting for confirmation</li>
 * </ul>
 */
public class HookStopAgentExample {

    public static void main(String[] args) throws Exception {
        // Print welcome message
        ExampleUtils.printWelcome(
                "Hook Stop Agent Example",
                "This example demonstrates human-in-the-loop tool confirmation.\n"
                        + "The agent will pause before executing sensitive operations,\n"
                        + "allowing you to review and confirm the tool calls.");

        // Get API key
        String apiKey = ExampleUtils.getDashScopeApiKey();

        // Create toolkit with sensitive tools
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SensitiveTools());

        System.out.println("Registered tools:");
        System.out.println("  - delete_file: Delete a file (requires confirmation)");
        System.out.println("  - send_email: Send an email (requires confirmation)");
        System.out.println("  - search_web: Search the web (safe, no confirmation needed)\n");

        // Create human-in-the-loop confirmation hook
        Hook confirmationHook = new ToolConfirmationHook();

        // Create Agent with confirmation hook
        ReActAgent agent =
                ReActAgent.builder()
                        .name("SafeAgent")
                        .sysPrompt(
                                "You are a helpful assistant with access to file and email tools."
                                    + " Always use the appropriate tool when asked to delete files"
                                    + " or send emails.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .hooks(List.of(confirmationHook))
                        .build();

        System.out.println("Try these commands:");
        System.out.println("  - 'Delete the file temp.txt'");
        System.out.println("  - 'Send an email to john@example.com saying Hello'");
        System.out.println("  - 'Search for weather forecast'\n");

        // Start interactive chat with confirmation loop
        startChatWithConfirmation(agent);
    }

    /**
     * Interactive chat loop that handles tool confirmation.
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

            // Create user message
            Msg userMsg =
                    Msg.builder()
                            .name("user")
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(input).build())
                            .build();

            // Call the agent
            Msg response = agent.call(userMsg).block();

            // Check if response has pending tool calls (waiting for confirmation)
            while (response != null && hasPendingToolUse(response)) {
                // Display pending tool calls
                System.out.println("\n⚠️  Agent paused for confirmation");
                displayPendingToolCalls(response);

                System.out.print("\nConfirm execution? (yes/no): ");
                String confirmation = scanner.nextLine().trim().toLowerCase();

                if (confirmation.equals("yes") || confirmation.equals("y")) {
                    // Resume execution with no arguments
                    System.out.println("Resuming execution...\n");
                    response = agent.call().block();
                } else if (confirmation.equals("no") || confirmation.equals("n")) {
                    // Provide a manual tool result for all pending tools
                    System.out.println("Operation cancelled by user.\n");
                    Msg cancelResult = createCancelledToolResults(response, agent.getName());
                    response = agent.call(cancelResult).block();
                } else {
                    System.out.println("Invalid input. Please enter 'yes' or 'no'.");
                    continue;
                }
            }

            // Print final response
            if (response != null) {
                System.out.println("\nAgent: " + response.getTextContent());
            }
        }
    }

    /**
     * Check if a message has pending tool use blocks (indicates agent is waiting).
     */
    static boolean hasPendingToolUse(Msg msg) {
        return msg.hasContentBlocks(ToolUseBlock.class);
    }

    /**
     * Display the pending tool calls for user review.
     */
    static void displayPendingToolCalls(Msg msg) {
        List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
        for (ToolUseBlock toolCall : toolCalls) {
            System.out.println("  Tool: " + toolCall.getName());
            System.out.println("  Input: " + toolCall.getInput());
        }
    }

    /**
     * Create tool result messages for all pending tool calls, indicating cancellation.
     */
    static Msg createCancelledToolResults(Msg toolUseMsg, String agentName) {
        List<ToolUseBlock> toolCalls = toolUseMsg.getContentBlocks(ToolUseBlock.class);
        if (toolCalls.isEmpty()) {
            // Return empty tool message if no tool calls (should not happen in normal flow)
            return Msg.builder().name(agentName).role(MsgRole.TOOL).build();
        }

        // Create ToolResultBlock for each pending tool call
        List<ToolResultBlock> results =
                toolCalls.stream()
                        .map(
                                tool ->
                                        ToolResultBlock.of(
                                                tool.getId(),
                                                tool.getName(),
                                                TextBlock.builder()
                                                        .text(
                                                                "Operation cancelled by user."
                                                                        + " Please try a different"
                                                                        + " approach.")
                                                        .build()))
                        .toList();

        return Msg.builder()
                .name(agentName)
                .role(MsgRole.TOOL)
                .content(results.toArray(new ToolResultBlock[0]))
                .build();
    }

    /**
     * Hook that requests confirmation for sensitive tool calls.
     *
     * <p>This hook checks if the pending tool call is for a sensitive operation
     * (delete_file, send_email) and calls stopAgent() to pause for user confirmation.
     */
    static class ToolConfirmationHook implements Hook {

        // Tools that require confirmation
        private static final List<String> SENSITIVE_TOOLS = List.of("delete_file", "send_email");

        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            if (event instanceof PostReasoningEvent postReasoning) {
                Msg reasoningMsg = postReasoning.getReasoningMessage();
                if (reasoningMsg == null) {
                    return Mono.just(event);
                }

                // Check if any sensitive tools are being called
                List<ToolUseBlock> toolCalls = reasoningMsg.getContentBlocks(ToolUseBlock.class);
                boolean hasSensitiveTool =
                        toolCalls.stream()
                                .anyMatch(tool -> SENSITIVE_TOOLS.contains(tool.getName()));

                if (hasSensitiveTool) {
                    System.out.println("\n🔒 Sensitive tool detected, requesting confirmation...");
                    postReasoning.stopAgent();
                }
            }
            return Mono.just(event);
        }
    }

    /**
     * Example tools including sensitive operations.
     */
    public static class SensitiveTools {

        @Tool(name = "delete_file", description = "Delete a file from the system")
        public String deleteFile(
                @ToolParam(name = "filename", description = "Name of the file to delete")
                        String filename) {
            // Simulated deletion
            System.out.println("[TOOL] Deleting file: " + filename);
            return "File '" + filename + "' has been deleted successfully.";
        }

        @Tool(name = "send_email", description = "Send an email to a recipient")
        public String sendEmail(
                @ToolParam(name = "to", description = "Recipient email address") String to,
                @ToolParam(name = "subject", description = "Email subject") String subject,
                @ToolParam(name = "body", description = "Email body content") String body) {
            // Simulated email sending
            System.out.println("[TOOL] Sending email to: " + to);
            System.out.println("[TOOL] Subject: " + subject);
            return "Email sent successfully to " + to;
        }

        @Tool(name = "search_web", description = "Search the web for information")
        public String searchWeb(
                @ToolParam(name = "query", description = "Search query") String query) {
            // Simulated web search
            System.out.println("[TOOL] Searching web for: " + query);
            return "Search results for '" + query + "': Found 10 relevant articles.";
        }
    }
}
