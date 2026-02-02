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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.formatter.dashscope.dto.DashScopeInput;
import io.agentscope.core.formatter.dashscope.dto.DashScopeMessage;
import io.agentscope.core.formatter.dashscope.dto.DashScopeParameters;
import io.agentscope.core.formatter.dashscope.dto.DashScopeRequest;
import io.agentscope.core.formatter.dashscope.dto.DashScopeResponse;
import io.agentscope.core.formatter.openai.dto.JsonSchema;
import io.agentscope.core.util.JsonSchemaUtils;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Tests for DashScopeHttpClient.
 */
@Tag("unit")
class DashScopeHttpClientTest {

    private MockWebServer mockServer;
    private DashScopeHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();

        client =
                DashScopeHttpClient.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .build();
    }

    @AfterEach
    void tearDown() throws Exception {

        mockServer.shutdown();
    }

    @Test
    void testSelectEndpointForTextModel() {
        assertEquals(
                DashScopeHttpClient.TEXT_GENERATION_ENDPOINT, client.selectEndpoint("qwen-plus"));
        assertEquals(
                DashScopeHttpClient.TEXT_GENERATION_ENDPOINT, client.selectEndpoint("qwen-max"));
        assertEquals(
                DashScopeHttpClient.TEXT_GENERATION_ENDPOINT,
                client.selectEndpoint("qwen-turbo-latest"));
        assertFalse(client.requiresMultimodalApi("qwen-plus"));
    }

    @Test
    void testSelectEndpointForVisionModel() {
        assertEquals(
                DashScopeHttpClient.MULTIMODAL_GENERATION_ENDPOINT,
                client.selectEndpoint("qwen-vl-max"));
        assertEquals(
                DashScopeHttpClient.MULTIMODAL_GENERATION_ENDPOINT,
                client.selectEndpoint("qwen-vl-plus"));
        assertTrue(client.requiresMultimodalApi("qwen-vl-max"));
    }

    @Test
    void testSelectEndpointForQvqModel() {
        assertEquals(
                DashScopeHttpClient.MULTIMODAL_GENERATION_ENDPOINT,
                client.selectEndpoint("qvq-72b"));
        assertEquals(
                DashScopeHttpClient.MULTIMODAL_GENERATION_ENDPOINT,
                client.selectEndpoint("qvq-7b"));
        assertTrue(client.requiresMultimodalApi("qvq-72b"));
    }

    @Test
    void testSelectEndpointForNullModel() {
        assertEquals(DashScopeHttpClient.TEXT_GENERATION_ENDPOINT, client.selectEndpoint(null));
    }

    @Test
    void testCallTextGenerationApi() throws Exception {
        String responseJson =
                """
                {
                  "request_id": "test-request-id",
                  "output": {
                    "choices": [
                      {
                        "message": {
                          "role": "assistant",
                          "content": "Hello, I'm Qwen!"
                        },
                        "finish_reason": "stop"
                      }
                    ]
                  },
                  "usage": {
                    "input_tokens": 5,
                    "output_tokens": 10
                  }
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        DashScopeRequest request = createTestRequest("qwen-plus", "Hello");

        DashScopeResponse response = client.call(request, null, null, null);

        assertNotNull(response);
        assertEquals("test-request-id", response.getRequestId());
        assertFalse(response.isError());
        assertNotNull(response.getOutput());
        assertEquals(
                "Hello, I'm Qwen!",
                response.getOutput().getFirstChoice().getMessage().getContentAsString());

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertTrue(recorded.getPath().contains("text-generation"));
        assertEquals("Bearer test-api-key", recorded.getHeader("Authorization"));
    }

    @Test
    void testCallMultimodalApi() throws Exception {
        String responseJson =
                """
                {
                  "request_id": "multimodal-request-id",
                  "output": {
                    "choices": [
                      {
                        "message": {
                          "role": "assistant",
                          "content": "I can see an image."
                        },
                        "finish_reason": "stop"
                      }
                    ]
                  }
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        DashScopeRequest request = createTestRequest("qwen-vl-max", "What's in this image?");

        DashScopeResponse response = client.call(request, null, null, null);

        assertNotNull(response);
        assertEquals("multimodal-request-id", response.getRequestId());

        RecordedRequest recorded = mockServer.takeRequest();
        assertTrue(recorded.getPath().contains("multimodal-generation"));
    }

    @Test
    void testStreamTextGenerationApi() {
        String sseResponse =
                "data:"
                    + " {\"request_id\":\"stream-1\",\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"}}]}}\n\n"
                    + "data:"
                    + " {\"request_id\":\"stream-1\",\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                    + " World\"}}]}}\n\n"
                    + "data: [DONE]\n\n";

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(sseResponse)
                        .setHeader("Content-Type", "text/event-stream"));

        DashScopeRequest request = createTestRequest("qwen-plus", "Hi");

        List<DashScopeResponse> responses = new ArrayList<>();
        StepVerifier.create(client.stream(request, null, null, null))
                .recordWith(() -> responses)
                .expectNextCount(2)
                .verifyComplete();

        assertEquals(2, responses.size());
        assertEquals(
                "Hello",
                responses.get(0).getOutput().getFirstChoice().getMessage().getContentAsString());
        assertEquals(
                " World",
                responses.get(1).getOutput().getFirstChoice().getMessage().getContentAsString());
    }

    @Test
    void testStreamMultimodalApi() {
        String sseResponse =
                "data:"
                    + " {\"request_id\":\"stream-vl\",\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"I"
                    + " see\"}}]}}\n\n"
                    + "data: [DONE]\n\n";

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(sseResponse)
                        .setHeader("Content-Type", "text/event-stream"));

        DashScopeRequest request = createTestRequest("qwen-vl-max", "Describe image");

        StepVerifier.create(client.stream(request, null, null, null))
                .expectNextMatches(
                        r ->
                                "I see"
                                        .equals(
                                                r.getOutput()
                                                        .getFirstChoice()
                                                        .getMessage()
                                                        .getContentAsString()))
                .verifyComplete();
    }

    @Test
    void testApiErrorHandling() {
        String errorJson =
                """
                {
                  "request_id": "error-request",
                  "code": "InvalidAPIKey",
                  "message": "Invalid API key provided"
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(errorJson)
                        .setHeader("Content-Type", "application/json"));

        DashScopeRequest request = createTestRequest("qwen-plus", "test");

        DashScopeHttpClient.DashScopeHttpException exception =
                assertThrows(
                        DashScopeHttpClient.DashScopeHttpException.class,
                        () -> client.call(request, null, null, null));

        assertTrue(exception.getMessage().contains("Invalid API key"));
    }

    @Test
    void testHttpErrorHandling() {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(500)
                        .setBody("Internal Server Error")
                        .setHeader("Content-Type", "text/plain"));

        DashScopeRequest request = createTestRequest("qwen-plus", "test");

        DashScopeHttpClient.DashScopeHttpException exception =
                assertThrows(
                        DashScopeHttpClient.DashScopeHttpException.class,
                        () -> client.call(request, null, null, null));

        assertEquals(500, exception.getStatusCode());
    }

    @Test
    void testBuilderRequiresApiKey() {
        assertThrows(IllegalArgumentException.class, () -> DashScopeHttpClient.builder().build());

        assertThrows(
                IllegalArgumentException.class,
                () -> DashScopeHttpClient.builder().apiKey("").build());
    }

    @Test
    void testDefaultBaseUrl() {
        DashScopeHttpClient defaultClient =
                DashScopeHttpClient.builder().apiKey("test-key").build();

        assertEquals(DashScopeHttpClient.DEFAULT_BASE_URL, defaultClient.getBaseUrl());
    }

    @Test
    void testRequestHeaders() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"request_id\":\"test\",\"output\":{\"choices\":[]}}")
                        .setHeader("Content-Type", "application/json"));

        DashScopeRequest request = createTestRequest("qwen-plus", "test");
        client.call(request, null, null, null);

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("Bearer test-api-key", recorded.getHeader("Authorization"));
        assertTrue(recorded.getHeader("Content-Type").contains("application/json"));
        assertTrue(recorded.getHeader("User-Agent").contains("agentscope-java"));
    }

    @Test
    void testStreamingRequestHeaders() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("data: {\"request_id\":\"test\"}\n\ndata: [DONE]\n")
                        .setHeader("Content-Type", "text/event-stream"));

        DashScopeRequest request = createTestRequest("qwen-plus", "test");
        client.stream(request, null, null, null).blockLast();

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("enable", recorded.getHeader("X-DashScope-SSE"));
    }

    @Test
    void testCallWithAdditionalHeaders() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"request_id\":\"test\",\"output\":{\"choices\":[]}}")
                        .setHeader("Content-Type", "application/json"));

        DashScopeRequest request = createTestRequest("qwen-plus", "test");
        java.util.Map<String, String> additionalHeaders = new java.util.HashMap<>();
        additionalHeaders.put("X-Custom-Header", "custom-value");
        additionalHeaders.put("X-Request-Id", "req-123");

        client.call(request, additionalHeaders, null, null);

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("custom-value", recorded.getHeader("X-Custom-Header"));
        assertEquals("req-123", recorded.getHeader("X-Request-Id"));
    }

    @Test
    void testCallWithAdditionalQueryParams() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"request_id\":\"test\",\"output\":{\"choices\":[]}}")
                        .setHeader("Content-Type", "application/json"));

        DashScopeRequest request = createTestRequest("qwen-plus", "test");
        java.util.Map<String, String> queryParams = new java.util.HashMap<>();
        queryParams.put("param1", "value1");
        queryParams.put("param2", "value2");

        client.call(request, null, null, queryParams);

        RecordedRequest recorded = mockServer.takeRequest();
        String path = recorded.getPath();
        assertTrue(path.contains("param1=value1"));
        assertTrue(path.contains("param2=value2"));
    }

    @Test
    void testCallWithAdditionalBodyParams() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"request_id\":\"test\",\"output\":{\"choices\":[]}}")
                        .setHeader("Content-Type", "application/json"));

        DashScopeRequest request = createTestRequest("qwen-plus", "test");
        java.util.Map<String, Object> bodyParams = new java.util.HashMap<>();
        bodyParams.put("custom_field", "custom_value");
        bodyParams.put("extra_option", 123);

        client.call(request, null, bodyParams, null);

        RecordedRequest recorded = mockServer.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("custom_field"));
        assertTrue(body.contains("custom_value"));
        assertTrue(body.contains("extra_option"));
    }

    @Test
    void testStreamWithAdditionalHeaders() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("data: {\"request_id\":\"test\"}\n\ndata: [DONE]\n")
                        .setHeader("Content-Type", "text/event-stream"));

        DashScopeRequest request = createTestRequest("qwen-plus", "test");
        java.util.Map<String, String> additionalHeaders = new java.util.HashMap<>();
        additionalHeaders.put("X-Stream-Header", "stream-value");

        client.stream(request, additionalHeaders, null, null).blockLast();

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("stream-value", recorded.getHeader("X-Stream-Header"));
        assertEquals("enable", recorded.getHeader("X-DashScope-SSE"));
    }

    @Test
    void testStreamErrorHandling() {
        String errorCode = "InvalidParameter";
        String errorMessage =
                "<400> InternalError.Algo.InvalidParameter: This model does not support"
                        + " enable_search.";
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(
                                "data: {\"code\":\""
                                        + errorCode
                                        + "\",\"message\":\""
                                        + errorMessage
                                        + "\",\"request_id\":\"request_id_123\"}")
                        .setHeader("Content-Type", "text/event-stream"));

        DashScopeRequest request = createTestRequest("qwen-plus", "test");
        java.util.Map<String, String> additionalHeaders = new java.util.HashMap<>();
        additionalHeaders.put("X-Stream-Header", "stream-value");

        Flux<DashScopeResponse> response = client.stream(request, additionalHeaders, null, null);
        StepVerifier.create(response)
                .expectErrorMatches(
                        e ->
                                (e
                                                instanceof
                                                DashScopeHttpClient.DashScopeHttpException
                                                        dashScopeHttpException)
                                        && dashScopeHttpException.getErrorCode().equals(errorCode)
                                        && dashScopeHttpException
                                                .getMessage()
                                                .equals("DashScope API error: " + errorMessage))
                .verify();
    }

    @Test
    void testHeaderOverride() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"request_id\":\"test\",\"output\":{\"choices\":[]}}")
                        .setHeader("Content-Type", "application/json"));

        DashScopeRequest request = createTestRequest("qwen-plus", "test");
        // Override the Content-Type header
        java.util.Map<String, String> additionalHeaders = new java.util.HashMap<>();
        additionalHeaders.put("Content-Type", "application/json; charset=utf-8");

        client.call(request, additionalHeaders, null, null);

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"));
    }

    @Test
    void testCallAdditionalHeadersAndParams() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"request_id\":\"test\",\"output\":{\"choices\":[]}}")
                        .setHeader("Content-Type", "application/json"));

        DashScopeRequest request = createTestRequest("qwen-plus", "test");
        // Override the Content-Type header
        Map<String, String> additionalHeaders = new HashMap<>();
        additionalHeaders.put("custom", "custom-header");
        Map<String, Object> additionalBodyParams = new HashMap<>();
        additionalBodyParams.put("custom", "custom-body");
        Map<String, String> additionalQueryParams = new HashMap<>();
        additionalQueryParams.put("custom", "custom-query");

        client.call(request, additionalHeaders, additionalBodyParams, additionalQueryParams);

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("custom-header", recorded.getHeader("custom"));
        assertEquals(
                DashScopeHttpClient.TEXT_GENERATION_ENDPOINT + "?custom=custom-query",
                recorded.getPath());
        assertTrue(recorded.getBody().readUtf8().contains("\"custom\":\"custom-body\""));
    }

    @Test
    void testStreamAdditionalHeadersAndParams() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"request_id\":\"test\",\"output\":{\"choices\":[]}}")
                        .setHeader("Content-Type", "application/json"));

        DashScopeRequest request = createTestRequest("qwen-plus", "test");
        // Override the Content-Type header
        Map<String, String> additionalHeaders = new HashMap<>();
        additionalHeaders.put("custom", "custom-header");
        Map<String, Object> additionalBodyParams = new HashMap<>();
        additionalBodyParams.put("custom", "custom-value");
        Map<String, String> additionalQueryParams = new HashMap<>();
        additionalQueryParams.put("custom", "custom-value");

        client.stream(request, additionalHeaders, additionalBodyParams, additionalQueryParams)
                .blockLast();

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("custom-header", recorded.getHeader("custom"));
        assertEquals(
                DashScopeHttpClient.TEXT_GENERATION_ENDPOINT + "?custom=custom-value",
                recorded.getPath());
        assertTrue(recorded.getBody().readUtf8().contains("\"custom\":\"custom-value\""));
    }

    // ==================== DashScopeHttpException Tests ====================

    @Test
    void testDashScopeHttpExceptionWithMessage() {
        DashScopeHttpClient.DashScopeHttpException exception =
                new DashScopeHttpClient.DashScopeHttpException("Test error message");

        assertEquals("Test error message", exception.getMessage());
        assertNull(exception.getStatusCode());
        assertNull(exception.getErrorCode());
        assertNull(exception.getResponseBody());
    }

    @Test
    void testDashScopeHttpExceptionWithCause() {
        Throwable cause = new RuntimeException("Original error");
        DashScopeHttpClient.DashScopeHttpException exception =
                new DashScopeHttpClient.DashScopeHttpException("Wrapped error", cause);

        assertEquals("Wrapped error", exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertNull(exception.getStatusCode());
        assertNull(exception.getErrorCode());
    }

    @Test
    void testDashScopeHttpExceptionWithStatusCode() {
        DashScopeHttpClient.DashScopeHttpException exception =
                new DashScopeHttpClient.DashScopeHttpException(
                        "HTTP error", 500, "Internal Server Error");

        assertEquals("HTTP error", exception.getMessage());
        assertEquals(500, exception.getStatusCode());
        assertNull(exception.getErrorCode());
        assertEquals("Internal Server Error", exception.getResponseBody());
    }

    @Test
    void testDashScopeHttpExceptionWithErrorCode() {
        DashScopeHttpClient.DashScopeHttpException exception =
                new DashScopeHttpClient.DashScopeHttpException(
                        "API error", "InvalidAPIKey", "{\"error\":\"invalid key\"}");

        assertEquals("API error", exception.getMessage());
        assertNull(exception.getStatusCode());
        assertEquals("InvalidAPIKey", exception.getErrorCode());
        assertEquals("{\"error\":\"invalid key\"}", exception.getResponseBody());
    }

    @Test
    void testConstructorWithNullApiKey() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DashScopeHttpClient.builder().apiKey(null).build());
    }

    @Test
    void testSimpleConstructors() {
        // Test constructor with just apiKey
        DashScopeHttpClient client1 = new DashScopeHttpClient("test-key");
        assertEquals(DashScopeHttpClient.DEFAULT_BASE_URL, client1.getBaseUrl());

        // Test constructor with apiKey and baseUrl
        DashScopeHttpClient client2 = new DashScopeHttpClient("test-key", "https://custom.url.com");
        assertEquals("https://custom.url.com", client2.getBaseUrl());

        // Test constructor with null baseUrl (should use default)
        DashScopeHttpClient client3 = new DashScopeHttpClient("test-key", null);
        assertEquals(DashScopeHttpClient.DEFAULT_BASE_URL, client3.getBaseUrl());
    }

    // ==================== Fetch Public Key Tests ====================

    @Test
    void testFetchPublicKeySuccess() throws Exception {
        String publicKeyResponse =
                """
                {
                  "request_id": "test-request-id",
                  "data": {
                    "public_key": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAnojrB579xgPQN5f46SvoRAiQBPWBaPzWh7hp51fWI+OsQk7KqH0qMcw8i0eK5rfOvJIPujOQgnes1ph9/gKAst9NzXVIl9JJYUSPtzTvOabhp4yvS3KBf9g3xHYVjYgW33SOY74Ue/tgbCXn717rV6gXb4sVvq9XK/1BrDcGbEOQEZEgBTFkm/g3lpWLQtACwwqHffoA9eQtkkz15ZFKosAgbR8LedfIvxAl2zk15REzxXiRcFgc9/tLF0U1t2Sxt9FkQefxYwn6EZawTsRJvf4kqF3MaPdTcDbOp0iSNvCl2qzPSf/F+Oll2CUM1tFAEu81oa4l0WaDR3UtvqOtyQIDAQAB",
                    "public_key_id": "1"
                  }
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(publicKeyResponse)
                        .setHeader("Content-Type", "application/json"));

        String baseUrl = mockServer.url("/").toString().replaceAll("/$", "");
        io.agentscope.core.model.transport.OkHttpTransport transport =
                io.agentscope.core.model.transport.OkHttpTransport.builder().build();

        try {
            DashScopeHttpClient.PublicKeyResult result =
                    DashScopeHttpClient.fetchPublicKey("test-api-key", baseUrl, transport);

            assertNotNull(result);
            assertEquals("1", result.publicKeyId());
            assertNotNull(result.publicKey());
            assertTrue(result.publicKey().length() > 0);

            RecordedRequest recorded = mockServer.takeRequest();
            assertEquals(DashScopeHttpClient.PUBLIC_KEYS_ENDPOINT, recorded.getPath());
            assertEquals("GET", recorded.getMethod());
            assertEquals("Bearer test-api-key", recorded.getHeader("Authorization"));
        } finally {
            transport.close();
        }
    }

    @Test
    void testFetchPublicKeyWithErrorResponse() throws Exception {
        String errorResponse =
                """
                {
                  "request_id": "test-request-id",
                  "code": "InvalidAPIKey",
                  "message": "Invalid API key provided"
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(errorResponse)
                        .setHeader("Content-Type", "application/json"));

        String baseUrl = mockServer.url("/").toString().replaceAll("/$", "");
        io.agentscope.core.model.transport.OkHttpTransport transport =
                io.agentscope.core.model.transport.OkHttpTransport.builder().build();

        try {
            DashScopeHttpClient.DashScopeHttpException exception =
                    assertThrows(
                            DashScopeHttpClient.DashScopeHttpException.class,
                            () ->
                                    DashScopeHttpClient.fetchPublicKey(
                                            "test-api-key", baseUrl, transport));

            assertTrue(exception.getMessage().contains("Invalid API key"));
            assertEquals("InvalidAPIKey", exception.getErrorCode());
        } finally {
            transport.close();
        }
    }

    @Test
    void testFetchPublicKeyWithHttpError() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(500)
                        .setBody("Internal Server Error")
                        .setHeader("Content-Type", "text/plain"));

        String baseUrl = mockServer.url("/").toString().replaceAll("/$", "");
        io.agentscope.core.model.transport.OkHttpTransport transport =
                io.agentscope.core.model.transport.OkHttpTransport.builder().build();

        try {
            DashScopeHttpClient.DashScopeHttpException exception =
                    assertThrows(
                            DashScopeHttpClient.DashScopeHttpException.class,
                            () ->
                                    DashScopeHttpClient.fetchPublicKey(
                                            "test-api-key", baseUrl, transport));

            assertEquals(500, exception.getStatusCode());
        } finally {
            transport.close();
        }
    }

    @Test
    void testFetchPublicKeyWithMissingData() throws Exception {
        String responseWithoutData =
                """
                {
                  "request_id": "test-request-id"
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(responseWithoutData)
                        .setHeader("Content-Type", "application/json"));

        String baseUrl = mockServer.url("/").toString().replaceAll("/$", "");
        io.agentscope.core.model.transport.OkHttpTransport transport =
                io.agentscope.core.model.transport.OkHttpTransport.builder().build();

        try {
            DashScopeHttpClient.DashScopeHttpException exception =
                    assertThrows(
                            DashScopeHttpClient.DashScopeHttpException.class,
                            () ->
                                    DashScopeHttpClient.fetchPublicKey(
                                            "test-api-key", baseUrl, transport));

            assertTrue(
                    exception.getMessage().contains("data is missing or incomplete"),
                    "Exception message should mention missing data");
        } finally {
            transport.close();
        }
    }

    // ==================== Encryption Tests ====================

    @Test
    void testEncryptionDisabled() {
        assertFalse(client.isEncryptionEnabled());
    }

    @Test
    void testEncryptionEnabled() throws Exception {
        // Generate RSA key pair for testing
        java.security.KeyPairGenerator keyGen = java.security.KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        java.security.KeyPair keyPair = keyGen.generateKeyPair();
        java.security.PublicKey publicKey = keyPair.getPublic();
        String publicKeyBase64 =
                java.util.Base64.getEncoder().encodeToString(publicKey.getEncoded());

        DashScopeHttpClient encryptedClient =
                DashScopeHttpClient.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .publicKeyId("test-key-id")
                        .publicKey(publicKeyBase64)
                        .build();

        assertTrue(encryptedClient.isEncryptionEnabled());
    }

    @Test
    void testEncryptionEnabledWithNullPublicKeyId() throws Exception {
        java.security.KeyPairGenerator keyGen = java.security.KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        java.security.KeyPair keyPair = keyGen.generateKeyPair();
        java.security.PublicKey publicKey = keyPair.getPublic();
        String publicKeyBase64 =
                java.util.Base64.getEncoder().encodeToString(publicKey.getEncoded());

        DashScopeHttpClient clientWithNullId =
                DashScopeHttpClient.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .publicKeyId(null)
                        .publicKey(publicKeyBase64)
                        .build();

        assertFalse(clientWithNullId.isEncryptionEnabled());
    }

    @Test
    void testEncryptionEnabledWithNullPublicKey() throws Exception {
        DashScopeHttpClient clientWithNullKey =
                DashScopeHttpClient.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .publicKeyId("test-key-id")
                        .publicKey(null)
                        .build();

        assertFalse(clientWithNullKey.isEncryptionEnabled());
    }

    @Test
    void testEncryptionEnabledWithEmptyPublicKeyId() throws Exception {
        java.security.KeyPairGenerator keyGen = java.security.KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        java.security.KeyPair keyPair = keyGen.generateKeyPair();
        java.security.PublicKey publicKey = keyPair.getPublic();
        String publicKeyBase64 =
                java.util.Base64.getEncoder().encodeToString(publicKey.getEncoded());

        DashScopeHttpClient clientWithEmptyId =
                DashScopeHttpClient.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .publicKeyId("")
                        .publicKey(publicKeyBase64)
                        .build();

        assertFalse(clientWithEmptyId.isEncryptionEnabled());
    }

    @Test
    void testEncryptionEnabledWithEmptyPublicKey() throws Exception {
        DashScopeHttpClient clientWithEmptyKey =
                DashScopeHttpClient.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .publicKeyId("test-key-id")
                        .publicKey("")
                        .build();

        assertFalse(clientWithEmptyKey.isEncryptionEnabled());
    }

    @Test
    void testBuilderWithEncryption() throws Exception {
        java.security.KeyPairGenerator keyGen = java.security.KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        java.security.KeyPair keyPair = keyGen.generateKeyPair();
        java.security.PublicKey publicKey = keyPair.getPublic();
        String publicKeyBase64 =
                java.util.Base64.getEncoder().encodeToString(publicKey.getEncoded());

        DashScopeHttpClient encryptedClient =
                DashScopeHttpClient.builder()
                        .apiKey("test-api-key")
                        .publicKeyId("test-key-id")
                        .publicKey(publicKeyBase64)
                        .build();

        assertNotNull(encryptedClient);
        assertTrue(encryptedClient.isEncryptionEnabled());
    }

    @Test
    void testEncryptRequestBody() throws Exception {
        // Generate RSA key pair for testing
        java.security.KeyPairGenerator keyGen = java.security.KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        java.security.KeyPair keyPair = keyGen.generateKeyPair();
        java.security.PublicKey publicKey = keyPair.getPublic();
        String publicKeyBase64 =
                java.util.Base64.getEncoder().encodeToString(publicKey.getEncoded());

        // Create encrypted client
        DashScopeHttpClient encryptedClient =
                DashScopeHttpClient.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .publicKeyId("test-key-id")
                        .publicKey(publicKeyBase64)
                        .build();

        // Mock response (this will be decrypted, but for this test we just want to verify
        // encryption happens)
        String responseJson =
                """
                {
                  "request_id": "test-request-id",
                  "output": {
                    "choices": [
                      {
                        "message": {
                          "role": "assistant",
                          "content": "Hello!"
                        }
                      }
                    ]
                  }
                }
                """;
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        // Create request with input
        DashScopeRequest request = createTestRequest("qwen-plus", "Hello, encrypt this!");

        // Make the call
        encryptedClient.call(request, null, null, null);

        // Verify request was sent
        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertNotNull(recordedRequest);

        // Parse request body
        String requestBody = recordedRequest.getBody().readUtf8();
        Map<String, Object> bodyMap =
                JsonUtils.getJsonCodec()
                        .fromJson(requestBody, new TypeReference<Map<String, Object>>() {});

        // Verify input field exists and is encrypted (should be a Base64 string, not JSON object)
        Object inputObj = bodyMap.get("input");
        assertNotNull(inputObj, "Input field should exist in request body");
        assertTrue(
                inputObj instanceof String, "Input field should be encrypted as a string (Base64)");

        String encryptedInput = (String) inputObj;
        assertFalse(encryptedInput.isEmpty(), "Encrypted input should not be empty");

        // Verify it's valid Base64 (encrypted data should be Base64-encoded)
        try {
            Base64.getDecoder().decode(encryptedInput);
            // If we get here, it's valid Base64
        } catch (IllegalArgumentException e) {
            fail("Encrypted input should be valid Base64 string");
        }

        // Verify the encrypted input is different from the original input JSON
        // (it should be encrypted, so it won't contain the original message content)
        assertFalse(
                encryptedInput.contains("Hello, encrypt this!"),
                "Encrypted input should not contain original plaintext");
    }

    @Test
    void testDecryptResponse() throws Exception {
        java.security.KeyPair keyPair = generateRsaKeyPair();
        String publicKeyBase64 =
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        DashScopeHttpClient encryptedClient =
                DashScopeHttpClient.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .publicKeyId("test-key-id")
                        .publicKey(publicKeyBase64)
                        .build();

        // Server dynamically encrypts response using AES key/IV derived from the request header
        mockServer.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        try {
                            SecretKey aesKey =
                                    extractAesKeyFromRequest(request, keyPair.getPrivate());
                            byte[] iv = extractIvFromRequest(request);
                            String outputJson =
                                    "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Decrypted"
                                        + " response!\"}}]}";
                            String encryptedOutput =
                                    DashScopeEncryptionUtils.encryptWithAes(aesKey, iv, outputJson);
                            String responseBody =
                                    String.format(
                                            "{\"request_id\":\"test-request-id\",\"output\":\"%s\"}",
                                            encryptedOutput);
                            return new MockResponse()
                                    .setResponseCode(200)
                                    .setHeader("Content-Type", "application/json")
                                    .setBody(responseBody);
                        } catch (Exception e) {
                            return new MockResponse().setResponseCode(500).setBody(e.toString());
                        }
                    }
                });

        DashScopeRequest request = createTestRequest("qwen-plus", "Test message");
        DashScopeResponse response = encryptedClient.call(request, null, null, null);

        assertNotNull(response);
        assertFalse(response.isError());
        assertNotNull(response.getOutput());
        assertNotNull(response.getOutput().getFirstChoice());
        assertNotNull(response.getOutput().getFirstChoice().getMessage());
        assertEquals(
                "Decrypted response!",
                response.getOutput().getFirstChoice().getMessage().getContentAsString());
    }

    @Test
    void testBuildEncryptionHeader() throws Exception {
        // Generate RSA key pair for testing
        java.security.KeyPairGenerator keyGen = java.security.KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        java.security.KeyPair keyPair = keyGen.generateKeyPair();
        java.security.PublicKey publicKey = keyPair.getPublic();
        String publicKeyBase64 =
                java.util.Base64.getEncoder().encodeToString(publicKey.getEncoded());

        // Create encrypted client
        DashScopeHttpClient encryptedClient =
                DashScopeHttpClient.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .publicKeyId("test-key-id")
                        .publicKey(publicKeyBase64)
                        .build();

        // Mock response
        String responseJson =
                """
                {
                  "request_id": "test-request-id",
                  "output": {
                    "choices": [
                      {
                        "message": {
                          "role": "assistant",
                          "content": "Hello!"
                        }
                      }
                    ]
                  }
                }
                """;
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        // Create request
        DashScopeRequest request = createTestRequest("qwen-plus", "Test message");

        // Make the call (buildEncryptionHeader will be called internally)
        encryptedClient.call(request, null, null, null);

        // Verify request was sent
        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertNotNull(recordedRequest);

        // Verify encryption header exists
        String encryptionHeader = recordedRequest.getHeader("X-DashScope-EncryptionKey");
        assertNotNull(encryptionHeader, "Encryption header should be present");

        // Parse encryption header JSON
        Map<String, String> headerMap =
                JsonUtils.getJsonCodec()
                        .fromJson(encryptionHeader, new TypeReference<Map<String, String>>() {});

        // Verify header contains required fields
        assertEquals("test-key-id", headerMap.get("public_key_id"));
        assertNotNull(headerMap.get("encrypt_key"));
        assertNotNull(headerMap.get("iv"));

        // Verify encrypt_key and iv are valid Base64
        try {
            Base64.getDecoder().decode(headerMap.get("encrypt_key"));
            Base64.getDecoder().decode(headerMap.get("iv"));
        } catch (IllegalArgumentException e) {
            fail("Encryption header values should be valid Base64");
        }
    }

    @Test
    void testCallWithEncryptionFullFlow() throws Exception {
        java.security.KeyPair keyPair = generateRsaKeyPair();
        String publicKeyBase64 =
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        DashScopeHttpClient encryptedClient =
                DashScopeHttpClient.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .publicKeyId("test-key-id")
                        .publicKey(publicKeyBase64)
                        .build();

        mockServer.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        try {
                            SecretKey aesKey =
                                    extractAesKeyFromRequest(request, keyPair.getPrivate());
                            byte[] iv = extractIvFromRequest(request);
                            // Simulate 官网示例：解密后 output 为 {finish_reason,text}
                            String outputJson =
                                    "{\"finish_reason\":\"stop\",\"text\":\"我是Qwen，由阿里云开发的超大规模语言模型。\"}";
                            String encryptedOutput =
                                    DashScopeEncryptionUtils.encryptWithAes(aesKey, iv, outputJson);
                            String responseBody =
                                    String.format(
                                            "{\"request_id\":\"test-request-id\",\"output\":\"%s\"}",
                                            encryptedOutput);
                            return new MockResponse()
                                    .setResponseCode(200)
                                    .setHeader("Content-Type", "application/json")
                                    .setBody(responseBody);
                        } catch (Exception e) {
                            return new MockResponse().setResponseCode(500).setBody(e.toString());
                        }
                    }
                });

        DashScopeRequest request = createTextFormatRequest("qwen-plus", "Full flow test");
        DashScopeResponse response = encryptedClient.call(request, null, null, null);

        assertNotNull(response);
        assertFalse(response.isError());
        assertEquals("test-request-id", response.getRequestId());
        assertNotNull(response.getOutput());
        assertEquals("stop", response.getOutput().getFinishReason());
        assertEquals("我是Qwen，由阿里云开发的超大规模语言模型。", response.getOutput().getText());
    }

    @Test
    void testStreamWithEncryptionFullFlow() throws Exception {
        java.security.KeyPair keyPair = generateRsaKeyPair();
        String publicKeyBase64 =
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        DashScopeHttpClient encryptedClient =
                DashScopeHttpClient.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .publicKeyId("test-key-id")
                        .publicKey(publicKeyBase64)
                        .build();

        mockServer.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        try {
                            SecretKey aesKey =
                                    extractAesKeyFromRequest(request, keyPair.getPrivate());
                            byte[] iv = extractIvFromRequest(request);

                            String chunk1Output =
                                    "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Stream\"}}]}";
                            String chunk2Output =
                                    "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                                        + " chunk 2\"}}]}";
                            String encryptedChunk1 =
                                    DashScopeEncryptionUtils.encryptWithAes(
                                            aesKey, iv, chunk1Output);
                            String encryptedChunk2 =
                                    DashScopeEncryptionUtils.encryptWithAes(
                                            aesKey, iv, chunk2Output);

                            String sseResponse =
                                    String.format(
                                            "data:"
                                                + " {\"request_id\":\"stream-1\",\"output\":\"%s\"}\n\n"
                                                + "data:"
                                                + " {\"request_id\":\"stream-1\",\"output\":\"%s\"}\n\n"
                                                + "data: [DONE]\n\n",
                                            encryptedChunk1, encryptedChunk2);
                            return new MockResponse()
                                    .setResponseCode(200)
                                    .setHeader("Content-Type", "text/event-stream")
                                    .setBody(sseResponse);
                        } catch (Exception e) {
                            return new MockResponse().setResponseCode(500).setBody(e.toString());
                        }
                    }
                });

        DashScopeRequest request = createTestRequest("qwen-plus", "Stream test");

        List<DashScopeResponse> responses = new ArrayList<>();
        StepVerifier.create(encryptedClient.stream(request, null, null, null))
                .recordWith(() -> responses)
                .expectNextCount(2)
                .verifyComplete();

        assertEquals(2, responses.size());
        assertEquals(
                "Stream",
                responses.get(0).getOutput().getFirstChoice().getMessage().getContentAsString());
        assertEquals(
                " chunk 2",
                responses.get(1).getOutput().getFirstChoice().getMessage().getContentAsString());
    }

    @Test
    void testBuildUrlWithNullQueryParams() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"request_id\":\"test\",\"output\":{\"choices\":[]}}")
                        .setHeader("Content-Type", "application/json"));

        DashScopeRequest request = createTestRequest("qwen-plus", "test");
        client.call(request, null, null, null);

        RecordedRequest recorded = mockServer.takeRequest();
        String path = recorded.getPath();
        // Path should not have query parameters
        assertFalse(path.contains("?"));
    }

    @Test
    void testBuildUrlWithNullKeyOrValueInQueryParams() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"request_id\":\"test\",\"output\":{\"choices\":[]}}")
                        .setHeader("Content-Type", "application/json"));

        DashScopeRequest request = createTestRequest("qwen-plus", "test");
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("valid", "value");
        queryParams.put(null, "value"); // null key
        queryParams.put("key", null); // null value

        client.call(request, null, null, queryParams);

        RecordedRequest recorded = mockServer.takeRequest();
        String path = recorded.getPath();
        // Should only contain valid param
        assertTrue(path.contains("valid=value"));
        // Should not contain null key/value params
        assertFalse(path.contains("key=null"));
    }

    @Test
    void testBuildRequestBodyWithAdditionalBodyParams() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"request_id\":\"test\",\"output\":{\"choices\":[]}}")
                        .setHeader("Content-Type", "application/json"));

        DashScopeRequest request = createTestRequest("qwen-plus", "test");

        Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromType(User.class);
        ResponseFormat responseFormat =
                ResponseFormat.jsonSchema(
                        JsonSchema.builder()
                                .name("user_info")
                                .description("The user information")
                                .strict(true)
                                .schema(schema)
                                .build());

        Map<String, Object> additionalBodyParams = new HashMap<>();
        additionalBodyParams.put("enable_search", true);
        additionalBodyParams.put("response_format", responseFormat);

        client.call(request, null, additionalBodyParams, null);

        RecordedRequest recorded = mockServer.takeRequest();
        String body = recorded.getBody().readUtf8();

        DashScopeRequest dashScopeRequest =
                JsonUtils.getJsonCodec().fromJson(body, DashScopeRequest.class);
        assertNotNull(dashScopeRequest);
        assertNotNull(dashScopeRequest.getParameters());
        assertTrue(dashScopeRequest.getParameters().getEnableSearch());
        ResponseFormat format = dashScopeRequest.getParameters().getResponseFormat();
        assertNotNull(format);
        assertEquals("json_schema", format.getType());
        assertEquals("user_info", format.getJsonSchema().getName());
        assertEquals("The user information", format.getJsonSchema().getDescription());
        assertTrue(format.getJsonSchema().getStrict());
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) format.getJsonSchema().getSchema().get("properties");
        assertTrue(properties.containsKey("name"));
        assertTrue(properties.containsKey("age"));
    }

    @Test
    void testEncryptionHeaderAbsentWhenNoInput() throws Exception {
        java.security.KeyPair keyPair = generateRsaKeyPair();
        String publicKeyBase64 =
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        DashScopeHttpClient encryptedClient =
                DashScopeHttpClient.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .publicKeyId("test-key-id")
                        .publicKey(publicKeyBase64)
                        .build();

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"request_id\":\"test\",\"output\":{\"choices\":[]}}")
                        .setHeader("Content-Type", "application/json"));

        // Build a request with no input -> request JSON will not contain "input"
        DashScopeRequest request =
                DashScopeRequest.builder()
                        .model("qwen-plus")
                        .parameters(
                                DashScopeParameters.builder()
                                        .resultFormat("message")
                                        .incrementalOutput(false)
                                        .build())
                        .build();

        encryptedClient.call(request, null, null, null);

        RecordedRequest recorded = mockServer.takeRequest();
        assertNull(
                recorded.getHeader("X-DashScope-EncryptionKey"),
                "Encryption header should be absent when no input is present");
    }

    @Test
    void testDecryptResponseWithNullContext() throws Exception {
        // This test now validates: if there is no encryption context for this request,
        // encrypted output will be left as-is and deserialized as null output (graceful behavior).

        java.security.KeyPair keyPair = generateRsaKeyPair();
        String publicKeyBase64 =
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        DashScopeHttpClient encryptedClient =
                DashScopeHttpClient.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .publicKeyId("test-key-id")
                        .publicKey(publicKeyBase64)
                        .build();

        // Server returns an encrypted output string, but request has no input -> client has no
        // context
        javax.crypto.SecretKey testAesKey = DashScopeEncryptionUtils.generateAesSecretKey();
        byte[] testIv = DashScopeEncryptionUtils.generateIv();
        String originalOutputJson = "{\"choices\":[]}";
        String encryptedOutput =
                DashScopeEncryptionUtils.encryptWithAes(testAesKey, testIv, originalOutputJson);
        String encryptedResponseJson =
                String.format(
                        "{\"request_id\":\"test-request-id\",\"output\":\"%s\"}", encryptedOutput);

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(encryptedResponseJson)
                        .setHeader("Content-Type", "application/json"));

        DashScopeRequest request =
                DashScopeRequest.builder()
                        .model("qwen-plus")
                        .parameters(
                                DashScopeParameters.builder()
                                        .resultFormat("message")
                                        .incrementalOutput(false)
                                        .build())
                        .build();

        DashScopeResponse response = encryptedClient.call(request, null, null, null);
        assertNotNull(response);
        assertNull(
                response.getOutput(),
                "When encrypted output is not decrypted, output should be null (graceful)");
    }

    @Test
    void testDecryptResponseWithNonStringOutput() throws Exception {
        java.security.KeyPair keyPair = generateRsaKeyPair();
        String publicKeyBase64 =
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        // Create encrypted client
        DashScopeHttpClient encryptedClient =
                DashScopeHttpClient.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .publicKeyId("test-key-id")
                        .publicKey(publicKeyBase64)
                        .build();

        // Response with output as object (not encrypted string)
        String responseJson =
                """
                {
                  "request_id": "test-request-id",
                  "output": {
                    "choices": [
                      {
                        "message": {
                          "role": "assistant",
                          "content": "Not encrypted"
                        }
                      }
                    ]
                  }
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        DashScopeRequest request = createTestRequest("qwen-plus", "test");

        // Make the call
        DashScopeResponse response = encryptedClient.call(request, null, null, null);

        // When output is not a string, decryptResponse should return original response
        assertNotNull(response);
        assertNotNull(response.getOutput());
    }

    private DashScopeRequest createTestRequest(String model, String content) {
        DashScopeMessage message = DashScopeMessage.builder().role("user").content(content).build();

        DashScopeParameters params =
                DashScopeParameters.builder()
                        .resultFormat("message")
                        .incrementalOutput(false)
                        .build();

        return DashScopeRequest.builder()
                .model(model)
                .input(DashScopeInput.of(List.of(message)))
                .parameters(params)
                .build();
    }

    private DashScopeRequest createTextFormatRequest(String model, String content) {
        DashScopeMessage message = DashScopeMessage.builder().role("user").content(content).build();
        DashScopeParameters params =
                DashScopeParameters.builder().resultFormat("text").incrementalOutput(false).build();
        return DashScopeRequest.builder()
                .model(model)
                .input(DashScopeInput.of(List.of(message)))
                .parameters(params)
                .build();
    }

    private java.security.KeyPair generateRsaKeyPair() throws Exception {
        java.security.KeyPairGenerator keyGen = java.security.KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        return keyGen.generateKeyPair();
    }

    private byte[] extractIvFromRequest(RecordedRequest request) throws Exception {
        Map<String, String> headerMap = parseEncryptionHeader(request);
        return Base64.getDecoder().decode(headerMap.get("iv"));
    }

    private SecretKey extractAesKeyFromRequest(
            RecordedRequest request, java.security.PrivateKey privateKey) throws Exception {
        Map<String, String> headerMap = parseEncryptionHeader(request);
        String encryptedKeyBase64 = headerMap.get("encrypt_key");
        byte[] encryptedKeyBytes = Base64.getDecoder().decode(encryptedKeyBase64);

        javax.crypto.Cipher rsaCipher = javax.crypto.Cipher.getInstance("RSA");
        rsaCipher.init(javax.crypto.Cipher.DECRYPT_MODE, privateKey);
        byte[] decrypted = rsaCipher.doFinal(encryptedKeyBytes);

        String base64AesKey = new String(decrypted, StandardCharsets.UTF_8);
        byte[] aesKeyBytes = Base64.getDecoder().decode(base64AesKey);
        return new SecretKeySpec(aesKeyBytes, "AES");
    }

    private Map<String, String> parseEncryptionHeader(RecordedRequest request) throws Exception {
        String encryptionHeader = request.getHeader("X-DashScope-EncryptionKey");
        assertNotNull(encryptionHeader, "Encryption header should be present");
        return JsonUtils.getJsonCodec()
                .fromJson(encryptionHeader, new TypeReference<Map<String, String>>() {});
    }

    private record User(
            @JsonPropertyDescription("The user name") @JsonProperty(value = "name", required = true)
                    String name,
            @JsonPropertyDescription("The user age") @JsonProperty(value = "age", required = true)
                    int age,
            @JsonPropertyDescription("The user email address") @JsonProperty("email")
                    String email) {}
}
