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

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.integration.ragflow.model.RAGFlowChunk;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converter for transforming RAGFlow API responses to AgentScope Document objects.
 *
 * <p>This class handles the conversion between RAGFlow's chunk format
 * and AgentScope's internal Document representation, ensuring seamless integration
 * with the RAG system.
 */
public class RAGFlowDocumentConverter {

    private static final Logger logger = LoggerFactory.getLogger(RAGFlowDocumentConverter.class);

    private RAGFlowDocumentConverter() {
        // Utility class, prevent instantiation
    }

    /**
     * Converts a list of RAGFlow chunks to Documents.
     *
     * @param chunks the RAGFlow chunks from retrieve response
     * @return a list of Document objects
     */
    public static List<Document> convertToDocuments(List<RAGFlowChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return new ArrayList<>();
        }

        List<Document> documents = new ArrayList<>();
        for (RAGFlowChunk chunk : chunks) {
            Document doc = convertToDocument(chunk);
            if (doc != null) {
                documents.add(doc);
            }
        }
        return documents;
    }

    /**
     * Converts a single RAGFlow chunk to a Document.
     *
     * @param chunk the RAGFlow chunk from retrieve response
     * @return a Document object, or null if conversion fails
     */
    public static Document convertToDocument(RAGFlowChunk chunk) {
        if (chunk == null) {
            return null;
        }

        try {
            // Extract text content
            String text = chunk.getContent();
            if (text == null || text.trim().isEmpty()) {
                return null;
            }

            // Extract document ID (fallback to chunk ID)
            String docId = chunk.getDocumentId();
            if (docId == null || docId.isEmpty()) {
                docId = chunk.getId() != null ? chunk.getId() : "unknown";
            }

            // Extract chunk ID
            String chunkId = chunk.getId();
            if (chunkId == null || chunkId.isEmpty()) {
                chunkId = "0";
            }

            // Build payload from chunk metadata
            Map<String, Object> payload = buildPayloadFromChunk(chunk);

            // Build DocumentMetadata with payload
            TextBlock content = TextBlock.builder().text(text).build();
            DocumentMetadata metadata = new DocumentMetadata(content, docId, chunkId, payload);

            // Create Document
            Document document = new Document(metadata);

            // Set similarity score
            if (chunk.getScore() != null) {
                document.setScore(chunk.getScore());
            } else {
                document.setScore(0.0);
            }

            logger.debug(
                    "Converted RAGFlow chunk to document: id={}, docName={}, score={},"
                            + " contentLength={}",
                    docId,
                    payload.getOrDefault("document_name", "unknown"),
                    chunk.getScore(),
                    text.length());

            return document;
        } catch (Exception e) {
            logger.warn("Failed to convert RAGFlow chunk to document: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Builds payload map from RAGFlow chunk metadata.
     *
     * <p>This method extracts relevant metadata fields from the RAGFlow Chunk object
     * and converts them into a payload map, preserving the original structure and naming.
     *
     * <p>Key principles:
     * <ul>
     *   <li>Use underscore naming as in API response</li>
     *   <li>Skip fields already used (id, document_id, content)</li>
     *   <li>Preserve all other metadata fields</li>
     * </ul>
     *
     * @param chunk the RAGFlow chunk containing metadata fields
     * @return a map of metadata key-value pairs (never null)
     */
    private static Map<String, Object> buildPayloadFromChunk(RAGFlowChunk chunk) {
        Map<String, Object> payload = new HashMap<>();

        // Document information (skip document_id as it's already used as docId)
        if (chunk.getDocumentKeyword() != null) {
            payload.put("document_keyword", chunk.getDocumentKeyword());
        }
        if (chunk.getKbId() != null) {
            payload.put("kb_id", chunk.getKbId());
        }

        // Chunk information (skip id and content as they're already used)
        if (chunk.getContentLtks() != null && !chunk.getContentLtks().isEmpty()) {
            payload.put("content_ltks", chunk.getContentLtks());
        }
        if (chunk.getHighlight() != null && !chunk.getHighlight().isEmpty()) {
            payload.put("highlight", chunk.getHighlight());
        }

        // Similarity scores
        if (chunk.getVectorSimilarity() != null) {
            payload.put("vector_similarity", chunk.getVectorSimilarity());
        }
        if (chunk.getTermSimilarity() != null) {
            payload.put("term_similarity", chunk.getTermSimilarity());
        }

        // Position information
        if (chunk.getPositions() != null && !chunk.getPositions().isEmpty()) {
            payload.put("positions", chunk.getPositions());
        }

        // Additional fields
        if (chunk.getImageId() != null && !chunk.getImageId().isEmpty()) {
            payload.put("image_id", chunk.getImageId());
        }
        if (chunk.getImportantKeywords() != null && !chunk.getImportantKeywords().isEmpty()) {
            payload.put("important_keywords", chunk.getImportantKeywords());
        }

        // Custom metadata (if any) - preserve as nested object
        if (chunk.getMetadata() != null && !chunk.getMetadata().isEmpty()) {
            payload.put("metadata", chunk.getMetadata());
        }

        if (chunk.getDatasetId() != null) {
            payload.put("dataset_id", chunk.getDatasetId());
        }

        if (chunk.getDocumentName() != null) {
            payload.put("document_name", chunk.getDocumentName());
        }

        return payload;
    }
}
