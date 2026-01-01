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
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
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
}
