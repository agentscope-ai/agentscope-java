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
package io.agentscope.core.model.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for OpenAI exception subclasses.
 *
 * <p>Tests the various exception types that map to HTTP status codes.
 */
@Tag("unit")
@DisplayName("OpenAI Exception Subclasses Tests")
class OpenAIExceptionSubclassesTest {

    @Test
    @DisplayName("Should create AuthenticationException with correct status code")
    void testAuthenticationException() {
        AuthenticationException ex =
                new AuthenticationException(
                        "Invalid API key", "invalid_api_key", "{\"error\": true}");

        assertEquals("Invalid API key", ex.getMessage());
        assertEquals(401, ex.getStatusCode());
        assertEquals("invalid_api_key", ex.getErrorCode());
        assertEquals("{\"error\": true}", ex.getResponseBody());
    }

    @Test
    @DisplayName("Should create BadRequestException with correct status code")
    void testBadRequestException() {
        BadRequestException ex =
                new BadRequestException("Invalid request", "invalid_request", "{\"error\": true}");

        assertEquals("Invalid request", ex.getMessage());
        assertEquals(400, ex.getStatusCode());
        assertEquals("invalid_request", ex.getErrorCode());
        assertEquals("{\"error\": true}", ex.getResponseBody());
    }

    @Test
    @DisplayName("Should create InternalServerException with correct status code")
    void testInternalServerException() {
        InternalServerException ex =
                new InternalServerException(
                        "Server error", 500, "server_error", "{\"error\": true}");

        assertEquals("Server error", ex.getMessage());
        assertEquals(500, ex.getStatusCode());
        assertEquals("server_error", ex.getErrorCode());
        assertEquals("{\"error\": true}", ex.getResponseBody());
    }

    @Test
    @DisplayName("Should create NotFoundException with correct status code")
    void testNotFoundException() {
        NotFoundException ex =
                new NotFoundException("Resource not found", "not_found", "{\"error\": true}");

        assertEquals("Resource not found", ex.getMessage());
        assertEquals(404, ex.getStatusCode());
        assertEquals("not_found", ex.getErrorCode());
        assertEquals("{\"error\": true}", ex.getResponseBody());
    }

    @Test
    @DisplayName("Should create PermissionDeniedException with correct status code")
    void testPermissionDeniedException() {
        PermissionDeniedException ex =
                new PermissionDeniedException(
                        "Permission denied", "permission_denied", "{\"error\": true}");

        assertEquals("Permission denied", ex.getMessage());
        assertEquals(403, ex.getStatusCode());
        assertEquals("permission_denied", ex.getErrorCode());
        assertEquals("{\"error\": true}", ex.getResponseBody());
    }

    @Test
    @DisplayName("Should create RateLimitException with correct status code")
    void testRateLimitException() {
        RateLimitException ex =
                new RateLimitException("Rate limit exceeded", "rate_limit", "{\"error\": true}");

        assertEquals("Rate limit exceeded", ex.getMessage());
        assertEquals(429, ex.getStatusCode());
        assertEquals("rate_limit", ex.getErrorCode());
        assertEquals("{\"error\": true}", ex.getResponseBody());
    }

    @Test
    @DisplayName("Should create UnprocessableEntityException with correct status code")
    void testUnprocessableEntityException() {
        UnprocessableEntityException ex =
                new UnprocessableEntityException(
                        "Unprocessable entity", "unprocessable", "{\"error\": true}");

        assertEquals("Unprocessable entity", ex.getMessage());
        assertEquals(422, ex.getStatusCode());
        assertEquals("unprocessable", ex.getErrorCode());
        assertEquals("{\"error\": true}", ex.getResponseBody());
    }

    @Test
    @DisplayName("Should verify exception types are correct")
    void testExceptionTypes() {
        AuthenticationException authEx = new AuthenticationException("msg", "code", "body");
        assertTrue(authEx instanceof OpenAIException);

        BadRequestException badRequestEx = new BadRequestException("msg", "code", "body");
        assertTrue(badRequestEx instanceof OpenAIException);

        InternalServerException serverEx = new InternalServerException("msg", 500, "code", "body");
        assertTrue(serverEx instanceof OpenAIException);

        NotFoundException notFoundEx = new NotFoundException("msg", "code", "body");
        assertTrue(notFoundEx instanceof OpenAIException);

        PermissionDeniedException permEx = new PermissionDeniedException("msg", "code", "body");
        assertTrue(permEx instanceof OpenAIException);

        RateLimitException rateEx = new RateLimitException("msg", "code", "body");
        assertTrue(rateEx instanceof OpenAIException);

        UnprocessableEntityException unprocessableEx =
                new UnprocessableEntityException("msg", "code", "body");
        assertTrue(unprocessableEx instanceof OpenAIException);
    }

    private void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected condition to be true");
        }
    }
}
