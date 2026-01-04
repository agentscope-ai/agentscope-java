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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for PromptProvider.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Fallback to default prompts when customPrompt is null</li>
 *   <li>Fallback to default prompts when custom prompt field is null</li>
 *   <li>Returning custom prompts when provided</li>
 *   <li>All four prompt getter methods</li>
 * </ul>
 */
@DisplayName("PromptProvider Tests")
class PromptProviderTest {

    @Test
    @DisplayName("Should return default prompt when customPrompt is null")
    void testNullCustomPrompt() {
        String prompt = PromptProvider.getPreviousRoundToolCompressPrompt(null);
        assertNotNull(prompt);
        assertEquals(Prompts.PREVIOUS_ROUND_TOOL_INVOCATION_COMPRESS_PROMPT, prompt);

        prompt = PromptProvider.getPreviousRoundSummaryPrompt(null);
        assertNotNull(prompt);
        assertEquals(Prompts.PREVIOUS_ROUND_CONVERSATION_SUMMARY_PROMPT, prompt);

        prompt = PromptProvider.getCurrentRoundLargeMessagePrompt(null);
        assertNotNull(prompt);
        assertEquals(Prompts.CURRENT_ROUND_LARGE_MESSAGE_SUMMARY_PROMPT, prompt);

        prompt = PromptProvider.getCurrentRoundCompressPrompt(null);
        assertNotNull(prompt);
        assertEquals(Prompts.CURRENT_ROUND_MESSAGE_COMPRESS_PROMPT, prompt);
    }

    @Test
    @DisplayName("Should return default prompt when custom prompt field is null")
    void testNullCustomPromptField() {
        PromptConfig customPrompt = PromptConfig.builder().build(); // All fields are null

        String prompt = PromptProvider.getPreviousRoundToolCompressPrompt(customPrompt);
        assertEquals(Prompts.PREVIOUS_ROUND_TOOL_INVOCATION_COMPRESS_PROMPT, prompt);

        prompt = PromptProvider.getPreviousRoundSummaryPrompt(customPrompt);
        assertEquals(Prompts.PREVIOUS_ROUND_CONVERSATION_SUMMARY_PROMPT, prompt);

        prompt = PromptProvider.getCurrentRoundLargeMessagePrompt(customPrompt);
        assertEquals(Prompts.CURRENT_ROUND_LARGE_MESSAGE_SUMMARY_PROMPT, prompt);

        prompt = PromptProvider.getCurrentRoundCompressPrompt(customPrompt);
        assertEquals(Prompts.CURRENT_ROUND_MESSAGE_COMPRESS_PROMPT, prompt);
    }

    @Test
    @DisplayName("Should return custom previousRoundToolCompressPrompt when provided")
    void testCustomPreviousRoundToolCompressPrompt() {
        String customPromptText = "Custom tool compress prompt";
        PromptConfig customPrompt =
                PromptConfig.builder().previousRoundToolCompressPrompt(customPromptText).build();

        String prompt = PromptProvider.getPreviousRoundToolCompressPrompt(customPrompt);
        assertEquals(customPromptText, prompt);
    }

    @Test
    @DisplayName("Should return custom previousRoundSummaryPrompt when provided")
    void testCustomPreviousRoundSummaryPrompt() {
        String customPromptText = "Custom summary prompt";
        PromptConfig customPrompt =
                PromptConfig.builder().previousRoundSummaryPrompt(customPromptText).build();

        String prompt = PromptProvider.getPreviousRoundSummaryPrompt(customPrompt);
        assertEquals(customPromptText, prompt);
    }

    @Test
    @DisplayName("Should return custom currentRoundLargeMessagePrompt when provided")
    void testCustomCurrentRoundLargeMessagePrompt() {
        String customPromptText = "Custom large message prompt";
        PromptConfig customPrompt =
                PromptConfig.builder().currentRoundLargeMessagePrompt(customPromptText).build();

        String prompt = PromptProvider.getCurrentRoundLargeMessagePrompt(customPrompt);
        assertEquals(customPromptText, prompt);
    }

    @Test
    @DisplayName("Should return custom currentRoundCompressPrompt when provided")
    void testCustomCurrentRoundCompressPrompt() {
        String customPromptText = "Custom compress prompt";
        PromptConfig customPrompt =
                PromptConfig.builder().currentRoundCompressPrompt(customPromptText).build();

        String prompt = PromptProvider.getCurrentRoundCompressPrompt(customPrompt);
        assertEquals(customPromptText, prompt);
    }

    @Test
    @DisplayName("Should return custom prompt for set field and default for unset field")
    void testMixedCustomAndDefaultPrompts() {
        String customToolPrompt = "Custom tool prompt";
        PromptConfig customPrompt =
                PromptConfig.builder()
                        .previousRoundToolCompressPrompt(customToolPrompt)
                        // Other prompts are not set
                        .build();

        // Should return custom prompt for set field
        String prompt = PromptProvider.getPreviousRoundToolCompressPrompt(customPrompt);
        assertEquals(customToolPrompt, prompt);

        // Should return default for unset fields
        prompt = PromptProvider.getPreviousRoundSummaryPrompt(customPrompt);
        assertEquals(Prompts.PREVIOUS_ROUND_CONVERSATION_SUMMARY_PROMPT, prompt);

        prompt = PromptProvider.getCurrentRoundLargeMessagePrompt(customPrompt);
        assertEquals(Prompts.CURRENT_ROUND_LARGE_MESSAGE_SUMMARY_PROMPT, prompt);

        prompt = PromptProvider.getCurrentRoundCompressPrompt(customPrompt);
        assertEquals(Prompts.CURRENT_ROUND_MESSAGE_COMPRESS_PROMPT, prompt);
    }

    @Test
    @DisplayName("Should return all custom prompts when all are provided")
    void testAllCustomPrompts() {
        String prompt1 = "Custom tool compress prompt";
        String prompt4 = "Custom summary prompt";
        String prompt5 = "Custom large message prompt";
        String prompt6 = "Custom compress prompt";

        PromptConfig customPrompt =
                PromptConfig.builder()
                        .previousRoundToolCompressPrompt(prompt1)
                        .previousRoundSummaryPrompt(prompt4)
                        .currentRoundLargeMessagePrompt(prompt5)
                        .currentRoundCompressPrompt(prompt6)
                        .build();

        assertEquals(prompt1, PromptProvider.getPreviousRoundToolCompressPrompt(customPrompt));
        assertEquals(prompt4, PromptProvider.getPreviousRoundSummaryPrompt(customPrompt));
        assertEquals(prompt5, PromptProvider.getCurrentRoundLargeMessagePrompt(customPrompt));
        assertEquals(prompt6, PromptProvider.getCurrentRoundCompressPrompt(customPrompt));
    }

    @Test
    @DisplayName("Should return default prompt when custom prompt is empty string")
    void testEmptyStringPrompts() {
        PromptConfig customPrompt =
                PromptConfig.builder()
                        .previousRoundToolCompressPrompt("")
                        .previousRoundSummaryPrompt("")
                        .currentRoundLargeMessagePrompt("")
                        .currentRoundCompressPrompt("")
                        .build();

        // Empty strings should fall back to defaults
        assertEquals(
                Prompts.PREVIOUS_ROUND_TOOL_INVOCATION_COMPRESS_PROMPT,
                PromptProvider.getPreviousRoundToolCompressPrompt(customPrompt));
        assertEquals(
                Prompts.PREVIOUS_ROUND_CONVERSATION_SUMMARY_PROMPT,
                PromptProvider.getPreviousRoundSummaryPrompt(customPrompt));
        assertEquals(
                Prompts.CURRENT_ROUND_LARGE_MESSAGE_SUMMARY_PROMPT,
                PromptProvider.getCurrentRoundLargeMessagePrompt(customPrompt));
        assertEquals(
                Prompts.CURRENT_ROUND_MESSAGE_COMPRESS_PROMPT,
                PromptProvider.getCurrentRoundCompressPrompt(customPrompt));
    }

    @Test
    @DisplayName("Should return default prompt when custom prompt is blank (whitespace only)")
    void testBlankStringPrompts() {
        PromptConfig customPrompt =
                PromptConfig.builder()
                        .previousRoundToolCompressPrompt("   ")
                        .previousRoundSummaryPrompt("\t\n")
                        .currentRoundLargeMessagePrompt(" ")
                        .currentRoundCompressPrompt("\r\n\t")
                        .build();

        // Blank strings (whitespace only) should fall back to defaults
        assertEquals(
                Prompts.PREVIOUS_ROUND_TOOL_INVOCATION_COMPRESS_PROMPT,
                PromptProvider.getPreviousRoundToolCompressPrompt(customPrompt));
        assertEquals(
                Prompts.PREVIOUS_ROUND_CONVERSATION_SUMMARY_PROMPT,
                PromptProvider.getPreviousRoundSummaryPrompt(customPrompt));
        assertEquals(
                Prompts.CURRENT_ROUND_LARGE_MESSAGE_SUMMARY_PROMPT,
                PromptProvider.getCurrentRoundLargeMessagePrompt(customPrompt));
        assertEquals(
                Prompts.CURRENT_ROUND_MESSAGE_COMPRESS_PROMPT,
                PromptProvider.getCurrentRoundCompressPrompt(customPrompt));
    }
}
