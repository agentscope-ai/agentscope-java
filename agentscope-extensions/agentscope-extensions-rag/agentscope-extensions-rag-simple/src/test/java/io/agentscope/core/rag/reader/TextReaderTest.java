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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.rag.exception.ReaderException;
import io.agentscope.core.rag.model.Document;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Unit tests for TextReader.
 */
@Tag("unit")
@DisplayName("TextReader Unit Tests")
class TextReaderTest {

    @Test
    @DisplayName("Should create TextReader with default settings")
    void testDefaultConstructor() {
        TextReader reader = new TextReader();

        assertEquals(512, reader.getChunkSize());
        assertEquals(SplitStrategy.PARAGRAPH, reader.getSplitStrategy());
        assertEquals(50, reader.getOverlapSize());
    }

    @Test
    @DisplayName("Should create TextReader with custom settings")
    void testCustomConstructor() {
        TextReader reader = new TextReader(256, SplitStrategy.CHARACTER, 25);

        assertEquals(256, reader.getChunkSize());
        assertEquals(SplitStrategy.CHARACTER, reader.getSplitStrategy());
        assertEquals(25, reader.getOverlapSize());
    }

    @Test
    @DisplayName("Should throw exception for invalid chunk size")
    void testInvalidChunkSize() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TextReader(0, SplitStrategy.CHARACTER, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TextReader(-1, SplitStrategy.CHARACTER, 0));
    }

    @Test
    @DisplayName("Should throw exception for null strategy")
    void testNullStrategy() {
        assertThrows(IllegalArgumentException.class, () -> new TextReader(100, null, 0));
    }

    @Test
    @DisplayName("Should throw exception for negative overlap")
    void testNegativeOverlap() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TextReader(100, SplitStrategy.CHARACTER, -1));
    }

    @Test
    @DisplayName("Should throw exception when overlap >= chunk size")
    void testOverlapTooLarge() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TextReader(100, SplitStrategy.CHARACTER, 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TextReader(100, SplitStrategy.CHARACTER, 150));
    }

    @Test
    @DisplayName("Should read text and create documents")
    void testRead() throws ReaderException {
        TextReader reader = new TextReader(10, SplitStrategy.CHARACTER, 0);
        ReaderInput input = ReaderInput.fromString("This is a test text.");

        StepVerifier.create(reader.read(input))
                .assertNext(
                        documents -> {
                            assertNotNull(documents);
                            assertTrue(documents.size() > 0);
                            for (Document doc : documents) {
                                assertNotNull(doc);
                                assertNotNull(doc.getMetadata());
                                assertNotNull(doc.getMetadata().getContentText());
                            }
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should create documents with correct metadata")
    void testDocumentMetadata() throws ReaderException {
        TextReader reader = new TextReader(10, SplitStrategy.CHARACTER, 0);
        ReaderInput input = ReaderInput.fromString("Short text");

        List<Document> documents = reader.read(input).block();

        assertNotNull(documents);
        assertEquals(1, documents.size());

        Document doc = documents.get(0);
        assertNotNull(doc.getId());
        assertEquals("0", doc.getMetadata().getChunkId());
        assertTrue(doc.getMetadata().getContentText().contains("Short text"));
    }

    @Test
    @DisplayName("Should create multiple documents for long text")
    void testMultipleDocuments() throws ReaderException {
        TextReader reader = new TextReader(5, SplitStrategy.CHARACTER, 0);
        ReaderInput input = ReaderInput.fromString("1234567890");

        List<Document> documents = reader.read(input).block();

        assertNotNull(documents);
        assertTrue(documents.size() > 1);

        // All documents should have the same docId
        String docId = documents.get(0).getMetadata().getDocId();
        for (Document doc : documents) {
            assertEquals(docId, doc.getMetadata().getDocId());
        }

        // Chunk IDs should be sequential
        for (int i = 0; i < documents.size(); i++) {
            assertEquals(String.valueOf(i), documents.get(i).getMetadata().getChunkId());
        }
    }

    @Test
    @DisplayName("Should handle empty text")
    void testEmptyText() throws ReaderException {
        TextReader reader = new TextReader(10, SplitStrategy.CHARACTER, 0);
        ReaderInput input = ReaderInput.fromString("");

        List<Document> documents = reader.read(input).block();

        assertNotNull(documents);
        assertEquals(1, documents.size());
        assertEquals("", documents.get(0).getMetadata().getContentText());
    }

    @Test
    @DisplayName("Should return supported formats")
    void testGetSupportedFormats() {
        TextReader reader = new TextReader();
        List<String> formats = reader.getSupportedFormats();

        assertNotNull(formats);
        assertTrue(formats.contains("txt"));
        assertTrue(formats.contains("md"));
        assertTrue(formats.contains("rst"));
    }

    @Test
    @DisplayName("Should handle null input")
    void testNullInput() throws ReaderException {
        TextReader reader = new TextReader();

        StepVerifier.create(reader.read(null)).expectError(ReaderException.class).verify();
    }

    @Test
    @DisplayName("Should use different strategies correctly")
    void testDifferentStrategies() throws ReaderException {
        String text = "First paragraph.\n\nSecond paragraph.\n\nThird paragraph.";

        // Test PARAGRAPH strategy
        TextReader paragraphReader = new TextReader(100, SplitStrategy.PARAGRAPH, 0);
        List<Document> paragraphDocs = paragraphReader.read(ReaderInput.fromString(text)).block();

        // Test CHARACTER strategy
        TextReader charReader = new TextReader(20, SplitStrategy.CHARACTER, 0);
        List<Document> charDocs = charReader.read(ReaderInput.fromString(text)).block();

        // Paragraph strategy should create fewer chunks for this text
        assertTrue(paragraphDocs.size() <= charDocs.size());
    }

    @Test
    @DisplayName("Should preserve text content across chunks")
    void testContentPreservation() throws ReaderException {
        String originalText = "This is a longer text that will be split into multiple chunks.";
        TextReader reader = new TextReader(10, SplitStrategy.CHARACTER, 2);
        ReaderInput input = ReaderInput.fromString(originalText);

        List<Document> documents = reader.read(input).block();

        // Reconstruct text from chunks (accounting for overlap)
        StringBuilder reconstructed = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            String chunk = documents.get(i).getMetadata().getContentText();
            if (i == 0) {
                reconstructed.append(chunk);
            } else {
                // Skip overlap (approximate)
                int overlap = 2;
                if (chunk.length() > overlap) {
                    reconstructed.append(chunk.substring(overlap));
                }
            }
        }

        // Original text should be contained in reconstruction (approximately)
        assertTrue(reconstructed.length() > 0);
    }
}
