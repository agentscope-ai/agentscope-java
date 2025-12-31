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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.model.exception.AuthenticationException;
import io.agentscope.core.model.exception.BadRequestException;
import io.agentscope.core.model.exception.InternalServerException;
import io.agentscope.core.model.exception.NotFoundException;
import io.agentscope.core.model.exception.PermissionDeniedException;
import io.agentscope.core.model.exception.RateLimitException;
import io.agentscope.core.model.exception.UnprocessableEntityException;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive exception handling tests for OpenAIClient.
 *
 * <p>Tests all HTTP status code error scenarios and proper exception mapping.
 */
@Tag("unit")
@DisplayName("OpenAIClient Exception Handling Tests")
class OpenAIClientExceptionTest {

    private HttpTransport transport;
    private OpenAIClient client;
    private OpenAIRequest request;
    private static final String TEST_API_KEY = "test-api-key";

    @BeforeEach
    void setUp() {
        transport = mock(HttpTransport.class);
        client = new OpenAIClient(transport);
        request = new OpenAIRequest();
        request.setModel("gpt-4");
        request.setMessages(new ArrayList<>());
    }

    @Test
    @DisplayName("Should throw BadRequestException for 400 status")
    void testBadRequestException() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(400);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getBody()).thenReturn("{\"error\": {\"message\": \"Bad request\"}}");

        when(transport.execute(any(HttpRequest.class))).thenReturn(response);

        BadRequestException exception =
                assertThrows(
                        BadRequestException.class, () -> client.call(TEST_API_KEY, null, request));
        assertEquals(400, exception.getStatusCode());
        assertNotNull(exception.getMessage());
    }

    @Test
    @DisplayName("Should throw AuthenticationException for 401 status")
    void testAuthenticationException() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(401);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getBody()).thenReturn("{\"error\": {\"message\": \"Invalid API key\"}}");

        when(transport.execute(any(HttpRequest.class))).thenReturn(response);

        AuthenticationException exception =
                assertThrows(
                        AuthenticationException.class,
                        () -> client.call(TEST_API_KEY, null, request));
        assertEquals(401, exception.getStatusCode());
        assertNotNull(exception.getMessage());
    }

    @Test
    @DisplayName("Should throw PermissionDeniedException for 403 status")
    void testPermissionDeniedException() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(403);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getBody()).thenReturn("{\"error\": {\"message\": \"Permission denied\"}}");

        when(transport.execute(any(HttpRequest.class))).thenReturn(response);

        PermissionDeniedException exception =
                assertThrows(
                        PermissionDeniedException.class,
                        () -> client.call(TEST_API_KEY, null, request));
        assertEquals(403, exception.getStatusCode());
        assertNotNull(exception.getMessage());
    }

    @Test
    @DisplayName("Should throw NotFoundException for 404 status")
    void testNotFoundException() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(404);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getBody()).thenReturn("{\"error\": {\"message\": \"Model not found\"}}");

        when(transport.execute(any(HttpRequest.class))).thenReturn(response);

        NotFoundException exception =
                assertThrows(
                        NotFoundException.class, () -> client.call(TEST_API_KEY, null, request));
        assertEquals(404, exception.getStatusCode());
        assertNotNull(exception.getMessage());
    }

    @Test
    @DisplayName("Should throw UnprocessableEntityException for 422 status")
    void testUnprocessableEntityException() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(422);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getBody()).thenReturn("{\"error\": {\"message\": \"Invalid parameters\"}}");

        when(transport.execute(any(HttpRequest.class))).thenReturn(response);

        UnprocessableEntityException exception =
                assertThrows(
                        UnprocessableEntityException.class,
                        () -> client.call(TEST_API_KEY, null, request));
        assertEquals(422, exception.getStatusCode());
        assertNotNull(exception.getMessage());
    }

    @Test
    @DisplayName("Should throw RateLimitException for 429 status")
    void testRateLimitException() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(429);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getBody()).thenReturn("{\"error\": {\"message\": \"Rate limit exceeded\"}}");

        when(transport.execute(any(HttpRequest.class))).thenReturn(response);

        RateLimitException exception =
                assertThrows(
                        RateLimitException.class, () -> client.call(TEST_API_KEY, null, request));
        assertEquals(429, exception.getStatusCode());
        assertNotNull(exception.getMessage());
    }

    @Test
    @DisplayName("Should throw InternalServerException for 500 status")
    void testInternalServerException() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(500);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getBody())
                .thenReturn("{\"error\": {\"message\": \"Internal server error\"}}");

        when(transport.execute(any(HttpRequest.class))).thenReturn(response);

        InternalServerException exception =
                assertThrows(
                        InternalServerException.class,
                        () -> client.call(TEST_API_KEY, null, request));
        assertEquals(500, exception.getStatusCode());
        assertNotNull(exception.getMessage());
    }

    @Test
    @DisplayName("Should throw InternalServerException for 503 status")
    void testServiceUnavailableException() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(503);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getBody()).thenReturn("{\"error\": {\"message\": \"Service unavailable\"}}");

        when(transport.execute(any(HttpRequest.class))).thenReturn(response);

        InternalServerException exception =
                assertThrows(
                        InternalServerException.class,
                        () -> client.call(TEST_API_KEY, null, request));
        assertEquals(503, exception.getStatusCode());
    }

    @Test
    @DisplayName("Should handle empty response body gracefully")
    void testEmptyResponseBodyException() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(200);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getBody()).thenReturn("");

        when(transport.execute(any(HttpRequest.class))).thenReturn(response);

        Exception exception =
                assertThrows(Exception.class, () -> client.call(TEST_API_KEY, null, request));
        assertNotNull(exception);
        assertNotNull(exception.getMessage());
    }

    @Test
    @DisplayName("Should handle null response body gracefully")
    void testNullResponseBodyException() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(200);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getBody()).thenReturn(null);

        when(transport.execute(any(HttpRequest.class))).thenReturn(response);

        Exception exception =
                assertThrows(Exception.class, () -> client.call(TEST_API_KEY, null, request));
        assertNotNull(exception);
    }

    @Test
    @DisplayName("Should handle malformed JSON response")
    void testMalformedJsonResponse() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(200);
        when(response.isSuccessful()).thenReturn(true);
        when(response.getBody()).thenReturn("{invalid json}");

        when(transport.execute(any(HttpRequest.class))).thenReturn(response);

        Exception exception =
                assertThrows(Exception.class, () -> client.call(TEST_API_KEY, null, request));
        assertNotNull(exception);
        assertNotNull(exception.getMessage());
    }

    @Test
    @DisplayName("Should handle error response with error details")
    void testErrorResponseWithErrorDetails() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(400);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getBody())
                .thenReturn(
                        "{\"error\": {\"message\": \"Invalid model\", \"code\":"
                                + " \"invalid_model\"}}");

        when(transport.execute(any(HttpRequest.class))).thenReturn(response);

        Exception exception =
                assertThrows(Exception.class, () -> client.call(TEST_API_KEY, null, request));
        assertNotNull(exception);
        assertNotNull(exception.getMessage());
    }

    @Test
    @DisplayName("Should handle response with null error field")
    void testResponseWithNullErrorField() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(400);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getBody()).thenReturn("{\"error\": null}");

        when(transport.execute(any(HttpRequest.class))).thenReturn(response);

        Exception exception =
                assertThrows(Exception.class, () -> client.call(TEST_API_KEY, null, request));
        assertNotNull(exception);
    }

    @Test
    @DisplayName("Should handle null request")
    void testNullRequestException() {
        assertThrows(NullPointerException.class, () -> client.call(TEST_API_KEY, null, null));
    }

    @Test
    @DisplayName("Should provide status code in exception")
    void testExceptionContainsStatusCode() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(401);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getBody()).thenReturn("{\"error\": {\"message\": \"Unauthorized\"}}");

        when(transport.execute(any(HttpRequest.class))).thenReturn(response);

        AuthenticationException exception =
                assertThrows(
                        AuthenticationException.class,
                        () -> client.call(TEST_API_KEY, null, request));
        assertEquals(401, exception.getStatusCode());
    }

    @Test
    @DisplayName("Should map correct exception type for each status code")
    void testExceptionTypeMapping() {
        testExceptionType(400, BadRequestException.class);
        testExceptionType(401, AuthenticationException.class);
        testExceptionType(403, PermissionDeniedException.class);
        testExceptionType(404, NotFoundException.class);
        testExceptionType(422, UnprocessableEntityException.class);
        testExceptionType(429, RateLimitException.class);
        testExceptionType(500, InternalServerException.class);
        testExceptionType(502, InternalServerException.class);
        testExceptionType(503, InternalServerException.class);
    }

    private void testExceptionType(int statusCode, Class<?> expectedExceptionType) {
        HttpTransport transport = mock(HttpTransport.class);
        OpenAIClient client = new OpenAIClient(transport);

        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(statusCode);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getBody())
                .thenReturn("{\"error\": {\"message\": \"Error for status " + statusCode + "\"}}");

        when(transport.execute(any(HttpRequest.class))).thenReturn(response);

        Exception exception =
                assertThrows(Exception.class, () -> client.call(TEST_API_KEY, null, request));
        assertInstanceOf(expectedExceptionType, exception);
    }

    @Test
    @DisplayName("Should include error message in exception")
    void testExceptionContainsErrorMessage() {
        String errorMessage = "Invalid API key";
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(401);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getBody())
                .thenReturn(
                        "{\"error\": {\"message\": \""
                                + errorMessage
                                + "\", \"code\": \"invalid_api_key\"}}");

        when(transport.execute(any(HttpRequest.class))).thenReturn(response);

        Exception exception =
                assertThrows(Exception.class, () -> client.call(TEST_API_KEY, null, request));
        assertNotNull(exception.getMessage());
    }
}
