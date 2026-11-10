/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.a2a.agent.message;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.a2aproject.sdk.spec.FileContent;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.FileWithUri;

/**
 * Parser for {@link FilePart} to {@link ContentBlock}.
 *
 * <ul>
 *     <li>{@link FilePart} with mimeType is `image/*` parse to {@link ImageBlock}</li>
 *     <li>{@link FilePart} with mimeType is `audio/*` parse to {@link AudioBlock}</li>
 *     <li>{@link FilePart} with mimeType is `video/*` parse to {@link VideoBlock}</li>
 *     <li>a {@link FilePart} whose MIME type names a document format (PDF, office files, text,
 *         ...) parses to a {@link DocumentBlock}; anything else is not routable content and
 *         yields {@code null} rather than binary bytes in a model request</li>
 *     <li>a {@link FilePart} without a usable MIME type yields {@code null}, even when it carries
 *         a block-type marker</li>
 * </ul>
 */
public class FilePartParser implements PartParser<FilePart> {

    /**
     * File types that are documents rather than arbitrary binaries. Anything else stays
     * unroutable, so a zip or a database dump is not pushed into a model request as content.
     */
    private static final Set<String> DOCUMENT_MIME_TYPES =
            Set.of(
                    "application/pdf",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/epub+zip",
                    "application/json",
                    "application/xml",
                    "text/plain",
                    "text/markdown",
                    "text/csv",
                    "text/xml");

    @Override
    public ContentBlock parse(FilePart part) {
        FileContent file = part.file();
        // A FilePart without a MIME type cannot be routed: the media blocks and the document
        // block all need one, and a document with an empty media type leaves providers unable to
        // build a data URL or pick a decoder. Check this before the marker: the marker routes the
        // part into the document branch and used to bypass this guard.
        if (file.mimeType() == null || file.mimeType().isBlank()) {
            return null;
        }
        // An explicit AgentScope block-type marker wins over the MIME type: a scanned page is a
        // document travelling as image/jpeg, and routing it by MIME alone would rebuild an
        // ImageBlock where the sender sent a DocumentBlock.
        String markedType = markedBlockType(part.metadata());
        if (MessageConstants.BlockContent.TYPE_DOCUMENT.equals(markedType)) {
            DocumentBlock document = parseToDocumentBlock(part);
            if (document != null) {
                return document;
            }
        }
        return switch (getPrimaryType(file.mimeType())) {
            case MessageConstants.BlockContent.TYPE_IMAGE -> parseToImageBlock(file);
            case MessageConstants.BlockContent.TYPE_AUDIO -> parseToAudioBlock(file);
            case MessageConstants.BlockContent.TYPE_VIDEO -> parseToVideoBlock(file);
            default -> isDocumentMimeType(file.mimeType()) ? parseToDocumentBlock(part) : null;
        };
    }

    private static String markedBlockType(Map<String, Object> metadata) {
        Object value =
                metadata == null ? null : metadata.get(MessageConstants.BLOCK_TYPE_METADATA_KEY);
        return value == null ? null : value.toString();
    }

    private ImageBlock parseToImageBlock(FileContent file) {
        ImageBlock.Builder builder = ImageBlock.builder();
        Source source = buildSource(file);
        return source != null ? builder.source(source).build() : null;
    }

    private AudioBlock parseToAudioBlock(FileContent file) {
        AudioBlock.Builder builder = AudioBlock.builder();
        Source source = buildSource(file);
        return source != null ? builder.source(source).build() : null;
    }

    private VideoBlock parseToVideoBlock(FileContent file) {
        VideoBlock.Builder builder = VideoBlock.builder();
        Source source = buildSource(file);
        return source != null ? builder.source(source).build() : null;
    }

    private DocumentBlock parseToDocumentBlock(FilePart part) {
        FileContent file = part.file();
        Source source = buildSource(file);
        if (source == null) {
            return null;
        }
        DocumentBlock.Builder builder =
                DocumentBlock.builder()
                        .source(source)
                        .name(file.name())
                        .mimeType(normalizeMimeType(file.mimeType()));
        Map<String, Object> userMetadata = userMetadata(part.metadata());
        if (!userMetadata.isEmpty()) {
            builder.metadata(userMetadata);
        }
        return builder.build();
    }

    /**
     * Extracts the user's entries from the part metadata, leaving the framework's block-type
     * marker out - it has already been consumed by the routing decision.
     *
     * @param metadata the file-part metadata, may be null
     * @return the user entries, possibly empty
     */
    private static Map<String, Object> userMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return Map.of();
        }
        Map<String, Object> user = new java.util.LinkedHashMap<>();
        metadata.forEach(
                (key, value) -> {
                    if (value != null && !MessageConstants.BLOCK_TYPE_METADATA_KEY.equals(key)) {
                        user.put(key, value);
                    }
                });
        return user;
    }

    private Source buildSource(FileContent file) {
        if (file instanceof FileWithBytes fileWithBytes) {
            return Base64Source.builder()
                    .mediaType(file.mimeType())
                    .data(fileWithBytes.bytes())
                    .build();
        } else if (file instanceof FileWithUri fileWithUri) {
            // Keep the declared type on the source: a DocumentBlock may not repeat it, and the
            // outbound parser resolves the type from the source before giving up on a file part.
            return URLSource.builder().url(fileWithUri.uri()).mimeType(file.mimeType()).build();
        } else {
            return null;
        }
    }

    private static boolean isDocumentMimeType(String mimeType) {
        String normalized = normalizeMimeType(mimeType);
        return normalized != null && DOCUMENT_MIME_TYPES.contains(normalized);
    }

    /**
     * Drops MIME parameters such as {@code ; charset=utf-8}, so the stored type is one that a
     * provider can send back verbatim.
     */
    private static String normalizeMimeType(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        String normalized = mimeType.toLowerCase(Locale.ROOT);
        int parameters = normalized.indexOf(';');
        if (parameters >= 0) {
            normalized = normalized.substring(0, parameters).trim();
        }
        return normalized;
    }

    private String getPrimaryType(String mimeType) {
        // If no mimeType, return empty string.
        if (mimeType == null || mimeType.isEmpty()) {
            return "";
        }
        int slashIndex = mimeType.indexOf('/');
        if (slashIndex == -1) {
            // If no '/', means this type is a primary type or wrong type.
            return mimeType;
        }
        // Extract primary type
        return mimeType.substring(0, slashIndex);
    }
}
