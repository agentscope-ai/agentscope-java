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

import com.openai.models.chat.completions.ChatCompletionContentPartInputAudio;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for handling media files in multimodal content.
 * Provides methods for processing images, audio, and other media types.
 */
public class MediaUtils {

    private static final Logger log = LoggerFactory.getLogger(MediaUtils.class);

    // File size limits
    private static final long WARN_SIZE_BYTES = 10 * 1024 * 1024; // 10MB
    private static final long MAX_SIZE_BYTES = 50 * 1024 * 1024; // 50MB

    // Supported extensions
    private static final List<String> SUPPORTED_IMAGE_EXTENSIONS =
            List.of("png", "jpg", "jpeg", "gif", "webp", "heic", "heif");
    private static final List<String> SUPPORTED_AUDIO_EXTENSIONS = List.of("wav", "mp3");
    private static final List<String> SUPPORTED_VIDEO_EXTENSIONS =
            List.of("mp4", "mpeg", "mpg", "mov", "avi", "webm", "wmv", "flv", "3gp", "3gpp");

    private MediaUtils() {
        // Utility class, prevent instantiation
    }

    /**
     * Check if a URL is a local file path (not a URL with protocol scheme).
     * Returns true for paths without http://, https://, ftp://, or file:// prefixes.
     * Used to distinguish local files from remote URLs for different processing paths.
     *
     * @param url The URL or file path to check
     * @return true if it's a local file path, false if it has a protocol scheme
     */
    public static boolean isLocalFile(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return !url.startsWith("http://")
                && !url.startsWith("https://")
                && !url.startsWith("ftp://")
                && !url.startsWith("file://");
    }

    /**
     * Convert a local file to base64 encoded string.
     * Validates file size before reading (max 50MB).
     * Used when APIs require base64-encoded media content.
     *
     * @param path The local file path
     * @return Base64-encoded string of file contents
     * @throws IOException If file cannot be read or exceeds size limit
     */
    static String fileToBase64(String path) throws IOException {
        checkFileSize(path);
        byte[] bytes = Files.readAllBytes(Path.of(path));
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Download a remote URL and convert to base64.
     * Used for APIs that require base64 encoding instead of direct URLs (e.g., OpenAI audio).
     * Validates downloaded size (max 50MB) and sets connection timeouts.
     *
     * @param url The remote URL to download
     * @return Base64-encoded string of downloaded content
     * @throws IOException If download fails, exceeds size limit, or returns non-200 status
     */
    static String downloadUrlToBase64(String url) throws IOException {
        log.debug("Downloading remote URL for base64 encoding: {}", url);

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000); // 10 seconds
        connection.setReadTimeout(30000); // 30 seconds
        connection.connect();

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("Failed to download URL: HTTP " + responseCode + " for " + url);
        }

        try (InputStream is = connection.getInputStream()) {
            byte[] bytes = is.readAllBytes();

            // Check size after download
            if (bytes.length > MAX_SIZE_BYTES) {
                throw new IOException(
                        "Downloaded content too large: "
                                + bytes.length
                                + " bytes (max: "
                                + MAX_SIZE_BYTES
                                + ")");
            }
            if (bytes.length > WARN_SIZE_BYTES) {
                log.warn("Large download detected: {} bytes from {}", bytes.length, url);
            }

            return Base64.getEncoder().encodeToString(bytes);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Convert a local file path to file:// protocol URL.
     * Resolves relative paths to absolute and validates file existence.
     * Used for DashScope vision models which accept file:// protocol for local files.
     *
     * @param path Local file path (relative or absolute)
     * @return file:// protocol URL with absolute path (e.g., file:///absolute/path/image.png)
     * @throws IOException If the file does not exist
     */
    public static String toFileProtocolUrl(String path) throws IOException {
        Path absolutePath = Path.of(path).toAbsolutePath();
        if (!Files.exists(absolutePath)) {
            throw new IOException("File does not exist: " + path);
        }
        return "file://" + absolutePath;
    }

    /**
     * Convert a file to a data URL with base64 encoding.
     * Format: data:{mediaType};base64,{base64Data}
     */
    public static String urlToBase64DataUrl(String path) throws IOException {
        String base64 = fileToBase64(path);
        String mediaType = determineMediaType(path);
        return String.format("data:%s;base64,%s", mediaType, base64);
    }

    /**
     * Determine MIME type from file extension.
     */
    static String determineMediaType(String path) {
        String ext = getExtension(path).toLowerCase();
        return switch (ext) {
            // Image formats
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "heic" -> "image/heic";
            case "heif" -> "image/heif";

            // Audio formats
            case "mp3" -> "audio/mp3";
            case "wav" -> "audio/wav";
            case "aiff" -> "audio/aiff";
            case "aac" -> "audio/aac";
            case "ogg" -> "audio/ogg";
            case "flac" -> "audio/flac";

            // Video formats (for future use)
            case "mp4" -> "video/mp4";
            case "mpeg", "mpg" -> "video/mpeg";
            case "mov" -> "video/quicktime";
            case "avi" -> "video/x-msvideo";
            case "webm" -> "video/webm";
            case "wmv" -> "video/x-ms-wmv";
            case "flv" -> "video/x-flv";
            case "3gpp", "3gp" -> "video/3gpp";

            default -> "application/octet-stream";
        };
    }

    /**
     * Validate that an image file has a supported extension.
     */
    public static void validateImageExtension(String url) {
        String ext = getExtension(url).toLowerCase();
        if (!SUPPORTED_IMAGE_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException(
                    String.format(
                            "\"%s\" should end with one of %s", url, SUPPORTED_IMAGE_EXTENSIONS));
        }
    }

    /**
     * Validate that an audio file has a supported extension.
     */
    static void validateAudioExtension(String url) {
        String ext = getExtension(url).toLowerCase();
        if (!SUPPORTED_AUDIO_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException(
                    String.format(
                            "Unsupported audio file extension: %s, %s are supported",
                            ext, SUPPORTED_AUDIO_EXTENSIONS));
        }
    }

    /**
     * Validate that a video file has a supported extension.
     */
    static void validateVideoExtension(String url) {
        String ext = getExtension(url).toLowerCase();
        if (!SUPPORTED_VIDEO_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException(
                    String.format(
                            "Unsupported video file extension: %s, %s are supported",
                            ext, SUPPORTED_VIDEO_EXTENSIONS));
        }
    }

    /**
     * Determine OpenAI audio format from file extension.
     */
    static ChatCompletionContentPartInputAudio.InputAudio.Format determineAudioFormat(String path) {
        String ext = getExtension(path).toLowerCase();
        return ext.equals("wav")
                ? ChatCompletionContentPartInputAudio.InputAudio.Format.WAV
                : ChatCompletionContentPartInputAudio.InputAudio.Format.MP3;
    }

    /**
     * Infer OpenAI audio format from MIME type.
     */
    static ChatCompletionContentPartInputAudio.InputAudio.Format inferAudioFormatFromMediaType(
            String mediaType) {
        if (mediaType != null && mediaType.contains("wav")) {
            return ChatCompletionContentPartInputAudio.InputAudio.Format.WAV;
        }
        return ChatCompletionContentPartInputAudio.InputAudio.Format.MP3; // default
    }

    /**
     * Extract file extension from path or URL.
     */
    private static String getExtension(String path) {
        if (path == null) {
            return "";
        }
        int dotIndex = path.lastIndexOf('.');
        int slashIndex = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        // Ensure the dot is after the last slash (not part of directory name)
        if (dotIndex > slashIndex && dotIndex < path.length() - 1) {
            return path.substring(dotIndex + 1);
        }
        return "";
    }

    /**
     * Check file size before reading.
     */
    private static void checkFileSize(String path) throws IOException {
        long size = Files.size(Path.of(path));
        if (size > MAX_SIZE_BYTES) {
            throw new IOException(
                    "File too large: " + size + " bytes (max: " + MAX_SIZE_BYTES + ")");
        }
        if (size > WARN_SIZE_BYTES) {
            log.warn("Large file detected: {} bytes at {}", size, path);
        }
    }
}
