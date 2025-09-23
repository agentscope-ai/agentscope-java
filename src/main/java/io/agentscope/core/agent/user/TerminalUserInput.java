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
import io.agentscope.core.message.TextBlock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Terminal-based user input implementation.
 * This corresponds to the Python TerminalUserInput class.
 */
public class TerminalUserInput implements UserInputBase {

    private final String inputHint;
    private final BufferedReader reader;

    public TerminalUserInput() {
        this("User Input: ");
    }

    public TerminalUserInput(String inputHint) {
        this.inputHint = inputHint;
        this.reader = new BufferedReader(new InputStreamReader(System.in));
    }

    @Override
    public Mono<UserInputData> handleInput(
            String agentId, String agentName, Class<?> structuredModel) {
        return Mono.fromCallable(
                        () -> {
                            try {
                                System.out.print(inputHint);
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
     * Handle structured input based on the provided model class.
     * This is a simplified version - in a full implementation, you would
     * use reflection or annotation processing to parse the model structure.
     */
    private Map<String, Object> handleStructuredInput(Class<?> structuredModel) {
        Map<String, Object> structuredInput = new HashMap<>();

        try {
            System.out.println("Structured input (press Enter to skip for optional fields):");

            // This is a simplified implementation - you would need to inspect
            // the class fields and their annotations to properly handle structured input
            System.out.print(
                    "\tEnter structured data as key=value pairs (or press Enter to skip): ");
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
            System.err.println("Error reading structured input: " + e.getMessage());
        }

        return structuredInput;
    }
}
