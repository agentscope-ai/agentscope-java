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
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.SearchResp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Milvus vector database store implementation.
 *
 * <p>This class provides an interface for storing and searching vectors using Milvus, a
 * cloud-native vector database. It implements the VDBStoreBase interface to provide
 * a unified API for vector storage operations.
 *
 * <p>In Milvus, we use dynamic fields to store the metadata, including the document ID,
 * chunk ID, and original content.
 *
 * <p>This implementation uses the Milvus Java SDK v2 (io.milvus:milvus-sdk-java). To use this
 * class, add the following dependency:
 *
 * <pre>{@code
 * <dependency>
 *     <groupId>io.milvus</groupId>
 *     <artifactId>milvus-sdk-java</artifactId>
 *     <version>2.5.9</version>
 * </dependency>
 * }</pre>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Using builder with authentication and timeout options (recommended)
 * try (MilvusStore store = MilvusStore.builder()
 *         .uri("http://localhost:19530")
 *         .collectionName("my_collection")
 *         .dimensions(1024)
 *         .token("root:Milvus")
 *         .connectTimeoutMs(30000)
 *         .build()) {
 *     store.add(document).block();
 *     List<Document> results = store.search(queryEmbedding, 5).block();
 *     // Store is automatically closed here
 * }
 *
 * // Using static factory method (simpler, uses defaults)
 * try (MilvusStore store = MilvusStore.create("http://localhost:19530", "my_collection", 1024)) {
 *     store.add(document).block();
 *     List<Document> results = store.search(queryEmbedding, 5).block();
 * }
 * }</pre>
 *
 * <p>Authentication options:
 * <ul>
 *   <li>Token-based: Use token("username:password") format</li>
 *   <li>Username/Password: Use username() and password() methods</li>
 *   <li>No auth: Leave token/username/password empty for local instances</li>
 * </ul>
 *
 * <p>Note: The uri parameter should be a full HTTP URL including protocol and port,
 * for example: "http://localhost:19530" or "https://milvus.example.com:19530"
 */
public class MilvusStore implements VDBStoreBase, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MilvusStore.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Gson GSON = new Gson();

    // Field names for Milvus collection schema
    private static final String FIELD_ID = "id";
    private static final String FIELD_VECTOR = "vector";
    private static final String FIELD_DOC_ID = "doc_id";
    private static final String FIELD_CHUNK_ID = "chunk_id";
    private static final String FIELD_CONTENT = "content";

    // Default configuration values
    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 30000L;

    private final String uri;
    private final String collectionName;
    private final int dimensions;
    private final IndexParam.MetricType metricType;
    private final MilvusClientV2 milvusClient;
    private volatile boolean closed = false;

    /**
     * Creates a new MilvusStore using the builder configuration.
     *
     * @param builder the builder instance
     * @throws VectorStoreException if client initialization or collection creation fails
     */
    @SuppressWarnings("rawtypes") // Milvus SDK uses raw types in builder pattern
    private MilvusStore(Builder builder) throws VectorStoreException {
        this.uri = builder.uri;
        this.collectionName = builder.collectionName;
        this.dimensions = builder.dimensions;
        this.metricType = builder.metricType;
        String token = builder.token;
        String username = builder.username;
        String password = builder.password;
        long connectTimeoutMs = builder.connectTimeoutMs;

        // Initialize client and collection immediately
        MilvusClientV2 tempClient = null;

        try {
            // Build connection config
            ConnectConfig.ConnectConfigBuilder configBuilder = ConnectConfig.builder().uri(uri);

            // Set authentication
            if (token != null && !token.trim().isEmpty()) {
                configBuilder.token(token);
            } else if (username != null && !username.trim().isEmpty()) {
                configBuilder.token(username + ":" + (password != null ? password : ""));
            }

            // Set timeout
            if (connectTimeoutMs > 0) {
                configBuilder.connectTimeoutMs(connectTimeoutMs);
            }

            ConnectConfig config = configBuilder.build();
            tempClient = new MilvusClientV2(config);

            log.debug("Initialized Milvus client: uri={}, collection={}", uri, collectionName);

            // Ensure collection exists
            ensureCollection(tempClient);

            // Assign to final field only after successful initialization
            this.milvusClient = tempClient;

            log.debug("MilvusStore initialized successfully for collection: {}", collectionName);
        } catch (Exception e) {
            // Clean up if initialization fails
            try {
                if (tempClient != null) {
                    tempClient.close();
                }
            } catch (Exception cleanupException) {
                log.warn(
                        "Error closing MilvusClient after initialization failure",
                        cleanupException);
            }
            throw new VectorStoreException("Failed to initialize MilvusStore", e);
        }
    }

    /**
     * Creates a new MilvusStore with minimal configuration.
     *
     * @param uri the Milvus server URI (e.g., "http://localhost:19530")
     * @param collectionName the name of the collection to use
     * @param dimensions the dimension of vectors that will be stored
     * @return a new MilvusStore instance
     * @throws VectorStoreException if initialization fails
     */
    public static MilvusStore create(String uri, String collectionName, int dimensions)
            throws VectorStoreException {
        return builder().uri(uri).collectionName(collectionName).dimensions(dimensions).build();
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
                                addDocumentsToMilvus(documents);
                                return null;
                            } catch (Exception e) {
                                throw new VectorStoreException(
                                        "Failed to add documents to Milvus", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                e instanceof VectorStoreException
                                        ? e
                                        : new VectorStoreException(
                                                "Failed to add documents to Milvus", e))
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
                                return searchDocumentsInMilvus(
                                        queryEmbedding, limit, scoreThreshold);
                            } catch (Exception e) {
                                throw new VectorStoreException(
                                        "Failed to search documents in Milvus", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                e instanceof VectorStoreException
                                        ? e
                                        : new VectorStoreException(
                                                "Failed to search documents in Milvus", e));
    }

    @Override
    public Mono<Boolean> delete(String docId) {
        if (docId == null || docId.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Document ID cannot be null or empty"));
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                ensureNotClosed();
                                return deleteDocumentFromMilvus(docId);
                            } catch (Exception e) {
                                throw new VectorStoreException(
                                        "Failed to delete document from Milvus", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                e instanceof VectorStoreException
                                        ? e
                                        : new VectorStoreException(
                                                "Failed to delete document from Milvus", e));
    }

    /**
     * Gets the underlying Milvus client for advanced operations.
     *
     * <p>This method provides access to the Milvus client for users who need to perform
     * operations beyond the standard VDBStoreBase interface, such as:
     * <ul>
     *   <li>Custom index configuration
     *   <li>Advanced filtering and faceting
     *   <li>Collection management
     *   <li>Batch operations
     * </ul>
     *
     * @return the MilvusClientV2 instance
     * @throws VectorStoreException if the store has been closed
     */
    public MilvusClientV2 getClient() throws VectorStoreException {
        ensureNotClosed();
        return milvusClient;
    }

    /**
     * Ensures the store is not closed before performing operations.
     *
     * @throws VectorStoreException if the store has been closed
     */
    private void ensureNotClosed() throws VectorStoreException {
        if (closed) {
            throw new VectorStoreException("MilvusStore has been closed");
        }
    }

    /**
     * Ensures the collection exists, creating it if necessary.
     *
     * @param client the MilvusClientV2 to use
     * @throws VectorStoreException if collection creation fails
     */
    private void ensureCollection(MilvusClientV2 client) throws VectorStoreException {
        try {
            // Check if collection exists
            HasCollectionReq hasCollectionReq =
                    HasCollectionReq.builder().collectionName(collectionName).build();
            boolean exists = client.hasCollection(hasCollectionReq);

            if (exists) {
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
     * Creates a new collection with the specified dimensions and schema.
     *
     * @param client the MilvusClientV2 to use
     */
    private void createCollection(MilvusClientV2 client) {
        // Create schema with dynamic fields enabled for metadata storage
        CreateCollectionReq.CollectionSchema schema = MilvusClientV2.CreateSchema();
        schema.setEnableDynamicField(true);

        // Add primary key field (UUID string)
        schema.addField(
                AddFieldReq.builder()
                        .fieldName(FIELD_ID)
                        .dataType(DataType.VarChar)
                        .maxLength(64)
                        .isPrimaryKey(true)
                        .autoID(false)
                        .build());

        // Add vector field
        schema.addField(
                AddFieldReq.builder()
                        .fieldName(FIELD_VECTOR)
                        .dataType(DataType.FloatVector)
                        .dimension(dimensions)
                        .build());

        // Add doc_id field for filtering
        schema.addField(
                AddFieldReq.builder()
                        .fieldName(FIELD_DOC_ID)
                        .dataType(DataType.VarChar)
                        .maxLength(256)
                        .build());

        // Add chunk_id field
        schema.addField(
                AddFieldReq.builder().fieldName(FIELD_CHUNK_ID).dataType(DataType.VarChar).build());

        // Add content field for storing serialized content
        schema.addField(
                AddFieldReq.builder()
                        .fieldName(FIELD_CONTENT)
                        .dataType(DataType.VarChar)
                        .maxLength(65535)
                        .build());

        // Create index parameters for vector field
        List<IndexParam> indexParams = new ArrayList<>();
        indexParams.add(
                IndexParam.builder()
                        .fieldName(FIELD_VECTOR)
                        .indexType(IndexParam.IndexType.AUTOINDEX)
                        .metricType(metricType)
                        .build());

        // Create the collection with schema and index
        CreateCollectionReq createCollectionReq =
                CreateCollectionReq.builder()
                        .collectionName(collectionName)
                        .collectionSchema(schema)
                        .indexParams(indexParams)
                        .build();

        client.createCollection(createCollectionReq);
    }

    /**
     * Adds multiple documents to Milvus.
     *
     * <p>Point IDs are generated as deterministic UUIDs based on doc_id, chunk_id, and content,
     * matching the Python implementation's _map_text_to_uuid function.
     *
     * @param documents the documents to store (all must have embeddings set)
     */
    private void addDocumentsToMilvus(List<Document> documents) {
        List<JsonObject> rows = new ArrayList<>();

        for (Document document : documents) {
            JsonObject row = new JsonObject();

            // Set primary key (document ID)
            row.addProperty(FIELD_ID, document.getId());

            // Convert double[] to float[] for vector
            double[] embedding = document.getEmbedding();
            List<Float> floatList = new ArrayList<>(embedding.length);
            for (double d : embedding) {
                floatList.add((float) d);
            }
            row.add(FIELD_VECTOR, GSON.toJsonTree(floatList));

            // Set metadata fields
            DocumentMetadata metadata = document.getMetadata();
            row.addProperty(FIELD_DOC_ID, metadata.getDocId());
            row.addProperty(FIELD_CHUNK_ID, metadata.getChunkId());

            // Serialize content to JSON string
            String contentJson;
            try {
                contentJson = OBJECT_MAPPER.writeValueAsString(metadata.getContent());
            } catch (Exception e) {
                log.warn("Failed to serialize content, using text representation", e);
                contentJson = metadata.getContentText();
            }
            row.addProperty(FIELD_CONTENT, contentJson);

            rows.add(row);
        }

        // Insert data
        InsertReq insertReq = InsertReq.builder().collectionName(collectionName).data(rows).build();

        InsertResp insertResp = milvusClient.insert(insertReq);
        log.debug(
                "Inserted {} documents into collection '{}'",
                insertResp.getInsertCnt(),
                collectionName);
    }

    /**
     * Searches for similar documents in Milvus.
     *
     * @param queryEmbedding the query embedding vector
     * @param limit the maximum number of results
     * @param scoreThreshold optional minimum score threshold
     * @return a list of documents with scores set
     */
    @SuppressWarnings("rawtypes") // Milvus SDK uses raw types in builder pattern
    private List<Document> searchDocumentsInMilvus(
            double[] queryEmbedding, int limit, Double scoreThreshold) {
        // Convert double[] to float[] for query vector
        float[] floatArray = new float[queryEmbedding.length];
        for (int i = 0; i < queryEmbedding.length; i++) {
            floatArray[i] = (float) queryEmbedding[i];
        }
        FloatVec queryVector = new FloatVec(floatArray);

        // Build search request
        SearchReq.SearchReqBuilder searchBuilder =
                SearchReq.builder()
                        .collectionName(collectionName)
                        .data(Collections.singletonList(queryVector))
                        .topK(limit)
                        .outputFields(
                                Arrays.asList(
                                        FIELD_ID, FIELD_DOC_ID, FIELD_CHUNK_ID, FIELD_CONTENT));

        SearchReq searchReq = searchBuilder.build();

        // Execute search
        SearchResp searchResp = milvusClient.search(searchReq);

        // Process results
        List<Document> results = new ArrayList<>();
        List<List<SearchResp.SearchResult>> searchResults = searchResp.getSearchResults();

        if (searchResults != null && !searchResults.isEmpty()) {
            for (SearchResp.SearchResult result : searchResults.get(0)) {
                try {
                    // Get score
                    double score = result.getScore();

                    // Apply score threshold if specified
                    if (scoreThreshold != null && score < scoreThreshold) {
                        continue;
                    }

                    // Reconstruct document from result
                    Document document = reconstructDocumentFromResult(result, score);
                    if (document != null) {
                        results.add(document);
                    }
                } catch (Exception e) {
                    log.warn("Failed to reconstruct document from search result", e);
                }
            }
        }

        return results;
    }

    /**
     * Reconstructs a Document from Milvus search result.
     *
     * @param result the search result from Milvus
     * @param score the similarity score
     * @return the reconstructed Document, or null if reconstruction fails
     */
    private Document reconstructDocumentFromResult(SearchResp.SearchResult result, double score) {
        try {
            Map<String, Object> entity = result.getEntity();

            // Extract metadata fields
            String docId = String.valueOf(entity.get(FIELD_DOC_ID));
            String chunkId = String.valueOf(entity.get(FIELD_CHUNK_ID));
            String contentJson = String.valueOf(entity.get(FIELD_CONTENT));

            // Deserialize content
            ContentBlock content;
            try {
                content = OBJECT_MAPPER.readValue(contentJson, ContentBlock.class);
            } catch (Exception e) {
                log.debug("Failed to deserialize ContentBlock, creating TextBlock from content", e);
                content = TextBlock.builder().text(contentJson).build();
            }

            // Create metadata and document
            DocumentMetadata metadata = new DocumentMetadata(content, docId, chunkId);
            Document document = new Document(metadata);
            document.setScore(score);

            return document;
        } catch (Exception e) {
            log.error("Failed to reconstruct document from search result", e);
            return null;
        }
    }

    /**
     * Deletes a document from Milvus document ID.
     *
     * @param docId the document ID to delete
     * @return true if deletion was successful
     */
    private boolean deleteDocumentFromMilvus(String docId) {
        DeleteReq deleteReq =
                DeleteReq.builder()
                        .collectionName(collectionName)
                        .filter(FIELD_DOC_ID + " in [\"" + docId + "\"]")
                        .build();

        DeleteResp deleteResp = milvusClient.delete(deleteReq);
        long deleteCnt = deleteResp.getDeleteCnt();
        log.debug("Deleted {} documents from collection '{}'", deleteCnt, collectionName);

        return deleteCnt > 0;
    }

    /**
     * Gets the Milvus server URI.
     *
     * @return the URI
     */
    public String getUri() {
        return uri;
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
     * Gets the metric type used for similarity search.
     *
     * @return the metric type
     */
    public IndexParam.MetricType getMetricType() {
        return metricType;
    }

    /**
     * Closes the Milvus client and releases all resources.
     *
     * <p>This method closes the MilvusClientV2, releasing all connections and associated
     * resources. This method is idempotent and can be called multiple times safely.
     *
     * <p>After closing, all operations on this store will fail with a VectorStoreException.
     * It's recommended to use try-with-resources for automatic resource management:
     *
     * <pre>{@code
     * try (MilvusStore store = MilvusStore.builder()
     *         .uri("http://localhost:19530")
     *         .collectionName("collection")
     *         .dimensions(1024)
     *         .build()) {
     *     store.add(documents).block();
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
                if (milvusClient != null) {
                    log.debug("Closing MilvusClient for collection: {}", collectionName);
                    milvusClient.close();
                }
            } catch (Exception e) {
                log.warn("Error closing MilvusClient", e);
            }

            log.debug("MilvusStore closed for collection: {}", collectionName);
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
     * Builder for creating MilvusStore instances with fluent configuration.
     *
     * <p>Example usage:
     * <pre>{@code
     * MilvusStore store = MilvusStore.builder()
     *     .uri("http://localhost:19530")
     *     .collectionName("my_collection")
     *     .dimensions(1024)
     *     .token("root:Milvus")
     *     .connectTimeoutMs(30000)
     *     .metricType(IndexParam.MetricType.COSINE)
     *     .build();
     * }</pre>
     */
    public static class Builder {
        private String uri;
        private String collectionName;
        private int dimensions;
        private String token;
        private String username;
        private String password;
        private long connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        private IndexParam.MetricType metricType = IndexParam.MetricType.COSINE;

        private Builder() {}

        /**
         * Sets the Milvus server URI.
         *
         * @param uri the Milvus server URI (e.g., "http://localhost:19530")
         * @return this builder
         */
        public Builder uri(String uri) {
            this.uri = uri;
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
         * Sets the authentication token.
         *
         * <p>The token should be in the format "username:password" for basic authentication.
         *
         * @param token the authentication token
         * @return this builder
         */
        public Builder token(String token) {
            this.token = token;
            return this;
        }

        /**
         * Sets the username for authentication.
         *
         * <p>This is an alternative to using token(). If both are set, token takes precedence.
         *
         * @param username the username
         * @return this builder
         */
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        /**
         * Sets the password for authentication.
         *
         * <p>This should be used together with username().
         *
         * @param password the password
         * @return this builder
         */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /**
         * Sets the connection timeout in milliseconds.
         *
         * <p>Default is 30000 (30 seconds). Set to 0 or negative to disable timeout.
         *
         * @param connectTimeoutMs the connection timeout in milliseconds
         * @return this builder
         */
        public Builder connectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        /**
         * Sets the metric type for vector similarity search.
         *
         * <p>Default is COSINE. Other options include L2 (Euclidean) and IP (Inner Product).
         *
         * @param metricType the metric type
         * @return this builder
         */
        public Builder metricType(IndexParam.MetricType metricType) {
            this.metricType = metricType;
            return this;
        }

        /**
         * Builds a new MilvusStore instance.
         *
         * <p>The client connection is established immediately during construction. If the
         * connection fails or the collection cannot be created, a VectorStoreException is thrown.
         *
         * @return a new MilvusStore instance
         * @throws IllegalArgumentException if required parameters are invalid
         * @throws VectorStoreException if client initialization or collection creation fails
         */
        public MilvusStore build() throws VectorStoreException {
            if (uri == null || uri.trim().isEmpty()) {
                throw new IllegalArgumentException("URI cannot be null or empty");
            }
            if (collectionName == null || collectionName.trim().isEmpty()) {
                throw new IllegalArgumentException("Collection name cannot be null or empty");
            }
            if (dimensions <= 0) {
                throw new IllegalArgumentException("Dimensions must be positive");
            }
            if (metricType == null) {
                throw new IllegalArgumentException("Metric type cannot be null");
            }

            return new MilvusStore(this);
        }
    }

    /**
     * Creates a new builder for constructing MilvusStore instances.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
}
