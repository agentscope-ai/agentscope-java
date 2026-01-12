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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Struct;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.ScoredPoint;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import reactor.test.StepVerifier;

/**
 * Unit tests for QdrantStore.
 *
 * <p>Note: These are minimal unit tests that focus on builder validation and construction
 * parameters. Full functional testing (add, search, delete operations) requires integration tests
 * with a running Qdrant server.
 *
 * <p>For integration tests, use the @Tag("integration") annotation and ensure a Qdrant server is
 * available at the configured location.
 */
@Tag("unit")
@DisplayName("QdrantStore Unit Tests")
class QdrantStoreTest {

    private static final String TEST_LOCATION = "http://localhost:6333";
    private static final String TEST_COLLECTION = "test_collection";
    private static final int TEST_DIMENSIONS = 1024;

    private QdrantStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
            store = null;
        }
    }

    @Test
    @DisplayName("Should create builder instance")
    void testBuilderCreation() {
        QdrantStore.Builder builder = QdrantStore.builder();
        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should throw exception for null location")
    void testNullLocation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location(null)
                            .collectionName(TEST_COLLECTION)
                            .dimensions(TEST_DIMENSIONS)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for empty location")
    void testEmptyLocation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location("")
                            .collectionName(TEST_COLLECTION)
                            .dimensions(TEST_DIMENSIONS)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for blank location")
    void testBlankLocation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location("  ")
                            .collectionName(TEST_COLLECTION)
                            .dimensions(TEST_DIMENSIONS)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for null collection name")
    void testNullCollectionName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location(TEST_LOCATION)
                            .collectionName(null)
                            .dimensions(TEST_DIMENSIONS)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for empty collection name")
    void testEmptyCollectionName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location(TEST_LOCATION)
                            .collectionName("")
                            .dimensions(TEST_DIMENSIONS)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for blank collection name")
    void testBlankCollectionName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location(TEST_LOCATION)
                            .collectionName("  ")
                            .dimensions(TEST_DIMENSIONS)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for zero dimensions")
    void testZeroDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location(TEST_LOCATION)
                            .collectionName(TEST_COLLECTION)
                            .dimensions(0)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for negative dimensions")
    void testNegativeDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location(TEST_LOCATION)
                            .collectionName(TEST_COLLECTION)
                            .dimensions(-1)
                            .build();
                });
    }

    @Test
    @DisplayName("Should create factory instance")
    void testStaticFactoryCreation() {
        // Verify the static factory method exists and returns builder
        QdrantStore.Builder builder =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should set TLS option in builder")
    void testBuilderTlsOption() {
        QdrantStore.Builder builder =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .useTransportLayerSecurity(false);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should set compatibility check option in builder")
    void testBuilderCompatibilityOption() {
        QdrantStore.Builder builder =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .checkCompatibility(false);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should set API key in builder")
    void testBuilderApiKey() {
        QdrantStore.Builder builder =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .apiKey("test-api-key");

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should chain builder methods")
    void testBuilderChaining() {
        QdrantStore.Builder builder =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .apiKey("test-api-key")
                        .useTransportLayerSecurity(true)
                        .checkCompatibility(false);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Qdrant store delete vector")
    void testDelete() throws Exception {
        MockedConstruction<QdrantGrpcClient> mockGrpc = mockConstruction(QdrantGrpcClient.class);
        MockedConstruction<QdrantClient> mockClient =
                mockConstruction(
                        QdrantClient.class,
                        (mock, context) -> {
                            // Mock collection exists check
                            when(mock.collectionExistsAsync(anyString()))
                                    .thenReturn(Futures.immediateFuture(true));

                            // Mock delete operation
                            Points.UpdateResult updateResult =
                                    Points.UpdateResult.newBuilder()
                                            .setStatus(Points.UpdateStatus.Completed)
                                            .build();
                            ListenableFuture<Points.UpdateResult> future =
                                    Futures.immediateFuture(updateResult);
                            when(mock.deleteAsync(anyString(), anyList())).thenReturn(future);
                        });

        QdrantStore mockStore =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .useTransportLayerSecurity(false)
                        .build();

        Boolean delete = mockStore.delete(UUID.randomUUID().toString()).block();

        assertEquals(Boolean.TRUE, delete);

        mockStore.close();
        mockGrpc.close();
        mockClient.close();
    }

    @Test
    @DisplayName("Should throw VectorStoreException when Qdrant store delete vector occur error")
    void testDeleteError() throws Exception {
        MockedConstruction<QdrantGrpcClient> mockGrpc = mockConstruction(QdrantGrpcClient.class);
        MockedConstruction<QdrantClient> mockClient =
                mockConstruction(
                        QdrantClient.class,
                        (mock, context) -> {
                            // Mock collection exists check
                            when(mock.collectionExistsAsync(anyString()))
                                    .thenReturn(Futures.immediateFuture(true));

                            // Mock delete operation
                            when(mock.deleteAsync(anyString(), anyList()))
                                    .thenThrow(new RuntimeException("Test Error"));
                        });

        QdrantStore mockStore =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .useTransportLayerSecurity(false)
                        .build();

        StepVerifier.create(mockStore.delete(UUID.randomUUID().toString()))
                .expectErrorMatches(
                        th ->
                                (th instanceof VectorStoreException vectorStoreException)
                                        && vectorStoreException
                                                .getMessage()
                                                .equals("Failed to delete document in Qdrant")
                                        && (th.getCause()
                                                instanceof RuntimeException runtimeException)
                                        && runtimeException.getMessage().equals("Test Error"))
                .verify();

        mockStore.close();
        mockGrpc.close();
        mockClient.close();
    }

    /**
     * Note: The following tests would require a running Qdrant instance and should be marked as
     * integration tests:
     *
     * <ul>
     *   <li>testAdd - verify batch document addition
     *   <li>testSearch - verify vector similarity search
     *   <li>testSearchWithScoreThreshold - verify score filtering
     *   <li>testDelete - verify document deletion
     *   <li>testClose - verify resource cleanup
     *   <li>testAutoCloseable - verify try-with-resources works
     * </ul>
     *
     * <p>Example integration test structure:
     *
     * <pre>{@code
     * @Test
     * @Tag("integration")
     * @DisplayName("Should add and search documents")
     * void testAddAndSearch() throws Exception {
     *     try (QdrantStore store = QdrantStore.builder()
     *             .location("http://localhost:6333")
     *             .collectionName("test_collection")
     *             .dimensions(3)
     *             .build()) {
     *
     *         // Create test document
     *         TextBlock content = TextBlock.builder().text("Test").build();
     *         DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");
     *         Document doc = new Document(metadata);
     *         doc.setEmbedding(new double[]{1.0, 0.0, 0.0});
     *
     *         // Add document
     *         store.add(List.of(doc)).block();
     *
     *         // Search for similar documents
     *         double[] query = {1.0, 0.0, 0.0};
     *         List<Document> results = store.search(query, 10, null).block();
     *
     *         assertNotNull(results);
     *         assertEquals(1, results.size());
     *         assertEquals(doc.getId(), results.get(0).getId());
     *     }
     * }
     * }</pre>
     */
    @Test
    @DisplayName("Integration tests require running Qdrant server")
    void testIntegrationTestsNote() {
        // This is a placeholder test to document that full testing requires integration tests
        // with a running Qdrant server at http://localhost:6333
        assertEquals(
                true,
                true,
                "Integration tests should be created with @Tag(\"integration\") to test"
                        + " add/search/delete operations");
    }

    // ==================== Helper Methods ====================

    /**
     * Creates a mocked QdrantStore for payload testing.
     *
     * <p>This method uses Mockito to mock the QdrantClient and QdrantGrpcClient,
     * allowing tests to run without a real Qdrant server. The mock returns
     * pre-constructed search results with payload data.
     *
     * @return a mocked QdrantStore instance
     * @throws Exception if store creation fails
     */
    private QdrantStore createMockStoreForPayloadTest() throws Exception {
        try (MockedConstruction<QdrantGrpcClient> ignoredGrpc =
                        mockConstruction(QdrantGrpcClient.class);
                MockedConstruction<QdrantClient> ignoredClient =
                        mockConstruction(
                                QdrantClient.class,
                                (mock, context) -> {
                                    // Mock collection exists check
                                    when(mock.collectionExistsAsync(anyString()))
                                            .thenReturn(Futures.immediateFuture(true));

                                    // Mock upsert operation
                                    when(mock.upsertAsync(anyString(), anyList()))
                                            .thenReturn(Futures.immediateFuture(null));

                                    // Mock search operation with payload
                                    ScoredPoint mockPoint = createMockScoredPoint();
                                    when(mock.queryAsync(any(Points.QueryPoints.class)))
                                            .thenReturn(
                                                    Futures.immediateFuture(List.of(mockPoint)));
                                })) {
            return QdrantStore.builder()
                    .location(TEST_LOCATION)
                    .collectionName(TEST_COLLECTION)
                    .dimensions(TEST_DIMENSIONS)
                    .useTransportLayerSecurity(false)
                    .build();
        }
    }

    /**
     * Creates a mock ScoredPoint with payload data for testing.
     *
     * @return a mocked ScoredPoint
     */
    private ScoredPoint createMockScoredPoint() {
        // Create payload map
        Map<String, Value> payloadMap = new HashMap<>();

        // Add doc_id
        payloadMap.put("doc_id", Value.newBuilder().setStringValue("doc-payload-test").build());

        // Add chunk_id
        payloadMap.put("chunk_id", Value.newBuilder().setStringValue("0").build());

        // Add content (as a struct representing TextBlock)
        Struct.Builder contentStruct = Struct.newBuilder();
        contentStruct.putFields("type", Value.newBuilder().setStringValue("text").build());
        contentStruct.putFields(
                "text", Value.newBuilder().setStringValue("Test document content").build());
        payloadMap.put("content", Value.newBuilder().setStructValue(contentStruct).build());

        // Add payload (custom fields)
        Struct.Builder customPayloadStruct = Struct.newBuilder();
        customPayloadStruct.putFields(
                "filename", Value.newBuilder().setStringValue("report.pdf").build());
        customPayloadStruct.putFields(
                "department", Value.newBuilder().setStringValue("Engineering").build());
        customPayloadStruct.putFields(
                "author", Value.newBuilder().setStringValue("John Doe").build());
        customPayloadStruct.putFields("priority", Value.newBuilder().setIntegerValue(1L).build());

        // Add tags list
        io.qdrant.client.grpc.JsonWithInt.ListValue.Builder tagsList =
                io.qdrant.client.grpc.JsonWithInt.ListValue.newBuilder();
        tagsList.addValues(Value.newBuilder().setStringValue("urgent").build());
        tagsList.addValues(Value.newBuilder().setStringValue("quarterly").build());
        customPayloadStruct.putFields("tags", Value.newBuilder().setListValue(tagsList).build());

        // Add custom object
        Struct.Builder customObjectStruct = Struct.newBuilder();
        customObjectStruct.putFields(
                "author", Value.newBuilder().setStringValue("John Doe").build());
        customObjectStruct.putFields("version", Value.newBuilder().setIntegerValue(1L).build());
        customObjectStruct.putFields("active", Value.newBuilder().setBoolValue(true).build());

        io.qdrant.client.grpc.JsonWithInt.ListValue.Builder customTagsList =
                io.qdrant.client.grpc.JsonWithInt.ListValue.newBuilder();
        customTagsList.addValues(Value.newBuilder().setStringValue("urgent").build());
        customTagsList.addValues(Value.newBuilder().setStringValue("quarterly").build());
        customObjectStruct.putFields(
                "tags", Value.newBuilder().setListValue(customTagsList).build());

        customPayloadStruct.putFields(
                "custom", Value.newBuilder().setStructValue(customObjectStruct).build());

        payloadMap.put("payload", Value.newBuilder().setStructValue(customPayloadStruct).build());

        // Create PointId
        PointId pointId =
                PointId.newBuilder().setUuid("550e8400-e29b-41d4-a716-446655440000").build();

        // Build ScoredPoint
        return ScoredPoint.newBuilder()
                .setId(pointId)
                .setScore(0.95f)
                .putAllPayload(payloadMap)
                .build();
    }

    // ==================== Integration Tests ====================

    /**
     * Integration test for document metadata payload functionality.
     *
     * <p>This test verifies that custom payload fields in DocumentMetadata are correctly stored
     * to and loaded from Qdrant. It requires a running Qdrant server at http://localhost:6333.
     */
    @Test
    @Tag("integration")
    @DisplayName("Should store and load document metadata payload")
    void testDocumentMetadataPayload() throws Exception {
        store = createMockStoreForPayloadTest();

        // Create document with custom payload
        TextBlock content = TextBlock.builder().text("Test document content").build();

        Map<String, Object> payload = new HashMap<>();
        payload.put("filename", "report.pdf");
        payload.put("department", "Engineering");
        payload.put("author", "John Doe");
        payload.put("priority", 1L);
        payload.put("tags", List.of("urgent", "quarterly"));

        CustomObject customObject = new CustomObject();
        customObject.setAuthor("John Doe");
        customObject.setVersion(1);
        customObject.setActive(true);
        customObject.setTags(List.of("urgent", "quarterly"));
        payload.put("custom", customObject);

        DocumentMetadata metadata = new DocumentMetadata(content, "doc-payload-test", "0", payload);
        Document doc = new Document(metadata);
        double[] embedding = new double[TEST_DIMENSIONS];
        embedding[0] = 1.0;
        doc.setEmbedding(embedding);
        store.add(List.of(doc)).block();

        // Search for the document
        double[] query = new double[TEST_DIMENSIONS];
        query[0] = 1.0;
        List<Document> results =
                store.search(SearchDocumentDto.builder().queryEmbedding(query).limit(10).build())
                        .block();

        // Verify results
        assertNotNull(results, "Search results should not be null");
        assertEquals(1L, results.size(), "Should find exactly one document");

        Document retrievedDoc = results.get(0);
        assertNotNull(retrievedDoc, "Retrieved document should not be null");

        // Verify payload fields are correctly loaded
        assertEquals(
                "report.pdf", retrievedDoc.getPayloadValue("filename"), "Filename should match");
        assertEquals(
                "Engineering",
                retrievedDoc.getPayloadValue("department"),
                "Department should match");
        assertEquals("John Doe", retrievedDoc.getPayloadValue("author"), "Author should match");
        assertEquals(1L, retrievedDoc.getPayloadValue("priority"), "Priority should match");

        // Verify tags list
        Object tagsObj = retrievedDoc.getPayloadValue("tags");
        assertNotNull(tagsObj, "Tags should not be null");
        assertEquals(true, tagsObj instanceof List, "Tags should be a List");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) tagsObj;
        assertEquals(2, tags.size(), "Should have 2 tags");
        assertEquals(true, tags.contains("urgent"), "Should contain 'urgent' tag");
        assertEquals(true, tags.contains("quarterly"), "Should contain 'quarterly' tag");

        // Verify payload key existence
        assertEquals(true, retrievedDoc.hasPayloadKey("filename"), "Should have filename key");
        assertEquals(
                false,
                retrievedDoc.hasPayloadKey("nonexistent"),
                "Should not have nonexistent key");

        // Verify content is preserved
        assertEquals(
                "Test document content",
                retrievedDoc.getMetadata().getContentText(),
                "Content should match");

        // Verify custom object
        CustomObject customObj = retrievedDoc.getPayloadValueAs("custom", CustomObject.class);
        assertNotNull(customObj, "Custom object should not be null");
    }

    /**
     * Custom object class for testing payload serialization
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
