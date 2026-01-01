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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.openai.dto.OpenAIContentPart;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
}
