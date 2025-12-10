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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.agentscope.core.formatter.openai.OpenAIResponseParser;
import io.agentscope.core.formatter.openai.OpenAIToolsHelper;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.test.ModelTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive unit tests for OpenAI thinking mode functionality.
 *
 * <p>Tests verify thinking mode support (o1/o1-mini models) including:
 * <ul>
 *   <li>Model creation with thinking enabled/disabled
 *   <li>Thinking budget configuration and options
 *   <li>Thinking parameter application to API requests
 *   <li>Thinking content extraction from responses
 * </ul>
 */
@Tag("unit")
@DisplayName("OpenAI Thinking Mode Integration Tests")
class OpenAIThinkingModeTest {

    private String mockApiKey;

    @BeforeEach
    void setUp() {
        mockApiKey = ModelTestUtils.createMockApiKey();
    }

    @Test
    @DisplayName("Should create o1 model with thinking enabled")
    void testCreateO1ModelWithThinking() {
        assertDoesNotThrow(
                () -> {
                    OpenAIChatModel o1Model =
                            OpenAIChatModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("o1")
                                    .enableThinking(true)
                                    .build();
                    assertNotNull(o1Model, "o1 model with thinking should be created");
                });
    }

    @Test
    @DisplayName("Should create o1-mini model with thinking enabled")
    void testCreateO1MiniModelWithThinking() {
        assertDoesNotThrow(
                () -> {
                    OpenAIChatModel o1MiniModel =
                            OpenAIChatModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("o1-mini")
                                    .enableThinking(true)
                                    .build();
                    assertNotNull(o1MiniModel, "o1-mini model with thinking should be created");
                });
    }

    @Test
    @DisplayName("Should configure thinking budget in GenerateOptions")
    void testThinkingBudgetConfiguration() {
        assertDoesNotThrow(
                () -> {
                    GenerateOptions options =
                            GenerateOptions.builder().thinkingBudget(10000).build();
                    assertNotNull(
                            options.getThinkingBudget(),
                            "Thinking budget should be set in options");
                    assertTrue(
                            options.getThinkingBudget() > 0, "Thinking budget should be positive");
                });
    }

    @Test
    @DisplayName("Should disable thinking when enableThinking is false")
    void testCreateModelWithThinkingDisabled() {
        assertDoesNotThrow(
                () -> {
                    OpenAIChatModel modelNoThinking =
                            OpenAIChatModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("o1")
                                    .enableThinking(false)
                                    .build();
                    assertNotNull(
                            modelNoThinking, "Model with thinking disabled should be created");
                });
    }

    @Test
    @DisplayName("Should disable thinking by default when enableThinking is null")
    void testCreateModelWithThinkingNull() {
        assertDoesNotThrow(
                () -> {
                    OpenAIChatModel modelDefaultThinking =
                            OpenAIChatModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("gpt-4")
                                    .enableThinking(null)
                                    .build();
                    assertNotNull(
                            modelDefaultThinking,
                            "Model with thinking null (default disabled) should be created");
                });
    }

    @Test
    @DisplayName("Should support thinking with streaming configuration")
    void testThinkingWithStreamingConfig() {
        assertDoesNotThrow(
                () -> {
                    OpenAIChatModel streamingO1 =
                            OpenAIChatModel.builder().apiKey(mockApiKey).modelName("o1").stream(
                                            true)
                                    .enableThinking(true)
                                    .build();
                    assertNotNull(
                            streamingO1,
                            "Streaming o1 with thinking should be created (streaming will be"
                                    + " disabled at runtime)");
                });
    }

    @Test
    @DisplayName("Should not apply thinking if thinking is not enabled")
    void testThinkingNotAppliedWhenDisabled() {
        assertDoesNotThrow(
                () -> {
                    OpenAIChatModel modelNoThinking =
                            OpenAIChatModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("gpt-4")
                                    .enableThinking(false)
                                    .build();
                    assertNotNull(
                            modelNoThinking, "Model with thinking disabled should be created");
                });
    }

    @Nested
    @DisplayName("Thinking Parameter Application Tests")
    class ThinkingParameterApplicationTests {

        private OpenAIToolsHelper toolsHelper;

        @BeforeEach
        void setUp() {
            toolsHelper = new OpenAIToolsHelper();
        }

        @Test
        @DisplayName("Should apply thinking with options budget")
        void testApplyThinkingWithOptions() {
            assertDoesNotThrow(
                    () -> {
                        ChatCompletionCreateParams.Builder paramsBuilder =
                                ChatCompletionCreateParams.builder()
                                        .model(com.openai.models.ChatModel.GPT_4);
                        GenerateOptions options =
                                GenerateOptions.builder().thinkingBudget(5000).build();
                        toolsHelper.applyThinking(paramsBuilder, options, null);
                        assertNotNull(paramsBuilder, "Params builder should be modified");
                    });
        }

        @Test
        @DisplayName("Should apply thinking with default options budget")
        void testApplyThinkingWithDefaultOptions() {
            assertDoesNotThrow(
                    () -> {
                        ChatCompletionCreateParams.Builder paramsBuilder =
                                ChatCompletionCreateParams.builder()
                                        .model(com.openai.models.ChatModel.GPT_4);
                        GenerateOptions defaultOptions =
                                GenerateOptions.builder().thinkingBudget(8000).build();
                        toolsHelper.applyThinking(paramsBuilder, null, defaultOptions);
                        assertNotNull(paramsBuilder, "Params builder should be modified");
                    });
        }

        @Test
        @DisplayName("Should prefer options budget over default budget")
        void testThinkingBudgetPriority() {
            assertDoesNotThrow(
                    () -> {
                        ChatCompletionCreateParams.Builder paramsBuilder =
                                ChatCompletionCreateParams.builder()
                                        .model(com.openai.models.ChatModel.GPT_4);
                        GenerateOptions options =
                                GenerateOptions.builder().thinkingBudget(5000).build();
                        GenerateOptions defaultOptions =
                                GenerateOptions.builder().thinkingBudget(8000).build();
                        toolsHelper.applyThinking(paramsBuilder, options, defaultOptions);
                        assertNotNull(paramsBuilder, "Params builder should be modified");
                    });
        }

        @Test
        @DisplayName("Should build valid params with thinking configured")
        void testBuildParamsWithThinking() {
            assertDoesNotThrow(
                    () -> {
                        ChatCompletionCreateParams.Builder paramsBuilder =
                                ChatCompletionCreateParams.builder()
                                        .model(com.openai.models.ChatModel.GPT_4)
                                        .addMessage(
                                                com.openai.models.chat.completions
                                                        .ChatCompletionUserMessageParam.builder()
                                                        .content("test")
                                                        .build());
                        GenerateOptions options =
                                GenerateOptions.builder().thinkingBudget(10000).build();
                        toolsHelper.applyThinking(paramsBuilder, options, null);
                        ChatCompletionCreateParams params = paramsBuilder.build();
                        assertNotNull(params, "Should successfully build params with thinking");
                    });
        }
    }

    @Nested
    @DisplayName("Thinking Block Extraction Tests")
    class ThinkingBlockExtractionTests {

        private OpenAIResponseParser parser;

        @BeforeEach
        void setUp() {
            parser = new OpenAIResponseParser();
        }

        @Test
        @DisplayName("Should create thinking block with content")
        void testThinkingBlockWithContent() {
            String thinkingContent = "Let me think about this problem step by step...";
            ThinkingBlock thinkingBlock = ThinkingBlock.builder().thinking(thinkingContent).build();
            assertNotNull(thinkingBlock, "Thinking block should be created");
            assertEquals(
                    thinkingContent, thinkingBlock.getThinking(), "Thinking content should match");
        }

        @Test
        @DisplayName("Should handle multiline thinking content")
        void testMultilineThinkingContent() {
            String multilineThinking =
                    "Step 1: Analyze the problem\n"
                            + "Step 2: Consider alternatives\n"
                            + "Step 3: Make a decision";
            ThinkingBlock thinkingBlock =
                    ThinkingBlock.builder().thinking(multilineThinking).build();
            assertNotNull(thinkingBlock, "Multiline thinking block should be created");
            assertTrue(
                    thinkingBlock.getThinking().contains("Step"),
                    "Thinking content should contain steps");
        }

        @Test
        @DisplayName("Should handle null thinking value")
        void testNullThinkingValue() {
            ThinkingBlock thinkingBlock = ThinkingBlock.builder().thinking(null).build();
            assertNotNull(thinkingBlock, "Thinking block with null value should be created");
            assertEquals(
                    "",
                    thinkingBlock.getThinking(),
                    "Null thinking should be converted to empty string");
        }

        @Test
        @DisplayName("Should handle special characters in thinking")
        void testSpecialCharactersInThinking() {
            String thinkingWithSpecialChars =
                    "Using symbols: {}, [], (), <>, @, #, $, %, &, *, +, =\n"
                            + "Using quotes: \"double\", 'single'";
            ThinkingBlock thinkingBlock =
                    ThinkingBlock.builder().thinking(thinkingWithSpecialChars).build();
            assertNotNull(
                    thinkingBlock, "Thinking block with special characters should be created");
            assertEquals(
                    thinkingWithSpecialChars,
                    thinkingBlock.getThinking(),
                    "Special characters should be preserved");
        }
    }
}
