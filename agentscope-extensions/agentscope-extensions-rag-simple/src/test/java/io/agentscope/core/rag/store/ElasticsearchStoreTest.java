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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import reactor.test.StepVerifier;

@Tag("unit")
@DisplayName("ElasticsearchStore Unit Tests")
public class ElasticsearchStoreTest {
    private static final String TEST_URL = "http://localhost:9200";
    private static final String TEST_INDEX = "test_index";
    private static final int TEST_DIMENSIONS = 1536;

    private ElasticsearchStore store;

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
        ElasticsearchStore.Builder builder = ElasticsearchStore.builder();
        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should throw exception for null URL")
    void testNullUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ElasticsearchStore.builder()
                                .url(null)
                                .indexName(TEST_INDEX)
                                .dimensions(TEST_DIMENSIONS)
                                .build());
    }

    @Test
    @DisplayName("Should throw exception for empty URL")
    void testEmptyUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ElasticsearchStore.builder()
                                .url("")
                                .indexName(TEST_INDEX)
                                .dimensions(TEST_DIMENSIONS)
                                .build());
    }

    @Test
    @DisplayName("Should throw exception for null index name")
    void testNullIndexName() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ElasticsearchStore.builder()
                                .url(TEST_URL)
                                .indexName(null)
                                .dimensions(TEST_DIMENSIONS)
                                .build());
    }

    @Test
    @DisplayName("Should throw exception for zero dimensions")
    void testZeroDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ElasticsearchStore.builder()
                                .url(TEST_URL)
                                .indexName(TEST_INDEX)
                                .dimensions(0)
                                .build());
    }

    // ==================== Mock-based Functional Tests ====================

    /**
     * Helper to create a store with a mock ElasticsearchClient.
     * This mocks the constructor initialization including ensureIndex().
     */
    private ElasticsearchStore createMockStore() throws VectorStoreException {
        // We mock the construction of ElasticsearchClient
        try (MockedConstruction<ElasticsearchClient> ignored =
                mockConstruction(
                        ElasticsearchClient.class,
                        (mock, context) -> {
                            // Mock indices client for ensureIndex()
                            ElasticsearchIndicesClient indicesClient =
                                    mock(ElasticsearchIndicesClient.class);
                            when(mock.indices()).thenReturn(indicesClient);

                            // Mock index exists check -> return true to skip creation logic
                            BooleanResponse boolResp = mock(BooleanResponse.class);
                            when(boolResp.value()).thenReturn(true);
                            when(indicesClient.exists(any(ExistsRequest.class)))
                                    .thenReturn(boolResp);
                        })) {
            return ElasticsearchStore.builder()
                    .url(TEST_URL)
                    .indexName(TEST_INDEX)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    @Test
    @DisplayName("Should build store with mock client")
    void testBuildWithMockClient() throws VectorStoreException {
        store = createMockStore();
        assertNotNull(store);
    }

    @Test
    @DisplayName("Should close store successfully")
    void testCloseStore() throws VectorStoreException {
        store = createMockStore();
        store.close();
        // ElasticsearchStore doesn't expose isClosed(), but we verify no exception is thrown
    }

    @Test
    @DisplayName("Should configure SSL context when URL starts with https")
    void testBuildWithHttpsUrl() throws VectorStoreException {
        // Mock the construction process to avoid real network connections
        try (MockedConstruction<ElasticsearchClient> ignored =
                mockConstruction(
                        ElasticsearchClient.class,
                        (mock, context) -> {
                            // Mock index check to avoid throwing exceptions in the constructor
                            ElasticsearchIndicesClient indicesClient =
                                    mock(ElasticsearchIndicesClient.class);
                            when(mock.indices()).thenReturn(indicesClient);
                            BooleanResponse boolResp = mock(BooleanResponse.class);
                            when(boolResp.value()).thenReturn(true);
                            when(indicesClient.exists(any(ExistsRequest.class)))
                                    .thenReturn(boolResp);
                        })) {

            // 1. Set HTTPS URL and authentication credentials
            // This will cover the code paths for SSL handling and setDefaultCredentialsProvider in
            // the ElasticsearchStore constructor
            ElasticsearchStore httpsStore =
                    ElasticsearchStore.builder()
                            .url("https://localhost:9200")
                            .indexName(TEST_INDEX)
                            .dimensions(TEST_DIMENSIONS)
                            .username("admin")
                            .password("secret")
                            .disableSslVerification(true)
                            .build();

            assertNotNull(httpsStore);
            httpsStore.close();
        }
    }

    @Test
    @DisplayName("Should create index with correct mappings when index does not exist")
    void testEnsureIndexCreatesIndex() throws VectorStoreException, IOException {
        // Use MockedConstruction to intercept the creation of ElasticsearchClient
        try (MockedConstruction<ElasticsearchClient> ignored =
                mockConstruction(
                        ElasticsearchClient.class,
                        (mock, context) -> {
                            ElasticsearchIndicesClient indicesClient =
                                    mock(ElasticsearchIndicesClient.class);
                            when(mock.indices()).thenReturn(indicesClient);

                            // 1. Mock exists() to return false, forcing entry into the index
                            // creation branch
                            BooleanResponse existsResp = mock(BooleanResponse.class);
                            when(existsResp.value()).thenReturn(false);
                            when(indicesClient.exists(any(ExistsRequest.class)))
                                    .thenReturn(existsResp);

                            // 2. Mock create() call to prevent NullPointerException
                            CreateIndexResponse createResp = mock(CreateIndexResponse.class);
                            when(indicesClient.create(any(CreateIndexRequest.class)))
                                    .thenReturn(createResp);
                        })) {

            // Initialize Store, which triggers ensureIndex() in the constructor
            ElasticsearchStore newStore =
                    ElasticsearchStore.builder()
                            .url(TEST_URL)
                            .indexName(TEST_INDEX)
                            .dimensions(TEST_DIMENSIONS)
                            .build();

            // Get the Mock object for verification
            ElasticsearchClient mockClient = ignored.constructed().get(0);
            ElasticsearchIndicesClient indicesClient = mockClient.indices();

            // 3. Capture CreateIndexRequest parameters to verify Mapping settings
            ArgumentCaptor<CreateIndexRequest> captor =
                    ArgumentCaptor.forClass(CreateIndexRequest.class);
            verify(indicesClient).create(captor.capture());

            CreateIndexRequest request = captor.getValue();

            // Verify index name
            assertEquals(TEST_INDEX, request.index());

            // Verify key properties (Coverage for lines 154-160)
            Map<String, Property> props = request.mappings().properties();

            // Verify Vector field
            assertTrue(props.containsKey("vector"), "Should contain vector field");
            Property vectorProp = props.get("vector");
            assertTrue(vectorProp.isDenseVector());
            assertEquals(TEST_DIMENSIONS, vectorProp.denseVector().dims());
            assertEquals("cosine", vectorProp.denseVector().similarity());
            assertTrue(vectorProp.denseVector().index());

            // Verify Content field
            assertTrue(props.containsKey("content"), "Should contain content field");
            assertTrue(props.get("content").isText());
            assertEquals(Boolean.FALSE, props.get("content").text().index());

            // Verify ID field
            assertTrue(props.containsKey("id"), "Should contain id field");
            assertTrue(props.get("id").isKeyword());

            newStore.close();
        }
    }

    // ==================== Add Method Tests ====================

    private ElasticsearchStore createMockStoreForAdd(boolean success) throws VectorStoreException {
        try (MockedConstruction<ElasticsearchClient> ignored =
                mockConstruction(
                        ElasticsearchClient.class,
                        (mock, context) -> {
                            // Handle ensureIndex
                            ElasticsearchIndicesClient indicesClient =
                                    mock(ElasticsearchIndicesClient.class);
                            when(mock.indices()).thenReturn(indicesClient);
                            BooleanResponse boolResp = mock(BooleanResponse.class);
                            when(boolResp.value()).thenReturn(true);
                            when(indicesClient.exists(any(ExistsRequest.class)))
                                    .thenReturn(boolResp);

                            // Handle Bulk
                            BulkResponse bulkResp = mock(BulkResponse.class);
                            when(bulkResp.errors()).thenReturn(!success);
                            // If failure, the store iterates items, but for simple test we just
                            // verify exception on error
                            when(bulkResp.items()).thenReturn(Collections.emptyList());
                            when(mock.bulk(any(BulkRequest.class))).thenReturn(bulkResp);
                        })) {
            return ElasticsearchStore.builder()
                    .url(TEST_URL)
                    .indexName(TEST_INDEX)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    @Test
    @DisplayName("Should complete for empty documents list")
    void testAddEmptyDocuments() throws VectorStoreException {
        store = createMockStoreForAdd(true);
        StepVerifier.create(store.add(List.of())).verifyComplete();
    }

    @Test
    @DisplayName("Should add documents successfully")
    void testAddSuccess() throws VectorStoreException {
        store = createMockStoreForAdd(true);

        TextBlock content = TextBlock.builder().text("Test content").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "chunk-1");
        Document doc = new Document(metadata);
        doc.setEmbedding(new double[TEST_DIMENSIONS]);

        StepVerifier.create(store.add(List.of(doc))).verifyComplete();
    }

    @Test
    @DisplayName("Should throw exception on bulk error")
    void testAddFailure() throws VectorStoreException {
        store = createMockStoreForAdd(false); // Simulated failure

        TextBlock content = TextBlock.builder().text("Test content").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "chunk-1");
        Document doc = new Document(metadata);
        doc.setEmbedding(new double[TEST_DIMENSIONS]);

        StepVerifier.create(store.add(List.of(doc)))
                .expectError(VectorStoreException.class)
                .verify();
    }

    @Test
    @DisplayName("Should validate document dimensions")
    void testAddDimensionMismatch() throws VectorStoreException {
        store = createMockStoreForAdd(true);

        TextBlock content = TextBlock.builder().text("Test").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");
        Document doc = new Document(metadata);
        doc.setEmbedding(new double[1]); // Wrong dimension

        StepVerifier.create(store.add(List.of(doc)))
                .expectError(VectorStoreException.class)
                .verify();
    }

    // ==================== Search Method Tests ====================

    @SuppressWarnings("unchecked")
    private ElasticsearchStore createMockStoreForSearch(List<Map<String, Object>> mockHits)
            throws VectorStoreException {
        try (MockedConstruction<ElasticsearchClient> ignored =
                mockConstruction(
                        ElasticsearchClient.class,
                        (mock, context) -> {
                            // Handle ensureIndex
                            ElasticsearchIndicesClient indicesClient =
                                    mock(ElasticsearchIndicesClient.class);
                            when(mock.indices()).thenReturn(indicesClient);
                            BooleanResponse boolResp = mock(BooleanResponse.class);
                            when(boolResp.value()).thenReturn(true);
                            when(indicesClient.exists(any(ExistsRequest.class)))
                                    .thenReturn(boolResp);

                            // Handle Search
                            SearchResponse<Map> searchResp = mock(SearchResponse.class);
                            HitsMetadata<Map> hitsMetadata = mock(HitsMetadata.class);
                            List<Hit<Map>> hitList = new ArrayList<>();

                            for (Map<String, Object> source : mockHits) {
                                Hit<Map> hit = mock(Hit.class);
                                when(hit.source()).thenReturn(source);
                                when(hit.score()).thenReturn(0.95);
                                hitList.add(hit);
                            }

                            when(hitsMetadata.hits()).thenReturn(hitList);
                            when(searchResp.hits()).thenReturn(hitsMetadata);

                            // Mocking the generic search call
                            when(mock.search(any(SearchRequest.class), eq(Map.class)))
                                    .thenReturn(searchResp);
                        })) {
            return ElasticsearchStore.builder()
                    .url(TEST_URL)
                    .indexName(TEST_INDEX)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    @Test
    @DisplayName("Should search successfully")
    void testSearchSuccess() throws VectorStoreException {
        // Prepare mock result
        Map<String, Object> source = new HashMap<>();
        source.put("doc_id", "doc-1");
        source.put("chunk_id", "0");
        source.put("content", "{\"type\":\"text\",\"text\":\"content\"}");
        // Mock vector return
        List<Double> vector = new ArrayList<>();
        for (int i = 0; i < TEST_DIMENSIONS; i++) vector.add(0.0);
        source.put("vector", vector);

        store = createMockStoreForSearch(List.of(source));

        double[] query = new double[TEST_DIMENSIONS];
        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(query)
                                        .limit(5)
                                        .scoreThreshold(0.5)
                                        .build()))
                .assertNext(
                        results -> {
                            assertNotNull(results);
                            assertEquals(1, results.size());
                            assertEquals("doc-1", results.get(0).getMetadata().getDocId());
                            assertEquals(0.95, results.get(0).getScore());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw exception for dimension mismatch in query")
    void testSearchDimensionMismatch() throws VectorStoreException {
        store = createMockStoreForSearch(Collections.emptyList());
        double[] query = new double[1]; // Wrong dimension

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(query)
                                        .limit(5)
                                        .scoreThreshold(null)
                                        .build()))
                .expectError(VectorStoreException.class)
                .verify();
    }

    // ==================== Delete Method Tests ====================

    private ElasticsearchStore createMockStoreForDelete(boolean deleted)
            throws VectorStoreException {
        try (MockedConstruction<ElasticsearchClient> ignored =
                mockConstruction(
                        ElasticsearchClient.class,
                        (mock, context) -> {
                            // Handle ensureIndex
                            ElasticsearchIndicesClient indicesClient =
                                    mock(ElasticsearchIndicesClient.class);
                            when(mock.indices()).thenReturn(indicesClient);
                            BooleanResponse boolResp = mock(BooleanResponse.class);
                            when(boolResp.value()).thenReturn(true);
                            when(indicesClient.exists(any(ExistsRequest.class)))
                                    .thenReturn(boolResp);

                            // Handle Delete
                            DeleteResponse deleteResp = mock(DeleteResponse.class);
                            Result result = deleted ? Result.Deleted : Result.NotFound;
                            when(deleteResp.result()).thenReturn(result);

                            // when(mock.delete(any(DeleteRequest.class))).thenReturn(deleteResp);
                            when(mock.delete(any(Function.class))).thenReturn(deleteResp);
                        })) {
            return ElasticsearchStore.builder()
                    .url(TEST_URL)
                    .indexName(TEST_INDEX)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    @Test
    @DisplayName("Should delete document successfully")
    void testDeleteSuccess() throws VectorStoreException {
        store = createMockStoreForDelete(true);
        StepVerifier.create(store.delete("doc-1"))
                .assertNext(Assertions::assertTrue)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return false when document not found")
    void testDeleteNotFound() throws VectorStoreException {
        store = createMockStoreForDelete(false);
        StepVerifier.create(store.delete("doc-1"))
                .assertNext(Assertions::assertFalse)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw exception for null ID")
    void testDeleteNullId() throws VectorStoreException {
        store = createMockStoreForDelete(true);
        StepVerifier.create(store.delete(null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }
}
