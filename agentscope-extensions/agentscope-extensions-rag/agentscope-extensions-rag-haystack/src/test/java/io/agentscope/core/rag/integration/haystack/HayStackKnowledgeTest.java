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
package io.agentscope.core.rag.integration.haystack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for HayStackKnowledge.
 *
 * <p>This test validates HayStackKnowledge behavior against the Knowledge contract,
 * including builder validation, retrieval logic, and unsupported operations.
 */
class HayStackKnowledgeTest {

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

    // ========= Mock Responses =========

    private MockResponse createSuccessResponse() {
        String body =
                """
                {
                  "code": 0,
                  "documents": [
                    {
                      "id": "doc-1",
                      "content": "HayStack is an open-source RAG framework.",
                      "score": 0.95,
                      "meta": {
                        "file_path": "intro.md"
                      }
                    },
                    {
                      "id": "doc-2",
                      "content": "It supports modular pipelines.",
                      "score": 0.88,
                      "meta": {
                        "file_path": "pipeline.md"
                      }
                    }
                  ]
                }
                """;

        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private MockResponse createSuccessWindowRetrieverResponse() {
        String body =
                """
                {
                  "context_windows": [
                    "Test Content "
                  ],
                  "context_documents": [
                    {
                      "id": "9e4cd66e1437776c6b9",
                      "content": "Test Content",
                      "meta": {
                        "source": "test.txt",
                        "source_id": "1528904be8e8f94f85b7",
                        "page_number": 1,
                        "split_id": 21,
                        "split_idx_start": 11199,
                        "_split_overlap": [
                          {
                            "doc_id": "55ee242643b2439c849df040",
                            "range": [433, 570]
                          },
                          {
                            "doc_id": "cdc785a04f358d15631fa91c2",
                            "range": [0, 146]
                          }
                        ]
                      }
                    }
                  ]
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
                .setBody("{\"code\": 0, \"documents\": []}");
    }

    private MockResponse createNullDocumentsResponse() {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\": 0, \"documents\": null}");
    }

    // ========= Config Factory =========

    private HayStackConfig createConfig() {
        return HayStackConfig.builder()
                .baseUrl(mockWebServer.url("/retrieve").toString())
                .topK(5)
                .maxRetries(0)
                .build();
    }

    // ========= Builder Tests =========

    @Test
    void testBuildWithConfig() {
        HayStackKnowledge knowledge = HayStackKnowledge.builder().config(createConfig()).build();

        assertNotNull(knowledge);
    }

    @Test
    void testBuildWithNullConfigShouldThrow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> HayStackKnowledge.builder().config(null).build());
    }

    @Test
    void testBuildWithoutConfigShouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> HayStackKnowledge.builder().build());
    }

    // ========= Retrieve Tests =========

    @ParameterizedTest
    @ValueSource(strings = {"documents", "context_documents"})
    void retrievalLimitIsAppliedAfterConversionAndScoreFiltering(String field) {
        mockWebServer.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                """
                                {"%s":[
                                  {"id":"below-threshold","content":"low","score":0.1},
                                  {"id":"blank","content":" ","score":1.0},
                                  {"id":"first","content":"first result","score":0.9,"meta":{"file_path":"first.txt"}},
                                  {"id":"second","content":"second result","score":0.8}
                                ]}
                                """
                                        .formatted(field)));
        HayStackKnowledge knowledge = HayStackKnowledge.builder().config(createConfig()).build();
        List<Document> documents =
                knowledge
                        .retrieve(
                                "query",
                                RetrieveConfig.builder().limit(1).scoreThreshold(0.5).build())
                        .block(Duration.ofSeconds(10));

        assertEquals(1, documents.size());
        assertEquals("first", documents.get(0).getMetadata().getChunkId());
        assertEquals("first.txt", documents.get(0).getMetadata().getDocId());
        assertEquals("first result", documents.get(0).getMetadata().getContentText());
        assertEquals(0.9, documents.get(0).getScore());
    }

    @ParameterizedTest
    @ValueSource(strings = {"documents", "context_documents"})
    void unscoredWindowResultsKeepOrderButRespectLimit(String field) {
        mockWebServer.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                """
                                {"%s":[
                                  {"id":"window-1","content":"first window"},
                                  {"id":"window-2","content":"second window"},
                                  {"id":"window-3","content":"third window"}
                                ]}
                                """
                                        .formatted(field)));
        HayStackKnowledge knowledge = HayStackKnowledge.builder().config(createConfig()).build();
        List<Document> documents =
                knowledge
                        .retrieve(
                                "query",
                                RetrieveConfig.builder().limit(2).scoreThreshold(1.0).build())
                        .block(Duration.ofSeconds(10));

        assertEquals(
                List.of("window-1", "window-2"),
                documents.stream().map(doc -> doc.getMetadata().getChunkId()).toList());
        assertTrue(documents.stream().allMatch(doc -> doc.getScore() == null));
    }

    @Test
    void testRetrieveSuccess() {
        mockWebServer.enqueue(createSuccessResponse());

        HayStackKnowledge knowledge = HayStackKnowledge.builder().config(createConfig()).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(0.5).build();

        List<Document> documents = knowledge.retrieve("What is HayStack?", retrieveConfig).block();

        assertNotNull(documents);
        assertEquals(2, documents.size());
    }

    @Test
    void testRetrieveFiltersByScoreThreshold() {
        mockWebServer.enqueue(createSuccessResponse());

        HayStackKnowledge knowledge = HayStackKnowledge.builder().config(createConfig()).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(0.9).build();

        List<Document> documents = knowledge.retrieve("What is HayStack?", retrieveConfig).block();

        assertNotNull(documents);
        assertEquals(1, documents.size());
        assertTrue(documents.get(0).getScore() >= 0.9);
    }

    @Test
    void testRetrieveFiltersByScoreForWindowRetrieverThreshold() {
        mockWebServer.enqueue(createSuccessWindowRetrieverResponse());

        HayStackKnowledge knowledge = HayStackKnowledge.builder().config(createConfig()).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(1.0).build();

        List<Document> documents = knowledge.retrieve("What is HayStack?", retrieveConfig).block();

        assertNotNull(documents);
        assertEquals(1, documents.size());
        assertNull(documents.get(0).getScore());
    }

    @Test
    void testRetrieveWithEmptyApiResponse() {
        mockWebServer.enqueue(createEmptyResponse());

        HayStackKnowledge knowledge = HayStackKnowledge.builder().config(createConfig()).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(0.5).build();

        List<Document> documents = knowledge.retrieve("unknown query", retrieveConfig).block();

        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }

    @Test
    void testRetrieveWithNullDocumentsResponse() {
        mockWebServer.enqueue(createNullDocumentsResponse());

        HayStackKnowledge knowledge = HayStackKnowledge.builder().config(createConfig()).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(0.5).build();

        List<Document> documents = knowledge.retrieve("test query", retrieveConfig).block();

        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }

    @Test
    void testContextDocumentsOnlyResponse() {
        mockWebServer.enqueue(createSuccessWindowRetrieverResponse());

        HayStackKnowledge knowledge = HayStackKnowledge.builder().config(createConfig()).build();
        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(0.5).build();

        List<Document> documents = knowledge.retrieve("test query?", retrieveConfig).block();

        assertNotNull(documents);
        assertEquals(1, documents.size());
    }

    // ========= Invalid Input Tests =========

    @Test
    void testRetrieveWithNullQueryShouldThrow() {
        HayStackKnowledge knowledge = HayStackKnowledge.builder().config(createConfig()).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(0.5).build();

        assertThrows(
                IllegalArgumentException.class,
                () -> knowledge.retrieve(null, retrieveConfig).block());
    }

    @Test
    void testRetrieveWithEmptyQuery() {
        HayStackKnowledge knowledge = HayStackKnowledge.builder().config(createConfig()).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(0.5).build();

        List<Document> documents = knowledge.retrieve("", retrieveConfig).block();

        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }

    @Test
    void testRetrieveWithWhitespaceQuery() {
        HayStackKnowledge knowledge = HayStackKnowledge.builder().config(createConfig()).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(0.5).build();

        List<Document> documents = knowledge.retrieve("   ", retrieveConfig).block();

        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }

    @Test
    void testRetrieveWithNullRetrieveConfigShouldThrow() {
        HayStackKnowledge knowledge = HayStackKnowledge.builder().config(createConfig()).build();

        assertThrows(
                IllegalArgumentException.class,
                () -> knowledge.retrieve("test query", null).block());
    }

    // ========= AddDocuments Tests =========

    @Test
    void testAddDocumentsShouldThrowUnsupportedOperation() {
        HayStackKnowledge knowledge = HayStackKnowledge.builder().config(createConfig()).build();

        UnsupportedOperationException exception =
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> knowledge.addDocuments(List.of()).block());

        assertTrue(exception.getMessage().contains("not supported"));
        assertTrue(exception.getMessage().contains("HayStack"));
    }
}
