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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.model.exception.OpenAIException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OpenAIClient.
 *
 * <p>These tests use MockWebServer to simulate OpenAI API responses.
 */
@Tag("unit")
@DisplayName("OpenAIClient Unit Tests")
class OpenAIClientTest {

    private MockWebServer mockServer;
    private OpenAIClient client;
    private String baseUrl;
    private static final String TEST_API_KEY = "test-api-key";

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        baseUrl = mockServer.url("/").toString();
        // Remove trailing slash for consistency
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        // Stateless client - no config stored
        client = new OpenAIClient();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("Should make successful non-streaming call")
    void testNonStreamingCall() throws Exception {
        // Prepare mock response
        String responseJson =
                """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Hello! How can I help you?"
                        },
                        "finish_reason": "stop"
                    }],
                    "usage": {
                        "prompt_tokens": 10,
                        "completion_tokens": 20,
                        "total_tokens": 30
                    }
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        // Make request with per-call config
        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(
                                List.of(
                                        OpenAIMessage.builder()
                                                .role("user")
                                                .content("Hello")
                                                .build()))
                        .build();

        OpenAIResponse response = client.call(TEST_API_KEY, baseUrl, request);

        // Verify response
        assertNotNull(response);
        assertEquals("chatcmpl-123", response.getId());
        assertEquals("chat.completion", response.getObject());
        assertNotNull(response.getChoices());
        assertEquals(1, response.getChoices().size());
        assertEquals(
                "Hello! How can I help you?",
                response.getChoices().get(0).getMessage().getContentAsString());
        assertEquals("stop", response.getChoices().get(0).getFinishReason());
        assertNotNull(response.getUsage());
        assertEquals(10, response.getUsage().getPromptTokens());
        assertEquals(20, response.getUsage().getCompletionTokens());

        // Verify request
        RecordedRequest recordedRequest = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(recordedRequest);
        assertEquals("POST", recordedRequest.getMethod());
        assertEquals("/v1/chat/completions", recordedRequest.getPath());
        assertTrue(recordedRequest.getHeader("Authorization").contains("Bearer " + TEST_API_KEY));
        assertTrue(recordedRequest.getHeader("Content-Type").contains("application/json"));
    }

    @Test
    @DisplayName("Should handle tool calls in response")
    void testToolCallsResponse() throws Exception {
        String responseJson =
                """
                {
                    "id": "chatcmpl-456",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": null,
                            "tool_calls": [{
                                "id": "call_abc123",
                                "type": "function",
                                "function": {
                                    "name": "get_weather",
                                    "arguments": "{\\"location\\": \\"Beijing\\"}"
                                }
                            }]
                        },
                        "finish_reason": "tool_calls"
                    }]
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(
                                List.of(
                                        OpenAIMessage.builder()
                                                .role("user")
                                                .content("What's the weather in Beijing?")
                                                .build()))
                        .build();

        OpenAIResponse response = client.call(TEST_API_KEY, baseUrl, request);

        assertNotNull(response);
        assertEquals("tool_calls", response.getFirstChoice().getFinishReason());
        assertNotNull(response.getFirstChoice().getMessage().getToolCalls());
        assertEquals(1, response.getFirstChoice().getMessage().getToolCalls().size());

        var toolCall = response.getFirstChoice().getMessage().getToolCalls().get(0);
        assertEquals("call_abc123", toolCall.getId());
        assertEquals("function", toolCall.getType());
        assertEquals("get_weather", toolCall.getFunction().getName());
        assertTrue(toolCall.getFunction().getArguments().contains("Beijing"));
    }

    @Test
    @DisplayName("Should handle API error response")
    void testErrorResponse() {
        String errorResponse =
                """
                {
                    "error": {
                        "message": "Invalid API key",
                        "type": "invalid_request_error",
                        "code": "invalid_api_key"
                    }
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(401)
                        .setBody(errorResponse)
                        .setHeader("Content-Type", "application/json"));

        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(
                                List.of(
                                        OpenAIMessage.builder()
                                                .role("user")
                                                .content("Hello")
                                                .build()))
                        .build();

        assertThrows(OpenAIException.class, () -> client.call(TEST_API_KEY, baseUrl, request));
    }

    @Test
    @DisplayName("Should handle API key from options override")
    void testApiKeyFromOptions() throws Exception {
        String responseJson =
                """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Hello"
                        },
                        "finish_reason": "stop"
                    }]
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(
                                List.of(
                                        OpenAIMessage.builder()
                                                .role("user")
                                                .content("Hello")
                                                .build()))
                        .build();

        GenerateOptions options = GenerateOptions.builder().apiKey("options-api-key").build();

        OpenAIResponse response = client.call(TEST_API_KEY, baseUrl, request, options);

        assertNotNull(response);

        RecordedRequest recordedRequest = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertTrue(recordedRequest.getHeader("Authorization").contains("Bearer options-api-key"));
    }

    @Test
    @DisplayName("Should handle base URL from options override")
    void testBaseUrlFromOptions() throws Exception {
        String responseJson =
                """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Hello"
                        },
                        "finish_reason": "stop"
                    }]
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(
                                List.of(
                                        OpenAIMessage.builder()
                                                .role("user")
                                                .content("Hello")
                                                .build()))
                        .build();

        // Options override baseUrl - use mockServer URL
        GenerateOptions options = GenerateOptions.builder().baseUrl(baseUrl).build();

        OpenAIResponse response = client.call(TEST_API_KEY, null, request, options);

        assertNotNull(response);
    }

    @Test
    @DisplayName("Should throw exception on empty response body")
    void testEmptyResponseBody() {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("") // Empty response body
                        .setHeader("Content-Type", "application/json"));

        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(
                                List.of(
                                        OpenAIMessage.builder()
                                                .role("user")
                                                .content("Hello")
                                                .build()))
                        .build();

        assertThrows(OpenAIException.class, () -> client.call(TEST_API_KEY, baseUrl, request));
    }

    @Test
    @DisplayName("Should detect error in response body even with 200 status")
    void testErrorInResponseBody() {
        String errorResponse =
                """
                {
                    "id": "chatcmpl-error",
                    "object": "chat.completion",
                    "error": {
                        "message": "Model overloaded",
                        "type": "server_error",
                        "code": "model_overloaded"
                    }
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(errorResponse)
                        .setHeader("Content-Type", "application/json"));

        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(
                                List.of(
                                        OpenAIMessage.builder()
                                                .role("user")
                                                .content("Hello")
                                                .build()))
                        .build();

        OpenAIException exception =
                assertThrows(
                        OpenAIException.class, () -> client.call(TEST_API_KEY, baseUrl, request));
        assertNotNull(exception);
    }

    @Test
    @DisplayName("Should handle concurrent requests safely")
    void testConcurrentRequests() throws Exception {
        String responseJson =
                """
                {
                    "id": "chatcmpl-concurrent",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Response"
                        },
                        "finish_reason": "stop"
                    }]
                }
                """;

        for (int i = 0; i < 5; i++) {
            mockServer.enqueue(
                    new MockResponse()
                            .setBody(responseJson)
                            .setHeader("Content-Type", "application/json"));
        }

        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(
                                List.of(
                                        OpenAIMessage.builder()
                                                .role("user")
                                                .content("Hello")
                                                .build()))
                        .build();

        java.util.List<java.util.concurrent.Future<OpenAIResponse>> futures =
                new java.util.ArrayList<>();
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(5);

        for (int i = 0; i < 5; i++) {
            futures.add(
                    executor.submit(
                            () -> {
                                OpenAIResponse response =
                                        client.call(TEST_API_KEY, baseUrl, request);
                                assertNotNull(response);
                                assertEquals("chatcmpl-concurrent", response.getId());
                                return response;
                            }));
        }

        for (java.util.concurrent.Future<OpenAIResponse> future : futures) {
            assertNotNull(future.get());
        }

        executor.shutdown();
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(5, mockServer.getRequestCount());
    }

    @Test
    @DisplayName("Should handle generic API call successfully")
    void testGenericApiCall() throws Exception {
        String responseBody = "{\"data\": \"test response\"}";

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseBody)
                        .setHeader("Content-Type", "application/json"));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("param1", "value1");

        String response = client.callApi(TEST_API_KEY, baseUrl, "/v1/test/endpoint", requestBody);

        assertNotNull(response);
        assertEquals(responseBody, response);

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
    }

    @Test
    @DisplayName("Should handle generic API call with string request body")
    void testGenericApiCallWithStringBody() throws Exception {
        String responseBody = "{\"result\": \"success\"}";

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseBody)
                        .setHeader("Content-Type", "application/json"));

        String requestBody = "{\"param\": \"value\"}";

        String response = client.callApi(TEST_API_KEY, baseUrl, "/v1/test", requestBody);

        assertNotNull(response);
        assertEquals(responseBody, response);
    }

    @Test
    @DisplayName("Should handle generic API call with GenerateOptions")
    void testGenericApiCallWithOptions() throws Exception {
        String responseBody = "{\"data\": \"test\"}";

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseBody)
                        .setHeader("Content-Type", "application/json"));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");

        GenerateOptions options =
                GenerateOptions.builder().additionalQueryParams(Map.of("custom", "param")).build();

        String response = client.callApi(TEST_API_KEY, baseUrl, "/v1/test", requestBody, options);

        assertNotNull(response);
        assertEquals(responseBody, response);

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertTrue(request.getPath().contains("custom=param"));
    }

    @Test
    @DisplayName("Should handle API call error in generic API call")
    void testGenericApiCallError() {
        String errorResponse =
                """
                {
                    "error": {
                        "message": "Invalid request",
                        "type": "invalid_request_error"
                    }
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(400)
                        .setBody(errorResponse)
                        .setHeader("Content-Type", "application/json"));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");

        assertThrows(
                OpenAIException.class,
                () -> client.callApi(TEST_API_KEY, baseUrl, "/v1/test", requestBody));
    }

    @Test
    @DisplayName("Should normalize base URL with v1 path correctly")
    void testBaseUrlNormalizationWithV1() throws Exception {
        String baseUrlWithV1 = mockServer.url("/v1").toString().replaceAll("/$", "");

        String responseJson =
                """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Response"
                        },
                        "finish_reason": "stop"
                    }]
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(
                                List.of(
                                        OpenAIMessage.builder()
                                                .role("user")
                                                .content("Hello")
                                                .build()))
                        .build();

        client.call(TEST_API_KEY, baseUrlWithV1, request);

        RecordedRequest recordedRequest = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(recordedRequest);
        assertTrue(
                recordedRequest.getPath().startsWith("/v1/"),
                "Path should start with /v1/ but got: " + recordedRequest.getPath());
    }

    @Test
    @DisplayName("Should handle base URL with custom path")
    void testBaseUrlWithCustomPath() throws Exception {
        String customBaseUrl = mockServer.url("/custom/path/v1").toString().replaceAll("/$", "");

        String responseJson =
                """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Response"
                        },
                        "finish_reason": "stop"
                    }]
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(
                                List.of(
                                        OpenAIMessage.builder()
                                                .role("user")
                                                .content("Hello")
                                                .build()))
                        .build();

        client.call(TEST_API_KEY, customBaseUrl, request);

        RecordedRequest recordedRequest = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(recordedRequest);
        assertTrue(recordedRequest.getPath().contains("chat"));
    }

    @Test
    @DisplayName("Should handle API call with additional query params")
    void testApiCallWithQueryParams() throws Exception {
        String responseJson =
                """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Response"
                        },
                        "finish_reason": "stop"
                    }]
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(
                                List.of(
                                        OpenAIMessage.builder()
                                                .role("user")
                                                .content("Hello")
                                                .build()))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalQueryParams(Map.of("custom_param", "custom_value"))
                        .build();

        client.call(TEST_API_KEY, baseUrl, request, options);

        RecordedRequest recordedRequest = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(recordedRequest);
        assertTrue(
                recordedRequest.getPath().contains("custom_param=custom_value"),
                "Path should contain query params: " + recordedRequest.getPath());
    }

    @Test
    @DisplayName("Should handle API call with additional headers")
    void testApiCallWithAdditionalHeaders() throws Exception {
        String responseJson =
                """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Response"
                        },
                        "finish_reason": "stop"
                    }]
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(
                                List.of(
                                        OpenAIMessage.builder()
                                                .role("user")
                                                .content("Hello")
                                                .build()))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalHeaders(Map.of("X-Custom-Header", "custom-value"))
                        .build();

        client.call(TEST_API_KEY, baseUrl, request, options);

        RecordedRequest recordedRequest = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(recordedRequest);
        assertEquals("custom-value", recordedRequest.getHeader("X-Custom-Header"));
    }
}
