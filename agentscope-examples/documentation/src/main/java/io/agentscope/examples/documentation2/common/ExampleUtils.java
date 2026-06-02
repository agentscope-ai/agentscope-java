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
package io.agentscope.examples.documentation2.common;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

/**
 * Shared utilities for documentation2 examples.
 */
public final class ExampleUtils {

    private static final BufferedReader READER =
            new BufferedReader(new InputStreamReader(System.in));

    private ExampleUtils() {}

    /**
     * Returns the DashScope API key from the {@code DASHSCOPE_API_KEY} environment variable.
     * Exits with an error message when the variable is not set.
     *
     * @return the API key string
     */
    public static String getDashScopeApiKey() {
        return getApiKey("DASHSCOPE_API_KEY", "DashScope", "https://dashscope.aliyun.com");
    }

    /**
     * Returns the OpenAI API key from the {@code OPENAI_API_KEY} environment variable.
     * Exits with an error message when the variable is not set.
     *
     * @return the API key string
     */
    public static String getOpenAIApiKey() {
        return getApiKey("OPENAI_API_KEY", "OpenAI", "https://platform.openai.com");
    }

    /**
     * Returns the Mem0 API key from the {@code MEM0_API_KEY} environment variable.
     * Exits with an error message when the variable is not set.
     *
     * @return the API key string
     */
    public static String getMem0ApiKey() {
        return getApiKey("MEM0_API_KEY", "Mem0", "https://app.mem0.ai");
    }

    /**
     * Returns the value of the given environment variable, exiting if it is absent.
     *
     * @param envVar  environment variable name
     * @param service human-readable service name (used in the error message)
     * @param url     service URL (used in the error message)
     * @return the non-empty value of the environment variable
     */
    public static String getApiKey(String envVar, String service, String url) {
        String key = System.getenv(envVar);
        if (key == null || key.isBlank()) {
            System.err.println("Error: " + envVar + " environment variable not set.");
            System.err.println("Get your " + service + " API key from: " + url);
            System.err.println("Then set it with: export " + envVar + "=your_api_key");
            System.exit(1);
        }
        return key;
    }

    /**
     * Masks an API key for safe display, showing only the first 4 and last 4 characters.
     *
     * @param key the API key to mask
     * @return the masked key string
     */
    public static String maskApiKey(String key) {
        if (key == null || key.length() < 10) {
            return "****";
        }
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    /**
     * Prints a formatted welcome banner for an example.
     *
     * @param title       the example title
     * @param description short description of what the example demonstrates
     */
    public static void printWelcome(String title, String description) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println(title);
        System.out.println("=".repeat(80));
        System.out.println(description);
        System.out.println("=".repeat(80) + "\n");
    }

    /**
     * Starts an interactive streaming chat loop with the given agent.
     * Type {@code exit} to quit the loop.
     *
     * @param agent the agent to interact with
     * @throws IOException if reading from stdin fails
     */
    public static void startChat(ReActAgent agent) throws IOException {
        System.out.println("Chat started. Type 'exit' to quit.\n");

        StreamOptions streamOptions =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
                        .build();

        while (true) {
            System.out.print("You: ");
            String input = READER.readLine();

            if (input == null || input.trim().equalsIgnoreCase("exit")) {
                System.out.println("\nGoodbye!");
                break;
            }

            if (input.isBlank()) {
                continue;
            }

            Msg userMsg = new UserMessage(input.trim());

            System.out.print("\nAgent: ");
            agent.stream(List.of(userMsg), streamOptions)
                    .doOnNext(
                            event -> {
                                if (event.getType() == EventType.REASONING) {
                                    System.out.print(
                                            event.getMessage() != null
                                                    ? event.getMessage().getTextContent()
                                                    : "");
                                }
                            })
                    .doOnComplete(() -> System.out.println("\n"))
                    .blockLast();
        }
    }
}
