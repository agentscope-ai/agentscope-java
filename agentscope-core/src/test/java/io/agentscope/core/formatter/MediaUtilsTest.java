/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.formatter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.models.chat.completions.ChatCompletionContentPartInputAudio;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
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
        assertFalse(MediaUtils.isLocalFile("oss://example.com/image.png"));
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

    // ========== Additional Tests for Better Coverage ==========

    @Test
    void testDetermineMediaType_AllImageFormats() {
        assertEquals("image/png", MediaUtils.determineMediaType("file.png"));
        assertEquals("image/jpeg", MediaUtils.determineMediaType("file.jpg"));
        assertEquals("image/jpeg", MediaUtils.determineMediaType("file.jpeg"));
        assertEquals("image/gif", MediaUtils.determineMediaType("file.gif"));
        assertEquals("image/webp", MediaUtils.determineMediaType("file.webp"));
        assertEquals("image/heic", MediaUtils.determineMediaType("file.heic"));
        assertEquals("image/heif", MediaUtils.determineMediaType("file.heif"));
    }

    @Test
    void testDetermineMediaType_AllAudioFormats() {
        assertEquals("audio/wav", MediaUtils.determineMediaType("file.wav"));
        assertEquals("audio/mp3", MediaUtils.determineMediaType("file.mp3"));
    }

    @Test
    void testDetermineMediaType_AllVideoFormats() {
        assertEquals("video/mp4", MediaUtils.determineMediaType("file.mp4"));
        assertEquals("video/mpeg", MediaUtils.determineMediaType("file.mpeg"));
        assertEquals("video/mpeg", MediaUtils.determineMediaType("file.mpg"));
        assertEquals("video/quicktime", MediaUtils.determineMediaType("file.mov"));
        assertEquals("video/x-msvideo", MediaUtils.determineMediaType("file.avi"));
        assertEquals("video/webm", MediaUtils.determineMediaType("file.webm"));
        assertEquals("video/x-ms-wmv", MediaUtils.determineMediaType("file.wmv"));
        assertEquals("video/x-flv", MediaUtils.determineMediaType("file.flv"));
        assertEquals("video/3gpp", MediaUtils.determineMediaType("file.3gp"));
        assertEquals("video/3gpp", MediaUtils.determineMediaType("file.3gpp"));
    }

    @Test
    void testDetermineMediaType_CaseInsensitive() {
        assertEquals("image/png", MediaUtils.determineMediaType("FILE.PNG"));
        assertEquals("image/jpeg", MediaUtils.determineMediaType("File.JPG"));
        assertEquals("audio/wav", MediaUtils.determineMediaType("Audio.WAV"));
    }

    @Test
    void testDetermineMediaType_UnsupportedExtension() {
        assertEquals("application/octet-stream", MediaUtils.determineMediaType("file.xyz"));
        assertEquals("application/octet-stream", MediaUtils.determineMediaType("file"));
        assertEquals("application/octet-stream", MediaUtils.determineMediaType("file."));
    }

    @Test
    void testDetermineMediaType_Empty() {
        assertEquals("application/octet-stream", MediaUtils.determineMediaType(""));
    }

    @Test
    void testDetermineMediaType_Null() {
        assertEquals("application/octet-stream", MediaUtils.determineMediaType(null));
    }

    @Test
    void testValidateImageExtension_AllSupported() {
        // These should not throw exceptions
        assertDoesNotThrow(() -> MediaUtils.validateImageExtension("image.png"));
        assertDoesNotThrow(() -> MediaUtils.validateImageExtension("image.jpg"));
        assertDoesNotThrow(() -> MediaUtils.validateImageExtension("image.jpeg"));
        assertDoesNotThrow(() -> MediaUtils.validateImageExtension("image.gif"));
        assertDoesNotThrow(() -> MediaUtils.validateImageExtension("image.webp"));
        assertDoesNotThrow(() -> MediaUtils.validateImageExtension("image.heic"));
        assertDoesNotThrow(() -> MediaUtils.validateImageExtension("image.heif"));
    }

    @Test
    void testValidateImageExtension_CaseInsensitive() {
        // These should not throw exceptions
        assertDoesNotThrow(() -> MediaUtils.validateImageExtension("image.PNG"));
        assertDoesNotThrow(() -> MediaUtils.validateImageExtension("image.JPG"));
        assertDoesNotThrow(() -> MediaUtils.validateImageExtension("image.Heic"));
    }

    @Test
    void testValidateImageExtension_Unsupported() {
        // These should throw exceptions
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaUtils.validateImageExtension("image.svg"));
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaUtils.validateImageExtension("image.bmp"));
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaUtils.validateImageExtension("image.tiff"));
        assertThrows(
                IllegalArgumentException.class, () -> MediaUtils.validateImageExtension("image"));
    }

    @Test
    void testValidateImageExtension_NullInput() {
        // Should handle null gracefully or throw appropriate exception
        assertThrows(Exception.class, () -> MediaUtils.validateImageExtension(null));
    }

    @Test
    void testIsLocalFile_EdgeCases() {
        assertTrue(MediaUtils.isLocalFile("/"));
        assertTrue(MediaUtils.isLocalFile("./"));
        assertTrue(MediaUtils.isLocalFile("../file.png"));
        assertTrue(MediaUtils.isLocalFile("C:\\Windows\\file.jpg"));

        assertFalse(MediaUtils.isLocalFile(""));
        assertFalse(MediaUtils.isLocalFile("   "));
        assertFalse(MediaUtils.isLocalFile("ftp://example.com/file.png"));
        assertFalse(
                MediaUtils.isLocalFile("file://somefile.png")); // file:// is not considered local
    }

    // Note: base64ToDataUrl method doesn't exist in MediaUtils, tests removed

    @Test
    @DisplayName("Url to protocol url with file path")
    void testUrlToProtocolUrlWithFile() throws IOException {
        String url = "src/test/java/io/agentscope/core/formatter/MediaUtilsTest.java";
        String protocolUrl = MediaUtils.urlToProtocolUrl(url);

        assertTrue(protocolUrl.startsWith("file://"));
    }

    @Test
    @DisplayName("Url to protocol url with web url")
    void testUrlToProtocolUrlWithUrl() throws IOException {
        String url = "https://example.com/file.png";
        String protocolUrl = MediaUtils.urlToProtocolUrl(url);

        assertEquals(url, protocolUrl);
    }

    @Test
    @DisplayName("To file protocol url with file path")
    void testToFileProtocolUrlWithFile() throws IOException {
        String url = "src/test/java/io/agentscope/core/formatter/MediaUtilsTest.java";
        String protocolUrl = MediaUtils.toFileProtocolUrl(url);

        assertTrue(protocolUrl.startsWith("file://"));
    }

    @Test
    @DisplayName("Should throw IOException if file not found")
    void testToFileProtocolUrlWithNotFoundFile() {
        String url = "/path/no/file";

        assertThrows(IOException.class, () -> MediaUtils.toFileProtocolUrl(url));
    }

    @Test
    @DisplayName("Url to inputStream with file path")
    void testUrlToInputStreamWithFile() throws IOException {
        String url = "src/test/java/io/agentscope/core/formatter/MediaUtilsTest.java";

        assertNotNull(MediaUtils.urlToInputStream(url));
    }

    @Test
    @DisplayName("Url to RGBA image inputStream with file path")
    void testUrlToRgbaImageInputStreamWithFile() throws IOException {
        String url = "src/test/resources/dog.png";
        InputStream is = MediaUtils.urlToRgbaImageInputStream(url);
        assertNotNull(is);
        is.close();
    }
}
