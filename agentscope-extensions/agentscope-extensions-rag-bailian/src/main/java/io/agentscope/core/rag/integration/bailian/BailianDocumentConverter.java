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

import com.aliyun.bailian20231229.models.RetrieveResponseBody;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converter for transforming Bailian API responses to AgentScope Document objects.
 *
 * <p>This class handles the conversion between Bailian's retrieve response format
 * and AgentScope's internal Document representation, ensuring seamless integration
 * with the RAG system.
 */
public class BailianDocumentConverter {

    private BailianDocumentConverter() {
        // Utility class, prevent instantiation
    }

    /**
     * Converts Bailian retrieve response to a list of Documents.
     *
     * @param response the Bailian retrieve response
     * @return a list of Document objects
     */
    public static List<Document> fromBailianResponse(RetrieveResponseBody response) {
        if (response == null || response.getData() == null) {
            return new ArrayList<>();
        }

        RetrieveResponseBody.RetrieveResponseBodyData data = response.getData();
        if (data.getNodes() == null || data.getNodes().isEmpty()) {
            return new ArrayList<>();
        }

        List<Document> documents = new ArrayList<>();
        for (RetrieveResponseBody.RetrieveResponseBodyDataNodes node : data.getNodes()) {
            Document doc = fromBailianNode(node);
            if (doc != null) {
                documents.add(doc);
            }
        }

        return documents;
    }

    /**
     * Converts a single Bailian node to a Document.
     *
     * @param node the Bailian node from retrieve response
     * @return a Document object, or null if conversion fails
     */
    public static Document fromBailianNode(
            RetrieveResponseBody.RetrieveResponseBodyDataNodes node) {
        if (node == null) {
            return null;
        }

        try {
            // Extract text content
            String text = node.getText();
            if (text == null || text.trim().isEmpty()) {
                return null;
            }

            // Extract metadata (convert Object to Map safely)
            Map<String, Object> nodeMetadata = new HashMap<>();
            Object metadataObj = node.getMetadata();
            if (metadataObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> metadataMap = (Map<String, Object>) metadataObj;
                nodeMetadata.putAll(metadataMap);
            }

            // Extract key fields from metadata
            String docId = extractStringFromMetadata(nodeMetadata, "doc_id");
            if (docId == null || docId.isEmpty()) {
                docId = "unknown";
            }

            // Extract chunk ID - Bailian uses string "_id" field
            String chunkId = extractStringFromMetadata(nodeMetadata, "_id");

            // Build payload from node metadata (exclude reserved fields)
            Map<String, Object> payload = new HashMap<>();
            if (!nodeMetadata.isEmpty()) {
                for (Map.Entry<String, Object> entry : nodeMetadata.entrySet()) {
                    String key = entry.getKey();
                    // Skip reserved fields that are already used for core metadata
                    if (!"doc_id".equals(key) && !"_id".equals(key)) {
                        payload.put(key, entry.getValue());
                    }
                }
            }

            // Build DocumentMetadata with payload
            TextBlock content = TextBlock.builder().text(text).build();
            DocumentMetadata metadata = new DocumentMetadata(content, docId, chunkId, payload);

            // Create Document
            Document document = new Document(metadata);

            // Set similarity score
            if (node.getScore() != null) {
                document.setScore(node.getScore());
            }

            return document;
        } catch (Exception e) {
            // Log error and return null to skip this document
            return null;
        }
    }

    /**
     * Safely extracts a string value from metadata map.
     *
     * @param metadata the metadata map
     * @param key the key to extract
     * @return the string value, or null if not found or not a string
     */
    private static String extractStringFromMetadata(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }

        Object value = metadata.get(key);
        if (value instanceof String) {
            return (String) value;
        }

        return value != null ? value.toString() : null;
    }
}
