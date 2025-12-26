/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
import static org.mockito.Mockito.mock;

import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.model.exception.OpenAIException;
import io.agentscope.core.model.transport.HttpTransport;
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
import reactor.test.StepVerifier;

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

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        String baseUrl = mockServer.url("/").toString();
        // Remove trailing slash for consistency
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        client = OpenAIClient.builder().apiKey("test-api-key").baseUrl(baseUrl).build();
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

        // Make request
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

        OpenAIResponse response = client.call(request);

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
        assertTrue(recordedRequest.getHeader("Authorization").contains("Bearer test-api-key"));
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

        OpenAIResponse response = client.call(request);

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
    @DisplayName("Should make streaming call")
    void testStreamingCall() {
        // Prepare SSE response
        String sseResponse =
                "data:"
                    + " {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}\n\n"
                    + "data:"
                    + " {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n"
                    + "data:"
                    + " {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"!\"},\"finish_reason\":null}]}\n\n"
                    + "data:"
                    + " {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n";

        mockServer.enqueue(
                new MockResponse()
                        .setBody(sseResponse)
                        .setHeader("Content-Type", "text/event-stream"));

        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(
                                List.of(OpenAIMessage.builder().role("user").content("Hi").build()))
                        .build();

        StepVerifier.create(client.stream(request))
                .expectNextCount(4) // 4 chunks (excluding [DONE])
                .verifyComplete();
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

        assertThrows(OpenAIException.class, () -> client.call(request));
    }

    @Test
    @DisplayName("Should use default base URL when not specified")
    void testDefaultBaseUrl() {
        OpenAIClient defaultClient = OpenAIClient.builder().apiKey("test-key").build();

        assertEquals("https://api.openai.com", defaultClient.getBaseUrl());
    }

    @Test
    @DisplayName("Should normalize base URL with trailing slash")
    void testBaseUrlNormalization() {
        OpenAIClient clientWithSlash =
                OpenAIClient.builder()
                        .apiKey("test-key")
                        .baseUrl("https://api.example.com/")
                        .build();

        assertEquals("https://api.example.com", clientWithSlash.getBaseUrl());
    }

    @Test
    @DisplayName("Should require API key in builder")
    void testEmptyApiKey() {
        // Builder should reject empty API key (consistent with DashScopeHttpClient)
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        OpenAIClient.builder()
                                .apiKey("")
                                .baseUrl(mockServer.url("/").toString())
                                .build());

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        OpenAIClient.builder()
                                .apiKey(null)
                                .baseUrl(mockServer.url("/").toString())
                                .build());
    }

    @Test
    @DisplayName("Should throw exception on empty response body")
    void testEmptyResponseBody() {
        // Simulate empty response body
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

        assertThrows(OpenAIException.class, () -> client.call(request));
    }

    @Test
    @DisplayName("Should detect error in response body even with 200 status")
    void testErrorInResponseBody() {
        // Response with 200 status but error field in body
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

        // Should throw exception even though status is 200
        OpenAIException exception = assertThrows(OpenAIException.class, () -> client.call(request));
        assertNotNull(exception);
    }

    @Test
    @DisplayName("Should handle errors in streaming response")
    void testStreamingErrorHandling() {
        // SSE response with error - OpenAI returns error as a regular chunk with error field
        // The error will be detected when parsing the response
        String errorSseResponse =
                "data:"
                    + " {\"id\":\"chatcmpl-error\",\"object\":\"chat.completion.chunk\",\"error\":{\"message\":\"Stream"
                    + " error\",\"type\":\"server_error\"}}\n\n"
                    + "data: [DONE]\n\n";

        mockServer.enqueue(
                new MockResponse()
                        .setBody(errorSseResponse)
                        .setHeader("Content-Type", "text/event-stream"));

        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(
                                List.of(OpenAIMessage.builder().role("user").content("Hi").build()))
                        .build();

        // Error in streaming is detected when parsing the chunk
        // The error will cause an exception to be thrown in the Flux
        StepVerifier.create(client.stream(request)).expectError(OpenAIException.class).verify();
    }

    @Test
    @DisplayName("Should handle streaming tool calls with fragments")
    void testStreamingToolCallsWithFragments() {
        // SSE response with streaming tool calls
        // First chunk: tool call with id and name
        // Subsequent chunks: only arguments fragments
        String sseResponse =
                "data:"
                    + " {\"id\":\"chatcmpl-tool\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_123\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"location\\\":\"}}]},\"finish_reason\":null}]}\n\n"
                    + "data:"
                    + " {\"id\":\"chatcmpl-tool\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_123\",\"type\":\"function\",\"function\":{\"arguments\":\"\\\"Beijing\\\"\"}}]},\"finish_reason\":null}]}\n\n"
                    + "data:"
                    + " {\"id\":\"chatcmpl-tool\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_123\",\"type\":\"function\",\"function\":{\"arguments\":\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                    + "data: [DONE]\n\n";

        mockServer.enqueue(
                new MockResponse()
                        .setBody(sseResponse)
                        .setHeader("Content-Type", "text/event-stream"));

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

        // Should receive all chunks including tool call fragments
        StepVerifier.create(client.stream(request))
                .expectNextCount(3) // 3 chunks (excluding [DONE])
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle malformed chunks in streaming")
    void testMalformedChunksInStreaming() {
        // SSE response with some malformed chunks
        String sseResponse =
                "data:"
                    + " {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n"
                    + "data: invalid json\n\n" // Malformed chunk - will be filtered out
                        // (returns null)
                        + "data:"
                        + " {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"!\"},\"finish_reason\":null}]}\n\n"
                        + "data: [DONE]\n\n";

        mockServer.enqueue(
                new MockResponse()
                        .setBody(sseResponse)
                        .setHeader("Content-Type", "text/event-stream"));

        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("gpt-4")
                        .messages(
                                List.of(OpenAIMessage.builder().role("user").content("Hi").build()))
                        .build();

        // Should skip malformed chunks (they return null and are filtered out) and continue
        // processing
        // Note: Malformed chunks cause parseStreamData to return null, which is now handled
        // gracefully using handle() operator, so the stream continues with valid chunks.
        StepVerifier.create(client.stream(request))
                .expectNextCount(2) // 2 valid chunks (malformed one is skipped)
                .verifyComplete();
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

        // Enqueue multiple responses for concurrent requests
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

        // Execute concurrent requests
        java.util.List<java.util.concurrent.Future<OpenAIResponse>> futures =
                new java.util.ArrayList<>();
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(5);

        for (int i = 0; i < 5; i++) {
            futures.add(
                    executor.submit(
                            () -> {
                                OpenAIResponse response = client.call(request);
                                assertNotNull(response);
                                assertEquals("chatcmpl-concurrent", response.getId());
                                return response;
                            }));
        }

        // Wait for all requests to complete
        for (java.util.concurrent.Future<OpenAIResponse> future : futures) {
            assertNotNull(future.get());
        }

        executor.shutdown();
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        // Verify all requests were made
        assertEquals(5, mockServer.getRequestCount());
    }

    @Test
    @DisplayName("Should create client with API key only (default base URL)")
    void testConstructorWithApiKeyOnly() {
        OpenAIClient client = OpenAIClient.builder().apiKey("test-key").build();

        assertEquals("https://api.openai.com", client.getBaseUrl());
    }

    @Test
    @DisplayName("Should create client with API key and base URL")
    void testConstructorWithApiKeyAndBaseUrl() {
        String customBaseUrl = "https://custom.api.com";
        OpenAIClient client =
                OpenAIClient.builder().apiKey("test-key").baseUrl(customBaseUrl).build();

        assertEquals(customBaseUrl, client.getBaseUrl());
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

        String response = client.callApi("/v1/test/endpoint", requestBody);

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

        String response = client.callApi("/v1/test", requestBody);

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

        String response = client.callApi("/v1/test", requestBody, options);

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

        assertThrows(OpenAIException.class, () -> client.callApi("/v1/test", requestBody));
    }

    @Test
    @DisplayName("Should handle empty response in generic API call")
    void testGenericApiCallEmptyResponse() {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("")
                        .setHeader("Content-Type", "application/json"));

        Map<String, Object> requestBody = new HashMap<>();

        assertThrows(OpenAIException.class, () -> client.callApi("/v1/test", requestBody));
    }

    @Test
    @DisplayName("Should normalize base URL with v1 path correctly")
    void testBaseUrlNormalizationWithV1() throws Exception {
        // Create client with base URL containing /v1
        String baseUrlWithV1 = mockServer.url("/v1").toString().replaceAll("/$", "");
        OpenAIClient clientWithV1 =
                OpenAIClient.builder().apiKey("test-key").baseUrl(baseUrlWithV1).build();

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

        clientWithV1.call(request);

        RecordedRequest recordedRequest = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(recordedRequest);
        // Should not duplicate /v1 in path
        assertTrue(
                recordedRequest.getPath().startsWith("/v1/"),
                "Path should start with /v1/ but got: " + recordedRequest.getPath());
    }

    @Test
    @DisplayName("Should handle close with managed transport")
    void testCloseWithManagedTransport() throws IOException {
        HttpTransport managedTransport = mock(HttpTransport.class);
        OpenAIClient client =
                new OpenAIClient(managedTransport, "test-key", "https://api.openai.com");

        // Register as managed
        io.agentscope.core.model.transport.HttpTransportFactory.register(managedTransport);

        // Should not close the managed transport
        client.close();

        // Verify transport.close() was not called
        // (since we can't easily verify no interaction, just ensure no exception is thrown)

        // Cleanup
        io.agentscope.core.model.transport.HttpTransportFactory.shutdown();
    }

    @Test
    @DisplayName("Should handle close with unmanaged transport")
    void testCloseWithUnmanagedTransport() throws Exception {
        HttpTransport unmanagedTransport = mock(HttpTransport.class);
        OpenAIClient client =
                new OpenAIClient(unmanagedTransport, "test-key", "https://api.openai.com");

        client.close();

        // Verify transport.close() was called (unmanaged transport gets closed)
        // Note: We can't easily verify this without the transport being in the list
    }

    @Test
    @DisplayName("Should handle base URL with custom path")
    void testBaseUrlWithCustomPath() throws Exception {
        String customBaseUrl = mockServer.url("/custom/path/v1").toString().replaceAll("/$", "");
        OpenAIClient client =
                OpenAIClient.builder().apiKey("test-key").baseUrl(customBaseUrl).build();

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

        client.call(request);

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

        client.call(request, options);

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

        client.call(request, options);

        RecordedRequest recordedRequest = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(recordedRequest);
        assertEquals("custom-value", recordedRequest.getHeader("X-Custom-Header"));
    }
}
