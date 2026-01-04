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

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.integration.haystack.model.HayStackDocument;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converter for transforming HayStack API responses to AgentScope Document objects.
 *
 * <p>This class handles the conversion between HayStack RAG's document format
 * and AgentScope's internal Document representation, ensuring seamless integration
 * with the RAG system.
 */
public class HayStackDocumentConverter {

    private static final Logger logger = LoggerFactory.getLogger(HayStackDocumentConverter.class);

    private HayStackDocumentConverter() {
        // Utility class, prevent instantiation
    }

    /**
     * Converts a list of HayStackDocuments to Documents.
     *
     * @param hayStackDocuments the hayStackDocuments from retrieve response
     * @return a list of Document objects
     */
    public static List<Document> convertToDocuments(List<HayStackDocument> hayStackDocuments) {
        if (hayStackDocuments == null || hayStackDocuments.isEmpty()) {
            return new ArrayList<>();
        }

        List<Document> documents =
                hayStackDocuments.stream()
                        .map(HayStackDocumentConverter::convertToDocument)
                        .filter(Objects::nonNull)
                        .peek(
                                doc ->
                                        logger.debug(
                                                "Converted HayStackDocument to Document, id={}",
                                                doc.getId()))
                        .toList();

        logger.debug("Converted {} HayStackDocuments to Documents", hayStackDocuments.size());

        return documents;
    }

    /**
     * Converts a single HayStack chunk to a Document.
     *
     * @param hayStackDocument the HayStackDocument from retrieve response
     * @return a Document object, or null if conversion fails
     */
    public static Document convertToDocument(HayStackDocument hayStackDocument) {
        if (hayStackDocument == null) {
            logger.debug("HayStackDocument is null.");
            return null;
        }

        try {
            // Content -> TextBlock
            String content = hayStackDocument.getContent();
            if (content == null || content.isBlank()) {
                logger.debug(
                        "HayStackDocument content is null or empty, id={}",
                        hayStackDocument.getId());
                return null;
            }

            TextBlock textBlock = TextBlock.builder().text(content).build();

            // docId & chunkId -> DocumentMetadata
            Document document = getDocumentWithMetadata(hayStackDocument, textBlock);

            // embedding
            if (hayStackDocument.getEmbedding() != null
                    && !hayStackDocument.getEmbedding().isEmpty()) {
                double[] embedding =
                        hayStackDocument.getEmbedding().stream()
                                .mapToDouble(Double::doubleValue)
                                .toArray();
                document.setEmbedding(embedding);
            }

            // score
            if (hayStackDocument.getScore() != null) {
                document.setScore(hayStackDocument.getScore());
            }

            return document;

        } catch (Exception e) {
            logger.warn(
                    "Failed to convert HayStackDocument to Document, id={}",
                    hayStackDocument.getId(),
                    e);
            return null;
        }
    }

    private static @NonNull Document getDocumentWithMetadata(
            HayStackDocument hayStackDocument, TextBlock textBlock) {
        String docId = null;
        String chunkId = null;

        Map<String, Object> meta = hayStackDocument.getMeta();
        if (meta != null) {
            // use file_path as docId
            Object filePath = meta.get("file_path");
            if (filePath != null) {
                docId = filePath.toString();
            } else {
                // For the SentenceWindowRetriever, the file path is stored under the "source" key.
                Object sourceFilePath = meta.get("source");
                if (sourceFilePath != null) {
                    docId = sourceFilePath.toString();
                }
            }
        }

        // fallback strategies
        String id = hayStackDocument.getId();
        if (docId == null || docId.isEmpty()) {
            docId = (id == null || id.isBlank()) ? "unknown" : id;
        }

        chunkId = hayStackDocument.getId() != null ? hayStackDocument.getId() : "0";

        // Build payload from meta (store all metadata from HayStack)
        Map<String, Object> payload = new HashMap<>();
        if (meta != null && !meta.isEmpty()) {
            payload.putAll(meta);
        }

        // Build metadata with payload
        DocumentMetadata metadata = new DocumentMetadata(textBlock, docId, chunkId, payload);

        // Build document
        return new Document(metadata);
    }
}
