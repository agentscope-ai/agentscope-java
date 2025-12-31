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

import com.google.genai.types.Blob;
import com.google.genai.types.Part;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converter for Gemini API multimodal content.
 * Converts ImageBlock, AudioBlock, and VideoBlock to Gemini Part objects with inline data.
 */
public class GeminiMediaConverter {

    private static final Logger log = LoggerFactory.getLogger(GeminiMediaConverter.class);

    /**
     * Supported file extensions for each media type.
     * These extensions are validated when converting media blocks to ensure compatibility
     * with the Gemini API's supported formats.
     */
    private static final Map<String, List<String>> SUPPORTED_EXTENSIONS =
            Map.of(
                    "image", List.of("png", "jpeg", "jpg", "webp", "heic", "heif"),
                    "video",
                            List.of(
                                    "mp4", "mpeg", "mov", "avi", "x-flv", "flv", "mpg", "webm",
                                    "wmv", "3gpp"),
                    "audio", List.of("mp3", "wav", "aiff", "aac", "ogg", "flac"));

    /**
     * Convert ImageBlock to Gemini Part with inline data.
     *
     * @param block ImageBlock to convert
     * @return Part object containing inline data
     */
    public Part convertToInlineDataPart(ImageBlock block) {
        return convertMediaBlockToInlineDataPart(block.getSource(), "image");
    }

    /**
     * Convert AudioBlock to Gemini Part with inline data.
     *
     * @param block AudioBlock to convert
     * @return Part object containing inline data
     */
    public Part convertToInlineDataPart(AudioBlock block) {
        return convertMediaBlockToInlineDataPart(block.getSource(), "audio");
    }

    /**
     * Convert VideoBlock to Gemini Part with inline data.
     *
     * @param block VideoBlock to convert
     * @return Part object containing inline data
     */
    public Part convertToInlineDataPart(VideoBlock block) {
        return convertMediaBlockToInlineDataPart(block.getSource(), "video");
    }

    /**
     * Convert a media source to Gemini Part with inline data.
     *
     * @param source Source object (Base64Source or URLSource)
     * @param mediaType Media type string ("image", "audio", or "video")
     * @return Part object with inline data
     */
    private Part convertMediaBlockToInlineDataPart(Source source, String mediaType) {
        byte[] data;
        String mimeType;

        if (source instanceof Base64Source base64Source) {
            // Base64: decode and use directly
            data = Base64.getDecoder().decode(base64Source.getData());
            mimeType = base64Source.getMediaType();

        } else if (source instanceof URLSource urlSource) {
            // URL: read file and get mime type
            String url = urlSource.getUrl();
            try {
                data = readFileAsBytes(url);
                mimeType = getMimeType(url, mediaType);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read file: " + url, e);
            }

        } else {
            throw new IllegalArgumentException(
                    "Unsupported source type: " + source.getClass().getName());
        }

        // Create Blob and Part
        Blob blob = Blob.builder().data(data).mimeType(mimeType).build();

        return Part.builder().inlineData(blob).build();
    }

    /**
     * Read a file from URL/path as byte array.
     *
     * <p>Supports both remote URLs (http://, https://) and local file paths.
     *
     * @param url File URL or path
     * @return File content as byte array
     * @throws IOException If file cannot be read
     */
    private byte[] readFileAsBytes(String url) throws IOException {
        // Check if it's a remote URL
        if (url.startsWith("http://") || url.startsWith("https://")) {
            try {
                URL remoteUrl = URI.create(url).toURL();
                try (InputStream in = remoteUrl.openStream()) {
                    return in.readAllBytes();
                }
            } catch (IOException e) {
                throw new IOException("Failed to download remote file: " + url, e);
            }
        } else {
            // Local file path
            Path path = Paths.get(url);
            if (!Files.exists(path)) {
                throw new IOException("File not found: " + url);
            }
            return Files.readAllBytes(path);
        }
    }

    /**
     * Determine MIME type from file extension.
     *
     * @param url File URL or path
     * @param mediaType Media type category ("image", "audio", "video")
     * @return MIME type string (e.g., "image/png")
     */
    private String getMimeType(String url, String mediaType) {
        String extension = extractExtension(url);

        // Validate extension is supported
        List<String> supportedExts = SUPPORTED_EXTENSIONS.get(mediaType);
        if (supportedExts == null || !supportedExts.contains(extension)) {
            throw new IllegalArgumentException(
                    String.format(
                            "Unsupported file extension: %s for media type: %s, expected one of:"
                                    + " %s",
                            extension, mediaType, supportedExts));
        }

        // Special case for jpg -> jpeg
        if ("jpg".equals(extension)) {
            extension = "jpeg";
        }

        return mediaType + "/" + extension;
    }

    /**
     * Extract file extension from URL or path.
     *
     * @param url File URL or path
     * @return File extension in lowercase (without dot)
     */
    private String extractExtension(String url) {
        int lastDotIndex = url.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == url.length() - 1) {
            throw new IllegalArgumentException("Cannot extract file extension from: " + url);
        }
        return url.substring(lastDotIndex + 1).toLowerCase();
    }
}
