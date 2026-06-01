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

import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.io.IOException;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for RAGFlowKnowledge.
 */
class RAGFlowKnowledgeTest {

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
                        "total": 2
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

    private RAGFlowConfig createConfig() {
        return RAGFlowConfig.builder()
                .apiKey("test-api-key")
                .baseUrl(mockWebServer.url("").toString().replaceAll("/$", ""))
                .addDatasetId("dataset-123")
                .maxRetries(0)
                .build();
    }

    // === Builder Tests ===

    @Test
    void testBuildWithConfig() {
        RAGFlowConfig config = createConfig();
        RAGFlowKnowledge knowledge = RAGFlowKnowledge.builder().config(config).build();

        assertNotNull(knowledge);
    }

    @Test
    void testBuildWithNullConfigShouldThrow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RAGFlowKnowledge.builder().config(null).build());
    }

    @Test
    void testBuildWithoutConfigShouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> RAGFlowKnowledge.builder().build());
    }

    // === Retrieve Tests ===

    @Test
    void testRetrieveSuccess() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        RAGFlowKnowledge knowledge = RAGFlowKnowledge.builder().config(createConfig()).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(0.5).build();

        List<Document> documents = knowledge.retrieve("What is RAGFlow?", retrieveConfig).block();

        assertNotNull(documents);
        assertEquals(2, documents.size());
    }

    @Test
    void testRetrieveWithEmptyQuery() {
        RAGFlowKnowledge knowledge = RAGFlowKnowledge.builder().config(createConfig()).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(0.5).build();

        List<Document> documents = knowledge.retrieve("", retrieveConfig).block();

        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }

    @Test
    void testRetrieveWithNullQuery() {
        RAGFlowKnowledge knowledge = RAGFlowKnowledge.builder().config(createConfig()).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(0.5).build();

        List<Document> documents = knowledge.retrieve(null, retrieveConfig).block();

        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }

    @Test
    void testRetrieveWithWhitespaceQuery() {
        RAGFlowKnowledge knowledge = RAGFlowKnowledge.builder().config(createConfig()).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(0.5).build();

        List<Document> documents = knowledge.retrieve("   ", retrieveConfig).block();

        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }

    @Test
    void testRetrieveWithEmptyApiResponse() throws Exception {
        mockWebServer.enqueue(createEmptyResponse());

        RAGFlowKnowledge knowledge = RAGFlowKnowledge.builder().config(createConfig()).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(0.5).build();

        List<Document> documents = knowledge.retrieve("unknown query", retrieveConfig).block();

        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }

    @Test
    void testRetrieveWithNullConfig() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        RAGFlowKnowledge knowledge = RAGFlowKnowledge.builder().config(createConfig()).build();

        List<Document> documents = knowledge.retrieve("test query", null).block();

        assertNotNull(documents);
        assertEquals(2, documents.size());
    }

    @Test
    void testRetrieveWithNullDataResponse() throws Exception {
        String body = "{\"code\": 0, \"data\": null}";
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(body));

        RAGFlowKnowledge knowledge = RAGFlowKnowledge.builder().config(createConfig()).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(0.5).build();

        List<Document> documents = knowledge.retrieve("test query", retrieveConfig).block();

        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }

    @Test
    void testRetrieveWithNullChunksResponse() throws Exception {
        String body = "{\"code\": 0, \"data\": {\"chunks\": null, \"total\": 0}}";
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(body));

        RAGFlowKnowledge knowledge = RAGFlowKnowledge.builder().config(createConfig()).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(0.5).build();

        List<Document> documents = knowledge.retrieve("test query", retrieveConfig).block();

        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }

    // === AddDocuments Tests ===

    @Test
    void testAddDocumentsShouldThrowUnsupportedOperation() {
        RAGFlowKnowledge knowledge = RAGFlowKnowledge.builder().config(createConfig()).build();

        UnsupportedOperationException exception =
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> knowledge.addDocuments(List.of()).block());

        assertTrue(exception.getMessage().contains("not supported"));
        assertTrue(exception.getMessage().contains("RAGFlow console"));
    }

    // === Error Handling Tests ===

    @Test
    void testRetrieveApiError() {
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(500)
                        .setBody("{\"message\": \"Internal server error\"}"));

        RAGFlowKnowledge knowledge = RAGFlowKnowledge.builder().config(createConfig()).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(0.5).build();

        assertThrows(
                Exception.class, () -> knowledge.retrieve("test query", retrieveConfig).block());
    }

    @Test
    void testRetrieveAuthError() {
        mockWebServer.enqueue(
                new MockResponse().setResponseCode(401).setBody("{\"message\": \"Unauthorized\"}"));

        RAGFlowKnowledge knowledge = RAGFlowKnowledge.builder().config(createConfig()).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(0.5).build();

        assertThrows(
                Exception.class, () -> knowledge.retrieve("test query", retrieveConfig).block());
    }
}
