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

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/**
 * UserAgent class for handling user interaction within the agent framework.
 * Acts as a bridge between various user input sources (terminal, web UI, CLI) and the message
 * system. Supports pluggable input methods through the UserInputBase interface, allowing
 * customization of how user input is collected and converted into framework messages.
 * Input is automatically saved to memory and can include both text content and structured data.
 */
public class UserAgent extends AgentBase {

    private static UserInputBase defaultInputMethod = new TerminalUserInput();
    private UserInputBase inputMethod;

    /**
     * Initialize the user agent with a name and memory.
     *
     * @param name The agent name
     * @param memory The memory instance for storing conversation history
     */
    public UserAgent(String name, Memory memory) {
        super(name, memory);
        this.inputMethod = defaultInputMethod;
    }

    /**
     * Initialize the user agent with a name, memory, and custom input method.
     *
     * @param name The agent name
     * @param memory The memory instance for storing conversation history
     * @param inputMethod The custom input method
     */
    public UserAgent(String name, Memory memory, UserInputBase inputMethod) {
        super(name, memory);
        this.inputMethod = inputMethod;
    }

    @Override
    protected Mono<Msg> doCall(Msg msg) {
        return handleUserInput(null);
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        return handleUserInput(null);
    }

    /**
     * Handle user input and generate a reply message.
     *
     * @param structuredModel Optional class for structured input format
     * @return Mono containing the reply message
     */
    public Mono<Msg> handleUserInput(Class<?> structuredModel) {
        return inputMethod
                .handleInput(getAgentId(), getName(), structuredModel)
                .map(this::createMessageFromInput)
                .doOnNext(
                        msg -> {
                            // Add the message to memory
                            getMemory().addMessage(msg);
                            // Print the message to console
                            printMessage(msg);
                        });
    }

    /**
     * Create a message from user input data.
     * Converts UserInputData containing content blocks and optional structured data into a
     * framework Msg with USER role. If no content blocks are provided, creates an empty text
     * block as fallback.
     *
     * @param inputData The user input data to convert
     * @return A Msg instance representing the user input
     */
    private Msg createMessageFromInput(UserInputData inputData) {
        List<ContentBlock> blocksInput = inputData.getBlocksInput();
        Map<String, Object> structuredInput = inputData.getStructuredInput();

        // Convert blocks input to content list
        List<ContentBlock> content;
        if (blocksInput != null && !blocksInput.isEmpty()) {
            // Use the blocks directly as List<ContentBlock>
            content = blocksInput;
        } else {
            // Create empty text block if no content
            content = List.of(TextBlock.builder().text("").build());
        }

        // Create the message
        Msg.Builder msgBuilder = Msg.builder().name(getName()).role(MsgRole.USER).content(content);

        // Add structured input as metadata if present
        if (structuredInput != null && !structuredInput.isEmpty()) {
            // In a full implementation, you'd want to add metadata support to Msg
            // For now, we'll include it in the message construction
        }

        return msgBuilder.build();
    }

    /**
     * Print the message to console.
     */
    private void printMessage(Msg msg) {
        System.out.println(
                "[" + msg.getName() + " (" + msg.getRole() + ")]: " + extractTextFromMsg(msg));
    }

    /**
     * Extract text content from a message for display purposes.
     * Concatenates text from TextBlock and ThinkingBlock instances, joining multiple blocks
     * with newlines. Non-text blocks are ignored.
     *
     * @param msg The message to extract text from
     * @return A string containing all text content, or empty string if none found
     */
    private String extractTextFromMsg(Msg msg) {
        return msg.getContent().stream()
                .map(
                        block -> {
                            if (block instanceof TextBlock) {
                                return ((TextBlock) block).getText();
                            } else if (block instanceof ThinkingBlock) {
                                return ((ThinkingBlock) block).getThinking();
                            }
                            return "";
                        })
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    /**
     * Override the input method for this UserAgent instance.
     *
     * @param inputMethod The new input method to use
     * @throws IllegalArgumentException if inputMethod is null
     */
    public void overrideInstanceInputMethod(UserInputBase inputMethod) {
        if (inputMethod == null) {
            throw new IllegalArgumentException("Input method cannot be null");
        }
        this.inputMethod = inputMethod;
    }

    /**
     * Override the default input method for all UserAgent instances.
     *
     * @param inputMethod The new default input method
     * @throws IllegalArgumentException if inputMethod is null
     */
    public static void overrideClassInputMethod(UserInputBase inputMethod) {
        if (inputMethod == null) {
            throw new IllegalArgumentException("Input method cannot be null");
        }
        defaultInputMethod = inputMethod;
    }

    /**
     * Handle interrupt scenarios.
     * For UserAgent, interrupts simply return an interrupted message.
     *
     * @param context The interrupt context containing metadata about the interruption
     * @param originalArgs The original arguments passed to the call() method
     * @return Mono containing an interrupt message
     */
    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
        Msg interruptMsg =
                Msg.builder()
                        .name(getName())
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Interrupted by user").build())
                        .build();

        // Add to memory
        addToMemory(interruptMsg);

        return Mono.just(interruptMsg);
    }
}
