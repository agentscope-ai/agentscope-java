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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.pgvector.PGvector;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import reactor.test.StepVerifier;

/**
 * Unit tests for PgVectorStore.
 *
 * <p>These tests cover parameter validation, builder configuration, and mock-based
 * functional testing. Full integration tests with a running PostgreSQL server should
 * be marked with @Tag("integration").
 */
@Tag("unit")
@DisplayName("PgVectorStore Unit Tests")
class PgVectorStoreTest {

    private static final String TEST_JDBC_URL = "jdbc:postgresql://localhost:5432/db";
    private static final String TEST_USERNAME = "postgres";
    private static final String TEST_PASSWORD = "postgres";
    private static final String TEST_TABLE = "vectordb";
    private static final String TEST_SCHEMA = "public";
    private static final int TEST_DIMENSIONS = 3;

    private PgVectorStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
            store = null;
        }
    }

    // ==================== Builder Validation Tests ====================

    @Test
    @DisplayName("Should create builder instance")
    void testBuilderCreation() {
        PgVectorStore.Builder builder = PgVectorStore.builder();
        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should throw exception for null JDBC URL")
    void testNullJdbcUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store =
                                PgVectorStore.builder()
                                        .jdbcUrl(null)
                                        .tableName(TEST_TABLE)
                                        .dimensions(TEST_DIMENSIONS)
                                        .build());
    }

    @Test
    @DisplayName("Should throw exception for empty JDBC URL")
    void testEmptyJdbcUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store =
                                PgVectorStore.builder()
                                        .jdbcUrl("")
                                        .tableName(TEST_TABLE)
                                        .dimensions(TEST_DIMENSIONS)
                                        .build());
    }

    @Test
    @DisplayName("Should throw exception for blank JDBC URL")
    void testBlankJdbcUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store =
                                PgVectorStore.builder()
                                        .jdbcUrl("  ")
                                        .tableName(TEST_TABLE)
                                        .dimensions(TEST_DIMENSIONS)
                                        .build());
    }

    @Test
    @DisplayName("Should throw exception for null table name")
    void testNullTableName() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store =
                                PgVectorStore.builder()
                                        .jdbcUrl(TEST_JDBC_URL)
                                        .tableName(null)
                                        .dimensions(TEST_DIMENSIONS)
                                        .build());
    }

    @Test
    @DisplayName("Should throw exception for empty table name")
    void testEmptyTableName() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store =
                                PgVectorStore.builder()
                                        .jdbcUrl(TEST_JDBC_URL)
                                        .tableName("")
                                        .dimensions(TEST_DIMENSIONS)
                                        .build());
    }

    @Test
    @DisplayName("Should throw exception for blank table name")
    void testBlankTableName() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store =
                                PgVectorStore.builder()
                                        .jdbcUrl(TEST_JDBC_URL)
                                        .tableName("  ")
                                        .dimensions(TEST_DIMENSIONS)
                                        .build());
    }

    @Test
    @DisplayName("Should throw exception for zero dimensions")
    void testZeroDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store =
                                PgVectorStore.builder()
                                        .jdbcUrl(TEST_JDBC_URL)
                                        .tableName(TEST_TABLE)
                                        .dimensions(0)
                                        .build());
    }

    @Test
    @DisplayName("Should throw exception for negative dimensions")
    void testNegativeDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store =
                                PgVectorStore.builder()
                                        .jdbcUrl(TEST_JDBC_URL)
                                        .tableName(TEST_TABLE)
                                        .dimensions(-1)
                                        .build());
    }

    @Test
    @DisplayName("Should throw exception for null distance type")
    void testNullDistanceType() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store =
                                PgVectorStore.builder()
                                        .jdbcUrl(TEST_JDBC_URL)
                                        .tableName(TEST_TABLE)
                                        .dimensions(TEST_DIMENSIONS)
                                        .distanceType(null)
                                        .build());
    }

    // ==================== Builder Configuration Tests ====================

    @Test
    @DisplayName("Should create factory instance")
    void testStaticFactoryCreation() {
        PgVectorStore.Builder builder =
                PgVectorStore.builder()
                        .jdbcUrl(TEST_JDBC_URL)
                        .tableName(TEST_TABLE)
                        .dimensions(TEST_DIMENSIONS);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should set schema in builder")
    void testBuilderSchema() {
        PgVectorStore.Builder builder =
                PgVectorStore.builder()
                        .jdbcUrl(TEST_JDBC_URL)
                        .schema(TEST_SCHEMA)
                        .tableName(TEST_TABLE)
                        .dimensions(TEST_DIMENSIONS);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should set username and password in builder")
    void testBuilderCredentials() {
        PgVectorStore.Builder builder =
                PgVectorStore.builder()
                        .jdbcUrl(TEST_JDBC_URL)
                        .username(TEST_USERNAME)
                        .password(TEST_PASSWORD)
                        .tableName(TEST_TABLE)
                        .dimensions(TEST_DIMENSIONS);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should allow null password with username")
    void testBuilderNullPasswordWithUsername() {
        PgVectorStore.Builder builder =
                PgVectorStore.builder()
                        .jdbcUrl(TEST_JDBC_URL)
                        .username(TEST_USERNAME)
                        .password(null)
                        .tableName(TEST_TABLE)
                        .dimensions(TEST_DIMENSIONS);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should allow zero timeout")
    void testBuilderZeroTimeout() {
        PgVectorStore.Builder builder =
                PgVectorStore.builder()
                        .jdbcUrl(TEST_JDBC_URL)
                        .tableName(TEST_TABLE)
                        .dimensions(TEST_DIMENSIONS)
                        .connectionTimeoutMs(0);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should allow negative timeout (for disable)")
    void testBuilderNegativeTimeout() {
        PgVectorStore.Builder builder =
                PgVectorStore.builder()
                        .jdbcUrl(TEST_JDBC_URL)
                        .tableName(TEST_TABLE)
                        .dimensions(TEST_DIMENSIONS)
                        .connectionTimeoutMs(-1);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should set distance type in builder")
    void testBuilderDistanceType() {
        PgVectorStore.Builder builderL2 =
                PgVectorStore.builder()
                        .jdbcUrl(TEST_JDBC_URL)
                        .tableName(TEST_TABLE)
                        .dimensions(TEST_DIMENSIONS)
                        .distanceType(PgVectorStore.DistanceType.L2);
        assertNotNull(builderL2);

        PgVectorStore.Builder builderIP =
                PgVectorStore.builder()
                        .jdbcUrl(TEST_JDBC_URL)
                        .tableName(TEST_TABLE)
                        .dimensions(TEST_DIMENSIONS)
                        .distanceType(PgVectorStore.DistanceType.INNER_PRODUCT);
        assertNotNull(builderIP);

        PgVectorStore.Builder builderCosine =
                PgVectorStore.builder()
                        .jdbcUrl(TEST_JDBC_URL)
                        .tableName(TEST_TABLE)
                        .dimensions(TEST_DIMENSIONS)
                        .distanceType(PgVectorStore.DistanceType.COSINE);
        assertNotNull(builderCosine);
    }

    @Test
    @DisplayName("Should chain builder methods")
    void testBuilderChaining() {
        PgVectorStore.Builder builder =
                PgVectorStore.builder()
                        .jdbcUrl(TEST_JDBC_URL)
                        .username(TEST_USERNAME)
                        .password(TEST_PASSWORD)
                        .schema(TEST_SCHEMA)
                        .tableName(TEST_TABLE)
                        .dimensions(TEST_DIMENSIONS)
                        .distanceType(PgVectorStore.DistanceType.COSINE)
                        .connectionTimeoutMs(30000);

        assertNotNull(builder);
    }

    // ==================== Distance Type Tests ====================

    @Test
    @DisplayName("Should have correct operator for L2 distance")
    void testL2DistanceOperator() {
        assertEquals("<->", PgVectorStore.DistanceType.L2.getOperator());
        assertEquals("vector_l2_ops", PgVectorStore.DistanceType.L2.getIndexOps());
    }

    @Test
    @DisplayName("Should have correct operator for inner product distance")
    void testInnerProductDistanceOperator() {
        assertEquals("<#>", PgVectorStore.DistanceType.INNER_PRODUCT.getOperator());
        assertEquals("vector_ip_ops", PgVectorStore.DistanceType.INNER_PRODUCT.getIndexOps());
    }

    @Test
    @DisplayName("Should have correct operator for cosine distance")
    void testCosineDistanceOperator() {
        assertEquals("<=>", PgVectorStore.DistanceType.COSINE.getOperator());
        assertEquals("vector_cosine_ops", PgVectorStore.DistanceType.COSINE.getIndexOps());
    }

    // ==================== Mock-based Functional Tests ====================

    private PgVectorStore createMockStore() throws Exception {
        return createMockStore(true);
    }

    private PgVectorStore createMockStore(boolean tableExists) throws Exception {
        Connection mockConnection = mock(Connection.class);
        Statement mockStatement = mock(Statement.class);
        DatabaseMetaData mockMetaData = mock(DatabaseMetaData.class);
        ResultSet mockTableResultSet = mock(ResultSet.class);

        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockConnection.getMetaData()).thenReturn(mockMetaData);
        when(mockMetaData.getTables(any(), any(), anyString(), any()))
                .thenReturn(mockTableResultSet);
        when(mockTableResultSet.next()).thenReturn(tableExists);

        try (MockedStatic<DriverManager> driverManagerMock = mockStatic(DriverManager.class);
                MockedStatic<PGvector> pgVectorMock = mockStatic(PGvector.class)) {

            driverManagerMock
                    .when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                    .thenReturn(mockConnection);

            pgVectorMock.when(() -> PGvector.addVectorType(any(Connection.class))).then(i -> null);

            return PgVectorStore.builder()
                    .jdbcUrl(TEST_JDBC_URL)
                    .username(TEST_USERNAME)
                    .password(TEST_PASSWORD)
                    .tableName(TEST_TABLE)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    private PgVectorStore createMockStoreWithSchema() throws Exception {
        Connection mockConnection = mock(Connection.class);
        Statement mockStatement = mock(Statement.class);
        DatabaseMetaData mockMetaData = mock(DatabaseMetaData.class);
        ResultSet mockTableResultSet = mock(ResultSet.class);

        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockConnection.getMetaData()).thenReturn(mockMetaData);
        when(mockMetaData.getTables(any(), any(), anyString(), any()))
                .thenReturn(mockTableResultSet);
        when(mockTableResultSet.next()).thenReturn(true);

        try (MockedStatic<DriverManager> driverManagerMock = mockStatic(DriverManager.class);
                MockedStatic<PGvector> pgVectorMock = mockStatic(PGvector.class)) {

            driverManagerMock
                    .when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                    .thenReturn(mockConnection);

            pgVectorMock.when(() -> PGvector.addVectorType(any(Connection.class))).then(i -> null);

            return PgVectorStore.builder()
                    .jdbcUrl(TEST_JDBC_URL)
                    .username(TEST_USERNAME)
                    .password(TEST_PASSWORD)
                    .schema(TEST_SCHEMA)
                    .tableName(TEST_TABLE)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    @Test
    @DisplayName("Should build store with mock connection and existing table")
    void testBuildWithMockConnectionExistingTable() throws Exception {
        store = createMockStore();
        assertNotNull(store);
        assertEquals(TEST_JDBC_URL, store.getJdbcUrl());
        assertEquals(TEST_TABLE, store.getTableName());
        assertEquals(TEST_DIMENSIONS, store.getDimensions());
        assertEquals(PgVectorStore.DistanceType.COSINE, store.getDistanceType());
        assertEquals("public", store.getSchema());
        store.close();
    }

    @Test
    @DisplayName("Should build store with custom schema")
    void testBuildWithCustomSchema() throws Exception {
        store = createMockStoreWithSchema();
        assertNotNull(store);
        assertEquals(TEST_SCHEMA, store.getSchema());
        store.close();
    }

    @Test
    @DisplayName("Should create new table when not exists")
    void testBuildWithMockConnectionNewTable() throws Exception {
        store = createMockStore(false);
        assertNotNull(store);
        store.close();
    }

    @Test
    @DisplayName("Should close store successfully")
    void testCloseStore() throws Exception {
        store = createMockStore();
        assertFalse(store.isClosed());
        store.close();
        assertTrue(store.isClosed());
    }

    @Test
    @DisplayName("Should be idempotent when closing multiple times")
    void testCloseIdempotent() throws Exception {
        store = createMockStore();
        store.close();
        assertTrue(store.isClosed());
        store.close(); // Should not throw
        assertTrue(store.isClosed());
    }

    @Test
    @DisplayName("Should throw exception when getting connection after close")
    void testGetConnectionAfterClose() throws Exception {
        store = createMockStore();
        store.close();
        assertThrows(VectorStoreException.class, () -> store.getConnection());
    }

    @Test
    @DisplayName("Should return connection when not closed")
    void testGetConnectionBeforeClose() throws Exception {
        store = createMockStore();
        assertNotNull(store.getConnection());
    }

    // ==================== Add Method Tests ====================

    private PgVectorStore createMockStoreForAdd() throws Exception {
        Connection mockConnection = mock(Connection.class);
        Statement mockStatement = mock(Statement.class);
        DatabaseMetaData mockMetaData = mock(DatabaseMetaData.class);
        ResultSet mockTableResultSet = mock(ResultSet.class);
        PreparedStatement mockPreparedStatement = mock(PreparedStatement.class);

        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockConnection.getMetaData()).thenReturn(mockMetaData);
        when(mockMetaData.getTables(any(), any(), anyString(), any()))
                .thenReturn(mockTableResultSet);
        when(mockTableResultSet.next()).thenReturn(true);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] {1});

        try (MockedStatic<DriverManager> driverManagerMock = mockStatic(DriverManager.class);
                MockedStatic<PGvector> pgVectorMock = mockStatic(PGvector.class)) {

            driverManagerMock
                    .when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                    .thenReturn(mockConnection);

            pgVectorMock.when(() -> PGvector.addVectorType(any(Connection.class))).then(i -> null);

            return PgVectorStore.builder()
                    .jdbcUrl(TEST_JDBC_URL)
                    .username(TEST_USERNAME)
                    .password(TEST_PASSWORD)
                    .tableName(TEST_TABLE)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    @Test
    @DisplayName("Should return error for null documents list")
    void testAddNullDocuments() throws Exception {
        store = createMockStoreForAdd();

        StepVerifier.create(store.add(null)).expectError(IllegalArgumentException.class).verify();
    }

    @Test
    @DisplayName("Should complete for empty documents list")
    void testAddEmptyDocuments() throws Exception {
        store = createMockStoreForAdd();

        StepVerifier.create(store.add(List.of())).verifyComplete();
    }

    @Test
    @DisplayName("Should return error for null document in list")
    void testAddNullDocumentInList() throws Exception {
        store = createMockStoreForAdd();
        List<Document> documents = new ArrayList<>();
        documents.add(null);

        StepVerifier.create(store.add(documents))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should return error for document without embedding")
    void testAddDocumentWithoutEmbedding() throws Exception {
        store = createMockStoreForAdd();
        TextBlock content = TextBlock.builder().text("Test").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");
        Document doc = new Document(metadata);
        // No embedding set

        StepVerifier.create(store.add(List.of(doc)))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should return error for dimension mismatch")
    void testAddDimensionMismatch() throws Exception {
        store = createMockStoreForAdd();
        TextBlock content = TextBlock.builder().text("Test").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");
        Document doc = new Document(metadata);
        doc.setEmbedding(new double[] {1.0, 2.0}); // Wrong dimension

        StepVerifier.create(store.add(List.of(doc)))
                .expectError(VectorStoreException.class)
                .verify();
    }

    @Test
    @DisplayName("Should add single document successfully")
    void testAddSingleDocument() throws Exception {
        store = createMockStoreForAdd();
        TextBlock content = TextBlock.builder().text("Test content").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");
        Document doc = new Document(metadata);
        doc.setEmbedding(new double[] {1.0, 0.0, 0.0});

        StepVerifier.create(store.add(List.of(doc))).verifyComplete();
    }

    @Test
    @DisplayName("Should add multiple documents successfully")
    void testAddMultipleDocuments() throws Exception {
        store = createMockStoreForAdd();
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            TextBlock content = TextBlock.builder().text("Content " + i).build();
            DocumentMetadata metadata = new DocumentMetadata(content, "doc-" + i, "0");
            Document doc = new Document(metadata);
            doc.setEmbedding(new double[] {1.0, 0.0, 0.0});
            docs.add(doc);
        }

        StepVerifier.create(store.add(docs)).verifyComplete();
    }

    @Test
    @DisplayName("Should return error when store is closed")
    void testAddAfterClose() throws Exception {
        store = createMockStoreForAdd();
        store.close();

        TextBlock content = TextBlock.builder().text("Test").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");
        Document doc = new Document(metadata);
        doc.setEmbedding(new double[] {1.0, 0.0, 0.0});

        StepVerifier.create(store.add(List.of(doc)))
                .expectError(VectorStoreException.class)
                .verify();
    }

    // ==================== Search Method Tests ====================

    private PgVectorStore createMockStoreForSearch() throws Exception {
        return createMockStoreForSearch(false);
    }

    private PgVectorStore createMockStoreForSearchWithResults() throws Exception {
        return createMockStoreForSearch(true);
    }

    private PgVectorStore createMockStoreForSearch(boolean withResults) throws Exception {
        Connection mockConnection = mock(Connection.class);
        Statement mockStatement = mock(Statement.class);
        DatabaseMetaData mockMetaData = mock(DatabaseMetaData.class);
        ResultSet mockTableResultSet = mock(ResultSet.class);
        PreparedStatement mockPreparedStatement = mock(PreparedStatement.class);
        ResultSet mockSearchResultSet = mock(ResultSet.class);

        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockConnection.getMetaData()).thenReturn(mockMetaData);
        when(mockMetaData.getTables(any(), any(), anyString(), any()))
                .thenReturn(mockTableResultSet);
        when(mockTableResultSet.next()).thenReturn(true);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
        when(mockPreparedStatement.executeQuery()).thenReturn(mockSearchResultSet);

        if (withResults) {
            when(mockSearchResultSet.next()).thenReturn(true, false);
            when(mockSearchResultSet.getDouble("distance")).thenReturn(0.1);
            when(mockSearchResultSet.getString("doc_id")).thenReturn("doc-1");
            when(mockSearchResultSet.getString("chunk_id")).thenReturn("0");
            when(mockSearchResultSet.getString("content"))
                    .thenReturn("{\"type\":\"text\",\"text\":\"Test content\"}");
            when(mockSearchResultSet.getString("payload")).thenReturn("{}");
            when(mockSearchResultSet.getObject("embedding")).thenReturn(null);
        } else {
            when(mockSearchResultSet.next()).thenReturn(false);
        }

        try (MockedStatic<DriverManager> driverManagerMock = mockStatic(DriverManager.class);
                MockedStatic<PGvector> pgVectorMock = mockStatic(PGvector.class)) {

            driverManagerMock
                    .when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                    .thenReturn(mockConnection);

            pgVectorMock.when(() -> PGvector.addVectorType(any(Connection.class))).then(i -> null);

            return PgVectorStore.builder()
                    .jdbcUrl(TEST_JDBC_URL)
                    .username(TEST_USERNAME)
                    .password(TEST_PASSWORD)
                    .tableName(TEST_TABLE)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    @Test
    @DisplayName("Should return error for null query embedding")
    void testSearchNullQueryEmbedding() throws Exception {
        store = createMockStoreForSearch();

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder().queryEmbedding(null).limit(10).build()))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should return error for dimension mismatch in query")
    void testSearchDimensionMismatch() throws Exception {
        store = createMockStoreForSearch();
        double[] wrongDimensionQuery = new double[] {1.0, 2.0}; // Wrong dimension

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(wrongDimensionQuery)
                                        .limit(10)
                                        .build()))
                .expectError(VectorStoreException.class)
                .verify();
    }

    @Test
    @DisplayName("Should return error for zero limit")
    void testSearchZeroLimit() throws Exception {
        store = createMockStoreForSearch();
        double[] query = new double[] {1.0, 0.0, 0.0};

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder().queryEmbedding(query).limit(0).build()))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should return error for negative limit")
    void testSearchNegativeLimit() throws Exception {
        store = createMockStoreForSearch();
        double[] query = new double[] {1.0, 0.0, 0.0};

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(query)
                                        .limit(-1)
                                        .build()))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should search successfully with valid parameters")
    void testSearchValidParameters() throws Exception {
        store = createMockStoreForSearch();
        double[] query = new double[] {1.0, 0.0, 0.0};

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(query)
                                        .limit(10)
                                        .build()))
                .assertNext(
                        results -> {
                            assertNotNull(results);
                            assertTrue(results.isEmpty());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should search with score threshold")
    void testSearchWithScoreThreshold() throws Exception {
        store = createMockStoreForSearch();
        double[] query = new double[] {1.0, 0.0, 0.0};

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(query)
                                        .limit(10)
                                        .scoreThreshold(0.5)
                                        .build()))
                .assertNext(Assertions::assertNotNull)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should search and return results")
    void testSearchWithResults() throws Exception {
        store = createMockStoreForSearchWithResults();
        double[] query = new double[] {1.0, 0.0, 0.0};

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(query)
                                        .limit(10)
                                        .build()))
                .assertNext(
                        results -> {
                            assertNotNull(results);
                            assertEquals(1, results.size());
                            Document result = results.get(0);
                            assertNotNull(result.getScore());
                            // Cosine distance 0.1 -> similarity score 0.9
                            assertEquals(0.9, result.getScore(), 0.01);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return error when store is closed")
    void testSearchAfterClose() throws Exception {
        store = createMockStoreForSearch();
        store.close();

        double[] query = new double[] {1.0, 0.0, 0.0};

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(query)
                                        .limit(10)
                                        .build()))
                .expectError(VectorStoreException.class)
                .verify();
    }

    @Test
    @DisplayName("Should handle large limit values")
    void testSearchLargeLimit() throws Exception {
        store = createMockStoreForSearch();
        double[] query = new double[] {1.0, 0.0, 0.0};

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(query)
                                        .limit(10000)
                                        .build()))
                .assertNext(Assertions::assertNotNull)
                .verifyComplete();
    }

    // ==================== Delete Method Tests ====================

    private PgVectorStore createMockStoreForDelete(int deletedCount) throws Exception {
        Connection mockConnection = mock(Connection.class);
        Statement mockStatement = mock(Statement.class);
        DatabaseMetaData mockMetaData = mock(DatabaseMetaData.class);
        ResultSet mockTableResultSet = mock(ResultSet.class);
        PreparedStatement mockPreparedStatement = mock(PreparedStatement.class);

        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockConnection.getMetaData()).thenReturn(mockMetaData);
        when(mockMetaData.getTables(any(), any(), anyString(), any()))
                .thenReturn(mockTableResultSet);
        when(mockTableResultSet.next()).thenReturn(true);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
        when(mockPreparedStatement.executeUpdate()).thenReturn(deletedCount);

        try (MockedStatic<DriverManager> driverManagerMock = mockStatic(DriverManager.class);
                MockedStatic<PGvector> pgVectorMock = mockStatic(PGvector.class)) {

            driverManagerMock
                    .when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                    .thenReturn(mockConnection);

            pgVectorMock.when(() -> PGvector.addVectorType(any(Connection.class))).then(i -> null);

            return PgVectorStore.builder()
                    .jdbcUrl(TEST_JDBC_URL)
                    .username(TEST_USERNAME)
                    .password(TEST_PASSWORD)
                    .tableName(TEST_TABLE)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    @Test
    @DisplayName("Should return error for null document ID")
    void testDeleteNullDocId() throws Exception {
        store = createMockStoreForDelete(0);

        StepVerifier.create(store.delete(null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should return error for empty document ID")
    void testDeleteEmptyDocId() throws Exception {
        store = createMockStoreForDelete(0);

        StepVerifier.create(store.delete("")).expectError(IllegalArgumentException.class).verify();
    }

    @Test
    @DisplayName("Should return error for blank document ID")
    void testDeleteBlankDocId() throws Exception {
        store = createMockStoreForDelete(0);

        StepVerifier.create(store.delete("   "))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should delete document successfully and return true")
    void testDeleteSuccess() throws Exception {
        store = createMockStoreForDelete(1);

        StepVerifier.create(store.delete("doc-1"))
                .assertNext(Assertions::assertTrue)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return false when document not found")
    void testDeleteNotFound() throws Exception {
        store = createMockStoreForDelete(0);

        StepVerifier.create(store.delete("non-existent-doc"))
                .assertNext(Assertions::assertFalse)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should delete multiple chunks with same doc ID")
    void testDeleteMultipleChunks() throws Exception {
        store = createMockStoreForDelete(5);

        StepVerifier.create(store.delete("doc-with-multiple-chunks"))
                .assertNext(Assertions::assertTrue)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return error when store is closed")
    void testDeleteAfterClose() throws Exception {
        store = createMockStoreForDelete(1);
        store.close();

        StepVerifier.create(store.delete("doc-1")).expectError(VectorStoreException.class).verify();
    }

    // ==================== Payload Functionality Tests ====================

    private PgVectorStore createMockStoreForPayloadTest() throws Exception {
        Connection mockConnection = mock(Connection.class);
        Statement mockStatement = mock(Statement.class);
        DatabaseMetaData mockMetaData = mock(DatabaseMetaData.class);
        ResultSet mockTableResultSet = mock(ResultSet.class);
        PreparedStatement mockPreparedStatement = mock(PreparedStatement.class);
        ResultSet mockSearchResultSet = mock(ResultSet.class);

        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockConnection.getMetaData()).thenReturn(mockMetaData);
        when(mockMetaData.getTables(any(), any(), anyString(), any()))
                .thenReturn(mockTableResultSet);
        when(mockTableResultSet.next()).thenReturn(true);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] {1});
        when(mockPreparedStatement.executeQuery()).thenReturn(mockSearchResultSet);

        // Setup search results with payload
        when(mockSearchResultSet.next()).thenReturn(true, false);
        when(mockSearchResultSet.getDouble("distance")).thenReturn(0.05);
        when(mockSearchResultSet.getString("doc_id")).thenReturn("doc-payload-test");
        when(mockSearchResultSet.getString("chunk_id")).thenReturn("0");
        when(mockSearchResultSet.getString("content"))
                .thenReturn("{\"type\":\"text\",\"text\":\"Test document content\"}");
        when(mockSearchResultSet.getString("payload"))
                .thenReturn(
                        "{\"filename\":\"report.pdf\",\"department\":\"Engineering\","
                                + "\"author\":\"John Doe\",\"priority\":1,"
                                + "\"tags\":[\"urgent\",\"quarterly\"],"
                                + "\"custom\":{\"author\":\"Alice\",\"version\":2,"
                                + "\"active\":true,\"tags\":[\"important\",\"reviewed\"]}}");
        when(mockSearchResultSet.getObject("embedding")).thenReturn(null);

        try (MockedStatic<DriverManager> driverManagerMock = mockStatic(DriverManager.class);
                MockedStatic<PGvector> pgVectorMock = mockStatic(PGvector.class)) {

            driverManagerMock
                    .when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                    .thenReturn(mockConnection);

            pgVectorMock.when(() -> PGvector.addVectorType(any(Connection.class))).then(i -> null);

            return PgVectorStore.builder()
                    .jdbcUrl(TEST_JDBC_URL)
                    .username(TEST_USERNAME)
                    .password(TEST_PASSWORD)
                    .tableName(TEST_TABLE)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    @Test
    @DisplayName("Should store and load document with custom payload")
    void testDocumentWithPayload() throws Exception {
        store = createMockStoreForPayloadTest();

        // Create document with custom payload
        TextBlock content = TextBlock.builder().text("Test document content").build();

        Map<String, Object> payload = new HashMap<>();
        payload.put("filename", "report.pdf");
        payload.put("department", "Engineering");
        payload.put("author", "John Doe");
        payload.put("priority", 1);
        payload.put("tags", List.of("urgent", "quarterly"));

        // Create custom object
        CustomObject customObject = new CustomObject();
        customObject.setAuthor("Alice");
        customObject.setVersion(2);
        customObject.setActive(true);
        customObject.setTags(List.of("important", "reviewed"));
        payload.put("custom", customObject);

        DocumentMetadata metadata = new DocumentMetadata(content, "doc-payload-test", "0", payload);
        Document doc = new Document(metadata);
        doc.setEmbedding(new double[] {1.0, 0.0, 0.0});

        // Add document
        StepVerifier.create(store.add(List.of(doc))).verifyComplete();

        // Search for the document
        double[] query = new double[] {1.0, 0.0, 0.0};
        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(query)
                                        .limit(10)
                                        .build()))
                .assertNext(
                        results -> {
                            assertNotNull(results, "Search results should not be null");
                            assertEquals(1, results.size(), "Should find exactly one document");

                            Document retrievedDoc = results.get(0);
                            assertNotNull(retrievedDoc, "Retrieved document should not be null");

                            // Verify payload fields are correctly loaded
                            assertEquals(
                                    "report.pdf",
                                    retrievedDoc.getPayloadValue("filename"),
                                    "Filename should match");
                            assertEquals(
                                    "Engineering",
                                    retrievedDoc.getPayloadValue("department"),
                                    "Department should match");
                            assertEquals(
                                    "John Doe",
                                    retrievedDoc.getPayloadValue("author"),
                                    "Author should match");
                            assertEquals(
                                    1,
                                    retrievedDoc.getPayloadValue("priority"),
                                    "Priority should match");

                            // Verify tags list
                            Object tagsObj = retrievedDoc.getPayloadValue("tags");
                            assertNotNull(tagsObj, "Tags should not be null");
                            assertTrue(tagsObj instanceof List, "Tags should be a List");
                            @SuppressWarnings("unchecked")
                            List<String> tags = (List<String>) tagsObj;
                            assertEquals(2, tags.size(), "Should have 2 tags");
                            assertTrue(tags.contains("urgent"), "Should contain 'urgent' tag");
                            assertTrue(
                                    tags.contains("quarterly"), "Should contain 'quarterly' tag");

                            // Verify payload key existence
                            assertTrue(
                                    retrievedDoc.hasPayloadKey("filename"),
                                    "Should have filename key");
                            assertFalse(
                                    retrievedDoc.hasPayloadKey("nonexistent"),
                                    "Should not have nonexistent key");

                            // Verify content is preserved
                            assertEquals(
                                    "Test document content",
                                    retrievedDoc.getMetadata().getContentText(),
                                    "Content should match");

                            // Verify custom object using getPayloadValueAs
                            CustomObject retrievedCustom =
                                    retrievedDoc.getPayloadValueAs("custom", CustomObject.class);
                            assertNotNull(retrievedCustom, "Custom object should not be null");
                            assertEquals("Alice", retrievedCustom.getAuthor());
                            assertEquals(2, retrievedCustom.getVersion());
                            assertTrue(retrievedCustom.isActive());
                        })
                .verifyComplete();
    }

    /**
     * Custom object for testing payload serialization
     */
    static class CustomObject {
        private String author;
        private int version;
        private boolean active;
        private List<String> tags;

        public String getAuthor() {
            return author;
        }

        public void setAuthor(String author) {
            this.author = author;
        }

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }
    }
}
