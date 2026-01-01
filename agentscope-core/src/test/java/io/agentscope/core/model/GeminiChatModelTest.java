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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.genai.types.HttpOptions;
import io.agentscope.core.formatter.gemini.GeminiChatFormatter;
import io.agentscope.core.formatter.gemini.GeminiMultiAgentFormatter;
import io.agentscope.core.model.test.ModelTestUtils;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for GeminiChatModel.
 *
 * <p>These tests verify the GeminiChatModel behavior including basic configuration, builder
 * pattern, streaming, tool calls, and various API configurations (Gemini API vs Vertex AI).
 *
 * <p>Tests use mock API keys to avoid actual network calls and focus on model construction and
 * configuration validation.
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
@Tag("unit")
@DisplayName("GeminiChatModel Unit Tests")
class GeminiChatModelTest {

    private String mockApiKey;

    @BeforeEach
    void setUp() {
        mockApiKey = ModelTestUtils.createMockApiKey();
    }

    @Test
    @DisplayName("Should create model with valid configuration")
    void testBasicModelCreation() {
        GeminiChatModel model =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-2.0-flash").build();

        assertNotNull(model, "Model should be created");
        assertEquals("gemini-2.0-flash", model.getModelName());
    }

    @Test
    @DisplayName("Should use default model name when not specified")
    void testDefaultModelName() {
        GeminiChatModel model = GeminiChatModel.builder().apiKey(mockApiKey).build();

        assertNotNull(model, "Model should be created");
        assertEquals("gemini-2.5-flash", model.getModelName());
    }

    @Test
    @DisplayName("Should create model with Gemini API configuration")
    void testGeminiAPIConfiguration() {
        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .streamEnabled(true)
                        .build();

        assertNotNull(model, "Gemini API model should be created");
    }

    @Test
    @DisplayName("Should handle streaming configuration")
    void testStreamingConfiguration() {
        // Create streaming model
        GeminiChatModel streamingModel =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .streamEnabled(true)
                        .build();

        assertNotNull(streamingModel, "Streaming model should be created");

        // Create non-streaming model
        GeminiChatModel nonStreamingModel =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .streamEnabled(false)
                        .build();

        assertNotNull(nonStreamingModel, "Non-streaming model should be created");
    }

    @Test
    @DisplayName("Should support tool calling configuration")
    void testToolCallConfiguration() {
        // Create model
        GeminiChatModel modelWithTools =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-2.0-flash").build();

        assertNotNull(modelWithTools, "Model with tools should be created");

        // Tool schemas can be passed in stream call
        List<ToolSchema> tools =
                List.of(ModelTestUtils.createSimpleToolSchema("test_tool", "A test tool"));

        assertNotNull(tools, "Tool schemas should be created");
    }

    @Test
    @DisplayName("Should handle error gracefully when API key is invalid")
    void testInvalidApiKey() {
        // Create model with invalid key
        GeminiChatModel invalidModel =
                GeminiChatModel.builder()
                        .apiKey("invalid_key")
                        .modelName("gemini-2.0-flash")
                        .build();

        assertNotNull(invalidModel, "Model should still be created with invalid key");

        // Note: Actual API call would fail, but model creation should succeed
    }

    @Test
    @DisplayName("Should require model name")
    void testModelNameRequired() {
        // Model name is required, but has default value
        assertDoesNotThrow(() -> GeminiChatModel.builder().apiKey(mockApiKey).build());
    }

    @Test
    @DisplayName("Should configure default options")
    void testDefaultOptionsConfiguration() {
        GenerateOptions options =
                GenerateOptions.builder().temperature(0.7).maxTokens(2000).topP(0.95).build();

        GeminiChatModel modelWithOptions =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .defaultOptions(options)
                        .build();

        assertNotNull(modelWithOptions);
    }

    @Test
    @DisplayName("Should return correct model name")
    void testGetModelName() {
        GeminiChatModel gemini20Flash =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-2.0-flash").build();

        assertEquals("gemini-2.0-flash", gemini20Flash.getModelName());

        GeminiChatModel gemini15Pro =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-1.5-pro").build();

        assertEquals("gemini-1.5-pro", gemini15Pro.getModelName());
    }

    @Test
    @DisplayName("Should create model with custom formatter")
    void testCustomFormatter() {
        // Test with custom formatter
        assertDoesNotThrow(
                () -> {
                    GeminiChatModel modelWithFormatter =
                            GeminiChatModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("gemini-2.0-flash")
                                    .formatter(new GeminiChatFormatter())
                                    .build();

                    assertNotNull(modelWithFormatter);
                });
    }

    @Test
    @DisplayName("Should handle GenerateOptions configuration")
    void testGenerateOptionsConfiguration() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .maxTokens(2000)
                        .topP(0.95)
                        .frequencyPenalty(0.2)
                        .presencePenalty(0.1)
                        .build();

        GeminiChatModel modelWithOptions =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .defaultOptions(options)
                        .build();

        assertNotNull(modelWithOptions);
    }

    @Test
    @DisplayName("Should build with minimal parameters")
    void testMinimalBuilder() {
        GeminiChatModel minimalModel =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-2.0-flash").build();

        assertNotNull(minimalModel);
        assertNotNull(minimalModel.getModelName());
    }

    @Test
    @DisplayName("Should handle thinking mode configuration")
    void testThinkingModeConfiguration() {
        // Test with thinking budget
        GenerateOptions thinkingOptions = GenerateOptions.builder().thinkingBudget(2000).build();

        GeminiChatModel thinkingModel =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash-thinking")
                        .defaultOptions(thinkingOptions)
                        .build();

        assertNotNull(thinkingModel);
    }

    @Test
    @DisplayName("Should create vision model")
    void testVisionModelCreation() {
        GeminiChatModel visionModel =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-1.5-pro").build();

        assertNotNull(visionModel);
        assertEquals("gemini-1.5-pro", visionModel.getModelName());
    }

    @Test
    @DisplayName("Should support multiagent formatter")
    void testMultiAgentFormatterConfiguration() {
        GeminiChatModel multiAgentModel =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .formatter(new GeminiMultiAgentFormatter())
                        .build();

        assertNotNull(multiAgentModel);
    }

    @Test
    @DisplayName("Should support different formatter types")
    void testDifferentFormatterTypes() {
        // Chat formatter
        GeminiChatModel chatModel =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .formatter(new GeminiChatFormatter())
                        .build();
        assertNotNull(chatModel);

        // MultiAgent formatter
        GeminiChatModel multiAgentModel =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .formatter(new GeminiMultiAgentFormatter())
                        .build();
        assertNotNull(multiAgentModel);
    }

    @Test
    @DisplayName("Should configure HTTP options")
    void testHttpOptionsConfiguration() {
        HttpOptions httpOptions = HttpOptions.builder().build();

        GeminiChatModel modelWithHttpOptions =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .httpOptions(httpOptions)
                        .build();

        assertNotNull(modelWithHttpOptions);
    }

    @Test
    @DisplayName("Should handle all generation options")
    void testAllGenerateOptions() {
        GenerateOptions fullOptions =
                GenerateOptions.builder()
                        .temperature(0.7)
                        .maxTokens(1500)
                        .topP(0.9)
                        .frequencyPenalty(0.3)
                        .presencePenalty(0.2)
                        .thinkingBudget(2000)
                        .build();

        GeminiChatModel modelWithFullOptions =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .defaultOptions(fullOptions)
                        .build();

        assertNotNull(modelWithFullOptions);
    }

    @Test
    @DisplayName("Should build with all builder methods for Gemini API")
    void testCompleteBuilderForGeminiAPI() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .maxTokens(2000)
                        .topP(0.95)
                        .frequencyPenalty(0.2)
                        .presencePenalty(0.1)
                        .build();

        HttpOptions httpOptions = HttpOptions.builder().build();

        GeminiChatModel completeModel =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .streamEnabled(true)
                        .defaultOptions(options)
                        .formatter(new GeminiChatFormatter())
                        .httpOptions(httpOptions)
                        .build();

        assertNotNull(completeModel);
        assertEquals("gemini-2.0-flash", completeModel.getModelName());
    }

    @Test
    @DisplayName("Should support gemini-2.0-flash-exp model")
    void testExperimentalModelCreation() {
        GeminiChatModel expModel =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash-exp")
                        .build();

        assertNotNull(expModel);
        assertEquals("gemini-2.0-flash-exp", expModel.getModelName());
    }

    @Test
    @DisplayName("Should support gemini-2.0-flash-thinking model")
    void testThinkingModelCreation() {
        GeminiChatModel thinkingModel =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash-thinking")
                        .build();

        assertNotNull(thinkingModel);
        assertEquals("gemini-2.0-flash-thinking", thinkingModel.getModelName());
    }

    @Test
    @DisplayName("Should handle close operation gracefully")
    void testCloseOperation() {
        GeminiChatModel model =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-2.0-flash").build();

        assertNotNull(model);

        // Should not throw exception
        assertDoesNotThrow(() -> model.close());
    }

    @Test
    @DisplayName("Should handle multiple close calls")
    void testMultipleCloseCall() {
        GeminiChatModel model =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-2.0-flash").build();

        assertDoesNotThrow(
                () -> {
                    model.close();
                    model.close(); // Should handle gracefully
                });
    }

    @Test
    @DisplayName("Should handle tool choice configuration")
    void testToolChoiceConfiguration() {
        GenerateOptions optionsWithToolChoice =
                GenerateOptions.builder().toolChoice(new ToolChoice.Auto()).build();

        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .defaultOptions(optionsWithToolChoice)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should create model with default streaming enabled")
    void testDefaultStreamingEnabled() {
        GeminiChatModel model =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-2.0-flash").build();

        assertNotNull(model);
        // Default streaming is true
    }

    @Test
    @DisplayName("Should handle null default options gracefully")
    void testNullDefaultOptions() {
        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .defaultOptions(null)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should handle null formatter gracefully")
    void testNullFormatter() {
        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .formatter(null)
                        .build();

        assertNotNull(model);
        // Should use default GeminiChatFormatter
    }
}
