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
package io.agentscope.core.rag.integration.ragflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.rag.integration.ragflow.model.RAGFlowChunk;
import io.agentscope.core.rag.model.Document;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for RAGFlowDocumentConverter.
 */
class RAGFlowDocumentConverterTest {

    @Nested
    class ConvertToDocumentsTest {

        @Test
        void shouldReturnEmptyListForNullInput() {
            List<Document> result = RAGFlowDocumentConverter.convertToDocuments(null);

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        void shouldReturnEmptyListForEmptyInput() {
            List<Document> result = RAGFlowDocumentConverter.convertToDocuments(new ArrayList<>());

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        void shouldConvertMultipleChunks() {
            List<RAGFlowChunk> chunks = new ArrayList<>();

            RAGFlowChunk chunk1 = new RAGFlowChunk();
            chunk1.setId("chunk-1");
            chunk1.setContent("Content 1");
            chunk1.setDocumentId("doc-1");
            chunk1.setScore(0.9);
            chunks.add(chunk1);

            RAGFlowChunk chunk2 = new RAGFlowChunk();
            chunk2.setId("chunk-2");
            chunk2.setContent("Content 2");
            chunk2.setDocumentId("doc-2");
            chunk2.setScore(0.8);
            chunks.add(chunk2);

            List<Document> result = RAGFlowDocumentConverter.convertToDocuments(chunks);

            assertNotNull(result);
            assertEquals(2, result.size());
        }

        @Test
        void shouldSkipNullChunks() {
            List<RAGFlowChunk> chunks = new ArrayList<>();
            chunks.add(null);

            RAGFlowChunk validChunk = new RAGFlowChunk();
            validChunk.setId("chunk-1");
            validChunk.setContent("Valid content");
            chunks.add(validChunk);

            List<Document> result = RAGFlowDocumentConverter.convertToDocuments(chunks);

            assertEquals(1, result.size());
        }

        @Test
        void shouldSkipChunksWithEmptyContent() {
            List<RAGFlowChunk> chunks = new ArrayList<>();

            RAGFlowChunk emptyChunk = new RAGFlowChunk();
            emptyChunk.setId("chunk-1");
            emptyChunk.setContent("");
            chunks.add(emptyChunk);

            RAGFlowChunk whitespaceChunk = new RAGFlowChunk();
            whitespaceChunk.setId("chunk-2");
            whitespaceChunk.setContent("   ");
            chunks.add(whitespaceChunk);

            RAGFlowChunk validChunk = new RAGFlowChunk();
            validChunk.setId("chunk-3");
            validChunk.setContent("Valid content");
            chunks.add(validChunk);

            List<Document> result = RAGFlowDocumentConverter.convertToDocuments(chunks);

            assertEquals(1, result.size());
        }
    }

    @Nested
    class ConvertToDocumentTest {

        @Test
        void shouldReturnNullForNullChunk() {
            Document result = RAGFlowDocumentConverter.convertToDocument(null);

            assertNull(result);
        }

        @Test
        void shouldReturnNullForNullContent() {
            RAGFlowChunk chunk = new RAGFlowChunk();
            chunk.setId("chunk-1");
            chunk.setContent(null);

            Document result = RAGFlowDocumentConverter.convertToDocument(chunk);

            assertNull(result);
        }

        @Test
        void shouldReturnNullForEmptyContent() {
            RAGFlowChunk chunk = new RAGFlowChunk();
            chunk.setId("chunk-1");
            chunk.setContent("");

            Document result = RAGFlowDocumentConverter.convertToDocument(chunk);

            assertNull(result);
        }

        @Test
        void shouldReturnNullForWhitespaceContent() {
            RAGFlowChunk chunk = new RAGFlowChunk();
            chunk.setId("chunk-1");
            chunk.setContent("   \t\n  ");

            Document result = RAGFlowDocumentConverter.convertToDocument(chunk);

            assertNull(result);
        }

        @Test
        void shouldConvertValidChunk() {
            RAGFlowChunk chunk = new RAGFlowChunk();
            chunk.setId("chunk-123");
            chunk.setContent("This is the chunk content.");
            chunk.setDocumentId("doc-456");
            chunk.setScore(0.95);

            Document result = RAGFlowDocumentConverter.convertToDocument(chunk);

            assertNotNull(result);
            assertNotNull(result.getMetadata());
            assertEquals("doc-456", result.getMetadata().getDocId());
            assertEquals("chunk-123", result.getMetadata().getChunkId());
            assertEquals(0.95, result.getScore());
            assertEquals("This is the chunk content.", result.getMetadata().getContentText());
        }

        @Test
        void shouldUseChunkIdAsDocIdFallback() {
            RAGFlowChunk chunk = new RAGFlowChunk();
            chunk.setId("chunk-123");
            chunk.setContent("Content");
            chunk.setDocumentId(null);

            Document result = RAGFlowDocumentConverter.convertToDocument(chunk);

            assertNotNull(result);
            assertEquals("chunk-123", result.getMetadata().getDocId());
        }

        @Test
        void shouldUseUnknownWhenBothIdsNull() {
            RAGFlowChunk chunk = new RAGFlowChunk();
            chunk.setId(null);
            chunk.setContent("Content");
            chunk.setDocumentId(null);

            Document result = RAGFlowDocumentConverter.convertToDocument(chunk);

            assertNotNull(result);
            assertEquals("unknown", result.getMetadata().getDocId());
        }

        @Test
        void shouldUseZeroAsChunkIdFallback() {
            RAGFlowChunk chunk = new RAGFlowChunk();
            chunk.setId(null);
            chunk.setContent("Content");
            chunk.setDocumentId("doc-1");

            Document result = RAGFlowDocumentConverter.convertToDocument(chunk);

            assertNotNull(result);
            assertEquals("0", result.getMetadata().getChunkId());
        }

        @Test
        void shouldSetZeroScoreWhenNull() {
            RAGFlowChunk chunk = new RAGFlowChunk();
            chunk.setId("chunk-1");
            chunk.setContent("Content");
            chunk.setScore(null);

            Document result = RAGFlowDocumentConverter.convertToDocument(chunk);

            assertNotNull(result);
            assertEquals(0.0, result.getScore());
        }

        @Test
        void shouldPreserveScore() {
            RAGFlowChunk chunk = new RAGFlowChunk();
            chunk.setId("chunk-1");
            chunk.setContent("Content");
            chunk.setScore(0.87);

            Document result = RAGFlowDocumentConverter.convertToDocument(chunk);

            assertNotNull(result);
            assertEquals(0.87, result.getScore(), 0.001);
        }

        @Test
        void shouldHandleFullyPopulatedChunk() {
            RAGFlowChunk chunk = new RAGFlowChunk();
            chunk.setId("chunk-full-123");
            chunk.setContent("Full content with all metadata.");
            chunk.setDocumentId("doc-full-456");
            chunk.setDocumentKeyword("test-document.pdf");
            chunk.setKbId("kb-789");
            chunk.setSimilarity(0.92);
            chunk.setVectorSimilarity(0.88);
            chunk.setTermSimilarity(0.75);
            chunk.setScore(0.92);
            chunk.setHighlight("<em>highlighted</em> content");

            Document result = RAGFlowDocumentConverter.convertToDocument(chunk);

            assertNotNull(result);
            assertEquals("doc-full-456", result.getMetadata().getDocId());
            assertEquals("chunk-full-123", result.getMetadata().getChunkId());
            assertEquals(0.92, result.getScore(), 0.001);
            assertTrue(result.getMetadata().getContentText().contains("Full content"));
        }

        @Test
        void shouldBuildPayloadFromChunk() {
            RAGFlowChunk chunk = new RAGFlowChunk();
            chunk.setId("chunk-123");
            chunk.setContent("Test content");
            chunk.setDocumentId("doc-456");
            chunk.setDocumentKeyword("test.pdf");
            chunk.setDocumentName("Test Document");
            chunk.setDatasetId("dataset-789");
            chunk.setKbId("kb-101");
            chunk.setScore(0.95);
            chunk.setSimilarity(0.95);
            chunk.setVectorSimilarity(0.90);
            chunk.setTermSimilarity(0.85);
            chunk.setHighlight("<em>highlighted</em> content");
            chunk.setImageId("image-123");

            Document result = RAGFlowDocumentConverter.convertToDocument(chunk);

            assertNotNull(result);
            assertEquals(0.95, result.getScore(), 0.001);

            // Verify reserved fields are NOT in payload
            assertNull(result.getPayloadValue("id"));
            assertNull(result.getPayloadValue("document_id"));
            assertNull(result.getPayloadValue("content"));

            // Verify payload fields
            assertEquals("test.pdf", result.getPayloadValue("document_keyword"));
            assertEquals("test.pdf", result.getPayloadValue("document_name"));
            assertEquals("kb-101", result.getPayloadValue("dataset_id"));
            assertEquals("kb-101", result.getPayloadValue("kb_id"));
            assertEquals(0.90, result.getPayloadValue("vector_similarity"));
            assertEquals(0.85, result.getPayloadValue("term_similarity"));
            assertEquals("<em>highlighted</em> content", result.getPayloadValue("highlight"));
            assertEquals("image-123", result.getPayloadValue("image_id"));
        }

        @Test
        void shouldHandleEmptyPayload() {
            RAGFlowChunk chunk = new RAGFlowChunk();
            chunk.setId("chunk-minimal");
            chunk.setContent("Minimal content");
            chunk.setDocumentId("doc-minimal");
            chunk.setScore(0.75);

            Document result = RAGFlowDocumentConverter.convertToDocument(chunk);

            assertNotNull(result);
            assertNotNull(result.getPayload());
            // Payload should be empty or contain only null-checked fields
            assertTrue(
                    result.getPayload().isEmpty()
                            || result.getPayload().values().stream().allMatch(v -> v == null));
        }
    }
}
