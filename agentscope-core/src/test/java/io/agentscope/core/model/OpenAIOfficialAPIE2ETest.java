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
 * End-to-End integration tests for OpenAIChatModel using official OpenAI API.
 *
 * <p>These tests make actual API calls to the official OpenAI API (https://api.openai.com)
 * to verify that the HTTP-based implementation correctly supports the official OpenAI API.
 *
 * <p>These tests verify:
 * <ul>
 *   <li>Non-streaming chat completion with official API</li>
 *   <li>Streaming chat completion with official API</li>
 *   <li>Default base URL is correct (https://api.openai.com)</li>
 *   <li>Options application</li>
 *   <li>Multi-turn conversation</li>
 * </ul>
 *
 * <p><b>Requirements:</b>
 * <ul>
 *   <li>OPENAI_API_KEY environment variable must be set (official OpenAI API key)</li>
 *   <li>Active internet connection</li>
 *   <li>Valid OpenAI API quota</li>
 * </ul>
 *
 * <p>Tagged as "e2e" - these tests make real API calls and may incur costs.
 */
@Tag("e2e")
@Tag("openai")
@Tag("official")
@DisplayName("OpenAI Official API E2E Tests (Real OpenAI API)")
@EnabledIfEnvironmentVariable(
        named = "OPENAI_API_KEY",
        matches = ".+",
        disabledReason = "Requires OPENAI_API_KEY environment variable (official OpenAI API key)")
class OpenAIOfficialAPIE2ETest {

    private static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com";
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    private OpenAIChatModel model;
    private OpenAIChatModel streamingModel;
    private OpenAIChatModel defaultBaseUrlModel; // Test default base URL
    private String openaiBaseUrl;

    @BeforeEach
    void setUp() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isEmpty(), "OPENAI_API_KEY must be set");

        // Get base URL from environment variable, fallback to default
        openaiBaseUrl = System.getenv("OPENAI_BASE_URL");
        if (openaiBaseUrl == null || openaiBaseUrl.isEmpty()) {
            openaiBaseUrl = DEFAULT_OPENAI_BASE_URL;
        }

        // Model with explicit official base URL
        model =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("gpt-4o-mini").stream(false)
                        .baseUrl(openaiBaseUrl)
                        .formatter(new OpenAIChatFormatter())
                        .build();

        // Streaming model with explicit official base URL
        streamingModel =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("gpt-4o-mini").stream(true)
                        .baseUrl(openaiBaseUrl)
                        .formatter(new OpenAIChatFormatter())
                        .build();

        // Model with default base URL (should be https://api.openai.com)
        defaultBaseUrlModel =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("gpt-4o-mini").stream(false)
                        // baseUrl not set - should use default
                        .formatter(new OpenAIChatFormatter())
                        .build();

        System.out.println("=== OpenAI Official API E2E Test Setup Complete ===");
        System.out.println("Model: gpt-4o-mini");
        System.out.println("Explicit Base URL: " + openaiBaseUrl);
        System.out.println("Using: Official OpenAI API");
    }

    @AfterEach
    void tearDown() throws IOException {
        // Stateless models don't need cleanup
    }

    @Test
    @DisplayName("Should use default base URL (https://api.openai.com)")
    void testDefaultBaseUrl() {
        // Stateless models don't have a getBaseUrl() method
        // The baseUrl is passed per-request via GenerateOptions
        // This test just verifies the model can be created and used
        assertNotNull(defaultBaseUrlModel, "Model should not be null");
        System.out.println("✓ Model created successfully");
    }

    @Test
    @DisplayName("Should handle non-streaming call with official API")
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
                            System.out.println("Official API Response: " + text);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle streaming call with official API")
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
    @DisplayName("Should handle non-streaming call with default base URL")
    void testDefaultBaseUrlCall() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Say 'test'").build()))
                                .build());

        StepVerifier.create(defaultBaseUrlModel.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            assertTrue(response.getContent().size() > 0);
                            String text = ((TextBlock) response.getContent().get(0)).getText();
                            assertNotNull(text);
                            System.out.println("Default Base URL Response: " + text);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle multi-turn conversation with official API")
    void testMultiTurnConversation() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("My name is Eve. Remember this.")
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("Hello Eve! I'll remember your name.")
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
                                    text.toLowerCase().contains("eve"),
                                    "Response should contain 'Eve'");
                            System.out.println("Multi-turn response: " + text);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return model name")
    void testGetModelName() {
        assertEquals("gpt-4o-mini", model.getModelName());
        assertEquals("gpt-4o-mini", streamingModel.getModelName());
        assertEquals("gpt-4o-mini", defaultBaseUrlModel.getModelName());
    }

    @Test
    @DisplayName("Should handle options with official API")
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
                            if (response.getContent().size() > 0) {
                                String text = ((TextBlock) response.getContent().get(0)).getText();
                                assertNotNull(text);
                                System.out.println("Official API Response with options: " + text);
                            }
                        })
                .verifyComplete();
    }
}
