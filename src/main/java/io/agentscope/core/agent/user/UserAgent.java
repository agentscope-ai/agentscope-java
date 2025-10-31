/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
import io.agentscope.core.hook.Hook;
import io.agentscope.core.interruption.InterruptContext;
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
 *
 * <p>Acts as a bridge between various user input sources (terminal, web UI, CLI) and the message
 * system. Supports pluggable input methods through the UserInputBase interface, allowing
 * customization of how user input is collected and converted into framework messages.
 *
 * <p>Design Philosophy:
 * <ul>
 *   <li>UserAgent does NOT manage memory - it only captures user input</li>
 *   <li>Input is obtained via pluggable UserInputBase implementations</li>
 *   <li>Supports both simple text input and structured input with validation</li>
 *   <li>Can participate in MsgHub for multi-agent conversations</li>
 * </ul>
 *
 * <p>Usage Examples:
 * <pre>{@code
 * // Simple terminal input
 * UserAgent user = new UserAgent("User");
 * Msg input = user.call().block();
 *
 * // With custom input method
 * UserAgent user = new UserAgent("User", new MockUserInput());
 *
 * // Structured input
 * TaskPlan plan = user.callWithStructuredOutput(null, TaskPlan.class).block();
 * }</pre>
 */
public class UserAgent extends AgentBase {

    private static UserInputBase defaultInputMethod = new TerminalUserInput();
    private UserInputBase inputMethod;

    /**
     * Initialize the user agent with a name.
     * Uses the default TerminalUserInput for input.
     *
     * @param name The agent name
     */
    public UserAgent(String name) {
        super(name);
        this.inputMethod = defaultInputMethod;
    }

    /**
     * Initialize the user agent with a name and hooks.
     * Uses the default TerminalUserInput for input.
     *
     * @param name The agent name
     * @param hooks List of hooks for monitoring execution
     */
    public UserAgent(String name, List<Hook> hooks) {
        super(name, hooks);
        this.inputMethod = defaultInputMethod;
    }

    /**
     * Initialize the user agent with a name and custom input method.
     *
     * @param name The agent name
     * @param inputMethod The custom input method
     */
    public UserAgent(String name, UserInputBase inputMethod) {
        super(name);
        this.inputMethod = inputMethod != null ? inputMethod : defaultInputMethod;
    }

    /**
     * Initialize the user agent with a name, custom input method, and hooks.
     *
     * @param name The agent name
     * @param inputMethod The custom input method
     * @param hooks List of hooks for monitoring execution
     */
    public UserAgent(String name, UserInputBase inputMethod, List<Hook> hooks) {
        super(name, hooks);
        this.inputMethod = inputMethod != null ? inputMethod : defaultInputMethod;
    }

    /**
     * Process a single input message and generate user input response.
     * Displays the input message before prompting for user input.
     *
     * @param msg Input message to display
     * @return User input message
     */
    @Override
    protected Mono<Msg> doCall(Msg msg) {
        return getUserInput(msg != null ? List.of(msg) : null, null);
    }

    /**
     * Process a single input message with structured model and generate user input response.
     * Displays the input message before prompting for user input.
     *
     * @param msg Input message to display
     * @param structuredModel Optional class defining the structure of expected input
     * @return User input message with structured data in metadata
     */
    @Override
    public Mono<Msg> call(Msg msg, Class<?> structuredModel) {
        return getUserInput(msg != null ? List.of(msg) : null, structuredModel);
    }

    /**
     * Process multiple input messages and generate user input response.
     * Displays the input messages before prompting for user input.
     *
     * @param msgs Input messages to display
     * @return User input message
     */
    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        return getUserInput(msgs, null);
    }

    /**
     * Process multiple input messages with structured model and generate user input response.
     * Displays the input messages before prompting for user input.
     *
     * @param msgs Input messages to display
     * @param structuredModel Optional class defining the structure of expected input
     * @return User input message with structured data in metadata
     */
    @Override
    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
        return getUserInput(msgs, structuredModel);
    }

    /**
     * Generate user input without any context.
     *
     * @return User input message
     */
    @Override
    protected Mono<Msg> doCall() {
        return getUserInput(null, null);
    }

    /**
     * Generate user input with structured model and without any context.
     * The structured model defines the expected structure of user input.
     *
     * @param structuredModel Optional class defining the structure of expected input
     * @return User input message with structured data in metadata
     */
    @Override
    public Mono<Msg> call(Class<?> structuredModel) {
        return getUserInput(null, structuredModel);
    }

    /**
     * Get user input with optional context messages and structured model.
     * This is the core method for obtaining user input.
     *
     * @param contextMessages Optional messages to display before prompting
     * @param structuredModel Optional class defining the structure of expected input
     * @return Mono containing the user input message
     */
    public Mono<Msg> getUserInput(List<Msg> contextMessages, Class<?> structuredModel) {
        return inputMethod
                .handleInput(getAgentId(), getName(), contextMessages, structuredModel)
                .map(this::createMessageFromInput)
                .doOnNext(this::printMessage);
    }

    /**
     * Create a message from user input data.
     * Converts UserInputData containing content blocks and optional structured data into a
     * framework Msg with USER role.
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
            content = blocksInput;
        } else {
            // Create empty text block if no content
            content = List.of(TextBlock.builder().text("").build());
        }

        // Create the message
        Msg.Builder msgBuilder = Msg.builder().name(getName()).role(MsgRole.USER).content(content);

        // Add structured input as metadata if present
        if (structuredInput != null && !structuredInput.isEmpty()) {
            msgBuilder.metadata(structuredInput);
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
     * Override the default input method for all new UserAgent instances.
     * This is a class-level setting that affects instances created after this call.
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
     * Get the current input method for this instance.
     *
     * @return The current input method
     */
    public UserInputBase getInputMethod() {
        return inputMethod;
    }

    /**
     * Observe messages without generating a reply.
     * UserAgent doesn't need to observe other agents' messages, so this is a no-op.
     *
     * @param msg Message to observe
     * @return Mono that completes immediately
     */
    @Override
    protected Mono<Void> doObserve(Msg msg) {
        // UserAgent doesn't observe, just complete
        return Mono.empty();
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

        return Mono.just(interruptMsg);
    }
}
