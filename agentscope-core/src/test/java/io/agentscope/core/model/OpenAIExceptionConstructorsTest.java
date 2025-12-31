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
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.model.exception.OpenAIException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for OpenAIException constructors and getters.
 *
 * <p>Tests the single-argument constructor and getter methods
 * that were not covered in other test files.
 */
@Tag("unit")
@DisplayName("OpenAIException Constructor and Getter Tests")
class OpenAIExceptionConstructorsTest {

    @Test
    @DisplayName("Should create exception with message only")
    void testSingleArgConstructor() {
        String message = "Test error message";
        OpenAIException exception = new OpenAIException(message);

        assertEquals(message, exception.getMessage());
        assertNull(exception.getStatusCode());
        assertNull(exception.getErrorCode());
        assertNull(exception.getResponseBody());
    }

    @Test
    @DisplayName("Should return null status code from single arg constructor")
    void testGetStatusCodeNull() {
        OpenAIException exception = new OpenAIException("message");
        assertNull(exception.getStatusCode());
    }

    @Test
    @DisplayName("Should return null error code from single arg constructor")
    void testGetErrorCodeNull() {
        OpenAIException exception = new OpenAIException("message");
        assertNull(exception.getErrorCode());
    }

    @Test
    @DisplayName("Should return null response body from single arg constructor")
    void testGetResponseBodyNull() {
        OpenAIException exception = new OpenAIException("message");
        assertNull(exception.getResponseBody());
    }

    @Test
    @DisplayName("Should create exception with message and cause")
    void testMessageAndCauseConstructor() {
        String message = "Test error";
        Throwable cause = new RuntimeException("Root cause");
        OpenAIException exception = new OpenAIException(message, cause);

        assertEquals(message, exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertNull(exception.getStatusCode());
        assertNull(exception.getErrorCode());
        assertNull(exception.getResponseBody());
    }

    @Test
    @DisplayName("Should create exception with message, status code, and response body")
    void testMessageStatusCodeBodyConstructor() {
        String message = "Test error";
        int statusCode = 400;
        String responseBody = "{\"error\": \"bad request\"}";
        OpenAIException exception = new OpenAIException(message, statusCode, responseBody);

        assertEquals(message, exception.getMessage());
        assertEquals(statusCode, exception.getStatusCode());
        assertEquals(responseBody, exception.getResponseBody());
        assertNull(exception.getErrorCode());
    }

    @Test
    @DisplayName("Should create exception with all parameters")
    void testFullConstructor() {
        String message = "Test error";
        int statusCode = 401;
        String errorCode = "invalid_api_key";
        String responseBody = "{\"error\": \"invalid key\"}";
        OpenAIException exception =
                new OpenAIException(message, statusCode, errorCode, responseBody);

        assertEquals(message, exception.getMessage());
        assertEquals(statusCode, exception.getStatusCode());
        assertEquals(errorCode, exception.getErrorCode());
        assertEquals(responseBody, exception.getResponseBody());
    }

    @Test
    @DisplayName("Should get status code from exception")
    void testGetStatusCode() {
        OpenAIException exception = new OpenAIException("error", 500, "server_error", "body");
        assertEquals(500, exception.getStatusCode());
    }

    @Test
    @DisplayName("Should get error code from exception")
    void testGetErrorCode() {
        OpenAIException exception = new OpenAIException("error", 400, "invalid_request", "body");
        assertEquals("invalid_request", exception.getErrorCode());
    }

    @Test
    @DisplayName("Should get response body from exception")
    void testGetResponseBody() {
        String body = "{\"error\": \"test\"}";
        OpenAIException exception = new OpenAIException("error", 400, "invalid_request", body);
        assertEquals(body, exception.getResponseBody());
    }

    @Test
    @DisplayName("Should create exception using factory method")
    void testFactoryMethod() {
        OpenAIException exception =
                OpenAIException.create(400, "Bad request", "invalid_request", "body");

        assertNotNull(exception);
        assertEquals("Bad request", exception.getMessage());
        assertEquals(400, exception.getStatusCode());
        assertEquals("invalid_request", exception.getErrorCode());
        assertEquals("body", exception.getResponseBody());
    }
}
