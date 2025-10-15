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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.test.ModelTestUtils;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for Model response validation.
 *
 * <p>These tests verify that model responses conform to expected formats including chat responses,
 * usage tracking, tool calls, and streaming chunks.
 *
 * <p>Tagged as "unit" - tests response structure and validation.
 */
@Tag("unit")
@DisplayName("Model Response Tests")
class ModelResponseTest {

    private MockModel model;
    private FormattedMessageList messages;
    private List<ToolSchema> tools;
    private GenerateOptions options;

    @BeforeEach
    void setUp() {
        model = new MockModel("Test response");
        messages = new FormattedMessageList(List.of());
        tools = List.of();
        options = new GenerateOptions();
    }

    @Test
    @DisplayName("Should return valid ChatResponse structure")
    void testChatResponse() {
        List<ChatResponse> responses =
                model.streamFlux(messages, tools, options)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(responses, "Responses should not be null");
        assertTrue(responses.size() > 0, "Should have at least one response");

        ChatResponse response = responses.get(0);

        assertAll(
                "ChatResponse should have all required fields",
                () -> assertNotNull(response.getContent(), "Content should not be null"),
                () -> assertTrue(response.getContent().size() > 0, "Content should not be empty"),
                () -> assertNotNull(response.getUsage(), "Usage should not be null"),
                () -> assertTrue(response.getUsage().getTotalTokens() >= 0, "Total tokens >= 0"));

        // Verify content is TextBlock
        ContentBlock firstBlock = response.getContent().get(0);
        assertTrue(firstBlock instanceof TextBlock, "First block should be TextBlock");

        TextBlock textBlock = (TextBlock) firstBlock;
        assertNotNull(textBlock.getText(), "Text should not be null");
        assertTrue(textBlock.getText().length() > 0, "Text should not be empty");
    }

    @Test
    @DisplayName("Should track ChatUsage correctly")
    void testChatUsage() {
        List<ChatResponse> responses =
                model.streamFlux(messages, tools, options)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(responses);
        assertTrue(responses.size() > 0);

        ChatUsage usage = responses.get(0).getUsage();
        assertNotNull(usage, "Usage should not be null");

        assertAll(
                "ChatUsage should have valid token counts",
                () -> assertTrue(usage.getInputTokens() >= 0, "Input tokens should be >= 0"),
                () -> assertTrue(usage.getOutputTokens() >= 0, "Output tokens should be >= 0"),
                () -> assertTrue(usage.getTotalTokens() >= 0, "Total tokens should be >= 0"),
                () ->
                        assertEquals(
                                usage.getInputTokens() + usage.getOutputTokens(),
                                usage.getTotalTokens(),
                                "Total should equal input + output"));
    }

    @Test
    @DisplayName("Should handle tool call responses")
    void testToolCallResponse() {
        // Create tool schema for testing
        Map<String, Object> properties = new HashMap<>();
        properties.put("query", Map.of("type", "string", "description", "Search query"));

        ToolSchema toolSchema =
                ModelTestUtils.createToolSchemaWithParams(
                        "search", "Search for information", properties);
        List<ToolSchema> toolsWithSchema = List.of(toolSchema);

        // Use mock model - it will return text response, which is fine for structure testing
        MockModel toolModel = new MockModel("Tool search result: found information");

        List<ChatResponse> responses =
                toolModel
                        .streamFlux(messages, toolsWithSchema, options)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(responses, "Responses should not be null");
        assertTrue(responses.size() > 0, "Should have responses");

        ChatResponse response = responses.get(0);
        assertNotNull(response.getContent(), "Content should not be null");
        assertTrue(response.getContent().size() > 0, "Content should not be empty");

        // Verify response structure (even if it's text, not tool call)
        ContentBlock firstBlock = response.getContent().get(0);
        assertNotNull(firstBlock, "First block should not be null");

        // Test tool call response creation utility
        Map<String, Object> args = new HashMap<>();
        args.put("query", "test query");
        ChatResponse toolCallResponse =
                ModelTestUtils.createToolCallResponse("search", "call_123", args);

        assertNotNull(toolCallResponse, "Tool call response should be created");
        assertTrue(
                ModelTestUtils.hasToolCall(toolCallResponse), "Response should contain tool call");
    }

    @Test
    @DisplayName("Should produce valid streaming chunks")
    void testStreamingChunks() {
        MockModel streamModel = new MockModel("Streaming test response");

        List<ChatResponse> chunks =
                streamModel
                        .streamFlux(messages, tools, options)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(chunks, "Chunks should not be null");
        assertTrue(chunks.size() > 0, "Should have at least one chunk");

        // Verify each chunk is valid
        for (ChatResponse chunk : chunks) {
            assertTrue(
                    ModelTestUtils.isValidChatResponse(chunk),
                    "Each chunk should be valid ChatResponse");

            assertNotNull(chunk.getContent(), "Each chunk should have content");
            assertTrue(chunk.getContent().size() > 0, "Each chunk content should not be empty");

            // Verify usage exists (even if zero)
            assertNotNull(chunk.getUsage(), "Each chunk should have usage info");
        }

        // Verify chunks can be concatenated
        StringBuilder fullText = new StringBuilder();
        for (ChatResponse chunk : chunks) {
            String text = ModelTestUtils.extractText(chunk.getContent());
            if (text != null) {
                fullText.append(text);
            }
        }

        assertTrue(fullText.length() > 0, "Combined chunks should have text");
    }
}
