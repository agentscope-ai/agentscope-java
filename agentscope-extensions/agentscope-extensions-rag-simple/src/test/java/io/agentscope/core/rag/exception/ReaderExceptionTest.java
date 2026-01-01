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
package io.agentscope.core.rag.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ReaderException.
 */
@Tag("unit")
@DisplayName("ReaderException Unit Tests")
class ReaderExceptionTest {

    @Test
    @DisplayName("Should create exception with message only")
    void testExceptionWithMessage() {
        ReaderException exception = new ReaderException("Test error message");

        assertEquals("Test error message", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    @DisplayName("Should create exception with message and cause")
    void testExceptionWithMessageAndCause() {
        Throwable cause = new RuntimeException("Root cause");
        ReaderException exception = new ReaderException("Test error", cause);

        assertEquals("Test error", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    @Test
    @DisplayName("Should be throwable and catchable")
    void testExceptionThrowable() {
        assertThrows(
                ReaderException.class,
                () -> {
                    throw new ReaderException("Test error");
                });
    }
}
