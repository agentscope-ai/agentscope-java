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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.model.test.ModelTestUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Model implementations.
 *
 * <p>These tests verify model integration scenarios including model switching, parallel execution,
 * formatter integration, and usage tracking.
 *
 * <p>Uses mock models to avoid external API dependencies.
 *
 * <p>Tagged as "integration" - tests component interaction without external services.
 */
@Tag("integration")
@DisplayName("Model Integration Tests")
class ModelIntegrationTest {

    private MockModel model1;
    private MockModel model2;
    private FormattedMessageList messages;

    @BeforeEach
    void setUp() {
        model1 = new MockModel("Response from model 1");
        model2 = new MockModel("Response from model 2");
        messages = new FormattedMessageList(List.of());
    }

    @Test
    @DisplayName("Should support switching between different models")
    void testModelSwitching() {
        List<ToolSchema> tools = List.of();
        GenerateOptions options = new GenerateOptions();

        // Call model 1
        List<ChatResponse> responses1 =
                model1.streamFlux(messages, tools, options)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(responses1, "Model 1 should return responses");
        assertTrue(responses1.size() > 0, "Model 1 should have responses");

        String text1 = ModelTestUtils.extractText(responses1.get(0).getContent());
        assertTrue(text1.contains("Response from model 1"), "Should get response from model 1");

        // Switch to model 2
        List<ChatResponse> responses2 =
                model2.streamFlux(messages, tools, options)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(responses2, "Model 2 should return responses");
        assertTrue(responses2.size() > 0, "Model 2 should have responses");

        String text2 = ModelTestUtils.extractText(responses2.get(0).getContent());
        assertTrue(text2.contains("Response from model 2"), "Should get response from model 2");

        // Verify both models work independently
        assertEquals(1, model1.getCallCount(), "Model 1 should be called once");
        assertEquals(1, model2.getCallCount(), "Model 2 should be called once");
    }

    @Test
    @DisplayName("Should support multiple models running in parallel")
    void testMultipleModelsParallel() throws InterruptedException {
        int modelCount = 5;
        List<MockModel> models = new ArrayList<>();
        for (int i = 0; i < modelCount; i++) {
            models.add(new MockModel("Response " + i));
        }

        CountDownLatch latch = new CountDownLatch(modelCount);
        AtomicInteger completedCount = new AtomicInteger(0);

        List<ToolSchema> tools = List.of();
        GenerateOptions options = new GenerateOptions();

        // Execute all models in parallel
        for (MockModel model : models) {
            new Thread(
                            () -> {
                                try {
                                    List<ChatResponse> responses =
                                            model.streamFlux(messages, tools, options)
                                                    .collectList()
                                                    .block(Duration.ofSeconds(5));

                                    if (responses != null && responses.size() > 0) {
                                        completedCount.incrementAndGet();
                                    }
                                } finally {
                                    latch.countDown();
                                }
                            })
                    .start();
        }

        // Wait for all to complete
        latch.await();

        assertEquals(modelCount, completedCount.get(), "All models should complete");

        // Verify each model was called
        for (MockModel model : models) {
            assertEquals(1, model.getCallCount(), "Each model should be called once");
        }
    }

    @Test
    @DisplayName("Should integrate with message formatting")
    void testFormatterIntegration() {
        // Create a formatted message list with actual content
        List<FormattedMessage> formattedMessages = new ArrayList<>();
        FormattedMessageList nonEmptyMessages = new FormattedMessageList(formattedMessages, true);

        MockModel model = new MockModel("Formatted response");

        List<ToolSchema> tools = List.of();
        GenerateOptions options = new GenerateOptions();

        // Model should handle formatted messages
        assertDoesNotThrow(
                () -> {
                    List<ChatResponse> responses =
                            model.streamFlux(nonEmptyMessages, tools, options)
                                    .collectList()
                                    .block(Duration.ofSeconds(5));

                    assertNotNull(responses);
                    assertTrue(responses.size() > 0);
                });

        assertEquals(1, model.getCallCount(), "Model should be called once");
    }

    @Test
    @DisplayName("Should track usage across multiple calls")
    void testUsageTracking() {
        MockModel model = new MockModel("Usage tracking test");

        List<ToolSchema> tools = List.of();
        GenerateOptions options = new GenerateOptions();

        int callCount = 3;
        List<ChatUsage> usages = new ArrayList<>();

        // Make multiple calls and collect usage
        for (int i = 0; i < callCount; i++) {
            List<ChatResponse> responses =
                    model.streamFlux(messages, tools, options)
                            .collectList()
                            .block(Duration.ofSeconds(5));

            assertNotNull(responses);
            if (responses.size() > 0 && responses.get(0).getUsage() != null) {
                usages.add(responses.get(0).getUsage());
            }
        }

        // Verify all calls completed
        assertEquals(callCount, model.getCallCount(), "Should track all calls");

        // Verify usage information exists
        assertTrue(usages.size() > 0, "Should collect usage information");

        // Verify usage information is valid
        for (ChatUsage usage : usages) {
            assertAll(
                    "Usage should have valid fields",
                    () -> assertTrue(usage.getInputTokens() >= 0, "Input tokens should be >= 0"),
                    () -> assertTrue(usage.getOutputTokens() >= 0, "Output tokens should be >= 0"),
                    () -> assertTrue(usage.getTotalTokens() >= 0, "Total tokens should be >= 0"));
        }

        // Calculate total usage
        int totalInputTokens = usages.stream().mapToInt(ChatUsage::getInputTokens).sum();
        int totalOutputTokens = usages.stream().mapToInt(ChatUsage::getOutputTokens).sum();
        int totalTokens = usages.stream().mapToInt(ChatUsage::getTotalTokens).sum();

        assertTrue(totalInputTokens >= 0, "Total input tokens should be tracked");
        assertTrue(totalOutputTokens >= 0, "Total output tokens should be tracked");
        assertTrue(totalTokens >= 0, "Total tokens should be tracked");
    }
}
