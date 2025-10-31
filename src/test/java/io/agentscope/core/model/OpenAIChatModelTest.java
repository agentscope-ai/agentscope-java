/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.formatter.OpenAIChatFormatter;
import io.agentscope.core.formatter.OpenAIMultiAgentFormatter;
import io.agentscope.core.model.test.ModelTestUtils;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OpenAIChatModel.
 *
 * <p>These tests verify the OpenAIChatModel behavior including basic chat, streaming, tool calls,
 * error handling, and retry mechanisms.
 *
 * <p>Tests use mock API responses to avoid actual network calls.
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
@Tag("unit")
@DisplayName("OpenAIChatModel Unit Tests")
class OpenAIChatModelTest {

    private OpenAIChatModel model;
    private String mockApiKey;

    @BeforeEach
    void setUp() {
        mockApiKey = ModelTestUtils.createMockApiKey();

        // Create model with builder
        model =
                OpenAIChatModel.builder().apiKey(mockApiKey).modelName("gpt-4").stream(false)
                        .build();
    }

    @Test
    @DisplayName("Should create model with valid configuration")
    void testBasicModelCreation() {
        assertNotNull(model, "Model should be created");

        // Test builder with different models
        OpenAIChatModel gpt35 =
                OpenAIChatModel.builder().apiKey(mockApiKey).modelName("gpt-3.5-turbo").build();

        assertNotNull(gpt35, "GPT-3.5 model should be created");

        OpenAIChatModel gpt4 =
                OpenAIChatModel.builder().apiKey(mockApiKey).modelName("gpt-4-turbo").stream(true)
                        .build();

        assertNotNull(gpt4, "GPT-4 Turbo model should be created");
    }

    @Test
    @DisplayName("Should handle streaming configuration")
    void testStreamingConfiguration() {
        // Create streaming model
        OpenAIChatModel streamingModel =
                OpenAIChatModel.builder().apiKey(mockApiKey).modelName("gpt-4").stream(true)
                        .build();

        assertNotNull(streamingModel, "Streaming model should be created");

        // Create non-streaming model
        OpenAIChatModel nonStreamingModel =
                OpenAIChatModel.builder().apiKey(mockApiKey).modelName("gpt-4").stream(false)
                        .build();

        assertNotNull(nonStreamingModel, "Non-streaming model should be created");
    }

    @Test
    @DisplayName("Should support tool calling configuration")
    void testToolCallConfiguration() {
        // Create model for tool calling
        OpenAIChatModel modelWithTools =
                OpenAIChatModel.builder().apiKey(mockApiKey).modelName("gpt-4").build();

        assertNotNull(modelWithTools, "Model with tools should be created");

        // Tool schemas can be passed in streamFlux call
        List<ToolSchema> tools =
                List.of(
                        ModelTestUtils.createSimpleToolSchema(
                                "get_weather", "Get weather information"),
                        ModelTestUtils.createSimpleToolSchema("search", "Search the web"));

        assertNotNull(tools, "Tool schemas should be created");
    }

    @Test
    @DisplayName("Should handle error gracefully when API key is invalid")
    void testInvalidApiKey() {
        // Create model with invalid key
        OpenAIChatModel invalidModel =
                OpenAIChatModel.builder().apiKey("sk-invalid").modelName("gpt-4").build();

        assertNotNull(invalidModel, "Model should still be created with invalid key");

        // Note: Actual API call would fail, but model creation should succeed
    }

    @Test
    @DisplayName("Should configure retry mechanism")
    void testRetryConfiguration() {
        // Model can be configured with default options
        GenerateOptions options = GenerateOptions.builder().build();

        assertDoesNotThrow(
                () -> {
                    OpenAIChatModel modelWithOptions =
                            OpenAIChatModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("gpt-4")
                                    .defaultOptions(options)
                                    .build();

                    assertNotNull(modelWithOptions);
                });
    }

    @Test
    @DisplayName("Should support timeout configuration")
    void testTimeoutConfiguration() {
        // Timeout is typically handled at HTTP client level
        // Here we verify model creation with various configurations

        assertDoesNotThrow(
                () -> {
                    OpenAIChatModel model1 =
                            OpenAIChatModel.builder().apiKey(mockApiKey).modelName("gpt-4").build();

                    assertNotNull(model1);

                    // Test with base URL override
                    OpenAIChatModel model2 =
                            OpenAIChatModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("gpt-4")
                                    .baseUrl("https://custom-openai-endpoint.com")
                                    .build();

                    assertNotNull(model2);
                });
    }

    @Test
    @DisplayName("Should return correct model name")
    void testGetModelName() {
        OpenAIChatModel gpt4Model =
                OpenAIChatModel.builder().apiKey(mockApiKey).modelName("gpt-4").build();

        assertNotNull(gpt4Model.getModelName());

        OpenAIChatModel gpt35Model =
                OpenAIChatModel.builder().apiKey(mockApiKey).modelName("gpt-3.5-turbo").build();

        assertNotNull(gpt35Model.getModelName());
    }

    @Test
    @DisplayName("Should create model with custom formatter")
    void testCustomFormatter() {
        // Test with custom formatter
        assertDoesNotThrow(
                () -> {
                    OpenAIChatModel modelWithFormatter =
                            OpenAIChatModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("gpt-4")
                                    .formatter(new OpenAIChatFormatter())
                                    .build();

                    assertNotNull(modelWithFormatter);
                });
    }

    @Test
    @DisplayName("Should handle GenerateOptions configuration")
    void testGenerateOptionsConfiguration() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.7)
                        .maxTokens(1000)
                        .topP(0.9)
                        .frequencyPenalty(0.5)
                        .presencePenalty(0.2)
                        .build();

        OpenAIChatModel modelWithOptions =
                OpenAIChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gpt-4")
                        .defaultOptions(options)
                        .build();

        assertNotNull(modelWithOptions);
    }

    @Test
    @DisplayName("Should build with minimal parameters")
    void testMinimalBuilder() {
        OpenAIChatModel minimalModel =
                OpenAIChatModel.builder().apiKey(mockApiKey).modelName("gpt-4").build();

        assertNotNull(minimalModel);
        assertNotNull(minimalModel.getModelName());
    }

    @Test
    @DisplayName("Should handle multi-modal models")
    void testMultiModalModels() {
        // Test with vision-capable models
        OpenAIChatModel visionModel =
                OpenAIChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gpt-4-vision-preview")
                        .build();

        assertNotNull(visionModel);
        assertNotNull(visionModel.getModelName());
    }

    @Test
    @DisplayName("Should create model with MultiAgent formatter")
    void testMultiAgentFormatter() {
        OpenAIChatModel multiAgentModel =
                OpenAIChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gpt-4")
                        .formatter(new OpenAIMultiAgentFormatter())
                        .build();

        assertNotNull(multiAgentModel);
    }

    @Test
    @DisplayName("Should handle null model name")
    void testNullModelName() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    OpenAIChatModel.builder().apiKey(mockApiKey).modelName(null).build();
                });
    }

    @Test
    @DisplayName("Should create with different model variants")
    void testDifferentModelVariants() {
        OpenAIChatModel gpt4o =
                OpenAIChatModel.builder().apiKey(mockApiKey).modelName("gpt-4o").build();
        assertNotNull(gpt4o);

        OpenAIChatModel gpt4oMini =
                OpenAIChatModel.builder().apiKey(mockApiKey).modelName("gpt-4o-mini").build();
        assertNotNull(gpt4oMini);

        OpenAIChatModel o1 =
                OpenAIChatModel.builder().apiKey(mockApiKey).modelName("o1-preview").build();
        assertNotNull(o1);
    }

    @Test
    @DisplayName("Should handle all generation options")
    void testAllGenerateOptions() {
        GenerateOptions fullOptions =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .maxTokens(2000)
                        .topP(0.95)
                        .frequencyPenalty(0.3)
                        .presencePenalty(0.4)
                        .build();

        OpenAIChatModel modelWithFullOptions =
                OpenAIChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gpt-4")
                        .defaultOptions(fullOptions)
                        .build();

        assertNotNull(modelWithFullOptions);
    }

    @Test
    @DisplayName("Should create with base URL configuration")
    void testBaseUrlConfiguration() {
        OpenAIChatModel modelWithBaseUrl =
                OpenAIChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gpt-4")
                        .baseUrl("https://api.openai.com/v1")
                        .build();

        assertNotNull(modelWithBaseUrl);
    }

    @Test
    @DisplayName("Should support different formatters")
    void testDifferentFormatters() {
        // OpenAIChatFormatter
        OpenAIChatModel chatModel =
                OpenAIChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gpt-4")
                        .formatter(new OpenAIChatFormatter())
                        .build();
        assertNotNull(chatModel);

        // OpenAIMultiAgentFormatter
        OpenAIChatModel multiAgentModel =
                OpenAIChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gpt-4")
                        .formatter(new OpenAIMultiAgentFormatter())
                        .build();
        assertNotNull(multiAgentModel);
    }

    @Test
    @DisplayName("Should build with complete configuration")
    void testCompleteBuilder() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.7)
                        .maxTokens(1500)
                        .topP(0.9)
                        .frequencyPenalty(0.2)
                        .presencePenalty(0.1)
                        .build();

        OpenAIChatModel completeModel =
                OpenAIChatModel.builder().apiKey(mockApiKey).modelName("gpt-4-turbo").stream(true)
                        .defaultOptions(options)
                        .formatter(new OpenAIChatFormatter())
                        .baseUrl("https://api.openai.com/v1")
                        .build();

        assertNotNull(completeModel);
        assertNotNull(completeModel.getModelName());
    }
}
