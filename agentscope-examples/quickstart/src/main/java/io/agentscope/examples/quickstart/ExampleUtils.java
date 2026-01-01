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
package io.agentscope.examples.quickstart;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.examples.quickstart.util.MsgUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Utility class providing common functionality for examples.
 *
 * <p>
 * Features:
 *
 * <ul>
 * <li>Interactive API key configuration
 * <li>Chat loop implementation
 * <li>Helper methods for user interaction
 * </ul>
 */
public class ExampleUtils {

    private static final BufferedReader reader =
            new BufferedReader(new InputStreamReader(System.in));

    /**
     * Get DashScope API key from environment variable or interactive input.
     *
     * @return API key
     * @throws IOException if input fails
     */
    public static String getDashScopeApiKey() throws IOException {
        return getApiKey(
                "DASHSCOPE_API_KEY", "DashScope", "https://dashscope.console.aliyun.com/apiKey");
    }

    /**
     * Get OpenAI API key from environment variable or interactive input.
     *
     * @return API key
     * @throws IOException if input fails
     */
    public static String getOpenAIApiKey() throws IOException {
        return getApiKey("OPENAI_API_KEY", "OpenAI", "https://platform.openai.com/api-keys");
    }

    /**
     * Get API key from environment variable or interactive input.
     *
     * @param envVarName  environment variable name
     * @param serviceName service name for display
     * @param helpUrl     URL to get API key
     * @return API key
     * @throws IOException if input fails
     */
    public static String getApiKey(String envVarName, String serviceName, String helpUrl)
            throws IOException {

        // 1. Try environment variable
        String apiKey = System.getenv(envVarName);

        if (apiKey != null && !apiKey.isEmpty()) {
            System.out.println("✓ Using API key from environment variable " + envVarName + "\n");
            return apiKey;
        }

        // 2. Interactive input
        System.out.println(envVarName + " environment variable not found.\n");
        System.out.println("Please enter your " + serviceName + " API Key:");
        System.out.println("(Get one at: " + helpUrl + ")");
        System.out.print("\nAPI Key: ");

        apiKey = reader.readLine().trim();

        if (apiKey.isEmpty()) {
            System.err.println("Error: API Key cannot be empty");
            System.exit(1);
        }

        System.out.println("\n✓ API Key configured");
        System.out.println("Tip: Set environment variable to skip this step:");
        System.out.println("  export " + envVarName + "=" + maskApiKey(apiKey) + "\n");

        return apiKey;
    }

    /**
     * Mask API key for display (show first 4 and last 4 characters).
     *
     * @param apiKey API key to mask
     * @return masked API key
     */
    public static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "***";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    /**
     * Start an interactive chat loop with an agent.
     *
     * @param agent the agent to chat with
     * @throws IOException if input fails
     */
    public static void startChat(Agent agent) throws IOException {
        System.out.println("=== Chat Started ===");
        System.out.println("Type 'exit' to quit\n");

        while (true) {
            System.out.print("You> ");
            String input = reader.readLine();

            if (input == null || "exit".equalsIgnoreCase(input.trim())) {
                System.out.println("Goodbye!");
                break;
            }

            if (input.trim().isEmpty()) {
                continue;
            }

            try {
                Msg userMsg =
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text(input).build())
                                .build();

                System.out.print("Agent> ");

                try {
                    // Try to use stream() first for real-time output
                    AtomicBoolean hasPrintedThinkingHeader = new AtomicBoolean(false);
                    AtomicBoolean hasPrintedTextHeader = new AtomicBoolean(false);
                    AtomicBoolean hasPrintedTextSeparator = new AtomicBoolean(false);
                    AtomicReference<String> lastThinkingContent = new AtomicReference<>("");
                    AtomicReference<String> lastTextContent = new AtomicReference<>("");

                    agent.stream(userMsg)
                            .doOnNext(
                                    event -> {
                                        Msg msg = event.getMessage();
                                        for (ContentBlock block : msg.getContent()) {
                                            if (block instanceof ThinkingBlock) {
                                                printStreamContent(
                                                        ((ThinkingBlock) block).getThinking(),
                                                        lastThinkingContent,
                                                        hasPrintedThinkingHeader,
                                                        "> Thinking: ",
                                                        null);
                                            } else if (block instanceof TextBlock) {
                                                printStreamContent(
                                                        ((TextBlock) block).getText(),
                                                        lastTextContent,
                                                        hasPrintedTextHeader,
                                                        "Text: ",
                                                        () -> {
                                                            if (hasPrintedThinkingHeader.get()
                                                                    && !hasPrintedTextSeparator
                                                                            .get()) {
                                                                System.out.print("\n\n");
                                                                hasPrintedTextSeparator.set(true);
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
                                "\n[Info] Streaming not supported by this agent. Falling back to"
                                        + " call().");
                    } else {
                        System.err.println(
                                "\n[Warning] Exception during streaming: " + e.getMessage());
                        e.printStackTrace();
                        System.err.println("[Info] Falling back to call().");
                    }

                    Msg response = agent.call(userMsg).block();
                    if (response != null) {
                        // Extract thinking and text separately to match streaming format
                        String thinking =
                                response.getContent().stream()
                                        .filter(block -> block instanceof ThinkingBlock)
                                        .map(block -> ((ThinkingBlock) block).getThinking())
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
        }
    }

    /**
     * Read a line from user input.
     *
     * @return user input
     * @throws IOException if input fails
     */
    public static String readLine() throws IOException {
        return reader.readLine();
    }

    /**
     * Print a welcome banner.
     *
     * @param title       example title
     * @param description example description
     */
    public static void printWelcome(String title, String description) {
        System.out.println("=== " + title + " ===\n");
        System.out.println(description);
        System.out.println();
    }

    /**
     * Extract text content from a message.
     *
     * @param msg message to extract text from
     * @return extracted text
     */
    public static String extractTextFromMsg(Msg msg) {
        return MsgUtils.getTextContent(msg);
    }

    /**
     * Helper method to print streaming content.
     *
     * @param content             content to print
     * @param lastContentRef      reference to the last content for delta
     *                            calculation
     * @param hasPrintedHeaderRef reference to whether the header has been printed
     * @param header              header to print
     * @param prePrintAction      action to run before printing (e.g., adding
     *                            separators)
     */
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
