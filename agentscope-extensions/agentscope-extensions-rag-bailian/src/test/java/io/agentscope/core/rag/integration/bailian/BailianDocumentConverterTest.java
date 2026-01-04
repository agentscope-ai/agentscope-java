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
package io.agentscope.core.rag.integration.bailian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aliyun.bailian20231229.models.RetrieveResponseBody;
import io.agentscope.core.rag.model.Document;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BailianDocumentConverterTest {

    @Test
    void testFromBailianResponseWithValidData() {
        // Create mock response
        RetrieveResponseBody response = new RetrieveResponseBody();
        RetrieveResponseBody.RetrieveResponseBodyData data =
                new RetrieveResponseBody.RetrieveResponseBodyData();

        // Create mock node
        RetrieveResponseBody.RetrieveResponseBodyDataNodes node =
                new RetrieveResponseBody.RetrieveResponseBodyDataNodes();
        node.setText("Test document content");
        node.setScore(0.95);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("doc_id", "doc123");
        metadata.put("_id", "chunk456");
        metadata.put("doc_name", "test.pdf");
        metadata.put("title", "Test Document");
        node.setMetadata(metadata);

        data.setNodes(List.of(node));
        response.setData(data);

        // Convert
        List<Document> documents = BailianDocumentConverter.fromBailianResponse(response);

        // Verify
        assertNotNull(documents);
        assertEquals(1, documents.size());

        Document doc = documents.get(0);
        assertNotNull(doc);
        assertEquals(0.95, doc.getScore());
        assertTrue(doc.getMetadata().getContentText().contains("Test document content"));
        assertEquals("doc123", doc.getMetadata().getDocId());
        assertEquals("chunk456", doc.getMetadata().getChunkId());
    }

    @Test
    void testFromBailianResponseWithNullResponse() {
        List<Document> documents = BailianDocumentConverter.fromBailianResponse(null);

        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }

    @Test
    void testFromBailianResponseWithNullData() {
        RetrieveResponseBody response = new RetrieveResponseBody();
        response.setData(null);

        List<Document> documents = BailianDocumentConverter.fromBailianResponse(response);

        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }

    @Test
    void testFromBailianResponseWithEmptyNodes() {
        RetrieveResponseBody response = new RetrieveResponseBody();
        RetrieveResponseBody.RetrieveResponseBodyData data =
                new RetrieveResponseBody.RetrieveResponseBodyData();
        data.setNodes(List.of());
        response.setData(data);

        List<Document> documents = BailianDocumentConverter.fromBailianResponse(response);

        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }

    @Test
    void testFromBailianNodeWithValidNode() {
        RetrieveResponseBody.RetrieveResponseBodyDataNodes node =
                new RetrieveResponseBody.RetrieveResponseBodyDataNodes();
        node.setText("Sample text content");
        node.setScore(0.85);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("doc_id", "doc789");
        metadata.put("_id", "chunk101");
        node.setMetadata(metadata);

        Document doc = BailianDocumentConverter.fromBailianNode(node);

        assertNotNull(doc);
        assertEquals(0.85, doc.getScore());
        assertEquals("Sample text content", doc.getMetadata().getContentText());
        assertEquals("doc789", doc.getMetadata().getDocId());
        assertEquals("chunk101", doc.getMetadata().getChunkId());
    }

    @Test
    void testFromBailianNodeWithNullNode() {
        Document doc = BailianDocumentConverter.fromBailianNode(null);
        assertNull(doc);
    }

    @Test
    void testFromBailianNodeWithNullText() {
        RetrieveResponseBody.RetrieveResponseBodyDataNodes node =
                new RetrieveResponseBody.RetrieveResponseBodyDataNodes();
        node.setText(null);
        node.setScore(0.85);

        Document doc = BailianDocumentConverter.fromBailianNode(node);
        assertNull(doc);
    }

    @Test
    void testFromBailianNodeWithEmptyText() {
        RetrieveResponseBody.RetrieveResponseBodyDataNodes node =
                new RetrieveResponseBody.RetrieveResponseBodyDataNodes();
        node.setText("   ");
        node.setScore(0.85);

        Document doc = BailianDocumentConverter.fromBailianNode(node);
        assertNull(doc);
    }

    @Test
    void testFromBailianNodeWithNullMetadata() {
        RetrieveResponseBody.RetrieveResponseBodyDataNodes node =
                new RetrieveResponseBody.RetrieveResponseBodyDataNodes();
        node.setText("Text content");
        node.setScore(0.75);
        node.setMetadata(null);

        Document doc = BailianDocumentConverter.fromBailianNode(node);

        assertNull(doc);
    }

    @Test
    void testFromBailianNodeWithMissingMetadataFields() {
        RetrieveResponseBody.RetrieveResponseBodyDataNodes node =
                new RetrieveResponseBody.RetrieveResponseBodyDataNodes();
        node.setText("Text content");
        node.setScore(0.75);
        Document doc = BailianDocumentConverter.fromBailianNode(node);
        assertNull(doc);
    }

    @Test
    void testFromBailianNodeWithNullScore() {
        RetrieveResponseBody.RetrieveResponseBodyDataNodes node =
                new RetrieveResponseBody.RetrieveResponseBodyDataNodes();
        node.setText("Text content");
        node.setScore(null);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("doc_id", "doc123");
        metadata.put("_id", "chunk456");
        node.setMetadata(metadata);

        Document doc = BailianDocumentConverter.fromBailianNode(node);

        assertNotNull(doc);
        assertNull(doc.getScore());
    }

    @Test
    void testFromBailianResponseWithMultipleNodes() {
        RetrieveResponseBody response = new RetrieveResponseBody();
        RetrieveResponseBody.RetrieveResponseBodyData data =
                new RetrieveResponseBody.RetrieveResponseBodyData();

        // Create multiple nodes
        RetrieveResponseBody.RetrieveResponseBodyDataNodes node1 =
                new RetrieveResponseBody.RetrieveResponseBodyDataNodes();
        node1.setText("First document");
        node1.setScore(0.95);
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("doc_id", "doc1");
        meta1.put("_id", "chunk1");
        node1.setMetadata(meta1);

        RetrieveResponseBody.RetrieveResponseBodyDataNodes node2 =
                new RetrieveResponseBody.RetrieveResponseBodyDataNodes();
        node2.setText("Second document");
        node2.setScore(0.85);
        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("doc_id", "doc2");
        meta2.put("_id", "chunk2");
        node2.setMetadata(meta2);

        data.setNodes(List.of(node1, node2));
        response.setData(data);

        List<Document> documents = BailianDocumentConverter.fromBailianResponse(response);

        assertNotNull(documents);
        assertEquals(2, documents.size());
        assertEquals("First document", documents.get(0).getMetadata().getContentText());
        assertEquals("Second document", documents.get(1).getMetadata().getContentText());
    }
}
