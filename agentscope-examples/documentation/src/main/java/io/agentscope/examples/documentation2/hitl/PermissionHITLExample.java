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
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.util.Scanner;

/**
 * PermissionHITLExample - Human-in-the-loop confirmation via the permission engine.
 *
 * <p>When the permission engine encounters a tool call that matches an {@code ASK} rule,
 * the agent stops and returns a response with
 * {@code getGenerateReason() == GenerateReason.PERMISSION_ASKING}. The caller can then inspect
 * the pending tool calls, ask the user for confirmation, and resume by calling the agent again.
 *
 * <p><b>Flow:</b>
 * <ol>
 *   <li>User sends a message that triggers a tool requiring confirmation.</li>
 *   <li>Agent returns early with {@code GenerateReason.PERMISSION_ASKING}.</li>
 *   <li>Application shows the pending tool call to the user and collects a decision.</li>
 *   <li>Application sends a follow-up message to resume or cancel.</li>
 * </ol>
 *
 * <p><b>Headless mode ({@link PermissionMode#DONT_ASK}):</b>
 * When running without a human operator (CI pipelines, automated tests), set the mode to
 * {@code DONT_ASK}. Tools that would have triggered an ask are auto-denied instead.
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation2 \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.hitl.PermissionHITLExample
 * </pre>
 */
public class PermissionHITLExample {

    /**
     * Runs the permission HITL example.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Permission HITL Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates human-in-the-loop confirmation via the permission engine.\n"
                        + "safe_read is auto-allowed; dangerous_delete requires confirmation.\n"
                        + "Pass --headless to run in DONT_ASK mode (auto-deny confirmations).");
        System.out.println("=".repeat(60) + "\n");

        boolean headless = args.length > 0 && "--headless".equalsIgnoreCase(args[0]);

        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DangerousTools());

        // ── Build permission context ───────────────────────────────────────────────────
        //
        // - safe_read: always auto-allowed (ALLOW rule with null ruleContent = matches everything)
        // - dangerous_delete: ask the user before executing (ASK rule)
        // - Mode DONT_ASK in headless mode: ASK degrades to DENY automatically
        PermissionMode mode = headless ? PermissionMode.DONT_ASK : PermissionMode.DEFAULT;
        PermissionContextState permCtx =
                PermissionContextState.builder()
                        .mode(mode)
                        .addAllowRule(
                                "safe_read",
                                new PermissionRule(
                                        "safe_read", null, PermissionBehavior.ALLOW, "policy"))
                        .addAskRule(
                                "dangerous_delete",
                                new PermissionRule(
                                        "dangerous_delete", null, PermissionBehavior.ASK, "policy"))
                        .build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("GuardedAgent")
                        .sysPrompt(
                                "You are a file assistant. You have safe_read and dangerous_delete"
                                        + " tools.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .permissionContext(permCtx)
                        .build();

        if (headless) {
            System.out.println(
                    "Running in headless mode (DONT_ASK): confirmations are auto-denied.\n");
            runHeadless(agent);
        } else {
            System.out.println(
                    "Running in interactive mode (DEFAULT): you will be asked to confirm"
                            + " deletes.\n");
            runInteractive(agent);
        }
    }

    private static void runHeadless(ReActAgent agent) {
        Msg result =
                agent.call(new UserMessage("user", "Delete the file /tmp/important.txt")).block();
        System.out.println("Reason: " + (result != null ? result.getGenerateReason() : "null"));
        System.out.println("Agent:  " + (result != null ? result.getTextContent() : "(null)"));
    }

    private static void runInteractive(ReActAgent agent) {
        Scanner scanner = new Scanner(System.in);
        System.out.print("You: ");

        while (scanner.hasNextLine()) {
            String userInput = scanner.nextLine().trim();
            if (userInput.isBlank() || "exit".equalsIgnoreCase(userInput)) {
                break;
            }

            Msg userMsg = new UserMessage("user", userInput);

            Msg result = agent.call(java.util.List.of(userMsg)).block();

            if (result != null && result.getGenerateReason() == GenerateReason.PERMISSION_ASKING) {
                // ── Agent stopped to ask for confirmation ──────────────────────────────
                System.out.println(
                        "\n[HITL] The agent wants to execute a tool that requires your approval.");
                System.out.println("[HITL] Pending operations:");
                result.getContent().forEach(block -> System.out.println("  - " + block));
                System.out.print("[HITL] Allow? (yes/no): ");

                String decision = scanner.hasNextLine() ? scanner.nextLine().trim() : "no";
                boolean approved =
                        "yes".equalsIgnoreCase(decision) || "y".equalsIgnoreCase(decision);

                // Resume the agent with the user's decision
                String resumeText =
                        approved ? "yes, proceed with the operation" : "no, cancel the operation";
                Msg resumeMsg = new UserMessage("user", resumeText);
                Msg finalResult = agent.call(java.util.List.of(resumeMsg)).block();
                System.out.println(
                        "\nAgent: "
                                + (finalResult != null ? finalResult.getTextContent() : "(null)"));
            } else {
                System.out.println(
                        "\nAgent: " + (result != null ? result.getTextContent() : "(null)"));
            }

            System.out.print("\nYou: ");
        }
    }

    /** Tools where one is safe and one is dangerous. */
    public static class DangerousTools {

        /**
         * Reads a file safely (always auto-allowed).
         *
         * @param path file path to read
         * @return simulated file content
         */
        @Tool(name = "safe_read", description = "Read a file (safe, always allowed)")
        public String safeRead(
                @ToolParam(name = "path", description = "File path to read") String path) {
            return "Content of " + path + ": [simulated data]";
        }

        /**
         * Permanently deletes a file (requires user confirmation).
         *
         * @param path file path to delete
         * @return confirmation string
         */
        @Tool(
                name = "dangerous_delete",
                description = "Permanently delete a file (requires confirmation)")
        public String dangerousDelete(
                @ToolParam(name = "path", description = "File path to delete permanently")
                        String path) {
            return "Deleted: " + path;
        }
    }
}
