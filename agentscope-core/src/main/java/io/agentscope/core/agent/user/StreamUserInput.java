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
 * Stream-based user input implementation that reads from any InputStream and writes to any
 * OutputStream. By default, uses System.in and System.out for console interaction.
 *
 * <p>Supports both simple text input and structured data input via key=value pairs. Blocking I/O
 * operations are executed on the bounded elastic scheduler to maintain reactive compatibility.
 *
 * <p>Usage examples:
 *
 * <pre>{@code
 * // Default console input/output
 * StreamUserInput consoleInput = StreamUserInput.builder().build();
 *
 * // Custom streams (e.g., for testing)
 * StreamUserInput customInput = StreamUserInput.builder()
 *     .inputStream(myInputStream)
 *     .outputStream(myOutputStream)
 *     .inputHint("Enter: ")
 *     .build();
 * }</pre>
 */
public class StreamUserInput implements UserInputBase {

    private final String inputHint;
    private final BufferedReader reader;
    private final PrintStream output;

    /**
     * Private constructor - use builder() to create instances.
     *
     * @param builder The builder instance
     */
    private StreamUserInput(Builder builder) {
        this.inputHint = builder.inputHint;
        this.reader = new BufferedReader(new InputStreamReader(builder.inputStream));
        this.output = new PrintStream(builder.outputStream);
    }

    /**
     * Create a new builder for StreamUserInput.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Handle user input from the configured input stream. Prints any context messages before
     * prompting the user with the configured input hint, reads a line of text, and optionally
     * collects structured data if a model class is provided. Returns a UserInputData containing
     * both the text content blocks and any structured input.
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
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Print a message to the output stream. Formats the message with the sender name and role,
     * followed by the text content.
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
     * Handle structured input based on the provided model class. Uses simple key=value pair
     * parsing. Future enhancements may include reflection-based parsing of model structure for
     * field-level validation.
     *
     * @param structuredModel The model class defining expected structure
     * @return Map containing parsed key-value pairs from user input
     */
    private Map<String, Object> handleStructuredInput(Class<?> structuredModel) {
        Map<String, Object> structuredInput = new HashMap<>();

        try {
            output.println("Structured input (press Enter to skip for optional fields):");
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

    /**
     * Builder for StreamUserInput.
     *
     * <p>Provides fluent API for configuring input/output streams and prompt hint. Defaults to
     * System.in/System.out if not specified.
     */
    public static class Builder {
        private String inputHint = "User Input: ";
        private InputStream inputStream = System.in;
        private OutputStream outputStream = System.out;

        private Builder() {}

        /**
         * Set the input hint prompt.
         *
         * @param inputHint The prompt text to display before user input
         * @return This builder
         */
        public Builder inputHint(String inputHint) {
            if (inputHint != null) {
                this.inputHint = inputHint;
            }
            return this;
        }

        /**
         * Set the input stream to read from.
         *
         * @param inputStream The input stream (defaults to System.in)
         * @return This builder
         */
        public Builder inputStream(InputStream inputStream) {
            if (inputStream != null) {
                this.inputStream = inputStream;
            }
            return this;
        }

        /**
         * Set the output stream to write to.
         *
         * @param outputStream The output stream (defaults to System.out)
         * @return This builder
         */
        public Builder outputStream(OutputStream outputStream) {
            if (outputStream != null) {
                this.outputStream = outputStream;
            }
            return this;
        }

        /**
         * Build the StreamUserInput instance.
         *
         * @return A new StreamUserInput instance
         */
        public StreamUserInput build() {
            return new StreamUserInput(this);
        }
    }
}
