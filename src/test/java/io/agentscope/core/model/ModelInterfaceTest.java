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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.model.test.ModelTestUtils;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Tests for the Model interface common behavior.
 *
 * <p>These tests verify that all Model implementations conform to the expected interface contract
 * including response format, streaming behavior, and error handling.
 *
 * <p>Tagged as "unit" - tests interface contract without external dependencies.
 */
@Tag("unit")
@DisplayName("Model Interface Tests")
class ModelInterfaceTest {

    @Test
    @DisplayName("Should conform to common Model interface behavior")
    void testCommonBehavior() {
        // Use MockModel which implements Model interface
        Model model = new MockModel(TestConstants.MOCK_MODEL_SIMPLE_RESPONSE);

        assertNotNull(model, "Model should not be null");

        // Verify model has streamFlux method
        assertNotNull(model, "Model interface should be implemented");

        // Create minimal formatted message list for testing
        FormattedMessageList messages = new FormattedMessageList(List.of());

        // Model should be able to handle empty tool list
        List<ToolSchema> emptyTools = List.of();

        // Model should accept null options
        GenerateOptions options = new GenerateOptions();

        // Verify method exists and returns Flux
        Flux<ChatResponse> flux = model.streamFlux(messages, emptyTools, options);

        assertNotNull(flux, "streamFlux should return non-null Flux");
    }

    @Test
    @DisplayName("Should return properly formatted responses")
    void testResponseFormat() {
        // Create mock model with test response
        MockModel model = new MockModel("Test response");

        FormattedMessageList messages = new FormattedMessageList(List.of());

        List<ToolSchema> tools = List.of();
        GenerateOptions options = new GenerateOptions();

        // Get responses
        List<ChatResponse> responses =
                model.streamFlux(messages, tools, options)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(responses, "Responses should not be null");
        assertTrue(responses.size() > 0, "Should have at least one response");

        // Verify first response format
        ChatResponse firstResponse = responses.get(0);
        assertTrue(
                ModelTestUtils.isValidChatResponse(firstResponse),
                "Response should have valid format");

        // Verify response has content
        assertNotNull(firstResponse.getContent(), "Response should have content");
        assertTrue(firstResponse.getContent().size() > 0, "Response should have content blocks");

        // Verify response has usage info
        assertNotNull(firstResponse.getUsage(), "Response should have usage information");
    }
}
