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
package io.agentscope.core.formatter.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.openai.dto.OpenAIContentPart;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIReasoningDetail;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OpenAIMessageConverter.
 *
 * <p>These tests verify the message conversion logic including:
 * <ul>
 *   <li>Text message conversion</li>
 *   <li>Multimodal content (image, audio) conversion</li>
 *   <li>Null source handling for ImageBlock and AudioBlock</li>
 *   <li>Role mapping</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("OpenAIMessageConverter Unit Tests")
class OpenAIMessageConverterTest {

    private OpenAIMessageConverter converter;

    @BeforeEach
    void setUp() {
        // Create converter with simple text extractor and tool result converter
        Function<Msg, String> textExtractor =
                msg -> {
                    StringBuilder sb = new StringBuilder();
                    for (ContentBlock block : msg.getContent()) {
                        if (block instanceof TextBlock tb) {
                            sb.append(tb.getText());
                        }
                    }
                    return sb.toString();
                };

        Function<List<ContentBlock>, String> toolResultConverter =
                blocks -> {
                    StringBuilder sb = new StringBuilder();
                    for (ContentBlock block : blocks) {
                        if (block instanceof TextBlock tb) {
                            sb.append(tb.getText());
                        }
                    }
                    return sb.toString();
                };

        converter = new OpenAIMessageConverter(textExtractor, toolResultConverter);
    }

    @Test
    @DisplayName("Should convert simple text message")
    void testConvertSimpleTextMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        OpenAIMessage result = converter.convertToMessage(msg, false);

        assertNotNull(result);
        assertEquals("user", result.getRole());
        assertEquals("Hello", result.getContentAsString());
    }

    // Note: ImageBlock and AudioBlock constructors require non-null source,
    // so we cannot test null source handling directly. The null checks in
    // OpenAIMessageConverter are defensive programming for edge cases that
    // might occur through deserialization or other means.

    @Test
    @DisplayName("Should convert ImageBlock with valid URLSource")
    void testImageBlockWithValidURLSource() {
        URLSource source = URLSource.builder().url("https://example.com/image.png").build();
        ImageBlock imageBlock = ImageBlock.builder().source(source).build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("See this image").build(),
                                        imageBlock))
                        .build();

        OpenAIMessage result = converter.convertToMessage(msg, true);

        assertNotNull(result);
        assertEquals("user", result.getRole());
        Object content = result.getContent();
        assertNotNull(content);
        assertTrue(content instanceof List);
        @SuppressWarnings("unchecked")
        List<OpenAIContentPart> parts = (List<OpenAIContentPart>) content;
        assertEquals(2, parts.size());
        assertEquals("See this image", parts.get(0).getText());
        assertNotNull(parts.get(1).getImageUrl());
        assertEquals("https://example.com/image.png", parts.get(1).getImageUrl().getUrl());
    }

    @Test
    @DisplayName("Should convert ImageBlock with valid Base64Source")
    void testImageBlockWithValidBase64Source() {
        String base64Data =
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
        Base64Source source =
                Base64Source.builder().data(base64Data).mediaType("image/png").build();
        ImageBlock imageBlock = ImageBlock.builder().source(source).build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("See this image").build(),
                                        imageBlock))
                        .build();

        OpenAIMessage result = converter.convertToMessage(msg, true);

        assertNotNull(result);
        assertEquals("user", result.getRole());
        Object content = result.getContent();
        assertNotNull(content);
        assertTrue(content instanceof List);
        @SuppressWarnings("unchecked")
        List<OpenAIContentPart> parts = (List<OpenAIContentPart>) content;
        assertEquals(2, parts.size());
        assertEquals("See this image", parts.get(0).getText());
        assertNotNull(parts.get(1).getImageUrl());
        assertTrue(parts.get(1).getImageUrl().getUrl().startsWith("data:image/png;base64,"));
    }

    @Test
    @DisplayName("Should convert AudioBlock with valid Base64Source")
    void testAudioBlockWithValidBase64Source() {
        String base64Data = "UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAAB9AAACABAAZGF0YQAAAAA=";
        Base64Source source =
                Base64Source.builder().data(base64Data).mediaType("audio/wav").build();
        AudioBlock audioBlock = AudioBlock.builder().source(source).build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Listen to this").build(),
                                        audioBlock))
                        .build();

        OpenAIMessage result = converter.convertToMessage(msg, true);

        assertNotNull(result);
        assertEquals("user", result.getRole());
        Object content = result.getContent();
        assertNotNull(content);
        assertTrue(content instanceof List);
        @SuppressWarnings("unchecked")
        List<OpenAIContentPart> parts = (List<OpenAIContentPart>) content;
        assertEquals(2, parts.size());
        assertEquals("Listen to this", parts.get(0).getText());
        assertNotNull(parts.get(1).getInputAudio());
        assertEquals(base64Data, parts.get(1).getInputAudio().getData());
    }

    @Test
    @DisplayName("Should convert system message")
    void testConvertSystemMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("You are a helpful assistant")
                                                .build()))
                        .build();

        OpenAIMessage result = converter.convertToMessage(msg, false);

        assertNotNull(result);
        assertEquals("system", result.getRole());
        assertEquals("You are a helpful assistant", result.getContentAsString());
    }

    @Test
    @DisplayName("Should convert assistant message")
    void testConvertAssistantMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(TextBlock.builder().text("Hello! How can I help?").build()))
                        .build();

        OpenAIMessage result = converter.convertToMessage(msg, false);

        assertNotNull(result);
        assertEquals("assistant", result.getRole());
        assertEquals("Hello! How can I help?", result.getContentAsString());
    }

    @Test
    @DisplayName("Should handle ImageBlock with URLSource")
    void testImageBlockWithURLSource() {
        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(URLSource.builder().url("http://example.com/image.png").build())
                        .build();

        Msg msg = Msg.builder().role(MsgRole.USER).content(List.of(imageBlock)).build();

        OpenAIMessage result = converter.convertToMessage(msg, true);

        assertNotNull(result);
        assertEquals("user", result.getRole());
        assertTrue(result.isMultimodal());
        List<OpenAIContentPart> content = result.getContentAsList();
        assertNotNull(content);
        assertTrue(!content.isEmpty());
    }

    @Test
    @DisplayName("Should handle AudioBlock with URLSource (converted to text reference)")
    void testAudioBlockWithURLSource() {
        AudioBlock audioBlock =
                AudioBlock.builder()
                        .source(URLSource.builder().url("http://example.com/audio.wav").build())
                        .build();

        Msg msg = Msg.builder().role(MsgRole.USER).content(List.of(audioBlock)).build();

        OpenAIMessage result = converter.convertToMessage(msg, true);

        assertNotNull(result);
        assertEquals("user", result.getRole());
        assertTrue(result.isMultimodal());
        List<OpenAIContentPart> content = result.getContentAsList();
        assertNotNull(content);
        // Should contain text reference since URL-based audio is not directly supported
        assertTrue(!content.isEmpty());
    }

    @Test
    @DisplayName("Should handle Base64 AudioBlock correctly")
    void testBase64AudioBlockConversion() {
        Base64Source audioSource =
                Base64Source.builder()
                        .data("SGVsbG8gV29ybGQ=") // Base64 for "Hello World"
                        .mediaType("audio/wav")
                        .build();

        AudioBlock audioBlock = AudioBlock.builder().source(audioSource).build();

        Msg msg = Msg.builder().role(MsgRole.USER).content(List.of(audioBlock)).build();

        OpenAIMessage result = converter.convertToMessage(msg, true);

        assertNotNull(result);
        assertEquals("user", result.getRole());
        assertTrue(result.isMultimodal());
        List<OpenAIContentPart> content = result.getContentAsList();
        assertNotNull(content);
        assertTrue(!content.isEmpty());
    }

    @Test
    @DisplayName("Should handle multimodal content with mixed types")
    void testMultimodalMixedContent() {
        Base64Source imageSource =
                Base64Source.builder()
                        .data(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
                        .mediaType("image/png")
                        .build();

        ImageBlock imageBlock = ImageBlock.builder().source(imageSource).build();

        TextBlock textBlock = TextBlock.builder().text("Here's an image:").build();

        Msg msg = Msg.builder().role(MsgRole.USER).content(List.of(textBlock, imageBlock)).build();

        OpenAIMessage result = converter.convertToMessage(msg, true);

        assertNotNull(result);
        assertEquals("user", result.getRole());
        assertTrue(result.isMultimodal());
        List<OpenAIContentPart> content = result.getContentAsList();
        assertNotNull(content);
        assertTrue(content.size() >= 2, "Should contain both text and image");
    }

    @Test
    @DisplayName("Should handle URLSource for ImageBlock as data URL")
    void testURLSourceImageBlock() {
        URLSource imageSource = URLSource.builder().url("http://example.com/image.png").build();

        ImageBlock imageBlock = ImageBlock.builder().source(imageSource).build();

        Msg msg = Msg.builder().role(MsgRole.USER).content(List.of(imageBlock)).build();

        OpenAIMessage result = converter.convertToMessage(msg, true);

        assertNotNull(result);
        assertEquals("user", result.getRole());
        assertTrue(result.isMultimodal());
        List<OpenAIContentPart> content = result.getContentAsList();
        assertNotNull(content);
        assertTrue(!content.isEmpty(), "Should contain image content");
    }

    @Nested
    @DisplayName("Tool Use Block Tests")
    class ToolUseBlockTests {

        @Test
        @DisplayName("Should convert assistant message with tool calls")
        void testAssistantWithToolCalls() {
            ToolUseBlock toolBlock =
                    ToolUseBlock.builder()
                            .id("call_123")
                            .name("get_weather")
                            .input(Map.of("city", "Beijing"))
                            .build();

            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(List.of(toolBlock)).build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            assertEquals("assistant", result.getRole());
            assertNotNull(result.getToolCalls());
            assertEquals(1, result.getToolCalls().size());
            assertEquals("call_123", result.getToolCalls().get(0).getId());
            assertEquals("get_weather", result.getToolCalls().get(0).getFunction().getName());
        }

        @Test
        @DisplayName("Should convert assistant message with text and tool calls")
        void testAssistantWithTextAndToolCalls() {
            TextBlock textBlock = TextBlock.builder().text("Let me check the weather").build();
            ToolUseBlock toolBlock =
                    ToolUseBlock.builder()
                            .id("call_456")
                            .name("get_weather")
                            .input(Map.of("city", "Shanghai"))
                            .build();

            Msg msg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(List.of(textBlock, toolBlock))
                            .build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            assertEquals("assistant", result.getRole());
            assertEquals("Let me check the weather", result.getContentAsString());
            assertNotNull(result.getToolCalls());
            assertEquals(1, result.getToolCalls().size());
        }

        @Test
        @DisplayName("Should include thought signature in tool call metadata")
        void testToolCallWithThoughtSignature() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, "signature_abc");

            ToolUseBlock toolBlock =
                    ToolUseBlock.builder()
                            .id("call_789")
                            .name("test_tool")
                            .input(Map.of())
                            .metadata(metadata)
                            .build();

            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(List.of(toolBlock)).build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            assertNotNull(result.getToolCalls());
            assertEquals(
                    "signature_abc",
                    result.getToolCalls().get(0).getFunction().getThoughtSignature());
        }

        @Test
        @DisplayName("Should handle multiple tool calls")
        void testMultipleToolCalls() {
            ToolUseBlock tool1 =
                    ToolUseBlock.builder()
                            .id("call_1")
                            .name("tool1")
                            .input(Map.of("a", "1"))
                            .build();
            ToolUseBlock tool2 =
                    ToolUseBlock.builder()
                            .id("call_2")
                            .name("tool2")
                            .input(Map.of("b", "2"))
                            .build();

            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(List.of(tool1, tool2)).build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            assertNotNull(result.getToolCalls());
            assertEquals(2, result.getToolCalls().size());
            assertEquals("tool1", result.getToolCalls().get(0).getFunction().getName());
            assertEquals("tool2", result.getToolCalls().get(1).getFunction().getName());
        }
    }

    @Nested
    @DisplayName("Tool Result Block Tests")
    class ToolResultBlockTests {

        @Test
        @DisplayName("Should convert tool message with result")
        void testToolMessageWithResult() {
            ToolResultBlock resultBlock =
                    new ToolResultBlock(
                            "call_123",
                            "get_weather",
                            List.of(TextBlock.builder().text("Sunny, 25°C").build()),
                            null);

            Msg msg = Msg.builder().role(MsgRole.TOOL).content(List.of(resultBlock)).build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            assertEquals("tool", result.getRole());
            assertEquals("call_123", result.getToolCallId());
            assertEquals("Sunny, 25°C", result.getContentAsString());
        }

        @Test
        @DisplayName("Should handle tool message with error")
        void testToolMessageWithError() {
            ToolResultBlock resultBlock =
                    new ToolResultBlock(
                            "call_error",
                            "failing_tool",
                            List.of(TextBlock.builder().text("Error: Connection timeout").build()),
                            null);

            Msg msg = Msg.builder().role(MsgRole.TOOL).content(List.of(resultBlock)).build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            assertEquals("tool", result.getRole());
            assertEquals("call_error", result.getToolCallId());
            // Error should be included in content
            assertTrue(result.getContentAsString().contains("Connection timeout"));
        }
    }

    @Nested
    @DisplayName("Thinking Block Tests")
    class ThinkingBlockTests {

        @Test
        @DisplayName("Should convert thinking block to reasoning_content")
        void testThinkingBlockToReasoningContent() {
            ThinkingBlock thinkingBlock =
                    ThinkingBlock.builder().thinking("My reasoning process").build();
            TextBlock textBlock = TextBlock.builder().text("Final answer").build();

            Msg msg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(List.of(thinkingBlock, textBlock))
                            .build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            assertEquals("assistant", result.getRole());
            assertEquals("My reasoning process", result.getReasoningContent());
            assertEquals("Final answer", result.getContentAsString());
        }

        @Test
        @DisplayName("Should handle thinking block with reasoning details metadata")
        void testThinkingBlockWithReasoningDetailsMetadata() {
            List<OpenAIReasoningDetail> details =
                    List.of(
                            new OpenAIReasoningDetail() {
                                {
                                    setType("reasoning.encrypted");
                                    setData("encrypted_signature");
                                }
                            });

            Map<String, Object> metadata = new HashMap<>();
            metadata.put(ThinkingBlock.METADATA_REASONING_DETAILS, details);

            ThinkingBlock thinkingBlock =
                    ThinkingBlock.builder().thinking("My thinking").metadata(metadata).build();

            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(List.of(thinkingBlock)).build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            assertNotNull(result.getReasoningDetails());
        }

        @Test
        @DisplayName("Should use first thinking block only (getFirstContentBlock behavior)")
        void testMultipleThinkingBlocks() {
            ThinkingBlock thinking1 = ThinkingBlock.builder().thinking("First thought").build();
            ThinkingBlock thinking2 = ThinkingBlock.builder().thinking("Second thought").build();
            TextBlock textBlock = TextBlock.builder().text("Answer").build();

            Msg msg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(List.of(thinking1, thinking2, textBlock))
                            .build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            // Implementation uses getFirstContentBlock, so only first ThinkingBlock is used
            assertEquals("First thought", result.getReasoningContent());
        }
    }

    @Nested
    @DisplayName("Name Field Tests")
    class NameFieldTests {

        @Test
        @DisplayName("Should set name field for user message")
        void testUserMessageWithName() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .name("Alice")
                            .content(List.of(TextBlock.builder().text("Hello").build()))
                            .build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            assertEquals("Alice", result.getName());
        }

        @Test
        @DisplayName("Should set name field for assistant message")
        void testAssistantMessageWithName() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .name("Agent")
                            .content(List.of(TextBlock.builder().text("Hi").build()))
                            .build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            assertEquals("Agent", result.getName());
        }

        @Test
        @DisplayName("Should handle null name")
        void testMessageWithNullName() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .name(null)
                            .content(List.of(TextBlock.builder().text("Hello").build()))
                            .build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            assertNull(result.getName());
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle empty content list")
        void testEmptyContentList() {
            Msg msg = Msg.builder().role(MsgRole.USER).content(List.of()).build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            assertEquals("user", result.getRole());
        }

        @Test
        @DisplayName("Should handle file URL for image")
        void testFileURLImageBlock() {
            URLSource imageSource =
                    URLSource.builder().url("file:///path/to/local/image.png").build();

            ImageBlock imageBlock = ImageBlock.builder().source(imageSource).build();

            Msg msg = Msg.builder().role(MsgRole.USER).content(List.of(imageBlock)).build();

            OpenAIMessage result = converter.convertToMessage(msg, true);

            assertNotNull(result);
            assertTrue(result.isMultimodal());
        }

        @Test
        @DisplayName("Should handle non-multimodal conversion for user message with images")
        void testNonMultimodalConversion() {
            Base64Source imageSource =
                    Base64Source.builder()
                            .data("iVBORw0KGgoAAAANSUhEUg==")
                            .mediaType("image/png")
                            .build();
            ImageBlock imageBlock = ImageBlock.builder().source(imageSource).build();
            TextBlock textBlock = TextBlock.builder().text("See image").build();

            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(List.of(textBlock, imageBlock))
                            .build();

            // Convert without multimodal support
            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            // Should still extract text
            assertEquals("See image", result.getContentAsString());
        }

        @Test
        @DisplayName("Should handle null content")
        void testNullContent() {
            Msg msg = Msg.builder().role(MsgRole.USER).content((List<ContentBlock>) null).build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            assertEquals("user", result.getRole());
        }

        @Test
        @DisplayName("Should handle system message with null content")
        void testSystemMessageWithNullContent() {
            Msg msg = Msg.builder().role(MsgRole.SYSTEM).content((List<ContentBlock>) null).build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            assertEquals("system", result.getRole());
            assertEquals("", result.getContentAsString());
        }

        @Test
        @DisplayName("Should handle tool message with multimodal result")
        void testToolMessageWithMultimodalResult() {
            Base64Source imageSource =
                    Base64Source.builder()
                            .data("iVBORw0KGgoAAAANSUhEUg==")
                            .mediaType("image/png")
                            .build();
            ImageBlock imageBlock = ImageBlock.builder().source(imageSource).build();

            ToolResultBlock resultBlock =
                    new ToolResultBlock(
                            "call_123",
                            "screenshot_tool",
                            List.of(
                                    TextBlock.builder().text("Screenshot taken").build(),
                                    imageBlock),
                            null);

            Msg msg = Msg.builder().role(MsgRole.TOOL).content(List.of(resultBlock)).build();

            OpenAIMessage result = converter.convertToMessage(msg, true);

            assertNotNull(result);
            assertEquals("tool", result.getRole());
            assertEquals("call_123", result.getToolCallId());
            assertTrue(result.isMultimodal());
        }

        @Test
        @DisplayName("Should handle tool message without result block")
        void testToolMessageWithoutResultBlock() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.TOOL)
                            .content(List.of(TextBlock.builder().text("No result").build()))
                            .build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            assertEquals("tool", result.getRole());
            // Should generate tool call ID
            assertTrue(result.getToolCallId().startsWith("tool_call_"));
        }

        @Test
        @DisplayName("Should handle system message with tool result block")
        void testSystemMessageWithToolResultBlock() {
            ToolResultBlock resultBlock =
                    new ToolResultBlock(
                            "call_456",
                            "tool_name",
                            List.of(TextBlock.builder().text("Result").build()),
                            null);

            Msg msg = Msg.builder().role(MsgRole.SYSTEM).content(List.of(resultBlock)).build();

            // System message with tool result should be treated as TOOL role
            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            assertEquals("tool", result.getRole());
            assertEquals("call_456", result.getToolCallId());
        }

        @Test
        @DisplayName("Should handle assistant message with null tool input")
        void testAssistantWithNullToolInput() {
            ToolUseBlock toolBlock =
                    ToolUseBlock.builder().id("call_789").name("test_tool").input(null).build();

            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(List.of(toolBlock)).build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            assertEquals("assistant", result.getRole());
            assertNotNull(result.getToolCalls());
            assertEquals(1, result.getToolCalls().size());
        }

        @Test
        @DisplayName("Should handle assistant message with null id in tool block")
        void testAssistantWithNullToolId() {
            ToolUseBlock toolBlock =
                    ToolUseBlock.builder()
                            .id(null)
                            .name("test_tool")
                            .input(Map.of("key", "value"))
                            .build();

            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(List.of(toolBlock)).build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            // Tool call with null id should be skipped
            assertEquals("assistant", result.getRole());
        }

        @Test
        @DisplayName("Should handle assistant message with null name in tool block")
        void testAssistantWithNullToolName() {
            ToolUseBlock toolBlock =
                    ToolUseBlock.builder()
                            .id("call_abc")
                            .name(null)
                            .input(Map.of("key", "value"))
                            .build();

            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(List.of(toolBlock)).build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNotNull(result);
            // Tool call with null name should be skipped
            assertEquals("assistant", result.getRole());
        }
    }
}
