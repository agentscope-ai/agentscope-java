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

    @Test
    @DisplayName("ToolCall should support getter and setter operations")
    void testToolCallGetterSetter() {
        // Test constructor
        ToolCall toolCall = new ToolCall();
        assertNotNull(toolCall, "ToolCall should be created");

        // Test setters and getters
        toolCall.setId("call_123");
        assertEquals("call_123", toolCall.getId(), "ID should match");

        toolCall.setName("search");
        assertEquals("search", toolCall.getName(), "Name should match");

        Map<String, Object> args = new HashMap<>();
        args.put("query", "test");
        args.put("limit", 10);
        toolCall.setArguments(args);

        assertNotNull(toolCall.getArguments(), "Arguments should not be null");
        assertEquals(2, toolCall.getArguments().size(), "Should have 2 arguments");
        assertEquals("test", toolCall.getArguments().get("query"), "Query argument should match");
        assertEquals(10, toolCall.getArguments().get("limit"), "Limit argument should match");
    }

    @Test
    @DisplayName("ToolCall builder should construct object properly")
    void testToolCallBuilder() {
        // Test builder pattern
        Map<String, Object> args = new HashMap<>();
        args.put("query", "search term");
        args.put("max_results", 5);

        ToolCall toolCall =
                ToolCall.builder().id("call_456").name("web_search").arguments(args).build();

        assertAll(
                "ToolCall builder should set all fields",
                () -> assertEquals("call_456", toolCall.getId(), "ID should be set by builder"),
                () ->
                        assertEquals(
                                "web_search", toolCall.getName(), "Name should be set by builder"),
                () -> assertNotNull(toolCall.getArguments(), "Arguments should be set by builder"),
                () ->
                        assertEquals(
                                2, toolCall.getArguments().size(), "Arguments size should match"),
                () ->
                        assertEquals(
                                "search term",
                                toolCall.getArguments().get("query"),
                                "Query should match"),
                () ->
                        assertEquals(
                                5,
                                toolCall.getArguments().get("max_results"),
                                "Max results should match"));
    }

    @Test
    @DisplayName("ToolCall builder should handle null arguments")
    void testToolCallBuilderWithNullArguments() {
        // Test builder with null arguments
        ToolCall toolCall = ToolCall.builder().id("call_789").name("simple_tool").build();

        assertAll(
                "ToolCall should handle null arguments gracefully",
                () -> assertEquals("call_789", toolCall.getId(), "ID should be set"),
                () -> assertEquals("simple_tool", toolCall.getName(), "Name should be set"),
                () -> assertEquals(null, toolCall.getArguments(), "Arguments should be null"));
    }

    @Test
    @DisplayName("ModelMessageContentItem should support getter and setter operations")
    void testModelMessageContentItemGetterSetter() {
        // Test constructor
        ModelMessageContentItem item = new ModelMessageContentItem();
        assertNotNull(item, "ModelMessageContentItem should be created");

        // Test setters and getters for text type
        item.setType("text");
        assertEquals("text", item.getType(), "Type should match");

        item.setText("Hello world");
        assertEquals("Hello world", item.getText(), "Text should match");

        // Test setters and getters for image type
        item.setType("image");
        item.setUrl("https://example.com/image.png");
        assertEquals("image", item.getType(), "Type should be updated to image");
        assertEquals("https://example.com/image.png", item.getUrl(), "URL should match for image");

        // Test audio type
        item.setType("audio");
        item.setUrl("https://example.com/audio.mp3");
        assertEquals("audio", item.getType(), "Type should be audio");
        assertEquals("https://example.com/audio.mp3", item.getUrl(), "URL should match for audio");

        // Test video type
        item.setType("video");
        item.setUrl("https://example.com/video.mp4");
        assertEquals("video", item.getType(), "Type should be video");
        assertEquals("https://example.com/video.mp4", item.getUrl(), "URL should match for video");
    }

    @Test
    @DisplayName("ModelMessageContentItem builder should construct text item properly")
    void testModelMessageContentItemBuilderForText() {
        // Test builder for text content
        ModelMessageContentItem textItem =
                ModelMessageContentItem.builder().type("text").text("Sample text content").build();

        assertAll(
                "Text content item should be properly built",
                () -> assertEquals("text", textItem.getType(), "Type should be text"),
                () ->
                        assertEquals(
                                "Sample text content",
                                textItem.getText(),
                                "Text should be set by builder"),
                () -> assertEquals(null, textItem.getUrl(), "URL should be null for text content"));
    }

    @Test
    @DisplayName("ModelMessageContentItem builder should construct image item properly")
    void testModelMessageContentItemBuilderForImage() {
        // Test builder for image content
        ModelMessageContentItem imageItem =
                ModelMessageContentItem.builder()
                        .type("image")
                        .url("https://example.com/test.jpg")
                        .build();

        assertAll(
                "Image content item should be properly built",
                () -> assertEquals("image", imageItem.getType(), "Type should be image"),
                () ->
                        assertEquals(
                                "https://example.com/test.jpg",
                                imageItem.getUrl(),
                                "URL should be set by builder"),
                () ->
                        assertEquals(
                                null,
                                imageItem.getText(),
                                "Text should be null for image content"));
    }

    @Test
    @DisplayName("ModelMessageContentItem builder should construct audio item properly")
    void testModelMessageContentItemBuilderForAudio() {
        // Test builder for audio content
        ModelMessageContentItem audioItem =
                ModelMessageContentItem.builder()
                        .type("audio")
                        .url("https://example.com/sound.wav")
                        .build();

        assertAll(
                "Audio content item should be properly built",
                () -> assertEquals("audio", audioItem.getType(), "Type should be audio"),
                () ->
                        assertEquals(
                                "https://example.com/sound.wav",
                                audioItem.getUrl(),
                                "URL should be set by builder"));
    }

    @Test
    @DisplayName("ModelMessageContentItem builder should construct video item properly")
    void testModelMessageContentItemBuilderForVideo() {
        // Test builder for video content
        ModelMessageContentItem videoItem =
                ModelMessageContentItem.builder()
                        .type("video")
                        .url("https://example.com/clip.mp4")
                        .build();

        assertAll(
                "Video content item should be properly built",
                () -> assertEquals("video", videoItem.getType(), "Type should be video"),
                () ->
                        assertEquals(
                                "https://example.com/clip.mp4",
                                videoItem.getUrl(),
                                "URL should be set by builder"));
    }
}
