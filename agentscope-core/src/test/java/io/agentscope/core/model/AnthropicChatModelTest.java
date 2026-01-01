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

import io.agentscope.core.formatter.anthropic.AnthropicChatFormatter;
import io.agentscope.core.formatter.anthropic.AnthropicMultiAgentFormatter;
import io.agentscope.core.model.test.ModelTestUtils;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AnthropicChatModel.
 *
 * <p>These tests verify the AnthropicChatModel behavior including builder pattern, configuration
 * options, and basic model creation.
 *
 * <p>Tests focus on model configuration and setup. Actual API interactions are tested in
 * integration tests.
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
@Tag("unit")
@DisplayName("AnthropicChatModel Unit Tests")
class AnthropicChatModelTest {

    private String mockApiKey;

    @BeforeEach
    void setUp() {
        mockApiKey = ModelTestUtils.createMockApiKey();
    }

    @Test
    @DisplayName("Should create model with valid configuration")
    void testBasicModelCreation() {
        AnthropicChatModel model =
                AnthropicChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("claude-sonnet-4-5-20250929")
                        .stream(false)
                        .build();

        assertNotNull(model, "Model should be created");
        assertEquals("claude-sonnet-4-5-20250929", model.getModelName());
    }

    @Test
    @DisplayName("Should create model with default model name")
    void testDefaultModelName() {
        AnthropicChatModel model = AnthropicChatModel.builder().apiKey(mockApiKey).build();

        assertNotNull(model, "Model should be created with default settings");
        assertEquals(
                "claude-sonnet-4-5-20250929",
                model.getModelName(),
                "Default model should be claude-sonnet-4-5-20250929");
    }

    @Test
    @DisplayName("Should create model with different Claude versions")
    void testDifferentClaudeVersions() {
        // Claude 3.5 Sonnet
        AnthropicChatModel sonnet35 =
                AnthropicChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("claude-3-5-sonnet-20241022")
                        .build();
        assertNotNull(sonnet35, "Claude 3.5 Sonnet model should be created");
        assertEquals("claude-3-5-sonnet-20241022", sonnet35.getModelName());

        // Claude 3 Opus
        AnthropicChatModel opus3 =
                AnthropicChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("claude-3-opus-20240229")
                        .build();
        assertNotNull(opus3, "Claude 3 Opus model should be created");
        assertEquals("claude-3-opus-20240229", opus3.getModelName());

        // Claude 3 Haiku
        AnthropicChatModel haiku3 =
                AnthropicChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("claude-3-haiku-20240307")
                        .build();
        assertNotNull(haiku3, "Claude 3 Haiku model should be created");
        assertEquals("claude-3-haiku-20240307", haiku3.getModelName());
    }

    @Test
    @DisplayName("Should handle streaming configuration")
    void testStreamingConfiguration() {
        // Create streaming model
        AnthropicChatModel streamingModel =
                AnthropicChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("claude-sonnet-4-5-20250929")
                        .stream(true)
                        .build();

        assertNotNull(streamingModel, "Streaming model should be created");

        // Create non-streaming model
        AnthropicChatModel nonStreamingModel =
                AnthropicChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("claude-sonnet-4-5-20250929")
                        .stream(false)
                        .build();

        assertNotNull(nonStreamingModel, "Non-streaming model should be created");
    }

    @Test
    @DisplayName("Should support default streaming mode")
    void testDefaultStreamingMode() {
        // Default should be streaming enabled
        AnthropicChatModel model = AnthropicChatModel.builder().apiKey(mockApiKey).build();

        assertNotNull(model, "Model should be created with default streaming");
    }

    @Test
    @DisplayName("Should support tool calling configuration")
    void testToolCallConfiguration() {
        // Create model for tool calling
        AnthropicChatModel modelWithTools =
                AnthropicChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("claude-sonnet-4-5-20250929")
                        .build();

        assertNotNull(modelWithTools, "Model with tools should be created");

        // Tool schemas can be passed in stream call
        List<ToolSchema> tools =
                List.of(
                        ModelTestUtils.createSimpleToolSchema(
                                "get_weather", "Get weather information"),
                        ModelTestUtils.createSimpleToolSchema("web_search", "Search the web"));

        assertNotNull(tools, "Tool schemas should be created");
    }

    @Test
    @DisplayName("Should create model with null API key to use environment variable")
    void testNullApiKey() {
        // Null API key should work - it will load from ANTHROPIC_API_KEY env var
        AnthropicChatModel model =
                AnthropicChatModel.builder()
                        .apiKey(null)
                        .modelName("claude-sonnet-4-5-20250929")
                        .build();

        assertNotNull(model, "Model should be created with null API key");
    }

    @Test
    @DisplayName("Should configure default generation options")
    void testDefaultGenerateOptions() {
        GenerateOptions options =
                GenerateOptions.builder().temperature(0.7).maxTokens(2000).topP(0.9).build();

        assertDoesNotThrow(
                () -> {
                    AnthropicChatModel modelWithOptions =
                            AnthropicChatModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("claude-sonnet-4-5-20250929")
                                    .defaultOptions(options)
                                    .build();

                    assertNotNull(modelWithOptions);
                });
    }

    @Test
    @DisplayName("Should create model with null default options")
    void testNullDefaultOptions() {
        // Null default options should work
        AnthropicChatModel model =
                AnthropicChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("claude-sonnet-4-5-20250929")
                        .defaultOptions(null)
                        .build();

        assertNotNull(model, "Model should be created with null default options");
    }

    @Test
    @DisplayName("Should return correct model name")
    void testGetModelName() {
        AnthropicChatModel sonnetModel =
                AnthropicChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("claude-sonnet-4-5-20250929")
                        .build();

        assertEquals("claude-sonnet-4-5-20250929", sonnetModel.getModelName());

        AnthropicChatModel opusModel =
                AnthropicChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("claude-3-opus-20240229")
                        .build();

        assertEquals("claude-3-opus-20240229", opusModel.getModelName());
    }

    @Test
    @DisplayName("Should create model with custom formatter")
    void testCustomChatFormatter() {
        assertDoesNotThrow(
                () -> {
                    AnthropicChatModel modelWithFormatter =
                            AnthropicChatModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("claude-sonnet-4-5-20250929")
                                    .formatter(new AnthropicChatFormatter())
                                    .build();

                    assertNotNull(modelWithFormatter);
                });
    }

    @Test
    @DisplayName("Should create model with multi-agent formatter")
    void testCustomMultiAgentFormatter() {
        assertDoesNotThrow(
                () -> {
                    AnthropicChatModel modelWithMultiAgentFormatter =
                            AnthropicChatModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("claude-sonnet-4-5-20250929")
                                    .formatter(new AnthropicMultiAgentFormatter())
                                    .build();

                    assertNotNull(modelWithMultiAgentFormatter);
                });
    }

    @Test
    @DisplayName("Should create model with null formatter to use default")
    void testNullFormatterUsesDefault() {
        // Null formatter should use default AnthropicChatFormatter
        AnthropicChatModel model =
                AnthropicChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("claude-sonnet-4-5-20250929")
                        .formatter(null)
                        .build();

        assertNotNull(model, "Model should be created with default formatter");
    }

    @Test
    @DisplayName("Should support custom base URL")
    void testCustomBaseUrl() {
        assertDoesNotThrow(
                () -> {
                    AnthropicChatModel modelWithCustomUrl =
                            AnthropicChatModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("claude-sonnet-4-5-20250929")
                                    .baseUrl("https://custom-anthropic-endpoint.com")
                                    .build();

                    assertNotNull(modelWithCustomUrl);
                });
    }

    @Test
    @DisplayName("Should create model with null base URL to use default")
    void testNullBaseUrlUsesDefault() {
        // Null base URL should use Anthropic's default endpoint
        AnthropicChatModel model =
                AnthropicChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("claude-sonnet-4-5-20250929")
                        .baseUrl(null)
                        .build();

        assertNotNull(model, "Model should be created with default base URL");
    }

    @Test
    @DisplayName("Should create model with all configuration options")
    void testFullConfiguration() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .maxTokens(4000)
                        .topP(0.95)
                        .toolChoice(new ToolChoice.Auto())
                        .build();

        assertDoesNotThrow(
                () -> {
                    AnthropicChatModel fullyConfiguredModel =
                            AnthropicChatModel.builder()
                                    .baseUrl("https://api.anthropic.com")
                                    .apiKey(mockApiKey)
                                    .modelName("claude-sonnet-4-5-20250929")
                                    .stream(true)
                                    .defaultOptions(options)
                                    .formatter(new AnthropicChatFormatter())
                                    .build();

                    assertNotNull(fullyConfiguredModel);
                    assertEquals("claude-sonnet-4-5-20250929", fullyConfiguredModel.getModelName());
                });
    }

    @Test
    @DisplayName("Should create builder and reuse it")
    void testBuilderReuse() {
        // Test that builder can be reused
        AnthropicChatModel.Builder builder =
                AnthropicChatModel.builder().apiKey(mockApiKey).stream(true);

        AnthropicChatModel model1 = builder.modelName("claude-sonnet-4-5-20250929").build();
        assertNotNull(model1);

        AnthropicChatModel model2 = builder.modelName("claude-3-opus-20240229").build();
        assertNotNull(model2);
        assertEquals("claude-3-opus-20240229", model2.getModelName());
    }

    @Test
    @DisplayName("Should handle vision models")
    void testVisionModels() {
        // Claude models support vision natively
        AnthropicChatModel visionModel =
                AnthropicChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("claude-sonnet-4-5-20250929")
                        .build();

        assertNotNull(visionModel, "Vision-capable model should be created");
    }

    @Test
    @DisplayName("Should support extended thinking models")
    void testExtendedThinkingModels() {
        // Claude Sonnet 4.5 supports extended thinking
        AnthropicChatModel thinkingModel =
                AnthropicChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("claude-sonnet-4-5-20250929")
                        .build();

        assertNotNull(thinkingModel, "Extended thinking model should be created");
    }

    @Test
    @DisplayName("Should create model with minimal configuration")
    void testMinimalConfiguration() {
        // Minimal configuration - just API key
        AnthropicChatModel minimalModel = AnthropicChatModel.builder().apiKey(mockApiKey).build();

        assertNotNull(minimalModel, "Model should be created with minimal configuration");
        assertEquals(
                "claude-sonnet-4-5-20250929",
                minimalModel.getModelName(),
                "Should use default model name");
    }

    @Test
    @DisplayName("Should create model with streaming disabled")
    void testStreamingDisabled() {
        AnthropicChatModel nonStreamingModel =
                AnthropicChatModel.builder().apiKey(mockApiKey).stream(false).build();

        assertNotNull(nonStreamingModel, "Non-streaming model should be created");
    }
}
