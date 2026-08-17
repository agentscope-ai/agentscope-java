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

import com.pgvector.PGvector;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import io.agentscope.core.util.JsonUtils;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * PostgreSQL pgvector database store implementation.
 *
 * <p>This class provides an interface for storing and searching vectors using PostgreSQL
 * with the pgvector extension. It implements the VDBStoreBase interface to provide
 * a unified API for vector storage operations.
 *
 * <p>In PostgreSQL with pgvector, we use a table to store the vectors and metadata,
 * including the document ID, chunk ID, content, and custom payload as JSONB.
 *
 * <p>This implementation uses the pgvector JDBC driver (com.pgvector:pgvector). To use this
 * class, add the following dependencies:
 *
 * <pre>{@code
 * <dependency>
 *     <groupId>org.postgresql</groupId>
 *     <artifactId>postgresql</artifactId>
 *     <version>42.7.4</version>
 * </dependency>
 * <dependency>
 *     <groupId>com.pgvector</groupId>
 *     <artifactId>pgvector</artifactId>
 *     <version>0.1.6</version>
 * </dependency>
 * }</pre>
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>PostgreSQL 11+ with pgvector extension installed</li>
 *   <li>Run: CREATE EXTENSION IF NOT EXISTS vector;</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Using builder with full configuration (recommended)
 * try (PgVectorStore store = PgVectorStore.builder()
 *         .jdbcUrl("jdbc:postgresql://localhost:5432/mydb")
 *         .username("postgres")
 *         .password("password")
 *         .schema("my_schema")  // optional, defaults to "public"
 *         .tableName("embeddings")
 *         .dimensions(1024)
 *         .distanceType(PgVectorStore.DistanceType.COSINE)
 *         .build()) {
 *     store.add(documents).block();
 *     List<Document> results = store.search(searchDto).block();
 * }
 *
 * // Using static factory method (simpler, uses defaults)
 * try (PgVectorStore store = PgVectorStore.create(
 *         "jdbc:postgresql://localhost:5432/mydb",
 *         "postgres", "password", "embeddings", 1024)) {
 *     store.add(documents).block();
 *     List<Document> results = store.search(searchDto).block();
 * }
 * }</pre>
 *
 * <p>Note: The JDBC URL should follow PostgreSQL format:
 * <ul>
 *   <li>jdbc:postgresql://host:port/database</li>
 *   <li>jdbc:postgresql://localhost:5432/vectordb</li>
 * </ul>
 */
public class PgVectorStore implements VDBStoreBase, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    // Column names for the table
    private static final String COL_ID = "id";
    private static final String COL_VECTOR = "embedding";
    private static final String COL_DOC_ID = "doc_id";
    private static final String COL_CHUNK_ID = "chunk_id";
    private static final String COL_CONTENT = "content";
    private static final String COL_PAYLOAD = "payload";

    private static final String DEFAULT_SCHEMA = "public";

    /**
     * Pattern for validating database identifiers (schema and table names).
     * Only allows alphanumeric characters and underscores, must start with a letter or underscore.
     * This prevents SQL injection attacks through malicious identifier names.
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /** PostgreSQL identifier length limit (63 bytes). */
    private static final int MAX_IDENTIFIER_LENGTH = 63;

    private final String jdbcUrl;
    private final String schema;
    private final String tableName;
    private final int dimensions;
    private final DistanceType distanceType;
    private final Connection connection;
    private volatile boolean closed = false;

    /**
     * Distance types supported by pgvector.
     */
    public enum DistanceType {
        /** L2 (Euclidean) distance - use {@code <->} operator */
        L2("vector_l2_ops", "<->"),

        /** Inner product - use {@code <#>} operator (for normalized vectors, higher is better) */
        INNER_PRODUCT("vector_ip_ops", "<#>"),

        /** Cosine distance - use {@code <=>} operator */
        COSINE("vector_cosine_ops", "<=>");

        private final String indexOps;
        private final String operator;

        DistanceType(String indexOps, String operator) {
            this.indexOps = indexOps;
            this.operator = operator;
        }

        /**
         * Gets the index operator class name for this distance type.
         *
         * <p>This is used when creating HNSW indexes in PostgreSQL with pgvector.
         * For example: {@code CREATE INDEX ... USING hnsw (embedding vector_cosine_ops)}
         *
         * @return the index operator class name (e.g., "vector_cosine_ops", "vector_l2_ops")
         */
        public String getIndexOps() {
            return indexOps;
        }

        /**
         * Gets the SQL operator for this distance type.
         *
         * <p>This operator is used in ORDER BY clauses to sort results by similarity.
         * For example: {@code ORDER BY embedding <=> query_vector}
         *
         * @return the SQL operator (e.g., "{@code <=>}" for cosine, "{@code <->}" for L2)
         */
        public String getOperator() {
            return operator;
        }
    }

    /**
     * Creates a new PgVectorStore using the builder configuration.
     *
     * @param builder the builder instance
     * @throws VectorStoreException if client initialization or table creation fails
     */
    private PgVectorStore(Builder builder) throws VectorStoreException {
        this.jdbcUrl = builder.jdbcUrl;
        this.schema = builder.schema != null ? builder.schema : DEFAULT_SCHEMA;
        this.tableName = builder.tableName;
        this.dimensions = builder.dimensions;
        this.distanceType = builder.distanceType;

        Connection tempConnection = null;

        try {
            // Build connection properties
            Properties props = new Properties();
            if (builder.username != null) {
                props.setProperty("user", builder.username);
            }
            if (builder.password != null) {
                props.setProperty("password", builder.password);
            }
            if (builder.connectionTimeoutMs > 0) {
                props.setProperty(
                        "connectTimeout", String.valueOf(builder.connectionTimeoutMs / 1000));
            }

            // Create connection
            tempConnection = DriverManager.getConnection(jdbcUrl, props);

            // Register pgvector type
            PGvector.addVectorType(tempConnection);

            log.debug(
                    "Initialized PostgreSQL connection: url={}, schema={}, table={}",
                    jdbcUrl,
                    schema,
                    tableName);

            // Ensure table exists
            ensureTable(tempConnection);

            // Assign to final field only after successful initialization
            this.connection = tempConnection;

            log.debug("PgVectorStore initialized successfully for table: {}", tableName);
        } catch (Exception e) {
            // Clean up if initialization fails
            try {
                if (tempConnection != null) {
                    tempConnection.close();
                }
            } catch (Exception cleanupException) {
                log.warn("Error closing connection after initialization failure", cleanupException);
            }
            throw new VectorStoreException("Failed to initialize PgVectorStore", e);
        }
    }

    /**
     * Creates a new PgVectorStore with minimal configuration.
     *
     * @param jdbcUrl the PostgreSQL JDBC URL (e.g., "jdbc:postgresql://localhost:5432/mydb")
     * @param username the database username
     * @param password the database password
     * @param tableName the name of the table to use
     * @param dimensions the dimension of vectors that will be stored
     * @return a new PgVectorStore instance
     * @throws VectorStoreException if initialization fails
     */
    public static PgVectorStore create(
            String jdbcUrl, String username, String password, String tableName, int dimensions)
            throws VectorStoreException {
        return builder()
                .jdbcUrl(jdbcUrl)
                .username(username)
                .password(password)
                .tableName(tableName)
                .dimensions(dimensions)
                .build();
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
                                addDocumentsToPostgres(documents);
                                return null;
                            } catch (Exception e) {
                                throw new VectorStoreException(
                                        "Failed to add documents to PostgreSQL", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                e instanceof VectorStoreException
                                        ? e
                                        : new VectorStoreException(
                                                "Failed to add documents to PostgreSQL", e))
                .then();
    }

    @Override
    public Mono<List<Document>> search(SearchDocumentDto searchDocumentDto) {
        double[] queryEmbedding = searchDocumentDto.getQueryEmbedding();
        int limit = searchDocumentDto.getLimit();
        Double scoreThreshold = searchDocumentDto.getScoreThreshold();

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
                                return searchDocumentsInPostgres(
                                        queryEmbedding, limit, scoreThreshold);
                            } catch (Exception e) {
                                throw new VectorStoreException(
                                        "Failed to search documents in PostgreSQL", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                e instanceof VectorStoreException
                                        ? e
                                        : new VectorStoreException(
                                                "Failed to search documents in PostgreSQL", e));
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
                                return deleteDocumentFromPostgres(docId);
                            } catch (Exception e) {
                                throw new VectorStoreException(
                                        "Failed to delete document from PostgreSQL", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                e instanceof VectorStoreException
                                        ? e
                                        : new VectorStoreException(
                                                "Failed to delete document from PostgreSQL", e));
    }

    /**
     * Gets the underlying JDBC connection for advanced operations.
     *
     * <p>This method provides access to the connection for users who need to perform
     * operations beyond the standard VDBStoreBase interface, such as:
     * <ul>
     *   <li>Custom index configuration</li>
     *   <li>Advanced filtering</li>
     *   <li>Table management</li>
     *   <li>Batch operations</li>
     * </ul>
     *
     * @return the Connection instance
     * @throws VectorStoreException if the store has been closed
     */
    public Connection getConnection() throws VectorStoreException {
        ensureNotClosed();
        return connection;
    }

    /**
     * Ensures the store is not closed before performing operations.
     *
     * @throws VectorStoreException if the store has been closed
     */
    private void ensureNotClosed() throws VectorStoreException {
        if (closed) {
            throw new VectorStoreException("PgVectorStore has been closed");
        }
    }

    /**
     * Gets the fully qualified table name including schema.
     *
     * <p>This method safely concatenates schema and table name. Both values are validated
     * during construction via {@link #validateIdentifier(String, String)} to ensure they
     * contain only safe characters (alphanumeric and underscores), preventing SQL injection.
     *
     * @return the fully qualified table name (schema.table)
     */
    private String getFullTableName() {
        return schema + "." + tableName;
    }

    /**
     * Ensures the table exists, creating it if necessary.
     *
     * @param conn the Connection to use
     * @throws VectorStoreException if table creation fails
     */
    private void ensureTable(Connection conn) throws VectorStoreException {
        try {
            // Ensure vector extension is available
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
            }

            // Ensure schema exists (create if not exists)
            if (!DEFAULT_SCHEMA.equalsIgnoreCase(schema)) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
                }
                log.debug("Ensured schema '{}' exists", schema);
            }

            // Check if table exists in the specified schema
            boolean tableExists = false;
            try (ResultSet rs =
                    conn.getMetaData()
                            .getTables(
                                    null,
                                    schema.toLowerCase(),
                                    tableName.toLowerCase(),
                                    new String[] {"TABLE"})) {
                tableExists = rs.next();
            }

            if (tableExists) {
                log.debug("Table '{}.{}' already exists", schema, tableName);
                return;
            }

            // Create table
            createTable(conn);
            log.debug("Created table '{}.{}' with dimensions {}", schema, tableName, dimensions);
        } catch (Exception e) {
            throw new VectorStoreException(
                    "Failed to ensure table exists: " + getFullTableName(), e);
        }
    }

    /**
     * Creates a new table with the specified dimensions and schema.
     *
     * @param conn the Connection to use
     * @throws SQLException if table creation fails
     */
    private void createTable(Connection conn) throws SQLException {
        String fullTableName = getFullTableName();

        // Create table with vector column
        String createTableSql =
                String.format(
                        "CREATE TABLE IF NOT EXISTS %s ("
                                + "%s VARCHAR(64) PRIMARY KEY, "
                                + "%s vector(%d), "
                                + "%s VARCHAR(256), "
                                + "%s VARCHAR(256), "
                                + "%s TEXT, "
                                + "%s JSONB"
                                + ")",
                        fullTableName,
                        COL_ID,
                        COL_VECTOR,
                        dimensions,
                        COL_DOC_ID,
                        COL_CHUNK_ID,
                        COL_CONTENT,
                        COL_PAYLOAD);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSql);
        }

        // Create index on doc_id for efficient filtering
        // Index name uses schema_table format to avoid conflicts
        String indexPrefix = schema + "_" + tableName;
        String createDocIdIndexSql =
                String.format(
                        "CREATE INDEX IF NOT EXISTS idx_%s_doc_id ON %s (%s)",
                        indexPrefix, fullTableName, COL_DOC_ID);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createDocIdIndexSql);
        }

        // Create HNSW index on vector column for fast similarity search
        String createVectorIndexSql =
                String.format(
                        "CREATE INDEX IF NOT EXISTS idx_%s_vector ON %s USING hnsw (%s %s)",
                        indexPrefix, fullTableName, COL_VECTOR, distanceType.getIndexOps());

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createVectorIndexSql);
        }
    }

    /**
     * Adds multiple documents to PostgreSQL.
     *
     * <p>Uses upsert (INSERT ... ON CONFLICT DO UPDATE) to handle duplicate IDs.
     *
     * @param documents the documents to store (all must have embeddings set)
     * @throws SQLException if the operation fails
     */
    private void addDocumentsToPostgres(List<Document> documents) throws SQLException {
        String fullTableName = getFullTableName();
        String upsertSql =
                String.format(
                        "INSERT INTO %s (%s, %s, %s, %s, %s, %s) VALUES (?, ?, ?, ?, ?, ?::jsonb) "
                                + "ON CONFLICT (%s) DO UPDATE SET "
                                + "%s = EXCLUDED.%s, "
                                + "%s = EXCLUDED.%s, "
                                + "%s = EXCLUDED.%s, "
                                + "%s = EXCLUDED.%s, "
                                + "%s = EXCLUDED.%s",
                        fullTableName,
                        COL_ID,
                        COL_VECTOR,
                        COL_DOC_ID,
                        COL_CHUNK_ID,
                        COL_CONTENT,
                        COL_PAYLOAD,
                        COL_ID,
                        COL_VECTOR,
                        COL_VECTOR,
                        COL_DOC_ID,
                        COL_DOC_ID,
                        COL_CHUNK_ID,
                        COL_CHUNK_ID,
                        COL_CONTENT,
                        COL_CONTENT,
                        COL_PAYLOAD,
                        COL_PAYLOAD);

        try (PreparedStatement stmt = connection.prepareStatement(upsertSql)) {
            for (Document document : documents) {
                // Set ID
                stmt.setString(1, document.getId());

                // Convert double[] to PGvector
                double[] embedding = document.getEmbedding();
                float[] floatArray = new float[embedding.length];
                for (int i = 0; i < embedding.length; i++) {
                    floatArray[i] = (float) embedding[i];
                }
                stmt.setObject(2, new PGvector(floatArray));

                // Set metadata fields
                DocumentMetadata metadata = document.getMetadata();
                stmt.setString(3, metadata.getDocId());
                stmt.setString(4, metadata.getChunkId());

                // Serialize content to JSON string
                String contentJson;
                try {
                    contentJson = JsonUtils.getJsonCodec().toJson(metadata.getContent());
                } catch (Exception e) {
                    log.warn("Failed to serialize content, using text representation", e);
                    contentJson = metadata.getContentText();
                }
                stmt.setString(5, contentJson);

                // Serialize custom payload to JSON
                Map<String, Object> customPayload = metadata.getPayload();
                String payloadJson =
                        customPayload != null && !customPayload.isEmpty()
                                ? JsonUtils.getJsonCodec().toJson(customPayload)
                                : "{}";
                stmt.setString(6, payloadJson);

                stmt.addBatch();
            }

            int[] results = stmt.executeBatch();
            log.debug(
                    "Inserted/Updated {} documents into table '{}'", results.length, fullTableName);
        }
    }

    /**
     * Searches for similar documents in PostgreSQL.
     *
     * @param queryEmbedding the query embedding vector
     * @param limit the maximum number of results
     * @param scoreThreshold optional minimum score threshold
     * @return a list of documents with scores set
     * @throws SQLException if the operation fails
     */
    private List<Document> searchDocumentsInPostgres(
            double[] queryEmbedding, int limit, Double scoreThreshold) throws SQLException {

        // Convert double[] to PGvector
        float[] floatArray = new float[queryEmbedding.length];
        for (int i = 0; i < queryEmbedding.length; i++) {
            floatArray[i] = (float) queryEmbedding[i];
        }
        PGvector queryVector = new PGvector(floatArray);

        // Build query - for cosine and L2, lower distance is better
        // For inner product, higher is better (but pgvector uses negative for ordering)
        String distanceExpr = String.format("%s %s ?", COL_VECTOR, distanceType.getOperator());

        String searchSql =
                String.format(
                        "SELECT %s, %s, %s, %s, %s, %s, (%s) as distance "
                                + "FROM %s "
                                + "ORDER BY distance ASC "
                                + "LIMIT ?",
                        COL_ID,
                        COL_VECTOR,
                        COL_DOC_ID,
                        COL_CHUNK_ID,
                        COL_CONTENT,
                        COL_PAYLOAD,
                        distanceExpr,
                        getFullTableName());

        List<Document> results = new ArrayList<>();

        try (PreparedStatement stmt = connection.prepareStatement(searchSql)) {
            stmt.setObject(1, queryVector);
            stmt.setInt(2, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    try {
                        // Get distance and convert to similarity score
                        double distance = rs.getDouble("distance");

                        // Convert distance to similarity score based on distance type
                        double score = convertDistanceToScore(distance);

                        // Apply score threshold if specified
                        if (scoreThreshold != null && score < scoreThreshold) {
                            continue;
                        }

                        // Reconstruct document from result
                        Document document = reconstructDocumentFromResult(rs, score);
                        if (document != null) {
                            results.add(document);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to reconstruct document from search result", e);
                    }
                }
            }
        }

        return results;
    }

    /**
     * Converts distance to similarity score based on distance type.
     *
     * @param distance the distance value from pgvector
     * @return the similarity score (higher is better)
     */
    private double convertDistanceToScore(double distance) {
        return switch (distanceType) {
            case COSINE ->
                    // Cosine distance is 1 - cosine_similarity, so score = 1 - distance
                    1.0 - distance;
            case L2 ->
                    // L2 distance: convert to similarity using 1 / (1 + distance)
                    1.0 / (1.0 + distance);
            case INNER_PRODUCT ->
                    // Inner product: pgvector uses negative inner product, so negate it
                    -distance;
        };
    }

    /**
     * Reconstructs a Document from PostgreSQL ResultSet.
     *
     * @param rs the ResultSet containing document data
     * @param score the similarity score
     * @return the reconstructed Document, or null if reconstruction fails
     */
    private Document reconstructDocumentFromResult(ResultSet rs, double score) {
        try {
            // Extract metadata fields
            String docId = rs.getString(COL_DOC_ID);
            String chunkId = rs.getString(COL_CHUNK_ID);
            String contentJson = rs.getString(COL_CONTENT);

            // Deserialize content
            ContentBlock content;
            try {
                content = JsonUtils.getJsonCodec().fromJson(contentJson, ContentBlock.class);
            } catch (Exception e) {
                log.debug("Failed to deserialize ContentBlock, creating TextBlock from content", e);
                content = TextBlock.builder().text(contentJson).build();
            }

            // Deserialize custom payload from payload field
            Map<String, Object> customPayload = new HashMap<>();
            String payloadJson = rs.getString(COL_PAYLOAD);
            if (payloadJson != null && !payloadJson.isEmpty() && !"{}".equals(payloadJson)) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> deserializedPayload =
                            JsonUtils.getJsonCodec().fromJson(payloadJson, Map.class);
                    customPayload = deserializedPayload;
                } catch (Exception e) {
                    log.warn("Failed to deserialize payload, using empty map", e);
                }
            }

            // Create metadata and document with payload
            DocumentMetadata metadata =
                    new DocumentMetadata(content, docId, chunkId, customPayload);
            Document document = new Document(metadata);
            document.setScore(score);

            // Extract embedding from vector column if needed
            try {
                PGvector vectorObj = (PGvector) rs.getObject(COL_VECTOR);
                if (vectorObj != null) {
                    float[] floatVector = vectorObj.toArray();
                    double[] embedding = new double[floatVector.length];
                    for (int i = 0; i < floatVector.length; i++) {
                        embedding[i] = floatVector[i];
                    }
                    document.setEmbedding(embedding);
                }
            } catch (Exception e) {
                log.debug("Failed to extract embedding from result", e);
            }

            return document;
        } catch (Exception e) {
            log.error("Failed to reconstruct document from search result", e);
            return null;
        }
    }

    /**
     * Deletes documents from PostgreSQL by document ID.
     *
     * @param docId the document ID to delete
     * @return true if deletion was successful
     * @throws SQLException if the operation fails
     */
    private boolean deleteDocumentFromPostgres(String docId) throws SQLException {
        String fullTableName = getFullTableName();
        String deleteSql = String.format("DELETE FROM %s WHERE %s = ?", fullTableName, COL_DOC_ID);

        try (PreparedStatement stmt = connection.prepareStatement(deleteSql)) {
            stmt.setString(1, docId);
            int deletedCount = stmt.executeUpdate();
            log.debug("Deleted {} documents from table '{}'", deletedCount, fullTableName);
            return deletedCount > 0;
        }
    }

    /**
     * Gets the JDBC URL.
     *
     * @return the JDBC URL
     */
    public String getJdbcUrl() {
        return jdbcUrl;
    }

    /**
     * Gets the schema name.
     *
     * @return the schema name
     */
    public String getSchema() {
        return schema;
    }

    /**
     * Gets the table name.
     *
     * @return the table name
     */
    public String getTableName() {
        return tableName;
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
     * Gets the distance type used for similarity search.
     *
     * @return the distance type
     */
    public DistanceType getDistanceType() {
        return distanceType;
    }

    /**
     * Closes the PostgreSQL connection and releases all resources.
     *
     * <p>This method closes the JDBC connection, releasing all database resources.
     * This method is idempotent and can be called multiple times safely.
     *
     * <p>After closing, all operations on this store will fail with a VectorStoreException.
     * It's recommended to use try-with-resources for automatic resource management:
     *
     * <pre>{@code
     * try (PgVectorStore store = PgVectorStore.builder()
     *         .jdbcUrl("jdbc:postgresql://localhost:5432/mydb")
     *         .tableName("embeddings")
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
                if (connection != null && !connection.isClosed()) {
                    log.debug("Closing PostgreSQL connection for table: {}", getFullTableName());
                    connection.close();
                }
            } catch (Exception e) {
                log.warn("Error closing PostgreSQL connection", e);
            }

            log.debug("PgVectorStore closed for table: {}", getFullTableName());
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
     * Builder for creating PgVectorStore instances with fluent configuration.
     *
     * <p>Example usage:
     * <pre>{@code
     * PgVectorStore store = PgVectorStore.builder()
     *     .jdbcUrl("jdbc:postgresql://localhost:5432/mydb")
     *     .username("postgres")
     *     .password("password")
     *     .schema("my_schema")
     *     .tableName("embeddings")
     *     .dimensions(1024)
     *     .distanceType(PgVectorStore.DistanceType.COSINE)
     *     .connectionTimeoutMs(30000)
     *     .build();
     * }</pre>
     */
    public static class Builder {
        private String jdbcUrl;
        private String username;
        private String password;
        private String schema;
        private String tableName;
        private int dimensions;
        private DistanceType distanceType = DistanceType.COSINE;
        private long connectionTimeoutMs = 30000L;

        private Builder() {}

        /**
         * Sets the PostgreSQL JDBC URL.
         *
         * @param jdbcUrl the JDBC URL (e.g., "jdbc:postgresql://localhost:5432/mydb")
         * @return this builder
         */
        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        /**
         * Sets the database username.
         *
         * @param username the username
         * @return this builder
         */
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        /**
         * Sets the database password.
         *
         * @param password the password
         * @return this builder
         */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /**
         * Sets the database schema.
         *
         * <p>Default is "public". If the schema does not exist, it will be created automatically.
         *
         * @param schema the schema name (e.g., "my_schema")
         * @return this builder
         */
        public Builder schema(String schema) {
            this.schema = schema;
            return this;
        }

        /**
         * Sets the table name.
         *
         * @param tableName the name of the table to use
         * @return this builder
         */
        public Builder tableName(String tableName) {
            this.tableName = tableName;
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
         * Sets the distance type for vector similarity search.
         *
         * <p>Default is COSINE. Other options include L2 (Euclidean) and INNER_PRODUCT.
         *
         * @param distanceType the distance type
         * @return this builder
         */
        public Builder distanceType(DistanceType distanceType) {
            this.distanceType = distanceType;
            return this;
        }

        /**
         * Sets the connection timeout in milliseconds.
         *
         * <p>Default is 30000 (30 seconds). Set to 0 or negative to disable timeout.
         *
         * @param connectionTimeoutMs the connection timeout in milliseconds
         * @return this builder
         */
        public Builder connectionTimeoutMs(long connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
            return this;
        }

        /**
         * Builds a new PgVectorStore instance.
         *
         * <p>The database connection is established immediately during construction. If the
         * connection fails or the table cannot be created, a VectorStoreException is thrown.
         *
         * @return a new PgVectorStore instance
         * @throws IllegalArgumentException if required parameters are invalid
         * @throws VectorStoreException if connection initialization or table creation fails
         */
        public PgVectorStore build() throws VectorStoreException {
            if (jdbcUrl == null || jdbcUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("JDBC URL cannot be null or empty");
            }
            if (tableName == null || tableName.trim().isEmpty()) {
                throw new IllegalArgumentException("Table name cannot be null or empty");
            }
            if (dimensions <= 0) {
                throw new IllegalArgumentException("Dimensions must be positive");
            }
            if (distanceType == null) {
                throw new IllegalArgumentException("Distance type cannot be null");
            }

            // Validate identifiers to prevent SQL injection
            validateIdentifier(tableName, "Table name");
            if (schema != null && !schema.isEmpty()) {
                validateIdentifier(schema, "Schema name");
            }

            return new PgVectorStore(this);
        }
    }

    /**
     * Creates a new builder for constructing PgVectorStore instances.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Validates a database identifier (schema or table name) to prevent SQL injection.
     *
     * <p>This method ensures that identifiers only contain safe characters (alphanumeric and
     * underscores) and start with a letter or underscore. This is critical for security since
     * schema and table names cannot be parameterized in prepared statements.
     *
     * @param identifier the identifier to validate (schema or table name)
     * @param identifierType description of the identifier type for error messages
     * @throws IllegalArgumentException if the identifier is invalid or contains unsafe characters
     */
    private static void validateIdentifier(String identifier, String identifierType) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException(identifierType + " cannot be null or empty");
        }
        if (identifier.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(
                    identifierType + " cannot exceed " + MAX_IDENTIFIER_LENGTH + " characters");
        }
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    identifierType
                            + " contains invalid characters. Only alphanumeric characters and"
                            + " underscores are allowed, and it must start with a letter or"
                            + " underscore. Invalid value: "
                            + identifier);
        }
    }
}
