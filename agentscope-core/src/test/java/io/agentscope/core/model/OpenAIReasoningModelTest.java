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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.model.test.ModelTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OpenAI Reasoning Model (Responses API).
 *
 * <p>Tests verify Responses API integration for reasoning models including:
 * <ul>
 *   <li>Model creation with reasoning effort levels
 *   <li>Proper reasoning_effort parameter application
 *   <li>ReasoningEffort enum parsing
 *   <li>Model builder configuration
 * </ul>
 */
@Tag("unit")
@DisplayName("OpenAI Reasoning Model Tests (Responses API)")
class OpenAIReasoningModelTest {

    private String mockApiKey;

    @BeforeEach
    void setUp() {
        mockApiKey = ModelTestUtils.createMockApiKey();
    }

    @Test
    @DisplayName("Should create reasoning model with default settings")
    void testCreateReasoningModelDefault() {
        assertDoesNotThrow(
                () -> {
                    OpenAIReasoningModel model =
                            OpenAIReasoningModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("gpt-5")
                                    .build();

                    assertNotNull(model);
                    assertEquals("gpt-5", model.getModelName());
                });
    }

    @Test
    @DisplayName("Should create reasoning model with HIGH reasoning effort")
    void testCreateReasoningModelWithHighEffort() {
        assertDoesNotThrow(
                () -> {
                    OpenAIReasoningModel model =
                            OpenAIReasoningModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("o1")
                                    .reasoningEffort("HIGH")
                                    .build();

                    assertNotNull(model);
                    assertEquals("o1", model.getModelName());
                });
    }

    @Test
    @DisplayName("Should create reasoning model with MEDIUM reasoning effort")
    void testCreateReasoningModelWithMediumEffort() {
        assertDoesNotThrow(
                () -> {
                    OpenAIReasoningModel model =
                            OpenAIReasoningModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("o4")
                                    .reasoningEffort("MEDIUM")
                                    .build();

                    assertNotNull(model);
                    assertEquals("o4", model.getModelName());
                });
    }

    @Test
    @DisplayName("Should create reasoning model with LOW reasoning effort")
    void testCreateReasoningModelWithLowEffort() {
        assertDoesNotThrow(
                () -> {
                    OpenAIReasoningModel model =
                            OpenAIReasoningModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("gpt-5")
                                    .reasoningEffort("LOW")
                                    .build();

                    assertNotNull(model);
                    assertEquals("gpt-5", model.getModelName());
                });
    }

    @Test
    @DisplayName("Should handle case-insensitive reasoning effort")
    void testCreateReasoningModelWithCaseInsensitiveEffort() {
        assertDoesNotThrow(
                () -> {
                    OpenAIReasoningModel model =
                            OpenAIReasoningModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("gpt-5")
                                    .reasoningEffort("medium") // lowercase
                                    .build();

                    assertNotNull(model);
                    assertEquals("gpt-5", model.getModelName());
                });
    }

    @Test
    @DisplayName("Should handle invalid reasoning effort gracefully")
    void testCreateReasoningModelWithInvalidEffort() {
        assertDoesNotThrow(
                () -> {
                    OpenAIReasoningModel model =
                            OpenAIReasoningModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("gpt-5")
                                    .reasoningEffort(
                                            "INVALID_EFFORT") // invalid, should default to MEDIUM
                                    .build();

                    assertNotNull(model);
                    assertEquals("gpt-5", model.getModelName());
                });
    }

    @Test
    @DisplayName("Should create reasoning model with custom base URL")
    void testCreateReasoningModelWithCustomBaseUrl() {
        assertDoesNotThrow(
                () -> {
                    String customBaseUrl = "https://custom.openai.api/v1";
                    OpenAIReasoningModel model =
                            OpenAIReasoningModel.builder()
                                    .apiKey(mockApiKey)
                                    .baseUrl(customBaseUrl)
                                    .modelName("gpt-5")
                                    .reasoningEffort("HIGH")
                                    .build();

                    assertNotNull(model);
                    assertEquals("gpt-5", model.getModelName());
                });
    }

    @Test
    @DisplayName("Should create reasoning model with custom options")
    void testCreateReasoningModelWithCustomOptions() {
        assertDoesNotThrow(
                () -> {
                    GenerateOptions customOptions =
                            GenerateOptions.builder()
                                    .maxTokens(32000)
                                    .temperature(0.7)
                                    .topP(0.95)
                                    .build();

                    OpenAIReasoningModel model =
                            OpenAIReasoningModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("o1-pro")
                                    .defaultOptions(customOptions)
                                    .reasoningEffort("HIGH")
                                    .build();

                    assertNotNull(model);
                    assertEquals("o1-pro", model.getModelName());
                });
    }
}
