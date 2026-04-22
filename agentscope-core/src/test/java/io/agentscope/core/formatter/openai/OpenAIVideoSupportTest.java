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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.openai.dto.OpenAIContentPart;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for video multimodal support in OpenAI formatter.
 *
 * <p>These tests verify the video conversion logic including:
 * <ul>
 *   <li>VideoBlock with URLSource conversion</li>
 *   <li>VideoBlock with Base64Source conversion</li>
 *   <li>Null source handling</li>
 *   <li>Mixed content (text + video) conversion</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("OpenAI Video Support Tests")
class OpenAIVideoSupportTest {

    private OpenAIMessageConverter converter;

    @BeforeEach
    void setUp() {
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

    @Nested
    @DisplayName("OpenAIConverterUtils Video Tests")
    class ConverterUtilsTests {

        @Test
        @DisplayName("Should convert URLSource to URL string")
        void testConvertVideoUrlSource() {
            URLSource source = new URLSource("https://example.com/video.mp4");
            String result = OpenAIConverterUtils.convertVideoSourceToUrl(source);
            assertEquals("https://example.com/video.mp4", result);
        }

        @Test
        @DisplayName("Should convert Base64Source to data URI")
        void testConvertVideoBase64Source() {
            Base64Source source = new Base64Source("video/mp4", "dmlkZW9kYXRh");
            String result = OpenAIConverterUtils.convertVideoSourceToUrl(source);
            assertEquals("data:video/mp4;base64,dmlkZW9kYXRh", result);
        }

        @Test
        @DisplayName("Should throw exception for null source")
        void testConvertVideoNullSource() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> OpenAIConverterUtils.convertVideoSourceToUrl(null));
        }

        @Test
        @DisplayName("Should throw exception for empty URL")
        void testConvertVideoEmptyUrl() {
            URLSource source = new URLSource("");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> OpenAIConverterUtils.convertVideoSourceToUrl(source));
        }
    }

    @Nested
    @DisplayName("OpenAIContentPart Video Tests")
    class ContentPartTests {

        @Test
        @DisplayName("Should create video_url content part")
        void testCreateVideoUrlContentPart() {
            OpenAIContentPart part = OpenAIContentPart.videoUrl("https://example.com/video.mp4");

            assertEquals("video_url", part.getType());
            assertNotNull(part.getVideoUrl());
            assertEquals("https://example.com/video.mp4", part.getVideoUrl().getUrl());
        }

        @Test
        @DisplayName("Should create video_url content part with data URI")
        void testCreateVideoUrlContentPartWithDataUri() {
            String dataUri = "data:video/mp4;base64,dmlkZW9kYXRh";
            OpenAIContentPart part = OpenAIContentPart.videoUrl(dataUri);

            assertEquals("video_url", part.getType());
            assertNotNull(part.getVideoUrl());
            assertEquals(dataUri, part.getVideoUrl().getUrl());
        }
    }

    @Nested
    @DisplayName("OpenAIMessageConverter Video Tests")
    class MessageConverterTests {

        @Test
        @DisplayName("Should convert VideoBlock with URLSource")
        void testConvertVideoBlockWithUrlSource() {
            VideoBlock videoBlock =
                    VideoBlock.builder()
                            .source(new URLSource("https://example.com/video.mp4"))
                            .build();

            Msg msg = Msg.builder().role(MsgRole.USER).content(List.of(videoBlock)).build();

            OpenAIMessage result = converter.convertToMessage(msg, true);

            assertEquals("user", result.getRole());
            assertNotNull(result.getContent());
            assertTrue(result.getContent() instanceof List);

            @SuppressWarnings("unchecked")
            List<OpenAIContentPart> parts = (List<OpenAIContentPart>) result.getContent();
            assertEquals(1, parts.size());

            OpenAIContentPart videoPart = parts.get(0);
            assertEquals("video_url", videoPart.getType());
            assertNotNull(videoPart.getVideoUrl());
            assertEquals("https://example.com/video.mp4", videoPart.getVideoUrl().getUrl());
        }

        @Test
        @DisplayName("Should convert VideoBlock with Base64Source")
        void testConvertVideoBlockWithBase64Source() {
            VideoBlock videoBlock =
                    VideoBlock.builder()
                            .source(new Base64Source("video/webm", "dmlkZW9kYXRh"))
                            .build();

            Msg msg = Msg.builder().role(MsgRole.USER).content(List.of(videoBlock)).build();

            OpenAIMessage result = converter.convertToMessage(msg, true);

            @SuppressWarnings("unchecked")
            List<OpenAIContentPart> parts = (List<OpenAIContentPart>) result.getContent();
            assertEquals(1, parts.size());

            OpenAIContentPart videoPart = parts.get(0);
            assertEquals("video_url", videoPart.getType());
            assertEquals("data:video/webm;base64,dmlkZW9kYXRh", videoPart.getVideoUrl().getUrl());
        }

        @Test
        @DisplayName("Should handle VideoBlock with null source gracefully")
        void testConvertVideoBlockWithNullSource() {
            VideoBlock videoBlock = VideoBlock.builder().source(null).build();

            Msg msg = Msg.builder().role(MsgRole.USER).content(List.of(videoBlock)).build();

            // Should not throw, just skip the block
            OpenAIMessage result = converter.convertToMessage(msg, true);

            assertNotNull(result);
            // Content should be empty string since the video block was skipped
            assertTrue(
                    result.getContent() instanceof String
                            || (result.getContent() instanceof List
                                    && ((List<?>) result.getContent()).isEmpty()));
        }

        @Test
        @DisplayName("Should convert mixed text and video content")
        void testConvertMixedTextAndVideoContent() {
            TextBlock textBlock = TextBlock.builder().text("Describe this video").build();
            VideoBlock videoBlock =
                    VideoBlock.builder()
                            .source(new URLSource("https://youtube.com/watch?v=abc123"))
                            .build();

            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(List.of(textBlock, videoBlock))
                            .build();

            OpenAIMessage result = converter.convertToMessage(msg, true);

            @SuppressWarnings("unchecked")
            List<OpenAIContentPart> parts = (List<OpenAIContentPart>) result.getContent();
            assertEquals(2, parts.size());

            // First part should be text
            assertEquals("text", parts.get(0).getType());
            assertEquals("Describe this video", parts.get(0).getText());

            // Second part should be video
            assertEquals("video_url", parts.get(1).getType());
            assertEquals("https://youtube.com/watch?v=abc123", parts.get(1).getVideoUrl().getUrl());
        }
    }
}
