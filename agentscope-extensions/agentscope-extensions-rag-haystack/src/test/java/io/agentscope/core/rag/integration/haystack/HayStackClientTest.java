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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.rag.integration.haystack.exception.HayStackApiException;
import io.agentscope.core.rag.integration.haystack.model.HayStackResponse;
import java.io.IOException;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for HayStackClient.
 */
class HayStackClientTest {

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

    // ========= Helpers =========

    private HayStackConfig createConfig() {
        return HayStackConfig.builder()
                .baseUrl(mockWebServer.url("/retrieve").toString())
                .topK(3)
                .maxRetries(0)
                .build();
    }

    private MockResponse successResponse() {
        String body =
                """
                {
                  "code": 0,
                  "documents": [
                    {
                      "id": "doc-1",
                      "content": "Haystack is a RAG",
                      "score": 0.91,
                      "meta": {
                        "file_path": "intro.txt"
                      }
                    },
                    {
                      "id": "doc-2",
                      "content": "It supports pipelines",
                      "score": 0.85,
                      "meta": {
                        "file_path": "intro1.txt"
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

    private MockResponse emptyResponse() {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"documents\":[]}");
    }

    // ========= Success =========

    @Test
    void testRetrieveSuccess() {
        mockWebServer.enqueue(successResponse());

        HayStackClient client = new HayStackClient(createConfig());
        HayStackResponse response = client.retrieve("What is Haystack?", null, null).block();

        assertNotNull(response);
        assertEquals(0, response.getCode());
        assertNotNull(response.getDocuments());
        assertEquals(2, response.getDocuments().size());
    }

    @Test
    void testRetrieveEmptyResult() {
        mockWebServer.enqueue(emptyResponse());

        HayStackClient client = new HayStackClient(createConfig());
        HayStackResponse response = client.retrieve("unknown query", null, null).block();

        assertNotNull(response);
        assertNotNull(response.getDocuments());
        assertTrue(response.getDocuments().isEmpty());
    }

    // ========= Validation =========

    @Test
    void testNullQueryShouldThrow() {
        HayStackClient client = new HayStackClient(createConfig());

        assertThrows(
                IllegalArgumentException.class, () -> client.retrieve(null, null, null).block());
    }

    @Test
    void testEmptyQueryShouldThrow() {
        HayStackClient client = new HayStackClient(createConfig());

        assertThrows(IllegalArgumentException.class, () -> client.retrieve("", null, null).block());

        assertThrows(
                IllegalArgumentException.class, () -> client.retrieve("   ", null, null).block());
    }

    // ========= Request Body =========

    @Test
    void testRequestContainsRequiredFields() throws Exception {
        mockWebServer.enqueue(successResponse());

        HayStackClient client = new HayStackClient(createConfig());
        client.retrieve("test query", null, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();

        Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {});

        assertEquals("test query", parsed.get("query"));
        assertEquals(3, parsed.get("top_k"));
    }

    @Test
    void testMethodTopKOverridesConfig() throws Exception {
        mockWebServer.enqueue(successResponse());

        HayStackClient client = new HayStackClient(createConfig());
        client.retrieve("test query", 10, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();

        Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {});

        assertEquals(10, parsed.get("top_k"));
    }

    @Test
    void testOptionalParametersIncluded() throws Exception {
        mockWebServer.enqueue(successResponse());

        HayStackConfig config =
                HayStackConfig.builder()
                        .baseUrl(mockWebServer.url("/retrieve").toString())
                        .topK(5)
                        .scaleScore(true)
                        .returnEmbedding(true)
                        .windowSize(2)
                        .maxRetries(0)
                        .build();

        HayStackClient client = new HayStackClient(config);
        client.retrieve("test query", null, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        Map<String, Object> parsed =
                objectMapper.readValue(request.getBody().readUtf8(), new TypeReference<>() {});

        assertEquals(true, parsed.get("scale_score"));
        assertEquals(true, parsed.get("return_embedding"));
        assertEquals(2, parsed.get("window_size"));
    }

    // ========= Headers =========

    @Test
    void testCustomHeaders() throws Exception {
        mockWebServer.enqueue(successResponse());

        HayStackConfig config =
                HayStackConfig.builder()
                        .baseUrl(mockWebServer.url("/retrieve").toString())
                        .addCustomHeader("X-Request-ID", "req-123")
                        .addCustomHeader("X-Test", "test")
                        .maxRetries(0)
                        .build();

        HayStackClient client = new HayStackClient(config);
        client.retrieve("test query", null, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("req-123", request.getHeader("X-Request-ID"));
        assertEquals("test", request.getHeader("X-Test"));
        assertTrue(request.getHeader("Content-Type").contains("application/json"));
    }

    // ========= Error Handling =========
    @Test
    void testApiErrorCodeNotZero() {
        String body =
                """
                {
                  "code": 1001,
                  "error": "Invalid query"
                }
                """;
        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(body));

        HayStackClient client = new HayStackClient(createConfig());

        HayStackApiException ex =
                assertThrows(
                        HayStackApiException.class,
                        () -> client.retrieve("bad query", null, null).block());

        assertTrue(ex.getMessage().contains("Invalid query"));
    }

    // ========= Constructor =========

    @Test
    void testConstructorWithCustomObjectMapper() {
        mockWebServer.enqueue(successResponse());

        ObjectMapper mapper = new ObjectMapper();
        HayStackClient client = new HayStackClient(createConfig(), mapper);

        HayStackResponse response = client.retrieve("test query", null, null).block();
        assertNotNull(response);
    }

    // ========= Endpoint =========

    @Test
    void testRequestMethodAndPath() throws Exception {
        mockWebServer.enqueue(successResponse());

        HayStackClient client = new HayStackClient(createConfig());
        client.retrieve("test query", null, null).block();

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("POST", request.getMethod());
        assertTrue(request.getPath().contains("/retrieve"));
    }
}
