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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
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
 * End-to-End integration tests for OpenAIChatModel using real OpenRouter API.
 *
 * <p>These tests make actual API calls to OpenRouter (OpenAI-compatible API) to verify:
 * <ul>
 *   <li>Non-streaming chat completion</li>
 *   <li>Streaming chat completion</li>
 *   <li>Tool calling support</li>
 *   <li>Options application</li>
 *   <li>Error handling</li>
 * </ul>
 *
 * <p><b>Requirements:</b>
 * <ul>
 *   <li>OPENROUTER_API_KEY environment variable must be set</li>
 *   <li>Active internet connection</li>
 *   <li>Valid OpenRouter API quota</li>
 * </ul>
 *
 * <p>Tagged as "e2e" - these tests make real API calls and may incur costs.
 */
@Tag("e2e")
@Tag("openai")
@DisplayName("OpenAIChatModel E2E Tests (Real OpenRouter API)")
@EnabledIfEnvironmentVariable(
        named = "OPENROUTER_API_KEY",
        matches = ".+",
        disabledReason = "Requires OPENROUTER_API_KEY environment variable")
class OpenAIChatModelE2ETest {

    private static final String DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api";
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    private OpenAIChatModel model;
    private OpenAIChatModel streamingModel;
    private String openRouterBaseUrl;

    @BeforeEach
    void setUp() {
        String apiKey = System.getenv("OPENROUTER_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isEmpty(), "OPENROUTER_API_KEY must be set");

        // Get base URL from environment variable, fallback to default
        openRouterBaseUrl = System.getenv("OPENROUTER_BASE_URL");
        if (openRouterBaseUrl == null || openRouterBaseUrl.isEmpty()) {
            openRouterBaseUrl = DEFAULT_OPENROUTER_BASE_URL;
        }

        // Non-streaming model
        model =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("openai/gpt-4o-mini").stream(
                                false)
                        .baseUrl(openRouterBaseUrl)
                        .formatter(new OpenAIChatFormatter())
                        .build();

        // Streaming model
        streamingModel =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("openai/gpt-4o-mini").stream(
                                true)
                        .baseUrl(openRouterBaseUrl)
                        .formatter(new OpenAIChatFormatter())
                        .build();

        System.out.println("=== OpenAIChatModel E2E Test Setup Complete ===");
        System.out.println("Model: openai/gpt-4o-mini");
        System.out.println("Base URL: " + openRouterBaseUrl);
    }

    @AfterEach
    void tearDown() {
        // Stateless models don't need cleanup
    }

    @Test
    @DisplayName("Should make non-streaming call successfully")
    void testNonStreamingCall() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text(
                                                                "Say 'Hello, World!' and nothing"
                                                                        + " else")
                                                        .build()))
                                .build());

        StepVerifier.create(model.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            assertTrue(response.getContent().size() > 0);
                            String text =
                                    ((io.agentscope.core.message.TextBlock)
                                                    response.getContent().get(0))
                                            .getText();
                            assertNotNull(text);
                            assertTrue(
                                    text.toLowerCase().contains("hello"),
                                    "Response should contain 'hello'");
                            System.out.println("Response: " + text);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should make streaming call successfully")
    void testStreamingCall() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text(
                                                                "Count from 1 to 5, one number per"
                                                                        + " line")
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
                            assertNotNull(response);
                            return true;
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should apply generation options")
    void testApplyOptions() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("What is 2+2? Answer in one word.")
                                                        .build()))
                                .build());

        io.agentscope.core.model.GenerateOptions options =
                io.agentscope.core.model.GenerateOptions.builder()
                        .temperature(0.1)
                        .maxTokens(10)
                        .build();

        StepVerifier.create(model.stream(messages, null, options))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            String text =
                                    ((io.agentscope.core.message.TextBlock)
                                                    response.getContent().get(0))
                                            .getText();
                            assertNotNull(text);
                            System.out.println("Response with low temperature: " + text);
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
                            String text =
                                    ((io.agentscope.core.message.TextBlock)
                                                    response.getContent().get(0))
                                            .getText();
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
        assertEquals("openai/gpt-4o-mini", model.getModelName());
        assertEquals("openai/gpt-4o-mini", streamingModel.getModelName());
    }

    @Test
    @DisplayName("Should handle reasoning effort parameter")
    void testReasoningEffort() {
        // Create model with reasoning effort
        OpenAIChatModel reasoningModel =
                OpenAIChatModel.builder()
                        .apiKey(System.getenv("OPENROUTER_API_KEY"))
                        .modelName("openai/gpt-4o-mini")
                        .reasoningEffort("high")
                        .stream(false)
                        .baseUrl(openRouterBaseUrl)
                        .formatter(new OpenAIChatFormatter())
                        .build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("What is 2+2? Answer briefly.")
                                                        .build()))
                                .build());

        StepVerifier.create(reasoningModel.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            assertTrue(response.getContent().size() > 0);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should parse reasoning content if available")
    void testReasoningContentParsing() {
        // Test with a model that might return reasoning content
        // Note: gpt-4o-mini doesn't support reasoning, but we test the parsing logic
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text(
                                                                "Solve this step by step: If 5"
                                                                    + " machines make 5 widgets in"
                                                                    + " 5 minutes, how long for 100"
                                                                    + " machines to make 100"
                                                                    + " widgets?")
                                                        .build()))
                                .build());

        StepVerifier.create(model.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            // Even if no ThinkingBlock, response should be valid
                            assertTrue(response.getContent().size() > 0);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle o1 model with reasoning content")
    void testO1ModelWithReasoning() {
        String apiKey = System.getenv("OPENROUTER_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isEmpty(), "OPENROUTER_API_KEY must be set");

        // Try OpenRouter-supported thinking models (in order of preference)
        // Based on OpenRouter documentation: o3-mini, o3, o1-mini, o1, o1-preview
        // Also try DeepSeek R1 models which support reasoning
        String[] thinkingModelNames = {
            "openai/o3-mini", // Cost-efficient, supports reasoning_effort
            "openai/o3", // Full o3 model
            "openai/o1-mini", // Smaller o1 variant
            "openai/o1", // Full o1 model
            "openai/o1-preview", // Preview version
            "deepseek/deepseek-r1", // DeepSeek R1 reasoning model
            "deepseek/deepseek-r1-distill-llama-70b" // DeepSeek R1 Distill
        };
        OpenAIChatModel o1Model = null;
        String workingModelName = null;

        // Try to find a working thinking model
        for (String modelName : thinkingModelNames) {
            try {
                o1Model =
                        OpenAIChatModel.builder().apiKey(apiKey).modelName(modelName).stream(false)
                                .baseUrl(openRouterBaseUrl)
                                .formatter(new OpenAIChatFormatter())
                                .build();

                // Try a simple test call to see if model is available
                List<Msg> testMsg =
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("Say 'test'")
                                                                .build()))
                                        .build());

                ChatResponse testResponse =
                        o1Model.stream(testMsg, null, null).blockFirst(TEST_TIMEOUT);
                if (testResponse != null) {
                    workingModelName = modelName;
                    System.out.println("✓ Found working thinking model: " + modelName);
                    break;
                }
            } catch (Exception e) {
                System.out.println(
                        "Thinking model " + modelName + " not available: " + e.getMessage());
                o1Model = null;
            }
        }

        // If no thinking model is available, skip the test
        assumeTrue(
                o1Model != null && workingModelName != null,
                "No thinking model available on OpenRouter (tried: "
                        + String.join(", ", thinkingModelNames)
                        + ")");

        System.out.println("Testing thinking model: " + workingModelName);
        final String finalModelName = workingModelName; // Make final for lambda

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

        StepVerifier.create(o1Model.stream(messages, null, null))
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
                                        "✓ ThinkingBlock found in " + finalModelName + " response");
                                ThinkingBlock thinkingBlock =
                                        response.getContent().stream()
                                                .filter(block -> block instanceof ThinkingBlock)
                                                .map(block -> (ThinkingBlock) block)
                                                .findFirst()
                                                .orElse(null);
                                assertNotNull(thinkingBlock, "ThinkingBlock should not be null");
                                assertNotNull(
                                        thinkingBlock.getThinking(),
                                        "Thinking content should not be null");
                                assertTrue(
                                        !thinkingBlock.getThinking().isEmpty(),
                                        "Thinking content should not be empty");
                                System.out.println(
                                        "Thinking content (first 200 chars): "
                                                + thinkingBlock
                                                        .getThinking()
                                                        .substring(
                                                                0,
                                                                Math.min(
                                                                        200,
                                                                        thinkingBlock
                                                                                .getThinking()
                                                                                .length()))
                                                + "...");
                            } else {
                                System.out.println(
                                        "Note: "
                                                + finalModelName
                                                + " response did not contain ThinkingBlock (may be"
                                                + " integrated in text)");
                            }

                            // Response should still be valid even without ThinkingBlock
                            String text =
                                    response.getContent().stream()
                                            .filter(block -> block instanceof TextBlock)
                                            .map(block -> ((TextBlock) block).getText())
                                            .findFirst()
                                            .orElse("");
                            assertNotNull(text, "Text content should not be null");
                            System.out.println("Response text: " + text);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle o1 model streaming with reasoning")
    void testO1ModelStreamingWithReasoning() {
        String apiKey = System.getenv("OPENROUTER_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isEmpty(), "OPENROUTER_API_KEY must be set");

        // Try OpenRouter-supported thinking models (in order of preference)
        String[] thinkingModelNames = {
            "openai/o3-mini", // Cost-efficient, supports reasoning_effort
            "openai/o3", // Full o3 model
            "openai/o1-mini", // Smaller o1 variant
            "openai/o1", // Full o1 model
            "openai/o1-preview", // Preview version
            "deepseek/deepseek-r1", // DeepSeek R1 reasoning model
            "deepseek/deepseek-r1-distill-llama-70b" // DeepSeek R1 Distill
        };
        OpenAIChatModel o1StreamingModel = null;
        String workingModelName = null;

        // Try to find a working thinking model
        for (String modelName : thinkingModelNames) {
            try {
                o1StreamingModel =
                        OpenAIChatModel.builder().apiKey(apiKey).modelName(modelName).stream(true)
                                .baseUrl(openRouterBaseUrl)
                                .formatter(new OpenAIChatFormatter())
                                .build();

                // Try a simple test call to see if model is available
                List<Msg> testMsg =
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("Say 'test'")
                                                                .build()))
                                        .build());

                ChatResponse testResponse =
                        o1StreamingModel.stream(testMsg, null, null).blockFirst(TEST_TIMEOUT);
                if (testResponse != null) {
                    workingModelName = modelName;
                    System.out.println("✓ Found working thinking streaming model: " + modelName);
                    break;
                }
            } catch (Exception e) {
                System.out.println(
                        "Thinking streaming model "
                                + modelName
                                + " not available: "
                                + e.getMessage());
                o1StreamingModel = null;
            }
        }

        // If no thinking model is available, skip the test
        assumeTrue(
                o1StreamingModel != null && workingModelName != null,
                "No thinking streaming model available on OpenRouter (tried: "
                        + String.join(", ", thinkingModelNames)
                        + ")");

        System.out.println("Testing thinking streaming model: " + workingModelName);
        final String finalStreamingModelName = workingModelName; // Make final for lambda

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("What is 2+2? Think step by step.")
                                                        .build()))
                                .build());

        StepVerifier.create(o1StreamingModel.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                        })
                .thenConsumeWhile(
                        response -> {
                            assertNotNull(response);
                            // Check for ThinkingBlock in streaming chunks
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
}
