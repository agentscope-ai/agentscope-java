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

import io.agentscope.core.a2a.agent.utils.LoggerUtil;
import io.agentscope.core.formatter.DocumentReferenceRenderer;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.FileIdSource;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import java.util.Map;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.FileWithUri;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parser for {@link DocumentBlock} to a {@link FilePart}.
 *
 * <p>URL and inline-data sources travel as a file part with the document name and MIME type. A
 * provider file id has no A2A representation, and a document whose MIME type cannot be resolved
 * cannot travel as a file part either: the receiving parser drops an untyped part. Both are sent
 * as a text reference instead, so the peer at least learns which file the turn referred to rather
 * than receiving a message that silently lost the attachment.
 */
public class DocumentBlockParser implements ContentBlockParser<DocumentBlock> {

    private static final Logger log = LoggerFactory.getLogger(DocumentBlockParser.class);

    @Override
    public Part<?> parse(DocumentBlock contentBlock) {
        Source source = contentBlock.getSource();
        String name = nameOf(contentBlock);
        String mimeType = mimeTypeOf(contentBlock);

        if (source instanceof Base64Source base64Source) {
            String resolvedMimeType =
                    mimeType != null && !mimeType.isBlank()
                            ? mimeType
                            : base64Source.getMediaType();
            if (resolvedMimeType == null || resolvedMimeType.isBlank()) {
                return untypedTextReference(contentBlock, name);
            }
            return new FilePart(
                    new FileWithBytes(resolvedMimeType, name, base64Source.getData()),
                    metadataOf(contentBlock));
        }
        if (source instanceof URLSource urlSource) {
            if (mimeType == null || mimeType.isBlank()) {
                return untypedTextReference(contentBlock, name);
            }
            return new FilePart(
                    new FileWithUri(mimeType, name, urlSource.getUrl()), metadataOf(contentBlock));
        }
        if (source instanceof FileIdSource fileIdSource) {
            LoggerUtil.warn(
                    log,
                    "Provider file id '{}' has no A2A file-part representation; sending a text"
                            + " reference instead.",
                    fileIdSource.getFileId());
            return new TextPart(
                    DocumentReferenceRenderer.render(contentBlock),
                    MetadataUtil.buildTypeMetadata(MessageConstants.BlockContent.TYPE_TEXT));
        }
        LoggerUtil.warn(log, "Unsupported source type: {}", source.getClass().getName());
        return null;
    }

    /**
     * Sends a document whose MIME type cannot be resolved as a text reference.
     *
     * <p>The receiving parser drops an untyped file part, so sending one would lose the document
     * silently - the exact behaviour this parser exists to prevent.
     *
     * @param contentBlock the document being exported
     * @param name the resolved document name
     * @return a text part carrying the document reference
     */
    private Part<?> untypedTextReference(DocumentBlock contentBlock, String name) {
        LoggerUtil.warn(
                log,
                "Document '{}' has no resolvable MIME type; sending a text reference instead of a"
                        + " file part.",
                name);
        return new TextPart(
                DocumentReferenceRenderer.render(contentBlock),
                MetadataUtil.buildTypeMetadata(MessageConstants.BlockContent.TYPE_TEXT));
    }

    /**
     * Builds the file-part metadata: the framework block-type marker first, then the user's
     * entries. Reserved keys win, so a caller cannot spoof the wire representation.
     *
     * @param block the document block being exported
     * @return the metadata map for the outgoing file part
     */
    private static Map<String, Object> metadataOf(DocumentBlock block) {
        Map<String, Object> metadata =
                MetadataUtil.buildTypeMetadata(MessageConstants.BlockContent.TYPE_DOCUMENT);
        if (block.getMetadata() != null) {
            block.getMetadata().forEach(metadata::putIfAbsent);
        }
        return metadata;
    }

    private static String nameOf(DocumentBlock block) {
        return block.getName() != null ? block.getName() : block.getId();
    }

    private static String mimeTypeOf(DocumentBlock block) {
        String mimeType = block.getMimeType();
        if (mimeType != null && !mimeType.isBlank()) {
            return mimeType;
        }
        // A URL source carries its own hint, and the URL extension often supplies one. Without
        // this fallback a caller that set the type only on the source would send an untyped file
        // part, which the receiving side drops.
        try {
            return MediaUtils.resolveMimeType(block.getSource());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
