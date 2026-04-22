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
package io.agentscope.core.model.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TTSException.
 */
class TTSExceptionTest {

    @Test
    @DisplayName("should create exception with message only")
    void shouldCreateWithMessageOnly() {
        TTSException ex = new TTSException("Test error");

        assertEquals("Test error", ex.getMessage());
        assertNull(ex.getStatusCode());
        assertNull(ex.getErrorCode());
        assertNull(ex.getResponseBody());
        assertNull(ex.getCause());
    }

    @Test
    @DisplayName("should create exception with message and cause")
    void shouldCreateWithMessageAndCause() {
        RuntimeException cause = new RuntimeException("root cause");
        TTSException ex = new TTSException("Test error", cause);

        assertEquals("Test error", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertNull(ex.getStatusCode());
        assertNull(ex.getErrorCode());
        assertNull(ex.getResponseBody());
    }

    @Test
    @DisplayName("should create exception with HTTP status code")
    void shouldCreateWithStatusCode() {
        TTSException ex = new TTSException("HTTP error", 500, "{\"error\":\"server error\"}");

        assertEquals("HTTP error", ex.getMessage());
        assertEquals(500, ex.getStatusCode());
        assertEquals("{\"error\":\"server error\"}", ex.getResponseBody());
        assertNull(ex.getErrorCode());
    }

    @Test
    @DisplayName("should create exception with error code")
    void shouldCreateWithErrorCode() {
        TTSException ex =
                new TTSException(
                        "API error", "InvalidParameter", "{\"code\":\"InvalidParameter\"}");

        assertEquals("API error", ex.getMessage());
        assertEquals("InvalidParameter", ex.getErrorCode());
        assertEquals("{\"code\":\"InvalidParameter\"}", ex.getResponseBody());
        assertNull(ex.getStatusCode());
    }
}
