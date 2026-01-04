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
package io.agentscope.core.rag.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Document class representing a document chunk in the RAG system.
 *
 * <p>This is the core data structure for RAG operations. Each document contains
 * metadata, an optional embedding vector, and an optional similarity score.
 *
 * <p>The document ID is automatically generated as a deterministic UUID based on
 * the document metadata (doc_id, chunk_id, and content), ensuring consistent IDs
 * for the same content across different runs.
 */
public class Document {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String id;
    private final DocumentMetadata metadata;
    private double[] embedding;
    private Double score;

    /**
     * Creates a new Document instance.
     *
     * <p>The document ID is automatically generated as a deterministic UUID based on
     * the metadata (doc_id, chunk_id, and content).
     *
     * @param metadata the document metadata
     */
    public Document(DocumentMetadata metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("Metadata cannot be null");
        }
        this.metadata = metadata;
        this.id = generateDocumentId(metadata);
    }

    /**
     * Gets the document ID.
     *
     * @return the document ID (UUID string)
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the document metadata.
     *
     * @return the document metadata
     */
    public DocumentMetadata getMetadata() {
        return metadata;
    }

    /**
     * Gets the embedding vector.
     *
     * @return the embedding vector, or null if not set
     */
    public double[] getEmbedding() {
        return embedding;
    }

    /**
     * Sets the embedding vector.
     *
     * @param embedding the embedding vector
     */
    public void setEmbedding(double[] embedding) {
        this.embedding = embedding;
    }

    /**
     * Gets the similarity score.
     *
     * @return the similarity score, or null if not set
     */
    public Double getScore() {
        return score;
    }

    /**
     * Sets the similarity score.
     *
     * @param score the similarity score
     */
    public void setScore(Double score) {
        this.score = score;
    }

    /**
     * Gets the custom payload from document metadata.
     *
     * <p>This is a convenience method that delegates to the metadata's getPayload().
     * The payload contains business-specific fields such as filename, department,
     * author, tags, and other custom metadata.
     *
     * @return an unmodifiable map of custom metadata fields (never null)
     */
    public Map<String, Object> getPayload() {
        return metadata.getPayload();
    }

    /**
     * Gets a specific payload value by key.
     *
     * <p>This is a convenience method that delegates to the metadata's getPayloadValue().
     * Use this to retrieve individual payload values without accessing the entire map.
     *
     * @param key the payload key
     * @return the payload value, or null if the key doesn't exist
     * @throws NullPointerException if key is null
     */
    public Object getPayloadValue(String key) {
        return metadata.getPayloadValue(key);
    }

    /**
     * Gets a specific payload value by key and converts it to the specified type.
     *
     * <p>This method is useful when the payload contains complex objects (like custom POJOs)
     * that were serialized to Map during storage. It uses Jackson's ObjectMapper to convert
     * the Map back to the original type.
     *
     * @param <T> the target type
     * @param key the payload key
     * @param targetClass the target class to convert to
     * @return the payload value converted to the specified type, or null if the key doesn't exist
     * @throws IllegalArgumentException if the value cannot be converted to the target type
     * @throws NullPointerException if key or clazz is null
     */
    public <T> T getPayloadValueAs(String key, Class<T> targetClass) {
        Object value = getPayloadValue(key);
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.convertValue(value, targetClass);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(
                    String.format(
                            "Failed to convert payload value for key '%s' to type %s",
                            key, targetClass.getName()),
                    e);
        }
    }

    /**
     * Checks if the document payload contains a specific key.
     *
     * <p>This is a convenience method that delegates to the metadata's hasPayloadKey().
     * Use this to safely check for the existence of a payload field before retrieving it.
     *
     * @param key the payload key to check
     * @return true if the key exists in the payload, false otherwise
     * @throws NullPointerException if key is null
     */
    public boolean hasPayloadKey(String key) {
        return metadata.hasPayloadKey(key);
    }

    /**
     * Generates a deterministic document ID based on metadata.
     *
     * <p>This method creates a UUID v3 (name-based with MD5) from a JSON representation
     * of the document's key fields (doc_id, chunk_id, content). This ensures that the
     * same document content always generates the same ID, which is compatible with the
     * Python implementation's _map_text_to_uuid function.
     *
     * @param metadata the document metadata
     * @return a deterministic UUID string
     */
    private static String generateDocumentId(DocumentMetadata metadata) {
        try {
            // Create a map with doc_id, chunk_id, and content (matching Python implementation)
            Map<String, Object> keyMap = new LinkedHashMap<>();
            keyMap.put("doc_id", metadata.getDocId());
            keyMap.put("chunk_id", metadata.getChunkId());
            keyMap.put("content", metadata.getContent());

            // Serialize to JSON (ensure_ascii=False in Python, so we use default UTF-8)
            String jsonKey = OBJECT_MAPPER.writeValueAsString(keyMap);

            // Generate UUID v3 (name-based with MD5) from the JSON string
            return UUID.nameUUIDFromBytes(jsonKey.getBytes(StandardCharsets.UTF_8)).toString();
        } catch (JsonProcessingException e) {
            // Fallback: use a random UUID if JSON serialization fails
            throw new RuntimeException("Failed to generate document ID", e);
        }
    }

    @Override
    public String toString() {
        return String.format(
                "Document(id=%s, score=%s, content=%s)",
                id,
                score != null ? String.format("%.3f", score) : "null",
                metadata.getContentText());
    }
}
