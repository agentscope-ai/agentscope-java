/*
 * Copyright 2024-2026 the original author or authors.
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.formatter.ollama.dto.OllamaEmbeddingRequest;
import io.agentscope.core.formatter.ollama.dto.OllamaEmbeddingResponse;
import io.agentscope.core.formatter.ollama.dto.OllamaRequest;
import io.agentscope.core.formatter.ollama.dto.OllamaResponse;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for OllamaHttpClient.
 */
@DisplayName("OllamaHttpClient Unit Tests")
class OllamaHttpClientTest {

    @Mock private HttpTransport mockTransport;

    private OllamaHttpClient httpClient;

    private static final String TEST_BASE_URL = "http://test.ollama.local:11434";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        httpClient = new OllamaHttpClient(mockTransport, TEST_BASE_URL);
    }

    @Test
    @DisplayName("Should create client with default base URL when null is provided")
    void testConstructorWithDefaultUrl() {
        OllamaHttpClient client = new OllamaHttpClient(mockTransport, null);
        assertEquals(OllamaHttpClient.DEFAULT_BASE_URL, client.getBaseUrl());
    }

    @Test
    @DisplayName("Should create client with provided base URL")
    void testConstructorWithProvidedUrl() {
        assertEquals(TEST_BASE_URL, httpClient.getBaseUrl());
    }

    @Test
    @DisplayName("Should create client with builder pattern")
    void testBuilderPattern() {
        OllamaHttpClient client =
                OllamaHttpClient.builder().baseUrl(TEST_BASE_URL).transport(mockTransport).build();

        assertEquals(TEST_BASE_URL, client.getBaseUrl());
    }

    @Test
    @DisplayName("Should make chat API call successfully")
    void testChatApiCall() throws Exception {
        // Arrange
        OllamaRequest request = new OllamaRequest();
        request.setModel("test-model");
        request.setMessages(Collections.emptyList());

        OllamaResponse expectedResponse = new OllamaResponse();
        expectedResponse.setModel("test-model");

        String responseBody = "{}"; // Serialized response
        HttpResponse httpResponse =
                HttpResponse.builder().statusCode(200).body(responseBody).build();

        when(mockTransport.execute(any(HttpRequest.class))).thenReturn(httpResponse);

        // Act
        OllamaResponse actualResponse = httpClient.chat(request);

        // Assert
        assertNotNull(actualResponse);
        verify(mockTransport).execute(any(HttpRequest.class));
    }

    @Test
    @DisplayName("Should make embed API call successfully")
    void testEmbedApiCall() throws Exception {
        // Arrange
        OllamaEmbeddingRequest request =
                new OllamaEmbeddingRequest("test-model", Arrays.asList("test input"));
        OllamaEmbeddingResponse expectedResponse = new OllamaEmbeddingResponse();

        String responseBody = "{}"; // Serialized response
        HttpResponse httpResponse =
                HttpResponse.builder().statusCode(200).body(responseBody).build();

        when(mockTransport.execute(any(HttpRequest.class))).thenReturn(httpResponse);

        // Act
        OllamaEmbeddingResponse actualResponse = httpClient.embed(request);

        // Assert
        assertNotNull(actualResponse);
        verify(mockTransport).execute(any(HttpRequest.class));
    }

    @Test
    @DisplayName("Should handle API call failure with non-success status code")
    void testApiCallFailure() throws Exception {
        // Arrange
        OllamaRequest request = new OllamaRequest();
        request.setModel("test-model");
        request.setMessages(Collections.emptyList());

        HttpResponse httpResponse =
                HttpResponse.builder().statusCode(500).body("Internal Server Error").build();

        when(mockTransport.execute(any(HttpRequest.class))).thenReturn(httpResponse);

        // Act & Assert
        OllamaHttpClient.OllamaHttpException exception =
                assertThrows(
                        OllamaHttpClient.OllamaHttpException.class, () -> httpClient.chat(request));

        assertTrue(exception.getMessage().contains("500"));
        assertEquals(Integer.valueOf(500), exception.getStatusCode());
        assertEquals("Internal Server Error", exception.getResponseBody());
    }

    @Test
    @DisplayName("Should handle JSON serialization error")
    void testJsonSerializationError() throws Exception {
        // Arrange
        OllamaRequest request = new OllamaRequest();
        request.setModel("test-model");
        request.setMessages(Collections.emptyList());

        doThrow(new RuntimeException("JSON serialization error"))
                .when(mockTransport)
                .execute(any(HttpRequest.class));

        // Act & Assert
        OllamaHttpClient.OllamaHttpException exception =
                assertThrows(
                        OllamaHttpClient.OllamaHttpException.class, () -> httpClient.chat(request));

        assertTrue(exception.getMessage().contains("JSON serialization error"));
    }

    @Test
    @DisplayName("Should handle transport exception")
    void testTransportException() throws Exception {
        // Arrange
        OllamaRequest request = new OllamaRequest();
        request.setModel("test-model");
        request.setMessages(Collections.emptyList());

        when(mockTransport.execute(any(HttpRequest.class)))
                .thenThrow(new HttpTransportException("Network error"));

        // Act & Assert
        OllamaHttpClient.OllamaHttpException exception =
                assertThrows(
                        OllamaHttpClient.OllamaHttpException.class, () -> httpClient.chat(request));

        assertTrue(exception.getMessage().contains("Network error"));
    }

    @Test
    @DisplayName("Should make generic API call with custom response type")
    void testGenericCall() throws Exception {
        // Arrange
        OllamaEmbeddingRequest request =
                new OllamaEmbeddingRequest("test-model", Arrays.asList("test input"));

        String responseBody = "{}"; // Serialized response
        HttpResponse httpResponse =
                HttpResponse.builder().statusCode(200).body(responseBody).build();

        when(mockTransport.execute(any(HttpRequest.class))).thenReturn(httpResponse);

        // Act
        OllamaEmbeddingResponse response =
                httpClient.call(
                        OllamaHttpClient.EMBED_ENDPOINT, request, OllamaEmbeddingResponse.class);

        // Assert
        assertNotNull(response);
        verify(mockTransport).execute(any(HttpRequest.class));
    }

    @Test
    @DisplayName("Should close transport when close is called")
    void testClose() {
        // Act
        httpClient.close();

        // Assert
        verify(mockTransport).close();
    }

    @Test
    @DisplayName("Should handle OllamaHttpException with message only")
    void testOllamaHttpExceptionWithMessageOnly() {
        OllamaHttpClient.OllamaHttpException exception =
                new OllamaHttpClient.OllamaHttpException("Test error");

        assertEquals("Test error", exception.getMessage());
        assertNull(exception.getStatusCode());
        assertNull(exception.getResponseBody());
    }

    @Test
    @DisplayName("Should handle OllamaHttpException with message and cause")
    void testOllamaHttpExceptionWithMessageAndCause() {
        Exception cause = new RuntimeException("Cause");
        OllamaHttpClient.OllamaHttpException exception =
                new OllamaHttpClient.OllamaHttpException("Test error", cause);

        assertEquals("Test error", exception.getMessage());
        assertNull(exception.getStatusCode());
        assertNull(exception.getResponseBody());
        assertEquals(cause, exception.getCause());
    }

    @Test
    @DisplayName("Should handle OllamaHttpException with status code and response body")
    void testOllamaHttpExceptionWithStatusCode() {
        OllamaHttpClient.OllamaHttpException exception =
                new OllamaHttpClient.OllamaHttpException("Test error", 500, "Server Error");

        assertEquals("Test error", exception.getMessage());
        assertEquals(Integer.valueOf(500), exception.getStatusCode());
        assertEquals("Server Error", exception.getResponseBody());
    }
}
