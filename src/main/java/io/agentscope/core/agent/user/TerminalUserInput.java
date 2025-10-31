/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agent.user;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Terminal-based user input implementation that reads from console using System.in.
 * Supports both simple text input and structured data input via key=value pairs.
 * Blocking I/O operations are executed on the bounded elastic scheduler to maintain
 * reactive compatibility. This is the default input method for UserAgent when no custom
 * implementation is provided.
 */
public class TerminalUserInput implements UserInputBase {

    private final String inputHint;
    private final BufferedReader reader;
    private final PrintStream output;

    public TerminalUserInput() {
        this("User Input: ");
    }

    public TerminalUserInput(String inputHint) {
        this.inputHint = inputHint;
        this.reader = new BufferedReader(new InputStreamReader(System.in));
        this.output = System.out;
    }

    /**
     * Constructor with custom input and output streams.
     * Useful for testing or custom terminal implementations.
     *
     * @param inputHint The prompt hint to display before user input
     * @param inputStream The input stream to read from
     * @param outputStream The output stream to write to
     */
    public TerminalUserInput(String inputHint, InputStream inputStream, OutputStream outputStream) {
        this.inputHint = inputHint;
        this.reader = new BufferedReader(new InputStreamReader(inputStream));
        this.output = new PrintStream(outputStream);
    }

    /**
     * Handle user input from the terminal console.
     * Prints any context messages before prompting the user with the configured input hint,
     * reads a line of text, and optionally collects structured data if a model class is provided.
     * Returns a UserInputData containing both the text content blocks and any structured input.
     *
     * @param agentId The agent identifier (unused in this implementation)
     * @param agentName The agent name (unused in this implementation)
     * @param contextMessages Optional messages to display before prompting (e.g., assistant
     *     response)
     * @param structuredModel Optional class for structured input format
     * @return Mono containing the user input data
     */
    @Override
    public Mono<UserInputData> handleInput(
            String agentId, String agentName, List<Msg> contextMessages, Class<?> structuredModel) {
        return Mono.fromCallable(
                        () -> {
                            try {
                                // Print context messages before prompting
                                if (contextMessages != null && !contextMessages.isEmpty()) {
                                    for (Msg msg : contextMessages) {
                                        printMessage(msg);
                                    }
                                }

                                output.print(inputHint);
                                String textInput = reader.readLine();

                                if (textInput == null) {
                                    textInput = "";
                                }

                                // Create text block content
                                List<ContentBlock> blocksInput =
                                        Collections.singletonList(
                                                TextBlock.builder().text(textInput).build());

                                // Handle structured input if model is provided
                                Map<String, Object> structuredInput = null;
                                if (structuredModel != null) {
                                    structuredInput = handleStructuredInput(structuredModel);
                                }

                                return new UserInputData(blocksInput, structuredInput);
                            } catch (IOException e) {
                                throw new RuntimeException("Error reading user input", e);
                            }
                        })
                .subscribeOn(
                        Schedulers.boundedElastic()); // Use bounded elastic scheduler for blocking
        // I/O
    }

    /**
     * Print a message to the console.
     * Formats the message with the sender name and role, followed by the text content.
     *
     * @param msg The message to print
     */
    private void printMessage(Msg msg) {
        StringBuilder sb = new StringBuilder();

        // Add sender name and role
        if (msg.getName() != null && !msg.getName().isEmpty()) {
            sb.append("[").append(msg.getName());
            if (msg.getRole() != null) {
                sb.append(" (").append(msg.getRole()).append(")");
            }
            sb.append("]: ");
        }

        // Extract and append text content
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock textBlock) {
                sb.append(textBlock.getText());
            }
        }

        output.println(sb.toString());
    }

    /**
     * Handle structured input based on the provided model class.
     * This is a simplified version - in a full implementation, you would
     * use reflection or annotation processing to parse the model structure.
     */
    private Map<String, Object> handleStructuredInput(Class<?> structuredModel) {
        Map<String, Object> structuredInput = new HashMap<>();

        try {
            output.println("Structured input (press Enter to skip for optional fields):");

            // This is a simplified implementation - you would need to inspect
            // the class fields and their annotations to properly handle structured input
            output.print("\tEnter structured data as key=value pairs (or press Enter to skip): ");
            String input = reader.readLine();

            if (input != null && !input.trim().isEmpty()) {
                // Simple key=value parsing
                String[] pairs = input.split(",");
                for (String pair : pairs) {
                    String[] keyValue = pair.split("=", 2);
                    if (keyValue.length == 2) {
                        structuredInput.put(keyValue[0].trim(), keyValue[1].trim());
                    }
                }
            }
        } catch (IOException e) {
            output.println("Error reading structured input: " + e.getMessage());
        }

        return structuredInput;
    }
}
