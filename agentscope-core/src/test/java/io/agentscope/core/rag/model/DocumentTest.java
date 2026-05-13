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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import java.util.HashMap;
import java.util.Map;
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
    @DisplayName("Should set and get vector name")
    void testSetGetVectorName() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");
        Document document = new Document(metadata);

        String vectorName = "test-vector";
        document.setVectorName(vectorName);

        assertEquals(vectorName, document.getVectorName());
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
        document.setVectorName("test-vector");

        String str = document.toString();
        assertTrue(str.contains("Document"));
        assertTrue(str.contains("Test content"));
        assertTrue(str.contains("0.950"));
        assertTrue(str.contains("test-vector"));
    }

    // ==================== Payload Tests ====================

    @Test
    @DisplayName("Should access payload through Document")
    void testDocumentGetPayload() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        Map<String, Object> payload = new HashMap<>();
        payload.put("filename", "document.pdf");
        payload.put("author", "Alice");

        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0", payload);
        Document document = new Document(metadata);

        assertNotNull(document.getPayload());
        assertEquals(2, document.getPayload().size());
        assertEquals("document.pdf", document.getPayload().get("filename"));
        assertEquals("Alice", document.getPayload().get("author"));
    }

    @Test
    @DisplayName("Should get payload value by key through Document")
    void testDocumentGetPayloadValue() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        Map<String, Object> payload = new HashMap<>();
        payload.put("department", "Engineering");
        payload.put("priority", 1);

        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0", payload);
        Document document = new Document(metadata);

        assertEquals("Engineering", document.getPayloadValue("department"));
        assertEquals(1, document.getPayloadValue("priority"));
        assertNull(document.getPayloadValue("nonExistent"));
    }

    @Test
    @DisplayName("Should check payload key existence through Document")
    void testDocumentHasPayloadKey() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "active");

        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0", payload);
        Document document = new Document(metadata);

        assertTrue(document.hasPayloadKey("status"));
        assertFalse(document.hasPayloadKey("nonExistent"));
    }

    @Test
    @DisplayName("Should handle empty payload in Document")
    void testDocumentEmptyPayload() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");
        Document document = new Document(metadata);

        assertNotNull(document.getPayload());
        assertTrue(document.getPayload().isEmpty());
        assertNull(document.getPayloadValue("anyKey"));
        assertFalse(document.hasPayloadKey("anyKey"));
    }

    @Test
    @DisplayName("Should generate same ID for documents with same content but different payload")
    void testDocumentIdWithPayload() {
        TextBlock content = TextBlock.builder().text("Test content").build();

        Map<String, Object> payload1 = new HashMap<>();
        payload1.put("key1", "value1");

        Map<String, Object> payload2 = new HashMap<>();
        payload2.put("key2", "value2");

        DocumentMetadata metadata1 = new DocumentMetadata(content, "doc-1", "0", payload1);
        DocumentMetadata metadata2 = new DocumentMetadata(content, "doc-1", "0", payload2);

        Document doc1 = new Document(metadata1);
        Document doc2 = new Document(metadata2);

        // ID should be the same because it's based on content, docId, chunkId (not payload)
        assertEquals(doc1.getId(), doc2.getId());

        // But payload should be different
        assertEquals("value1", doc1.getPayloadValue("key1"));
        assertEquals("value2", doc2.getPayloadValue("key2"));
    }

    @Test
    @DisplayName("Should create Document with Builder pattern including payload")
    void testDocumentWithBuilderAndPayload() {
        TextBlock content = TextBlock.builder().text("Test content").build();

        DocumentMetadata metadata =
                DocumentMetadata.builder()
                        .content(content)
                        .docId("doc-1")
                        .chunkId("0")
                        .addPayload("filename", "test.txt")
                        .addPayload("size", 2048)
                        .build();

        Document document = new Document(metadata);

        assertNotNull(document.getId());
        assertEquals("test.txt", document.getPayloadValue("filename"));
        assertEquals(2048, document.getPayloadValue("size"));
    }
}
