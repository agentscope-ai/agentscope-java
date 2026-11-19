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
package io.agentscope.extensions.model.gemini.formatter;

import com.google.genai.types.Blob;
import com.google.genai.types.FileData;
import com.google.genai.types.Part;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.FileIdSource;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converter for Gemini API multimodal content.
 *
 * <p>Converts ImageBlock, AudioBlock, VideoBlock and DataBlock to Gemini Part objects with inline
 * data, and DocumentBlock either to inline data or to a {@code fileData} reference.
 */
public class GeminiMediaConverter {

    private static final Logger log = LoggerFactory.getLogger(GeminiMediaConverter.class);

    /** Gemini accepts at most 20 MB of inline data per request. */
    private static final int MAX_INLINE_DOCUMENT_BYTES = 20 * 1024 * 1024;

    /**
     * Files API endpoint of the Gemini Developer API, used to expand a bare {@code files/...} name
     * into the URI that {@code fileData} expects. Deployments that talk to Vertex AI or to a custom
     * {@code baseUrl} should pass a full URI or a {@code gs://} location instead.
     */
    private static final String DEFAULT_FILES_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/";

    /** Returned by {@link MediaUtils#determineMediaType(String)} when the extension says nothing. */
    private static final String OCTET_STREAM = "application/octet-stream";

    /**
     * Document MIME types the Gemini API can read, per the {@code generateContent} API reference.
     * An unlisted document (a {@code .docx}, for example) makes the API reject the whole request,
     * so the converter degrades such documents to a text placeholder instead. Aliases documented
     * by earlier Gemini releases are accepted as well.
     */
    private static final Set<String> SUPPORTED_DOCUMENT_MIME_TYPES =
            Set.of(
                    "application/pdf",
                    // Text formats.
                    "text/plain",
                    "text/html",
                    "text/css",
                    "text/javascript",
                    "text/x-typescript",
                    "text/csv",
                    "text/markdown",
                    "text/x-python",
                    "text/xml",
                    "text/rtf",
                    // Application formats.
                    "application/x-javascript",
                    "application/x-typescript",
                    "application/x-python-code",
                    "application/json",
                    "application/x-ipynb+json",
                    "application/rtf",
                    // Aliases documented by earlier Gemini releases.
                    "text/md",
                    "application/xml");

    /** Providers whose {@link FileIdSource} ids this converter forwards as Gemini file URIs. */
    private static final Set<String> FILE_PROVIDER_ALIASES = Set.of("gemini", "google");

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
     * Convert DataBlock to Gemini Part with inline data.
     *
     * <p>MIME type resolution order:
     * <ol>
     *   <li>{@code Base64Source.mediaType} — always explicit</li>
     *   <li>{@code URLSource.mimeType} — caller-supplied hint for extension-less URLs</li>
     *   <li>Extension-based inference via {@link #getMimeType}</li>
     * </ol>
     *
     * @param block DataBlock to convert
     * @return Part object containing inline data
     */
    public Part convertToInlineDataPart(DataBlock block) {
        Source source = block.getSource();
        String mimeType = MediaUtils.resolveMimeType(source);
        byte[] data;

        if (source instanceof Base64Source base64Source) {
            data = Base64.getDecoder().decode(base64Source.getData());
        } else if (source instanceof URLSource urlSource) {
            try {
                data = readFileAsBytes(urlSource.getUrl());
            } catch (IOException e) {
                throw new RuntimeException(
                        "Failed to read DataBlock file: " + urlSource.getUrl(), e);
            }
        } else {
            throw new IllegalArgumentException(
                    "Unsupported source type: " + source.getClass().getName());
        }

        Blob blob = Blob.builder().data(data).mimeType(mimeType).build();
        return Part.builder().inlineData(blob).build();
    }

    /**
     * Convert DocumentBlock to a Gemini Part.
     *
     * <p>Documents already uploaded to the Gemini Files API are sent as {@code fileData}, with the
     * file id expanded to the URI that {@code fileData} expects. Documents supplied as bytes or a
     * URL are sent inline.
     *
     * <p>The MIME type comes from {@link DocumentBlock#getMimeType()} when set; otherwise it is
     * resolved from the source (Base64 media type or URL hint/extension).
     *
     * @param block DocumentBlock to convert
     * @return Part object containing the document payload
     */
    public Part convertToInlineDataPart(DocumentBlock block) {
        Source source = block.getSource();
        String label = block.getName() != null ? block.getName() : block.getId();
        String declaredMimeType = block.getMimeType();
        boolean hasMimeType = declaredMimeType != null && !declaredMimeType.isBlank();

        if (source instanceof FileIdSource fileIdSource) {
            String provider = fileIdSource.getProvider();
            if (!FILE_PROVIDER_ALIASES.contains(provider.toLowerCase(Locale.ROOT))) {
                // Cross-provider file ids are a configuration mistake, not a reason to fail the
                // whole request: degrade to a placeholder so the rest of the turn still runs.
                log.warn(
                        "Gemini document input requires a Gemini file id, got provider '{}';"
                                + " sending a text placeholder for '{}'",
                        provider,
                        label);
                return Part.builder()
                        .text("[Document - not supported by Gemini: " + label + "]")
                        .build();
            }
            FileData.Builder fileData = FileData.builder();
            String fileUri = toFileUri(fileIdSource.getFileId());
            if (fileUri == null) {
                log.warn(
                        "Gemini file id '{}' of document '{}' cannot be turned into a file URI;"
                                + " sending a text placeholder",
                        fileIdSource.getFileId(),
                        label);
                return Part.builder()
                        .text("[Document - not supported by Gemini: " + label + "]")
                        .build();
            }
            String resolvedMimeType = hasMimeType ? declaredMimeType : inferMimeTypeFromName(block);
            if (resolvedMimeType == null || !isSupportedDocumentMimeType(resolvedMimeType)) {
                // fileData needs a MIME type next to fileUri, and the model only reads the
                // documented document types; sending anything else makes the API reject the whole
                // request, which is worse than a placeholder.
                log.warn(
                        "Cannot send document '{}' to Gemini: MIME type '{}' is not one of the"
                                + " types the API can read; sending a text placeholder",
                        label,
                        resolvedMimeType);
                return Part.builder()
                        .text("[Document - unsupported MIME type: " + label + "]")
                        .build();
            }
            // FileData.mimeType(String) wraps the value in Optional.of, so it must not be null.
            return Part.builder()
                    .fileData(fileData.fileUri(fileUri).mimeType(resolvedMimeType).build())
                    .build();
        }

        String mimeType;
        try {
            mimeType = hasMimeType ? declaredMimeType : MediaUtils.resolveMimeType(source);
        } catch (IllegalArgumentException e) {
            // A document whose type cannot be resolved used to be dropped silently; fail soft
            // instead of aborting the whole request.
            log.warn("Cannot determine MIME type for document '{}': {}", label, e.getMessage());
            return Part.builder().text("[Document - unsupported MIME type: " + label + "]").build();
        }
        if (mimeType == null || mimeType.isBlank() || !isSupportedDocumentMimeType(mimeType)) {
            // Base64Source carries the MIME type itself; when it is missing, or names a type the
            // API cannot read as a document, there is no point inlining the payload - the request
            // would be rejected wholesale.
            log.warn(
                    "Document '{}' has an unusable MIME type ({}); sending a text placeholder",
                    label,
                    mimeType);
            return Part.builder().text("[Document - unsupported MIME type: " + label + "]").build();
        }
        try {
            return convertMediaBlockToInlineDataPart(source, "document", mimeType);
        } catch (RuntimeException e) {
            // An expired URL, an unreachable host or an oversized payload used to be skipped;
            // failing the whole turn is worse than a placeholder.
            log.warn("Cannot inline document '{}': {}", label, e.getMessage());
            return Part.builder().text("[Document - not readable: " + label + "]").build();
        }
    }

    /**
     * Check a MIME type against the document types the Gemini API can read.
     *
     * @param mimeType the declared or resolved MIME type, may carry parameters ({@code ;charset=})
     * @return {@code true} when the API documents the type as a readable document input
     */
    private static boolean isSupportedDocumentMimeType(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        int parameters = mimeType.indexOf(';');
        String normalized =
                (parameters >= 0 ? mimeType.substring(0, parameters) : mimeType)
                        .trim()
                        .toLowerCase(Locale.ROOT);
        return SUPPORTED_DOCUMENT_MIME_TYPES.contains(normalized);
    }

    /**
     * Expand a Gemini Files API name into the URI that {@code fileData} expects.
     *
     * <p>The Files API returns {@code name} as {@code files/abc123} while {@code uri} is the full
     * {@code https://generativelanguage.googleapis.com/v1beta/files/abc123}; {@code FileData}
     * takes the URI. Bare names are expanded against {@link #DEFAULT_FILES_ENDPOINT}; values that
     * are already a URI (http, https or gs) are passed through. Anything else cannot be expressed
     * as a file URI, so the caller degrades instead of sending a part the API will reject.
     *
     * @param fileId Provider file id from {@link FileIdSource}
     * @return Full file URI accepted by {@code fileData}, or {@code null} when the id is unusable
     */
    private static String toFileUri(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return null;
        }
        if (fileId.startsWith("http://")
                || fileId.startsWith("https://")
                || fileId.startsWith("gs://")) {
            return fileId;
        }
        if (!fileId.startsWith("files/")) {
            log.warn(
                    "Gemini file id '{}' is neither a URI nor a Files API name (files/...);"
                            + " it cannot be used as a file URI",
                    fileId);
            return null;
        }
        return DEFAULT_FILES_ENDPOINT + fileId;
    }

    /**
     * Infers a MIME type from the document name, for sources that carry no type of their own.
     *
     * @param block document block
     * @return inferred MIME type, or {@code null} when the name gives nothing usable
     */
    private static String inferMimeTypeFromName(DocumentBlock block) {
        String name = block.getName();
        if (name == null || name.isBlank()) {
            return null;
        }
        String inferred = MediaUtils.determineMediaType(name);
        return OCTET_STREAM.equals(inferred) ? null : inferred;
    }

    /**
     * Convert a media source to Gemini Part with inline data.
     *
     * @param source Source object (Base64Source or URLSource)
     * @param mediaType Media type string ("image", "audio", or "video")
     * @return Part object with inline data
     */
    private Part convertMediaBlockToInlineDataPart(Source source, String mediaType) {
        return convertMediaBlockToInlineDataPart(source, mediaType, null);
    }

    /**
     * Convert a media source to Gemini Part with inline data.
     *
     * @param source Source object (Base64Source or URLSource)
     * @param mediaType Media type string ("image", "audio", "video", or "document")
     * @param resolvedMimeType MIME type resolved by the caller; when {@code null} the source hints
     *     are used
     * @return Part object with inline data
     */
    private Part convertMediaBlockToInlineDataPart(
            Source source, String mediaType, String resolvedMimeType) {
        byte[] data;
        String mimeType;

        if (source instanceof Base64Source base64Source) {
            // Base64: decode and use directly
            data = Base64.getDecoder().decode(base64Source.getData());
            mimeType = resolvedMimeType != null ? resolvedMimeType : base64Source.getMediaType();

        } else if (source instanceof URLSource urlSource) {
            // URL: read file and get mime type
            String url = urlSource.getUrl();
            try {
                data = readFileAsBytes(url);
                mimeType =
                        resolvedMimeType != null ? resolvedMimeType : getMimeType(url, mediaType);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read file: " + url, e);
            }

        } else {
            throw new IllegalArgumentException(
                    "Unsupported source type: " + source.getClass().getName());
        }

        if ("document".equals(mediaType) && data.length > MAX_INLINE_DOCUMENT_BYTES) {
            throw new IllegalArgumentException(
                    "inline document is larger than " + MAX_INLINE_DOCUMENT_BYTES + " bytes");
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
        String extension = MediaUtils.getExtension(url);
        if (extension.isBlank()) {
            throw new IllegalArgumentException("Cannot extract file extension from: " + url);
        }
        return extension.toLowerCase();
    }
}
