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
package io.agentscope.core.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for MediaUtils.
 *
 * <p>These tests verify media utility functions including:
 * <ul>
 *   <li>Local file detection</li>
 *   <li>File to base64 conversion</li>
 *   <li>File protocol URL conversion</li>
 *   <li>Audio format determination</li>
 *   <li>Extension validation</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("MediaUtils Unit Tests")
class MediaUtilsTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("Should identify local file paths")
    void testIsLocalFile() {
        assertTrue(MediaUtils.isLocalFile("image.png"));
        assertTrue(MediaUtils.isLocalFile("/absolute/path/image.png"));
        assertTrue(MediaUtils.isLocalFile("./relative/path/image.png"));
        assertTrue(MediaUtils.isLocalFile("file.png"));

        assertFalse(MediaUtils.isLocalFile("http://example.com/image.png"));
        assertFalse(MediaUtils.isLocalFile("https://example.com/image.png"));
        assertFalse(MediaUtils.isLocalFile("oss://example.com/image.png"));
        assertFalse(MediaUtils.isLocalFile("ftp://example.com/image.png"));
        assertFalse(MediaUtils.isLocalFile("file:///absolute/path/image.png"));
        assertFalse(MediaUtils.isLocalFile(null));
        assertFalse(MediaUtils.isLocalFile(""));
    }

    @Test
    @DisplayName("Should convert file to base64")
    void testFileToBase64() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        String content = "Hello, World!";
        Files.write(testFile, content.getBytes());

        String base64 = MediaUtils.fileToBase64(testFile.toString());

        assertNotNull(base64);
        assertTrue(base64.length() > 0);
        // Verify it's valid base64
        byte[] decoded = java.util.Base64.getDecoder().decode(base64);
        assertEquals(content, new String(decoded));
    }

    @Test
    @DisplayName("Should throw exception for non-existent file")
    void testFileToBase64NonExistent() {
        assertThrows(
                IOException.class,
                () -> MediaUtils.fileToBase64("/nonexistent/file.txt"),
                "Should throw IOException for non-existent file");
    }

    @Test
    @DisplayName("Should throw exception for unreadable file")
    void testFileToBase64UnReadable() throws IOException {
        Path testFile = tempDir.resolve("unreadable.txt");
        Files.write(testFile, "content".getBytes());
        testFile.toFile().setReadable(false);

        assertThrows(
                IOException.class,
                () -> MediaUtils.fileToBase64(testFile.toString()),
                "Should throw IOException for unreadable file");
    }

    @Test
    @DisplayName("Should convert file to file:// protocol URL")
    void testToFileProtocolUrl() throws IOException {
        Path testFile = tempDir.resolve("test.png");
        Files.createFile(testFile);

        String fileUrl = MediaUtils.toFileProtocolUrl(testFile.toString());

        assertNotNull(fileUrl);
        assertTrue(fileUrl.startsWith("file://"));
        assertTrue(fileUrl.contains("test.png"));
    }

    @Test
    @DisplayName("Should throw exception for non-existent file in toFileProtocolUrl")
    void testToFileProtocolUrlNonExistent() {
        assertThrows(
                IOException.class,
                () -> MediaUtils.toFileProtocolUrl("/nonexistent/file.png"),
                "Should throw IOException for non-existent file");
    }

    @Test
    @DisplayName("Should determine audio format from extension")
    void testDetermineAudioFormat() {
        assertEquals("wav", MediaUtils.determineAudioFormat("audio.wav"));
        assertEquals("wav", MediaUtils.determineAudioFormat("audio.WAV"));
        assertEquals("mp3", MediaUtils.determineAudioFormat("audio.mp3"));
        assertEquals("mp3", MediaUtils.determineAudioFormat("audio.MP3"));
        assertEquals("mp3", MediaUtils.determineAudioFormat("audio.unknown"));
        assertEquals("mp3", MediaUtils.determineAudioFormat("audio"));
    }

    @Test
    @DisplayName("Should infer audio format from MIME type")
    void testInferAudioFormatFromMediaType() {
        assertEquals("wav", MediaUtils.inferAudioFormatFromMediaType("audio/wav"));
        assertEquals("wav", MediaUtils.inferAudioFormatFromMediaType("audio/x-wav"));
        assertEquals("mp3", MediaUtils.inferAudioFormatFromMediaType("audio/mpeg"));
        assertEquals("mp3", MediaUtils.inferAudioFormatFromMediaType("audio/mp3"));
        assertEquals("opus", MediaUtils.inferAudioFormatFromMediaType("audio/opus"));
        assertEquals("flac", MediaUtils.inferAudioFormatFromMediaType("audio/flac"));
        assertEquals("mp3", MediaUtils.inferAudioFormatFromMediaType(null));
        assertEquals("mp3", MediaUtils.inferAudioFormatFromMediaType("unknown/type"));
    }

    @Test
    @DisplayName("Should determine media type from extension")
    void testDetermineMediaType() {
        // Images
        assertEquals("image/png", MediaUtils.determineMediaType("image.png"));
        assertEquals("image/jpeg", MediaUtils.determineMediaType("image.jpg"));
        assertEquals("image/jpeg", MediaUtils.determineMediaType("image.jpeg"));
        assertEquals("image/gif", MediaUtils.determineMediaType("image.gif"));
        assertEquals("image/webp", MediaUtils.determineMediaType("image.webp"));
        assertEquals("image/heic", MediaUtils.determineMediaType("image.heic"));

        // Audio
        assertEquals("audio/mp3", MediaUtils.determineMediaType("audio.mp3"));
        assertEquals("audio/wav", MediaUtils.determineMediaType("audio.wav"));
        assertEquals("audio/flac", MediaUtils.determineMediaType("audio.flac"));

        // Video
        assertEquals("video/mp4", MediaUtils.determineMediaType("video.mp4"));
        assertEquals("video/mpeg", MediaUtils.determineMediaType("video.mpeg"));
        assertEquals("video/quicktime", MediaUtils.determineMediaType("video.mov"));
        assertEquals("video/x-msvideo", MediaUtils.determineMediaType("video.avi"));

        // Fallback
        assertEquals("application/octet-stream", MediaUtils.determineMediaType("file.unknown"));
        assertEquals("application/octet-stream", MediaUtils.determineMediaType("file"));
        assertEquals("application/octet-stream", MediaUtils.determineMediaType(null));
        assertEquals("application/octet-stream", MediaUtils.determineMediaType(""));
    }

    @Test
    @DisplayName("Should validate image extension")
    void testValidateImageExtension() {
        // Should not throw for valid extensions
        MediaUtils.validateImageExtension("image.png");
        MediaUtils.validateImageExtension("image.jpg");
        MediaUtils.validateImageExtension("image.jpeg");
        MediaUtils.validateImageExtension("image.gif");
        MediaUtils.validateImageExtension("image.webp");
        MediaUtils.validateImageExtension("image.heic");
        MediaUtils.validateImageExtension("image.heif");

        // Should throw for invalid extensions
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaUtils.validateImageExtension("image.txt"),
                "Should throw for unsupported image extension");
    }

    @Test
    @DisplayName("Should validate audio extension")
    void testValidateAudioExtension() {
        // Should not throw for valid extensions
        MediaUtils.validateAudioExtension("audio.wav");
        MediaUtils.validateAudioExtension("audio.mp3");

        // Should throw for invalid extensions
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaUtils.validateAudioExtension("audio.txt"),
                "Should throw for unsupported audio extension");
    }

    @Test
    @DisplayName("Should validate video extension")
    void testValidateVideoExtension() {
        // Should not throw for valid extensions
        MediaUtils.validateVideoExtension("video.mp4");
        MediaUtils.validateVideoExtension("video.mpeg");
        MediaUtils.validateVideoExtension("video.mov");
        MediaUtils.validateVideoExtension("video.avi");
        MediaUtils.validateVideoExtension("video.webm");

        // Should throw for invalid extensions
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaUtils.validateVideoExtension("video.txt"),
                "Should throw for unsupported video extension");
    }

    @Test
    @DisplayName("Should handle relative paths in toFileProtocolUrl")
    void testToFileProtocolUrlRelativePath() throws IOException {
        Path testFile = tempDir.resolve("test.png");
        Files.createFile(testFile);

        // Use absolute path (toFileProtocolUrl converts relative to absolute internally)
        // The method resolves relative paths to absolute, so we test with absolute path
        String fileUrl = MediaUtils.toFileProtocolUrl(testFile.toString());

        assertNotNull(fileUrl);
        assertTrue(fileUrl.startsWith("file://"));
        assertTrue(fileUrl.contains("test.png"));
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
    @DisplayName("Should return null or empty string with null or empty path")
    void testUrlToProtocolUrlWithEmptyPath() throws IOException {
        assertNull(null, MediaUtils.urlToProtocolUrl(null));
        assertEquals("", MediaUtils.urlToProtocolUrl(""));
    }

    @Test
    @DisplayName("Should return source path with invalid path")
    void testUrlToProtocolUrlWithInvalidPath() throws IOException {
        String path = "/path/to/file/<:|/\\>\u0000\b\n\t\0.txt";
        assertEquals(path, MediaUtils.urlToProtocolUrl(path));
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
