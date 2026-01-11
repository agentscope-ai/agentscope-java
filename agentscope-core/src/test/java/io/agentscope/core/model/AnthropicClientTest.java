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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.formatter.anthropic.dto.AnthropicContent;
import io.agentscope.core.formatter.anthropic.dto.AnthropicMessage;
import io.agentscope.core.formatter.anthropic.dto.AnthropicRequest;
import io.agentscope.core.formatter.anthropic.dto.AnthropicResponse;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Unit tests for {@link AnthropicClient}.
 */
class AnthropicClientTest {

    private static final String TEST_API_KEY = "test-api-key";
    private static final String TEST_BASE_URL = "https://test.api.anthropic.com";

    private HttpTransport mockTransport;
    private AnthropicClient client;

    @BeforeEach
    void setUp() {
        mockTransport = mock(HttpTransport.class);
        client = new AnthropicClient(mockTransport);
    }

    @Test
    void testConstructorWithDefaultTransport() {
        AnthropicClient defaultClient = new AnthropicClient();
        assertNotNull(defaultClient.getTransport());
    }

    @Test
    void testGetTransport() {
        assertEquals(mockTransport, client.getTransport());
    }

    @Test
    void testSuccessfulCall() throws Exception {
        // Arrange
        AnthropicRequest request = createTestRequest();
        String responseBody = createSuccessResponseJson();

        HttpResponse mockResponse = mock(HttpResponse.class);
        when(mockResponse.isSuccessful()).thenReturn(true);
        when(mockResponse.getStatusCode()).thenReturn(200);
        when(mockResponse.getBody()).thenReturn(responseBody);

        when(mockTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

        // Act
        AnthropicResponse response = client.call(TEST_API_KEY, null, request);

        // Assert
        assertNotNull(response);
        assertEquals("msg_123", response.getId());
        assertEquals(1, response.getContent().size());
        assertEquals("Hello, world!", response.getContent().get(0).getText());
        assertEquals(100, response.getUsage().getInputTokens());
        assertEquals(50, response.getUsage().getOutputTokens());

        // Verify request was built correctly
        verify(mockTransport).execute(any(HttpRequest.class));
    }

    @Test
    void testCallWithCustomBaseUrl() throws Exception {
        // Arrange
        AnthropicRequest request = createTestRequest();
        String responseBody = createSuccessResponseJson();

        HttpResponse mockResponse = mock(HttpResponse.class);
        when(mockResponse.isSuccessful()).thenReturn(true);
        when(mockResponse.getStatusCode()).thenReturn(200);
        when(mockResponse.getBody()).thenReturn(responseBody);

        when(mockTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

        // Act
        AnthropicResponse response = client.call(TEST_API_KEY, TEST_BASE_URL, request);

        // Assert
        assertNotNull(response);
        assertEquals("msg_123", response.getId());
    }

    @Test
    void testCallWithGenerateOptions() throws Exception {
        // Arrange
        AnthropicRequest request = createTestRequest();
        String responseBody = createSuccessResponseJson();

        HttpResponse mockResponse = mock(HttpResponse.class);
        when(mockResponse.isSuccessful()).thenReturn(true);
        when(mockResponse.getStatusCode()).thenReturn(200);
        when(mockResponse.getBody()).thenReturn(responseBody);

        when(mockTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

        GenerateOptions options =
                GenerateOptions.builder()
                        .apiKey("options-api-key")
                        .baseUrl("https://options.api.com")
                        .build();

        // Act
        AnthropicResponse response = client.call(TEST_API_KEY, TEST_BASE_URL, request, options);

        // Assert
        assertNotNull(response);
        assertEquals("msg_123", response.getId());
    }

    @Test
    void testCallWithNullRequest() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    client.call(TEST_API_KEY, null, null);
                });
    }

    @Test
    void testCallWithFailedResponse() throws Exception {
        // Arrange
        AnthropicRequest request = createTestRequest();
        String errorBody = "{\"error\":{\"message\":\"Invalid request\"}}";

        HttpResponse mockResponse = mock(HttpResponse.class);
        when(mockResponse.isSuccessful()).thenReturn(false);
        when(mockResponse.getStatusCode()).thenReturn(400);
        when(mockResponse.getBody()).thenReturn(errorBody);

        when(mockTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

        // Act & Assert
        ModelException exception =
                assertThrows(
                        ModelException.class,
                        () -> {
                            client.call(TEST_API_KEY, null, request);
                        });

        assertTrue(exception.getMessage().contains("400"));
        assertTrue(exception.getMessage().contains("Invalid request"));
    }

    @Test
    void testCallWithEmptyResponseBody() throws Exception {
        // Arrange
        AnthropicRequest request = createTestRequest();

        HttpResponse mockResponse = mock(HttpResponse.class);
        when(mockResponse.isSuccessful()).thenReturn(true);
        when(mockResponse.getStatusCode()).thenReturn(200);
        when(mockResponse.getBody()).thenReturn("");

        when(mockTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

        // Act & Assert
        ModelException exception =
                assertThrows(
                        ModelException.class,
                        () -> {
                            client.call(TEST_API_KEY, null, request);
                        });

        assertTrue(exception.getMessage().contains("empty response body"));
    }

    @Test
    void testCallWithInvalidJsonResponse() throws Exception {
        // Arrange
        AnthropicRequest request = createTestRequest();

        HttpResponse mockResponse = mock(HttpResponse.class);
        when(mockResponse.isSuccessful()).thenReturn(true);
        when(mockResponse.getStatusCode()).thenReturn(200);
        when(mockResponse.getBody()).thenReturn("invalid json");

        when(mockTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

        // Act & Assert
        ModelException exception =
                assertThrows(
                        ModelException.class,
                        () -> {
                            client.call(TEST_API_KEY, null, request);
                        });

        assertTrue(exception.getMessage().contains("Failed to parse"));
    }

    @Test
    void testStreamWithSuccess() throws Exception {
        // Arrange
        AnthropicRequest request = createTestRequest();
        String streamData =
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}";

        // HttpTransport.stream() should handle SSE parsing and return data without "data: " prefix
        when(mockTransport.stream(any(HttpRequest.class)))
                .thenReturn(Flux.just(streamData, "[DONE]"));

        // Act
        Flux<io.agentscope.core.formatter.anthropic.dto.AnthropicStreamEvent> eventFlux =
                client.stream(TEST_API_KEY, null, request, null);

        // Assert
        List<io.agentscope.core.formatter.anthropic.dto.AnthropicStreamEvent> events =
                eventFlux.collectList().block();
        assertNotNull(events);
        assertEquals(1, events.size());
        assertEquals("content_block_delta", events.get(0).getType());
    }

    @Test
    void testStreamWithNullRequest() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    client.stream(TEST_API_KEY, null, null, null);
                });
    }

    @Test
    void testStreamWithTransportError() throws Exception {
        // Arrange
        AnthropicRequest request = createTestRequest();

        when(mockTransport.stream(any(HttpRequest.class)))
                .thenReturn(Flux.error(new HttpTransportException("Connection failed")));

        // Act
        Flux<io.agentscope.core.formatter.anthropic.dto.AnthropicStreamEvent> eventFlux =
                client.stream(TEST_API_KEY, null, request, null);

        // Assert
        ModelException exception =
                assertThrows(
                        ModelException.class,
                        () -> {
                            eventFlux.blockFirst();
                        });

        assertTrue(exception.getMessage().contains("HTTP transport error"));
    }

    @Test
    void testDefaultConstants() {
        assertEquals("https://api.anthropic.com", AnthropicClient.DEFAULT_BASE_URL);
        assertEquals("/v1/messages", AnthropicClient.MESSAGES_ENDPOINT);
        assertEquals("2023-06-01", AnthropicClient.DEFAULT_API_VERSION);
    }

    @Test
    void testNormalizeBaseUrl() throws Exception {
        // We can't directly test the private method, but we can verify behavior
        // through the public API by using different base URLs
        AnthropicRequest request = createTestRequest();
        String responseBody = createSuccessResponseJson();

        HttpResponse mockResponse = mock(HttpResponse.class);
        when(mockResponse.isSuccessful()).thenReturn(true);
        when(mockResponse.getStatusCode()).thenReturn(200);
        when(mockResponse.getBody()).thenReturn(responseBody);

        when(mockTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

        // Test with trailing slash
        client.call(TEST_API_KEY, "https://api.example.com/", request);

        // Test without trailing slash
        client.call(TEST_API_KEY, "https://api.example.com", request);
    }

    private AnthropicRequest createTestRequest() {
        AnthropicRequest request = new AnthropicRequest();
        request.setModel("claude-3-5-sonnet-20241022");
        request.setMaxTokens(1024);

        AnthropicMessage message =
                new AnthropicMessage("user", List.of(AnthropicContent.text("Hello")));
        request.setMessages(List.of(message));

        return request;
    }

    private String createSuccessResponseJson() {
        return "{"
                + "\"id\":\"msg_123\","
                + "\"type\":\"message\","
                + "\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"Hello, world!\"}],"
                + "\"model\":\"claude-3-5-sonnet-20241022\","
                + "\"stop_reason\":\"end_turn\","
                + "\"usage\":{\"input_tokens\":100,\"output_tokens\":50}"
                + "}";
    }
}
