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
package io.agentscope.core.rag.integration.dify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.rag.integration.dify.exception.DifyApiException;
import io.agentscope.core.rag.integration.dify.exception.DifyAuthException;
import io.agentscope.core.rag.integration.dify.model.DifyResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DifyRAGClient.
 */
class DifyRAGClientTest {

    private MockWebServer mockWebServer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    private MockResponse createSuccessResponse() {
        String responseBody =
                """
                {
                    "records": [
                        {
                            "segment": {
                                "id": "seg-1",
                                "content": "Test content",
                                "document_id": "doc-1"
                            },
                            "score": 0.95
                        }
                    ]
                }
                """;
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody);
    }

    private MockResponse createEmptyResponse() {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"records\": []}");
    }

    private DifyRAGConfig createConfig() {
        return DifyRAGConfig.builder()
                .apiKey("test-api-key")
                .apiBaseUrl(mockWebServer.url("/v1").toString())
                .datasetId("dataset-123")
                .maxRetries(0)
                .build();
    }

    // === HTTP Request Format Tests ===

    @Test
    void testSendPostToCorrectEndpoint() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        DifyRAGClient client = new DifyRAGClient(createConfig());
        client.retrieve("test query", 5).block();

        RecordedRequest request = mockWebServer.takeRequest();

        assertEquals("POST", request.getMethod());
        assertTrue(request.getPath().contains("/datasets/dataset-123/retrieve"));
    }

    @Test
    void testIncludeAuthorizationHeader() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("my-secret-api-key")
                        .apiBaseUrl(mockWebServer.url("/v1").toString())
                        .datasetId("dataset-123")
                        .maxRetries(0)
                        .build();

        DifyRAGClient client = new DifyRAGClient(config);
        client.retrieve("test query", 5).block();

        RecordedRequest request = mockWebServer.takeRequest();

        assertEquals("Bearer my-secret-api-key", request.getHeader("Authorization"));
    }

    @Test
    void testIncludeContentTypeHeader() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        DifyRAGClient client = new DifyRAGClient(createConfig());
        client.retrieve("test query", 5).block();

        RecordedRequest request = mockWebServer.takeRequest();

        assertTrue(request.getHeader("Content-Type").contains("application/json"));
    }

    @Test
    void testIncludeCustomHeaders() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("test-api-key")
                        .apiBaseUrl(mockWebServer.url("/v1").toString())
                        .datasetId("dataset-123")
                        .addCustomHeader("X-Custom-Header", "custom-value")
                        .addCustomHeader("X-Request-ID", "req-123")
                        .maxRetries(0)
                        .build();

        DifyRAGClient client = new DifyRAGClient(config);
        client.retrieve("test query", 5).block();

        RecordedRequest request = mockWebServer.takeRequest();

        assertEquals("custom-value", request.getHeader("X-Custom-Header"));
        assertEquals("req-123", request.getHeader("X-Request-ID"));
    }

    // === Request Body Tests ===

    @Test
    void testIncludeQueryParameter() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        DifyRAGClient client = new DifyRAGClient(createConfig());
        client.retrieve("What is RAG?", 5).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});

        assertEquals("What is RAG?", parsed.get("query"));
    }

    @Test
    void testIncludeRetrievalModel() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("test-api-key")
                        .apiBaseUrl(mockWebServer.url("/v1").toString())
                        .datasetId("dataset-123")
                        .retrievalMode(RetrievalMode.HYBRID_SEARCH)
                        .topK(10)
                        .scoreThreshold(0.5)
                        .maxRetries(0)
                        .build();

        DifyRAGClient client = new DifyRAGClient(config);
        client.retrieve("test query", 10).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});

        @SuppressWarnings("unchecked")
        Map<String, Object> retrievalModel = (Map<String, Object>) parsed.get("retrieval_model");

        assertNotNull(retrievalModel);
        assertEquals("hybrid_search", retrievalModel.get("search_method"));
        assertEquals(10, retrievalModel.get("top_k"));
        assertEquals(0.5, retrievalModel.get("score_threshold"));
        assertEquals(true, retrievalModel.get("score_threshold_enabled"));
    }

    @Test
    void testIncludeRerankingConfig() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("test-api-key")
                        .apiBaseUrl(mockWebServer.url("/v1").toString())
                        .datasetId("dataset-123")
                        .enableRerank(true)
                        .rerankConfig(
                                RerankConfig.builder()
                                        .providerName("cohere")
                                        .modelName("rerank-english-v2.0")
                                        .build())
                        .maxRetries(0)
                        .build();

        DifyRAGClient client = new DifyRAGClient(config);
        client.retrieve("test query", 5).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});

        @SuppressWarnings("unchecked")
        Map<String, Object> retrievalModel = (Map<String, Object>) parsed.get("retrieval_model");

        assertEquals(true, retrievalModel.get("reranking_enable"));

        @SuppressWarnings("unchecked")
        Map<String, Object> rerankingMode =
                (Map<String, Object>) retrievalModel.get("reranking_mode");
        assertNotNull(rerankingMode);
        assertEquals("cohere", rerankingMode.get("reranking_provider_name"));
        assertEquals("rerank-english-v2.0", rerankingMode.get("reranking_model_name"));
    }

    @Test
    void testIncludeMetadataFilter() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("test-api-key")
                        .apiBaseUrl(mockWebServer.url("/v1").toString())
                        .datasetId("dataset-123")
                        .metadataFilter(
                                MetadataFilter.builder()
                                        .logicalOperator("and")
                                        .addCondition(
                                                MetadataFilterCondition.builder()
                                                        .name("author")
                                                        .comparisonOperator("equals")
                                                        .value("John")
                                                        .build())
                                        .addCondition(
                                                MetadataFilterCondition.builder()
                                                        .name("year")
                                                        .comparisonOperator("equals")
                                                        .value("2023")
                                                        .build())
                                        .build())
                        .maxRetries(0)
                        .build();

        DifyRAGClient client = new DifyRAGClient(config);
        client.retrieve("test query", 5).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});

        @SuppressWarnings("unchecked")
        Map<String, Object> retrievalModel = (Map<String, Object>) parsed.get("retrieval_model");

        @SuppressWarnings("unchecked")
        Map<String, Object> metadataFilter =
                (Map<String, Object>) retrievalModel.get("metadata_filtering_conditions");

        assertNotNull(metadataFilter);
        assertEquals("and", metadataFilter.get("logical_operator"));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> conditions =
                (List<Map<String, String>>) metadataFilter.get("conditions");
        assertEquals(2, conditions.size());
    }

    // === Retrieval Mode Tests ===

    @Test
    void testKeywordSearchMode() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("test-api-key")
                        .apiBaseUrl(mockWebServer.url("/v1").toString())
                        .datasetId("dataset-123")
                        .retrievalMode(RetrievalMode.KEYWORD_SEARCH)
                        .maxRetries(0)
                        .build();

        DifyRAGClient client = new DifyRAGClient(config);
        client.retrieve("test query", 10).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});

        @SuppressWarnings("unchecked")
        Map<String, Object> retrievalModel = (Map<String, Object>) parsed.get("retrieval_model");

        assertEquals("keyword_search", retrievalModel.get("search_method"));
    }

    @Test
    void testSemanticSearchMode() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("test-api-key")
                        .apiBaseUrl(mockWebServer.url("/v1").toString())
                        .datasetId("dataset-123")
                        .retrievalMode(RetrievalMode.SEMANTIC_SEARCH)
                        .maxRetries(0)
                        .build();

        DifyRAGClient client = new DifyRAGClient(config);
        client.retrieve("test query", 10).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});

        @SuppressWarnings("unchecked")
        Map<String, Object> retrievalModel = (Map<String, Object>) parsed.get("retrieval_model");

        assertEquals("semantic_search", retrievalModel.get("search_method"));
    }

    @Test
    void testFulltextSearchMode() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("test-api-key")
                        .apiBaseUrl(mockWebServer.url("/v1").toString())
                        .datasetId("dataset-123")
                        .retrievalMode(RetrievalMode.FULL_TEXT_SEARCH)
                        .maxRetries(0)
                        .build();

        DifyRAGClient client = new DifyRAGClient(config);
        client.retrieve("test query", 10).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});

        @SuppressWarnings("unchecked")
        Map<String, Object> retrievalModel = (Map<String, Object>) parsed.get("retrieval_model");

        assertEquals("full_text_search", retrievalModel.get("search_method"));
    }

    // === Response Handling Tests ===

    @Test
    void testParseSuccessfulResponse() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        DifyRAGClient client = new DifyRAGClient(createConfig());
        DifyResponse response = client.retrieve("test query", 5).block();

        assertNotNull(response);
        assertNotNull(response.getRecords());
        assertEquals(1, response.getRecords().size());
        assertEquals("seg-1", response.getRecords().get(0).getSegment().getId());
        assertEquals(0.95, response.getRecords().get(0).getScore(), 0.001);
    }

    @Test
    void testHandleEmptyResponse() throws Exception {
        mockWebServer.enqueue(createEmptyResponse());

        DifyRAGClient client = new DifyRAGClient(createConfig());
        DifyResponse response = client.retrieve("test query", 5).block();

        assertNotNull(response);
        assertNotNull(response.getRecords());
        assertTrue(response.getRecords().isEmpty());
    }

    // === Error Handling Tests ===

    @Test
    void testNullQueryShouldThrow() {
        DifyRAGClient client = new DifyRAGClient(createConfig());

        assertThrows(IllegalArgumentException.class, () -> client.retrieve(null, 5).block());
    }

    @Test
    void testEmptyQueryShouldThrow() {
        DifyRAGClient client = new DifyRAGClient(createConfig());

        assertThrows(IllegalArgumentException.class, () -> client.retrieve("", 5).block());

        assertThrows(IllegalArgumentException.class, () -> client.retrieve("   ", 5).block());
    }

    @Test
    void testHttp401Unauthorized() {
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(401)
                        .setBody("{\"message\": \"Invalid API key\"}"));

        DifyRAGClient client = new DifyRAGClient(createConfig());

        assertThrows(DifyAuthException.class, () -> client.retrieve("test query", 5).block());
    }

    @Test
    void testHttp403Forbidden() {
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(403)
                        .setBody("{\"message\": \"Access denied\"}"));

        DifyRAGClient client = new DifyRAGClient(createConfig());

        assertThrows(DifyAuthException.class, () -> client.retrieve("test query", 5).block());
    }

    @Test
    void testHttp404NotFound() {
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(404)
                        .setBody("{\"message\": \"Dataset not found\"}"));

        DifyRAGClient client = new DifyRAGClient(createConfig());

        DifyApiException exception =
                assertThrows(
                        DifyApiException.class, () -> client.retrieve("test query", 5).block());

        assertTrue(exception.getMessage().contains("Dataset not found"));
    }

    @Test
    void testHttp500ServerError() {
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(500)
                        .setBody("{\"message\": \"Internal server error\"}"));

        DifyRAGClient client = new DifyRAGClient(createConfig());

        DifyApiException exception =
                assertThrows(
                        DifyApiException.class, () -> client.retrieve("test query", 5).block());

        assertTrue(exception.getMessage().contains("Internal server error"));
    }

    // === Configuration Tests ===

    @Test
    void testNullConfigShouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> new DifyRAGClient(null));
    }

    @Test
    void testGetConfig() {
        DifyRAGConfig config = createConfig();
        DifyRAGClient client = new DifyRAGClient(config);

        assertEquals(config, client.getConfig());
    }

    @Test
    void testLimitOverridesConfigTopK() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("test-api-key")
                        .apiBaseUrl(mockWebServer.url("/v1").toString())
                        .datasetId("dataset-123")
                        .topK(10) // config topK
                        .maxRetries(0)
                        .build();

        DifyRAGClient client = new DifyRAGClient(config);
        client.retrieve("test query", 50).block(); // method limit should override

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});

        @SuppressWarnings("unchecked")
        Map<String, Object> retrievalModel = (Map<String, Object>) parsed.get("retrieval_model");

        assertEquals(50, retrievalModel.get("top_k"));
    }

    @Test
    void testScoreThresholdDisabledWhenZero() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("test-api-key")
                        .apiBaseUrl(mockWebServer.url("/v1").toString())
                        .datasetId("dataset-123")
                        .scoreThreshold(0.0)
                        .maxRetries(0)
                        .build();

        DifyRAGClient client = new DifyRAGClient(config);
        client.retrieve("test query", 5).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});

        @SuppressWarnings("unchecked")
        Map<String, Object> retrievalModel = (Map<String, Object>) parsed.get("retrieval_model");

        assertEquals(false, retrievalModel.get("score_threshold_enabled"));
    }

    @Test
    void testRerankingDisabledByDefault() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        DifyRAGClient client = new DifyRAGClient(createConfig());
        client.retrieve("test query", 5).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});

        @SuppressWarnings("unchecked")
        Map<String, Object> retrievalModel = (Map<String, Object>) parsed.get("retrieval_model");

        assertEquals(false, retrievalModel.get("reranking_enable"));
    }
}
