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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.rag.exception.ReaderException;
import io.agentscope.core.rag.model.Document;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

/**
 * Unit tests for WordReader.
 */
@Tag("unit")
@DisplayName("WordReader Unit Tests")
class WordReaderTest {

    @Test
    @DisplayName("Should create WordReader with default settings")
    void testDefaultConstructor() {
        WordReader reader = new WordReader();
        assertEquals(512, reader.getChunkSize());
        assertEquals(SplitStrategy.PARAGRAPH, reader.getSplitStrategy());
        assertEquals(50, reader.getOverlapSize());
        assertTrue(reader.isIncludeImage());
        assertFalse(reader.isSeparateTable());
        assertEquals(TableFormat.MARKDOWN, reader.getTableFormat());
    }

    @Test
    @DisplayName("Should create WordReader with custom settings")
    void testConstructorWithSettings() {
        WordReader reader =
                new WordReader(1024, SplitStrategy.TOKEN, 100, false, true, TableFormat.JSON);
        assertEquals(1024, reader.getChunkSize());
        assertEquals(SplitStrategy.TOKEN, reader.getSplitStrategy());
        assertEquals(100, reader.getOverlapSize());
        assertFalse(reader.isIncludeImage());
        assertTrue(reader.isSeparateTable());
        assertEquals(TableFormat.JSON, reader.getTableFormat());
    }

    @Test
    @DisplayName("Should throw exception for invalid chunk size")
    void testInvalidChunkSize() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WordReader(
                                0, SplitStrategy.PARAGRAPH, 50, true, false, TableFormat.MARKDOWN));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WordReader(
                                -1,
                                SplitStrategy.PARAGRAPH,
                                50,
                                true,
                                false,
                                TableFormat.MARKDOWN));
    }

    @Test
    @DisplayName("Should throw exception for null split strategy")
    void testNullSplitStrategy() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WordReader(512, null, 50, true, false, TableFormat.MARKDOWN));
    }

    @Test
    @DisplayName("Should throw exception for negative overlap size")
    void testNegativeOverlapSize() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WordReader(
                                512,
                                SplitStrategy.PARAGRAPH,
                                -1,
                                true,
                                false,
                                TableFormat.MARKDOWN));
    }

    @Test
    @DisplayName("Should throw exception for null table format")
    void testNullTableFormat() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WordReader(512, SplitStrategy.PARAGRAPH, 50, true, false, null));
    }

    @Test
    @DisplayName("Should return supported Word formats")
    void testGetSupportedFormats() {
        WordReader reader = new WordReader();
        assertEquals(List.of("doc", "docx"), reader.getSupportedFormats());
    }

    @Test
    @DisplayName("Should handle null input")
    void testNullInput() throws ReaderException {
        WordReader reader = new WordReader();

        StepVerifier.create(reader.read(null)).expectError(ReaderException.class).verify();
    }

    @Test
    @DisplayName("Should throw exception when Word file does not exist")
    void testNonExistentWordFile() throws ReaderException {
        WordReader reader = new WordReader();
        ReaderInput input = ReaderInput.fromString("/non/existent/file.docx");

        StepVerifier.create(reader.read(input)).expectError(ReaderException.class).verify();
    }

    @Test
    @DisplayName("Should keep a blank paragraph as a blank line")
    void testBlankParagraphIsPreservedAsBlankLine(@TempDir Path tempDir) throws Exception {
        Path docx =
                writeDocx(
                        tempDir.resolve("blank-line.docx"),
                        doc -> {
                            doc.createParagraph().createRun().setText("Paragraph one.");
                            doc.createParagraph(); // blank line: a single Enter in Word
                            doc.createParagraph().createRun().setText("Paragraph two.");
                        });

        assertEquals("Paragraph one.\n\nParagraph two.", readSingleChunk(new WordReader(), docx));
    }

    @Test
    @DisplayName("Should keep consecutive blank paragraphs as consecutive blank lines")
    void testConsecutiveBlankParagraphsArePreserved(@TempDir Path tempDir) throws Exception {
        Path docx =
                writeDocx(
                        tempDir.resolve("two-blank-lines.docx"),
                        doc -> {
                            doc.createParagraph().createRun().setText("Paragraph one.");
                            doc.createParagraph();
                            doc.createParagraph();
                            doc.createParagraph().createRun().setText("Paragraph two.");
                        });

        // CHARACTER keeps the extracted text verbatim; PARAGRAPH would collapse any run of
        // blank lines back to a single one when it re-joins the split paragraphs.
        WordReader reader =
                new WordReader(512, SplitStrategy.CHARACTER, 50, true, false, TableFormat.MARKDOWN);

        assertEquals("Paragraph one.\n\n\nParagraph two.", readSingleChunk(reader, docx));
    }

    @Test
    @DisplayName("Should escape special characters in Markdown table cells")
    void testMarkdownTableCellsAreEscaped(@TempDir Path tempDir) throws Exception {
        Path docx =
                writeDocx(
                        tempDir.resolve("table-cells.docx"),
                        doc -> {
                            XWPFTable table = doc.createTable(3, 2);
                            table.getRow(0).getCell(0).setText("A|B");
                            table.getRow(0).getCell(1).setText("Path \\| label");
                            table.getRow(1).getCell(0).setText("1|2");
                            table.getRow(1).getCell(1).setText("Line 1\nLine 2");
                            table.getRow(2).getCell(0).setText("plain");
                            table.getRow(2).getCell(1).setText("ok");
                        });
        WordReader reader =
                new WordReader(4096, SplitStrategy.CHARACTER, 0, false, true, TableFormat.MARKDOWN);

        String expected =
                "| A\\|B | Path \\\\\\| label |\n"
                        + "| --- | --- |\n"
                        + "| 1\\|2 | Line 1<br>Line 2 |\n"
                        + "| plain | ok |\n";
        assertEquals(expected, readSingleChunk(reader, docx));
    }

    @Test
    @DisplayName("Should pad irregular Markdown table rows to header column count")
    void testMarkdownTablePadsIrregularRows(@TempDir Path tempDir) throws Exception {
        Path docx =
                writeDocx(
                        tempDir.resolve("irregular-table.docx"),
                        doc -> {
                            XWPFTable table = doc.createTable(3, 3);
                            table.getRow(0).getCell(0).setText("A");
                            table.getRow(0).getCell(1).setText("B");
                            table.getRow(0).getCell(2).setText("C");
                            table.getRow(1).getCell(0).setText("d");
                            table.getRow(1).getCell(1).setText("e");
                            // Simulate a merged/irregular row with fewer cells than the header.
                            table.getRow(1).removeCell(2);
                            table.getRow(2).getCell(0).setText("f");
                            table.getRow(2).getCell(1).setText("g");
                            table.getRow(2).getCell(2).setText("h");
                        });
        WordReader reader =
                new WordReader(4096, SplitStrategy.CHARACTER, 0, false, true, TableFormat.MARKDOWN);

        String markdown = readSingleChunk(reader, docx);
        String expected =
                "| A | B | C |\n" + "| --- | --- | --- |\n" + "| d | e |  |\n" + "| f | g | h |\n";
        assertEquals(expected, markdown);

        String[] lines = markdown.split("\n", -1);
        // Trailing newline yields an empty final segment; ignore it.
        assertTrue(lines.length >= 4);
        int expectedCols = markdownTableColumnCount(lines[0]);
        assertEquals(3, expectedCols);
        assertEquals(expectedCols, markdownTableColumnCount(lines[1]), "delimiter columns");
        assertEquals(expectedCols, markdownTableColumnCount(lines[2]), "padded data row columns");
        assertEquals(expectedCols, markdownTableColumnCount(lines[3]), "full data row columns");
    }

    /** Counts GFM table columns from a Markdown row (pipe-delimited). */
    private static int markdownTableColumnCount(String row) {
        // "| A | B | C |" -> ["", " A ", " B ", " C ", ""]
        String[] parts = row.split("\\|", -1);
        assertTrue(parts.length >= 2, "row should be pipe-wrapped: " + row);
        return parts.length - 2;
    }

    /** Authors a .docx fixture in memory, so that no binary test resource is required. */
    private static Path writeDocx(Path target, Consumer<XWPFDocument> author) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            author.accept(doc);
            try (OutputStream out = Files.newOutputStream(target)) {
                doc.write(out);
            }
        }
        return target;
    }

    private static String readSingleChunk(WordReader reader, Path docx) {
        List<Document> docs = reader.read(ReaderInput.fromPath(docx)).block();
        assertNotNull(docs);
        assertEquals(1, docs.size(), "fixture is expected to fit into a single chunk");
        return docs.get(0).getMetadata().getContentText();
    }
}
