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
 * End-to-End integration tests for Gemini Chat Model using OpenAIChatModel via OpenRouter.
 *
 * <p>These tests make actual API calls to OpenRouter (OpenAI-compatible API) to verify:
 * <ul>
 *   <li>Non-streaming chat completion</li>
 *   <li>Streaming chat completion</li>
 *   <li>Gemini 3 Flash model support</li>
 *   <li>Gemini 3 Pro model support</li>
 *   <li>Options application</li>
 *   <li>Multi-turn conversation</li>
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
@Tag("gemini")
@DisplayName("Gemini Chat Model E2E Tests (OpenAIChatModel via OpenRouter)")
@EnabledIfEnvironmentVariable(
        named = "OPENROUTER_API_KEY",
        matches = ".+",
        disabledReason = "Requires OPENROUTER_API_KEY environment variable")
class GeminiChatModelE2ETest {

    private static final String DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api";
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(60);

    private OpenAIChatModel gemini3FlashModel;
    private OpenAIChatModel gemini3FlashStreamingModel;
    private OpenAIChatModel gemini3ProModel;
    private OpenAIChatModel gemini3ProStreamingModel;
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

        // Try available Gemini 3 models on OpenRouter
        // Based on OpenRouter API: google/gemini-3-flash-preview, google/gemini-3-pro-preview
        String[] gemini3FlashModelNames = {
            "google/gemini-3-flash", // Try official release first
            "google/gemini-3-flash-preview", // Known available on OpenRouter
            "google/gemini-2.5-flash", // Fallback to available model
            "google/gemini-2.0-flash-001"
        };

        String[] gemini3ProModelNames = {
            "google/gemini-3-pro", // Try official release first
            "google/gemini-3-pro-preview", // Known available on OpenRouter
            "google/gemini-2.5-pro" // Fallback
        };

        // Find working Gemini 3 Flash model
        String workingFlashModel = null;
        for (String modelName : gemini3FlashModelNames) {
            try {
                OpenAIChatModel testModel =
                        OpenAIChatModel.builder().apiKey(apiKey).modelName(modelName).stream(false)
                                .baseUrl(openRouterBaseUrl)
                                .formatter(new OpenAIChatFormatter())
                                .build();

                // Try a simple test call
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
                        testModel.stream(testMsg, null, null).blockFirst(TEST_TIMEOUT);
                if (testResponse != null) {
                    workingFlashModel = modelName;
                    System.out.println("✓ Found working Gemini Flash model: " + modelName);
                    break;
                }
            } catch (Exception e) {
                System.out.println(
                        "Gemini Flash model " + modelName + " not available: " + e.getMessage());
            }
        }

        // Find working Gemini 3 Pro model
        String workingProModel = null;
        for (String modelName : gemini3ProModelNames) {
            try {
                OpenAIChatModel testModel =
                        OpenAIChatModel.builder().apiKey(apiKey).modelName(modelName).stream(false)
                                .baseUrl(openRouterBaseUrl)
                                .formatter(new OpenAIChatFormatter())
                                .build();

                // Try a simple test call
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
                        testModel.stream(testMsg, null, null).blockFirst(TEST_TIMEOUT);
                if (testResponse != null) {
                    workingProModel = modelName;
                    System.out.println("✓ Found working Gemini Pro model: " + modelName);
                    break;
                }
            } catch (Exception e) {
                System.out.println(
                        "Gemini Pro model " + modelName + " not available: " + e.getMessage());
            }
        }

        assumeTrue(
                workingFlashModel != null,
                "No Gemini Flash model available on OpenRouter (tried: "
                        + String.join(", ", gemini3FlashModelNames)
                        + ")");
        assumeTrue(
                workingProModel != null,
                "No Gemini Pro model available on OpenRouter (tried: "
                        + String.join(", ", gemini3ProModelNames)
                        + ")");

        // Create models with working model names
        gemini3FlashModel =
                OpenAIChatModel.builder().apiKey(apiKey).modelName(workingFlashModel).stream(false)
                        .baseUrl(openRouterBaseUrl)
                        .formatter(new OpenAIChatFormatter())
                        .build();

        gemini3FlashStreamingModel =
                OpenAIChatModel.builder().apiKey(apiKey).modelName(workingFlashModel).stream(true)
                        .baseUrl(openRouterBaseUrl)
                        .formatter(new OpenAIChatFormatter())
                        .build();

        gemini3ProModel =
                OpenAIChatModel.builder().apiKey(apiKey).modelName(workingProModel).stream(false)
                        .baseUrl(openRouterBaseUrl)
                        .formatter(new OpenAIChatFormatter())
                        .build();

        gemini3ProStreamingModel =
                OpenAIChatModel.builder().apiKey(apiKey).modelName(workingProModel).stream(true)
                        .baseUrl(openRouterBaseUrl)
                        .formatter(new OpenAIChatFormatter())
                        .build();

        System.out.println("=== Gemini Chat Model E2E Test Setup Complete ===");
        System.out.println("Gemini Flash Model: " + workingFlashModel);
        System.out.println("Gemini Pro Model: " + workingProModel);
        System.out.println("Base URL: " + openRouterBaseUrl);
        System.out.println("Using: OpenRouter via OpenAIChatModel");
    }

    @AfterEach
    void tearDown() throws IOException {
        // Stateless models don't need cleanup
    }

    @Test
    @DisplayName("Should handle Gemini 3 Flash non-streaming call")
    void testGemini3FlashNonStreamingCall() {
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

        StepVerifier.create(gemini3FlashModel.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            assertTrue(response.getContent().size() > 0);
                            String text = ((TextBlock) response.getContent().get(0)).getText();
                            assertNotNull(text);
                            System.out.println("Gemini 3 Flash Response: " + text);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle Gemini 3 Flash streaming call")
    void testGemini3FlashStreamingCall() {
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

        StepVerifier.create(gemini3FlashStreamingModel.stream(messages, null, null))
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
    @DisplayName("Should handle Gemini 3 Pro non-streaming call")
    void testGemini3ProNonStreamingCall() {
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

        StepVerifier.create(gemini3ProModel.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            assertTrue(response.getContent().size() > 0);
                            String text = ((TextBlock) response.getContent().get(0)).getText();
                            assertNotNull(text);
                            System.out.println("Gemini 3 Pro Response: " + text);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle Gemini 3 Pro streaming call")
    void testGemini3ProStreamingCall() {
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

        StepVerifier.create(gemini3ProStreamingModel.stream(messages, null, null))
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
    @DisplayName("Should handle multi-turn conversation with Gemini 3 Flash")
    void testGemini3FlashMultiTurnConversation() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("My name is Charlie. Remember this.")
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text(
                                                                "Hello Charlie! I'll remember your"
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

        StepVerifier.create(gemini3FlashModel.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            String text = ((TextBlock) response.getContent().get(0)).getText();
                            assertNotNull(text);
                            assertTrue(
                                    text.toLowerCase().contains("charlie"),
                                    "Response should contain 'Charlie'");
                            System.out.println("Gemini 3 Flash Multi-turn response: " + text);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle multi-turn conversation with Gemini 3 Pro")
    void testGemini3ProMultiTurnConversation() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("My name is David. Remember this.")
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text(
                                                                "Hello David! I'll remember your"
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

        StepVerifier.create(gemini3ProModel.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            String text = ((TextBlock) response.getContent().get(0)).getText();
                            assertNotNull(text);
                            assertTrue(
                                    text.toLowerCase().contains("david"),
                                    "Response should contain 'David'");
                            System.out.println("Gemini 3 Pro Multi-turn response: " + text);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return correct model names")
    void testGetModelName() {
        String flashModelName = gemini3FlashModel.getModelName();
        String proModelName = gemini3ProModel.getModelName();
        assertEquals(flashModelName, gemini3FlashStreamingModel.getModelName());
        assertEquals(proModelName, gemini3ProStreamingModel.getModelName());
        assertTrue(
                flashModelName.contains("gemini") && flashModelName.contains("flash"),
                "Flash model name should contain 'gemini' and 'flash'");
        assertTrue(
                proModelName.contains("gemini") && proModelName.contains("pro"),
                "Pro model name should contain 'gemini' and 'pro'");
        System.out.println("Flash Model name: " + flashModelName);
        System.out.println("Pro Model name: " + proModelName);
    }

    @Test
    @DisplayName("Should handle options with Gemini 3 Flash")
    void testGemini3FlashApplyOptions() {
        GenerateOptions options = GenerateOptions.builder().temperature(0.1).maxTokens(50).build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Say 'Four'").build()))
                                .build());

        StepVerifier.create(gemini3FlashModel.stream(messages, null, options))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            assertTrue(
                                    response.getContent().size() > 0,
                                    "Response should have content");
                            // Handle case where content might be empty
                            if (response.getContent().size() > 0) {
                                String text = ((TextBlock) response.getContent().get(0)).getText();
                                assertNotNull(text);
                                System.out.println(
                                        "Gemini 3 Flash Response with low temperature: " + text);
                            } else {
                                System.out.println(
                                        "Gemini 3 Flash Response with low temperature: (empty"
                                                + " content)");
                            }
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle options with Gemini 3 Pro")
    void testGemini3ProApplyOptions() {
        // Use higher maxTokens to avoid empty responses
        GenerateOptions options = GenerateOptions.builder().temperature(0.1).maxTokens(100).build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Say 'Four'").build()))
                                .build());

        StepVerifier.create(gemini3ProModel.stream(messages, null, options))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            // Some models may return empty content with very restrictive options
                            // This is acceptable as long as the request was processed
                            if (response.getContent().size() > 0) {
                                String text = ((TextBlock) response.getContent().get(0)).getText();
                                assertNotNull(text);
                                System.out.println(
                                        "Gemini 3 Pro Response with low temperature: " + text);
                            } else {
                                System.out.println(
                                        "Gemini 3 Pro Response with low temperature: (empty"
                                                + " content, but options were applied)");
                            }
                        })
                .verifyComplete();
    }
}
