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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for PromptConfig.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Builder pattern functionality</li>
 *   <li>Getter methods returning null for unset prompts</li>
 *   <li>Setting and retrieving custom prompts</li>
 *   <li>Builder method chaining</li>
 *   <li>Partial configuration (only some prompts set)</li>
 * </ul>
 */
@DisplayName("PromptConfig Tests")
class PromptConfigTest {

    @Test
    @DisplayName("Should create builder instance")
    void testBuilderCreation() {
        PromptConfig.Builder builder = PromptConfig.builder();
        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should build config with all prompts unset (null)")
    void testBuilderWithDefaults() {
        PromptConfig config = PromptConfig.builder().build();

        assertNull(config.getPreviousRoundToolCompressPrompt());
        assertNull(config.getPreviousRoundSummaryPrompt());
        assertNull(config.getCurrentRoundLargeMessagePrompt());
        assertNull(config.getCurrentRoundCompressPrompt());
    }

    @Test
    @DisplayName("Should set and get previousRoundToolCompressPrompt")
    void testPreviousRoundToolCompressPrompt() {
        String customPrompt = "Custom tool compress prompt";
        PromptConfig config =
                PromptConfig.builder().previousRoundToolCompressPrompt(customPrompt).build();

        assertEquals(customPrompt, config.getPreviousRoundToolCompressPrompt());
        assertNull(config.getPreviousRoundSummaryPrompt());
        assertNull(config.getCurrentRoundLargeMessagePrompt());
        assertNull(config.getCurrentRoundCompressPrompt());
    }

    @Test
    @DisplayName("Should set and get previousRoundSummaryPrompt")
    void testPreviousRoundSummaryPrompt() {
        String customPrompt = "Custom summary prompt";
        PromptConfig config =
                PromptConfig.builder().previousRoundSummaryPrompt(customPrompt).build();

        assertNull(config.getPreviousRoundToolCompressPrompt());
        assertEquals(customPrompt, config.getPreviousRoundSummaryPrompt());
        assertNull(config.getCurrentRoundLargeMessagePrompt());
        assertNull(config.getCurrentRoundCompressPrompt());
    }

    @Test
    @DisplayName("Should set and get currentRoundLargeMessagePrompt")
    void testCurrentRoundLargeMessagePrompt() {
        String customPrompt = "Custom large message prompt";
        PromptConfig config =
                PromptConfig.builder().currentRoundLargeMessagePrompt(customPrompt).build();

        assertNull(config.getPreviousRoundToolCompressPrompt());
        assertNull(config.getPreviousRoundSummaryPrompt());
        assertEquals(customPrompt, config.getCurrentRoundLargeMessagePrompt());
        assertNull(config.getCurrentRoundCompressPrompt());
    }

    @Test
    @DisplayName("Should set and get currentRoundCompressPrompt")
    void testCurrentRoundCompressPrompt() {
        String customPrompt = "Custom compress prompt";
        PromptConfig config =
                PromptConfig.builder().currentRoundCompressPrompt(customPrompt).build();

        assertNull(config.getPreviousRoundToolCompressPrompt());
        assertNull(config.getPreviousRoundSummaryPrompt());
        assertNull(config.getCurrentRoundLargeMessagePrompt());
        assertEquals(customPrompt, config.getCurrentRoundCompressPrompt());
    }

    @Test
    @DisplayName("Should set all prompts")
    void testSetAllPrompts() {
        String prompt1 = "Custom tool compress prompt";
        String prompt4 = "Custom summary prompt";
        String prompt5 = "Custom large message prompt";
        String prompt6 = "Custom compress prompt";

        PromptConfig config =
                PromptConfig.builder()
                        .previousRoundToolCompressPrompt(prompt1)
                        .previousRoundSummaryPrompt(prompt4)
                        .currentRoundLargeMessagePrompt(prompt5)
                        .currentRoundCompressPrompt(prompt6)
                        .build();

        assertEquals(prompt1, config.getPreviousRoundToolCompressPrompt());
        assertEquals(prompt4, config.getPreviousRoundSummaryPrompt());
        assertEquals(prompt5, config.getCurrentRoundLargeMessagePrompt());
        assertEquals(prompt6, config.getCurrentRoundCompressPrompt());
    }

    @Test
    @DisplayName("Should support builder method chaining")
    void testBuilderMethodChaining() {
        PromptConfig.Builder builder = PromptConfig.builder();

        // All methods should return the builder instance for chaining
        PromptConfig.Builder result =
                builder.previousRoundToolCompressPrompt("prompt1")
                        .previousRoundSummaryPrompt("prompt4")
                        .currentRoundLargeMessagePrompt("prompt5")
                        .currentRoundCompressPrompt("prompt6");

        assertNotNull(result);
        assertEquals(builder, result);
    }

    @Test
    @DisplayName("Should build multiple independent config instances")
    void testMultipleConfigInstances() {
        PromptConfig config1 =
                PromptConfig.builder().previousRoundToolCompressPrompt("prompt1").build();

        PromptConfig config2 = PromptConfig.builder().previousRoundSummaryPrompt("prompt4").build();

        // Configs should be independent
        assertEquals("prompt1", config1.getPreviousRoundToolCompressPrompt());
        assertNull(config1.getPreviousRoundSummaryPrompt());

        assertNull(config2.getPreviousRoundToolCompressPrompt());
        assertEquals("prompt4", config2.getPreviousRoundSummaryPrompt());
    }

    @Test
    @DisplayName("Should allow partial configuration")
    void testPartialConfiguration() {
        PromptConfig config =
                PromptConfig.builder()
                        .previousRoundToolCompressPrompt("custom tool prompt")
                        .currentRoundCompressPrompt("custom compress prompt")
                        .build();

        assertEquals("custom tool prompt", config.getPreviousRoundToolCompressPrompt());
        assertNull(config.getPreviousRoundSummaryPrompt());
        assertNull(config.getCurrentRoundLargeMessagePrompt());
        assertEquals("custom compress prompt", config.getCurrentRoundCompressPrompt());
    }

    @Test
    @DisplayName("Should handle empty string prompts")
    void testEmptyStringPrompts() {
        PromptConfig config =
                PromptConfig.builder()
                        .previousRoundToolCompressPrompt("")
                        .previousRoundSummaryPrompt("")
                        .build();

        assertEquals("", config.getPreviousRoundToolCompressPrompt());
        assertEquals("", config.getPreviousRoundSummaryPrompt());
    }

    @Test
    @DisplayName("Should create independent instances on multiple build() calls")
    void testMultipleBuildCalls() {
        PromptConfig.Builder builder =
                PromptConfig.builder()
                        .previousRoundToolCompressPrompt("prompt1")
                        .previousRoundSummaryPrompt("prompt4");

        // First build
        PromptConfig config1 = builder.build();

        // Modify builder after first build
        builder.currentRoundLargeMessagePrompt("prompt5");

        // Second build - should create a new instance
        PromptConfig config2 = builder.build();

        // Verify they are different instances
        assertNotSame(config1, config2);

        // Verify config1 is not affected by builder modifications after first build
        assertEquals("prompt1", config1.getPreviousRoundToolCompressPrompt());
        assertEquals("prompt4", config1.getPreviousRoundSummaryPrompt());
        assertNull(config1.getCurrentRoundLargeMessagePrompt());
        assertNull(config1.getCurrentRoundCompressPrompt());

        // Verify config2 has all the values set before second build
        assertEquals("prompt1", config2.getPreviousRoundToolCompressPrompt());
        assertEquals("prompt4", config2.getPreviousRoundSummaryPrompt());
        assertEquals("prompt5", config2.getCurrentRoundLargeMessagePrompt());
        assertNull(config2.getCurrentRoundCompressPrompt());

        // Modify config1 should not affect config2
        // (This test ensures immutability, though PromptConfig fields are not final)
        // Since String is immutable, we can't directly test this, but the fact that
        // they are different instances is sufficient
    }
}
