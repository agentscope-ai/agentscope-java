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
package io.agentscope.core.formatter.gemini;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.Blob;
import com.google.genai.types.Part;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for GeminiMediaConverter.
 */
class GeminiMediaConverterTest extends GeminiFormatterTestBase {

    private final GeminiMediaConverter converter = new GeminiMediaConverter();

    @Test
    void testConvertImageBlockWithBase64Source() {
        Base64Source source =
                Base64Source.builder()
                        .data("ZmFrZSBpbWFnZSBjb250ZW50")
                        .mediaType("image/png")
                        .build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        Part result = converter.convertToInlineDataPart(block);

        assertNotNull(result);
        assertTrue(result.inlineData().isPresent());
        Blob blob = result.inlineData().get();
        assertTrue(blob.data().isPresent());
        assertTrue(blob.mimeType().isPresent());

        byte[] expectedData = "fake image content".getBytes();
        assertArrayEquals(expectedData, blob.data().get());
        assertEquals("image/png", blob.mimeType().get());
    }

    @Test
    void testConvertImageBlockWithURLSource() {
        URLSource source = URLSource.builder().url(tempImageFile.toString()).build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        Part result = converter.convertToInlineDataPart(block);

        assertNotNull(result);
        assertTrue(result.inlineData().isPresent());
        Blob blob = result.inlineData().get();
        assertTrue(blob.data().isPresent());
        assertTrue(blob.mimeType().isPresent());

        byte[] expectedData = "fake image content".getBytes();
        assertArrayEquals(expectedData, blob.data().get());
        assertEquals("image/png", blob.mimeType().get());
    }

    @Test
    void testConvertAudioBlockWithBase64Source() {
        Base64Source source =
                Base64Source.builder()
                        .data("ZmFrZSBhdWRpbyBjb250ZW50")
                        .mediaType("audio/mp3")
                        .build();
        AudioBlock block = AudioBlock.builder().source(source).build();

        Part result = converter.convertToInlineDataPart(block);

        assertNotNull(result);
        assertTrue(result.inlineData().isPresent());
        Blob blob = result.inlineData().get();

        byte[] expectedData = "fake audio content".getBytes();
        assertArrayEquals(expectedData, blob.data().get());
        assertEquals("audio/mp3", blob.mimeType().get());
    }

    @Test
    void testConvertAudioBlockWithURLSource() {
        URLSource source = URLSource.builder().url(tempAudioFile.toString()).build();
        AudioBlock block = AudioBlock.builder().source(source).build();

        Part result = converter.convertToInlineDataPart(block);

        assertNotNull(result);
        assertTrue(result.inlineData().isPresent());
        Blob blob = result.inlineData().get();

        byte[] expectedData = "fake audio content".getBytes();
        assertArrayEquals(expectedData, blob.data().get());
        assertEquals("audio/mp3", blob.mimeType().get());
    }

    @Test
    void testConvertVideoBlockWithBase64Source() {
        Base64Source source =
                Base64Source.builder()
                        .data("ZmFrZSB2aWRlbyBjb250ZW50")
                        .mediaType("video/mp4")
                        .build();
        VideoBlock block = VideoBlock.builder().source(source).build();

        Part result = converter.convertToInlineDataPart(block);

        assertNotNull(result);
        assertTrue(result.inlineData().isPresent());
        Blob blob = result.inlineData().get();

        byte[] expectedData = "fake video content".getBytes();
        assertArrayEquals(expectedData, blob.data().get());
        assertEquals("video/mp4", blob.mimeType().get());
    }

    @Test
    void testUnsupportedExtension() {
        URLSource source = URLSource.builder().url("file.xyz").build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        assertThrows(RuntimeException.class, () -> converter.convertToInlineDataPart(block));
    }

    @Test
    void testFileNotFound() {
        URLSource source = URLSource.builder().url("/nonexistent/file.png").build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        assertThrows(RuntimeException.class, () -> converter.convertToInlineDataPart(block));
    }

    @Test
    void testBase64EncodingDecoding() {
        // Test that base64 encoding/decoding works correctly
        String originalText = "fake image content";
        String base64Encoded = Base64.getEncoder().encodeToString(originalText.getBytes());
        assertEquals("ZmFrZSBpbWFnZSBjb250ZW50", base64Encoded);

        Base64Source source =
                Base64Source.builder().data(base64Encoded).mediaType("image/png").build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        Part result = converter.convertToInlineDataPart(block);
        byte[] resultData = result.inlineData().get().data().get();

        assertArrayEquals(originalText.getBytes(), resultData);
    }
}
