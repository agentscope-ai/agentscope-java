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
package io.agentscope.core.formatter.dashscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.formatter.dashscope.dto.DashScopeContentPart;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DashScopeMediaConverter}. */
@Tag("unit")
class DashScopeMediaConverterTest {

    private final DashScopeMediaConverter converter = new DashScopeMediaConverter();

    @Test
    void testConvertImageBlockToContentPartWithMinPixels() throws Exception {
        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(URLSource.builder().url("https://example.com/image.png").build())
                        .minPixels(256)
                        .build();

        DashScopeContentPart contentPart = converter.convertImageBlockToContentPart(imageBlock);

        assertEquals("https://example.com/image.png", contentPart.getImage());
        assertEquals(256, contentPart.getMinPixels());
        assertNull(contentPart.getMaxPixels());
    }

    @Test
    void testConvertImageBlockToContentPartWithMaxPixels() throws Exception {
        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(URLSource.builder().url("https://example.com/image.png").build())
                        .maxPixels(1048576)
                        .build();

        DashScopeContentPart contentPart = converter.convertImageBlockToContentPart(imageBlock);

        assertEquals("https://example.com/image.png", contentPart.getImage());
        assertEquals(1048576, contentPart.getMaxPixels());
        assertNull(contentPart.getMinPixels());
    }

    @Test
    void testConvertImageBlockToContentPartWithBothPixels() throws Exception {
        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(URLSource.builder().url("https://example.com/image.png").build())
                        .minPixels(256)
                        .maxPixels(1048576)
                        .build();

        DashScopeContentPart contentPart = converter.convertImageBlockToContentPart(imageBlock);

        assertEquals("https://example.com/image.png", contentPart.getImage());
        assertEquals(256, contentPart.getMinPixels());
        assertEquals(1048576, contentPart.getMaxPixels());
    }

    @Test
    void testConvertImageBlockToContentPartWithBase64Source() throws Exception {
        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("image/png")
                                        .data("iVBORw0KGgo...")
                                        .build())
                        .minPixels(128)
                        .maxPixels(512000)
                        .build();

        DashScopeContentPart contentPart = converter.convertImageBlockToContentPart(imageBlock);

        assertEquals("data:image/png;base64,iVBORw0KGgo...", contentPart.getImage());
        assertEquals(128, contentPart.getMinPixels());
        assertEquals(512000, contentPart.getMaxPixels());
    }

    @Test
    void testConvertImageBlockToContentPartWithoutPixels() throws Exception {
        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(URLSource.builder().url("https://example.com/image.png").build())
                        .build();

        DashScopeContentPart contentPart = converter.convertImageBlockToContentPart(imageBlock);

        assertEquals("https://example.com/image.png", contentPart.getImage());
        assertNull(contentPart.getMinPixels());
        assertNull(contentPart.getMaxPixels());
    }

    @Test
    void testConvertVideoBlockToContentPartWithFps() throws Exception {
        VideoBlock videoBlock =
                VideoBlock.builder()
                        .source(URLSource.builder().url("https://example.com/video.mp4").build())
                        .fps(2.5f)
                        .build();

        DashScopeContentPart contentPart = converter.convertVideoBlockToContentPart(videoBlock);

        assertEquals("https://example.com/video.mp4", contentPart.getVideoAsString());
        assertEquals(2.5f, contentPart.getFps());
        assertNull(contentPart.getMaxFrames());
    }

    @Test
    void testConvertVideoBlockToContentPartWithMaxFrames() throws Exception {
        VideoBlock videoBlock =
                VideoBlock.builder()
                        .source(URLSource.builder().url("https://example.com/video.mp4").build())
                        .maxFrames(32)
                        .build();

        DashScopeContentPart contentPart = converter.convertVideoBlockToContentPart(videoBlock);

        assertEquals("https://example.com/video.mp4", contentPart.getVideoAsString());
        assertEquals(32, contentPart.getMaxFrames());
        assertNull(contentPart.getFps());
    }

    @Test
    void testConvertVideoBlockToContentPartWithMinPixels() throws Exception {
        VideoBlock videoBlock =
                VideoBlock.builder()
                        .source(URLSource.builder().url("https://example.com/video.mp4").build())
                        .minPixels(256)
                        .build();

        DashScopeContentPart contentPart = converter.convertVideoBlockToContentPart(videoBlock);

        assertEquals("https://example.com/video.mp4", contentPart.getVideoAsString());
        assertEquals(256, contentPart.getMinPixels());
    }

    @Test
    void testConvertVideoBlockToContentPartWithMaxPixels() throws Exception {
        VideoBlock videoBlock =
                VideoBlock.builder()
                        .source(URLSource.builder().url("https://example.com/video.mp4").build())
                        .maxPixels(1048576)
                        .build();

        DashScopeContentPart contentPart = converter.convertVideoBlockToContentPart(videoBlock);

        assertEquals("https://example.com/video.mp4", contentPart.getVideoAsString());
        assertEquals(1048576, contentPart.getMaxPixels());
    }

    @Test
    void testConvertVideoBlockToContentPartWithTotalPixels() throws Exception {
        VideoBlock videoBlock =
                VideoBlock.builder()
                        .source(URLSource.builder().url("https://example.com/video.mp4").build())
                        .totalPixels(2097152)
                        .build();

        DashScopeContentPart contentPart = converter.convertVideoBlockToContentPart(videoBlock);

        assertEquals("https://example.com/video.mp4", contentPart.getVideoAsString());
        assertEquals(2097152, contentPart.getTotalPixels());
    }

    @Test
    void testConvertVideoBlockToContentPartWithAllParameters() throws Exception {
        VideoBlock videoBlock =
                VideoBlock.builder()
                        .source(URLSource.builder().url("https://example.com/video.mp4").build())
                        .fps(2.0f)
                        .maxFrames(16)
                        .minPixels(256)
                        .maxPixels(512000)
                        .totalPixels(8192000)
                        .build();

        DashScopeContentPart contentPart = converter.convertVideoBlockToContentPart(videoBlock);

        assertEquals("https://example.com/video.mp4", contentPart.getVideoAsString());
        assertEquals(2.0f, contentPart.getFps());
        assertEquals(16, contentPart.getMaxFrames());
        assertEquals(256, contentPart.getMinPixels());
        assertEquals(512000, contentPart.getMaxPixels());
        assertEquals(8192000, contentPart.getTotalPixels());
    }

    @Test
    void testConvertVideoBlockToContentPartWithBase64Source() throws Exception {
        VideoBlock videoBlock =
                VideoBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("video/mp4")
                                        .data("AAAAIGZ0eXBpc29tAAACAGlzb21pc28y...")
                                        .build())
                        .fps(1.5f)
                        .maxFrames(8)
                        .minPixels(128)
                        .maxPixels(256000)
                        .totalPixels(2048000)
                        .build();

        DashScopeContentPart contentPart = converter.convertVideoBlockToContentPart(videoBlock);

        assertEquals(
                "data:video/mp4;base64,AAAAIGZ0eXBpc29tAAACAGlzb21pc28y...",
                contentPart.getVideoAsString());
        assertEquals(1.5f, contentPart.getFps());
        assertEquals(8, contentPart.getMaxFrames());
        assertEquals(128, contentPart.getMinPixels());
        assertEquals(256000, contentPart.getMaxPixels());
        assertEquals(2048000, contentPart.getTotalPixels());
    }

    @Test
    void testConvertVideoBlockToContentPartWithoutParameters() throws Exception {
        VideoBlock videoBlock =
                VideoBlock.builder()
                        .source(URLSource.builder().url("https://example.com/video.mp4").build())
                        .build();

        DashScopeContentPart contentPart = converter.convertVideoBlockToContentPart(videoBlock);

        assertEquals("https://example.com/video.mp4", contentPart.getVideoAsString());
        assertNull(contentPart.getFps());
        assertNull(contentPart.getMaxFrames());
        assertNull(contentPart.getMinPixels());
        assertNull(contentPart.getMaxPixels());
        assertNull(contentPart.getTotalPixels());
    }

    @Test
    void testImageBlockBuilderWithAllParameters() {
        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(URLSource.builder().url("https://example.com/image.png").build())
                        .minPixels(128)
                        .maxPixels(1048576)
                        .build();

        assertEquals(
                "https://example.com/image.png", ((URLSource) imageBlock.getSource()).getUrl());
        assertEquals(128, imageBlock.getMinPixels());
        assertEquals(1048576, imageBlock.getMaxPixels());
    }

    @Test
    void testVideoBlockBuilderWithAllParameters() {
        VideoBlock videoBlock =
                VideoBlock.builder()
                        .source(URLSource.builder().url("https://example.com/video.mp4").build())
                        .fps(3.0f)
                        .maxFrames(24)
                        .minPixels(256)
                        .maxPixels(512000)
                        .totalPixels(12288000)
                        .build();

        assertEquals(
                "https://example.com/video.mp4", ((URLSource) videoBlock.getSource()).getUrl());
        assertEquals(3.0f, videoBlock.getFps());
        assertEquals(24, videoBlock.getMaxFrames());
        assertEquals(256, videoBlock.getMinPixels());
        assertEquals(512000, videoBlock.getMaxPixels());
        assertEquals(12288000, videoBlock.getTotalPixels());
    }

    @Test
    void testDashScopeContentPartBuilderWithAllParameters() {
        DashScopeContentPart contentPart =
                DashScopeContentPart.builder()
                        .image("https://example.com/image.png")
                        .fps(2.0f)
                        .maxFrames(16)
                        .minPixels(128)
                        .maxPixels(512000)
                        .totalPixels(8192000)
                        .build();

        assertEquals("https://example.com/image.png", contentPart.getImage());
        assertEquals(2.0f, contentPart.getFps());
        assertEquals(16, contentPart.getMaxFrames());
        assertEquals(128, contentPart.getMinPixels());
        assertEquals(512000, contentPart.getMaxPixels());
        assertEquals(8192000, contentPart.getTotalPixels());
    }

    @Test
    void testImageBlockSourceNotNull() {
        try {
            ImageBlock.builder().source(null).build();
        } catch (NullPointerException e) {
            assertNotNull(e);
            return;
        }
    }

    @Test
    void testVideoBlockSourceNotNull() {
        try {
            VideoBlock.builder().source(null).build();
        } catch (NullPointerException e) {
            assertNotNull(e);
            return;
        }
    }

    @Test
    void testImageBlockDefaultConstructorNullPixels() {
        ImageBlock imageBlock =
                new ImageBlock(URLSource.builder().url("https://example.com/image.png").build());

        assertNull(imageBlock.getMinPixels());
        assertNull(imageBlock.getMaxPixels());
    }

    @Test
    void testVideoBlockDefaultConstructorNullParameters() {
        VideoBlock videoBlock =
                new VideoBlock(URLSource.builder().url("https://example.com/video.mp4").build());

        assertNull(videoBlock.getFps());
        assertNull(videoBlock.getMaxFrames());
        assertNull(videoBlock.getMinPixels());
        assertNull(videoBlock.getMaxPixels());
        assertNull(videoBlock.getTotalPixels());
    }
}
