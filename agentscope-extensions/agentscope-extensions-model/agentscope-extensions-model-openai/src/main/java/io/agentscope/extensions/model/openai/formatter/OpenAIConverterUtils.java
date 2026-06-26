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
package io.agentscope.extensions.model.openai.formatter;

import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;

/**
 * Utility class for OpenAI message and conversation conversion.
 */
public final class OpenAIConverterUtils {

    private OpenAIConverterUtils() {
        // Utility class, no instantiation
    }

    /**
     * Convert an image source to a URL string.
     *
     * @param source The image source (URLSource or Base64Source)
     * @return The URL string or data URI
     * @throws IllegalArgumentException if source is null or of unknown type
     */
    public static String convertImageSourceToUrl(Source source) {
        if (source == null) {
            throw new IllegalArgumentException("Image source cannot be null");
        }
        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            if (url == null || url.isEmpty()) {
                throw new IllegalArgumentException("URLSource has null or empty URL");
            }
            return url;
        } else if (source instanceof Base64Source b64Source) {
            // Convert base64 data to data URI
            String data = b64Source.getData();
            if (data == null || data.isEmpty()) {
                throw new IllegalArgumentException("Base64Source has null or empty data");
            }
            String mediaType =
                    b64Source.getMediaType() != null ? b64Source.getMediaType() : "image/png";
            return "data:" + mediaType + ";base64," + data;
        } else {
            throw new IllegalArgumentException("Unknown source type: " + source.getClass());
        }
    }

    /**
     * Detect audio format from media type.
     *
     * @param mediaType The media type (e.g., "audio/wav")
     * @return The format string (e.g., "wav")
     */
    public static String detectAudioFormat(String mediaType) {
        if (mediaType == null) {
            return "mp3";
        }
        if (mediaType.contains("wav")) {
            return "wav";
        } else if (mediaType.contains("mp3")) {
            return "mp3";
        } else if (mediaType.contains("opus")) {
            return "opus";
        } else if (mediaType.contains("flac")) {
            return "flac";
        } else {
            return "mp3";
        }
    }

    /**
     * Convert a video source to a URL string.
     *
     * @param source The video source (URLSource or Base64Source)
     * @return The URL string or data URI
     * @throws IllegalArgumentException if source is null or of unknown type
     */
    public static String convertVideoSourceToUrl(Source source) {
        if (source == null) {
            throw new IllegalArgumentException("Video source cannot be null");
        }
        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            if (url == null || url.isEmpty()) {
                throw new IllegalArgumentException("URLSource has null or empty URL");
            }
            return url;
        } else if (source instanceof Base64Source b64Source) {
            // Convert base64 data to data URI
            String data = b64Source.getData();
            if (data == null || data.isEmpty()) {
                throw new IllegalArgumentException("Base64Source has null or empty data");
            }
            String mediaType =
                    b64Source.getMediaType() != null ? b64Source.getMediaType() : "video/mp4";
            return "data:" + mediaType + ";base64," + data;
        } else {
            throw new IllegalArgumentException("Unknown source type: " + source.getClass());
        }
    }

    /**
     * Resolve the MIME type from a Source.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code Base64Source.mediaType} — always explicit</li>
     *   <li>{@code URLSource.mimeType} — caller-supplied hint for extension-less URLs</li>
     *   <li>{@code MediaUtils.determineMediaType(url)} — extension-based inference</li>
     * </ol>
     *
     * @param source The source to resolve MIME type from
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
            String inferred = MediaUtils.determineMediaType(urlSource.getUrl());
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
}
