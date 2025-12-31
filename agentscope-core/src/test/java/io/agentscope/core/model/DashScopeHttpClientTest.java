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

import io.agentscope.core.formatter.dashscope.dto.DashScopeInput;
import io.agentscope.core.formatter.dashscope.dto.DashScopeMessage;
import io.agentscope.core.formatter.dashscope.dto.DashScopeParameters;
import io.agentscope.core.formatter.dashscope.dto.DashScopeRequest;
import io.agentscope.core.formatter.dashscope.dto.DashScopeResponse;
import java.util.ArrayList;
import java.util.List;
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
        client.close();
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

        DashScopeResponse response = client.call(request);

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

        DashScopeResponse response = client.call(request);

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
        StepVerifier.create(client.stream(request))
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

        StepVerifier.create(client.stream(request))
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
                        () -> client.call(request));

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
                        () -> client.call(request));

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
        defaultClient.close();
    }

    @Test
    void testRequestHeaders() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"request_id\":\"test\",\"output\":{\"choices\":[]}}")
                        .setHeader("Content-Type", "application/json"));

        DashScopeRequest request = createTestRequest("qwen-plus", "test");
        client.call(request);

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
        client.stream(request).blockLast();

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
        client1.close();

        // Test constructor with apiKey and baseUrl
        DashScopeHttpClient client2 = new DashScopeHttpClient("test-key", "https://custom.url.com");
        assertEquals("https://custom.url.com", client2.getBaseUrl());
        client2.close();

        // Test constructor with null baseUrl (should use default)
        DashScopeHttpClient client3 = new DashScopeHttpClient("test-key", null);
        assertEquals(DashScopeHttpClient.DEFAULT_BASE_URL, client3.getBaseUrl());
        client3.close();
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
}
