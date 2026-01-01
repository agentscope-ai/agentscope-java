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
import java.util.List;
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

            // Build DocumentMetadata
            TextBlock content = TextBlock.builder().text(text).build();
            DocumentMetadata metadata = new DocumentMetadata(content, docId, chunkId);

            // Create Document
            Document document = new Document(metadata);

            // Set similarity score
            if (chunk.getScore() != null) {
                document.setScore(chunk.getScore());
            } else {
                document.setScore(0.0);
            }

            return document;
        } catch (Exception e) {
            logger.warn("Failed to convert RAGFlow chunk to document: {}", e.getMessage());
            return null;
        }
    }
}
