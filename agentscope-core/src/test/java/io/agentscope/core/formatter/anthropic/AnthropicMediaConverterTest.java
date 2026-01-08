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
package io.agentscope.core.formatter.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.formatter.anthropic.dto.AnthropicContent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.URLSource;
import java.util.Base64;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Unit tests for AnthropicMediaConverter. */
class AnthropicMediaConverterTest extends AnthropicFormatterTestBase {

    private final AnthropicMediaConverter converter = new AnthropicMediaConverter();

    @Test
    void testConvertImageBlockWithBase64Source() throws Exception {
        Base64Source source =
                Base64Source.builder()
                        .data("ZmFrZSBpbWFnZSBjb250ZW50")
                        .mediaType("image/png")
                        .build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        AnthropicContent.ImageSource result = converter.convertImageBlock(block);

        assertNotNull(result);
        assertEquals("base64", result.getType());
        assertEquals("ZmFrZSBpbWFnZSBjb250ZW50", result.getData());
        assertEquals("image/png", result.getMediaType());
    }

    @Test
    void testConvertImageBlockWithURLSourceLocal() throws Exception {
        URLSource source =
                URLSource.builder().url(tempImageFile.toAbsolutePath().toString()).build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        AnthropicContent.ImageSource result = converter.convertImageBlock(block);

        assertNotNull(result);
        assertEquals("base64", result.getType());
        assertNotNull(result.getData());

        // Verify it's valid base64
        byte[] decoded = Base64.getDecoder().decode(result.getData());
        assertEquals("fake image content", new String(decoded));
    }

    @Test
    @Disabled(
            "Requires network access and mocked MediaUtils. The new implementation always downloads"
                    + " remote URLs.")
    void testConvertImageBlockWithURLSourceRemote() {
        // This test is disabled because it tries to download from example.com
    }

    @Test
    void testConvertImageBlockWithInvalidExtension() {
        URLSource source = URLSource.builder().url("file.invalid").build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        assertThrows(Exception.class, () -> converter.convertImageBlock(block));
    }

    @Test
    void testConvertImageBlockWithNonExistentFile() {
        URLSource source = URLSource.builder().url("/nonexistent/file.png").build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        assertThrows(Exception.class, () -> converter.convertImageBlock(block));
    }

    @Test
    void testConvertImageBlockWithUnsupportedSourceType() {
        ImageBlock block = ImageBlock.builder().source(new CustomSource()).build();

        assertThrows(IllegalArgumentException.class, () -> converter.convertImageBlock(block));
    }

    @Test
    void testBase64EncodingDecoding() throws Exception {
        String originalText = "fake image content";
        String base64Encoded = Base64.getEncoder().encodeToString(originalText.getBytes());
        assertEquals("ZmFrZSBpbWFnZSBjb250ZW50", base64Encoded);

        Base64Source source =
                Base64Source.builder().data(base64Encoded).mediaType("image/png").build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        AnthropicContent.ImageSource result = converter.convertImageBlock(block);

        assertEquals("base64", result.getType());
        byte[] decoded = Base64.getDecoder().decode(result.getData());
        assertEquals(originalText, new String(decoded));
    }

    @Test
    void testConvertImageBlockWithJpegMediaType() throws Exception {
        Base64Source source =
                Base64Source.builder()
                        .data("ZmFrZSBpbWFnZSBjb250ZW50")
                        .mediaType("image/jpeg")
                        .build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        AnthropicContent.ImageSource result = converter.convertImageBlock(block);

        assertEquals("image/jpeg", result.getMediaType());
    }

    @Test
    void testConvertImageBlockWithWebpMediaType() throws Exception {
        Base64Source source =
                Base64Source.builder()
                        .data("ZmFrZSBpbWFnZSBjb250ZW50")
                        .mediaType("image/webp")
                        .build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        AnthropicContent.ImageSource result = converter.convertImageBlock(block);

        assertEquals("image/webp", result.getMediaType());
    }

    @Test
    void testConvertImageBlockWithGifMediaType() throws Exception {
        Base64Source source =
                Base64Source.builder()
                        .data("ZmFrZSBpbWFnZSBjb250ZW50")
                        .mediaType("image/gif")
                        .build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        AnthropicContent.ImageSource result = converter.convertImageBlock(block);

        assertEquals("image/gif", result.getMediaType());
    }

    // Custom source type for testing unsupported sources
    private static class CustomSource extends io.agentscope.core.message.Source {}
}
