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
package io.agentscope.core.rag.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.ListenableFuture;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.NullValue;
import io.qdrant.client.grpc.JsonWithInt.Struct;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.Vector;
import io.qdrant.client.grpc.Points.Vectors;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import io.qdrant.client.grpc.Points.WithVectorsSelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Qdrant vector database store implementation.
 *
 * <p>This class provides an interface for storing and searching vectors using Qdrant, a
 * production-ready vector database. It implements the VDBStoreBase interface to provide
 * a unified API for vector storage operations.
 *
 * <p>In Qdrant, we use the payload field to store the metadata, including the document ID,
 * chunk ID, and original content.
 *
 * <p>This implementation uses the Qdrant Java SDK (io.qdrant:client). To use this class,
 * add the following dependency:
 *
 * <pre>{@code
 * <dependency>
 *     <groupId>io.qdrant</groupId>
 *     <artifactId>client</artifactId>
 *     <version>1.15.0</version>
 *     <scope>provided</scope>
 * </dependency>
 * }</pre>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Using builder with TLS and compatibility options (recommended)
 * try (QdrantStore store = QdrantStore.builder()
 *         .location("http://localhost:6333")
 *         .collectionName("my_collection")
 *         .dimensions(1024)
 *         .apiKey("my-api-key")
 *         .useTransportLayerSecurity(true)
 *         .checkCompatibility(false)
 *         .build()) {
 *     store.add(document).block();
 *     List<Document> results = store.search(queryEmbedding, 5).block();
 *     // Store is automatically closed here
 * }
 *
 * // Using static factory method (simpler, uses defaults)
 * try (QdrantStore store = QdrantStore.create("http://localhost:6333", "my_collection", 1024)) {
 *     store.add(document).block();
 *     List<Document> results = store.search(queryEmbedding, 5).block();
 * }
 * }</pre>
 *
 * <p>Note: The location parameter can be:
 * <ul>
 *   <li>HTTP URL: "http://localhost:6333" - will use gRPC on port 6334</li>
 *   <li>gRPC URL: "localhost:6334" - direct gRPC connection</li>
 *   <li>Local path: "file:///path/to/qdrant" - local Qdrant instance</li>
 *   <li>Memory mode: ":memory:" - in-memory Qdrant (requires local Qdrant)</li>
 * </ul>
 */
public class QdrantStore implements VDBStoreBase, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QdrantStore.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String location;
    private final String collectionName;
    private final int dimensions;
    private final String apiKey;
    private final boolean useTransportLayerSecurity;
    private final boolean checkCompatibility;
    private final QdrantClient qdrantClient;
    private final QdrantGrpcClient grpcClient;
    private volatile boolean closed = false;

    /**
     * Creates a new QdrantStore using the builder configuration.
     *
     * @param builder the builder instance
     * @throws VectorStoreException if client initialization or collection creation fails
     */
    private QdrantStore(Builder builder) throws VectorStoreException {
        this.location = builder.location;
        this.collectionName = builder.collectionName;
        this.dimensions = builder.dimensions;
        this.apiKey = builder.apiKey;
        this.useTransportLayerSecurity = builder.useTransportLayerSecurity;
        this.checkCompatibility = builder.checkCompatibility;

        // Initialize client and collection immediately
        QdrantGrpcClient tempGrpcClient = null;
        QdrantClient tempQdrantClient = null;

        try {
            ConnectionInfo connInfo = parseLocation(location);

            // Build gRPC client with TLS and compatibility check options
            QdrantGrpcClient.Builder grpcClientBuilder =
                    QdrantGrpcClient.newBuilder(
                            connInfo.host,
                            connInfo.port,
                            useTransportLayerSecurity,
                            checkCompatibility);

            if (apiKey != null && !apiKey.trim().isEmpty()) {
                grpcClientBuilder = grpcClientBuilder.withApiKey(apiKey);
            }

            tempGrpcClient = grpcClientBuilder.build();

            // Create QdrantClient
            tempQdrantClient = new QdrantClient(tempGrpcClient);

            log.debug(
                    "Initialized Qdrant client: host={}, port={}, collection={}",
                    connInfo.host,
                    connInfo.port,
                    collectionName);

            // Ensure collection exists
            ensureCollection(tempQdrantClient);

            // Assign to final fields only after successful initialization
            this.grpcClient = tempGrpcClient;
            this.qdrantClient = tempQdrantClient;

            log.debug("QdrantStore initialized successfully for collection: {}", collectionName);
        } catch (Exception e) {
            // Clean up if initialization fails
            try {
                if (tempQdrantClient != null) {
                    tempQdrantClient.close();
                }
            } catch (Exception cleanupException) {
                log.warn(
                        "Error closing QdrantClient after initialization failure",
                        cleanupException);
            }
            try {
                if (tempGrpcClient != null) {
                    tempGrpcClient.close();
                }
            } catch (Exception cleanupException) {
                log.warn(
                        "Error closing QdrantGrpcClient after initialization failure",
                        cleanupException);
            }
            throw new VectorStoreException("Failed to initialize QdrantStore", e);
        }
    }

    @Override
    public Mono<Void> add(List<Document> documents) {
        if (documents == null) {
            return Mono.error(new IllegalArgumentException("Documents list cannot be null"));
        }
        if (documents.isEmpty()) {
            return Mono.empty();
        }

        // Validate all documents
        for (Document document : documents) {
            if (document == null) {
                return Mono.error(new IllegalArgumentException("Document cannot be null"));
            }
            if (document.getEmbedding() == null) {
                return Mono.error(new IllegalArgumentException("Document must have embedding set"));
            }
            if (document.getEmbedding().length != dimensions) {
                return Mono.error(
                        new VectorStoreException(
                                String.format(
                                        "Embedding dimension mismatch: expected %d, got %d",
                                        dimensions, document.getEmbedding().length)));
            }
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                ensureNotClosed();
                                addDocumentsToQdrant(documents);
                                return null;
                            } catch (Exception e) {
                                throw new VectorStoreException(
                                        "Failed to add documents to Qdrant", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                e instanceof VectorStoreException
                                        ? e
                                        : new VectorStoreException(
                                                "Failed to add documents to Qdrant", e))
                .then();
    }

    @Override
    public Mono<List<Document>> search(double[] queryEmbedding, int limit, Double scoreThreshold) {
        if (queryEmbedding == null) {
            return Mono.error(new IllegalArgumentException("Query embedding cannot be null"));
        }
        if (queryEmbedding.length != dimensions) {
            return Mono.error(
                    new VectorStoreException(
                            String.format(
                                    "Query embedding dimension mismatch: expected %d, got %d",
                                    dimensions, queryEmbedding.length)));
        }
        if (limit <= 0) {
            return Mono.error(new IllegalArgumentException("Limit must be positive"));
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                ensureNotClosed();
                                return searchDocumentsInQdrant(
                                        queryEmbedding, limit, scoreThreshold);
                            } catch (Exception e) {
                                throw new VectorStoreException(
                                        "Failed to search documents in Qdrant", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                e instanceof VectorStoreException
                                        ? e
                                        : new VectorStoreException(
                                                "Failed to search documents in Qdrant", e));
    }

    @Override
    public Mono<Boolean> delete(String id) {
        // Delete is not implemented for QdrantStore (matching Python implementation)
        return Mono.error(
                new UnsupportedOperationException("Delete is not implemented for QdrantStore"));
    }

    /**
     * Gets the underlying Qdrant client for advanced operations.
     *
     * <p>This method provides access to the Qdrant client for users who need to perform
     * operations beyond the standard VDBStoreBase interface, such as:
     * <ul>
     *   <li>Custom index configuration
     *   <li>Advanced filtering and faceting
     *   <li>Collection management
     *   <li>Batch operations
     * </ul>
     *
     * @return the QdrantClient instance
     * @throws VectorStoreException if the store has been closed
     */
    public QdrantClient getClient() throws VectorStoreException {
        ensureNotClosed();
        return qdrantClient;
    }

    /**
     * Gets the underlying gRPC client for low-level operations.
     *
     * <p>This provides direct access to the gRPC client for advanced users who need
     * maximum control over Qdrant operations.
     *
     * @return the QdrantGrpcClient instance
     * @throws VectorStoreException if the store has been closed
     */
    public QdrantGrpcClient getGrpcClient() throws VectorStoreException {
        ensureNotClosed();
        return grpcClient;
    }

    /**
     * Ensures the store is not closed before performing operations.
     *
     * @throws VectorStoreException if the store has been closed
     */
    private void ensureNotClosed() throws VectorStoreException {
        if (closed) {
            throw new VectorStoreException("QdrantStore has been closed");
        }
    }

    /**
     * Ensures the collection exists, creating it if necessary.
     *
     * @param client the QdrantClient to use
     * @throws VectorStoreException if collection creation fails
     */
    private void ensureCollection(QdrantClient client) throws VectorStoreException {
        try {
            // Check if collection exists
            ListenableFuture<Boolean> existsFuture = client.collectionExistsAsync(collectionName);
            Boolean exists = existsFuture.get();
            if (Boolean.TRUE.equals(exists)) {
                log.debug("Collection '{}' already exists", collectionName);
                return;
            }

            // Create collection
            createCollection(client);
            log.debug("Created collection '{}' with dimensions {}", collectionName, dimensions);
        } catch (Exception e) {
            throw new VectorStoreException(
                    "Failed to ensure collection exists: " + collectionName, e);
        }
    }

    /**
     * Creates a new collection with the specified dimensions.
     *
     * @param client the QdrantClient to use
     * @throws Exception if collection creation fails
     */
    private void createCollection(QdrantClient client) throws Exception {
        VectorParams vectorParams =
                VectorParams.newBuilder().setDistance(Distance.Cosine).setSize(dimensions).build();

        ListenableFuture<?> future = client.createCollectionAsync(collectionName, vectorParams);
        future.get();
    }

    /**
     * Adds multiple documents to Qdrant.
     *
     * <p>In Qdrant, we use the payload field to store the metadata, including the document ID,
     * chunk ID, and original content.
     *
     * <p>Point IDs are generated as deterministic UUIDs based on doc_id, chunk_id, and content,
     * matching the Python implementation's _map_text_to_uuid function.
     *
     * @param documents the documents to store (all must have embeddings set)
     * @throws Exception if the operation fails
     */
    private void addDocumentsToQdrant(List<Document> documents) throws Exception {
        List<PointStruct> points =
                documents.stream()
                        .map(
                                document -> {
                                    try {
                                        // Convert double[] to List<Float>
                                        double[] embedding = document.getEmbedding();
                                        List<Float> floatList = new ArrayList<>(embedding.length);
                                        for (double d : embedding) {
                                            floatList.add((float) d);
                                        }

                                        // Generate deterministic UUID point ID
                                        String pointIdStr = generatePointId(document.getMetadata());
                                        PointId pointId =
                                                PointId.newBuilder().setUuid(pointIdStr).build();

                                        // Build Vector
                                        Vector vector =
                                                Vector.newBuilder().addAllData(floatList).build();
                                        Vectors vectors =
                                                Vectors.newBuilder().setVector(vector).build();

                                        // Convert DocumentMetadata to payload map
                                        Map<String, Value> payloadMap =
                                                convertMetadataToPayload(document.getMetadata());

                                        // Build PointStruct with vector and payload
                                        PointStruct.Builder pointBuilder =
                                                PointStruct.newBuilder()
                                                        .setId(pointId)
                                                        .setVectors(vectors);
                                        for (Map.Entry<String, Value> entry :
                                                payloadMap.entrySet()) {
                                            pointBuilder.putPayload(
                                                    entry.getKey(), entry.getValue());
                                        }
                                        return pointBuilder.build();
                                    } catch (Exception e) {
                                        throw new RuntimeException(
                                                "Failed to convert document to PointStruct", e);
                                    }
                                })
                        .collect(Collectors.toList());

        // Upsert points
        ListenableFuture<?> future = qdrantClient.upsertAsync(collectionName, points);
        future.get();
    }

    /**
     * Generates a deterministic UUID point ID based on metadata.
     *
     * <p>This matches the Python implementation's _map_text_to_uuid function, which generates
     * a UUID v3 from a JSON representation of doc_id, chunk_id, and content.
     *
     * @param metadata the document metadata
     * @return a deterministic UUID string
     */
    private String generatePointId(DocumentMetadata metadata) {
        try {
            Map<String, Object> keyMap = new LinkedHashMap<>();
            keyMap.put("doc_id", metadata.getDocId());
            keyMap.put("chunk_id", metadata.getChunkId());
            keyMap.put("content", metadata.getContent());

            String jsonKey = OBJECT_MAPPER.writeValueAsString(keyMap);
            return UUID.nameUUIDFromBytes(jsonKey.getBytes(StandardCharsets.UTF_8)).toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate point ID", e);
        }
    }

    /**
     * Converts DocumentMetadata to Qdrant payload map.
     *
     * <p>Serializes the ContentBlock to JSON and stores it as a Qdrant Value.
     *
     * @param metadata the document metadata
     * @return a map of payload key-value pairs
     */
    private Map<String, Value> convertMetadataToPayload(DocumentMetadata metadata) {
        Map<String, Value> payloadMap = new HashMap<>();

        try {
            // Serialize ContentBlock to JSON, then convert to Qdrant Value
            String contentJson = OBJECT_MAPPER.writeValueAsString(metadata.getContent());
            @SuppressWarnings("unchecked")
            Map<String, Object> contentMap = OBJECT_MAPPER.readValue(contentJson, Map.class);
            Value contentValue = convertObjectToValue(contentMap);
            payloadMap.put("content", contentValue);
        } catch (Exception e) {
            log.error("Failed to serialize ContentBlock, using fallback", e);
            // Fallback: store as string
            Value contentValue =
                    Value.newBuilder().setStringValue(metadata.getContentText()).build();
            payloadMap.put("content", contentValue);
        }

        // Store doc_id
        Value docIdValue = Value.newBuilder().setStringValue(metadata.getDocId()).build();
        payloadMap.put("doc_id", docIdValue);

        // Store chunk_id
        Value chunkIdValue = Value.newBuilder().setStringValue(metadata.getChunkId()).build();
        payloadMap.put("chunk_id", chunkIdValue);

        return payloadMap;
    }

    /**
     * Converts a Java Object to Qdrant Value.
     *
     * @param obj the object to convert
     * @return the Qdrant Value
     */
    private Value convertObjectToValue(Object obj) {
        if (obj == null) {
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        } else if (obj instanceof String) {
            return Value.newBuilder().setStringValue((String) obj).build();
        } else if (obj instanceof Number) {
            if (obj instanceof Double || obj instanceof Float) {
                return Value.newBuilder().setDoubleValue(((Number) obj).doubleValue()).build();
            } else {
                return Value.newBuilder().setIntegerValue(((Number) obj).longValue()).build();
            }
        } else if (obj instanceof Boolean) {
            return Value.newBuilder().setBoolValue((Boolean) obj).build();
        } else if (obj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) obj;
            Struct.Builder structBuilder = Struct.newBuilder();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                structBuilder.putFields(entry.getKey(), convertObjectToValue(entry.getValue()));
            }
            return Value.newBuilder().setStructValue(structBuilder.build()).build();
        } else if (obj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) obj;
            io.qdrant.client.grpc.JsonWithInt.ListValue.Builder listBuilder =
                    io.qdrant.client.grpc.JsonWithInt.ListValue.newBuilder();
            for (Object item : list) {
                listBuilder.addValues(convertObjectToValue(item));
            }
            return Value.newBuilder().setListValue(listBuilder.build()).build();
        } else {
            // Fallback to string representation
            return Value.newBuilder().setStringValue(obj.toString()).build();
        }
    }

    /**
     * Searches for similar documents in Qdrant.
     *
     * @param queryEmbedding the query embedding vector
     * @param limit the maximum number of results
     * @param scoreThreshold optional minimum score threshold
     * @return a list of documents with scores and embeddings set
     * @throws Exception if the operation fails
     */
    private List<Document> searchDocumentsInQdrant(
            double[] queryEmbedding, int limit, Double scoreThreshold) throws Exception {
        // Convert double[] to List<Float>
        List<Float> floatList = new ArrayList<>(queryEmbedding.length);
        for (double d : queryEmbedding) {
            floatList.add((float) d);
        }

        // Build SearchPoints
        SearchPoints.Builder searchBuilder =
                SearchPoints.newBuilder()
                        .setCollectionName(collectionName)
                        .addAllVector(floatList)
                        .setLimit(limit)
                        .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                        .setWithVectors(
                                WithVectorsSelector.newBuilder()
                                        .setEnable(true)
                                        .build()); // Request vectors to include embeddings

        // Add score threshold if specified
        if (scoreThreshold != null) {
            searchBuilder.setScoreThreshold(scoreThreshold.floatValue());
        }

        SearchPoints searchPoints = searchBuilder.build();

        // Search
        ListenableFuture<List<ScoredPoint>> future = qdrantClient.searchAsync(searchPoints);
        List<ScoredPoint> scoredPoints = future.get();

        // Extract results and reconstruct documents
        List<Document> results = new ArrayList<>();
        for (ScoredPoint scoredPoint : scoredPoints) {
            // Get ID (UUID string)
            PointId pointId = scoredPoint.getId();
            String id = pointId.getUuid();

            // Get score
            double score = scoredPoint.getScore();

            // Reconstruct document from payload and vectors
            Document document = reconstructDocumentFromPayload(scoredPoint, id, score);
            if (document != null) {
                results.add(document);
            } else {
                log.warn("Failed to reconstruct document from payload for ID: {}", id);
            }
        }

        return results;
    }

    /**
     * Reconstructs a Document from Qdrant payload and vectors.
     *
     * <p>Reconstructs DocumentMetadata directly from payload map and extracts the embedding
     * vector, similar to Python implementation: Document(metadata=DocMetadata(**point.payload),
     * embedding=point.vector, score=point.score)
     *
     * @param scoredPoint the scored point from Qdrant
     * @param id the document ID (UUID string)
     * @param score the similarity score
     * @return the reconstructed Document, or null if reconstruction fails
     */
    private Document reconstructDocumentFromPayload(
            ScoredPoint scoredPoint, String id, double score) {
        try {
            Map<String, Value> payload = scoredPoint.getPayloadMap();
            if (payload == null || payload.isEmpty()) {
                log.warn("Payload missing for document ID: {}", id);
                return null;
            }

            // Reconstruct DocumentMetadata from payload
            DocumentMetadata metadata = reconstructMetadataFromPayload(payload);

            Document document = new Document(metadata);
            document.setScore(score);

            // Extract embedding from vectors if available
            if (scoredPoint.hasVectors()) {
                var vectorsOutput = scoredPoint.getVectors();
                if (vectorsOutput.hasVector()) {
                    var vectorOutput = vectorsOutput.getVector();
                    List<Float> floatList = vectorOutput.getDataList();
                    double[] embedding = new double[floatList.size()];
                    for (int i = 0; i < floatList.size(); i++) {
                        embedding[i] = floatList.get(i);
                    }
                    document.setEmbedding(embedding);
                }
            }

            return document;
        } catch (Exception e) {
            log.error("Failed to reconstruct document from payload for ID: {}", id, e);
            return null;
        }
    }

    /**
     * Reconstructs DocumentMetadata from Qdrant payload map.
     *
     * <p>Deserializes the content field from JSON to ContentBlock.
     *
     * @param payload the payload map from Qdrant
     * @return the reconstructed DocumentMetadata
     * @throws IllegalArgumentException if required fields are missing
     */
    private DocumentMetadata reconstructMetadataFromPayload(Map<String, Value> payload) {
        // Extract content and deserialize to ContentBlock
        Value contentValue = payload.get("content");
        if (contentValue == null) {
            throw new IllegalArgumentException("Payload missing 'content' field");
        }

        ContentBlock content;
        try {
            // Convert Qdrant Value to Map, then to JSON, then to ContentBlock
            Object contentObj = convertValueToObject(contentValue);
            String contentJson = OBJECT_MAPPER.writeValueAsString(contentObj);
            content = OBJECT_MAPPER.readValue(contentJson, ContentBlock.class);
        } catch (Exception e) {
            log.error("Failed to deserialize ContentBlock from payload, using fallback", e);
            // Fallback: create a TextBlock from string representation
            String textContent = contentValue.toString();
            content = io.agentscope.core.message.TextBlock.builder().text(textContent).build();
        }

        // Extract doc_id
        Value docIdValue = payload.get("doc_id");
        if (docIdValue == null || !docIdValue.hasStringValue()) {
            throw new IllegalArgumentException("Payload missing 'doc_id' field");
        }
        String docId = docIdValue.getStringValue();

        // Extract chunk_id
        Value chunkIdValue = payload.get("chunk_id");
        if (chunkIdValue == null || !chunkIdValue.hasStringValue()) {
            throw new IllegalArgumentException("Payload missing 'chunk_id' field");
        }
        String chunkId = chunkIdValue.getStringValue();

        return new DocumentMetadata(content, docId, chunkId);
    }

    /**
     * Converts a Qdrant Value to Java Object.
     *
     * @param value the Qdrant Value
     * @return the Java Object
     */
    private Object convertValueToObject(Value value) {
        if (value.hasNullValue()) {
            return null;
        } else if (value.hasStringValue()) {
            return value.getStringValue();
        } else if (value.hasIntegerValue()) {
            return value.getIntegerValue();
        } else if (value.hasDoubleValue()) {
            return value.getDoubleValue();
        } else if (value.hasBoolValue()) {
            return value.getBoolValue();
        } else if (value.hasStructValue()) {
            Struct struct = value.getStructValue();
            Map<String, Object> map = new HashMap<>();
            for (Map.Entry<String, Value> entry : struct.getFieldsMap().entrySet()) {
                map.put(entry.getKey(), convertValueToObject(entry.getValue()));
            }
            return map;
        } else if (value.hasListValue()) {
            io.qdrant.client.grpc.JsonWithInt.ListValue listValue = value.getListValue();
            List<Object> list = new ArrayList<>();
            for (Value item : listValue.getValuesList()) {
                list.add(convertValueToObject(item));
            }
            return list;
        } else {
            throw new IllegalArgumentException("Unknown value type: " + value.getKindCase());
        }
    }

    /**
     * Parses the location string to extract host and port information.
     *
     * @param location the location string (HTTP URL, gRPC URL, or file path)
     * @return connection information
     */
    private ConnectionInfo parseLocation(String location) {
        // Handle :memory: mode
        if (":memory:".equals(location)) {
            return new ConnectionInfo("localhost", 6334);
        }

        // Handle file:// protocol (local Qdrant)
        if (location.startsWith("file://")) {
            return new ConnectionInfo("localhost", 6334);
        }

        try {
            URI uri = URI.create(location);
            String host = uri.getHost();
            int port = uri.getPort();

            // If it's an HTTP URL, use gRPC port (6334) instead of HTTP port (6333)
            if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                if (port == 6333 || port == -1) {
                    port = 6334; // Default gRPC port
                }
            }

            // If no port specified and no scheme, assume it's a direct gRPC connection
            if (host == null && port == -1) {
                // Try parsing as host:port
                if (location.contains(":")) {
                    String[] parts = location.split(":", 2);
                    host = parts[0];
                    try {
                        port = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        port = 6334; // Default gRPC port
                    }
                } else {
                    host = location;
                    port = 6334; // Default gRPC port
                }
            }

            if (host == null) {
                host = "localhost";
            }
            if (port == -1) {
                port = 6334; // Default gRPC port
            }

            return new ConnectionInfo(host, port);
        } catch (Exception e) {
            // Fallback: treat as hostname
            log.warn("Failed to parse location '{}', using as hostname", location, e);
            return new ConnectionInfo(location, 6334);
        }
    }

    /**
     * Connection information holder.
     */
    private static class ConnectionInfo {
        final String host;
        final int port;

        ConnectionInfo(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /**
     * Gets the Qdrant server location.
     *
     * @return the location
     */
    public String getLocation() {
        return location;
    }

    /**
     * Gets the collection name.
     *
     * @return the collection name
     */
    public String getCollectionName() {
        return collectionName;
    }

    /**
     * Gets the vector dimensions.
     *
     * @return the dimensions
     */
    public int getDimensions() {
        return dimensions;
    }

    /**
     * Gets the API key (if set).
     *
     * @return the API key, or null if not set
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Closes the Qdrant client and releases all resources.
     *
     * <p>This method closes both the QdrantClient and the underlying QdrantGrpcClient,
     * releasing all gRPC connections and associated resources. This method is idempotent
     * and can be called multiple times safely.
     *
     * <p>After closing, all operations on this store will fail with a VectorStoreException.
     * It's recommended to use try-with-resources for automatic resource management:
     *
     * <pre>{@code
     * try (QdrantStore store = new QdrantStore("http://localhost:6333", "collection", 1024)) {
     *     store.add(1L, embedding).block();
     *     // Store is automatically closed here
     * }
     * }</pre>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }

        synchronized (this) {
            if (closed) {
                return;
            }

            closed = true;

            try {
                if (qdrantClient != null) {
                    log.debug("Closing QdrantClient for collection: {}", collectionName);
                    qdrantClient.close();
                }
            } catch (Exception e) {
                log.warn("Error closing QdrantClient", e);
            }

            try {
                if (grpcClient != null) {
                    log.debug("Closing QdrantGrpcClient for collection: {}", collectionName);
                    grpcClient.close();
                }
            } catch (Exception e) {
                log.warn("Error closing QdrantGrpcClient", e);
            }

            log.debug("QdrantStore closed for collection: {}", collectionName);
        }
    }

    /**
     * Checks if this store has been closed.
     *
     * @return true if the store has been closed, false otherwise
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Builder for creating QdrantStore instances with fluent configuration.
     *
     * <p>Example usage:
     * <pre>{@code
     * QdrantStore store = QdrantStore.builder()
     *     .location("http://localhost:6333")
     *     .collectionName("my_collection")
     *     .dimensions(1024)
     *     .apiKey("my-api-key")
     *     .useTransportLayerSecurity(true)
     *     .checkCompatibility(false)
     *     .build();
     * }</pre>
     */
    public static class Builder {
        private String location;
        private String collectionName;
        private int dimensions;
        private String apiKey;
        private boolean useTransportLayerSecurity = true;
        private boolean checkCompatibility = true;

        private Builder() {}

        /**
         * Sets the Qdrant server location.
         *
         * @param location the Qdrant server location (e.g., "http://localhost:6333" or
         *     "localhost:6334")
         * @return this builder
         */
        public Builder location(String location) {
            this.location = location;
            return this;
        }

        /**
         * Sets the collection name.
         *
         * @param collectionName the name of the collection to use
         * @return this builder
         */
        public Builder collectionName(String collectionName) {
            this.collectionName = collectionName;
            return this;
        }

        /**
         * Sets the vector dimensions.
         *
         * @param dimensions the dimension of vectors that will be stored
         * @return this builder
         */
        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        /**
         * Sets the API key for authentication.
         *
         * @param apiKey the API key (optional, null for no authentication)
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets whether to use Transport Layer Security (TLS) for gRPC connections.
         *
         * <p>Default is false. Set to true to enable TLS encryption for secure connections.
         *
         * @param useTransportLayerSecurity true to enable TLS, false otherwise
         * @return this builder
         */
        public Builder useTransportLayerSecurity(boolean useTransportLayerSecurity) {
            this.useTransportLayerSecurity = useTransportLayerSecurity;
            return this;
        }

        /**
         * Sets whether to check client-server compatibility.
         *
         * <p>Default is true. Set to false to skip version compatibility checks, which can be
         * useful when connecting to older Qdrant servers or when compatibility checks fail
         * incorrectly.
         *
         * @param checkCompatibility true to enable compatibility checks, false to skip
         * @return this builder
         */
        public Builder checkCompatibility(boolean checkCompatibility) {
            this.checkCompatibility = checkCompatibility;
            return this;
        }

        /**
         * Builds a new QdrantStore instance.
         *
         * <p>The client connection is established immediately during construction. If the
         * connection fails or the collection cannot be created, a VectorStoreException is thrown.
         *
         * @return a new QdrantStore instance
         * @throws IllegalArgumentException if required parameters are invalid
         * @throws VectorStoreException if client initialization or collection creation fails
         */
        public QdrantStore build() throws VectorStoreException {
            if (location == null || location.trim().isEmpty()) {
                throw new IllegalArgumentException("Location cannot be null or empty");
            }
            if (collectionName == null || collectionName.trim().isEmpty()) {
                throw new IllegalArgumentException("Collection name cannot be null or empty");
            }
            if (dimensions <= 0) {
                throw new IllegalArgumentException("Dimensions must be positive");
            }

            return new QdrantStore(this);
        }
    }

    /**
     * Creates a new builder for constructing QdrantStore instances.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
}
