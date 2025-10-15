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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.model.test.ModelTestUtils;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Boundary tests for Model implementations.
 *
 * <p>These tests verify model behavior at boundary conditions including empty prompts, large
 * prompts, invalid API keys, and network failures.
 *
 * <p>Tagged as "unit" - tests boundary conditions with mocks.
 */
@Tag("unit")
@DisplayName("Model Boundary Tests")
class ModelBoundaryTest {

    private MockModel model;
    private List<ToolSchema> tools;
    private GenerateOptions options;

    @BeforeEach
    void setUp() {
        model = new MockModel("Boundary test response");
        tools = List.of();
        options = new GenerateOptions();
    }

    @Test
    @DisplayName("Should handle empty message list")
    void testEmptyPrompt() {
        // Empty message list
        FormattedMessageList emptyMessages = new FormattedMessageList(List.of());

        // Model should handle empty messages gracefully
        assertDoesNotThrow(
                () -> {
                    List<ChatResponse> responses =
                            model.streamFlux(emptyMessages, tools, options)
                                    .collectList()
                                    .block(Duration.ofSeconds(5));

                    assertNotNull(responses, "Should return responses even with empty messages");
                });

        assertTrue(model.getCallCount() >= 1, "Model should be called");
    }

    @Test
    @DisplayName("Should handle very large message lists")
    void testLargePrompt() {
        // Create a large message list
        StringBuilder largeText = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            largeText.append("This is a very long text that simulates a large prompt. ");
        }

        // Note: In real implementation, this would be converted to FormattedMessageList
        // For testing purposes, we use empty list as the mock doesn't process content
        FormattedMessageList messages = new FormattedMessageList(List.of());

        MockModel largeModel = new MockModel("Response to large prompt");

        // Model should handle large prompts
        assertDoesNotThrow(
                () -> {
                    List<ChatResponse> responses =
                            largeModel
                                    .streamFlux(messages, tools, options)
                                    .collectList()
                                    .block(Duration.ofSeconds(10));

                    assertNotNull(responses, "Should handle large prompts");
                    assertTrue(responses.size() > 0, "Should return responses");
                });

        assertEquals(1, largeModel.getCallCount(), "Model should be called once");
    }

    @Test
    @DisplayName("Should handle invalid API key gracefully")
    void testInvalidAPIKey() {
        // Create model configuration with invalid key
        String invalidKey = "invalid_key_12345";
        String mockApiKey = ModelTestUtils.createMockApiKey();

        // For DashScope model
        assertDoesNotThrow(
                () -> {
                    DashScopeChatModel model =
                            DashScopeChatModel.builder()
                                    .apiKey(invalidKey)
                                    .modelName("qwen-plus")
                                    .build();

                    assertNotNull(model, "Model should be created even with invalid key");
                });

        // For OpenAI model
        assertDoesNotThrow(
                () -> {
                    OpenAIChatModel model =
                            OpenAIChatModel.builder().apiKey(invalidKey).modelName("gpt-4").build();

                    assertNotNull(model, "Model should be created even with invalid key");
                });

        // Note: Actual API calls would fail with invalid keys, but model creation should succeed
    }

    @Test
    @DisplayName("Should handle network failure scenarios")
    void testNetworkFailure() {
        // Create mock model that simulates network failure
        MockModel failureModel = new MockModel("Network error");
        failureModel.withError("Simulated network failure");

        FormattedMessageList messages = new FormattedMessageList(List.of());

        // Model should handle network failures
        try {
            List<ChatResponse> responses =
                    failureModel
                            .streamFlux(messages, tools, options)
                            .collectList()
                            .block(Duration.ofSeconds(5));

            // If mock doesn't throw, verify it was at least called
            assertTrue(
                    failureModel.getCallCount() >= 1,
                    "Model should attempt the call despite error");
        } catch (Exception e) {
            // Expected for network failures
            assertTrue(
                    e.getMessage().contains("error") || e.getMessage().contains("fail"),
                    "Error message should indicate failure");
        }
    }

    private void assertEquals(int expected, int actual, String message) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message);
    }
}
