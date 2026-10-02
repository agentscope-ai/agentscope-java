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
package io.agentscope.core.rag.integration.ragflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.rag.integration.ragflow.exception.RAGFlowApiException;
import io.agentscope.core.rag.integration.ragflow.exception.RAGFlowAuthException;
import io.agentscope.core.rag.integration.ragflow.model.RAGFlowResponse;
import io.agentscope.core.util.JsonUtils;
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
 * Unit tests for RAGFlowClient.
 */
class RAGFlowClientTest {

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    private MockResponse createSuccessResponse() {
        String body =
                """
                {
                    "code": 0,
                    "data": {
                        "chunks": [
                            {
                                "id": "chunk-1",
                                "content": "RAGFlow is an open-source RAG engine.",
                                "document_id": "doc-1",
                                "similarity": 0.95
                            },
                            {
                                "id": "chunk-2",
                                "content": "It supports multiple document formats.",
                                "document_id": "doc-2",
                                "similarity": 0.88
                            }
                        ],
                        "total": 2,
                        "doc_aggs": [
                            {"doc_id": "doc-1", "doc_name": "intro.pdf", "count": 1}
                        ]
                    }
                }
                """;
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private MockResponse createEmptyResponse() {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\": 0, \"data\": {\"chunks\": [], \"total\": 0}}");
    }

    private MockResponse createErrorResponse(int code, String message) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                        String.format(
                                "{\"code\": %d, \"message\": \"%s\", \"data\": false}",
                                code, message));
    }

    private RAGFlowConfig createConfig() {
        return RAGFlowConfig.builder()
                .apiKey("test-api-key")
                .baseUrl(mockWebServer.url("").toString().replaceAll("/$", ""))
                .addDatasetId("dataset-123")
                .maxRetries(0) // Disable retries for testing
                .build();
    }

    @Test
    void testRetrieveSuccess() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        RAGFlowClient client = new RAGFlowClient(createConfig());
        RAGFlowResponse response = client.retrieve("What is RAGFlow?", null, null, null).block();

        assertNotNull(response);
        assertEquals(0, response.getCode());
        assertNotNull(response.getData());
        assertNotNull(response.getData().getChunks());
        assertEquals(2, response.getData().getChunks().size());
        assertEquals(2, response.getData().getTotal());
    }

    @Test
    void testRetrieveWithEmptyResult() throws Exception {
        mockWebServer.enqueue(createEmptyResponse());

        RAGFlowClient client = new RAGFlowClient(createConfig());
        RAGFlowResponse response = client.retrieve("unknown query", null, null, null).block();

        assertNotNull(response);
        assertEquals(0, response.getCode());
        assertNotNull(response.getData());
        assertTrue(response.getData().getChunks().isEmpty());
    }

    @Test
    void testRetrieveWithNullQueryShouldThrow() {
        RAGFlowClient client = new RAGFlowClient(createConfig());

        assertThrows(
                IllegalArgumentException.class,
                () -> client.retrieve(null, null, null, null).block());
    }

    @Test
    void testRetrieveWithEmptyQueryShouldThrow() {
        RAGFlowClient client = new RAGFlowClient(createConfig());

        assertThrows(
                IllegalArgumentException.class,
                () -> client.retrieve("", null, null, null).block());

        assertThrows(
                IllegalArgumentException.class,
                () -> client.retrieve("   ", null, null, null).block());
    }

    @Test
    void testRequestContainsRequiredFields() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        RAGFlowConfig config =
                RAGFlowConfig.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockWebServer.url("").toString().replaceAll("/$", ""))
                        .addDatasetId("dataset-1")
                        .addDatasetId("dataset-2")
                        .maxRetries(0)
                        .build();

        RAGFlowClient client = new RAGFlowClient(config);
        client.retrieve("test query", null, null, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                JsonUtils.getJsonCodec()
                        .fromJson(body, new TypeReference<Map<String, Object>>() {});

        assertEquals("test query", parsed.get("question"));

        @SuppressWarnings("unchecked")
        List<String> datasetIds = (List<String>) parsed.get("dataset_ids");
        assertEquals(2, datasetIds.size());
        assertTrue(datasetIds.contains("dataset-1"));
        assertTrue(datasetIds.contains("dataset-2"));
    }

    @Test
    void testRequestContainsOptionalParameters() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        RAGFlowConfig config =
                RAGFlowConfig.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockWebServer.url("").toString().replaceAll("/$", ""))
                        .addDatasetId("dataset-123")
                        .addDocumentId("doc-1")
                        .topK(50)
                        .similarityThreshold(0.5)
                        .vectorSimilarityWeight(0.7)
                        .page(2)
                        .pageSize(20)
                        .useKg(true)
                        .keyword(true)
                        .highlight(true)
                        .maxRetries(0)
                        .build();

        RAGFlowClient client = new RAGFlowClient(config);
        client.retrieve("test query", null, null, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                JsonUtils.getJsonCodec()
                        .fromJson(body, new TypeReference<Map<String, Object>>() {});

        assertEquals(50, parsed.get("top_k"));
        assertEquals(0.5, parsed.get("similarity_threshold"));
        assertEquals(0.7, parsed.get("vector_similarity_weight"));
        assertEquals(2, parsed.get("page"));
        assertEquals(20, parsed.get("page_size"));
        assertEquals(true, parsed.get("use_kg"));
        assertEquals(true, parsed.get("keyword"));
        assertEquals(true, parsed.get("highlight"));

        @SuppressWarnings("unchecked")
        List<String> documentIds = (List<String>) parsed.get("document_ids");
        assertEquals(1, documentIds.size());
        assertTrue(documentIds.contains("doc-1"));
    }

    @Test
    void testRequestOverridesConfigWithMethodParameters() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        RAGFlowConfig config =
                RAGFlowConfig.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockWebServer.url("").toString().replaceAll("/$", ""))
                        .addDatasetId("dataset-123")
                        .topK(10)
                        .similarityThreshold(0.3)
                        .maxRetries(0)
                        .build();

        RAGFlowClient client = new RAGFlowClient(config);
        client.retrieve("test query", 100, 0.8, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                JsonUtils.getJsonCodec()
                        .fromJson(body, new TypeReference<Map<String, Object>>() {});

        // Method parameters should override config
        assertEquals(100, parsed.get("top_k"));
        assertEquals(0.8, parsed.get("similarity_threshold"));
    }

    @Test
    void testRequestWithMetadataCondition() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        Map<String, Object> metadataCondition =
                Map.of(
                        "logic",
                        "and",
                        "conditions",
                        List.of(
                                Map.of(
                                        "name",
                                        "author",
                                        "comparison_operator",
                                        "=",
                                        "value",
                                        "Toby")));

        RAGFlowClient client = new RAGFlowClient(createConfig());
        client.retrieve("test query", null, null, metadataCondition).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                JsonUtils.getJsonCodec()
                        .fromJson(body, new TypeReference<Map<String, Object>>() {});

        @SuppressWarnings("unchecked")
        Map<String, Object> condition = (Map<String, Object>) parsed.get("metadata_condition");
        assertNotNull(condition);
        assertEquals("and", condition.get("logic"));
    }

    @Test
    void testRequestContainsAuthorizationHeader() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        RAGFlowConfig config =
                RAGFlowConfig.builder()
                        .apiKey("my-secret-key")
                        .baseUrl(mockWebServer.url("").toString().replaceAll("/$", ""))
                        .addDatasetId("dataset-123")
                        .maxRetries(0)
                        .build();

        RAGFlowClient client = new RAGFlowClient(config);
        client.retrieve("test query", null, null, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("Bearer my-secret-key", request.getHeader("Authorization"));
        assertTrue(request.getHeader("Content-Type").contains("application/json"));
    }

    @Test
    void testRequestContainsCustomHeaders() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        RAGFlowConfig config =
                RAGFlowConfig.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockWebServer.url("").toString().replaceAll("/$", ""))
                        .addDatasetId("dataset-123")
                        .addCustomHeader("X-Custom-Header", "custom-value")
                        .addCustomHeader("X-Request-ID", "req-123")
                        .maxRetries(0)
                        .build();

        RAGFlowClient client = new RAGFlowClient(config);
        client.retrieve("test query", null, null, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("custom-value", request.getHeader("X-Custom-Header"));
        assertEquals("req-123", request.getHeader("X-Request-ID"));
    }

    @Test
    void testApiErrorResponse() {
        mockWebServer.enqueue(createErrorResponse(404, "Dataset not found"));

        RAGFlowClient client = new RAGFlowClient(createConfig());

        RAGFlowApiException exception =
                assertThrows(
                        RAGFlowApiException.class,
                        () -> client.retrieve("test query", null, null, null).block());

        assertTrue(exception.getMessage().contains("Dataset not found"));
    }

    @Test
    void testHttp401Unauthorized() {
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(401)
                        .setBody("{\"message\": \"Invalid API key\"}"));

        RAGFlowClient client = new RAGFlowClient(createConfig());

        assertThrows(
                RAGFlowAuthException.class,
                () -> client.retrieve("test query", null, null, null).block());
    }

    @Test
    void testHttp403Forbidden() {
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(403)
                        .setBody("{\"message\": \"Access denied\"}"));

        RAGFlowClient client = new RAGFlowClient(createConfig());

        assertThrows(
                RAGFlowAuthException.class,
                () -> client.retrieve("test query", null, null, null).block());
    }

    @Test
    void testHttp404NotFound() {
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(404)
                        .setBody("{\"message\": \"Dataset not found\"}"));

        RAGFlowClient client = new RAGFlowClient(createConfig());

        RAGFlowApiException exception =
                assertThrows(
                        RAGFlowApiException.class,
                        () -> client.retrieve("test query", null, null, null).block());

        assertTrue(exception.getMessage().contains("Dataset not found"));
    }

    @Test
    void testHttp429RateLimited() {
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(429)
                        .setBody("{\"message\": \"Too many requests\"}"));

        RAGFlowClient client = new RAGFlowClient(createConfig());

        RAGFlowApiException exception =
                assertThrows(
                        RAGFlowApiException.class,
                        () -> client.retrieve("test query", null, null, null).block());

        assertTrue(exception.getMessage().contains("Rate limit"));
    }

    @Test
    void testHttp500ServerError() {
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(500)
                        .setBody("{\"message\": \"Internal server error\"}"));

        RAGFlowClient client = new RAGFlowClient(createConfig());

        RAGFlowApiException exception =
                assertThrows(
                        RAGFlowApiException.class,
                        () -> client.retrieve("test query", null, null, null).block());

        assertTrue(exception.getMessage().contains("server error"));
    }

    @Test
    void testRequestToCorrectEndpoint() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        RAGFlowClient client = new RAGFlowClient(createConfig());
        client.retrieve("test query", null, null, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("POST", request.getMethod());
        assertTrue(request.getPath().contains("/api/v1/retrieval"));
    }

    @Test
    void testCrossLanguagesParameter() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        RAGFlowConfig config =
                RAGFlowConfig.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockWebServer.url("").toString().replaceAll("/$", ""))
                        .addDatasetId("dataset-123")
                        .addCrossLanguage("en")
                        .addCrossLanguage("zh")
                        .maxRetries(0)
                        .build();

        RAGFlowClient client = new RAGFlowClient(config);
        client.retrieve("test query", null, null, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                JsonUtils.getJsonCodec()
                        .fromJson(body, new TypeReference<Map<String, Object>>() {});

        @SuppressWarnings("unchecked")
        List<String> languages = (List<String>) parsed.get("cross_languages");
        assertNotNull(languages);
        assertEquals(2, languages.size());
        assertTrue(languages.contains("en"));
        assertTrue(languages.contains("zh"));
    }

    @Test
    void testResponseWithDocAggregations() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        RAGFlowClient client = new RAGFlowClient(createConfig());
        RAGFlowResponse response = client.retrieve("test query", null, null, null).block();

        assertNotNull(response);
        assertNotNull(response.getData().getDocAggs());
        assertEquals(1, response.getData().getDocAggs().size());
        assertEquals("doc-1", response.getData().getDocAggs().get(0).getDocId());
        assertEquals("intro.pdf", response.getData().getDocAggs().get(0).getDocName());
    }

    // === Constructor Tests ===

    @Test
    void testConstructorWithCustomObjectMapper() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        RAGFlowClient client = new RAGFlowClient(createConfig());
        RAGFlowResponse response = client.retrieve("test query", null, null, null).block();

        assertNotNull(response);
    }

    // === Additional Config Parameter Tests ===

    @Test
    void testTocEnhanceParameter() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        RAGFlowConfig config =
                RAGFlowConfig.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockWebServer.url("").toString().replaceAll("/$", ""))
                        .addDatasetId("dataset-123")
                        .tocEnhance(true)
                        .maxRetries(0)
                        .build();

        RAGFlowClient client = new RAGFlowClient(config);
        client.retrieve("test query", null, null, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                JsonUtils.getJsonCodec()
                        .fromJson(body, new TypeReference<Map<String, Object>>() {});

        assertEquals(true, parsed.get("toc_enhance"));
    }

    @Test
    void testRerankIdParameter() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        RAGFlowConfig config =
                RAGFlowConfig.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockWebServer.url("").toString().replaceAll("/$", ""))
                        .addDatasetId("dataset-123")
                        .rerankId(42)
                        .maxRetries(0)
                        .build();

        RAGFlowClient client = new RAGFlowClient(config);
        client.retrieve("test query", null, null, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                JsonUtils.getJsonCodec()
                        .fromJson(body, new TypeReference<Map<String, Object>>() {});

        assertEquals(42, parsed.get("rerank_id"));
    }

    @Test
    void testMetadataConditionFromConfig() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        Map<String, Object> configMetadata =
                Map.of(
                        "logic",
                        "or",
                        "conditions",
                        List.of(
                                Map.of(
                                        "name",
                                        "category",
                                        "comparison_operator",
                                        "=",
                                        "value",
                                        "tech")));

        RAGFlowConfig config =
                RAGFlowConfig.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockWebServer.url("").toString().replaceAll("/$", ""))
                        .addDatasetId("dataset-123")
                        .metadataCondition(configMetadata)
                        .maxRetries(0)
                        .build();

        RAGFlowClient client = new RAGFlowClient(config);
        // Pass null for metadataCondition to use config value
        client.retrieve("test query", null, null, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                JsonUtils.getJsonCodec()
                        .fromJson(body, new TypeReference<Map<String, Object>>() {});

        @SuppressWarnings("unchecked")
        Map<String, Object> condition = (Map<String, Object>) parsed.get("metadata_condition");
        assertNotNull(condition);
        assertEquals("or", condition.get("logic"));
    }

    @Test
    void testMethodMetadataConditionOverridesConfig() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        Map<String, Object> configMetadata = Map.of("logic", "and", "conditions", List.of());

        Map<String, Object> methodMetadata =
                Map.of(
                        "logic",
                        "or",
                        "conditions",
                        List.of(
                                Map.of(
                                        "name",
                                        "type",
                                        "comparison_operator",
                                        "=",
                                        "value",
                                        "pdf")));

        RAGFlowConfig config =
                RAGFlowConfig.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockWebServer.url("").toString().replaceAll("/$", ""))
                        .addDatasetId("dataset-123")
                        .metadataCondition(configMetadata)
                        .maxRetries(0)
                        .build();

        RAGFlowClient client = new RAGFlowClient(config);
        // Method parameter should override config
        client.retrieve("test query", null, null, methodMetadata).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                JsonUtils.getJsonCodec()
                        .fromJson(body, new TypeReference<Map<String, Object>>() {});

        @SuppressWarnings("unchecked")
        Map<String, Object> condition = (Map<String, Object>) parsed.get("metadata_condition");
        assertNotNull(condition);
        assertEquals("or", condition.get("logic"));
    }

    @Test
    void testUsesConfigTopKWhenMethodParameterIsNull() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        RAGFlowConfig config =
                RAGFlowConfig.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockWebServer.url("").toString().replaceAll("/$", ""))
                        .addDatasetId("dataset-123")
                        .topK(25)
                        .maxRetries(0)
                        .build();

        RAGFlowClient client = new RAGFlowClient(config);
        client.retrieve("test query", null, null, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                JsonUtils.getJsonCodec()
                        .fromJson(body, new TypeReference<Map<String, Object>>() {});

        assertEquals(25, parsed.get("top_k"));
    }

    @Test
    void testUsesConfigSimilarityThresholdWhenMethodParameterIsNull() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        RAGFlowConfig config =
                RAGFlowConfig.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockWebServer.url("").toString().replaceAll("/$", ""))
                        .addDatasetId("dataset-123")
                        .similarityThreshold(0.75)
                        .maxRetries(0)
                        .build();

        RAGFlowClient client = new RAGFlowClient(config);
        client.retrieve("test query", null, null, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                JsonUtils.getJsonCodec()
                        .fromJson(body, new TypeReference<Map<String, Object>>() {});

        assertEquals(0.75, parsed.get("similarity_threshold"));
    }

    // === Additional Error Handling Tests ===

    @Test
    void testHttp400BadRequest() {
        mockWebServer.enqueue(
                new MockResponse().setResponseCode(400).setBody("{\"message\": \"Bad request\"}"));

        RAGFlowClient client = new RAGFlowClient(createConfig());

        RAGFlowApiException exception =
                assertThrows(
                        RAGFlowApiException.class,
                        () -> client.retrieve("test query", null, null, null).block());

        assertTrue(exception.getMessage().contains("Bad request"));
    }

    @Test
    void testHttp502BadGateway() {
        mockWebServer.enqueue(
                new MockResponse().setResponseCode(502).setBody("{\"message\": \"Bad gateway\"}"));

        RAGFlowClient client = new RAGFlowClient(createConfig());

        RAGFlowApiException exception =
                assertThrows(
                        RAGFlowApiException.class,
                        () -> client.retrieve("test query", null, null, null).block());

        assertTrue(exception.getMessage().contains("server error"));
    }

    @Test
    void testHttp503ServiceUnavailable() {
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(503)
                        .setBody("{\"message\": \"Service unavailable\"}"));

        RAGFlowClient client = new RAGFlowClient(createConfig());

        RAGFlowApiException exception =
                assertThrows(
                        RAGFlowApiException.class,
                        () -> client.retrieve("test query", null, null, null).block());

        assertTrue(exception.getMessage().contains("server error"));
    }

    @Test
    void testApiErrorWithNonZeroCode() {
        String errorResponse =
                """
                {
                    "code": 1001,
                    "message": "Invalid dataset ID format",
                    "data": null
                }
                """;
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(errorResponse));

        RAGFlowClient client = new RAGFlowClient(createConfig());

        RAGFlowApiException exception =
                assertThrows(
                        RAGFlowApiException.class,
                        () -> client.retrieve("test query", null, null, null).block());

        assertTrue(exception.getMessage().contains("Invalid dataset ID"));
    }

    @Test
    void testEmptyResponseBody() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody(""));

        RAGFlowClient client = new RAGFlowClient(createConfig());

        assertThrows(
                RAGFlowApiException.class,
                () -> client.retrieve("test query", null, null, null).block());
    }

    // === No Custom Headers Test ===

    @Test
    void testNoCustomHeaders() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        RAGFlowConfig config =
                RAGFlowConfig.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockWebServer.url("").toString().replaceAll("/$", ""))
                        .addDatasetId("dataset-123")
                        .maxRetries(0)
                        .build();

        RAGFlowClient client = new RAGFlowClient(config);
        client.retrieve("test query", null, null, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        // Standard headers should still be present
        assertEquals("Bearer test-api-key", request.getHeader("Authorization"));
        assertTrue(request.getHeader("Content-Type").contains("application/json"));
    }

    // === Empty Document IDs Test ===

    @Test
    void testEmptyDocumentIds() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        RAGFlowConfig config =
                RAGFlowConfig.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockWebServer.url("").toString().replaceAll("/$", ""))
                        .addDatasetId("dataset-123")
                        .maxRetries(0)
                        .build();

        RAGFlowClient client = new RAGFlowClient(config);
        client.retrieve("test query", null, null, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                JsonUtils.getJsonCodec()
                        .fromJson(body, new TypeReference<Map<String, Object>>() {});

        // document_ids should not be in request body when empty
        assertTrue(!parsed.containsKey("document_ids") || parsed.get("document_ids") == null);
    }

    // === All Parameters Combined Test ===

    @Test
    void testAllParametersCombined() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        Map<String, Object> metadata = Map.of("logic", "and", "conditions", List.of());

        RAGFlowConfig config =
                RAGFlowConfig.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockWebServer.url("").toString().replaceAll("/$", ""))
                        .addDatasetId("dataset-1")
                        .addDatasetId("dataset-2")
                        .addDocumentId("doc-1")
                        .addDocumentId("doc-2")
                        .topK(100)
                        .similarityThreshold(0.6)
                        .vectorSimilarityWeight(0.5)
                        .page(3)
                        .pageSize(50)
                        .useKg(true)
                        .tocEnhance(true)
                        .rerankId(10)
                        .keyword(true)
                        .highlight(true)
                        .addCrossLanguage("en")
                        .addCrossLanguage("zh")
                        .addCrossLanguage("ja")
                        .metadataCondition(metadata)
                        .addCustomHeader("X-Test", "test-value")
                        .maxRetries(0)
                        .build();

        RAGFlowClient client = new RAGFlowClient(config);
        client.retrieve("comprehensive test", null, null, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        Map<String, Object> parsed =
                JsonUtils.getJsonCodec()
                        .fromJson(body, new TypeReference<Map<String, Object>>() {});

        // Verify all parameters are present
        assertEquals("comprehensive test", parsed.get("question"));

        @SuppressWarnings("unchecked")
        List<String> datasetIds = (List<String>) parsed.get("dataset_ids");
        assertEquals(2, datasetIds.size());

        @SuppressWarnings("unchecked")
        List<String> documentIds = (List<String>) parsed.get("document_ids");
        assertEquals(2, documentIds.size());

        assertEquals(100, parsed.get("top_k"));
        assertEquals(0.6, parsed.get("similarity_threshold"));
        assertEquals(0.5, parsed.get("vector_similarity_weight"));
        assertEquals(3, parsed.get("page"));
        assertEquals(50, parsed.get("page_size"));
        assertEquals(true, parsed.get("use_kg"));
        assertEquals(true, parsed.get("toc_enhance"));
        assertEquals(10, parsed.get("rerank_id"));
        assertEquals(true, parsed.get("keyword"));
        assertEquals(true, parsed.get("highlight"));

        @SuppressWarnings("unchecked")
        List<String> languages = (List<String>) parsed.get("cross_languages");
        assertEquals(3, languages.size());

        assertNotNull(parsed.get("metadata_condition"));

        // Verify custom header
        assertEquals("test-value", request.getHeader("X-Test"));
    }

    // === Response Without DocAggs Test ===

    @Test
    void testResponseWithoutDocAggs() throws Exception {
        String bodyWithoutDocAggs =
                """
                {
                    "code": 0,
                    "data": {
                        "chunks": [
                            {
                                "id": "chunk-1",
                                "content": "Test content",
                                "document_id": "doc-1",
                                "similarity": 0.9
                            }
                        ],
                        "total": 1
                    }
                }
                """;
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(bodyWithoutDocAggs));

        RAGFlowClient client = new RAGFlowClient(createConfig());
        RAGFlowResponse response = client.retrieve("test query", null, null, null).block();

        assertNotNull(response);
        assertEquals(0, response.getCode());
        assertEquals(1, response.getData().getChunks().size());
    }

    // === Response With Null Data Test ===

    @Test
    void testResponseWithNullData() throws Exception {
        String bodyWithNullData = "{\"code\": 0, \"data\": null}";
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(bodyWithNullData));

        RAGFlowClient client = new RAGFlowClient(createConfig());
        RAGFlowResponse response = client.retrieve("test query", null, null, null).block();

        assertNotNull(response);
        assertEquals(0, response.getCode());
    }
}
