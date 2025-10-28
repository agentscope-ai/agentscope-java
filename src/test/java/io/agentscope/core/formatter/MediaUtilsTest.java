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
package io.agentscope.core.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.models.chat.completions.ChatCompletionContentPartInputAudio;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaUtilsTest {

    @TempDir Path tempDir;

    @Test
    void testIsLocalFile() {
        assertTrue(MediaUtils.isLocalFile("/path/to/file.png"));
        assertTrue(MediaUtils.isLocalFile("./relative/path.jpg"));
        assertTrue(MediaUtils.isLocalFile("file.wav"));

        assertFalse(MediaUtils.isLocalFile("http://example.com/image.png"));
        assertFalse(MediaUtils.isLocalFile("https://example.com/image.png"));
        assertFalse(MediaUtils.isLocalFile(null));
    }

    @Test
    void testDetermineMediaType() {
        // Images
        assertEquals("image/jpeg", MediaUtils.determineMediaType("photo.jpg"));
        assertEquals("image/jpeg", MediaUtils.determineMediaType("photo.jpeg"));
        assertEquals("image/png", MediaUtils.determineMediaType("image.png"));
        assertEquals("image/gif", MediaUtils.determineMediaType("animated.gif"));
        assertEquals("image/webp", MediaUtils.determineMediaType("modern.webp"));
        assertEquals("image/heic", MediaUtils.determineMediaType("apple.heic"));
        assertEquals("image/heif", MediaUtils.determineMediaType("apple.heif"));

        // Audio
        assertEquals("audio/mp3", MediaUtils.determineMediaType("song.mp3"));
        assertEquals("audio/wav", MediaUtils.determineMediaType("voice.wav"));

        // Case insensitive
        assertEquals("image/png", MediaUtils.determineMediaType("IMAGE.PNG"));
        assertEquals("audio/mp3", MediaUtils.determineMediaType("SONG.MP3"));

        // Unknown
        assertEquals("application/octet-stream", MediaUtils.determineMediaType("file.unknown"));
    }

    @Test
    void testValidateImageExtension() {
        // Valid extensions
        MediaUtils.validateImageExtension("photo.jpg");
        MediaUtils.validateImageExtension("photo.jpeg");
        MediaUtils.validateImageExtension("image.png");
        MediaUtils.validateImageExtension("image.gif");
        MediaUtils.validateImageExtension("image.webp");
        MediaUtils.validateImageExtension("image.heic");
        MediaUtils.validateImageExtension("image.heif");

        // Invalid extensions
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaUtils.validateImageExtension("file.txt"));
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaUtils.validateImageExtension("file.mp3"));
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaUtils.validateImageExtension("file.unknown"));
    }

    @Test
    void testValidateAudioExtension() {
        // Valid extensions
        MediaUtils.validateAudioExtension("audio.wav");
        MediaUtils.validateAudioExtension("audio.mp3");

        // Invalid extensions
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaUtils.validateAudioExtension("file.txt"));
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaUtils.validateAudioExtension("file.jpg"));
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaUtils.validateAudioExtension("file.unknown"));
    }

    @Test
    void testDetermineAudioFormat() {
        assertEquals(
                ChatCompletionContentPartInputAudio.InputAudio.Format.WAV,
                MediaUtils.determineAudioFormat("audio.wav"));
        assertEquals(
                ChatCompletionContentPartInputAudio.InputAudio.Format.MP3,
                MediaUtils.determineAudioFormat("audio.mp3"));
        assertEquals(
                ChatCompletionContentPartInputAudio.InputAudio.Format.WAV,
                MediaUtils.determineAudioFormat("AUDIO.WAV"));
    }

    @Test
    void testInferAudioFormatFromMediaType() {
        assertEquals(
                ChatCompletionContentPartInputAudio.InputAudio.Format.WAV,
                MediaUtils.inferAudioFormatFromMediaType("audio/wav"));
        assertEquals(
                ChatCompletionContentPartInputAudio.InputAudio.Format.MP3,
                MediaUtils.inferAudioFormatFromMediaType("audio/mp3"));
        assertEquals(
                ChatCompletionContentPartInputAudio.InputAudio.Format.MP3,
                MediaUtils.inferAudioFormatFromMediaType("audio/mpeg"));
        assertEquals(
                ChatCompletionContentPartInputAudio.InputAudio.Format.MP3,
                MediaUtils.inferAudioFormatFromMediaType(null));
    }

    @Test
    void testFileToBase64() throws IOException {
        // Create a test file
        Path testFile = tempDir.resolve("test.txt");
        String content = "Hello, World!";
        Files.writeString(testFile, content);

        String base64 = MediaUtils.fileToBase64(testFile.toString());
        assertNotNull(base64);
        assertTrue(base64.length() > 0);

        // Verify it's valid base64
        String decoded = new String(java.util.Base64.getDecoder().decode(base64));
        assertEquals(content, decoded);
    }

    @Test
    void testFileToBase64_FileNotFound() {
        assertThrows(IOException.class, () -> MediaUtils.fileToBase64("/nonexistent/file.txt"));
    }

    @Test
    void testFileToBase64_FileTooLarge() throws IOException {
        // Create a file larger than MAX_SIZE_BYTES (50MB)
        Path largeFile = tempDir.resolve("large.bin");
        byte[] data = new byte[51 * 1024 * 1024]; // 51MB
        Files.write(largeFile, data);

        assertThrows(IOException.class, () -> MediaUtils.fileToBase64(largeFile.toString()));
    }

    @Test
    void testUrlToBase64DataUrl() throws IOException {
        // Create a test image file
        Path testFile = tempDir.resolve("test.png");
        byte[] pngData = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}; // PNG header
        Files.write(testFile, pngData);

        String dataUrl = MediaUtils.urlToBase64DataUrl(testFile.toString());
        assertNotNull(dataUrl);
        assertTrue(dataUrl.startsWith("data:image/png;base64,"));
    }

    @Test
    void testDownloadUrlToBase64_InvalidUrl() {
        assertThrows(
                IOException.class,
                () -> MediaUtils.downloadUrlToBase64("http://invalid.example.com/nonexistent"));
    }
}
