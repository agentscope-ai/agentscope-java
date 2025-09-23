/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContentBlockUtilsTest {

    @Test
    void testExtractTextContent() {
        // Test TextBlock
        TextBlock textBlock = TextBlock.builder().text("Hello World").build();
        assertEquals("Hello World", ContentBlockUtils.extractTextContent(textBlock));

        // Test ThinkingBlock
        ThinkingBlock thinkingBlock = ThinkingBlock.builder().text("I'm thinking...").build();
        assertEquals("I'm thinking...", ContentBlockUtils.extractTextContent(thinkingBlock));

        // Test ImageBlock (should return empty string)
        URLSource urlSource = URLSource.builder().url("https://example.com/image.jpg").build();
        ImageBlock imageBlock = ImageBlock.builder().source(urlSource).build();
        assertEquals("", ContentBlockUtils.extractTextContent(imageBlock));

        // Test null
        assertEquals("", ContentBlockUtils.extractTextContent(null));
    }

    @Test
    void testHasTextContent() {
        TextBlock textBlock = TextBlock.builder().text("Hello").build();
        assertTrue(ContentBlockUtils.hasTextContent(textBlock));

        ThinkingBlock thinkingBlock = ThinkingBlock.builder().text("Thinking").build();
        assertTrue(ContentBlockUtils.hasTextContent(thinkingBlock));

        URLSource urlSource = URLSource.builder().url("https://example.com/image.jpg").build();
        ImageBlock imageBlock = ImageBlock.builder().source(urlSource).build();
        assertFalse(ContentBlockUtils.hasTextContent(imageBlock));

        assertFalse(ContentBlockUtils.hasTextContent(null));
    }

    @Test
    void testHasMediaContent() {
        URLSource urlSource = URLSource.builder().url("https://example.com/image.jpg").build();

        ImageBlock imageBlock = ImageBlock.builder().source(urlSource).build();
        assertTrue(ContentBlockUtils.hasMediaContent(imageBlock));

        AudioBlock audioBlock = AudioBlock.builder().source(urlSource).build();
        assertTrue(ContentBlockUtils.hasMediaContent(audioBlock));

        VideoBlock videoBlock = VideoBlock.builder().source(urlSource).build();
        assertTrue(ContentBlockUtils.hasMediaContent(videoBlock));

        TextBlock textBlock = TextBlock.builder().text("Hello").build();
        assertFalse(ContentBlockUtils.hasMediaContent(textBlock));

        assertFalse(ContentBlockUtils.hasMediaContent(null));
    }

    @Test
    void testGetMediaInfo() {
        // Test URLSource
        URLSource urlSource = URLSource.builder().url("https://example.com/image.jpg").build();
        ImageBlock imageBlock = ImageBlock.builder().source(urlSource).build();

        MediaInfo mediaInfo = ContentBlockUtils.getMediaInfo(imageBlock);
        assertNotNull(mediaInfo);
        assertEquals("https://example.com/image.jpg", mediaInfo.getData());
        assertEquals(MediaInfo.SourceType.URL, mediaInfo.getSourceType());

        // Test Base64Source
        Base64Source base64Source =
                Base64Source.builder()
                        .mediaType("image/png")
                        .data(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==")
                        .build();
        ImageBlock base64ImageBlock = ImageBlock.builder().source(base64Source).build();

        MediaInfo base64MediaInfo = ContentBlockUtils.getMediaInfo(base64ImageBlock);
        assertNotNull(base64MediaInfo);
        assertTrue(base64MediaInfo.getData().startsWith("data:image/png;base64,"));
        assertEquals(MediaInfo.SourceType.BASE64, base64MediaInfo.getSourceType());

        // Test non-media content
        TextBlock textBlock = TextBlock.builder().text("Hello").build();
        MediaInfo nullInfo = ContentBlockUtils.getMediaInfo(textBlock);
        assertNull(nullInfo);
    }

    @Test
    void testCreateMediaDescription() {
        URLSource urlSource = URLSource.builder().url("https://example.com/image.jpg").build();
        ImageBlock imageBlock = ImageBlock.builder().source(urlSource).build();

        String description = ContentBlockUtils.createMediaDescription(imageBlock);
        assertTrue(description.contains("[Image content"));
        assertTrue(description.contains("https://example.com/image.jpg"));

        Base64Source base64Source =
                Base64Source.builder().mediaType("audio/mp3").data("base64data").build();
        AudioBlock audioBlock = AudioBlock.builder().source(base64Source).build();

        String audioDescription = ContentBlockUtils.createMediaDescription(audioBlock);
        assertTrue(audioDescription.contains("[Audio content"));
        assertTrue(audioDescription.contains("audio/mp3"));

        // Test non-media content
        TextBlock textBlock = TextBlock.builder().text("Hello").build();
        assertEquals("", ContentBlockUtils.createMediaDescription(textBlock));
    }

    @Test
    void testToTextRepresentation() {
        // Test text content
        TextBlock textBlock = TextBlock.builder().text("Hello World").build();
        assertEquals("Hello World", ContentBlockUtils.toTextRepresentation(textBlock));

        // Test media content
        URLSource urlSource = URLSource.builder().url("https://example.com/image.jpg").build();
        ImageBlock imageBlock = ImageBlock.builder().source(urlSource).build();

        String representation = ContentBlockUtils.toTextRepresentation(imageBlock);
        assertTrue(representation.contains("[Image content"));

        // Test null
        assertEquals("", ContentBlockUtils.toTextRepresentation(null));
    }
}
