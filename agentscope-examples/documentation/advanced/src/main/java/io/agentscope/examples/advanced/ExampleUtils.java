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
package io.agentscope.examples.advanced;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.examples.advanced.util.MsgUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Utility class providing common functionality for examples.
 */
public class ExampleUtils {

    private static final BufferedReader reader =
            new BufferedReader(new InputStreamReader(System.in));

    private ExampleUtils() {}

    /**
     * Gets the DashScope API key from environment variable. Exits if not set.
     *
     * @return The API key
     */
    public static String getDashScopeApiKey() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Error: DASHSCOPE_API_KEY environment variable not set.");
            System.err.println("Please set it with: export DASHSCOPE_API_KEY=your_api_key");
            System.exit(1);
        }
        return apiKey;
    }

    /**
     * Gets the Mem0 API key from environment variable. Exits if not set.
     *
     * @return The API key
     */
    public static String getMem0ApiKey() {
        String apiKey = System.getenv("MEM0_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Error: MEM0_API_KEY environment variable not set.");
            System.err.println("Please set it with: export MEM0_API_KEY=your_api_key");
            System.exit(1);
        }
        return apiKey;
    }

    /**
     * Prints a welcome message for an example.
     *
     * @param title The title of the example
     * @param description The description of what the example demonstrates
     */
    public static void printWelcome(String title, String description) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println(title);
        System.out.println("=".repeat(80));
        System.out.println(description);
        System.out.println("=".repeat(80) + "\n");
    }

    /**
     * Starts an interactive chat session with the given agent.
     *
     * @param agent The agent to chat with
     * @throws IOException If reading input fails
     */
    public static void startChat(ReActAgent agent) throws IOException {
        System.out.println("Chat started! Type 'exit' to quit.\n");

        while (true) {
            System.out.print("You: ");
            String input = reader.readLine();

            if (input == null || input.trim().equalsIgnoreCase("exit")) {
                System.out.println("\nGoodbye!");
                break;
            }

            if (input.trim().isEmpty()) {
                continue;
            }

            Msg userMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(input.trim()).build())
                            .build();

            System.out.print("\nAssistant: ");
            try {
                Msg response = agent.call(userMsg).block();
                if (response != null) {
                    System.out.println(MsgUtils.getTextContent(response));
                }
                System.out.println();
            } catch (Exception e) {
                System.err.println("\nError: " + e.getMessage());
            }
        }
    }
}
