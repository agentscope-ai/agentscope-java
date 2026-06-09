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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.rag.exception.ReaderException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Unit tests for ImageReader.
 */
@Tag("unit")
@DisplayName("ImageReader Unit Tests")
class ImageReaderTest {

    @Test
    @DisplayName("Should create ImageReader with default settings")
    void testDefaultConstructor() {
        ImageReader reader = new ImageReader();
        assertFalse(reader.isOcrEnabled());
    }

    @Test
    @DisplayName("Should create ImageReader with OCR enabled")
    void testConstructorWithOCR() {
        ImageReader reader = new ImageReader(true);
        assertTrue(reader.isOcrEnabled());
    }

    @Test
    @DisplayName("Should return supported image formats")
    void testGetSupportedFormats() {
        ImageReader reader = new ImageReader();
        List<String> formats = reader.getSupportedFormats();

        assertTrue(formats.contains("jpg"));
        assertTrue(formats.contains("jpeg"));
        assertTrue(formats.contains("png"));
        assertTrue(formats.contains("gif"));
        assertTrue(formats.contains("bmp"));
        assertTrue(formats.contains("tiff"));
        assertTrue(formats.contains("webp"));
    }

    @Test
    @DisplayName("Should read image from file path")
    void testReadFromFilePath() throws ReaderException {
        ImageReader reader = new ImageReader();
        ReaderInput input = ReaderInput.fromString("path/to/image.jpg");

        StepVerifier.create(reader.read(input))
                .assertNext(
                        documents -> {
                            assertNotNull(documents);
                            assertEquals(1, documents.size());
                            Document doc = documents.get(0);
                            assertNotNull(doc);
                            DocumentMetadata metadata = doc.getMetadata();
                            assertNotNull(metadata);

                            // Content should be an ImageBlock
                            assertTrue(metadata.getContent() instanceof ImageBlock);
                            ImageBlock imageBlock = (ImageBlock) metadata.getContent();
                            assertNotNull(imageBlock.getSource());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should read image from URL")
    void testReadFromURL() throws ReaderException {
        ImageReader reader = new ImageReader();
        ReaderInput input = ReaderInput.fromString("https://example.com/image.png");

        StepVerifier.create(reader.read(input))
                .assertNext(
                        documents -> {
                            assertEquals(1, documents.size());
                            Document doc = documents.get(0);
                            DocumentMetadata metadata = doc.getMetadata();

                            // Content should be an ImageBlock with URLSource
                            assertTrue(metadata.getContent() instanceof ImageBlock);
                            ImageBlock imageBlock = (ImageBlock) metadata.getContent();
                            assertNotNull(imageBlock.getSource());
                            assertTrue(imageBlock.getSource() instanceof URLSource);
                            URLSource urlSource = (URLSource) imageBlock.getSource();
                            assertEquals("https://example.com/image.png", urlSource.getUrl());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle null input")
    void testNullInput() throws ReaderException {
        ImageReader reader = new ImageReader();

        StepVerifier.create(reader.read(null)).expectError(ReaderException.class).verify();
    }

    @Test
    @DisplayName("Should create document with image content")
    void testImageDocumentContent() throws ReaderException {
        ImageReader reader = new ImageReader();
        ReaderInput input = ReaderInput.fromString("test.jpg");

        StepVerifier.create(reader.read(input))
                .assertNext(
                        documents -> {
                            Document doc = documents.get(0);
                            DocumentMetadata metadata = doc.getMetadata();
                            assertEquals("0", metadata.getChunkId());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should generate deterministic doc_id using MD5 of image path")
    void testDeterministicDocId() throws ReaderException {
        ImageReader reader = new ImageReader();
        String imagePath = "path/to/image.jpg";
        ReaderInput input = ReaderInput.fromString(imagePath);

        // Read the same image twice
        List<Document> docs1 = reader.read(input).block();
        List<Document> docs2 = reader.read(input).block();

        assertNotNull(docs1);
        assertNotNull(docs2);
        assertEquals(1, docs1.size());
        assertEquals(1, docs2.size());

        // Doc IDs should be the same for the same image path
        String docId1 = docs1.get(0).getMetadata().getDocId();
        String docId2 = docs2.get(0).getMetadata().getDocId();
        assertEquals(docId1, docId2, "Doc ID should be deterministic for same image path");

        // Expected MD5 hash of "path/to/image.jpg"
        String expectedDocId = "1f8fb775f2e55e67d7afc058c1db5ddd";
        assertEquals(
                expectedDocId,
                docId1,
                "Doc ID should be MD5 hash of image path (matching Python implementation)");
    }

    @Test
    @DisplayName("Should generate different doc_ids for different image paths")
    void testDifferentDocIdsForDifferentPaths() throws ReaderException {
        ImageReader reader = new ImageReader();

        List<Document> docs1 = reader.read(ReaderInput.fromString("image1.jpg")).block();
        List<Document> docs2 = reader.read(ReaderInput.fromString("image2.jpg")).block();

        assertNotNull(docs1);
        assertNotNull(docs2);

        String docId1 = docs1.get(0).getMetadata().getDocId();
        String docId2 = docs2.get(0).getMetadata().getDocId();

        assertFalse(docId1.equals(docId2), "Different image paths should have different doc IDs");
    }
}
