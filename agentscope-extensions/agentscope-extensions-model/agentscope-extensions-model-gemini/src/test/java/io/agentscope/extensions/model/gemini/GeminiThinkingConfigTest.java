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
package io.agentscope.extensions.model.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.ThinkingConfig;
import io.agentscope.core.model.GenerateOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Gemini thinkingConfig tests")
class GeminiThinkingConfigTest {

    private static ThinkingConfig thinkingConfig(
            String modelName, GenerateOptions options, GenerateOptions defaultOptions) {
        GeminiChatModel.Builder builder =
                GeminiChatModel.builder().apiKey("test-key").modelName(modelName);
        if (defaultOptions != null) {
            builder.defaultOptions(defaultOptions);
        }
        GenerateContentConfig config = builder.build().buildGenerateContentConfig(null, options);
        return config.thinkingConfig().orElse(null);
    }

    private static ThinkingConfig thinkingConfig(String modelName, GenerateOptions options) {
        return thinkingConfig(modelName, options, null);
    }

    @Test
    @DisplayName("Should omit thinkingConfig when no thinking option is set")
    void testNoThinkingOptionsOmitted() {
        assertNull(thinkingConfig("gemini-2.5-pro", GenerateOptions.builder().build()));
    }

    @Test
    @DisplayName("Should map includeThoughts directly")
    void testIncludeThoughtsMappedDirectly() {
        ThinkingConfig config =
                thinkingConfig(
                        "gemini-2.5-pro", GenerateOptions.builder().includeThoughts(false).build());

        assertFalse(config.includeThoughts().orElseThrow());
        assertTrue(config.thinkingBudget().isEmpty());
        assertTrue(config.thinkingLevel().isEmpty());
    }

    @Test
    @DisplayName("Should map thinkingBudget without deriving another option")
    void testThinkingBudgetMappedDirectly() {
        ThinkingConfig config =
                thinkingConfig(
                        "gemini-2.5-pro", GenerateOptions.builder().thinkingBudget(4096).build());

        assertEquals(4096, config.thinkingBudget().orElseThrow());
        assertTrue(config.includeThoughts().isEmpty());
        assertTrue(config.thinkingLevel().isEmpty());
    }

    @Test
    @DisplayName("Should map reasoningEffort to an uppercase thinking level")
    void testReasoningEffortMappedToThinkingLevel() {
        ThinkingConfig config =
                thinkingConfig(
                        "gemini-4-argon",
                        GenerateOptions.builder().reasoningEffort("high").build());

        assertEquals("HIGH", config.thinkingLevel().orElseThrow().toString());
        assertTrue(config.includeThoughts().isEmpty());
        assertTrue(config.thinkingBudget().isEmpty());
    }

    @Test
    @DisplayName("Should map all thinking options together")
    void testAllThinkingOptionsMappedTogether() {
        ThinkingConfig config =
                thinkingConfig(
                        "gemini-4-argon",
                        GenerateOptions.builder()
                                .includeThoughts(true)
                                .thinkingBudget(2048)
                                .reasoningEffort("xhigh")
                                .build());

        assertTrue(config.includeThoughts().orElseThrow());
        assertEquals(2048, config.thinkingBudget().orElseThrow());
        assertEquals("XHIGH", config.thinkingLevel().orElseThrow().toString());
    }

    @Test
    @DisplayName("Should inherit thinking options from default options")
    void testDefaultOptionsInherited() {
        ThinkingConfig config =
                thinkingConfig(
                        "gemini-4-argon",
                        null,
                        GenerateOptions.builder()
                                .includeThoughts(true)
                                .reasoningEffort("low")
                                .build());

        assertTrue(config.includeThoughts().orElseThrow());
        assertEquals("LOW", config.thinkingLevel().orElseThrow().toString());
    }

    @Test
    @DisplayName("Should let per-call options override default options")
    void testPerCallOptionsOverrideDefaults() {
        ThinkingConfig config =
                thinkingConfig(
                        "gemini-4-argon",
                        GenerateOptions.builder()
                                .includeThoughts(false)
                                .reasoningEffort("high")
                                .build(),
                        GenerateOptions.builder()
                                .includeThoughts(true)
                                .reasoningEffort("low")
                                .build());

        assertFalse(config.includeThoughts().orElseThrow());
        assertEquals("HIGH", config.thinkingLevel().orElseThrow().toString());
    }
}
