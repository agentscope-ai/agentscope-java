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
package io.agentscope.core.memory.autocontext;

/**
 * Configuration class for compression prompts used by AutoContextMemory.
 *
 * <p>This class allows customization of the prompts used in different compression strategies.
 * All prompts are optional - if not specified, default prompts from {@link Prompts} will be used.
 *
 * <p><b>Configurable Prompts:</b>
 * <ul>
 *   <li><b>Strategy 1:</b> Previous round tool invocation compression</li>
 *   <li><b>Strategy 4:</b> Previous round conversation summarization</li>
 *   <li><b>Strategy 5:</b> Current round large message summarization</li>
 *   <li><b>Strategy 6:</b> Current round message compression</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Create a custom prompt configuration
 * PromptConfig customPrompt = PromptConfig.builder()
 *     .previousRoundToolCompressPrompt("Custom tool compression prompt...")
 *     .currentRoundCompressPrompt("Custom current round compression prompt...")
 *     .build();
 *
 * // Use in AutoContextConfig
 * AutoContextConfig config = AutoContextConfig.builder()
 *     .msgThreshold(50)
 *     .customPrompt(customPrompt)
 *     .build();
 * }</pre>
 *
 * <p><b>Note:</b> Format templates (PREVIOUS_ROUND_COMPRESSED_TOOL_INVOCATION_FORMAT and
 * PREVIOUS_ROUND_CONVERSATION_SUMMARY_FORMAT) are not configurable and will always use default
 * values from {@link Prompts}.
 *
 * <p><b>Empty String Handling:</b> If a prompt is set to an empty string or contains only
 * whitespace, the system will automatically fall back to the default prompt. This is handled by
 * {@link PromptProvider}.
 *
 * @see Prompts
 * @see PromptProvider
 * @see AutoContextConfig
 */
public class PromptConfig {

    /**
     * Strategy 1: Prompt for compressing previous round tool invocations.
     *
     * <p>This prompt is used when compressing historical tool invocation sequences. It guides the
     * LLM to compress tool calls while preserving tool names, parameters, and key results.
     *
     * <p>If null or empty, the default prompt from {@link Prompts#PREVIOUS_ROUND_TOOL_INVOCATION_COMPRESS_PROMPT}
     * will be used.
     */
    private String previousRoundToolCompressPrompt;

    /**
     * Strategy 4: Prompt for summarizing previous round conversations.
     *
     * <p>This prompt is used when summarizing entire conversation rounds (user-assistant pairs)
     * from previous rounds. It guides the LLM to create concise summaries while retaining key
     * decisions and important information.
     *
     * <p>If null or empty, the default prompt from {@link Prompts#PREVIOUS_ROUND_CONVERSATION_SUMMARY_PROMPT}
     * will be used.
     */
    private String previousRoundSummaryPrompt;

    /**
     * Strategy 5: Prompt for summarizing current round large messages.
     *
     * <p>This prompt is used when summarizing individual large messages in the current round.
     * It guides the LLM to create summaries that preserve critical information while reducing
     * message size.
     *
     * <p>If null or empty, the default prompt from {@link Prompts#CURRENT_ROUND_LARGE_MESSAGE_SUMMARY_PROMPT}
     * will be used.
     */
    private String currentRoundLargeMessagePrompt;

    /**
     * Strategy 6: Prompt for compressing current round messages.
     *
     * <p>This prompt is used when compressing all messages in the current round (typically tool
     * calls and results). It guides the LLM to compress content while being conservative to
     * preserve as much information as possible, since this is actively used content.
     *
     * <p><b>Note:</b> This prompt does not include character count requirements. The character
     * count requirement is handled separately via {@link Prompts#CURRENT_ROUND_MESSAGE_COMPRESS_CHAR_REQUIREMENT},
     * which is sent as a separate message after the main prompt.
     *
     * <p>If null or empty, the default prompt from {@link Prompts#CURRENT_ROUND_MESSAGE_COMPRESS_PROMPT}
     * will be used.
     */
    private String currentRoundCompressPrompt;

    /**
     * Private constructor to enforce use of Builder pattern.
     */
    private PromptConfig() {}

    /**
     * Builder class for constructing PromptConfig instances.
     *
     * <p>This builder follows the standard Builder pattern and supports method chaining.
     * Each call to {@link #build()} creates a new independent instance, allowing the same
     * builder to be reused multiple times safely.
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * PromptConfig config = PromptConfig.builder()
     *     .previousRoundToolCompressPrompt("Custom prompt 1")
     *     .previousRoundSummaryPrompt("Custom prompt 4")
     *     .build();
     * }</pre>
     */
    public static class Builder {
        private PromptConfig config = new PromptConfig();

        /**
         * Sets the prompt for Strategy 1: compressing previous round tool invocations.
         *
         * @param prompt the custom prompt text, or null/empty to use default
         * @return this builder instance for method chaining
         * @see #previousRoundToolCompressPrompt
         */
        public Builder previousRoundToolCompressPrompt(String prompt) {
            config.previousRoundToolCompressPrompt = prompt;
            return this;
        }

        /**
         * Sets the prompt for Strategy 4: summarizing previous round conversations.
         *
         * @param prompt the custom prompt text, or null/empty to use default
         * @return this builder instance for method chaining
         * @see #previousRoundSummaryPrompt
         */
        public Builder previousRoundSummaryPrompt(String prompt) {
            config.previousRoundSummaryPrompt = prompt;
            return this;
        }

        /**
         * Sets the prompt for Strategy 5: summarizing current round large messages.
         *
         * @param prompt the custom prompt text, or null/empty to use default
         * @return this builder instance for method chaining
         * @see #currentRoundLargeMessagePrompt
         */
        public Builder currentRoundLargeMessagePrompt(String prompt) {
            config.currentRoundLargeMessagePrompt = prompt;
            return this;
        }

        /**
         * Sets the prompt for Strategy 6: compressing current round messages.
         *
         * <p><b>Note:</b> This prompt does not include character count requirements.
         * The character count requirement is handled separately and sent as a separate message.
         *
         * @param prompt the custom prompt text, or null/empty to use default
         * @return this builder instance for method chaining
         * @see #currentRoundCompressPrompt
         */
        public Builder currentRoundCompressPrompt(String prompt) {
            config.currentRoundCompressPrompt = prompt;
            return this;
        }

        /**
         * Builds a new PromptConfig instance with the configured values.
         *
         * <p>Each call to this method creates a new independent instance, ensuring that
         * multiple calls to {@code build()} on the same builder do not share state.
         *
         * @return a new PromptConfig instance with the configured prompt values
         */
        public PromptConfig build() {
            // Create a new instance to avoid sharing state across multiple build() calls
            PromptConfig result = new PromptConfig();
            result.previousRoundToolCompressPrompt = config.previousRoundToolCompressPrompt;
            result.previousRoundSummaryPrompt = config.previousRoundSummaryPrompt;
            result.currentRoundLargeMessagePrompt = config.currentRoundLargeMessagePrompt;
            result.currentRoundCompressPrompt = config.currentRoundCompressPrompt;
            return result;
        }
    }

    /**
     * Creates a new Builder instance for constructing PromptConfig.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the prompt for Strategy 1: compressing previous round tool invocations.
     *
     * @return the custom prompt, or null if not set (will use default from {@link Prompts})
     * @see #previousRoundToolCompressPrompt
     */
    public String getPreviousRoundToolCompressPrompt() {
        return previousRoundToolCompressPrompt;
    }

    /**
     * Gets the prompt for Strategy 4: summarizing previous round conversations.
     *
     * @return the custom prompt, or null if not set (will use default from {@link Prompts})
     * @see #previousRoundSummaryPrompt
     */
    public String getPreviousRoundSummaryPrompt() {
        return previousRoundSummaryPrompt;
    }

    /**
     * Gets the prompt for Strategy 5: summarizing current round large messages.
     *
     * @return the custom prompt, or null if not set (will use default from {@link Prompts})
     * @see #currentRoundLargeMessagePrompt
     */
    public String getCurrentRoundLargeMessagePrompt() {
        return currentRoundLargeMessagePrompt;
    }

    /**
     * Gets the prompt for Strategy 6: compressing current round messages.
     *
     * @return the custom prompt, or null if not set (will use default from {@link Prompts})
     * @see #currentRoundCompressPrompt
     */
    public String getCurrentRoundCompressPrompt() {
        return currentRoundCompressPrompt;
    }
}
