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

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
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
    private static final int MAX_REDIRECTS = 5;

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
     * Returns true for paths without http://, https://, ftp://, file: or oss:// prefixes.
     * Used to distinguish local files from remote URLs for different processing paths.
     *
     * @param url The URL or file path to check
     * @return true if it's a local file path, false if it has a protocol scheme
     */
    public static boolean isLocalFile(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return !lower.startsWith("http://")
                && !lower.startsWith("https://")
                && !lower.startsWith("ftp://")
                && !lower.startsWith("file:")
                && !lower.startsWith("oss://");
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
    public static String fileToBase64(String path) throws IOException {
        return Base64.getEncoder().encodeToString(readFileBytes(path));
    }

    private static byte[] readFileBytes(String path) throws IOException {
        Path filePath = Path.of(path);
        if (!Files.isRegularFile(filePath)) {
            throw new IOException("File does not exist or is not a regular file: " + path);
        }
        if (!Files.isReadable(filePath)) {
            throw new IOException("File is not readable: " + path);
        }
        checkFileSize(path);
        try (InputStream input = Files.newInputStream(filePath)) {
            // Keep the read bounded even if the file grows after the size check.
            return readLimitedBytes(input);
        }
    }

    /**
     * Download a remote HTTP(S) URL and convert to base64.
     * Used for APIs that require base64 encoding instead of direct URLs (e.g., OpenAI audio).
     * Bounds the read to 50MB and follows at most five same-protocol redirects with connection
     * timeouts. This method has no destination-policy callback: checking only the initial URL
     * does not authorize redirects. Callers must enforce destination restrictions for every
     * connection through their application's network policy, or fetch approved bytes themselves.
     *
     * @param url The remote URL to download
     * @return Base64-encoded string of downloaded content
     * @throws IOException If the scheme is unsupported, download fails, exceeds the size or
     *     redirect limit, contains an invalid redirect, or returns non-200 status
     */
    public static String downloadUrlToBase64(String url) throws IOException {
        return Base64.getEncoder().encodeToString(downloadUrlAsBytes(url));
    }

    private static byte[] downloadUrlAsBytes(String url) throws IOException {
        URL remoteUrl = new URL(url);
        if (!"http".equalsIgnoreCase(remoteUrl.getProtocol())
                && !"https".equalsIgnoreCase(remoteUrl.getProtocol())) {
            throw new IOException("Only HTTP(S) media downloads are supported");
        }
        log.debug("Downloading remote URL for base64 encoding: {}", url);

        boolean followRedirects = HttpURLConnection.getFollowRedirects();
        for (int redirects = 0; ; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) remoteUrl.openConnection();
            try {
                // Handle redirects here so the hop limit is independent of JVM-wide settings.
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000); // 10 seconds
                connection.setReadTimeout(30000); // 30 seconds
                connection.connect();

                int responseCode = connection.getResponseCode();
                boolean redirect =
                        switch (responseCode) {
                            case 301, 302, 303, 307, 308 -> true;
                            default -> false;
                        };
                if (redirect && followRedirects) {
                    if (redirects >= MAX_REDIRECTS) {
                        throw new IOException(
                                "Too many media redirects (max: " + MAX_REDIRECTS + ")");
                    }
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("Media redirect is missing a Location header");
                    }
                    URL target = new URL(remoteUrl, location);
                    // Preserve HttpURLConnection's same-protocol rule, including no TLS downgrade.
                    if (!remoteUrl.getProtocol().equalsIgnoreCase(target.getProtocol())) {
                        throw new IOException("Media redirects must keep the same HTTP(S) scheme");
                    }
                    remoteUrl = target;
                    continue;
                }
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException(
                            "Failed to download URL: HTTP " + responseCode + " for " + remoteUrl);
                }

                if (connection.getContentLengthLong() > MAX_SIZE_BYTES) {
                    throw new IOException(
                            "Downloaded content too large (max: " + MAX_SIZE_BYTES + ")");
                }
                try (InputStream is = connection.getInputStream()) {
                    // Content-Length may be absent or inaccurate; enforce the limit while reading.
                    byte[] bytes = readLimitedBytes(is);
                    if (bytes.length > WARN_SIZE_BYTES) {
                        log.warn(
                                "Large download detected: {} bytes from {}",
                                bytes.length,
                                remoteUrl);
                    }
                    return bytes;
                }
            } finally {
                connection.disconnect();
            }
        }
    }

    /**
     * Reads a local path, file URI, or HTTP(S) URL with a 50MB limit.
     *
     * <p>This method reads bytes without validating their media format. Callers are responsible
     * for authorizing local paths and remote destinations before passing untrusted input.
     * HTTP(S) reads follow the redirect and network-policy contract of {@link #downloadUrlToBase64}.
     *
     * @param url local path, file URI, or remote HTTP(S) URL
     * @return resource bytes
     * @throws IOException if the resource cannot be read, the URI is invalid, the download scheme
     *     is unsupported, or the size limit is exceeded
     */
    public static byte[] readUrlAsBytes(String url) throws IOException {
        if (url == null || url.isBlank()) {
            throw new IOException("Media location must not be blank");
        }
        if (url.regionMatches(true, 0, "file:", 0, 5)) {
            try {
                return readFileBytes(Path.of(URI.create(url)).toString());
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid file URI", e);
            }
        }
        return isLocalFile(url) ? readFileBytes(url) : downloadUrlAsBytes(url);
    }

    static byte[] readLimitedBytes(InputStream input) throws IOException {
        // InputStream.readNBytes grows in chunks; the limit is not an initial buffer allocation.
        // One extra byte distinguishes an exact-limit resource from an oversized one.
        byte[] bytes = input.readNBytes((int) MAX_SIZE_BYTES + 1);
        if (bytes.length > MAX_SIZE_BYTES) {
            throw new IOException("Media content too large (max: " + MAX_SIZE_BYTES + ")");
        }
        return bytes;
    }

    /**
     * Convert a URL to a file:// protocol URL or leave as-is for web URLs.
     *
     * @param url URL or file path
     * @return file:// protocol URL or original URL (e.g., file:///absolute/path/image.png)
     * @throws IOException If the file does not exist
     */
    public static String urlToProtocolUrl(String url) throws IOException {
        if (isFileExists(url)) {
            return toFileProtocolUrl(url);
        }

        return url;
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
     * Convert a file or web URL to an InputStream.
     *
     * @param url The file or web URL
     * @return An InputStream for the resource
     * @throws IOException If the resource read failed
     */
    public static InputStream urlToInputStream(String url) throws IOException {
        if (isFileExists(url)) {
            // Treat as local file
            return Files.newInputStream(Path.of(url));
        } else {
            // Treat as web URL
            return URI.create(url).toURL().openStream();
        }
    }

    /**
     * Convert a file or web URL to an InputStream with RGBA image.
     * @param url a local file path or web URL
     * @return an InputStream with RGBA image
     * @throws IOException if the resource convert failed
     * @throws IllegalArgumentException if the resource read failed
     */
    public static InputStream urlToRgbaImageInputStream(String url) throws IOException {
        BufferedImage img = ImageIO.read(urlToInputStream(url));
        if (img == null) {
            throw new IllegalArgumentException("Unable to read image from: " + url);
        }

        // Ensure image has alpha channel (RGBA)
        BufferedImage rgbaImg =
                new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = rgbaImg.createGraphics();
        try {
            graphics.drawImage(img, 0, 0, null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(rgbaImg, "png", bos);
            return new ByteArrayInputStream(bos.toByteArray());
        } finally {
            graphics.dispose();
        }
    }

    /**
     * Convert a local path, file URI, or HTTP(S) URL to a data URL with base64 encoding.
     * Applies the same 50MB read limit as {@link #readUrlAsBytes(String)}.
     * @param url a local file path, file URI, or HTTP(S) URL
     * @return a data URL with base64 encoding, the format is data:{mediaType};base64,{base64Data}
     * @throws IOException if the resource cannot be read or exceeds the size limit
     */
    public static String urlToBase64DataUrl(String url) throws IOException {
        String base64 = Base64.getEncoder().encodeToString(readUrlAsBytes(url));

        String mediaType = determineMediaType(url);
        return String.format("data:%s;base64,%s", mediaType, base64);
    }

    /**
     * Determine MIME type from file extension.
     */
    public static String determineMediaType(String path) {
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
     * Resolve the MIME type from a {@link Source}.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@link Base64Source#getMediaType()} — always explicit</li>
     *   <li>{@link URLSource#getMimeType()} — caller-supplied hint for extension-less URLs</li>
     *   <li>{@link #determineMediaType(String)} — extension-based inference</li>
     * </ol>
     *
     * @param source the source to resolve MIME type from
     * @return MIME type string (e.g. "image/jpeg")
     * @throws IllegalArgumentException if the type cannot be determined or source type is unknown
     */
    public static String resolveMimeType(Source source) {
        if (source instanceof Base64Source b64) {
            return b64.getMediaType();
        }
        if (source instanceof URLSource urlSource) {
            String hint = urlSource.getMimeType();
            if (hint != null && !hint.isBlank()) {
                return hint;
            }
            String inferred = determineMediaType(urlSource.getUrl());
            if (!"application/octet-stream".equals(inferred)) {
                return inferred;
            }
            throw new IllegalArgumentException(
                    "Cannot determine MIME type for URL '"
                            + urlSource.getUrl()
                            + "'; set URLSource.mimeType explicitly");
        }
        throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
    }

    /**
     * Validate that an image file has a supported extension.
     * This is a format-routing check only; it does not inspect content or authorize file access.
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
    public static void validateAudioExtension(String url) {
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
    public static void validateVideoExtension(String url) {
        String ext = getExtension(url).toLowerCase();
        if (!SUPPORTED_VIDEO_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException(
                    String.format(
                            "Unsupported video file extension: %s, %s are supported",
                            ext, SUPPORTED_VIDEO_EXTENSIONS));
        }
    }

    /**
     * Determine audio format string from file extension.
     *
     * @param path The file path
     * @return Audio format string ("wav" or "mp3")
     */
    public static String determineAudioFormat(String path) {
        String ext = getExtension(path).toLowerCase();
        return ext.equals("wav") ? "wav" : "mp3";
    }

    /**
     * Infer audio format string from MIME type.
     *
     * @param mediaType The MIME type
     * @return Audio format string ("wav", "mp3", "opus", "flac", etc.)
     */
    public static String inferAudioFormatFromMediaType(String mediaType) {
        if (mediaType == null) {
            return "mp3"; // default
        }
        if (mediaType.contains("wav")) {
            return "wav";
        } else if (mediaType.contains("opus")) {
            return "opus";
        } else if (mediaType.contains("flac")) {
            return "flac";
        }
        return "mp3"; // default
    }

    /**
     * Extract file extension from path or URL.
     */
    public static String getExtension(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }

        String fileName;
        try {
            if (isLocalFile(path)) {
                // treat as file
                Path fileNamePath = Paths.get(path).normalize().getFileName();
                fileName = fileNamePath == null ? "" : fileNamePath.toString();
            } else {
                // URI paths use '/' regardless of the host filesystem (e.g. /C:/ on Windows).
                URI uri = URI.create(path).normalize();
                String uriPath = uri.getPath();
                fileName = uriPath.substring(uriPath.lastIndexOf('/') + 1);
            }
        } catch (Exception e) {
            log.warn("Invalid path: {}", path, e);
            return "";
        }

        int dotIndex = fileName.lastIndexOf('.');
        // Ensure the dot exists and is not the last character
        if (dotIndex != -1 && dotIndex < fileName.length() - 1) {
            return fileName.substring(dotIndex + 1);
        }
        return "";
    }

    /**
     * Check if a file exists.
     * @param path a local file path or URL.
     * @return true: exists, false: not exists or path is invalid
     */
    public static boolean isFileExists(String path) {
        if (!isLocalFile(path)) {
            return false;
        }
        try {
            return Files.exists(Path.of(path));
        } catch (InvalidPathException e) {
            return false;
        }
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
