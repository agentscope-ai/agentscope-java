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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.model.exception.OpenAIException;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * Tests for HTTP retry mechanism in OpenAIClient.
 */
class OpenAIClientRetryTest {

    @Test
    void testRetryOn429RateLimited() {
        HttpTransport transport = mock(HttpTransport.class);

        // First call returns 429 (rate limited), second call succeeds
        HttpResponse rateLimitedResponse = mock(HttpResponse.class);
        when(rateLimitedResponse.getStatusCode()).thenReturn(429);

        HttpResponse successResponse = mock(HttpResponse.class);
        when(successResponse.getStatusCode()).thenReturn(200);
        when(successResponse.isSuccessful()).thenReturn(true);
        when(successResponse.getBody())
                .thenReturn(
                        "{\"id\": \"chatcmpl-123\", \"object\": \"chat.completion\", \"created\":"
                                + " 0, \"model\": \"gpt-4\", \"choices\": [{\"message\": {\"role\":"
                                + " \"assistant\", \"content\": \"response\"}, \"finish_reason\":"
                                + " \"stop\"}], \"usage\": {\"prompt_tokens\": 10,"
                                + " \"completion_tokens\": 5, \"total_tokens\": 15}}");

        when(transport.execute(any(HttpRequest.class)))
                .thenReturn(rateLimitedResponse)
                .thenReturn(successResponse);

        OpenAIClient client = new OpenAIClient(transport, "test-key", null);
        OpenAIRequest request = new OpenAIRequest();
        request.setModel("gpt-4");
        request.setMessages(new ArrayList<>());

        // Should not throw exception, should retry and succeed
        OpenAIResponse response = client.call(request);
        assertEquals("chatcmpl-123", response.getId());

        // Verify retry occurred (execute called twice)
        verify(transport, times(2)).execute(any(HttpRequest.class));
    }

    @Test
    void testRetryOn500ServerError() {
        HttpTransport transport = mock(HttpTransport.class);

        // First call returns 500, second call succeeds
        HttpResponse serverErrorResponse = mock(HttpResponse.class);
        when(serverErrorResponse.getStatusCode()).thenReturn(500);

        HttpResponse successResponse = mock(HttpResponse.class);
        when(successResponse.getStatusCode()).thenReturn(200);
        when(successResponse.isSuccessful()).thenReturn(true);
        when(successResponse.getBody())
                .thenReturn(
                        "{\"id\": \"chatcmpl-456\", \"object\": \"chat.completion\", \"created\":"
                                + " 0, \"model\": \"gpt-4\", \"choices\": [{\"message\": {\"role\":"
                                + " \"assistant\", \"content\": \"response\"}, \"finish_reason\":"
                                + " \"stop\"}], \"usage\": {\"prompt_tokens\": 10,"
                                + " \"completion_tokens\": 5, \"total_tokens\": 15}}");

        when(transport.execute(any(HttpRequest.class)))
                .thenReturn(serverErrorResponse)
                .thenReturn(successResponse);

        OpenAIClient client = new OpenAIClient(transport, "test-key", null);
        OpenAIRequest request = new OpenAIRequest();
        request.setModel("gpt-4");
        request.setMessages(new ArrayList<>());

        // Should not throw exception, should retry and succeed
        OpenAIResponse response = client.call(request);
        assertEquals("chatcmpl-456", response.getId());

        // Verify retry occurred
        verify(transport, times(2)).execute(any(HttpRequest.class));
    }

    @Test
    void testRetryExhaustedAfterMaxAttempts() {
        HttpTransport transport = mock(HttpTransport.class);

        // All calls return 429
        HttpResponse rateLimitedResponse = mock(HttpResponse.class);
        when(rateLimitedResponse.getStatusCode()).thenReturn(429);
        when(rateLimitedResponse.getBody()).thenReturn("{}");

        when(transport.execute(any(HttpRequest.class))).thenReturn(rateLimitedResponse);

        OpenAIClient client = new OpenAIClient(transport, "test-key", null);
        OpenAIRequest request = new OpenAIRequest();
        request.setModel("gpt-4");
        request.setMessages(new ArrayList<>());

        // Should throw exception after max retries
        assertThrows(OpenAIException.class, () -> client.call(request));

        // Verify retry attempts were made (at least 3 times)
        verify(transport, times(3)).execute(any(HttpRequest.class));
    }

    @Test
    void testNoRetryOn4xxClientError() {
        HttpTransport transport = mock(HttpTransport.class);

        // Return 400 (bad request) - should not retry
        HttpResponse clientErrorResponse = mock(HttpResponse.class);
        when(clientErrorResponse.getStatusCode()).thenReturn(400);
        when(clientErrorResponse.isSuccessful()).thenReturn(false);
        when(clientErrorResponse.getBody()).thenReturn("{\"error\": \"Bad request\"}");

        when(transport.execute(any(HttpRequest.class))).thenReturn(clientErrorResponse);

        OpenAIClient client = new OpenAIClient(transport, "test-key", null);
        OpenAIRequest request = new OpenAIRequest();
        request.setModel("gpt-4");
        request.setMessages(new ArrayList<>());

        // Should throw exception without retrying
        assertThrows(OpenAIException.class, () -> client.call(request));

        // Verify execute was called only once (no retry)
        verify(transport, times(1)).execute(any(HttpRequest.class));
    }

    @Test
    void testNoRetryOn200Success() {
        HttpTransport transport = mock(HttpTransport.class);

        // Return 200 success immediately
        HttpResponse successResponse = mock(HttpResponse.class);
        when(successResponse.getStatusCode()).thenReturn(200);
        when(successResponse.isSuccessful()).thenReturn(true);
        when(successResponse.getBody())
                .thenReturn(
                        "{\"id\": \"chatcmpl-789\", \"object\": \"chat.completion\", \"created\":"
                                + " 0, \"model\": \"gpt-4\", \"choices\": [{\"message\": {\"role\":"
                                + " \"assistant\", \"content\": \"response\"}, \"finish_reason\":"
                                + " \"stop\"}], \"usage\": {\"prompt_tokens\": 10,"
                                + " \"completion_tokens\": 5, \"total_tokens\": 15}}");

        when(transport.execute(any(HttpRequest.class))).thenReturn(successResponse);

        OpenAIClient client = new OpenAIClient(transport, "test-key", null);
        OpenAIRequest request = new OpenAIRequest();
        request.setModel("gpt-4");
        request.setMessages(new ArrayList<>());

        // Should succeed on first try
        OpenAIResponse response = client.call(request);
        assertEquals("chatcmpl-789", response.getId());

        // Verify execute was called only once (no retry)
        verify(transport, times(1)).execute(any(HttpRequest.class));
    }

    @Test
    void testRetryOn502BadGateway() {
        HttpTransport transport = mock(HttpTransport.class);

        // First two calls return 502, third call succeeds
        HttpResponse gatewayErrorResponse = mock(HttpResponse.class);
        when(gatewayErrorResponse.getStatusCode()).thenReturn(502);

        HttpResponse successResponse = mock(HttpResponse.class);
        when(successResponse.getStatusCode()).thenReturn(200);
        when(successResponse.isSuccessful()).thenReturn(true);
        when(successResponse.getBody())
                .thenReturn(
                        "{\"id\": \"chatcmpl-999\", \"object\": \"chat.completion\", \"created\":"
                                + " 0, \"model\": \"gpt-4\", \"choices\": [{\"message\": {\"role\":"
                                + " \"assistant\", \"content\": \"response\"}, \"finish_reason\":"
                                + " \"stop\"}], \"usage\": {\"prompt_tokens\": 10,"
                                + " \"completion_tokens\": 5, \"total_tokens\": 15}}");

        when(transport.execute(any(HttpRequest.class)))
                .thenReturn(gatewayErrorResponse)
                .thenReturn(gatewayErrorResponse)
                .thenReturn(successResponse);

        OpenAIClient client = new OpenAIClient(transport, "test-key", null);
        OpenAIRequest request = new OpenAIRequest();
        request.setModel("gpt-4");
        request.setMessages(new ArrayList<>());

        // Should retry twice and succeed on third attempt
        OpenAIResponse response = client.call(request);
        assertEquals("chatcmpl-999", response.getId());

        // Verify all three attempts were made
        verify(transport, times(3)).execute(any(HttpRequest.class));
    }

    @Test
    void testRetryAfterHeaderIn429Response() {
        HttpTransport transport = mock(HttpTransport.class);

        // First call returns 429 with Retry-After: 2 (seconds)
        HttpResponse rateLimitedResponse = mock(HttpResponse.class);
        when(rateLimitedResponse.getStatusCode()).thenReturn(429);
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Retry-After", "2");
        when(rateLimitedResponse.getHeaders()).thenReturn(headers);

        // Second call succeeds
        HttpResponse successResponse = mock(HttpResponse.class);
        when(successResponse.getStatusCode()).thenReturn(200);
        when(successResponse.isSuccessful()).thenReturn(true);
        when(successResponse.getBody())
                .thenReturn(
                        "{\"id\": \"chatcmpl-retry-after\", \"object\": \"chat.completion\","
                                + " \"created\": 0, \"model\": \"gpt-4\", \"choices\":"
                                + " [{\"message\": {\"role\": \"assistant\", \"content\":"
                                + " \"response\"}, \"finish_reason\": \"stop\"}], \"usage\":"
                                + " {\"prompt_tokens\": 10, \"completion_tokens\": 5,"
                                + " \"total_tokens\": 15}}");

        when(transport.execute(any(HttpRequest.class)))
                .thenReturn(rateLimitedResponse)
                .thenReturn(successResponse);

        OpenAIClient client = new OpenAIClient(transport, "test-key", null);
        OpenAIRequest request = new OpenAIRequest();
        request.setModel("gpt-4");
        request.setMessages(new ArrayList<>());

        long startTime = System.currentTimeMillis();
        OpenAIResponse response = client.call(request);
        long endTime = System.currentTimeMillis();
        long elapsed = endTime - startTime;

        assertEquals("chatcmpl-retry-after", response.getId());

        // Verify retry occurred (execute called twice)
        verify(transport, times(2)).execute(any(HttpRequest.class));

        // Verify that we waited at least close to 2 seconds (allowing some margin for test
        // execution)
        // Note: This is a best-effort check. In real scenarios, the Retry-After header should be
        // respected.
        assertTrue(
                elapsed >= 1500,
                "Should have waited at least ~2 seconds based on Retry-After header, but elapsed: "
                        + elapsed
                        + "ms");
    }

    @Test
    void testRetryAfterHeaderWithInvalidValue() {
        HttpTransport transport = mock(HttpTransport.class);

        // First call returns 429 with invalid Retry-After (not a number)
        HttpResponse rateLimitedResponse = mock(HttpResponse.class);
        when(rateLimitedResponse.getStatusCode()).thenReturn(429);
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Retry-After", "invalid-number");
        when(rateLimitedResponse.getHeaders()).thenReturn(headers);

        // Second call succeeds
        HttpResponse successResponse = mock(HttpResponse.class);
        when(successResponse.getStatusCode()).thenReturn(200);
        when(successResponse.isSuccessful()).thenReturn(true);
        when(successResponse.getBody())
                .thenReturn(
                        "{\"id\": \"chatcmpl-fallback\", \"object\": \"chat.completion\","
                                + " \"created\": 0, \"model\": \"gpt-4\", \"choices\":"
                                + " [{\"message\": {\"role\": \"assistant\", \"content\":"
                                + " \"response\"}, \"finish_reason\": \"stop\"}], \"usage\":"
                                + " {\"prompt_tokens\": 10, \"completion_tokens\": 5,"
                                + " \"total_tokens\": 15}}");

        when(transport.execute(any(HttpRequest.class)))
                .thenReturn(rateLimitedResponse)
                .thenReturn(successResponse);

        OpenAIClient client = new OpenAIClient(transport, "test-key", null);
        OpenAIRequest request = new OpenAIRequest();
        request.setModel("gpt-4");
        request.setMessages(new ArrayList<>());

        // Should fall back to exponential backoff when Retry-After is invalid
        OpenAIResponse response = client.call(request);
        assertEquals("chatcmpl-fallback", response.getId());

        // Verify retry occurred
        verify(transport, times(2)).execute(any(HttpRequest.class));
    }

    @Test
    void testRetryAfterHeaderMissing() {
        HttpTransport transport = mock(HttpTransport.class);

        // First call returns 429 without Retry-After header
        HttpResponse rateLimitedResponse = mock(HttpResponse.class);
        when(rateLimitedResponse.getStatusCode()).thenReturn(429);
        when(rateLimitedResponse.getHeaders()).thenReturn(new java.util.HashMap<>());

        // Second call succeeds
        HttpResponse successResponse = mock(HttpResponse.class);
        when(successResponse.getStatusCode()).thenReturn(200);
        when(successResponse.isSuccessful()).thenReturn(true);
        when(successResponse.getBody())
                .thenReturn(
                        "{\"id\": \"chatcmpl-no-header\", \"object\": \"chat.completion\","
                                + " \"created\": 0, \"model\": \"gpt-4\", \"choices\":"
                                + " [{\"message\": {\"role\": \"assistant\", \"content\":"
                                + " \"response\"}, \"finish_reason\": \"stop\"}], \"usage\":"
                                + " {\"prompt_tokens\": 10, \"completion_tokens\": 5,"
                                + " \"total_tokens\": 15}}");

        when(transport.execute(any(HttpRequest.class)))
                .thenReturn(rateLimitedResponse)
                .thenReturn(successResponse);

        OpenAIClient client = new OpenAIClient(transport, "test-key", null);
        OpenAIRequest request = new OpenAIRequest();
        request.setModel("gpt-4");
        request.setMessages(new ArrayList<>());

        // Should fall back to exponential backoff when Retry-After is missing
        OpenAIResponse response = client.call(request);
        assertEquals("chatcmpl-no-header", response.getId());

        // Verify retry occurred
        verify(transport, times(2)).execute(any(HttpRequest.class));
    }
}
