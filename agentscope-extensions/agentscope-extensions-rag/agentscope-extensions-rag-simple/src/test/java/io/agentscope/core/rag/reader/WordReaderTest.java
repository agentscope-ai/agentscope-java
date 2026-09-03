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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.ReaderException;
import io.agentscope.core.rag.model.Document;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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

    @TempDir Path tempDir;

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
    @DisplayName("Should preserve blank paragraphs as paragraph boundaries when chunking")
    void testBlankParagraphsPreserveChunkBoundaries() throws IOException {
        // Each paragraph is long enough that any two of them exceed the chunk size,
        // so every paragraph must end up in its own chunk when boundaries are detected.
        // WordReader trims each paragraph's trailing whitespace, so expect trimmed text.
        String paragraph1 = "First travel policy paragraph. ".repeat(7).trim();
        String paragraph2 = "Second travel policy paragraph. ".repeat(7).trim();
        String paragraph3 = "Third travel policy paragraph. ".repeat(7).trim();

        Path file = tempDir.resolve("blank-paragraphs.docx");
        try (XWPFDocument doc = new XWPFDocument();
                FileOutputStream fos = new FileOutputStream(file.toFile())) {
            doc.createParagraph().createRun().setText(paragraph1);
            doc.createParagraph(); // blank paragraph (empty line in Word)
            doc.createParagraph().createRun().setText(paragraph2);
            doc.createParagraph(); // blank paragraph (empty line in Word)
            doc.createParagraph().createRun().setText(paragraph3);
            doc.write(fos);
        }

        WordReader reader =
                new WordReader(300, SplitStrategy.PARAGRAPH, 0, true, true, TableFormat.MARKDOWN);
        List<Document> documents = reader.read(ReaderInput.fromString(file.toString())).block();

        assertEquals(3, documents.size());
        assertEquals(
                paragraph1, ((TextBlock) documents.get(0).getMetadata().getContent()).getText());
        assertEquals(
                paragraph2, ((TextBlock) documents.get(1).getMetadata().getContent()).getText());
        assertEquals(
                paragraph3, ((TextBlock) documents.get(2).getMetadata().getContent()).getText());
    }

    @Test
    @DisplayName("Should treat a paragraph containing only properties as a blank boundary")
    void testParagraphPropertiesOnlyPreserveChunkBoundary() throws IOException {
        String paragraph1 = "First travel policy paragraph. ".repeat(7).trim();
        String paragraph2 = "Second travel policy paragraph. ".repeat(7).trim();

        Path file = tempDir.resolve("paragraph-properties-only.docx");
        try (XWPFDocument doc = new XWPFDocument();
                FileOutputStream fos = new FileOutputStream(file.toFile())) {
            doc.createParagraph().createRun().setText(paragraph1);
            doc.createParagraph().getCTP().addNewPPr();
            doc.createParagraph().createRun().setText(paragraph2);
            doc.write(fos);
        }

        WordReader reader =
                new WordReader(300, SplitStrategy.PARAGRAPH, 0, true, true, TableFormat.MARKDOWN);
        List<Document> documents = reader.read(ReaderInput.fromString(file.toString())).block();

        assertEquals(2, documents.size());
        assertEquals(
                paragraph1, ((TextBlock) documents.get(0).getMetadata().getContent()).getText());
        assertEquals(
                paragraph2, ((TextBlock) documents.get(1).getMetadata().getContent()).getText());
    }

    @Test
    @DisplayName("Should join adjacent paragraphs without blank line using a single newline")
    void testAdjacentParagraphsJoinedWithSingleNewline() throws IOException {
        String paragraph1 = "First short paragraph. ".repeat(4).trim();
        String paragraph2 = "Second short paragraph. ".repeat(4).trim();

        Path file = tempDir.resolve("adjacent-paragraphs.docx");
        try (XWPFDocument doc = new XWPFDocument();
                FileOutputStream fos = new FileOutputStream(file.toFile())) {
            doc.createParagraph().createRun().setText(paragraph1);
            doc.createParagraph().createRun().setText(paragraph2);
            doc.write(fos);
        }

        WordReader reader =
                new WordReader(300, SplitStrategy.PARAGRAPH, 0, true, true, TableFormat.MARKDOWN);
        List<Document> documents = reader.read(ReaderInput.fromString(file.toString())).block();

        assertEquals(1, documents.size());
        assertEquals(
                paragraph1 + "\n" + paragraph2,
                ((TextBlock) documents.get(0).getMetadata().getContent()).getText());
    }

    @Test
    @DisplayName("Should not treat a break-only paragraph as a blank paragraph boundary")
    void testBreakOnlyParagraphDoesNotCreateBlankParagraphBoundary() throws IOException {
        String paragraph1 = "First short paragraph. ".repeat(4).trim();
        String paragraph2 = "Second short paragraph. ".repeat(4).trim();

        Path file = tempDir.resolve("break-only-paragraph.docx");
        try (XWPFDocument doc = new XWPFDocument();
                FileOutputStream fos = new FileOutputStream(file.toFile())) {
            doc.createParagraph().createRun().setText(paragraph1);
            doc.createParagraph().createRun().addBreak();
            doc.createParagraph().createRun().setText(paragraph2);
            doc.write(fos);
        }

        WordReader reader =
                new WordReader(300, SplitStrategy.PARAGRAPH, 0, true, true, TableFormat.MARKDOWN);
        List<Document> documents = reader.read(ReaderInput.fromString(file.toString())).block();

        assertEquals(1, documents.size());
        assertEquals(
                paragraph1 + "\n" + paragraph2,
                ((TextBlock) documents.get(0).getMetadata().getContent()).getText());
    }

    @Test
    @DisplayName("Should return no documents for a document containing only blank paragraphs")
    void testDocumentWithOnlyBlankParagraphs() throws IOException {
        Path file = tempDir.resolve("only-blank-paragraphs.docx");
        try (XWPFDocument doc = new XWPFDocument();
                FileOutputStream fos = new FileOutputStream(file.toFile())) {
            doc.createParagraph();
            doc.createParagraph();
            doc.write(fos);
        }

        WordReader reader = new WordReader();
        List<Document> documents = reader.read(ReaderInput.fromString(file.toString())).block();

        assertTrue(documents.isEmpty());
    }

    @Test
    @DisplayName("Should preserve blank line between a merged table and the following paragraph")
    void testBlankParagraphBetweenTableAndText() throws IOException {
        String paragraph = "Travel policy paragraph after the table. ".repeat(5).trim();
        String tableMarkdown = "| Header |\n| --- |\n";

        Path file = tempDir.resolve("table-blank-paragraph.docx");
        try (XWPFDocument doc = new XWPFDocument();
                FileOutputStream fos = new FileOutputStream(file.toFile())) {
            doc.createTable(1, 1).getRow(0).getCell(0).setText("Header");
            doc.createParagraph(); // blank paragraph (empty line in Word)
            doc.createParagraph().createRun().setText(paragraph);
            doc.write(fos);
        }

        // separateTable=false: the table is merged into the text block, and the blank
        // paragraph must be preserved as "\n\n" so the table/paragraph boundary is
        // detectable by paragraph-based chunking
        WordReader mergedReader =
                new WordReader(300, SplitStrategy.PARAGRAPH, 0, true, false, TableFormat.MARKDOWN);
        List<Document> merged = mergedReader.read(ReaderInput.fromString(file.toString())).block();

        assertEquals(1, merged.size());
        assertEquals(
                tableMarkdown + "\n" + paragraph,
                ((TextBlock) merged.get(0).getMetadata().getContent()).getText());

        // separateTable=true: the table stays a separate chunk and the blank
        // paragraph must not introduce any extra newline
        WordReader separatedReader =
                new WordReader(300, SplitStrategy.PARAGRAPH, 0, true, true, TableFormat.MARKDOWN);
        List<Document> separated =
                separatedReader.read(ReaderInput.fromString(file.toString())).block();

        assertEquals(2, separated.size());
        // trailing newline of the table markdown is trimmed by paragraph chunking
        assertEquals(
                tableMarkdown.trim(),
                ((TextBlock) separated.get(0).getMetadata().getContent()).getText());
        assertEquals(
                paragraph, ((TextBlock) separated.get(1).getMetadata().getContent()).getText());
    }
}
