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
 * Unit tests for DifyKnowledge.
 */
class DifyKnowledgeTest {

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
        String responseBody =
                """
                {
                    "records": [
                        {
                            "segment": {
                                "id": "seg-1",
                                "content": "RAG stands for Retrieval-Augmented Generation.",
                                "document_id": "doc-1",
                                "document": {
                                    "id": "doc-1",
                                    "name": "rag-intro.pdf"
                                }
                            },
                            "score": 0.95
                        },
                        {
                            "segment": {
                                "id": "seg-2",
                                "content": "It combines retrieval and generation capabilities.",
                                "document_id": "doc-2",
                                "document": {
                                    "id": "doc-2",
                                    "name": "llm-guide.pdf"
                                }
                            },
                            "score": 0.88
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

    @Test
    void testBuildWithConfig() {
        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("test-api-key")
                        .apiBaseUrl(mockWebServer.url("/v1").toString())
                        .datasetId("dataset-123")
                        .build();

        DifyKnowledge knowledge = DifyKnowledge.builder().config(config).build();

        assertNotNull(knowledge);
    }

    @Test
    void testBuildWithNullConfigShouldThrow() {
        assertThrows(
                IllegalArgumentException.class, () -> DifyKnowledge.builder().config(null).build());
    }

    @Test
    void testRetrieveDocuments() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("test-api-key")
                        .apiBaseUrl(mockWebServer.url("/v1").toString())
                        .datasetId("dataset-123")
                        .build();

        DifyKnowledge knowledge = DifyKnowledge.builder().config(config).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();

        List<Document> documents = knowledge.retrieve("What is RAG?", retrieveConfig).block();

        assertNotNull(documents);
        assertEquals(2, documents.size());

        Document firstDoc = documents.get(0);
        assertNotNull(firstDoc.getMetadata());
        assertTrue(firstDoc.getMetadata().getContentText().contains("Retrieval-Augmented"));
        assertEquals(0.95, firstDoc.getScore(), 0.001);
    }

    @Test
    void testRetrieveWithEmptyQuery() throws Exception {
        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("test-api-key")
                        .apiBaseUrl(mockWebServer.url("/v1").toString())
                        .datasetId("dataset-123")
                        .build();

        DifyKnowledge knowledge = DifyKnowledge.builder().config(config).build();

        List<Document> documents =
                knowledge
                        .retrieve("", RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build())
                        .block();

        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }

    @Test
    void testRetrieveWithNullQueryShouldThrow() throws Exception {
        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("test-api-key")
                        .apiBaseUrl(mockWebServer.url("/v1").toString())
                        .datasetId("dataset-123")
                        .build();

        DifyKnowledge knowledge = DifyKnowledge.builder().config(config).build();

        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    knowledge
                            .retrieve(
                                    null,
                                    RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build())
                            .block();
                });
    }

    @Test
    void testRetrieveWithEmptyApiResponse() throws Exception {
        mockWebServer.enqueue(createEmptyResponse());

        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("test-api-key")
                        .apiBaseUrl(mockWebServer.url("/v1").toString())
                        .datasetId("dataset-123")
                        .build();

        DifyKnowledge knowledge = DifyKnowledge.builder().config(config).build();

        List<Document> documents =
                knowledge
                        .retrieve(
                                "test query",
                                RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build())
                        .block();

        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }

    @Test
    void testRetrieveWithNullConfigShouldThrow() throws Exception {
        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("test-api-key")
                        .apiBaseUrl(mockWebServer.url("/v1").toString())
                        .datasetId("dataset-123")
                        .topK(10)
                        .build();

        DifyKnowledge knowledge = DifyKnowledge.builder().config(config).build();

        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    knowledge.retrieve("test query", null).block();
                });
    }

    @Test
    void testAddDocumentsShouldThrowUnsupportedOperation() {
        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("test-api-key")
                        .apiBaseUrl(mockWebServer.url("/v1").toString())
                        .datasetId("dataset-123")
                        .build();

        DifyKnowledge knowledge = DifyKnowledge.builder().config(config).build();

        assertThrows(
                UnsupportedOperationException.class,
                () -> knowledge.addDocuments(List.of()).block());
    }
}
