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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.SearchResp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import reactor.test.StepVerifier;

/**
 * Unit tests for MilvusStore.
 *
 * <p>These tests cover parameter validation, builder configuration, and mock-based
 * functional testing. Full integration tests with a running Milvus server should
 * be marked with @Tag("integration").
 */
@Tag("unit")
@DisplayName("MilvusStore Unit Tests")
class MilvusStoreTest {

    private static final String TEST_URI = "http://localhost:19530";
    private static final String TEST_COLLECTION = "test_collection";
    private static final int TEST_DIMENSIONS = 3;

    private MilvusStore store;

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
        MilvusStore.Builder builder = MilvusStore.builder();
        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should throw exception for null URI")
    void testNullUri() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store =
                                MilvusStore.builder()
                                        .uri(null)
                                        .collectionName(TEST_COLLECTION)
                                        .dimensions(TEST_DIMENSIONS)
                                        .build());
    }

    @Test
    @DisplayName("Should throw exception for empty URI")
    void testEmptyUri() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store =
                                MilvusStore.builder()
                                        .uri("")
                                        .collectionName(TEST_COLLECTION)
                                        .dimensions(TEST_DIMENSIONS)
                                        .build());
    }

    @Test
    @DisplayName("Should throw exception for blank URI")
    void testBlankUri() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store =
                                MilvusStore.builder()
                                        .uri("  ")
                                        .collectionName(TEST_COLLECTION)
                                        .dimensions(TEST_DIMENSIONS)
                                        .build());
    }

    @Test
    @DisplayName("Should throw exception for null collection name")
    void testNullCollectionName() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store =
                                MilvusStore.builder()
                                        .uri(TEST_URI)
                                        .collectionName(null)
                                        .dimensions(TEST_DIMENSIONS)
                                        .build());
    }

    @Test
    @DisplayName("Should throw exception for empty collection name")
    void testEmptyCollectionName() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store =
                                MilvusStore.builder()
                                        .uri(TEST_URI)
                                        .collectionName("")
                                        .dimensions(TEST_DIMENSIONS)
                                        .build());
    }

    @Test
    @DisplayName("Should throw exception for blank collection name")
    void testBlankCollectionName() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store =
                                MilvusStore.builder()
                                        .uri(TEST_URI)
                                        .collectionName("  ")
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
                                MilvusStore.builder()
                                        .uri(TEST_URI)
                                        .collectionName(TEST_COLLECTION)
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
                                MilvusStore.builder()
                                        .uri(TEST_URI)
                                        .collectionName(TEST_COLLECTION)
                                        .dimensions(-1)
                                        .build());
    }

    @Test
    @DisplayName("Should throw exception for null metric type")
    void testNullMetricType() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store =
                                MilvusStore.builder()
                                        .uri(TEST_URI)
                                        .collectionName(TEST_COLLECTION)
                                        .dimensions(TEST_DIMENSIONS)
                                        .metricType(null)
                                        .build());
    }

    // ==================== Builder Configuration Tests ====================

    @Test
    @DisplayName("Should create factory instance")
    void testStaticFactoryCreation() {
        MilvusStore.Builder builder =
                MilvusStore.builder()
                        .uri(TEST_URI)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should allow empty token")
    void testBuilderEmptyToken() {
        MilvusStore.Builder builder =
                MilvusStore.builder()
                        .uri(TEST_URI)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .token("");

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should allow null password with username")
    void testBuilderNullPasswordWithUsername() {
        MilvusStore.Builder builder =
                MilvusStore.builder()
                        .uri(TEST_URI)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .username("root")
                        .password(null);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should allow zero timeout")
    void testBuilderZeroTimeout() {
        MilvusStore.Builder builder =
                MilvusStore.builder()
                        .uri(TEST_URI)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .connectTimeoutMs(0);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should allow negative timeout (for disable)")
    void testBuilderNegativeTimeout() {
        MilvusStore.Builder builder =
                MilvusStore.builder()
                        .uri(TEST_URI)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .connectTimeoutMs(-1);

        assertNotNull(builder);
    }

    // ==================== Mock-based Functional Tests ====================

    private MilvusStore createMockStore() throws VectorStoreException {
        try (MockedConstruction<MilvusClientV2> ignored =
                mockConstruction(
                        MilvusClientV2.class,
                        (mock, context) ->
                                when(mock.hasCollection(any(HasCollectionReq.class)))
                                        .thenReturn(true))) {
            return MilvusStore.builder()
                    .uri(TEST_URI)
                    .collectionName(TEST_COLLECTION)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    @Test
    @DisplayName("Should build store with mock client and existing collection")
    void testBuildWithMockClientExistingCollection() throws VectorStoreException {
        store = createMockStore();
        assertNotNull(store);
        assertEquals(TEST_URI, store.getUri());
        assertEquals(TEST_COLLECTION, store.getCollectionName());
        assertEquals(TEST_DIMENSIONS, store.getDimensions());
        assertEquals(IndexParam.MetricType.COSINE, store.getMetricType());
        store.close();
    }

    @Test
    @DisplayName("Should create new collection when not exists")
    void testBuildWithMockClientNewCollection() throws VectorStoreException {
        try (MockedConstruction<MilvusClientV2> ignored =
                mockConstruction(
                        MilvusClientV2.class,
                        (mock, context) ->
                                when(mock.hasCollection(any(HasCollectionReq.class)))
                                        .thenReturn(false))) {
            store =
                    MilvusStore.builder()
                            .uri(TEST_URI)
                            .collectionName(TEST_COLLECTION)
                            .dimensions(TEST_DIMENSIONS)
                            .build();
            assertNotNull(store);
            store.close();
        }
    }

    @Test
    @DisplayName("Should close store successfully")
    void testCloseStore() throws VectorStoreException {
        store = createMockStore();
        assertFalse(store.isClosed());
        store.close();
        assertTrue(store.isClosed());
    }

    @Test
    @DisplayName("Should be idempotent when closing multiple times")
    void testCloseIdempotent() throws VectorStoreException {
        store = createMockStore();
        store.close();
        assertTrue(store.isClosed());
        store.close(); // Should not throw
        assertTrue(store.isClosed());
    }

    @Test
    @DisplayName("Should throw exception when getting client after close")
    void testGetClientAfterClose() throws VectorStoreException {
        store = createMockStore();
        store.close();
        assertThrows(VectorStoreException.class, () -> store.getClient());
    }

    @Test
    @DisplayName("Should return client when not closed")
    void testGetClientBeforeClose() throws VectorStoreException {
        store = createMockStore();
        assertNotNull(store.getClient());
    }

    // ==================== Add Method Tests ====================

    private MilvusStore createMockStoreForAdd() throws VectorStoreException {
        try (MockedConstruction<MilvusClientV2> ignored =
                mockConstruction(
                        MilvusClientV2.class,
                        (mock, context) -> {
                            when(mock.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
                            InsertResp insertResp = mock(InsertResp.class);
                            when(insertResp.getInsertCnt()).thenReturn(1L);
                            when(mock.insert(any(InsertReq.class))).thenReturn(insertResp);
                        })) {
            return MilvusStore.builder()
                    .uri(TEST_URI)
                    .collectionName(TEST_COLLECTION)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    @Test
    @DisplayName("Should return error for null documents list")
    void testAddNullDocuments() throws VectorStoreException {
        store = createMockStoreForAdd();

        StepVerifier.create(store.add(null)).expectError(IllegalArgumentException.class).verify();
    }

    @Test
    @DisplayName("Should complete for empty documents list")
    void testAddEmptyDocuments() throws VectorStoreException {
        store = createMockStoreForAdd();

        StepVerifier.create(store.add(List.of())).verifyComplete();
    }

    @Test
    @DisplayName("Should return error for null document in list")
    void testAddNullDocumentInList() throws VectorStoreException {
        store = createMockStoreForAdd();
        List<Document> documents = new ArrayList<>();
        documents.add(null);

        StepVerifier.create(store.add(documents))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should return error for document without embedding")
    void testAddDocumentWithoutEmbedding() throws VectorStoreException {
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
    void testAddDimensionMismatch() throws VectorStoreException {
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
    void testAddSingleDocument() throws VectorStoreException {
        store = createMockStoreForAdd();
        TextBlock content = TextBlock.builder().text("Test content").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");
        Document doc = new Document(metadata);
        doc.setEmbedding(new double[] {1.0, 0.0, 0.0});

        StepVerifier.create(store.add(List.of(doc))).verifyComplete();
    }

    @Test
    @DisplayName("Should add multiple documents successfully")
    void testAddMultipleDocuments() throws VectorStoreException {
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
    void testAddAfterClose() throws VectorStoreException {
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

    private MilvusStore createMockStoreForSearch() throws VectorStoreException {
        try (MockedConstruction<MilvusClientV2> ignored =
                mockConstruction(
                        MilvusClientV2.class,
                        (mock, context) -> {
                            when(mock.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
                            SearchResp searchResp = mock(SearchResp.class);
                            when(searchResp.getSearchResults())
                                    .thenReturn(List.of(Collections.emptyList()));
                            when(mock.search(any(SearchReq.class))).thenReturn(searchResp);
                        })) {
            return MilvusStore.builder()
                    .uri(TEST_URI)
                    .collectionName(TEST_COLLECTION)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    private MilvusStore createMockStoreForSearchWithResults() throws VectorStoreException {
        try (MockedConstruction<MilvusClientV2> ignored =
                mockConstruction(
                        MilvusClientV2.class,
                        (mock, context) -> {
                            when(mock.hasCollection(any(HasCollectionReq.class))).thenReturn(true);

                            // Create mock search result
                            SearchResp.SearchResult mockResult =
                                    mock(SearchResp.SearchResult.class);
                            when(mockResult.getScore()).thenReturn(0.9f);
                            Map<String, Object> entity = new HashMap<>();
                            entity.put("doc_id", "doc-1");
                            entity.put("chunk_id", 0);
                            entity.put("content", "{\"type\":\"text\",\"text\":\"Test content\"}");
                            when(mockResult.getEntity()).thenReturn(entity);

                            SearchResp searchResp = mock(SearchResp.class);
                            when(searchResp.getSearchResults())
                                    .thenReturn(List.of(List.of(mockResult)));
                            when(mock.search(any(SearchReq.class))).thenReturn(searchResp);
                        })) {
            return MilvusStore.builder()
                    .uri(TEST_URI)
                    .collectionName(TEST_COLLECTION)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    @Test
    @DisplayName("Should return error for null query embedding")
    void testSearchNullQueryEmbedding() throws VectorStoreException {
        store = createMockStoreForSearch();

        StepVerifier.create(store.search(null, 10, null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should return error for dimension mismatch in query")
    void testSearchDimensionMismatch() throws VectorStoreException {
        store = createMockStoreForSearch();
        double[] wrongDimensionQuery = new double[] {1.0, 2.0}; // Wrong dimension

        StepVerifier.create(store.search(wrongDimensionQuery, 10, null))
                .expectError(VectorStoreException.class)
                .verify();
    }

    @Test
    @DisplayName("Should return error for zero limit")
    void testSearchZeroLimit() throws VectorStoreException {
        store = createMockStoreForSearch();
        double[] query = new double[] {1.0, 0.0, 0.0};

        StepVerifier.create(store.search(query, 0, null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should return error for negative limit")
    void testSearchNegativeLimit() throws VectorStoreException {
        store = createMockStoreForSearch();
        double[] query = new double[] {1.0, 0.0, 0.0};

        StepVerifier.create(store.search(query, -1, null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should search successfully with valid parameters")
    void testSearchValidParameters() throws VectorStoreException {
        store = createMockStoreForSearch();
        double[] query = new double[] {1.0, 0.0, 0.0};

        StepVerifier.create(store.search(query, 10, null))
                .assertNext(
                        results -> {
                            assertNotNull(results);
                            assertTrue(results.isEmpty());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should search with score threshold")
    void testSearchWithScoreThreshold() throws VectorStoreException {
        store = createMockStoreForSearch();
        double[] query = new double[] {1.0, 0.0, 0.0};

        StepVerifier.create(store.search(query, 10, 0.5))
                .assertNext(Assertions::assertNotNull)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should search with zero score threshold")
    void testSearchWithZeroScoreThreshold() throws VectorStoreException {
        store = createMockStoreForSearch();
        double[] query = new double[] {1.0, 0.0, 0.0};

        StepVerifier.create(store.search(query, 10, 0.0))
                .assertNext(Assertions::assertNotNull)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should search and return results")
    void testSearchWithResults() throws VectorStoreException {
        store = createMockStoreForSearchWithResults();
        double[] query = new double[] {1.0, 0.0, 0.0};

        StepVerifier.create(store.search(query, 10, null))
                .assertNext(
                        results -> {
                            assertNotNull(results);
                            assertEquals(1, results.size());
                            Document result = results.get(0);
                            assertNotNull(result.getScore());
                            assertEquals(0.9, result.getScore(), 0.01);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should filter results by score threshold")
    void testSearchFilterByScoreThreshold() throws VectorStoreException {
        store = createMockStoreForSearchWithResults();
        double[] query = new double[] {1.0, 0.0, 0.0};

        // Score threshold higher than result score (0.9)
        StepVerifier.create(store.search(query, 10, 0.95))
                .assertNext(
                        results -> {
                            assertNotNull(results);
                            assertTrue(results.isEmpty());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return error when store is closed")
    void testSearchAfterClose() throws VectorStoreException {
        store = createMockStoreForSearch();
        store.close();

        double[] query = new double[] {1.0, 0.0, 0.0};

        StepVerifier.create(store.search(query, 10, null))
                .expectError(VectorStoreException.class)
                .verify();
    }

    @Test
    @DisplayName("Should handle large limit values")
    void testSearchLargeLimit() throws VectorStoreException {
        store = createMockStoreForSearch();
        double[] query = new double[] {1.0, 0.0, 0.0};

        StepVerifier.create(store.search(query, 10000, null))
                .assertNext(Assertions::assertNotNull)
                .verifyComplete();
    }

    // ==================== Delete Method Tests ====================

    private MilvusStore createMockStoreForDelete(long deleteCnt) throws VectorStoreException {
        try (MockedConstruction<MilvusClientV2> ignored =
                mockConstruction(
                        MilvusClientV2.class,
                        (mock, context) -> {
                            when(mock.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
                            DeleteResp deleteResp = mock(DeleteResp.class);
                            when(deleteResp.getDeleteCnt()).thenReturn(deleteCnt);
                            when(mock.delete(any(DeleteReq.class))).thenReturn(deleteResp);
                        })) {
            return MilvusStore.builder()
                    .uri(TEST_URI)
                    .collectionName(TEST_COLLECTION)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    @Test
    @DisplayName("Should return error for null document ID")
    void testDeleteNullDocId() throws VectorStoreException {
        store = createMockStoreForDelete(0);

        StepVerifier.create(store.delete(null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should return error for empty document ID")
    void testDeleteEmptyDocId() throws VectorStoreException {
        store = createMockStoreForDelete(0);

        StepVerifier.create(store.delete("")).expectError(IllegalArgumentException.class).verify();
    }

    @Test
    @DisplayName("Should return error for blank document ID")
    void testDeleteBlankDocId() throws VectorStoreException {
        store = createMockStoreForDelete(0);

        StepVerifier.create(store.delete("   "))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should delete document successfully and return true")
    void testDeleteSuccess() throws VectorStoreException {
        store = createMockStoreForDelete(1);

        StepVerifier.create(store.delete("doc-1"))
                .assertNext(Assertions::assertTrue)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return false when document not found")
    void testDeleteNotFound() throws VectorStoreException {
        store = createMockStoreForDelete(0);

        StepVerifier.create(store.delete("non-existent-doc"))
                .assertNext(Assertions::assertFalse)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should delete multiple chunks with same doc ID")
    void testDeleteMultipleChunks() throws VectorStoreException {
        store = createMockStoreForDelete(5);

        StepVerifier.create(store.delete("doc-with-multiple-chunks"))
                .assertNext(Assertions::assertTrue)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return error when store is closed")
    void testDeleteAfterClose() throws VectorStoreException {
        store = createMockStoreForDelete(1);
        store.close();

        StepVerifier.create(store.delete("doc-1")).expectError(VectorStoreException.class).verify();
    }
}
