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
package io.agentscope.core.rag.integration.dify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.rag.integration.dify.model.DifyResponse;
import io.agentscope.core.rag.model.Document;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DifyDocumentConverterTest {

    @Test
    void testFromDifyResponseNull() {
        List<Document> documents = DifyDocumentConverter.fromDifyResponse(null);
        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }

    @Test
    void testFromDifyResponseEmptyRecords() {
        DifyResponse response = new DifyResponse();
        response.setRecords(new ArrayList<>());

        List<Document> documents = DifyDocumentConverter.fromDifyResponse(response);
        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }

    @Test
    void testFromDifyResponseNullRecords() {
        DifyResponse response = new DifyResponse();
        // records is null by default

        List<Document> documents = DifyDocumentConverter.fromDifyResponse(response);
        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }

    @Test
    void testFromDifyResponseSuccess() {
        // Create mock response with new structure
        DifyResponse response = new DifyResponse();

        DifyResponse.Segment segment = new DifyResponse.Segment();
        segment.setId("segment-123");
        segment.setDocumentId("doc-456");
        segment.setContent("Test content");
        segment.setPosition(0);

        DifyResponse.DocumentInfo docInfo = new DifyResponse.DocumentInfo();
        docInfo.setId("doc-456");
        docInfo.setName("test-document.pdf");
        segment.setDocument(docInfo);

        DifyResponse.Record record = new DifyResponse.Record();
        record.setSegment(segment);
        record.setScore(0.95);

        response.setRecords(List.of(record));

        // Convert
        List<Document> documents = DifyDocumentConverter.fromDifyResponse(response);

        // Verify
        assertNotNull(documents);
        assertEquals(1, documents.size());

        Document doc = documents.get(0);
        assertNotNull(doc);
        assertEquals(0.95, doc.getScore());
        assertEquals("Test content", doc.getMetadata().getContentText());
        assertEquals("doc-456", doc.getMetadata().getDocId());
    }

    @Test
    void testFromDifyRecordNull() {
        Document doc = DifyDocumentConverter.fromDifyRecord(null);
        assertNull(doc);
    }

    @Test
    void testFromDifyRecordNullSegment() {
        DifyResponse.Record record = new DifyResponse.Record();
        record.setScore(0.9);
        // segment is null

        Document doc = DifyDocumentConverter.fromDifyRecord(record);
        assertNull(doc);
    }

    @Test
    void testFromDifyRecordEmptyContent() {
        DifyResponse.Segment segment = new DifyResponse.Segment();
        segment.setId("segment-123");
        segment.setContent("");

        DifyResponse.Record record = new DifyResponse.Record();
        record.setSegment(segment);
        record.setScore(0.9);

        Document doc = DifyDocumentConverter.fromDifyRecord(record);
        assertNull(doc);
    }

    @Test
    void testFromDifyRecordNoDocumentId() {
        DifyResponse.Segment segment = new DifyResponse.Segment();
        segment.setId("segment-123");
        segment.setContent("Test content");
        // documentId is null, should fallback to segment id

        DifyResponse.Record record = new DifyResponse.Record();
        record.setSegment(segment);
        record.setScore(0.85);

        Document doc = DifyDocumentConverter.fromDifyRecord(record);
        assertNotNull(doc);
        assertEquals("segment-123", doc.getMetadata().getDocId());
    }

    @Test
    void testFromDifyRecordWithPosition() {
        DifyResponse.Segment segment = new DifyResponse.Segment();
        segment.setId("segment-123");
        segment.setDocumentId("doc-456");
        segment.setContent("Test content");
        segment.setPosition(5);

        DifyResponse.Record record = new DifyResponse.Record();
        record.setSegment(segment);
        record.setScore(0.92);

        Document doc = DifyDocumentConverter.fromDifyRecord(record);
        assertNotNull(doc);
        assertEquals(0.92, doc.getScore());
        assertEquals("doc-456", doc.getMetadata().getDocId());
        assertEquals("segment-123", doc.getMetadata().getChunkId());
    }

    @Test
    void testFromDifyResponseMultipleRecords() {
        DifyResponse response = new DifyResponse();

        List<DifyResponse.Record> records = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            DifyResponse.Segment segment = new DifyResponse.Segment();
            segment.setId("segment-" + i);
            segment.setDocumentId("doc-" + i);
            segment.setContent("Content " + i);
            segment.setPosition(i);

            DifyResponse.Record record = new DifyResponse.Record();
            record.setSegment(segment);
            record.setScore(0.9 - i * 0.1);
            records.add(record);
        }

        response.setRecords(records);

        List<Document> documents = DifyDocumentConverter.fromDifyResponse(response);

        assertEquals(3, documents.size());
        assertEquals(0.9, documents.get(0).getScore());
        assertEquals(0.8, documents.get(1).getScore(), 0.001);
        assertEquals(0.7, documents.get(2).getScore(), 0.001);
    }

    @Test
    void testFromDifyRecordWithDocumentInfo() {
        DifyResponse.Segment segment = new DifyResponse.Segment();
        segment.setId("segment-123");
        segment.setDocumentId("doc-456");
        segment.setContent("Test content from PDF");
        segment.setPosition(2);
        segment.setWordCount(100);
        segment.setTokens(50);

        DifyResponse.DocumentInfo docInfo = new DifyResponse.DocumentInfo();
        docInfo.setId("doc-456");
        docInfo.setName("knowledge-base.pdf");
        docInfo.setDataSourceType("upload_file");
        segment.setDocument(docInfo);

        DifyResponse.Record record = new DifyResponse.Record();
        record.setSegment(segment);
        record.setScore(0.88);

        Document doc = DifyDocumentConverter.fromDifyRecord(record);
        assertNotNull(doc);
        assertEquals(0.88, doc.getScore());
        assertEquals("doc-456", doc.getMetadata().getDocId());
        assertEquals("Test content from PDF", doc.getMetadata().getContentText());
    }
}
