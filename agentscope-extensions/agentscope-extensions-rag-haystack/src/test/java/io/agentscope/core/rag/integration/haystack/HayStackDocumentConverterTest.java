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
package io.agentscope.core.rag.integration.haystack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.rag.integration.haystack.model.HayStackDocument;
import io.agentscope.core.rag.model.Document;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Test class for HayStackDocumentConverter.
 */
class HayStackDocumentConverterTest {

    @Nested
    class ConvertToDocumentsTest {

        @Test
        void shouldReturnEmptyListForNullInput() {
            List<Document> result = HayStackDocumentConverter.convertToDocuments(null);

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        void shouldReturnEmptyListForEmptyInput() {
            List<Document> result = HayStackDocumentConverter.convertToDocuments(new ArrayList<>());

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        void shouldConvertMultipleDocuments() {
            List<HayStackDocument> docs = new ArrayList<>();

            HayStackDocument d1 = new HayStackDocument();
            d1.setId("chunk-1");
            d1.setContent("Content 1");
            d1.setScore(0.9);
            docs.add(d1);

            HayStackDocument d2 = new HayStackDocument();
            d2.setId("chunk-2");
            d2.setContent("Content 2");
            d2.setScore(0.8);
            docs.add(d2);

            List<Document> result = HayStackDocumentConverter.convertToDocuments(docs);

            assertNotNull(result);
            assertEquals(2, result.size());
        }

        @Test
        void shouldSkipNullDocuments() {
            List<HayStackDocument> docs = new ArrayList<>();
            docs.add(null);

            HayStackDocument valid = new HayStackDocument();
            valid.setId("chunk-1");
            valid.setContent("Valid content");
            docs.add(valid);

            List<Document> result = HayStackDocumentConverter.convertToDocuments(docs);

            assertEquals(1, result.size());
        }

        @Test
        void shouldSkipDocumentsWithEmptyContent() {
            List<HayStackDocument> docs = new ArrayList<>();

            HayStackDocument empty = new HayStackDocument();
            empty.setId("chunk-1");
            empty.setContent("");
            docs.add(empty);

            HayStackDocument whitespace = new HayStackDocument();
            whitespace.setId("chunk-2");
            whitespace.setContent("   ");
            docs.add(whitespace);

            HayStackDocument valid = new HayStackDocument();
            valid.setId("chunk-3");
            valid.setContent("Valid content");
            docs.add(valid);

            List<Document> result = HayStackDocumentConverter.convertToDocuments(docs);

            assertEquals(1, result.size());
        }
    }

    @Nested
    class ConvertToDocumentTest {

        @Test
        void shouldReturnNullForNullInput() {
            Document result = HayStackDocumentConverter.convertToDocument(null);

            assertNull(result);
        }

        @Test
        void shouldReturnNullForNullContent() {
            HayStackDocument doc = new HayStackDocument();
            doc.setId("chunk-1");
            doc.setContent(null);

            Document result = HayStackDocumentConverter.convertToDocument(doc);

            assertNull(result);
        }

        @Test
        void shouldReturnNullForEmptyContent() {
            HayStackDocument doc = new HayStackDocument();
            doc.setId("chunk-1");
            doc.setContent("");

            Document result = HayStackDocumentConverter.convertToDocument(doc);

            assertNull(result);
        }

        @Test
        void shouldReturnNullForWhitespaceContent() {
            HayStackDocument doc = new HayStackDocument();
            doc.setId("chunk-1");
            doc.setContent("   \t\n ");

            Document result = HayStackDocumentConverter.convertToDocument(doc);

            assertNull(result);
        }

        @Test
        void shouldConvertValidDocument() {
            HayStackDocument doc = new HayStackDocument();
            doc.setId("chunk-123");
            doc.setContent("This is the chunk content.");
            doc.setScore(0.95);

            Document result = HayStackDocumentConverter.convertToDocument(doc);

            assertNotNull(result);
            assertEquals("chunk-123", result.getMetadata().getDocId());
            assertEquals("chunk-123", result.getMetadata().getChunkId());
            assertEquals(0.95, result.getScore());
            assertEquals("This is the chunk content.", result.getMetadata().getContentText());
        }

        @Test
        void shouldUseFilePathAsDocIdWhenPresent() {
            HayStackDocument doc = new HayStackDocument();
            doc.setId("chunk-1");
            doc.setContent("Content");
            doc.setMeta(Map.of("file_path", "doc-456"));

            Document result = HayStackDocumentConverter.convertToDocument(doc);

            assertNotNull(result);
            assertEquals("doc-456", result.getMetadata().getDocId());
        }

        @Test
        void shouldUseChunkIdFallbackWhenDocIdMissing() {
            HayStackDocument doc = new HayStackDocument();
            doc.setId("chunk-999");
            doc.setContent("Content");
            doc.setMeta(null);

            Document result = HayStackDocumentConverter.convertToDocument(doc);

            assertNotNull(result);
            assertEquals("chunk-999", result.getMetadata().getDocId());
        }

        @Test
        void shouldUseZeroAsChunkIdFallback() {
            HayStackDocument doc = new HayStackDocument();
            doc.setId(null);
            doc.setContent("Content");

            Document result = HayStackDocumentConverter.convertToDocument(doc);

            assertNotNull(result);
            assertEquals("0", result.getMetadata().getChunkId());
        }

        @Test
        void shouldPreserveScore() {
            HayStackDocument doc = new HayStackDocument();
            doc.setId("chunk-1");
            doc.setContent("Content");
            doc.setScore(0.87);

            Document result = HayStackDocumentConverter.convertToDocument(doc);

            assertNotNull(result);
            assertEquals(0.87, result.getScore(), 0.001);
        }

        @Test
        void shouldHandleFullyPopulatedDocument() {
            HayStackDocument doc = new HayStackDocument();
            doc.setId("chunk-full-123");
            doc.setContent("Full content with all metadata.");
            doc.setScore(0.92);
            doc.setMeta(
                    Map.of(
                            "file_path", "doc-full-456",
                            "kb_id", "kb-789"));

            Document result = HayStackDocumentConverter.convertToDocument(doc);

            assertNotNull(result);
            assertEquals("doc-full-456", result.getMetadata().getDocId());
            assertEquals("chunk-full-123", result.getMetadata().getChunkId());
            assertEquals(0.92, result.getScore(), 0.001);
            assertTrue(result.getMetadata().getContentText().contains("Full content"));
        }

        @Test
        void shouldUseSourceAsDocIdWhenFilePathAbsent() {
            HayStackDocument doc = new HayStackDocument();
            doc.setId("window-1");
            doc.setContent("Window content");
            doc.setMeta(Map.of("source", "usr_90.txt"));

            Document result = HayStackDocumentConverter.convertToDocument(doc);

            assertNotNull(result);
            assertEquals("usr_90.txt", result.getMetadata().getDocId());
        }

        @Test
        void shouldHandleSentenceWindowDocumentWithoutScore() {
            HayStackDocument doc = new HayStackDocument();
            doc.setId("window-1");
            doc.setContent("Window content");
            doc.setMeta(Map.of("source", "doc.txt"));

            Document result = HayStackDocumentConverter.convertToDocument(doc);

            assertNotNull(result);
            assertNull(result.getScore());
        }

        @Test
        void shouldHandleSentenceWindowDocumentWithoutEmbedding() {
            HayStackDocument doc = new HayStackDocument();
            doc.setId("window-emb");
            doc.setContent("Window content");
            doc.setMeta(Map.of("source", "doc.txt"));
            doc.setEmbedding(null);

            Document result = HayStackDocumentConverter.convertToDocument(doc);

            assertNotNull(result);
            assertNull(result.getEmbedding());
        }
    }
}
