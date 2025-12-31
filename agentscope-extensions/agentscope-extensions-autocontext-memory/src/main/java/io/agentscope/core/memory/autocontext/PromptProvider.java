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
 * Utility class for providing prompts with fallback to defaults.
 *
 * <p>This class provides a unified interface for retrieving prompts, with automatic fallback to
 * default prompts from {@link Prompts} when custom prompts are not provided.
 */
public class PromptProvider {

    /**
     * Strategy 1: Gets the prompt for compressing previous round tool invocations.
     * Returns custom prompt if provided, otherwise returns default from Prompts.
     *
     * @param customPrompt the custom prompt configuration, or null to use default
     * @return the prompt to use for compressing previous round tool invocations
     */
    public static String getPreviousRoundToolCompressPrompt(PromptConfig customPrompt) {
        if (customPrompt != null) {
            String prompt = customPrompt.getPreviousRoundToolCompressPrompt();
            if (prompt != null && !prompt.isBlank()) {
                return prompt;
            }
        }
        return Prompts.PREVIOUS_ROUND_TOOL_INVOCATION_COMPRESS_PROMPT;
    }

    /**
     * Strategy 4: Gets the prompt for summarizing previous round conversations.
     * Returns custom prompt if provided, otherwise returns default from Prompts.
     *
     * @param customPrompt the custom prompt configuration, or null to use default
     * @return the prompt to use for summarizing previous round conversations
     */
    public static String getPreviousRoundSummaryPrompt(PromptConfig customPrompt) {
        if (customPrompt != null) {
            String prompt = customPrompt.getPreviousRoundSummaryPrompt();
            if (prompt != null && !prompt.isBlank()) {
                return prompt;
            }
        }
        return Prompts.PREVIOUS_ROUND_CONVERSATION_SUMMARY_PROMPT;
    }

    /**
     * Strategy 5: Gets the prompt for summarizing current round large messages.
     * Returns custom prompt if provided, otherwise returns default from Prompts.
     *
     * @param customPrompt the custom prompt configuration, or null to use default
     * @return the prompt to use for summarizing current round large messages
     */
    public static String getCurrentRoundLargeMessagePrompt(PromptConfig customPrompt) {
        if (customPrompt != null) {
            String prompt = customPrompt.getCurrentRoundLargeMessagePrompt();
            if (prompt != null && !prompt.isBlank()) {
                return prompt;
            }
        }
        return Prompts.CURRENT_ROUND_LARGE_MESSAGE_SUMMARY_PROMPT;
    }

    /**
     * Strategy 6: Gets the prompt for compressing current round messages.
     * Returns custom prompt if provided, otherwise returns default from Prompts.
     *
     * <p>Note: This prompt does not include character count requirements. The character count
     * requirement is handled separately via {@link Prompts#CURRENT_ROUND_MESSAGE_COMPRESS_CHAR_REQUIREMENT}.
     *
     * @param customPrompt the custom prompt configuration, or null to use default
     * @return the prompt to use for compressing current round messages
     */
    public static String getCurrentRoundCompressPrompt(PromptConfig customPrompt) {
        if (customPrompt != null) {
            String prompt = customPrompt.getCurrentRoundCompressPrompt();
            if (prompt != null && !prompt.isBlank()) {
                return prompt;
            }
        }
        return Prompts.CURRENT_ROUND_MESSAGE_COMPRESS_PROMPT;
    }
}
