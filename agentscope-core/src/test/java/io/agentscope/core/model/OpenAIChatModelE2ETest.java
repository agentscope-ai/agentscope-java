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
 * distributed under the License is distributed on "AS IS" BASIS,
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
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final String OPENROUTER_BASE_URL = "https://openrouter.ai/api";
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    private OpenAIChatModel model;
    private OpenAIChatModel streamingModel;

    @BeforeEach
    void setUp() {
        String apiKey = System.getenv("OPENROUTER_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isEmpty(), "OPENROUTER_API_KEY must be set");

        // Build headers for OpenRouter
        Map<String, String> additionalHeaders = new HashMap<>();
        additionalHeaders.put("HTTP-Referer", "https://agentscope.io");
        additionalHeaders.put("X-Title", "AgentScope Java Tests");

        io.agentscope.core.model.GenerateOptions defaultOptions =
                io.agentscope.core.model.GenerateOptions.builder()
                        .additionalHeaders(additionalHeaders)
                        .build();

        // Non-streaming model
        model =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("openai/gpt-4o-mini").stream(
                                false)
                        .baseUrl(OPENROUTER_BASE_URL)
                        .formatter(new OpenAIChatFormatter())
                        .defaultOptions(defaultOptions)
                        .build();

        // Streaming model
        streamingModel =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("openai/gpt-4o-mini").stream(
                                true)
                        .baseUrl(OPENROUTER_BASE_URL)
                        .formatter(new OpenAIChatFormatter())
                        .defaultOptions(defaultOptions)
                        .build();

        System.out.println("=== OpenAIChatModel E2E Test Setup Complete ===");
        System.out.println("Model: openai/gpt-4o-mini");
        System.out.println("Base URL: " + OPENROUTER_BASE_URL);
    }

    @AfterEach
    void tearDown() {
        try {
            if (model != null) {
                model.close();
            }
            if (streamingModel != null) {
                streamingModel.close();
            }
        } catch (java.io.IOException e) {
            // Ignore close errors
        }
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
}
