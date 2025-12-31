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
package io.agentscope.core.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for EmbeddingException.
 *
 * <p>Tests exception creation, message handling, and context information.
 */
@Tag("unit")
@DisplayName("EmbeddingException Unit Tests")
class EmbeddingExceptionTest {

    @Test
    @DisplayName("Should create exception with message only")
    void testExceptionWithMessage() {
        EmbeddingException exception = new EmbeddingException("Test error message");

        assertEquals("Test error message", exception.getMessage());
        assertNull(exception.getModelName());
        assertNull(exception.getProvider());
        assertNull(exception.getCause());
    }

    @Test
    @DisplayName("Should create exception with message and cause")
    void testExceptionWithMessageAndCause() {
        Throwable cause = new RuntimeException("Root cause");
        EmbeddingException exception = new EmbeddingException("Test error", cause);

        assertEquals("Test error", exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertNull(exception.getModelName());
        assertNull(exception.getProvider());
    }

    @Test
    @DisplayName("Should create exception with message, model name, and provider")
    void testExceptionWithContext() {
        EmbeddingException exception =
                new EmbeddingException("Test error", "text-embedding-v3", "dashscope");

        assertEquals("Test error", exception.getMessage());
        assertEquals("text-embedding-v3", exception.getModelName());
        assertEquals("dashscope", exception.getProvider());
        assertNull(exception.getCause());
    }

    @Test
    @DisplayName("Should create exception with all context information")
    void testExceptionWithFullContext() {
        Throwable cause = new RuntimeException("Root cause");
        EmbeddingException exception =
                new EmbeddingException("Test error", cause, "text-embedding-v3", "dashscope");

        assertEquals("Test error", exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertEquals("text-embedding-v3", exception.getModelName());
        assertEquals("dashscope", exception.getProvider());
    }

    @Test
    @DisplayName("Should be throwable and catchable")
    void testExceptionThrowable() {
        assertThrows(
                EmbeddingException.class,
                () -> {
                    throw new EmbeddingException("Test error");
                });
    }
}
