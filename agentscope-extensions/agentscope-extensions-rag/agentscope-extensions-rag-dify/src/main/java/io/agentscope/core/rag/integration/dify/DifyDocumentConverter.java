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

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.integration.dify.model.DifyResponse;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converter for transforming Dify API responses to AgentScope Document objects.
 *
 * <p>This class handles the conversion between Dify's retrieve response format
 * and AgentScope's internal Document representation, ensuring seamless integration
 * with the RAG system.
 *
 * <p>Dify API response format:
 * <pre>{@code
 * {
 *   "query": { "content": "..." },
 *   "records": [
 *     {
 *       "segment": {
 *         "id": "...",
 *         "document_id": "...",
 *         "content": "...",
 *         "document": { "id": "...", "name": "..." }
 *       },
 *       "score": 0.95
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>Example usage:
 * <pre>{@code
 * DifyResponse response = difyClient.retrieve(...).block();
 * List<Document> documents = DifyDocumentConverter.fromDifyResponse(response);
 * }</pre>
 */
public class DifyDocumentConverter {

    private static final Logger log = LoggerFactory.getLogger(DifyDocumentConverter.class);

    private DifyDocumentConverter() {
        // Utility class, prevent instantiation
    }

    /**
     * Converts Dify API response to a list of Documents.
     *
     * <p>This method extracts records from the Dify response and converts
     * each record to an AgentScope Document object. If the response is null or empty,
     * an empty list is returned.
     *
     * @param response the Dify API response
     * @return a list of Document objects (never null)
     */
    public static List<Document> fromDifyResponse(DifyResponse response) {
        if (response == null) {
            log.debug("Dify response is null, returning empty list");
            return new ArrayList<>();
        }

        List<DifyResponse.Record> records = response.getRecords();
        if (records == null || records.isEmpty()) {
            log.debug("Dify response contains no records, returning empty list");
            return new ArrayList<>();
        }

        List<Document> documents = new ArrayList<>();
        for (DifyResponse.Record record : records) {
            try {
                Document doc = fromDifyRecord(record);
                if (doc != null) {
                    documents.add(doc);
                }
            } catch (Exception e) {
                log.warn("Failed to convert Dify record to document: {}", e.getMessage());
            }
        }

        log.debug("Converted {} Dify records to documents", documents.size());
        return documents;
    }

    /**
     * Converts a single Dify record to a Document.
     *
     * <p>This method extracts the segment content, score, and metadata from a Dify record
     * and constructs an AgentScope Document object. If the record is invalid or missing
     * required fields, null is returned.
     *
     * @param record the Dify record from API response
     * @return a Document object, or null if conversion fails
     */
    public static Document fromDifyRecord(DifyResponse.Record record) {
        if (record == null) {
            log.debug("Dify record is null");
            return null;
        }

        DifyResponse.Segment segment = record.getSegment();
        if (segment == null) {
            log.debug("Dify record has no segment");
            return null;
        }

        // Extract and validate content
        String content = segment.getContent();
        if (content == null || content.trim().isEmpty()) {
            log.debug("Dify segment has empty content, skipping");
            return null;
        }

        // Extract document ID (prefer document_id, fallback to segment id)
        String docId = segment.getDocumentId();
        if (docId == null || docId.trim().isEmpty()) {
            docId = segment.getId();
        }
        if (docId == null || docId.trim().isEmpty()) {
            docId = "unknown";
            log.debug("Dify segment has no document ID, using 'unknown'");
        }

        // Extract document name from nested document object
        String docName = null;
        if (segment.getDocument() != null) {
            docName = segment.getDocument().getName();
        }

        // Use segment id as chunk ID
        String chunkId = segment.getId();
        if (chunkId == null || chunkId.isEmpty()) {
            chunkId = "0";
        }

        // Build payload from segment metadata
        Map<String, Object> payload = buildPayloadFromSegment(segment);

        // Build DocumentMetadata with payload
        TextBlock textBlock = TextBlock.builder().text(content).build();
        DocumentMetadata docMetadata = new DocumentMetadata(textBlock, docId, chunkId, payload);

        // Create Document
        Document document = new Document(docMetadata);

        // Set similarity score from record level
        Double score = record.getScore();
        if (score != null) {
            document.setScore(score);
        } else {
            log.debug("Dify record has no score");
        }

        log.debug(
                "Converted Dify record to document: id={}, docName={}, score={}, contentLength={}",
                docId,
                docName,
                score,
                content.length());

        return document;
    }

    /**
     * Builds payload map from Dify segment metadata.
     *
     * <p>This method extracts relevant metadata fields from the Dify Segment object
     * and converts them into a payload map. Unlike Bailian which provides metadata
     * as a direct Map, Dify's metadata is scattered across multiple fields in the
     * Segment object, so we need to manually collect them.
     *
     * <p>Selected metadata fields include:
     * <ul>
     *   <li>Document information: document_id, document_name, data_source_type</li>
     *   <li>Segment information: segment_id, position, word_count, tokens</li>
     *   <li>Index information: index_node_id, index_node_hash</li>
     *   <li>Status information: status, enabled, hit_count</li>
     *   <li>Timestamp information: created_at, indexing_at, completed_at</li>
     *   <li>Keywords: keywords list</li>
     * </ul>
     *
     * @param segment the Dify segment containing metadata fields
     * @return a map of metadata key-value pairs (never null)
     */
    private static Map<String, Object> buildPayloadFromSegment(DifyResponse.Segment segment) {
        Map<String, Object> payload = new HashMap<>();

        // Document information - preserve nested structure
        if (segment.getDocument() != null) {
            Map<String, Object> documentInfo = new HashMap<>();
            DifyResponse.DocumentInfo doc = segment.getDocument();

            if (doc.getId() != null) {
                documentInfo.put("id", doc.getId());
            }
            if (doc.getName() != null) {
                documentInfo.put("name", doc.getName());
            }
            if (doc.getDataSourceType() != null) {
                documentInfo.put("data_source_type", doc.getDataSourceType());
            }

            if (!documentInfo.isEmpty()) {
                payload.put("document", documentInfo);
            }
        }

        // Segment information (skip id, document_id, content as they're already used)
        if (segment.getPosition() != null) {
            payload.put("position", segment.getPosition());
        }
        if (segment.getWordCount() != null) {
            payload.put("word_count", segment.getWordCount());
        }
        if (segment.getTokens() != null) {
            payload.put("tokens", segment.getTokens());
        }
        if (segment.getAnswer() != null && !segment.getAnswer().isEmpty()) {
            payload.put("answer", segment.getAnswer());
        }

        // Index information
        if (segment.getIndexNodeId() != null) {
            payload.put("index_node_id", segment.getIndexNodeId());
        }
        if (segment.getIndexNodeHash() != null) {
            payload.put("index_node_hash", segment.getIndexNodeHash());
        }

        // Status information
        if (segment.getStatus() != null) {
            payload.put("status", segment.getStatus());
        }
        if (segment.getEnabled() != null) {
            payload.put("enabled", segment.getEnabled());
        }
        if (segment.getDisabledAt() != null) {
            payload.put("disabled_at", segment.getDisabledAt());
        }
        if (segment.getDisabledBy() != null) {
            payload.put("disabled_by", segment.getDisabledBy());
        }
        if (segment.getHitCount() != null) {
            payload.put("hit_count", segment.getHitCount());
        }

        // Timestamp information (epoch milliseconds)
        if (segment.getCreatedAt() != null) {
            payload.put("created_at", segment.getCreatedAt());
        }
        if (segment.getCreatedBy() != null) {
            payload.put("created_by", segment.getCreatedBy());
        }
        if (segment.getIndexingAt() != null) {
            payload.put("indexing_at", segment.getIndexingAt());
        }
        if (segment.getCompletedAt() != null) {
            payload.put("completed_at", segment.getCompletedAt());
        }
        if (segment.getStoppedAt() != null) {
            payload.put("stopped_at", segment.getStoppedAt());
        }

        // Keywords
        if (segment.getKeywords() != null && !segment.getKeywords().isEmpty()) {
            payload.put("keywords", segment.getKeywords());
        }

        // Error information (if any)
        if (segment.getError() != null && !segment.getError().isEmpty()) {
            payload.put("error", segment.getError());
        }

        return payload;
    }
}
