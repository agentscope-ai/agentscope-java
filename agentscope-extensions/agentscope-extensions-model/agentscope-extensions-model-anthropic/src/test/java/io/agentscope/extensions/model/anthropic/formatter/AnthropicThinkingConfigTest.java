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
package io.agentscope.extensions.model.anthropic.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ThinkingConfigParam;
import io.agentscope.core.model.GenerateOptions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Anthropic thinking config tests")
class AnthropicThinkingConfigTest {

    private static MessageCreateParams params(
            GenerateOptions options, GenerateOptions defaultOptions) {
        MessageCreateParams.Builder builder =
                MessageCreateParams.builder()
                        .model("claude-sonnet-4-5")
                        .maxTokens(8192)
                        .messages(
                                List.of(
                                        MessageParam.builder()
                                                .role(MessageParam.Role.USER)
                                                .content("hi")
                                                .build()));
        AnthropicToolsHelper.applyOptions(builder, options, defaultOptions);
        return builder.build();
    }

    private static MessageCreateParams params(GenerateOptions options) {
        return params(options, null);
    }

    @Test
    @DisplayName("Should omit thinking when no thinking option is set")
    void testNoThinkingOptionsOmitted() {
        assertTrue(params(GenerateOptions.builder().build()).thinking().isEmpty());
    }

    @Test
    @DisplayName("Should map thinkingBudget to enabled thinking")
    void testThinkingBudgetMappedToEnabled() {
        ThinkingConfigParam thinking =
                params(GenerateOptions.builder().thinkingBudget(4096).build())
                        .thinking()
                        .orElseThrow();
        assertEquals(4096, thinking.enabled().orElseThrow().budgetTokens());
    }

    @Test
    @DisplayName("Should map includeThoughts=false to disabled thinking")
    void testIncludeThoughtsFalseMappedToDisabled() {
        ThinkingConfigParam thinking =
                params(GenerateOptions.builder().includeThoughts(false).build())
                        .thinking()
                        .orElseThrow();
        assertTrue(thinking.isDisabled());
    }

    @Test
    @DisplayName("Explicit disable takes precedence over thinkingBudget")
    void testDisablePrecedesBudget() {
        ThinkingConfigParam thinking =
                params(
                                GenerateOptions.builder()
                                        .includeThoughts(false)
                                        .thinkingBudget(4096)
                                        .build())
                        .thinking()
                        .orElseThrow();
        assertTrue(thinking.isDisabled());
    }

    @Test
    @DisplayName("includeThoughts=true without budget enables the API minimum budget")
    void testIncludeThoughtsTrueDefaultBudget() {
        ThinkingConfigParam thinking =
                params(GenerateOptions.builder().includeThoughts(true).build())
                        .thinking()
                        .orElseThrow();
        assertEquals(1024, thinking.enabled().orElseThrow().budgetTokens());
    }

    @Test
    @DisplayName("includeThoughts=true with budget uses the given budget")
    void testIncludeThoughtsTrueWithBudget() {
        ThinkingConfigParam thinking =
                params(GenerateOptions.builder().includeThoughts(true).thinkingBudget(2048).build())
                        .thinking()
                        .orElseThrow();
        assertEquals(2048, thinking.enabled().orElseThrow().budgetTokens());
    }

    @Test
    @DisplayName("Should fall back to defaultOptions thinkingBudget")
    void testDefaultOptionsFallback() {
        ThinkingConfigParam thinking =
                params(
                                GenerateOptions.builder().build(),
                                GenerateOptions.builder().thinkingBudget(3000).build())
                        .thinking()
                        .orElseThrow();
        assertEquals(3000, thinking.enabled().orElseThrow().budgetTokens());
    }

    @Test
    @DisplayName("Request-level thinkingBudget overrides defaultOptions")
    void testRequestOverridesDefault() {
        ThinkingConfigParam thinking =
                params(
                                GenerateOptions.builder().thinkingBudget(2048).build(),
                                GenerateOptions.builder().thinkingBudget(3000).build())
                        .thinking()
                        .orElseThrow();
        assertEquals(2048, thinking.enabled().orElseThrow().budgetTokens());
    }

    @Test
    @DisplayName("Additional body thinking property survives the typed mapping")
    void testAdditionalBodyThinkingPreserved() {
        MessageCreateParams built =
                params(
                        GenerateOptions.builder()
                                .thinkingBudget(4096)
                                .additionalBodyParams(
                                        Map.of("thinking", Map.of("type", "adaptive")))
                                .build());
        assertEquals(4096, built.thinking().orElseThrow().enabled().orElseThrow().budgetTokens());
        assertTrue(built._additionalBodyProperties().containsKey("thinking"));
    }

    @Test
    @DisplayName("reasoningEffort alone does not set thinking")
    void testReasoningEffortNotMapped() {
        assertTrue(
                params(GenerateOptions.builder().reasoningEffort("high").build())
                        .thinking()
                        .isEmpty());
    }
}
