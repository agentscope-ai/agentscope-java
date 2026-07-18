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
package io.agentscope.extensions.model.anthropic.formatter;

import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.CitationsConfigParam;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.UrlImageSource;
import com.anthropic.models.messages.UrlPdfSource;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Handles media content conversion for Anthropic API.
 */
public class AnthropicMediaConverter {

    private static final String PDF_MEDIA_TYPE = "application/pdf";

    private final CitationMode citationMode;

    /** Create an Anthropic media converter with citations disabled. */
    public AnthropicMediaConverter() {
        this(CitationMode.DISABLED);
    }

    /**
     * Create an Anthropic media converter with the given citation mode.
     *
     * @param citationMode whether PDF documents should enable native citations
     */
    AnthropicMediaConverter(CitationMode citationMode) {
        this.citationMode = Objects.requireNonNull(citationMode);
    }

    /**
     * Convert ImageBlock to Anthropic ImageBlockParam. For local files, converts to base64. For
     * remote URLs, uses URL source directly.
     */
    public ImageBlockParam convertImageBlock(ImageBlock imageBlock) throws Exception {
        Source source = imageBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            String mediaPath = isFileUri(url) ? Path.of(URI.create(url)).toString() : url;
            MediaUtils.validateImageExtension(mediaPath);
            String mediaType = MediaUtils.determineMediaType(mediaPath);
            return convertUrlImage(url, mediaType != null ? mediaType : "image/png");
        } else if (source instanceof Base64Source base64Source) {
            String mediaType = base64Source.getMediaType();
            String base64Data = base64Source.getData();

            return ImageBlockParam.builder()
                    .source(
                            Base64ImageSource.builder()
                                    .data(base64Data)
                                    .mediaType(
                                            Base64ImageSource.MediaType.of(
                                                    mediaType != null ? mediaType : "image/png"))
                                    .build())
                    .build();
        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }

    /**
     * Convert DataBlock to Anthropic ImageBlockParam by resolving MIME type and routing to image.
     *
     * <p>This image-only conversion method returns {@link ImageBlockParam}. Use {@link
     * #convertDataBlockContent(DataBlock)} when the input may be either an image or a PDF.
     * Audio and video DataBlocks throw {@link IllegalArgumentException} because the Anthropic API
     * does not expose a generic binary content block param.
     *
     * <p>MIME type resolution order:
     * <ol>
     *   <li>{@code Base64Source.mediaType} — always explicit</li>
     *   <li>{@code URLSource.mimeType} — caller-supplied hint for extension-less URLs</li>
     *   <li>{@code MediaUtils.determineMediaType(url)} — extension-based inference</li>
     * </ol>
     *
     * @param dataBlock The data block to convert
     * @return ImageBlockParam for Anthropic API
     * @throws Exception If conversion fails or MIME type resolves to a non-image category
     */
    public ImageBlockParam convertDataBlock(DataBlock dataBlock) throws Exception {
        Source source = dataBlock.getSource();
        String mimeType = MediaUtils.resolveMimeType(source);

        if (!mimeType.startsWith("image/")) {
            throw new IllegalArgumentException(
                    "Anthropic API only supports image DataBlocks; got MIME type: " + mimeType);
        }

        if (source instanceof URLSource urlSource) {
            return convertUrlImage(urlSource.getUrl(), mimeType);
        } else if (source instanceof Base64Source base64Source) {
            return ImageBlockParam.builder()
                    .source(
                            Base64ImageSource.builder()
                                    .data(base64Source.getData())
                                    .mediaType(Base64ImageSource.MediaType.of(mimeType))
                                    .build())
                    .build();
        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }

    /**
     * Convert a generic DataBlock to an Anthropic image or PDF document content block.
     *
     * @param dataBlock The data block to convert
     * @return Anthropic image or document content block
     * @throws Exception If conversion fails or the MIME type is unsupported
     */
    public ContentBlockParam convertDataBlockContent(DataBlock dataBlock) throws Exception {
        String mimeType = MediaUtils.resolveMimeType(dataBlock.getSource());
        if (mimeType.startsWith("image/")) {
            return ContentBlockParam.ofImage(convertDataBlock(dataBlock));
        }
        if (PDF_MEDIA_TYPE.equalsIgnoreCase(mimeType)) {
            return ContentBlockParam.ofDocument(convertPdfDataBlock(dataBlock));
        }
        throw new IllegalArgumentException(
                "Anthropic API only supports image and PDF DataBlocks; got MIME type: " + mimeType);
    }

    /**
     * Convert a PDF DataBlock to an Anthropic document block.
     *
     * <p>Local files are encoded as base64. Remote HTTP(S) URLs are passed through as URL PDF
     * sources. Citations are enabled based on the context provided at construction time.
     *
     * @param dataBlock PDF data block
     * @return Anthropic PDF document block
     * @throws Exception If the source cannot be resolved or read as a PDF
     */
    public DocumentBlockParam convertPdfDataBlock(DataBlock dataBlock) throws Exception {
        Source source = dataBlock.getSource();
        String mimeType = MediaUtils.resolveMimeType(source);
        if (!PDF_MEDIA_TYPE.equalsIgnoreCase(mimeType)) {
            throw new IllegalArgumentException(
                    "Expected PDF DataBlock; got MIME type: " + mimeType);
        }

        DocumentBlockParam.Builder builder = DocumentBlockParam.builder();
        if (dataBlock.getName() != null && !dataBlock.getName().isBlank()) {
            builder.title(dataBlock.getName());
        }
        if (citationMode.isEnabled()) {
            builder.citations(CitationsConfigParam.builder().enabled(true).build());
        }

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            if (isFileUri(url)) {
                Path path = Path.of(URI.create(url));
                builder.source(
                        Base64PdfSource.builder()
                                .data(MediaUtils.fileToBase64(path.toString()))
                                .build());
            } else if (MediaUtils.isLocalFile(url)) {
                builder.source(
                        Base64PdfSource.builder().data(MediaUtils.fileToBase64(url)).build());
            } else if (isHttpUrl(url)) {
                builder.source(UrlPdfSource.builder().url(url).build());
            } else {
                throw new IllegalArgumentException(
                        "Anthropic PDF URLs must use HTTP(S) or the file scheme: " + url);
            }
        } else if (source instanceof Base64Source base64Source) {
            builder.source(Base64PdfSource.builder().data(base64Source.getData()).build());
        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }

        return builder.build();
    }

    private ImageBlockParam convertUrlImage(String url, String mimeType) throws Exception {
        if (isFileUri(url)) {
            Path path = Path.of(URI.create(url));
            return createBase64Image(MediaUtils.fileToBase64(path.toString()), mimeType);
        }
        if (MediaUtils.isLocalFile(url)) {
            return createBase64Image(MediaUtils.fileToBase64(url), mimeType);
        }
        if (isHttpUrl(url)) {
            // The MIME type was resolved before this method, so extension-less HTTP(S) URLs with
            // an explicit hint remain supported.
            return ImageBlockParam.builder()
                    .source(UrlImageSource.builder().url(url).build())
                    .build();
        }
        throw new IllegalArgumentException(
                "Anthropic image URLs must use HTTP(S) or the file scheme: " + url);
    }

    private ImageBlockParam createBase64Image(String data, String mimeType) {
        return ImageBlockParam.builder()
                .source(
                        Base64ImageSource.builder()
                                .data(data)
                                .mediaType(Base64ImageSource.MediaType.of(mimeType))
                                .build())
                .build();
    }

    private static boolean isFileUri(String url) {
        return url != null && url.regionMatches(true, 0, "file:", 0, "file:".length());
    }

    private static boolean isHttpUrl(String url) {
        return url != null
                && (url.regionMatches(true, 0, "http://", 0, "http://".length())
                        || url.regionMatches(true, 0, "https://", 0, "https://".length()));
    }
}
