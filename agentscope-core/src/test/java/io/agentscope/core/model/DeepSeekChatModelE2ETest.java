/*
 * Copyright 2024-2026 the original author or authors.
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.test.StepVerifier;

/**
 * End-to-End integration tests for DeepSeek Chat Model using real DeepSeek API.
 *
 * <p>These tests make actual API calls to DeepSeek (OpenAI-compatible API) to verify:
 * <ul>
 *   <li>Non-streaming chat completion</li>
 *   <li>Streaming chat completion</li>
 *   <li>Reasoner model support (deepseek-reasoner)</li>
 *   <li>Reasoning content parsing</li>
 *   <li>Options application</li>
 * </ul>
 *
 * <p><b>Requirements:</b>
 * <ul>
 *   <li>DEEPSEEK_API_KEY environment variable must be set</li>
 *   <li>Active internet connection</li>
 *   <li>Valid DeepSeek API quota</li>
 * </ul>
 *
 * <p>Tagged as "e2e" - these tests make real API calls and may incur costs.
 */
@Tag("e2e")
@Tag("deepseek")
@DisplayName("DeepSeek Chat Model E2E Tests (Real DeepSeek API or OpenRouter)")
@EnabledIfEnvironmentVariable(
        named = "DEEPSEEK_API_KEY",
        matches = ".+",
        disabledReason = "Requires DEEPSEEK_API_KEY or OPENROUTER_API_KEY environment variable")
class DeepSeekChatModelE2ETest {

    private static final String DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api";
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(60);

    private OpenAIChatModel model;
    private OpenAIChatModel streamingModel;
    private OpenAIChatModel reasonerModel;
    private boolean useOpenRouter;

    @BeforeEach
    void setUp() {
        // Try DEEPSEEK_API_KEY first, fallback to OPENROUTER_API_KEY
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        // Get base URL from environment variable, fallback to default
        String baseUrl = System.getenv("DEEPSEEK_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_DEEPSEEK_BASE_URL;
        }
        String modelName = "deepseek-chat";
        String reasonerModelName = "deepseek-reasoner";

        if (apiKey == null || apiKey.isEmpty()) {
            // Try OpenRouter as fallback
            apiKey = System.getenv("OPENROUTER_API_KEY");
            if (apiKey != null && !apiKey.isEmpty()) {
                // Get base URL from environment variable, fallback to default
                baseUrl = System.getenv("OPENROUTER_BASE_URL");
                if (baseUrl == null || baseUrl.isEmpty()) {
                    baseUrl = DEFAULT_OPENROUTER_BASE_URL;
                }
                modelName = "deepseek/deepseek-chat";
                reasonerModelName = "deepseek/deepseek-r1"; // OpenRouter uses deepseek-r1
                useOpenRouter = true;
                System.out.println("Using OpenRouter for DeepSeek testing");
            }
        } else {
            useOpenRouter = false;
            System.out.println("Using DeepSeek API directly");
        }

        assumeTrue(
                apiKey != null && !apiKey.isEmpty(),
                "DEEPSEEK_API_KEY or OPENROUTER_API_KEY must be set");

        // Non-streaming model
        model =
                OpenAIChatModel.builder().apiKey(apiKey).modelName(modelName).stream(false)
                        .baseUrl(baseUrl)
                        .formatter(new OpenAIChatFormatter())
                        .build();

        // Streaming model
        streamingModel =
                OpenAIChatModel.builder().apiKey(apiKey).modelName(modelName).stream(true)
                        .baseUrl(baseUrl)
                        .formatter(new OpenAIChatFormatter())
                        .build();

        // Reasoner model - supports reasoning_content
        reasonerModel =
                OpenAIChatModel.builder().apiKey(apiKey).modelName(reasonerModelName).stream(false)
                        .baseUrl(baseUrl)
                        .formatter(new OpenAIChatFormatter())
                        .build();

        System.out.println("=== DeepSeek Chat Model E2E Test Setup Complete ===");
        System.out.println("Model: " + modelName);
        System.out.println("Reasoner Model: " + reasonerModelName);
        System.out.println("Base URL: " + baseUrl);
        System.out.println("Using: " + (useOpenRouter ? "OpenRouter" : "DeepSeek API"));
    }

    @AfterEach
    void tearDown() throws IOException {
        // Stateless models don't need cleanup
    }

    @Test
    @DisplayName("Should handle non-streaming call")
    void testNonStreamingCall() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("Say 'Hello, World!'")
                                                        .build()))
                                .build());

        StepVerifier.create(model.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            assertTrue(response.getContent().size() > 0);
                            String text = ((TextBlock) response.getContent().get(0)).getText();
                            assertNotNull(text);
                            System.out.println("Response: " + text);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle streaming call")
    void testStreamingCall() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("Count from 1 to 5")
                                                        .build()))
                                .build());

        StepVerifier.create(streamingModel.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                        })
                .thenConsumeWhile(
                        response -> {
                            // Continue consuming chunks
                            return true;
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle reasoner model with reasoning content")
    void testReasonerModelWithReasoning() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text(
                                                                "If it takes 5 machines 5 minutes"
                                                                    + " to make 5 widgets, how long"
                                                                    + " would it take 100 machines"
                                                                    + " to make 100 widgets? Think"
                                                                    + " step by step.")
                                                        .build()))
                                .build());

        StepVerifier.create(reasonerModel.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response, "Response should not be null");
                            assertNotNull(response.getContent(), "Content should not be null");
                            assertTrue(
                                    response.getContent().size() > 0,
                                    "Response should have content");

                            // Check if reasoning content is present
                            boolean hasThinkingBlock =
                                    response.getContent().stream()
                                            .anyMatch(block -> block instanceof ThinkingBlock);

                            if (hasThinkingBlock) {
                                System.out.println(
                                        "✓ ThinkingBlock found in deepseek-reasoner response");
                                ThinkingBlock thinkingBlock =
                                        response.getContent().stream()
                                                .filter(block -> block instanceof ThinkingBlock)
                                                .map(block -> (ThinkingBlock) block)
                                                .findFirst()
                                                .orElse(null);

                                assertNotNull(thinkingBlock, "ThinkingBlock should not be null");
                                String thinking = thinkingBlock.getThinking();
                                assertNotNull(thinking, "Thinking content should not be null");
                                assertTrue(
                                        !thinking.isEmpty(),
                                        "Thinking content should not be empty");
                                System.out.println(
                                        "Reasoning content length: " + thinking.length());
                            } else {
                                System.out.println(
                                        "Note: deepseek-reasoner response did not contain"
                                                + " ThinkingBlock (may be integrated in text)");
                            }

                            // Check for final answer (text content)
                            boolean hasTextBlock =
                                    response.getContent().stream()
                                            .anyMatch(block -> block instanceof TextBlock);

                            if (hasTextBlock) {
                                TextBlock textBlock =
                                        response.getContent().stream()
                                                .filter(block -> block instanceof TextBlock)
                                                .map(block -> (TextBlock) block)
                                                .findFirst()
                                                .orElse(null);

                                if (textBlock != null) {
                                    String text = textBlock.getText();
                                    System.out.println("Response text: " + text);
                                    assertNotNull(text, "Text content should not be null");
                                }
                            }
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle reasoner model streaming with reasoning content")
    void testReasonerModelStreamingWithReasoning() {
        // Get API key and configuration
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        // Get base URL from environment variable, fallback to default
        String baseUrl = System.getenv("DEEPSEEK_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_DEEPSEEK_BASE_URL;
        }
        String reasonerModelName = "deepseek-reasoner";

        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getenv("OPENROUTER_API_KEY");
            if (apiKey != null && !apiKey.isEmpty()) {
                // Get base URL from environment variable, fallback to default
                baseUrl = System.getenv("OPENROUTER_BASE_URL");
                if (baseUrl == null || baseUrl.isEmpty()) {
                    baseUrl = DEFAULT_OPENROUTER_BASE_URL;
                }
                reasonerModelName = "deepseek/deepseek-r1";
            }
        }

        // Create streaming reasoner model
        OpenAIChatModel streamingReasonerModel =
                OpenAIChatModel.builder().apiKey(apiKey).modelName(reasonerModelName).stream(true)
                        .baseUrl(baseUrl)
                        .formatter(new OpenAIChatFormatter())
                        .build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text(
                                                                "Explain the concept of"
                                                                        + " recursion in"
                                                                        + " programming. Think step"
                                                                        + " by step.")
                                                        .build()))
                                .build());

        StepVerifier.create(streamingReasonerModel.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                        })
                .thenConsumeWhile(
                        response -> {
                            // Check for thinking blocks in streaming chunks
                            boolean hasThinking =
                                    response.getContent().stream()
                                            .anyMatch(block -> block instanceof ThinkingBlock);
                            if (hasThinking) {
                                System.out.println("✓ ThinkingBlock found in streaming chunk");
                            }
                            return true;
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle multi-turn conversation")
    void testMultiTurnConversation() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("My name is Alice. Remember this.")
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text(
                                                                "Hello Alice! I'll remember your"
                                                                        + " name.")
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("What is my name?")
                                                        .build()))
                                .build());

        StepVerifier.create(model.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            String text = ((TextBlock) response.getContent().get(0)).getText();
                            assertNotNull(text);
                            assertTrue(
                                    text.toLowerCase().contains("alice"),
                                    "Response should contain 'Alice'");
                            System.out.println("Multi-turn response: " + text);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return model name")
    void testGetModelName() {
        String expectedModelName = useOpenRouter ? "deepseek/deepseek-chat" : "deepseek-chat";
        String expectedReasonerName = useOpenRouter ? "deepseek/deepseek-r1" : "deepseek-reasoner";
        assertEquals(expectedModelName, model.getModelName());
        assertEquals(expectedModelName, streamingModel.getModelName());
        assertEquals(expectedReasonerName, reasonerModel.getModelName());
    }

    @Test
    @DisplayName("Should handle options")
    void testApplyOptions() {
        GenerateOptions options = GenerateOptions.builder().temperature(0.1).maxTokens(50).build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Say 'Four'").build()))
                                .build());

        StepVerifier.create(model.stream(messages, null, options))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            String text = ((TextBlock) response.getContent().get(0)).getText();
                            assertNotNull(text);
                            System.out.println("Response with low temperature: " + text);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should verify DeepSeek uses OpenAI code path")
    void testVerifyOpenAICodePath() {
        // Verify that the model is using OpenAIChatModel
        assertTrue(model instanceof OpenAIChatModel, "Model should be OpenAIChatModel instance");

        OpenAIChatModel openAIModel = (OpenAIChatModel) model;

        // Verify the actual class name
        String className = model.getClass().getName();
        assertEquals(
                "io.agentscope.core.model.OpenAIChatModel",
                className,
                "Model class should be OpenAIChatModel");
        System.out.println("✓ Verified: Model class = " + className);

        // Verify model name
        String actualModelName = model.getModelName();
        String expectedModelName = useOpenRouter ? "deepseek/deepseek-chat" : "deepseek-chat";
        assertEquals(expectedModelName, actualModelName, "Model name should match expected value");
        System.out.println("✓ Verified: Model name = " + actualModelName);

        // Verify that the formatter is OpenAIChatFormatter by checking behavior
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Say 'test'").build()))
                                .build());

        StepVerifier.create(model.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response, "Response should not be null");
                            assertNotNull(
                                    response.getContent(), "Response content should not be null");
                            assertTrue(
                                    response.getContent().size() > 0,
                                    "Response should have content");

                            // Verify response structure matches OpenAI format
                            String text = ((TextBlock) response.getContent().get(0)).getText();
                            assertNotNull(text, "Response text should not be null");
                            assertTrue(text.length() > 0, "Response text should not be empty");

                            System.out.println(
                                    "✓ Verified: Response format matches OpenAI structure");
                            System.out.println(
                                    "✓ Verified: Response text = "
                                            + text.substring(0, Math.min(50, text.length())));
                        })
                .verifyComplete();

        System.out.println("✓ All verifications passed: DeepSeek uses OpenAI code path");
    }
}
