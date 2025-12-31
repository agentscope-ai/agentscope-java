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
package io.agentscope.core.rag.reader;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.rag.exception.ReaderException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Image reader implementation that reads image files.
 *
 * <p>This reader supports various image formats and can optionally extract text
 * from images using OCR (if enabled). The reader creates Document objects with
 * image content that can be used in multimodal RAG systems.
 *
 * <p>Example usage:
 * <pre>{@code
 * ImageReader reader = new ImageReader(false);
 * ReaderInput input = ReaderInput.fromString("path/to/image.jpg");
 * List<Document> documents = reader.read(input).block();
 * }</pre>
 */
public class ImageReader implements Reader {

    private final boolean enableOCR;

    /**
     * Creates a new ImageReader with OCR disabled.
     */
    public ImageReader() {
        this(false);
    }

    /**
     * Creates a new ImageReader with the specified OCR setting.
     *
     * @param enableOCR true to enable OCR text extraction from images
     */
    public ImageReader(boolean enableOCR) {
        this.enableOCR = enableOCR;
    }

    @Override
    public Mono<List<Document>> read(ReaderInput input) {
        if (input == null) {
            return Mono.error(new ReaderException("Input cannot be null"));
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                String imagePath = input.asString();
                                return loadImageDocument(imagePath);
                            } catch (Exception e) {
                                throw new ReaderException("Failed to read image from: " + input, e);
                            }
                        })
                .onErrorMap(ReaderException.class, e -> e);
    }

    @Override
    public List<String> getSupportedFormats() {
        return List.of("jpg", "jpeg", "png", "gif", "bmp", "tiff", "webp");
    }

    /**
     * Loads an image document from the given path.
     *
     * @param imagePath the path to the image file (can be local file path or URL)
     * @return a list containing a single Document with image content
     */
    private List<Document> loadImageDocument(String imagePath) {
        // Generate deterministic doc_id using MD5 of image path (matching Python implementation)
        String docId = ReaderUtils.generateDocIdMD5(imagePath);

        // Create ImageBlock with URLSource
        String url;
        if (isUrl(imagePath)) {
            url = imagePath;
        } else {
            // For local files, use file:// protocol
            url = imagePath.startsWith("file://") ? imagePath : "file://" + imagePath;
        }

        URLSource source = URLSource.builder().url(url).build();
        ImageBlock content = ImageBlock.builder().source(source).build();

        // If OCR is enabled, extract text from image
        // Note: Actual OCR implementation would require additional dependencies
        // For now, we only store the image content
        // Future: Could create a composite ContentBlock or add OCR text as separate field

        DocumentMetadata metadata = new DocumentMetadata(content, docId, "0");
        return List.of(new Document(metadata));
    }

    /**
     * Checks if the given path is a URL.
     *
     * @param path the path to check
     * @return true if it's a URL, false otherwise
     */
    private boolean isUrl(String path) {
        if (path == null) {
            return false;
        }
        String lowerPath = path.toLowerCase();
        return lowerPath.startsWith("http://")
                || lowerPath.startsWith("https://")
                || lowerPath.startsWith("file://");
    }

    /**
     * Extracts text from an image using OCR.
     *
     * <p>This is a placeholder method. Actual OCR implementation would require
     * additional dependencies (e.g., Tesseract, cloud OCR APIs).
     *
     * @param imagePath the path to the image file
     * @return extracted text, or empty string if OCR is not available
     */
    private String extractTextFromImage(String imagePath) {
        // Placeholder: Actual OCR would be implemented here
        // For now, return empty string to indicate no text extracted
        // Future implementation could integrate with:
        // - Tesseract OCR (tess4j library)
        // - Cloud OCR APIs (DashScope, OpenAI Vision, etc.)
        return "";
    }

    /**
     * Checks if OCR is enabled.
     *
     * @return true if OCR is enabled
     */
    public boolean isOcrEnabled() {
        return enableOCR;
    }
}
