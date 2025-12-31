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
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.URLSource;
import org.junit.jupiter.api.Test;

/**
 * Tests for OpenAIConverterUtils.
 */
class OpenAIConverterUtilsTest {

    @Test
    void testConvertImageSourceToUrlWithURLSource() {
        URLSource source = new URLSource("https://example.com/image.png");
        String result = OpenAIConverterUtils.convertImageSourceToUrl(source);
        assertEquals("https://example.com/image.png", result);
    }

    @Test
    void testConvertImageSourceToUrlWithBase64Source() {
        Base64Source source = new Base64Source("image/jpeg", "data123");
        String result = OpenAIConverterUtils.convertImageSourceToUrl(source);
        assertEquals("data:image/jpeg;base64,data123", result);
    }

    @Test
    void testConvertImageSourceToUrlWithBase64SourceDefaultMediaType() {
        // When media type is not provided in the data, it defaults to image/png
        Base64Source source = new Base64Source("image/png", "data456");
        String result = OpenAIConverterUtils.convertImageSourceToUrl(source);
        assertEquals("data:image/png;base64,data456", result);
    }

    @Test
    void testConvertImageSourceToUrlWithNullSource() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OpenAIConverterUtils.convertImageSourceToUrl(null));
    }

    @Test
    void testConvertImageSourceToUrlWithEmptyURLSource() {
        URLSource source = new URLSource("");
        assertThrows(
                IllegalArgumentException.class,
                () -> OpenAIConverterUtils.convertImageSourceToUrl(source));
    }

    @Test
    void testConvertImageSourceToUrlWithEmptyBase64Source() {
        Base64Source source = new Base64Source("image/png", "");
        assertThrows(
                IllegalArgumentException.class,
                () -> OpenAIConverterUtils.convertImageSourceToUrl(source));
    }

    @Test
    void testDetectAudioFormatWav() {
        String result = OpenAIConverterUtils.detectAudioFormat("audio/wav");
        assertEquals("wav", result);
    }

    @Test
    void testDetectAudioFormatMp3() {
        String result = OpenAIConverterUtils.detectAudioFormat("audio/mp3");
        assertEquals("mp3", result);
    }

    @Test
    void testDetectAudioFormatOpus() {
        String result = OpenAIConverterUtils.detectAudioFormat("audio/opus");
        assertEquals("opus", result);
    }

    @Test
    void testDetectAudioFormatFlac() {
        String result = OpenAIConverterUtils.detectAudioFormat("audio/flac");
        assertEquals("flac", result);
    }

    @Test
    void testDetectAudioFormatUnknown() {
        String result = OpenAIConverterUtils.detectAudioFormat("audio/unknown");
        assertEquals("mp3", result);
    }

    @Test
    void testDetectAudioFormatNull() {
        String result = OpenAIConverterUtils.detectAudioFormat(null);
        assertEquals("mp3", result);
    }

    @Test
    void testDetectAudioFormatWithoutPrefix() {
        String result = OpenAIConverterUtils.detectAudioFormat("mp3");
        assertEquals("mp3", result);
    }
}
