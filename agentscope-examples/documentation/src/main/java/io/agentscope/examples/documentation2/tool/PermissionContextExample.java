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
package io.agentscope.examples.documentation2.tool;

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
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;

/**
 * PermissionContextExample - Demonstrates the {@link PermissionContextState} permission engine.
 *
 * <p>The permission engine gates every tool call before execution. Its behaviour is governed by:
 * <ol>
 *   <li>Explicit allow/deny/ask rules added to {@link PermissionContextState}</li>
 *   <li>The global {@link PermissionMode} (DEFAULT, ACCEPT_EDITS, EXPLORE, BYPASS, DONT_ASK)</li>
 *   <li>Per-tool {@code checkPermissions()} self-checks (available on {@link io.agentscope.core.tool.ToolBase} subclasses)</li>
 * </ol>
 *
 * <p><b>PermissionMode overview:</b>
 * <table border="1">
 *   <caption>PermissionMode behaviour summary</caption>
 *   <tr><th>Mode</th><th>Behaviour</th></tr>
 *   <tr><td>DEFAULT</td><td>Ask for everything unless an explicit rule matches</td></tr>
 *   <tr><td>ACCEPT_EDITS</td><td>Auto-allow write tools; ask for destructive ones</td></tr>
 *   <tr><td>EXPLORE</td><td>Auto-allow read-only tools; ask for write tools</td></tr>
 *   <tr><td>BYPASS</td><td>Skip all permission checks</td></tr>
 *   <tr><td>DONT_ASK</td><td>Auto-deny tools that would trigger an ask</td></tr>
 * </table>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation2 \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.tool.PermissionContextExample
 * </pre>
 */
public class PermissionContextExample {

    /**
     * Runs the permission context example.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Permission Context Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates how PermissionContextState controls tool access.\n"
                    + "Mode: DEFAULT (ask for everything unless a rule matches).\n"
                    + "read_file → allow rule; write_file → ask rule; delete_file → deny rule.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FileTools());

        // ── Build the permission context ───────────────────────────────────────────────
        //
        // Rule evaluation order (first match wins, see PermissionEngine):
        //   1. Explicit deny rules
        //   2. Explicit ask rules
        //   3. Tool-specific checks (EXPLORE/ACCEPT_EDITS mode + ToolBase.checkPermissions)
        //   4. Explicit allow rules
        //   5. BYPASS fallback
        //   6. Default ASK (or DENY under DONT_ASK mode)
        PermissionContextState permCtx =
                PermissionContextState.builder()
                        // Global mode: DEFAULT asks for everything unless an explicit rule matches.
                        // The three rules below demonstrate each behavior:
                        //   - read_file  → explicit ALLOW  → auto-allowed
                        //   - write_file → explicit ASK    → pauses for confirmation
                        //   - delete_file → explicit DENY  → permanently denied
                        //
                        // Note: EXPLORE mode auto-denies non-readOnly tools before allow rules
                        // are checked, so it should only be used with ToolBase tools that set
                        // readOnly(true).
                        .mode(PermissionMode.DEFAULT)
                        .addAllowRule(
                                "read_file",
                                new PermissionRule(
                                        "read_file",
                                        null,
                                        PermissionBehavior.ALLOW,
                                        "userSettings"))
                        .addAskRule(
                                "write_file",
                                new PermissionRule(
                                        "write_file", null, PermissionBehavior.ASK, "userSettings"))
                        .addDenyRule(
                                "delete_file",
                                new PermissionRule(
                                        "delete_file",
                                        null,
                                        PermissionBehavior.DENY,
                                        "userSettings"))
                        .build();

        // ── Wire permission context into the agent ─────────────────────────────────────
        ReActAgent agent =
                ReActAgent.builder()
                        .name("FileAgent")
                        .sysPrompt(
                                "You are a file assistant. You can read, write, and delete files.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .permissionContext(permCtx)
                        .middleware(new AgentTraceMiddleware())
                        .build();

        // ── Test: read_file → should be allowed automatically ─────────────────────────
        System.out.println("--- Scenario 1: read_file (expect: auto-allowed) ---");
        Msg readResult =
                agent.call(new UserMessage("user", "Read the file /tmp/notes.txt")).block();
        printResult(readResult);

        // ── Test: delete_file → should be denied ──────────────────────────────────────
        System.out.println("\n--- Scenario 2: delete_file (expect: denied) ---");
        Msg deleteResult =
                agent.call(new UserMessage("user", "Delete the file /tmp/notes.txt")).block();
        printResult(deleteResult);

        // ── Test: write_file → stops with PERMISSION_ASKING ───────────────────────────
        System.out.println("\n--- Scenario 3: write_file (expect: stops for confirmation) ---");
        Msg writeResult =
                agent.call(new UserMessage("user", "Write 'hello world' to /tmp/out.txt")).block();
        if (writeResult != null
                && writeResult.getGenerateReason() == GenerateReason.PERMISSION_ASKING) {
            System.out.println("Agent paused for permission confirmation (PERMISSION_ASKING).");
            System.out.println(
                    "In a real UI, display the pending tool calls and let the user confirm.");
        } else {
            printResult(writeResult);
        }
    }

    private static void printResult(Msg msg) {
        if (msg == null) {
            System.out.println("(null response)");
        } else {
            System.out.println("Reason: " + msg.getGenerateReason());
            System.out.println("Agent: " + msg.getTextContent());
        }
    }

    /** Simple file operation stubs. */
    public static class FileTools {

        /**
         * Reads a file (simulated).
         *
         * @param path file path to read
         * @return simulated file content
         */
        @Tool(name = "read_file", readOnly = true, description = "Read a file at the given path")
        public String readFile(
                @ToolParam(name = "path", description = "Absolute file path") String path) {
            return "Simulated content of: " + path;
        }

        /**
         * Writes content to a file (simulated).
         *
         * @param path    destination path
         * @param content content to write
         * @return confirmation message
         */
        @Tool(name = "write_file", description = "Write content to a file")
        public String writeFile(
                @ToolParam(name = "path", description = "Absolute file path") String path,
                @ToolParam(name = "content", description = "Text content to write")
                        String content) {
            return "Wrote " + content.length() + " chars to: " + path;
        }

        /**
         * Deletes a file (simulated).
         *
         * @param path file path to delete
         * @return confirmation message
         */
        @Tool(name = "delete_file", description = "Permanently delete a file")
        public String deleteFile(
                @ToolParam(name = "path", description = "Absolute file path to delete")
                        String path) {
            return "Deleted: " + path;
        }
    }
}
