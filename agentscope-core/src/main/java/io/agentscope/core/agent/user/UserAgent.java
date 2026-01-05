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

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.MessageMetadataKeys;
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
 * <p>Acts as a bridge between various user input sources (streams, web UI, etc.) and the message
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
 * // Simple console input (default)
 * UserAgent user = UserAgent.builder()
 *     .name("User")
 *     .build();
 * Msg input = user.call().block();
 *
 * // With custom input method
 * UserAgent user = UserAgent.builder()
 *     .name("User")
 *     .inputMethod(myCustomInput)
 *     .build();
 *
 * // With hooks
 * UserAgent user = UserAgent.builder()
 *     .name("User")
 *     .hooks(List.of(myHook))
 *     .build();
 * }</pre>
 */
public class UserAgent extends AgentBase {

    private static UserInputBase defaultInputMethod = StreamUserInput.builder().build();
    private UserInputBase inputMethod;

    /**
     * Private constructor - use builder() to create instances.
     *
     * @param builder The builder instance
     */
    private UserAgent(Builder builder) {
        super(builder.name, builder.description, builder.checkRunning, builder.hooks);
        this.inputMethod = builder.inputMethod != null ? builder.inputMethod : defaultInputMethod;
    }

    /**
     * Create a new builder for UserAgent.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Process multiple input messages and generate user input response.
     * Displays the input messages before prompting for user input.
     *
     * @hidden
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
     * @hidden
     * @param msgs Input messages to display
     * @param structuredModel Optional class defining the structure of expected input
     * @return User input message with structured data in metadata
     */
    @Override
    public Mono<Msg> doCall(List<Msg> msgs, Class<?> structuredModel) {
        return getUserInput(msgs, structuredModel);
    }

    /**
     * Get user input with optional context messages and structured model.
     * This is the core method for obtaining user input.
     *
     * @hidden
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
            msgBuilder.metadata(Map.of(MessageMetadataKeys.STRUCTURED_OUTPUT, structuredInput));
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
     * @hidden
     * @param inputMethod The new input method to use
     * @throws IllegalArgumentException if inputMethod is null
     */
    protected void overrideInstanceInputMethod(UserInputBase inputMethod) {
        if (inputMethod == null) {
            throw new IllegalArgumentException("Input method cannot be null");
        }
        this.inputMethod = inputMethod;
    }

    /**
     * Override the default input method for all new UserAgent instances.
     * This is a class-level setting that affects instances created after this call.
     *
     * @hidden
     * @param inputMethod The new default input method
     * @throws IllegalArgumentException if inputMethod is null
     */
    protected static void overrideClassInputMethod(UserInputBase inputMethod) {
        if (inputMethod == null) {
            throw new IllegalArgumentException("Input method cannot be null");
        }
        defaultInputMethod = inputMethod;
    }

    /**
     * Get the current input method for this instance.
     *
     * @hidden
     * @return The current input method
     */
    protected UserInputBase getInputMethod() {
        return inputMethod;
    }

    /**
     * Observe messages without generating a reply.
     * UserAgent doesn't need to observe other agents' messages, so this is a no-op.
     *
     * @hidden
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

    /**
     * Builder for UserAgent.
     *
     * <p>Provides fluent API for configuring agent name, input method, and hooks. The name is
     * required; other properties have sensible defaults.
     */
    public static class Builder {
        private String name;
        private String description;
        private boolean checkRunning = true;
        private UserInputBase inputMethod;
        private List<Hook> hooks;

        private Builder() {}

        /**
         * Set the agent name (required).
         *
         * @param name The agent name
         * @return This builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Set the agent description.
         *
         * @param description The agent description
         * @return This builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Set the checkRunning flag.
         *
         * @param checkRunning The checkRunning flag
         * @return This builder
         */
        public Builder checkRunning(boolean checkRunning) {
            this.checkRunning = checkRunning;
            return this;
        }

        /**
         * Set the input method for user interaction.
         *
         * @param inputMethod The input method implementation (defaults to StreamUserInput with
         *     System.in/out)
         * @return This builder
         */
        public Builder inputMethod(UserInputBase inputMethod) {
            this.inputMethod = inputMethod;
            return this;
        }

        /**
         * Set the hooks for monitoring agent execution.
         *
         * @param hooks List of hooks
         * @return This builder
         */
        public Builder hooks(List<Hook> hooks) {
            this.hooks = hooks;
            return this;
        }

        /**
         * Build the UserAgent instance.
         *
         * @return A new UserAgent instance
         * @throws IllegalArgumentException if name is null or empty
         */
        public UserAgent build() {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Agent name is required");
            }
            return new UserAgent(this);
        }
    }
}
