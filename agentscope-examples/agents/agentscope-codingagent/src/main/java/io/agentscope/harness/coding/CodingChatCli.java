/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.coding;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.harness.coding.channel.chatui.ChatUiChannel;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

/**
 * Local CLI entry point for the Coding Agent.
 *
 * <p>Starts the {@link CodingBootstrap}, obtains the {@link ChatUiChannel}, and runs an
 * interactive REPL loop on standard input/output. Does not require a webhook — suitable for local
 * development and smoke-testing.
 *
 * <h2>Run</h2>
 *
 * <pre>
 * export DASHSCOPE_API_KEY=your_key_here
 * mvn -pl agentscope-examples/agents/agentscope-codingagent -am compile \
 *     org.codehaus.mojo:exec-maven-plugin:3.6.3:java \
 *     -Dexec.mainClass=io.agentscope.harness.coding.CodingChatCli
 * </pre>
 *
 * <h2>Subcommands</h2>
 *
 * <ul>
 *   <li>{@code review <pr_url>} — trigger the reviewer agent on the given PR URL
 *   <li>Any other text — send to the coding agent
 * </ul>
 *
 * <p>Type {@code /exit} or {@code /quit} to terminate.
 */
public class CodingChatCli {

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD = "\u001B[1m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";

    public static void main(String[] args) throws Exception {
        printBanner();

        Path cwd = Paths.get(System.getProperty("user.dir"));
        Model model = buildModel();

        CodingBootstrap bootstrap = CodingBootstrap.builder().cwd(cwd).model(model).build();

        ChatUiChannel chat = bootstrap.chatUiChannel();

        System.out.println(ANSI_GREEN + "✓ Coding Agent ready" + ANSI_RESET);
        System.out.println("  Workspace: " + cwd.resolve(".agentscope/coding-workspace"));
        System.out.println("  Model: " + resolveModelId());
        System.out.println();
        System.out.println("Type your message, or:");
        System.out.println(
                "  "
                        + ANSI_CYAN
                        + "review <pr_url>"
                        + ANSI_RESET
                        + "  — trigger reviewer agent on a PR");
        System.out.println("  " + ANSI_CYAN + "/exit" + ANSI_RESET + "             — quit");
        System.out.println();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while (true) {
                System.out.print(ANSI_BOLD + "You> " + ANSI_RESET);
                System.out.flush();
                line = reader.readLine();
                if (line == null
                        || line.equalsIgnoreCase("/exit")
                        || line.equalsIgnoreCase("/quit")) {
                    System.out.println("Goodbye.");
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }

                if (line.startsWith("review ")) {
                    String prUrl = line.substring("review ".length()).trim();
                    dispatchReviewer(chat, prUrl);
                    continue;
                }

                System.out.print(ANSI_YELLOW + "Agent> " + ANSI_RESET);
                System.out.flush();
                try {
                    Msg reply = chat.send(line).block();
                    if (reply != null) {
                        String text =
                                reply.getContent().stream()
                                        .filter(b -> b instanceof TextBlock)
                                        .map(b -> ((TextBlock) b).getText())
                                        .collect(Collectors.joining("\n"));
                        System.out.println(text.isEmpty() ? "[no text response]" : text);
                    }
                } catch (Exception e) {
                    System.err.println("[Error] " + e.getMessage());
                }
                System.out.println();
            }
        }

        bootstrap.stop();
    }

    private static void dispatchReviewer(ChatUiChannel chat, String prUrl) {
        if (prUrl.isBlank()) {
            System.err.println("[Error] Usage: review <pr_url>");
            return;
        }
        System.out.println(
                ANSI_YELLOW + "Reviewer> " + ANSI_RESET + "Triggering review of: " + prUrl);
        String reviewPrompt =
                "Please review the following pull request and provide detailed feedback: " + prUrl;
        try {
            Msg reply = chat.send(reviewPrompt).block();
            if (reply != null) {
                String text =
                        reply.getContent().stream()
                                .filter(b -> b instanceof TextBlock)
                                .map(b -> ((TextBlock) b).getText())
                                .collect(Collectors.joining("\n"));
                System.out.println(text.isEmpty() ? "[no reviewer response]" : text);
            }
        } catch (Exception e) {
            System.err.println("[Error] Reviewer dispatch failed: " + e.getMessage());
        }
        System.out.println();
    }

    private static Model buildModel() {
        String modelId = resolveModelId();
        String apiKey = resolveApiKey(modelId);

        if (modelId.startsWith("dashscope:") || !modelId.contains(":")) {
            String name =
                    modelId.startsWith("dashscope:")
                            ? modelId.substring("dashscope:".length())
                            : modelId;
            return DashScopeChatModel.builder().apiKey(apiKey).modelName(name).stream(true).build();
        }

        throw new IllegalArgumentException(
                "Unsupported model prefix in CODING_MODEL_ID: "
                        + modelId
                        + ". Use 'dashscope:<name>' (default) or implement additional model"
                        + " builders.");
    }

    private static String resolveModelId() {
        String env = System.getenv("CODING_MODEL_ID");
        return (env != null && !env.isBlank()) ? env : "dashscope:qwen-max";
    }

    private static String resolveApiKey(String modelId) {
        if (modelId.startsWith("dashscope:") || !modelId.contains(":")) {
            String key = System.getenv("DASHSCOPE_API_KEY");
            if (key == null || key.isBlank()) {
                System.err.println(
                        "[Warning] DASHSCOPE_API_KEY is not set. Set the environment variable or"
                                + " use CODING_MODEL_ID to switch models.");
                return "";
            }
            return key;
        }
        return "";
    }

    private static void printBanner() {
        System.out.println(ANSI_BOLD + ANSI_CYAN);
        System.out.println("  ╔═══════════════════════════════════════════╗");
        System.out.println("  ║        AgentScope Coding Agent CLI        ║");
        System.out.println("  ║     open-swe replicated on HarnessAgent   ║");
        System.out.println("  ╚═══════════════════════════════════════════╝");
        System.out.println(ANSI_RESET);
    }
}
