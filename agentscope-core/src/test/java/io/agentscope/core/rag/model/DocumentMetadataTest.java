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

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DocumentMetadata.
 */
@Tag("unit")
@DisplayName("DocumentMetadata Unit Tests")
class DocumentMetadataTest {

    @Test
    @DisplayName("Should create DocumentMetadata with valid parameters")
    void testCreateMetadata() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        String docId = "doc-1";
        String chunkId = "0";

        DocumentMetadata metadata = new DocumentMetadata(content, docId, chunkId);

        assertEquals(content, metadata.getContent());
        assertEquals(docId, metadata.getDocId());
        assertEquals(chunkId, metadata.getChunkId());
        assertEquals("Test content", metadata.getContentText());
    }

    @Test
    @DisplayName("Should throw exception when content is null")
    void testCreateMetadataNullContent() {
        assertThrows(
                IllegalArgumentException.class, () -> new DocumentMetadata(null, "doc-1", "0"));
    }

    @Test
    @DisplayName("Should throw exception when docId is null")
    void testCreateMetadataNullDocId() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        assertThrows(
                IllegalArgumentException.class, () -> new DocumentMetadata(content, null, "0"));
    }

    @Test
    @DisplayName("Should throw exception when chunkId is null")
    void testCreateMetadataNullChunkId() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        assertThrows(
                IllegalArgumentException.class, () -> new DocumentMetadata(content, "doc-1", null));
    }

    @Test
    @DisplayName("Should return text from ImageBlock toString when image content")
    void testGetContentTextFromImage() {
        URLSource source = URLSource.builder().url("https://example.com/image.jpg").build();
        ImageBlock content = ImageBlock.builder().source(source).build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");

        // ImageBlock.toString() returns the string representation
        String contentText = metadata.getContentText();
        assertEquals(content.toString(), contentText);
    }

    @Test
    @DisplayName("Should handle multiple chunks correctly")
    void testMultipleChunks() {
        TextBlock content1 = TextBlock.builder().text("Chunk 1").build();
        TextBlock content2 = TextBlock.builder().text("Chunk 2").build();
        TextBlock content3 = TextBlock.builder().text("Chunk 3").build();

        DocumentMetadata metadata1 = new DocumentMetadata(content1, "doc-1", "0");
        DocumentMetadata metadata2 = new DocumentMetadata(content2, "doc-1", "1");
        DocumentMetadata metadata3 = new DocumentMetadata(content3, "doc-1", "2");

        assertEquals("0", metadata1.getChunkId());
        assertEquals("1", metadata2.getChunkId());
        assertEquals("2", metadata3.getChunkId());
    }

    // ==================== Payload Tests ====================

    @Test
    @DisplayName("Should create DocumentMetadata with custom payload")
    void testCreateMetadataWithPayload() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        Map<String, Object> payload = new HashMap<>();
        payload.put("filename", "report.pdf");
        payload.put("department", "Finance");
        payload.put("author", "John Doe");

        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0", payload);

        assertNotNull(metadata.getPayload());
        assertEquals(3, metadata.getPayload().size());
        assertEquals("report.pdf", metadata.getPayloadValue("filename"));
        assertEquals("Finance", metadata.getPayloadValue("department"));
        assertEquals("John Doe", metadata.getPayloadValue("author"));
    }

    @Test
    @DisplayName("Should handle null payload gracefully")
    void testCreateMetadataWithNullPayload() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0", null);

        assertNotNull(metadata.getPayload());
        assertTrue(metadata.getPayload().isEmpty());
    }

    @Test
    @DisplayName("Should return immutable payload map")
    void testPayloadImmutability() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        Map<String, Object> payload = new HashMap<>();
        payload.put("key1", "value1");

        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0", payload);
        Map<String, Object> retrievedPayload = metadata.getPayload();

        // Try to modify the retrieved payload - should throw exception
        assertThrows(
                UnsupportedOperationException.class, () -> retrievedPayload.put("key2", "value2"));
    }

    @Test
    @DisplayName("Should get payload value by key")
    void testGetPayloadValue() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        Map<String, Object> payload = new HashMap<>();
        payload.put("filename", "document.txt");
        payload.put("size", 1024);
        payload.put("tags", Arrays.asList("important", "urgent"));

        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0", payload);

        assertEquals("document.txt", metadata.getPayloadValue("filename"));
        assertEquals(1024, metadata.getPayloadValue("size"));
        assertEquals(Arrays.asList("important", "urgent"), metadata.getPayloadValue("tags"));
    }

    @Test
    @DisplayName("Should return null for non-existent payload key")
    void testGetPayloadValueNonExistent() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        Map<String, Object> payload = new HashMap<>();
        payload.put("key1", "value1");

        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0", payload);

        assertNull(metadata.getPayloadValue("nonExistentKey"));
    }

    @Test
    @DisplayName("Should check if payload key exists")
    void testHasPayloadKey() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        Map<String, Object> payload = new HashMap<>();
        payload.put("filename", "test.pdf");
        payload.put("author", "Alice");

        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0", payload);

        assertTrue(metadata.hasPayloadKey("filename"));
        assertTrue(metadata.hasPayloadKey("author"));
        assertFalse(metadata.hasPayloadKey("nonExistent"));
    }

    @Test
    @DisplayName("Should throw exception when checking null key")
    void testHasPayloadKeyNull() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");

        assertThrows(NullPointerException.class, () -> metadata.hasPayloadKey(null));
    }

    @Test
    @DisplayName("Should build DocumentMetadata with Builder pattern")
    void testBuilderPattern() {
        TextBlock content = TextBlock.builder().text("Test content").build();

        DocumentMetadata metadata =
                DocumentMetadata.builder()
                        .content(content)
                        .docId("doc-1")
                        .chunkId("0")
                        .addPayload("filename", "report.pdf")
                        .addPayload("department", "HR")
                        .addPayload("priority", 5)
                        .build();

        assertNotNull(metadata);
        assertEquals(content, metadata.getContent());
        assertEquals("doc-1", metadata.getDocId());
        assertEquals("0", metadata.getChunkId());
        assertEquals(3, metadata.getPayload().size());
        assertEquals("report.pdf", metadata.getPayloadValue("filename"));
        assertEquals("HR", metadata.getPayloadValue("department"));
        assertEquals(5, metadata.getPayloadValue("priority"));
    }

    @Test
    @DisplayName("Should build DocumentMetadata without payload using Builder")
    void testBuilderWithoutPayload() {
        TextBlock content = TextBlock.builder().text("Test content").build();

        DocumentMetadata metadata =
                DocumentMetadata.builder().content(content).docId("doc-1").chunkId("0").build();

        assertNotNull(metadata);
        assertNotNull(metadata.getPayload());
        assertTrue(metadata.getPayload().isEmpty());
    }

    @Test
    @DisplayName("Should throw exception when building with null required fields")
    void testBuilderValidation() {
        TextBlock content = TextBlock.builder().text("Test content").build();

        // Missing content
        assertThrows(
                IllegalArgumentException.class,
                () -> DocumentMetadata.builder().docId("doc-1").chunkId("0").build());

        // Missing docId
        assertThrows(
                IllegalArgumentException.class,
                () -> DocumentMetadata.builder().content(content).chunkId("0").build());

        // Missing chunkId
        assertThrows(
                IllegalArgumentException.class,
                () -> DocumentMetadata.builder().content(content).docId("doc-1").build());
    }

    @Test
    @DisplayName("Should support complex payload types")
    void testComplexPayloadTypes() {
        TextBlock content = TextBlock.builder().text("Test content").build();
        Map<String, Object> nestedMap = new HashMap<>();
        nestedMap.put("city", "Beijing");
        nestedMap.put("country", "China");

        Map<String, Object> payload = new HashMap<>();
        payload.put("string", "text");
        payload.put("integer", 42);
        payload.put("double", 3.14);
        payload.put("boolean", true);
        payload.put("list", Arrays.asList("a", "b", "c"));
        payload.put("map", nestedMap);

        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0", payload);

        assertEquals("text", metadata.getPayloadValue("string"));
        assertEquals(42, metadata.getPayloadValue("integer"));
        assertEquals(3.14, metadata.getPayloadValue("double"));
        assertEquals(true, metadata.getPayloadValue("boolean"));
        assertEquals(Arrays.asList("a", "b", "c"), metadata.getPayloadValue("list"));
        assertEquals(nestedMap, metadata.getPayloadValue("map"));
    }
}
