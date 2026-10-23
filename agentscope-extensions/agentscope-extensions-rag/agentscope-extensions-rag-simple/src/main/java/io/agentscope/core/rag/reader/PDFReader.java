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

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.ReaderException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import reactor.core.publisher.Mono;

/**
 * PDF reader implementation that reads and extracts text from PDF files.
 *
 * <p>This reader extracts text content from PDF files and chunks it according
 * to the specified strategy. It supports various text splitting strategies
 * similar to TextReader.
 *
 * <p>Note: This is a basic implementation that reads PDF files as text.
 * For full PDF parsing with proper text extraction, additional dependencies
 * (e.g., Apache PDFBox) would be required. This implementation provides a
 * foundation that can be extended.
 *
 * <p>Example usage:
 * <pre>{@code
 * PDFReader reader = new PDFReader(512, SplitStrategy.PARAGRAPH, 50);
 * ReaderInput input = ReaderInput.fromString("path/to/document.pdf");
 * List<Document> documents = reader.read(input).block();
 * }</pre>
 */
public class PDFReader extends AbstractChunkingReader {

    private final boolean extractImages;

    /**
     * Creates a new PDFReader with the specified configuration.
     *
     * @param chunkSize the target size for each chunk
     * @param splitStrategy the strategy for splitting text
     * @param overlapSize the number of characters to overlap between chunks
     * @throws IllegalArgumentException if parameters are invalid
     */
    public PDFReader(int chunkSize, SplitStrategy splitStrategy, int overlapSize) {
        this(chunkSize, splitStrategy, overlapSize, false);
    }

    /**
     * Creates a new PDFReader with the specified configuration.
     *
     * @param chunkSize the target size for each chunk
     * @param splitStrategy the strategy for splitting text
     * @param overlapSize the number of characters to overlap between chunks
     * @param extractImages true to extract images from PDF (placeholder for future
     *     implementation)
     * @throws IllegalArgumentException if parameters are invalid
     */
    public PDFReader(
            int chunkSize, SplitStrategy splitStrategy, int overlapSize, boolean extractImages) {
        super(chunkSize, splitStrategy, overlapSize);
        this.extractImages = extractImages;
    }

    /**
     * Creates a new PDFReader with default settings.
     *
     * <p>Defaults: chunkSize=512, strategy=PARAGRAPH, overlapSize=50
     */
    public PDFReader() {
        this(512, SplitStrategy.PARAGRAPH, 50, false);
    }

    @Override
    public Mono<List<Document>> read(ReaderInput input) {
        if (input == null) {
            return Mono.error(new ReaderException("Input cannot be null"));
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                String pdfPath = input.asString();
                                String text = extractTextFromPDF(pdfPath);
                                List<String> chunks =
                                        TextChunker.chunkText(
                                                text, chunkSize, splitStrategy, overlapSize);
                                return createDocuments(chunks, pdfPath);
                            } catch (Exception e) {
                                throw new ReaderException("Failed to read PDF from: " + input, e);
                            }
                        })
                .onErrorMap(ReaderException.class, e -> e);
    }

    @Override
    public List<String> getSupportedFormats() {
        return List.of("pdf");
    }

    /**
     * Extracts text from a PDF file using Apache PDFBox.
     *
     * <p>This method loads the PDF document and extracts text page by page,
     * joining pages with double newlines (\n\n) to match the Python implementation.
     *
     * @param pdfPath the path to the PDF file
     * @return extracted text content with pages separated by \n\n
     * @throws IOException if the file cannot be read
     * @throws ReaderException if PDF parsing fails
     */
    private String extractTextFromPDF(String pdfPath) throws IOException, ReaderException {
        Path path = Paths.get(pdfPath);
        if (!Files.exists(path)) {
            throw new ReaderException("PDF file does not exist: " + pdfPath);
        }

        try (PDDocument document = Loader.loadPDF(new File(pdfPath))) {
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder fullText = new StringBuilder();

            int totalPages = document.getNumberOfPages();
            for (int page = 1; page <= totalPages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                fullText.append(pageText);

                // Add double newline between pages (matching Python implementation)
                if (page < totalPages) {
                    fullText.append("\n\n");
                }
            }

            return fullText.toString();
        } catch (IOException e) {
            throw new ReaderException("Failed to extract text from PDF: " + pdfPath, e);
        }
    }

    /**
     * Creates Document objects from text chunks with deterministic doc_id.
     *
     * @param chunks the list of text chunks
     * @param pdfPath the PDF file path (used to generate doc_id)
     * @return a list of Document objects
     */
    private List<Document> createDocuments(List<String> chunks, String pdfPath) {
        // Generate deterministic doc_id using SHA256 of PDF path (matching Python implementation)
        String docId = ReaderUtils.generateDocIdSHA256(pdfPath);
        List<Document> documents = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            TextBlock content = TextBlock.builder().text(chunks.get(i)).build();
            DocumentMetadata metadata = new DocumentMetadata(content, docId, String.valueOf(i));
            documents.add(new Document(metadata));
        }

        return documents;
    }

    /**
     * Checks if image extraction is enabled.
     *
     * @return true if image extraction is enabled
     */
    public boolean isExtractImages() {
        return extractImages;
    }
}
