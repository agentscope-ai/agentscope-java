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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.ThinkingConfig;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Wire-level tests for the model-name-aware Gemini thinking configuration.
 *
 * <p>Both the buffered and streamed request paths build their request config through
 * {@link GeminiChatModel#buildGenerateContentConfig}, so these assertions cover both paths.
 * Invalid combinations are additionally verified end-to-end on the stream path, where they
 * must surface as a {@link ModelException} before any dispatch.
 *
 * <p>Mapping under test (mirrors agentscope-python PR #3523 / #3675):
 * <ul>
 *   <li>No thinking option set: omit {@code thinkingConfig} (model default applies).</li>
 *   <li>Budget-era enable: {@code includeThoughts=true, thinkingBudget=&lt;budget or 1024&gt;}.</li>
 *   <li>Budget-era disable: {@code includeThoughts=false, thinkingBudget=0}.</li>
 *   <li>Gemini 4 enable: {@code includeThoughts=true, thinkingLevel=&lt;level&gt;} (no budget).</li>
 *   <li>Gemini 4 disable: {@code includeThoughts=false} (no budget, no level).</li>
 * </ul>
 */
@DisplayName("Gemini thinkingConfig tests")
class GeminiThinkingConfigTest {

    private static GeminiChatModel model(String modelName, GenerateOptions defaultOptions) {
        GeminiChatModel.Builder builder =
                GeminiChatModel.builder().apiKey("test-key").modelName(modelName);
        if (defaultOptions != null) {
            builder.defaultOptions(defaultOptions);
        }
        return builder.build();
    }

    private static ThinkingConfig thinkingConfig(
            String modelName, GenerateOptions options, GenerateOptions defaultOptions) {
        GenerateContentConfig config =
                model(modelName, defaultOptions).buildGenerateContentConfig(null, options);
        return config.thinkingConfig().orElse(null);
    }

    private static ThinkingConfig thinkingConfig(String modelName, GenerateOptions options) {
        return thinkingConfig(modelName, options, null);
    }

    @Test
    @DisplayName("Should omit thinkingConfig when no thinking option is set")
    void testNoThinkingOptionsOmitted() {
        assertNull(thinkingConfig("gemini-2.5-pro", GenerateOptions.builder().build()));
        assertNull(thinkingConfig("gemini-4-argon", GenerateOptions.builder().build()));
    }

    @Test
    @DisplayName("Should enable thinking on a budget-era model with a legacy budget")
    void testLegacyBudgetEnablesBudgetEraModel() {
        ThinkingConfig config =
                thinkingConfig(
                        "gemini-2.5-pro", GenerateOptions.builder().thinkingBudget(4096).build());

        assertTrue(config.includeThoughts().orElseThrow());
        assertEquals(4096, config.thinkingBudget().orElseThrow());
        assertTrue(config.thinkingLevel().isEmpty());
    }

    @Test
    @DisplayName("Should default to a 1024 budget when enabling without an explicit budget")
    void testEnableWithoutBudgetDefaultsTo1024() {
        ThinkingConfig config =
                thinkingConfig(
                        "gemini-2.5-pro", GenerateOptions.builder().includeThoughts(true).build());

        assertTrue(config.includeThoughts().orElseThrow());
        assertEquals(1024, config.thinkingBudget().orElseThrow());
    }

    @Test
    @DisplayName("Should treat a zero budget as an explicit disable on budget-era models")
    void testZeroBudgetDisablesBudgetEraModel() {
        ThinkingConfig config =
                thinkingConfig(
                        "gemini-2.5-pro", GenerateOptions.builder().thinkingBudget(0).build());

        assertFalse(config.includeThoughts().orElseThrow());
        assertEquals(0, config.thinkingBudget().orElseThrow());
    }

    @Test
    @DisplayName("Should disable thinking on a budget-era model with includeThoughts=false")
    void testExplicitDisableBudgetEraModel() {
        ThinkingConfig config =
                thinkingConfig(
                        "gemini-2.5-pro", GenerateOptions.builder().includeThoughts(false).build());

        assertFalse(config.includeThoughts().orElseThrow());
        assertEquals(0, config.thinkingBudget().orElseThrow());
    }

    @Test
    @DisplayName("Should ignore a budget when disabling on a budget-era model")
    void testDisableIgnoresBudgetBudgetEraModel() {
        ThinkingConfig config =
                thinkingConfig(
                        "gemini-2.5-pro",
                        GenerateOptions.builder()
                                .includeThoughts(false)
                                .thinkingBudget(4096)
                                .build());

        assertFalse(config.includeThoughts().orElseThrow());
        assertEquals(0, config.thinkingBudget().orElseThrow());
    }

    @Test
    @DisplayName("Should enable Gemini 4 with a thinking level and no budget")
    void testGemini4LevelEnable() {
        ThinkingConfig config =
                thinkingConfig(
                        "gemini-4-argon", GenerateOptions.builder().thinkingLevel("low").build());

        assertTrue(config.includeThoughts().orElseThrow());
        assertEquals("LOW", config.thinkingLevel().orElseThrow().toString());
        assertTrue(config.thinkingBudget().isEmpty());
    }

    @Test
    @DisplayName("Should gate gemini-4-carbon the same way as gemini-4-argon")
    void testGemini4CarbonAlsoGated() {
        ThinkingConfig config =
                thinkingConfig(
                        "gemini-4-carbon", GenerateOptions.builder().thinkingLevel("high").build());

        assertTrue(config.includeThoughts().orElseThrow());
        assertEquals("HIGH", config.thinkingLevel().orElseThrow().toString());
    }

    @Test
    @DisplayName("Should normalize the thinking level to uppercase")
    void testThinkingLevelNormalization() {
        ThinkingConfig config =
                thinkingConfig(
                        "gemini-4-argon",
                        GenerateOptions.builder().thinkingLevel(" High ").build());

        assertEquals("HIGH", config.thinkingLevel().orElseThrow().toString());
    }

    @Test
    @DisplayName("Should disable Gemini 4 with includeThoughts=false only")
    void testGemini4Disable() {
        ThinkingConfig config =
                thinkingConfig(
                        "gemini-4-argon", GenerateOptions.builder().includeThoughts(false).build());

        assertFalse(config.includeThoughts().orElseThrow());
        assertTrue(config.thinkingBudget().isEmpty());
        assertTrue(config.thinkingLevel().isEmpty());
    }

    @Test
    @DisplayName("Should reject a thinking budget on Gemini 4 models")
    void testGemini4BudgetRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        thinkingConfig(
                                "gemini-4-argon",
                                GenerateOptions.builder().thinkingBudget(4096).build()));
    }

    @Test
    @DisplayName("Should require a thinking level to enable Gemini 4")
    void testGemini4EnableRequiresLevel() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        thinkingConfig(
                                "gemini-4-argon",
                                GenerateOptions.builder().includeThoughts(true).build()));
    }

    @Test
    @DisplayName("Should reject a thinking level combined with disable on Gemini 4")
    void testGemini4DisableWithLevelRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        thinkingConfig(
                                "gemini-4-argon",
                                GenerateOptions.builder()
                                        .includeThoughts(false)
                                        .thinkingLevel("low")
                                        .build()));
    }

    @Test
    @DisplayName("Should reject a thinking level on budget-era models")
    void testLevelOnBudgetEraModelRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        thinkingConfig(
                                "gemini-2.5-pro",
                                GenerateOptions.builder().thinkingLevel("high").build()));
    }

    @Test
    @DisplayName("Should reject unsupported thinking level values")
    void testUnsupportedLevelRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        thinkingConfig(
                                "gemini-4-argon",
                                GenerateOptions.builder().thinkingLevel("xhigh").build()));
    }

    @Test
    @DisplayName("Should inherit thinking options from default options")
    void testDefaultOptionsInherited() {
        ThinkingConfig config =
                thinkingConfig(
                        "gemini-2.5-pro",
                        null,
                        GenerateOptions.builder().thinkingBudget(2048).build());

        assertTrue(config.includeThoughts().orElseThrow());
        assertEquals(2048, config.thinkingBudget().orElseThrow());
    }

    @Test
    @DisplayName("Should let per-call options override default options")
    void testPerCallOptionsOverrideDefaults() {
        ThinkingConfig config =
                thinkingConfig(
                        "gemini-2.5-pro",
                        GenerateOptions.builder().thinkingBudget(4096).build(),
                        GenerateOptions.builder().thinkingBudget(2048).build());

        assertEquals(4096, config.thinkingBudget().orElseThrow());
    }

    @Test
    @DisplayName("Should fail fast on the stream path with a ModelException before dispatch")
    void testStreamPathFailsFastBeforeDispatch() {
        GeminiChatModel model = model("gemini-4-argon", null);
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("hi").build()))
                                .build());
        GenerateOptions options = GenerateOptions.builder().thinkingBudget(4096).build();

        StepVerifier.create(model.doStream0(messages, null, options))
                .expectErrorSatisfies(
                        e -> {
                            assertTrue(e instanceof ModelException);
                            assertTrue(e.getMessage().contains("thinkingBudget"));
                        })
                .verify();
    }
}
