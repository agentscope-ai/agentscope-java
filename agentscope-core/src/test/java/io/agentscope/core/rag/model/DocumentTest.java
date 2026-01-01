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
package io.agentscope.core.rag.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Document.
 */
@Tag("unit")
@DisplayName("Document Unit Tests")
class DocumentTest {

    @Test
    @DisplayName("Should create Document with metadata")
    void testCreateDocument() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");

        Document document = new Document(metadata);

        assertNotNull(document.getId());
        assertEquals(metadata, document.getMetadata());
        assertNull(document.getEmbedding());
        assertNull(document.getScore());
    }

    @Test
    @DisplayName("Should throw exception when metadata is null")
    void testCreateDocumentNullMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new Document(null));
    }

    @Test
    @DisplayName("Should generate consistent ID for same content")
    void testDocumentIdConsistency() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        DocumentMetadata metadata1 = new DocumentMetadata(content, "doc-1", "0");
        DocumentMetadata metadata2 = new DocumentMetadata(content, "doc-1", "0");

        Document doc1 = new Document(metadata1);
        Document doc2 = new Document(metadata2);

        assertEquals(doc1.getId(), doc2.getId());
    }

    @Test
    @DisplayName("Should generate different IDs for different content")
    void testDocumentIdUniqueness() {
        TextBlock content1 = TextBlock.builder().text("Test content 1").build();
        TextBlock content2 = TextBlock.builder().text("Test content 2").build();
        DocumentMetadata metadata1 = new DocumentMetadata(content1, "doc-1", "0");
        DocumentMetadata metadata2 = new DocumentMetadata(content2, "doc-1", "0");

        Document doc1 = new Document(metadata1);
        Document doc2 = new Document(metadata2);

        // IDs should be different for different content
        // UUIDs are deterministic based on content, so different content = different IDs
        assertEquals(false, doc1.getId().equals(doc2.getId()));
    }

    @Test
    @DisplayName("Should set and get embedding")
    void testSetGetEmbedding() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");
        Document document = new Document(metadata);

        double[] embedding = new double[] {0.1, 0.2, 0.3};
        document.setEmbedding(embedding);

        assertEquals(embedding, document.getEmbedding());
    }

    @Test
    @DisplayName("Should set and get score")
    void testSetGetScore() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");
        Document document = new Document(metadata);

        Double score = 0.95;
        document.setScore(score);

        assertEquals(score, document.getScore());
    }

    @Test
    @DisplayName("Should generate valid UUID ID")
    void testDocumentIdFormat() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");
        Document document = new Document(metadata);

        // UUID format: 8-4-4-4-12 characters
        assertNotNull(document.getId());
        assertEquals(
                true,
                document.getId()
                        .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    @Test
    @DisplayName("Should format toString correctly")
    void testToString() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");
        Document document = new Document(metadata);
        document.setScore(0.95);

        String str = document.toString();
        assertEquals(true, str.contains("Document"));
        assertEquals(true, str.contains("Test content"));
        assertEquals(true, str.contains("0.950"));
    }
}
