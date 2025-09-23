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
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * UserAgent class for handling user interaction.
 * This allows developers to handle user input from different sources,
 * such as web UI, CLI, and other interfaces.
 *
 * This corresponds to the Python UserAgent class.
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
    protected Flux<Msg> doStream(Msg msg) {
        return handleUserInput(null).flux();
    }

    @Override
    protected Flux<Msg> doStream(List<Msg> msgs) {
        return handleUserInput(null).flux();
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
                            // Print the message (equivalent to Python's self.print(msg))
                            printMessage(msg);
                        });
    }

    /**
     * Create a message from user input data.
     */
    private Msg createMessageFromInput(UserInputData inputData) {
        List<ContentBlock> blocksInput = inputData.getBlocksInput();
        Map<String, Object> structuredInput = inputData.getStructuredInput();

        // Convert blocks input to content
        ContentBlock content;
        if (blocksInput != null
                && blocksInput.size() == 1
                && blocksInput.get(0) instanceof TextBlock) {
            // If only one text block, use it directly
            content = blocksInput.get(0);
        } else if (blocksInput != null && !blocksInput.isEmpty()) {
            // For multiple blocks, we'd need a MultiContentBlock or similar
            // For now, just use the first block
            content = blocksInput.get(0);
        } else {
            // Create empty text block if no content
            content = TextBlock.builder().text("").build();
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
     * Print the message to console (equivalent to Python's self.print(msg)).
     */
    private void printMessage(Msg msg) {
        System.out.println(
                "["
                        + msg.getName()
                        + " ("
                        + msg.getRole()
                        + ")]: "
                        + getTextFromContent(msg.getContent()));
    }

    /**
     * Extract text content from a ContentBlock.
     */
    private String getTextFromContent(ContentBlock content) {
        if (content instanceof TextBlock) {
            return ((TextBlock) content).getText();
        }
        return content.toString();
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
     * This corresponds to the Python handle_interrupt method.
     *
     * @return Mono containing an interrupt message
     */
    public Mono<Msg> handleInterrupt() {
        return Mono.fromCallable(
                () ->
                        Msg.builder()
                                .name(getName())
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Interrupted by user").build())
                                .build());
    }
}
