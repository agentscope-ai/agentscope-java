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
import java.util.List;
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

        // Build DocumentMetadata
        TextBlock textBlock = TextBlock.builder().text(content).build();
        DocumentMetadata docMetadata = new DocumentMetadata(textBlock, docId, chunkId);

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
}
