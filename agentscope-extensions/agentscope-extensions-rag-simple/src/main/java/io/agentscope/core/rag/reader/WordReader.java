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

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.ReaderException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import reactor.core.publisher.Mono;

/**
 * Word document reader that extracts text, tables, and images from Word files.
 *
 * <p>This reader supports comprehensive extraction of content from Word documents (.docx),
 * including:
 * <ul>
 *   <li>Text extraction from paragraphs
 *   <li>Table extraction with Markdown or JSON formatting
 *   <li>Image extraction with Base64 encoding
 *   <li>Configurable chunking strategies
 * </ul>
 *
 * <p><b>Dependencies:</b> Requires Apache POI (poi-ooxml) to be available on the classpath.
 *
 * <p><b>Example usage:</b>
 * <pre>{@code
 * WordReader reader = new WordReader(512, SplitStrategy.PARAGRAPH, 50,
 *                                     true, false, TableFormat.MARKDOWN);
 * ReaderInput input = ReaderInput.fromString("path/to/document.docx");
 * List<Document> documents = reader.read(input).block();
 * }</pre>
 */
public class WordReader extends AbstractChunkingReader {

    private final boolean includeImage;
    private final boolean separateTable;
    private final TableFormat tableFormat;

    /**
     * Creates a new WordReader with full configuration.
     *
     * @param chunkSize the target size for each chunk
     * @param splitStrategy the strategy for splitting text
     * @param overlapSize the number of characters to overlap between chunks
     * @param includeImage whether to extract and include images from the document
     * @param separateTable whether to treat tables as separate chunks
     * @param tableFormat the format for extracted tables (MARKDOWN or JSON)
     * @throws IllegalArgumentException if parameters are invalid
     */
    public WordReader(
            int chunkSize,
            SplitStrategy splitStrategy,
            int overlapSize,
            boolean includeImage,
            boolean separateTable,
            TableFormat tableFormat) {
        super(chunkSize, splitStrategy, overlapSize);
        if (tableFormat == null) {
            throw new IllegalArgumentException("Table format cannot be null");
        }
        this.includeImage = includeImage;
        this.separateTable = separateTable;
        this.tableFormat = tableFormat;
    }

    /**
     * Creates a new WordReader with default settings.
     *
     * <p>Defaults:
     * <ul>
     *   <li>chunkSize = 512
     *   <li>splitStrategy = PARAGRAPH
     *   <li>overlapSize = 50
     *   <li>includeImage = true
     *   <li>separateTable = false
     *   <li>tableFormat = MARKDOWN
     * </ul>
     */
    public WordReader() {
        this(512, SplitStrategy.PARAGRAPH, 50, true, false, TableFormat.MARKDOWN);
    }

    @Override
    public Mono<List<Document>> read(ReaderInput input) {
        if (input == null) {
            return Mono.error(new ReaderException("Input cannot be null"));
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                String wordPath = input.asString();
                                List<ContentBlock> blocks = getDataBlocks(wordPath);
                                String docId = ReaderUtils.generateDocIdMD5(wordPath);

                                List<Document> documents = new ArrayList<>();

                                // Process each block
                                for (ContentBlock block : blocks) {
                                    if (block instanceof TextBlock) {
                                        // Chunk text blocks using TextChunker
                                        String text = ((TextBlock) block).getText();
                                        List<String> chunks =
                                                TextChunker.chunkText(
                                                        text,
                                                        chunkSize,
                                                        splitStrategy,
                                                        overlapSize);

                                        for (String chunk : chunks) {
                                            TextBlock contentBlock =
                                                    TextBlock.builder().text(chunk).build();
                                            DocumentMetadata metadata =
                                                    new DocumentMetadata(
                                                            contentBlock,
                                                            docId,
                                                            "0"); // Temporary, will be reset
                                            documents.add(new Document(metadata));
                                        }
                                    } else if (block instanceof ImageBlock) {
                                        // Image blocks are added as-is
                                        DocumentMetadata metadata =
                                                new DocumentMetadata(
                                                        block, docId,
                                                        "0"); // Temporary, will be reset
                                        documents.add(new Document(metadata));
                                    }
                                }

                                // Reset chunk_id for all documents
                                List<Document> finalDocuments = new ArrayList<>();
                                for (int i = 0; i < documents.size(); i++) {
                                    Document doc = documents.get(i);
                                    DocumentMetadata oldMetadata = doc.getMetadata();
                                    DocumentMetadata newMetadata =
                                            new DocumentMetadata(
                                                    oldMetadata.getContent(),
                                                    oldMetadata.getDocId(),
                                                    String.valueOf(i));
                                    finalDocuments.add(new Document(newMetadata));
                                }

                                return finalDocuments;
                            } catch (Exception e) {
                                throw new ReaderException(
                                        "Failed to read Word document from: " + input, e);
                            }
                        })
                .onErrorMap(ReaderException.class, e -> e);
    }

    @Override
    public List<String> getSupportedFormats() {
        return List.of("doc", "docx");
    }

    /**
     * Extracts content blocks (text, tables, images) from a Word document.
     *
     * <p>This method processes the document sequentially and determines when to merge
     * or separate blocks based on the configuration.
     *
     * @param wordPath the path to the Word document file
     * @return a list of ContentBlock objects (TextBlock or ImageBlock)
     * @throws IOException if the file cannot be read
     * @throws ReaderException if Word document parsing fails
     */
    private List<ContentBlock> getDataBlocks(String wordPath) throws IOException, ReaderException {
        Path path = Paths.get(wordPath);
        if (!Files.exists(path)) {
            throw new ReaderException("Word document file does not exist: " + wordPath);
        }

        try (FileInputStream fis = new FileInputStream(wordPath);
                XWPFDocument doc = new XWPFDocument(fis)) {

            List<ContentBlock> blocks = new ArrayList<>();
            String lastType = null;

            for (IBodyElement element : doc.getBodyElements()) {
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    XWPFParagraph para = (XWPFParagraph) element;

                    // Extract images if enabled
                    if (includeImage) {
                        List<ImageBlock> imageBlocks = extractImageData(para);
                        if (!imageBlocks.isEmpty()) {
                            blocks.addAll(imageBlocks);
                            lastType = "image";
                        }
                    }

                    // Extract text
                    String text = extractTextFromParagraph(para);
                    if (text != null && !text.isEmpty()) {
                        // Merging logic based on lastType and separateTable
                        if ("text".equals(lastType)
                                || ("table".equals(lastType) && !separateTable)) {
                            // Append to last text block
                            TextBlock lastBlock = (TextBlock) blocks.get(blocks.size() - 1);
                            TextBlock mergedBlock =
                                    TextBlock.builder()
                                            .text(lastBlock.getText() + "\n" + text)
                                            .build();
                            blocks.set(blocks.size() - 1, mergedBlock);
                        } else {
                            // Create new text block
                            blocks.add(TextBlock.builder().text(text).build());
                        }
                        lastType = "text";
                    }

                } else if (element.getElementType() == BodyElementType.TABLE) {
                    XWPFTable table = (XWPFTable) element;

                    // Extract table data
                    List<List<String>> tableData = extractTableData(table);
                    String tableText =
                            (tableFormat == TableFormat.MARKDOWN)
                                    ? tableToMarkdown(tableData)
                                    : tableToJson(tableData);

                    // Merging logic
                    if (!separateTable && ("text".equals(lastType) || "table".equals(lastType))) {
                        // Append to last text block
                        TextBlock lastBlock = (TextBlock) blocks.get(blocks.size() - 1);
                        TextBlock mergedBlock =
                                TextBlock.builder()
                                        .text(lastBlock.getText() + "\n" + tableText)
                                        .build();
                        blocks.set(blocks.size() - 1, mergedBlock);
                    } else {
                        // Create new text block
                        blocks.add(TextBlock.builder().text(tableText).build());
                    }
                    lastType = "table";
                }
            }

            return blocks;
        } catch (IOException e) {
            throw new ReaderException("Failed to parse Word document: " + wordPath, e);
        }
    }

    /**
     * Extracts text from a paragraph.
     *
     * <p>This implementation uses the standard POI API to extract text.
     * Advanced text extraction (e.g., from text boxes) would require XML parsing.
     *
     * @param para the paragraph to extract text from
     * @return the extracted text, or empty string if no text found
     */
    private String extractTextFromParagraph(XWPFParagraph para) {
        if (para == null) {
            return "";
        }

        // Method 1: Use standard API
        String text = para.getText();
        if (text != null && !text.isEmpty()) {
            return text.trim();
        }

        // Method 2: Extract from runs (handles some special formatting)
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : para.getRuns()) {
            String runText = run.getText(0);
            if (runText != null) {
                sb.append(runText);
            }
        }

        return sb.toString().trim();
    }

    /**
     * Extracts table data as a 2D list of strings.
     *
     * @param table the table to extract
     * @return 2D list where each inner list represents a row of cells
     */
    private List<List<String>> extractTableData(XWPFTable table) {
        List<List<String>> tableData = new ArrayList<>();

        for (XWPFTableRow row : table.getRows()) {
            List<String> rowData = new ArrayList<>();

            for (XWPFTableCell cell : row.getTableCells()) {
                // Extract all paragraphs within the cell, joined by newlines
                StringBuilder cellText = new StringBuilder();
                for (XWPFParagraph para : cell.getParagraphs()) {
                    String paraText = para.getText();
                    if (paraText != null && !paraText.isEmpty()) {
                        if (cellText.length() > 0) {
                            cellText.append("\n");
                        }
                        cellText.append(paraText);
                    }
                }
                rowData.add(cellText.toString());
            }

            tableData.add(rowData);
        }

        return tableData;
    }

    /**
     * Converts table data to Markdown format.
     *
     * @param tableData the 2D table data
     * @return Markdown-formatted table string
     */
    private String tableToMarkdown(List<List<String>> tableData) {
        if (tableData == null || tableData.isEmpty()) {
            return "";
        }

        StringBuilder md = new StringBuilder();
        int numCols = tableData.get(0).size();

        // Header row
        md.append("| ");
        md.append(String.join(" | ", tableData.get(0)));
        md.append(" |\n");

        // Separator row
        md.append("| ");
        for (int i = 0; i < numCols; i++) {
            md.append("---");
            if (i < numCols - 1) {
                md.append(" | ");
            }
        }
        md.append(" |\n");

        // Data rows
        for (int i = 1; i < tableData.size(); i++) {
            md.append("| ");
            md.append(String.join(" | ", tableData.get(i)));
            md.append(" |\n");
        }

        return md.toString();
    }

    /**
     * Converts table data to JSON format.
     *
     * @param tableData the 2D table data
     * @return JSON-formatted table string
     */
    private String tableToJson(List<List<String>> tableData) {
        if (tableData == null || tableData.isEmpty()) {
            return "";
        }

        StringBuilder json = new StringBuilder();
        json.append("<system-info>A table loaded as a JSON array:</system-info>\n");

        for (List<String> row : tableData) {
            try {
                json.append(JsonUtils.getJsonCodec().toJson(row));
                json.append("\n");
            } catch (JsonException e) {
                // Fallback: manual JSON construction
                json.append("[");
                for (int i = 0; i < row.size(); i++) {
                    json.append("\"").append(escapeJson(row.get(i))).append("\"");
                    if (i < row.size() - 1) {
                        json.append(", ");
                    }
                }
                json.append("]\n");
            }
        }

        return json.toString();
    }

    /**
     * Escapes special characters for JSON strings.
     *
     * @param str the string to escape
     * @return escaped string
     */
    private String escapeJson(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /**
     * Extracts images from a paragraph as Base64-encoded ImageBlocks.
     *
     * @param para the paragraph to extract images from
     * @return list of ImageBlock objects with Base64-encoded image data
     */
    private List<ImageBlock> extractImageData(XWPFParagraph para) {
        List<ImageBlock> images = new ArrayList<>();

        if (para == null) {
            return images;
        }

        for (XWPFRun run : para.getRuns()) {
            List<XWPFPicture> pictures = run.getEmbeddedPictures();

            for (XWPFPicture picture : pictures) {
                try {
                    byte[] imageData = picture.getPictureData().getData();
                    String base64Data = Base64.getEncoder().encodeToString(imageData);
                    // Use getPictureType() to determine media type
                    int pictureType = picture.getPictureData().getPictureType();
                    String mediaType = getMediaTypeFromPictureType(pictureType);

                    ImageBlock imageBlock =
                            ImageBlock.builder()
                                    .source(
                                            Base64Source.builder()
                                                    .data(base64Data)
                                                    .mediaType(mediaType)
                                                    .build())
                                    .build();

                    images.add(imageBlock);
                } catch (Exception e) {
                    // Log error and continue (don't fail entire document for one image)
                    System.err.println("Failed to extract image: " + e.getMessage());
                }
            }
        }

        return images;
    }

    /**
     * Converts Apache POI picture type to media type string.
     *
     * @param pictureType the POI picture type constant
     * @return corresponding media type string
     */
    private String getMediaTypeFromPictureType(int pictureType) {
        // POI picture type constants from org.apache.poi.xwpf.usermodel.Document
        // XWPFDocument.PICTURE_TYPE_*
        return switch (pictureType) {
            case 2 -> "image/emf"; // EMF
            case 3 -> "image/wmf"; // WMF
            case 4 -> "image/x-pict"; // PICT
            case 5 -> "image/jpeg"; // JPEG
            case 6 -> "image/png"; // PNG
            case 7 -> "image/x-dib"; // DIB
            case 8 -> "image/gif"; // GIF
            case 9 -> "image/tiff"; // TIFF
            case 10 -> "image/x-eps"; // EPS
            case 11 -> "image/bmp"; // BMP
            case 12 -> "image/x-wpg"; // WPG
            default -> "application/octet-stream"; // Unknown
        };
    }

    /**
     * Checks if image extraction is enabled.
     *
     * @return true if images are extracted
     */
    public boolean isIncludeImage() {
        return includeImage;
    }

    /**
     * Checks if tables are separated into individual chunks.
     *
     * @return true if tables are separated
     */
    public boolean isSeparateTable() {
        return separateTable;
    }

    /**
     * Gets the table format.
     *
     * @return the table format (MARKDOWN or JSON)
     */
    public TableFormat getTableFormat() {
        return tableFormat;
    }
}
